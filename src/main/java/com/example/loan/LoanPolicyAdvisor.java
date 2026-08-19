package com.example.loan;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * The advisor that gives the model everything it needs to decide the loan, and then records what
 * it decided: read the company, its policies, and its standing denials out of the graph, draw one
 * of the underwriters on the roster, hand all of it over as facts rather than as a verdict, and
 * write the answer back as a decision trace joined to whoever drew the run.
 *
 * The write happens after the model answers, because there is no outcome until it does. That is
 * the whole flip: an earlier version computed the verdict in Java, committed it, and asked the
 * model to explain a conclusion it had no part in.
 *
 * Java still owns two things the model cannot be trusted with, and neither is the decision. The
 * measurements are the engine's, so an edge cannot claim a number nothing measured. The cited
 * ids are filtered to the ones that were actually sent, so a citation cannot join the trace to
 * something that is not there.
 *
 * The conversation id is read back out of the request rather than injected, because the chat
 * memory advisor further out in the chain put it there.
 */
@Component
class LoanPolicyAdvisor implements CallAdvisor {

	static final String COMPANY_ID = "companyId";

	static final String REQUESTED_AMOUNT = "requestedAmount";

	/** Response context key the answer is handed back to the caller under. */
	static final String ANSWER = "loanAnswer";

	/**
	 * Appended to the applicant's question. Facts and nothing else: every earlier version of
	 * this block told the model what the outcome was, and the point of this one is that it
	 * does not know until it decides.
	 */
	private static final String FACTS = """

			---
			The file in front of you.

			Applicant: %s (%s)
			  credit risk score  %d
			  current debt       $%,d
			  annual income      $%,d
			  asking for         $%,d

			The bank's policies, measured against this file. The key on the left is what
			decidingPolicyKey takes; the name beside it is what you call the policy when
			you write to the applicant.

			%s

			Denials still counting against this company: %s
			""";

	/**
	 * Who is on duty, appended to the last user message beside the facts rather than set on the
	 * system prompt. The identity changes on every run, and a system prompt that changes on every
	 * run invalidates the prompt cache on every run. What does not vary stays in the system
	 * prompt: the role and how far off is too far.
	 */
	private static final String PERSONA = """

			---
			Who is on duty today. You are %s, %s, %d years on the job.

			%s
			""";

	private final LoanGraph graph;

	private final PolicyEngine engine;

	/**
	 * Generates the schema from the record and converts the answer back. Its default cleaner
	 * strips markdown fences, which a bare ObjectMapper would choke on. It is no defence against
	 * adaptive thinking: thinking never arrives as inline tags inside the text, it arrives as a
	 * content block of its own that becomes a Generation of its own, which is what
	 * {@link #verdictGeneration} is for.
	 */
	private final BeanOutputConverter<LoanVerdict> converter = new BeanOutputConverter<>(
			LoanVerdict.class);

	/**
	 * The repository rather than the {@link ChatMemory} wrapping it, because LoanOfficer builds
	 * that ChatMemory itself and depends on this advisor, so asking for it here would be a cycle.
	 * Used for one thing: undoing a question that never got an answer.
	 */
	private final ChatMemoryRepository chatMemoryRepository;

	LoanPolicyAdvisor(LoanGraph graph, PolicyEngine engine,
			ChatMemoryRepository chatMemoryRepository) {
		this.graph = graph;
		this.engine = engine;
		this.chatMemoryRepository = chatMemoryRepository;
	}

	@Override
	public String getName() {
		return "loanPolicy";
	}

