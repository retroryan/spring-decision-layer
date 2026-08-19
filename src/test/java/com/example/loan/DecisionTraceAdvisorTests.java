package com.example.loan;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The invariants this advisor exists to hold, asserted without calling a model. What the model
 * sends back is a fixture here, which is the only way to test the cases that matter: a verdict
 * naming a policy that cleared, a verdict citing a decision that was never sent, and a run where
 * nothing came back at all.
 *
 * Java owns two things and neither is the decision. Both are checked below by watching what reaches
 * {@link LoanGraph#saveDecision}, because the graph is where a wrong answer to either would become
 * permanent.
 */
class DecisionTraceAdvisorTests {

	private static final String CONVERSATION = "c-1";

	private static final PolicyResult BELOW = new PolicyResult("debtToIncomeLimit",
			"Debt to Income Limit", false, 0.61, 0.40, "61% with this loan, must be under 40%");

	private static final PolicyResult ABOVE = new PolicyResult("minimumCreditScore",
			"Minimum Credit Score", true, 700, 620, "score 700, needs 620");

	private static final Underwriter UNDERWRITER = new Underwriter("U-1", "Dale Okafor",
			"Senior Underwriter", 14, "Dale", "Reads the history before the numbers.");

	private final LoanGraph graph = mock(LoanGraph.class);

	private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);

	private final DecisionTraceAdvisor advisor = new DecisionTraceAdvisor(this.graph,
			this.chatMemoryRepository);

	/**
	 * The model named a policy that measured above the line, which is the near miss that has to
	 * resolve to nothing: substituting the policy it should have named would put the deciding back
	 * in Java, and writing the edge anyway would claim a line stopped a loan that cleared it.
	 */
	@Test
	void aVerdictNamingAPolicyThatClearedWritesNoPolicyEdge() {
		LoanVerdict verdict = verdict("minimumCreditScore", List.of());

		assertThat(DecisionTraceAdvisor.crossedLine(verdict, List.of(ABOVE, BELOW))).isNull();
	}

	/** A key that is not a policy at all, which is what a model inventing one looks like. */
	@Test
	void aVerdictNamingAPolicyThatDoesNotExistWritesNoPolicyEdge() {
		LoanVerdict verdict = verdict("theVibes", List.of());

		assertThat(DecisionTraceAdvisor.crossedLine(verdict, List.of(ABOVE, BELOW))).isNull();
	}

	/** No key at all is legal: a denial can be reached on the pattern in a file. */
	@Test
	void aVerdictNamingNoPolicyWritesNoPolicyEdge() {
		LoanVerdict verdict = verdict(null, List.of());

		assertThat(DecisionTraceAdvisor.crossedLine(verdict, List.of(ABOVE, BELOW))).isNull();
	}

	/** The case that does write an edge, resolved to the engine's own measurement of it. */
	@Test
	void aVerdictNamingALineItCrossedResolvesToTheEnginesMeasurement() {
		LoanVerdict verdict = verdict("debtToIncomeLimit", List.of());

		assertThat(DecisionTraceAdvisor.crossedLine(verdict, List.of(ABOVE, BELOW)))
			.isSameAs(BELOW);
	}

	/**
	 * An id nothing sent, which would join the trace to a decision that has no bearing on it, or to
	 * nothing at all.
	 */
	@Test
	void aCitationThatWasNeverSentIsDropped() {
		LoanVerdict verdict = verdict(null, List.of("D-real", "D-invented"));

		assertThat(DecisionTraceAdvisor.citedDenials(verdict, List.of("D-real")))
			.containsExactly("D-real");
	}

	/** The same denial cited twice is one edge, because ESCALATED_FROM says one thing. */
	@Test
	void aDenialCitedTwiceBecomesOneEdge() {
		LoanVerdict verdict = verdict(null, List.of("D-real", "D-real"));

		assertThat(DecisionTraceAdvisor.citedDenials(verdict, List.of("D-real")))
			.containsExactly("D-real");
	}

	/** Nothing cited, which is what a file decided on its numbers alone looks like. */
	@Test
	void citingNothingIsNotAnEmptyStringOrANull() {
		assertThat(DecisionTraceAdvisor.citedDenials(verdict(null, null), List.of("D-real")))
			.isEmpty();
		assertThat(DecisionTraceAdvisor.citedDenials(verdict(null, List.of()), List.of("D-real")))
			.isEmpty();
	}

	/** An exception naming a denial that was actually sent survives the guardrail. */
	@Test
	void anExceptionNamingADenialThatWasSentIsGranted() {
		LoanVerdict.Pardon exception = new LoanVerdict.Pardon("D-real", "Since resolved.");
		LoanVerdict verdict = verdict(null, List.of(), exception);

		assertThat(DecisionTraceAdvisor.grantedException(verdict, List.of("D-real")))
			.isSameAs(exception);
	}

	/**
	 * An id nothing sent, the same failure {@link #aCitationThatWasNeverSentIsDropped} guards
	 * against: an exception hanging off a decisionId the model invented is a broken graph rather
	 * than a judgement call.
	 */
	@Test
	void anExceptionNamingADenialThatWasNeverSentIsDropped() {
		LoanVerdict.Pardon exception = new LoanVerdict.Pardon("D-invented", "Since resolved.");
		LoanVerdict verdict = verdict(null, List.of(), exception);

		assertThat(DecisionTraceAdvisor.grantedException(verdict, List.of("D-real"))).isNull();
	}

	/** No exception at all is the ordinary case, and it resolves to nothing rather than a throw. */
	@Test
	void noExceptionAtAllGrantsNothing() {
		LoanVerdict verdict = verdict(null, List.of());

		assertThat(DecisionTraceAdvisor.grantedException(verdict, List.of("D-real"))).isNull();
	}

	/**
	 * The same two filters again, this time through the advisor rather than against the methods,
	 * so what is asserted is what actually reaches the graph.
	 */
	@Test
	void theGraphIsWrittenWithTheEnginesNumbersAndOnlyTheCitationsThatWereSent() {
		ChatClientResponse response = advise(
				"""
						{"outcome":"DENIED","reason":"Too far off.",\
						"decidingPolicyKey":"minimumCreditScore",\
						"citedDecisionIds":["D-real","D-invented"],\
						"explanation":"We cannot approve this today.","confidence":"CLEAR"}""");

		ArgumentCaptor<PolicyResult> crossed = ArgumentCaptor.forClass(PolicyResult.class);
		ArgumentCaptor<List<String>> cited = ArgumentCaptor.captor();
		verify(this.graph).saveDecision(anyString(), anyLong(), any(), any(), crossed.capture(),
				cited.capture(), anyString());

		assertThat(crossed.getValue()).isNull();
		assertThat(cited.getValue()).containsExactly("D-real");
		assertThat(response).isNotNull();
	}

	/** An exception naming a denial the file actually carried is written to the graph. */
	@Test
	void anExceptionNamingASentDenialIsGrantedOnTheGraph() {
		advise("""
				{"outcome":"DENIED","reason":"Too far off.",\
				"citedDecisionIds":[],"explanation":"We cannot approve this today.",\
				"confidence":"CLEAR",\
				"exception":{"decisionId":"D-real","justification":"Since resolved."}}""");

		verify(this.graph).grantException("D-real", "Since resolved.", UNDERWRITER);
	}

	/** An exception naming a denial the file never carried is dropped rather than granted. */
	@Test
	void anExceptionNamingAnUnsentDenialIsNeverGranted() {
		advise("""
				{"outcome":"DENIED","reason":"Too far off.",\
				"citedDecisionIds":[],"explanation":"We cannot approve this today.",\
				"confidence":"CLEAR",\
				"exception":{"decisionId":"D-invented","justification":"Since resolved."}}""");

		verify(this.graph, never()).grantException(anyString(), anyString(), any());
	}

	/**
	 * The advisor's contract: what comes back is the letter the applicant was sent, because
	 * MessageChatMemoryAdvisor is outside this advisor and stores whatever text it finds. The JSON
	 * verdict never leaves the class.
	 */
	@Test
	void whatComesBackIsTheLetterAndNotTheJson() {
		ChatClientResponse response = advise("""
				{"outcome":"APPROVED","reason":"Clean history.",\
				"citedDecisionIds":[],"explanation":"Approved. Your history carried this.",\
				"confidence":"CLEAR"}""");

		assertThat(response.chatResponse().getResult().getOutput().getText())
			.isEqualTo("Approved. Your history carried this.")
			.doesNotContain("outcome");
	}

	/** And the verdict itself comes back on the context, typed, which is the way to it. */
	@Test
	void theVerdictComesBackOnTheContextRatherThanOnTheText() {
		ChatClientResponse response = advise("""
				{"outcome":"APPROVED","reason":"Clean history.",\
				"citedDecisionIds":[],"explanation":"Approved. Your history carried this.",\
				"confidence":"CLEAR"}""");

		LoanAnswer answer = DecisionTraceAdvisor.answerIn(response);

		assertThat(answer.verdict().approved()).isTrue();
		assertThat(answer.conversationId()).isEqualTo(CONVERSATION);
		assertThat(answer.underwriter()).isEqualTo(UNDERWRITER);
		assertThat(answer.measurements()).containsExactly(ABOVE, BELOW);
	}

	/** A caller reading a response this advisor never touched gets a sentence, not a cast failure. */
	@Test
	void aResponseFromAChainWithoutThisAdvisorSaysSo() {
		ChatClientResponse untouched = ChatClientResponse.builder()
			.chatResponse(chatResponse("anything"))
			.build();

		assertThatIllegalStateException().isThrownBy(() -> DecisionTraceAdvisor.answerIn(untouched))
			.withMessageContaining("did not run");
	}

	/**
	 * A run that decided nothing writes nothing, and it also has to undo the question chat memory
	 * stored on the way in. Left alone the Session keeps half an exchange, which the next read of
	 * the transcript would print as though someone had been asked and said nothing.
	 */
	@Test
	void aRunThatDecidedNothingWritesNothingAndLeavesNoHalfExchange() {
		CallAdvisorChain chain = chain(request -> {
			throw new IllegalStateException("the model fell over");
		});

		assertThatRuntimeException()
			.isThrownBy(() -> this.advisor.adviseCall(request(), chain))
			.withMessageContaining("fell over");

		verify(this.chatMemoryRepository).deleteByConversationId(CONVERSATION);
		verify(this.graph, never()).saveDecision(anyString(), anyLong(), any(), any(), any(), any(),
				anyString());
	}

	/** The same, for a response that came back empty rather than one that threw. */
	@Test
	void aRunWhereTheModelSaidNothingIsTheSameKindOfFailure() {
		CallAdvisorChain chain = chain(
				request -> ChatClientResponse.builder().chatResponse(chatResponse("")).build());

		assertThatIllegalStateException()
			.isThrownBy(() -> this.advisor.adviseCall(request(), chain))
			.withMessageContaining("empty answer");

		verify(this.chatMemoryRepository).deleteByConversationId(CONVERSATION);
		verify(this.graph, never()).saveDecision(anyString(), anyLong(), any(), any(), any(), any(),
				anyString());
	}

	/**
	 * A request that never passed through PrecedentAdvisor has no file on it, and this advisor
	 * fails before it calls anything rather than deciding about a company it was never given.
	 */
	@Test
	void aRequestWithNoFileOnItNeverReachesTheModel() {
		ChatClientRequest bare = ChatClientRequest.builder()
			.prompt(new Prompt("Can C-1042 get a construction loan?"))
			.build();

		assertThatIllegalStateException()
			.isThrownBy(() -> this.advisor.adviseCall(bare, chain(request -> {
				throw new AssertionError("the chain should not have been called");
			})))
			.withMessageContaining("PrecedentAdvisor did not run");

		verifyNoInteractions(this.graph, this.chatMemoryRepository);
	}

	/**
	 * Which generation the verdict is read out of, which nothing here could catch before, because
	 * nothing here calls the model. Every live run failed on it: claude-sonnet-5 thinks adaptively,
	 * AnthropicChatModel gives each thinking block a Generation of its own and appends the text one
	 * last, and ChatResponse.getResult() returns the first. The verdict was in the response the
	 * whole time, sitting behind an empty thinking block.
	 *
	 * The shapes below are the ones the API actually sent, taken off the wire: a thinking block
	 * whose text is empty and whose signature is in the properties, then the JSON.
	 */
	@Test
	void theVerdictIsReadFromTheGenerationBehindTheThinkingBlock() {
		List<Generation> generations = List.of(thinking(""), answer());

		assertThat(DecisionTraceAdvisor.verdictGeneration(generations).getOutput().getText())
			.isEqualTo(VERDICT);
	}

	/**
	 * Thinking that is not empty is the case a first-generation read would have appeared to
	 * survive, by handing the converter prose that parses as nothing.
	 */
	@Test
	void thinkingWithSomethingInItStillDoesNotWin() {
		List<Generation> generations = List.of(thinking("Let me weigh the debt ratio here."),
				answer());

		assertThat(DecisionTraceAdvisor.verdictGeneration(generations).getOutput().getText())
			.isEqualTo(VERDICT);
	}

	/** A redacted thinking block carries a marker and no text at all, so there is nothing to read. */
	@Test
	void aRedactedThinkingBlockIsSkippedTheSameWay() {
		Generation redacted = new Generation(
				AssistantMessage.builder().properties(Map.of("data", "EroBCk")).build());

		List<Generation> generations = List.of(redacted, answer());

		assertThat(DecisionTraceAdvisor.verdictGeneration(generations).getOutput().getText())
			.isEqualTo(VERDICT);
	}

	/** One generation and no thinking at all is the ordinary case, and it still works. */
	@Test
	void aResponseThatDidNotThinkIsReadTheSameWay() {
		assertThat(DecisionTraceAdvisor.verdictGeneration(List.of(answer())).getOutput().getText())
			.isEqualTo(VERDICT);
	}

	/**
	 * Nothing to fall back on, because the verdict is the model's now. The run fails and the
	 * operator runs it again, which is the honest answer when nobody decided anything.
	 */
	@Test
	void aResponseThatIsAllThinkingAndNoAnswerFailsTheRun() {
		assertThatIllegalStateException()
			.isThrownBy(() -> DecisionTraceAdvisor.verdictGeneration(List.of(thinking(""))))
			.withMessageContaining("empty answer");
	}

	@Test
	void aResponseWithNoGenerationsAtAllFailsTheRun() {
		assertThatIllegalStateException()
			.isThrownBy(() -> DecisionTraceAdvisor.verdictGeneration(List.of()))
			.withMessageContaining("empty answer");
	}

	/** Runs the advisor over a canned answer, the way the chain would with the file already on. */
	private ChatClientResponse advise(String json) {
		when(this.graph.saveDecision(anyString(), anyLong(), any(), any(), any(), any(),
				anyString()))
			.thenReturn("D-written");

		return this.advisor.adviseCall(request(),
				chain(request -> ChatClientResponse.builder()
					.chatResponse(chatResponse(json))
					.context(request.context())
					.build()));
	}

	/** A request as it leaves PrecedentAdvisor: the file on the context, the question on the prompt. */
	private static ChatClientRequest request() {
		LoanFile file = new LoanFile(CONVERSATION,
				new Company("C-1042", "Ridge Line Builders", 700, 1_200_000, 2_000_000), 250_000,
				List.of(ABOVE, BELOW), List.of("D-real"), UNDERWRITER);

		return ChatClientRequest.builder()
			.prompt(new Prompt("Can C-1042 get a construction loan of $250,000?"))
			.context(PrecedentAdvisor.FILE, file)
			.build();
	}

	/**
	 * The rest of the chain, as one function. Nothing downstream of this advisor is under test, so
	 * the only method that has to do anything is nextCall.
	 */
	private static CallAdvisorChain chain(
			java.util.function.Function<ChatClientRequest, ChatClientResponse> model) {
		return new CallAdvisorChain() {
			@Override
			public ChatClientResponse nextCall(ChatClientRequest request) {
				return model.apply(request);
			}

			@Override
			public List<CallAdvisor> getCallAdvisors() {
				return List.of();
			}

			@Override
			public CallAdvisorChain copy(CallAdvisor advisor) {
				return this;
			}
		};
	}

	private static ChatResponse chatResponse(String text) {
		return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text))))
			.build();
	}

	private static LoanVerdict verdict(String decidingPolicyKey, List<String> citedDecisionIds) {
		return verdict(decidingPolicyKey, citedDecisionIds, null);
	}

	private static LoanVerdict verdict(String decidingPolicyKey, List<String> citedDecisionIds,
			LoanVerdict.Pardon exception) {
		return new LoanVerdict(LoanVerdict.Outcome.DENIED, "Too far off.", decidingPolicyKey,
				citedDecisionIds, "We cannot approve this today.", LoanVerdict.Confidence.CLEAR,
				exception);
	}

	private static final String VERDICT = "{\"outcome\":\"DENIED\",\"reason\":\"Too far off.\"}";

	private static Generation thinking(String thought) {
		return new Generation(AssistantMessage.builder()
			.content(thought)
			.properties(Map.of("signature", "ErMLCpAB"))
			.build());
	}

	private static Generation answer() {
		return new Generation(new AssistantMessage(VERDICT));
	}

}
