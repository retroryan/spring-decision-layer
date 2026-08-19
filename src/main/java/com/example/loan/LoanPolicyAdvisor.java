package com.example.loan;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * The advisor that gives the model everything it needs to decide the loan, and then records what
 * it decided: read the company, its policies, and its standing denials out of the graph, hand
 * them over as facts rather than as a verdict, and write the answer back as a decision trace.
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

	private final LoanGraph graph;

	private final PolicyEngine engine;

	/**
	 * Generates the schema from the record and converts the answer back. Its default cleaner
	 * strips thinking tags and markdown fences, which a bare ObjectMapper would choke on:
	 * Sonnet 5 thinks adaptively unless told not to, so those tags can be there.
	 */
	private final BeanOutputConverter<LoanVerdict> converter = new BeanOutputConverter<>(
			LoanVerdict.class);

	LoanPolicyAdvisor(LoanGraph graph, PolicyEngine engine) {
		this.graph = graph;
		this.engine = engine;
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

		ChatClientResponse response = chain
			.nextCall(withFacts(request, company, requestedAmount, measurements, priorDenials));

		LoanVerdict verdict = this.converter.convert(text(response));
		PolicyResult crossed = crossedLine(verdict, measurements);

		this.graph.saveDecision(companyId, requestedAmount, verdict, crossed,
				citedDenials(verdict, priorDenials), string(request, ChatMemory.CONVERSATION_ID));

		return readable(response, new LoanAnswer(verdict, measurements, crossed));
	}

	/**
	 * The facts, plus the schema the answer has to come back in. The schema goes in the request
	 * context rather than on the options directly: ChatModelCallAdvisor reads these two keys and
	 * mutates the options it already has, so the model pin and everything else from
	 * application.yaml survives, which building fresh options here would drop.
	 *
	 * OUTPUT_FORMAT is the fallback and is deliberately set even though the native path never
	 * reads it. ChatModelCallAdvisor only takes the native branch when the options implement
	 * StructuredOutputChatOptions, and falls through to appending this text when they do not.
	 * Left unset, that fallback appends the word "null" to the prompt.
	 */
	private ChatClientRequest withFacts(ChatClientRequest request, Company company,
			long requestedAmount, List<PolicyResult> measurements, List<String> priorDenials) {

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

		Prompt augmented = request.prompt()
			.augmentUserMessage(userMessage -> userMessage.mutate()
				.text(userMessage.getText() + facts)
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
	 */
	private ChatClientResponse readable(ChatClientResponse response, LoanAnswer answer) {
		ChatResponse rebuilt = ChatResponse.builder()
			.from(response.chatResponse())
			.generations(List.of(new Generation(new AssistantMessage(answer.verdict().explanation()),
					response.chatResponse().getResult().getMetadata())))
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
	 * Every step down to the text is optional in the API, and any of them can be absent. Blank
	 * is not recoverable here the way it was when Java had already decided: there is no verdict
	 * to fall back on, so the run fails and the operator runs it again.
	 */
	private static String text(ChatClientResponse response) {
		if (response == null || response.chatResponse() == null
				|| response.chatResponse().getResult() == null) {
			throw new IllegalStateException("The model returned no result, so nothing decided this "
					+ "application and nothing was written. Run it again.");
		}
		String json = response.chatResponse().getResult().getOutput().getText();
		if (json == null || json.isBlank()) {
			throw new IllegalStateException("The model returned an empty answer where a verdict was "
					+ "expected. Run it again.");
		}
		return json;
	}

}