	/**
	 * Outside the tool-calling loop, because an advisor placed under it is re-entered once per
	 * tool round trip and this one writes a decision every time it is entered. Inside
	 * MessageChatMemoryAdvisor, so the appended facts never reach the stored transcript.
	 */
	@Override
	public int getOrder() {
		return ToolCallingAdvisor.DEFAULT_ORDER - 1;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		String companyId = string(request, COMPANY_ID);
		long requestedAmount = number(request, REQUESTED_AMOUNT);

		// Application gates on the same lookup with a friendlier message; this one is the
		// invariant, since there is nothing to decide about without the Company.
		Company company = this.graph.findCompany(companyId)
			.orElseThrow(() -> new IllegalArgumentException("No company with id " + companyId));
		// Policies first: the window Repeat Denial Escalation counts over is a property on its
		// Policy node, so the graph decides how far back the next read looks.
		Map<String, Policy> policies = this.graph.loadPolicies();
		List<String> priorDenials = this.graph.findPriorDenials(companyId,
				this.engine.denialWindowMonths(policies));
		List<PolicyResult> measurements = this.engine.measure(company, requestedAmount,
				priorDenials.size(), policies);
		Underwriter underwriter = draw();

		String conversationId = string(request, ChatMemory.CONVERSATION_ID);
		ChatClientResponse response;
		Generation answered;
		LoanVerdict verdict;
		try {
			response = chain.nextCall(withFacts(request, company, requestedAmount, measurements,
					priorDenials, underwriter));
			answered = verdict(response);
			verdict = this.converter.convert(answered.getOutput().getText());
		}
		catch (RuntimeException ex) {
			// MessageChatMemoryAdvisor stored the question on its way in and adds the answer on
			// the way back out, which it never reaches if anything in here throws. Left alone, a
			// run that decided nothing leaves a Session behind holding half an exchange.
			this.chatMemoryRepository.deleteByConversationId(conversationId);
			throw ex;
		}
		PolicyResult crossed = crossedLine(verdict, measurements);

		this.graph.saveDecision(companyId, requestedAmount, verdict, underwriter, crossed,
				citedDenials(verdict, priorDenials), conversationId);

		return readable(response, answered,
				new LoanAnswer(underwriter, verdict, measurements, crossed));
	}

	/**
	 * Who is on duty for this run, drawn at random out of the roster in the graph. There is no
	 * flag to pin it, on purpose: an underwriter chosen on the command line is a scripted outcome
	 * in the language of judgement, and it turns three people back into a dial with three
	 * settings. What the seed pins is the position; who answers it is drawn.
	 */
	private Underwriter draw() {
		List<Underwriter> roster = this.graph.findUnderwriters();
		if (roster.isEmpty()) {
			throw new IllegalStateException("No Underwriter nodes in the graph, so there is nobody "
					+ "to put this file in front of. They are seeded from seed.json, so restart "
					+ "the app to seed them.");
		}
		return roster.get(ThreadLocalRandom.current().nextInt(roster.size()));
	}

	/**
	 * The facts and who is reading them, plus the schema the answer has to come back in. Both
	 * blocks go on the last user message: the facts because that is where they have always gone,
	 * and the persona because it changes every run and the system prompt is cached.
	 *
	 * The schema goes in the request context rather than on the options directly:
	 * ChatModelCallAdvisor reads these two keys and mutates the options it already has, so the
	 * model pin and everything else from application.yaml survives, which building fresh options
	 * here would drop.
	 *
	 * OUTPUT_FORMAT is the fallback and is deliberately set even though the native path never
	 * reads it. ChatModelCallAdvisor only takes the native branch when the options implement
	 * StructuredOutputChatOptions, and falls through to appending this text when they do not.
	 * Left unset, that fallback appends the word "null" to the prompt.
	 */
	private ChatClientRequest withFacts(ChatClientRequest request, Company company,
			long requestedAmount, List<PolicyResult> measurements, List<String> priorDenials,
			Underwriter underwriter) {

		// The key leads, because decidingPolicyKey is matched against it exactly and the model
		// cannot be left to derive minimumCreditScore from "Minimum Credit Score". A near miss
		// there is silent: crossedLine resolves nothing, no policy edge is written, and the run
		// prints "no policy named" as though the underwriter had decided on the file as a whole.
		// Both columns are wide enough for the longest key and name in seed.json.
		String measured = measurements.stream()
			.map(result -> "  %-24s %-26s %-15s %s".formatted(result.key(), result.name(),
					result.passed() ? "above the line" : "below the line", result.detail()))
			.collect(Collectors.joining("\n"));

		String facts = FACTS.formatted(company.name(), company.companyId(),
				company.creditRiskScore(), company.currentDebt(), company.annualIncome(),
				requestedAmount, measured,
				priorDenials.isEmpty() ? "none" : String.join(", ", priorDenials));

		String persona = PERSONA.formatted(underwriter.name(), underwriter.title(),
				underwriter.yearsOnTheJob(), underwriter.disposition());

		// The persona last, so the file reads as a file and the person reading it comes after it,
		// which is the order a real one arrives in.
		Prompt augmented = request.prompt()
			.augmentUserMessage(userMessage -> userMessage.mutate()
				.text(userMessage.getText() + facts + persona)
				.build());

		return request.mutate()
			.prompt(augmented)
			.context(ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
					this.converter.getJsonSchema())
			.context(ChatClientAttributes.STRUCTURED_OUTPUT_NATIVE.getKey(), true)
			.context(ChatClientAttributes.OUTPUT_FORMAT.getKey(), this.converter.getFormat())
			.build();
	}

