package com.example.loan;

import java.util.List;

import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ThinkingConfigParam;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * The write half of the decision layer: the shape an answer has to come back in, and the trace it
 * becomes once it does. It declares the schema on the way past, reads the verdict out of the
 * response, joins what the model chose to what the engine measured, and writes the decision to the
 * graph where the next run will read it as precedent.
 *
 * The write happens after the model answers, because there is no outcome until it does. That is
 * the whole flip: an earlier version computed the verdict in Java, committed it, and asked the
 * model to explain a conclusion it had no part in.
 *
 * Separate from {@link PrecedentAdvisor} because reading context and recording a decision are two
 * capabilities, not one. Each is a bean an agent adds or does not, they can be reasoned about and
 * tested apart, and a second agent that only wants the context adds the first without inheriting a
 * writer it has no decisions to give. Sharing an advisor is what tied them together; sharing
 * {@link LoanFile} is what keeps them apart.
 *
 * Java still owns two things the model cannot be trusted with, and neither is the decision. The
 * measurements are the engine's, so an edge cannot claim a number nothing measured. The cited ids
 * are filtered to the ones that were actually sent, so a citation cannot join the trace to
 * something that is not there.
 *
 * <p><strong>What this advisor returns.</strong> The text on the response is the letter to the
 * applicant, not the JSON the model emitted, for the reason {@link #applicantLetter} gives. The
 * structured verdict is not on the response text and is not reachable with {@code .entity()};
 * {@link #answerIn} is how a caller gets it.
 */
@Component
class DecisionTraceAdvisor implements CallAdvisor {

	/** Response context key the answer is handed back to the caller under. */
	static final String ANSWER = "loanAnswer";

	private final LoanGraph graph;

	/**
	 * Generates the schema from the record and converts the answer back, which is why the schema
	 * is declared here rather than one advisor further out: the shape of the answer belongs with
	 * the thing that reads it. Its default cleaner strips markdown fences, which a bare
	 * ObjectMapper would choke on. It is no defence against adaptive thinking: thinking never
	 * arrives as inline tags inside the text, it arrives as a content block of its own that
	 * becomes a Generation of its own, which is what {@link #verdictGeneration} is for.
	 */
	private final BeanOutputConverter<LoanVerdict> converter = new BeanOutputConverter<>(
			LoanVerdict.class);

	/**
	 * The repository rather than the {@link ChatMemory} wrapping it, because LoanOfficer builds
	 * that ChatMemory itself and depends on this advisor, so asking for it here would be a cycle.
	 * Used for one thing: undoing a question that never got an answer.
	 */
	private final ChatMemoryRepository chatMemoryRepository;

	DecisionTraceAdvisor(LoanGraph graph, ChatMemoryRepository chatMemoryRepository) {
		this.graph = graph;
		this.chatMemoryRepository = chatMemoryRepository;
	}

	@Override
	public String getName() {
		return "decisionTrace";
	}

	/**
	 * Outside the tool-calling loop, because an advisor placed under it is re-entered once per tool
	 * round trip and this one writes a decision every time it is entered. Inside
	 * {@link PrecedentAdvisor}, so the file it records the answer to is already on the request.
	 */
	@Override
	public int getOrder() {
		return ToolCallingAdvisor.DEFAULT_ORDER - 1;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		LoanFile file = PrecedentAdvisor.fileIn(request);

		ChatClientResponse response;
		Generation answered;
		LoanVerdict verdict;
		try {
			response = chain.nextCall(withSchema(request));
			answered = verdict(response);
			verdict = this.converter.convert(answered.getOutput().getText());
		}
		catch (RuntimeException ex) {
			// MessageChatMemoryAdvisor stored the question on its way in and adds the answer on
			// the way back out, which it never reaches if anything in here throws. Left alone, a
			// run that decided nothing leaves a Session behind holding half an exchange. The
			// rollback is here rather than one advisor out because this is the advisor whose
			// writes it is undoing.
			this.chatMemoryRepository.deleteByConversationId(file.conversationId());
			throw ex;
		}
		PolicyResult crossed = crossedLine(verdict, file.measurements());

		this.graph.saveDecision(file.companyId(), file.requestedAmount(), verdict,
				file.underwriter(), crossed, citedDenials(verdict, file.priorDenials()),
				file.conversationId());

		return applicantLetter(response, answered, new LoanAnswer(file.conversationId(),
				file.underwriter(), verdict, file.measurements(), crossed));
	}

	/**
	 * What this advisor decided, for the caller that asked. A typed accessor rather than a cast on
	 * a string key at the call site: the response text is the letter, so this is the only way to
	 * the verdict, and it should not be an unchecked cast in the caller to get there.
	 */
	static LoanAnswer answerIn(ChatClientResponse response) {
		if (!(response.context().get(ANSWER) instanceof LoanAnswer answer)) {
			throw new IllegalStateException(
					"DecisionTraceAdvisor did not run, so the file was never put in front of "
							+ "anyone and no verdict came back. Check that it is still registered "
							+ "as a default advisor on this ChatClient.");
		}
		return answer;
	}

	/**
	 * The schema the answer has to come back in, set on the request context rather than on the
	 * options directly: ChatModelCallAdvisor reads these two keys and mutates the options it
	 * already has, so the model pin and everything else from application.yaml survives, which
	 * building fresh options here would drop.
	 *
	 * OUTPUT_FORMAT is the fallback and is deliberately set even though the native path never
	 * reads it. ChatModelCallAdvisor only takes the native branch when the options implement
	 * StructuredOutputChatOptions, and falls through to appending this text when they do not. Left
	 * unset, that fallback appends the word "null" to the prompt.
	 *
	 * Thinking goes off on the way past too. That is a decision about the record rather than about
	 * the answer, and {@link #withoutThinking} is where it is argued.
	 */
	private ChatClientRequest withSchema(ChatClientRequest request) {
		return request.mutate()
			.prompt(request.prompt()
				.mutate()
				.chatOptions(withoutThinking(request.prompt().getOptions()))
				.build())
			.context(ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
					this.converter.getJsonSchema())
			.context(ChatClientAttributes.STRUCTURED_OUTPUT_NATIVE.getKey(), true)
			.context(ChatClientAttributes.OUTPUT_FORMAT.getKey(), this.converter.getFormat())
			.build();
	}

	/**
	 * Thinking off, because reading the right generation is only half the problem.
	 * {@link #verdictGeneration} picks the generation the answer is in; this is about what is
	 * inside that one generation.
	 *
	 * claude-sonnet-5 thinks adaptively unless told otherwise, and thinking interleaves with the
	 * answer rather than finishing before it. AnthropicChatModel accumulates every text block into
	 * one StringBuilder, so when the model pauses to think and then restarts its answer, the
	 * abandoned attempt and the real one arrive concatenated. The merged document still parses,
	 * because the junk lands inside a string value, which is how one run in four wrote a
	 * 996-character reason to the graph with fragments of a discarded draft inside it. Generation
	 * selection cannot help: the damage is within a single generation. A field that quietly
	 * absorbs an abandoned draft is worse than a run that fails outright, because it is stored,
	 * cited as precedent, and read back as though somebody meant it.
	 *
	 * It has to be Java. spring.ai.anthropic.chat.thinking cannot be bound from application.yaml
	 * because ThinkingConfigParam is an SDK union type the Boot binder cannot construct, and
	 * trying fails the whole startup with a message about a property named '-json'.
	 *
	 * mutate() rather than fresh options, for the same reason the schema goes on the request
	 * context: the model pin and everything else from application.yaml has to survive.
	 */
	private static ChatOptions withoutThinking(ChatOptions options) {
		if (options instanceof AnthropicChatOptions anthropic) {
			return anthropic.mutate()
				.thinking(ThinkingConfigParam
					.ofDisabled(ThinkingConfigDisabled.builder().build()))
				.build();
		}
		return options;
	}

	/**
	 * The model names the policy and the engine owns the measurement, so the two have to be joined
	 * here. A key that is not one of the policies measured below the line resolves to nothing and
	 * writes no edge, which covers both a null key and a model naming a policy that actually
	 * cleared. Letting Java substitute a policy the model did not choose would be putting the
	 * deciding back where this phase took it from.
	 */
	static PolicyResult crossedLine(LoanVerdict verdict, List<PolicyResult> measurements) {
		return measurements.stream()
			.filter(result -> !result.passed())
			.filter(result -> result.key().equals(verdict.decidingPolicyKey()))
			.findFirst()
			.orElse(null);
	}

	/**
	 * ESCALATED_FROM means this decision was reached over a standing denial, so the citations are
	 * kept only where they name one of the denials that were sent. That one filter drops an id the
	 * model invented and an approval it cited alike, and both would make the edge mean something
	 * other than what every read of it assumes.
	 */
	static List<String> citedDenials(LoanVerdict verdict, List<String> priorDenials) {
		if (verdict.citedDecisionIds() == null) {
			return List.of();
		}
		return verdict.citedDecisionIds().stream().distinct().filter(priorDenials::contains).toList();
	}

	/**
	 * The response, rebuilt down to the letter the applicant was sent. This is the advisor's
	 * contract and not an incidental tidy-up: what comes back from this ChatClient is prose, and
	 * the JSON never leaves this class.
	 *
	 * {@link MessageChatMemoryAdvisor} sits outside this advisor and stores whatever text came
	 * back, so left alone the transcript would hold the JSON verdict and print a blob where it
	 * prints prose today. Reordering cannot fix that: an advisor placed outside chat memory
	 * augments the request before memory sees it, and the transcript would hold the whole facts
	 * block instead.
	 *
	 * The cost is that {@code .entity()} and {@code .content()} on this client no longer report
	 * what the model emitted, which is why {@link #answerIn} exists and why the class javadoc says
	 * so out loud.
	 *
	 * The metadata is taken from the generation that carried the verdict rather than from the
	 * first one, for the same reason {@link #verdictGeneration} exists.
	 */
	private ChatClientResponse applicantLetter(ChatClientResponse response, Generation answered,
			LoanAnswer answer) {
		ChatResponse rebuilt = ChatResponse.builder()
			.from(response.chatResponse())
			.generations(List.of(new Generation(new AssistantMessage(answer.verdict().explanation()),
					answered.getMetadata())))
			.build();

		return response.mutate().chatResponse(rebuilt).context(ANSWER, answer).build();
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
	 * ChatResponse.getResult() returns the first. So getResult() is the thinking block: empty text
	 * and a signature. Reading it handed the converter an empty string and failed every live run
	 * while the model's answer sat in the generation behind it.
	 *
	 * Taking the last non-blank text is what the provider contract actually guarantees, and it
	 * holds whether the thinking block is empty or full. {@link #withoutThinking} turns thinking
	 * off, so on this configuration there is only ever one generation to choose from. This stays
	 * because it is the half that survives thinking being turned back on, and because the two
	 * cover different failures: that one is about what is inside a generation, this one is about
	 * which generation to read.
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
