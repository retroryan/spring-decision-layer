package com.example.loan;

import java.util.List;

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
	 * ObjectMapper would choke on. This is the same {@link BeanOutputConverter} that
	 * {@code ChatClient.entity(...)} uses; the advisor runs it directly because it reads the
	 * verdict off the response rather than being the caller.
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

		LoanVerdict.Pardon exception = grantedException(verdict, file.priorDenials());
		if (exception != null) {
			this.graph.grantException(exception.decisionId(), exception.justification(),
					file.underwriter());
		}

		return applicantLetter(response, answered, new LoanAnswer(file.conversationId(),
				file.underwriter(), verdict, file.measurements(), crossed, exception));
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
	 * options directly: {@code ChatModelCallAdvisor} reads these keys and mutates the options it
	 * already has, so the model pin and everything else from application.properties survives, which
	 * building fresh options here would drop.
	 *
	 * Two keys and no more: the JSON schema, and the flag that turns on native enforcement. When
	 * STRUCTURED_OUTPUT_NATIVE is present, the schema has text, and the options implement
	 * StructuredOutputChatOptions (OpenAiChatOptions does), {@code ChatModelCallAdvisor} sets the
	 * schema as the request's {@code response_format} and leaves the prompt untouched. That is the
	 * same provider-native path {@code ChatClient.entity(type, spec ->
	 * spec.useProviderStructuredOutput())} takes; this advisor sets the keys itself only because it
	 * sits inside the chain rather than being the caller. OUTPUT_FORMAT, the prompt-appended
	 * fallback for options that are not StructuredOutputChatOptions, is deliberately not set: on
	 * this OpenAI configuration the native branch is always the one taken, so it would never be
	 * read.
	 */
	private ChatClientRequest withSchema(ChatClientRequest request) {
		return request.mutate()
			.context(ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
					this.converter.getJsonSchema())
			.context(ChatClientAttributes.STRUCTURED_OUTPUT_NATIVE.getKey(), true)
			.build();
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
	 * The one guardrail this design keeps: the decisionId on a granted exception has to be one of
	 * the denials that were actually sent in the facts block. Anything else is dropped rather than
	 * written, the same treatment an unsent citation gets, because an exception hanging off a
	 * decisionId the model invented is a broken graph rather than a judgement call. Independent
	 * of {@link LoanVerdict#outcome}: a denial today can still waive a denial from before it.
	 */
	static LoanVerdict.Pardon grantedException(LoanVerdict verdict, List<String> priorDenials) {
		LoanVerdict.Pardon exception = verdict.exception();
		if (exception == null || !priorDenials.contains(exception.decisionId())) {
			return null;
		}
		return exception;
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
	 * The metadata is taken from the same generation the verdict was read from.
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
	 * The generation carrying the verdict: the primary result, the same one
	 * {@code ChatClient.entity(...)} reads it out of (its {@code getContentFromChatResponse} is
	 * {@code getResult().getOutput().getText()}). On chat completions there is one generation, and
	 * native structured output puts the JSON in it. A blank or absent answer is not recoverable now
	 * that the verdict is the model's rather than something Java already decided, so the run fails
	 * and the operator runs it again.
	 */
	private static Generation verdict(ChatClientResponse response) {
		ChatResponse chatResponse = (response != null) ? response.chatResponse() : null;
		Generation result = (chatResponse != null) ? chatResponse.getResult() : null;
		if (result == null || result.getOutput() == null || result.getOutput().getText() == null
				|| result.getOutput().getText().isBlank()) {
			throw new IllegalStateException("The model returned an empty answer where a verdict was "
					+ "expected. Run it again.");
		}
		return result;
	}

}