	/**
	 * The model names the policy and the engine owns the measurement, so the two have to be
	 * joined here. A key that is not one of the policies measured below the line resolves to
	 * nothing and writes no edge, which covers both a null key and a model naming a policy that
	 * actually cleared. Letting Java substitute a policy the model did not choose would be
	 * putting the deciding back where this phase took it from.
	 */
	private PolicyResult crossedLine(LoanVerdict verdict, List<PolicyResult> measurements) {
		return measurements.stream()
			.filter(result -> !result.passed())
			.filter(result -> result.key().equals(verdict.decidingPolicyKey()))
			.findFirst()
			.orElse(null);
	}

	/**
	 * ESCALATED_FROM means this decision was reached over a standing denial, so the citations
	 * are kept only where they name one of the denials that were sent. That one filter drops an
	 * id the model invented and an approval it cited alike, and both would make the edge mean
	 * something other than what every read of it assumes.
	 */
	private List<String> citedDenials(LoanVerdict verdict, List<String> priorDenials) {
		if (verdict.citedDecisionIds() == null) {
			return List.of();
		}
		return verdict.citedDecisionIds().stream().distinct().filter(priorDenials::contains).toList();
	}

	/**
	 * MessageChatMemoryAdvisor sits outside this advisor and stores whatever text came back, so
	 * left alone it would store the JSON verdict and the transcript would print a blob where it
	 * prints prose today. Rebuilt down to the explanation: chat memory stores the letter, and
	 * the JSON never leaves this advisor.
	 *
	 * The metadata is taken from the generation that carried the verdict rather than from the
	 * first one, for the same reason {@link #verdictGeneration} exists.
	 */
	private ChatClientResponse readable(ChatClientResponse response, Generation answered,
			LoanAnswer answer) {
		ChatResponse rebuilt = ChatResponse.builder()
			.from(response.chatResponse())
			.generations(List.of(new Generation(new AssistantMessage(answer.verdict().explanation()),
					answered.getMetadata())))
			.build();

		return response.mutate().chatResponse(rebuilt).context(ANSWER, answer).build();
	}

	/**
	 * The company and the amount arrive as typed advisor parameters rather than being parsed
	 * back out of the applicant's sentence, which would only add a way for the two to disagree.
	 */
	private String string(ChatClientRequest request, String key) {
		Object value = request.context().get(key);
		if (!(value instanceof String text) || text.isBlank()) {
			throw new IllegalStateException("Advisor parameter '" + key
					+ "' is missing. Pass it with .advisors(a -> a.param(...)) on the call.");
		}
		return text;
	}

	private long number(ChatClientRequest request, String key) {
		Object value = request.context().get(key);
		if (!(value instanceof Number amount)) {
			throw new IllegalStateException("Advisor parameter '" + key
					+ "' is missing or is not a number. Pass it with .advisors(a -> a.param(...)).");
		}
		return amount.longValue();
	}

	/**
	 * Every step down to the generations is optional in the API, and any of them can be absent.
	 */
	private static Generation verdict(ChatClientResponse response) {
		if (response == null || response.chatResponse() == null) {
			throw new IllegalStateException("The model returned no result, so nothing decided this "
					+ "application and nothing was written. Run it again.");
		}
		return verdictGeneration(response.chatResponse().getResults());
	}

	/**
	 * The generation carrying the verdict, which is not the first one on a model that thinks.
	 * claude-sonnet-5 thinks adaptively unless told otherwise, AnthropicChatModel gives every
	 * thinking block a Generation of its own and appends the text one last, and
	 * ChatResponse.getResult() returns the first. So getResult() is the thinking block: empty
	 * text and a signature. Reading it handed the converter an empty string and failed every
	 * live run while the model's answer sat in the generation behind it.
	 *
	 * Taking the last non-blank text is what the provider contract actually guarantees, and it
	 * holds whether the thinking block is empty or full. Disabling thinking would have worked
	 * too, and only in Java: spring.ai.anthropic.chat.thinking cannot be bound from YAML,
	 * because ThinkingConfigParam is an SDK union type the Boot binder cannot construct. This is
	 * the better half of that choice anyway, since it survives thinking being turned back on.
	 *
	 * Blank throughout is not recoverable the way it was when Java had already decided: there is
	 * no verdict to fall back on, so the run fails and the operator runs it again.
	 */
	static Generation verdictGeneration(List<Generation> generations) {
		Generation answered = null;
		if (generations != null) {
			for (Generation generation : generations) {
				String text = generation.getOutput().getText();
				if (text != null && !text.isBlank()) {
					answered = generation;
				}
			}
		}
		if (answered == null) {
			throw new IllegalStateException("The model returned an empty answer where a verdict was "
					+ "expected. Run it again.");
		}
		return answered;
	}

}
