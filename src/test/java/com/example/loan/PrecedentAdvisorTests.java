package com.example.loan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the model is handed, asserted without calling one. The draw is the half worth pinning down:
 * it decides who reads the file, so a run that drew differently on the second pass would leave a
 * reader unable to say whether the precedent or the person moved the outcome.
 */
class PrecedentAdvisorTests {

	private static final List<Underwriter> ROSTER = List.of(
			new Underwriter("U-1", "Dale Okafor", "Senior Underwriter", 14, "Dale",
					"Reads the history before the numbers."),
			new Underwriter("U-2", "Marta Reyes", "Underwriter", 6, "Marta",
					"Holds the line on the ratio."),
			new Underwriter("U-3", "Sam Whitlock", "Chief Credit Officer", 22, "Sam",
					"Has approved past a line before."));

	private static final Company COMPANY = new Company("C-1042", "Ridge Line Builders", 700,
			1_200_000, 2_000_000);

	private final LoanGraph graph = mock(LoanGraph.class);

	private final PrecedentAdvisor advisor = new PrecedentAdvisor(this.graph, new PolicyEngine());

	/**
	 * The same application draws the same person, which is what makes the second run a statement
	 * about the precedent that arrived in between and not about who happened to pick the file up.
	 */
	@Test
	void theSameApplicationDrawsTheSameUnderwriterEveryTime() {
		Underwriter first = PrecedentAdvisor.draw(ROSTER, "C-1042", 250_000);

		assertThat(PrecedentAdvisor.draw(ROSTER, "C-1042", 250_000)).isEqualTo(first);
		assertThat(PrecedentAdvisor.draw(ROSTER, "C-1042", 250_000)).isEqualTo(first);
	}

	/** Changing either half of the application is a different file, and can reach someone else. */
	@Test
	void aDifferentApplicationIsADifferentDraw() {
		assertThat(PrecedentAdvisor.draw(ROSTER, "C-1042", 250_000))
			.isEqualTo(PrecedentAdvisor.draw(ROSTER, "C-1042", 250_000));

		assertThat(List.of(PrecedentAdvisor.draw(ROSTER, "C-1042", 250_000),
				PrecedentAdvisor.draw(ROSTER, "C-1042", 900_000),
				PrecedentAdvisor.draw(ROSTER, "C-2071", 250_000)))
			.allMatch(ROSTER::contains);
	}

	/** Whoever is drawn is on the roster, whatever the hash of the application happens to be. */
	@Test
	void theDrawIsAlwaysSomebodyOnTheRoster() {
		for (long amount = 1; amount < 400; amount++) {
			assertThat(PrecedentAdvisor.draw(ROSTER, "C-1042", amount)).isIn(ROSTER);
			assertThat(PrecedentAdvisor.draw(List.of(ROSTER.get(0)), "C-1042", amount))
				.isEqualTo(ROSTER.get(0));
		}
	}

	/** An unseeded graph has nobody on duty, and that is not a file anyone can decide. */
	@Test
	void anEmptyRosterIsNotAFileAnyoneCanDecide() {
		assertThatIllegalStateException()
			.isThrownBy(() -> PrecedentAdvisor.draw(List.of(), "C-1042", 250_000))
			.withMessageContaining("nobody");
	}

	/**
	 * The file the advisor assembles carries the engine's measurements and the denials that were
	 * actually sent, which is what DecisionTraceAdvisor writes the trace from.
	 */
	@Test
	void theFileHandedDownstreamCarriesTheMeasurementsAndTheDenialsThatWereSent() {
		LoanFile file = adviseAndCaptureFile();

		assertThat(file.conversationId()).isEqualTo("c-1");
		assertThat(file.companyId()).isEqualTo("C-1042");
		assertThat(file.requestedAmount()).isEqualTo(250_000);
		assertThat(file.priorDenials()).containsExactly("D-real");
		assertThat(file.underwriter()).isIn(ROSTER);
		assertThat(file.measurements()).extracting(PolicyResult::key)
			.containsExactly(PolicyEngine.MINIMUM_CREDIT_SCORE, PolicyEngine.DEBT_TO_INCOME_LIMIT,
					PolicyEngine.REPEAT_DENIAL_ESCALATION);
	}

	/**
	 * The facts and the persona go on the user message, and nothing about the outcome goes with
	 * them: this advisor hands over a file, not a verdict.
	 */
	@Test
	void theFactsAndWhoIsReadingThemAreAppendedToTheQuestion() {
		String[] sent = new String[1];
		advise(request -> {
			sent[0] = request.prompt().getUserMessage().getText();
			return response();
		});

		assertThat(sent[0]).startsWith("Can C-1042 get a construction loan of $250,000?")
			.contains("Applicant: Ridge Line Builders (C-1042)")
			.contains("debtToIncomeLimit")
			.contains("Denials still counting against this company: D-real")
			.contains("Who is on duty today.")
			.doesNotContain("APPROVED")
			.doesNotContain("DENIED");
	}

	/** The window the history is read over comes off the Policy node, not out of this class. */
	@Test
	void theDenialWindowComesFromTheGraphRatherThanFromCode() {
		adviseAndCaptureFile();

		org.mockito.Mockito.verify(this.graph).findPriorDenials("C-1042", 18);
	}

	/** No Company is not a file with a missing field, it is nothing to decide about. */
	@Test
	void anApplicationForACompanyTheGraphDoesNotHoldIsNotAFile() {
		when(this.graph.findCompany("C-1042")).thenReturn(Optional.empty());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.advisor.adviseCall(request(), chain(request -> response())))
			.withMessageContaining("No company with id");
	}

	/** The two typed parameters are the contract with the caller, and a missing one says so. */
	@Test
	void aCallThatForgotTheAdvisorParametersSaysWhichOne() {
		ChatClientRequest bare = ChatClientRequest.builder()
			.prompt(new Prompt("Can C-1042 get a construction loan?"))
			.build();

		assertThatIllegalStateException()
			.isThrownBy(() -> this.advisor.adviseCall(bare, chain(request -> response())))
			.withMessageContaining("companyId");
	}

	private LoanFile adviseAndCaptureFile() {
		LoanFile[] captured = new LoanFile[1];
		advise(request -> {
			captured[0] = PrecedentAdvisor.fileIn(request);
			return response();
		});
		return captured[0];
	}

	private ChatClientResponse advise(Function<ChatClientRequest, ChatClientResponse> downstream) {
		when(this.graph.findCompany("C-1042")).thenReturn(Optional.of(COMPANY));
		when(this.graph.loadPolicies()).thenReturn(policies());
		when(this.graph.findPriorDenials(anyString(), anyLong())).thenReturn(List.of("D-real"));
		when(this.graph.findUnderwriters()).thenReturn(ROSTER);

		return this.advisor.adviseCall(request(), chain(downstream));
	}

	/** The request as the ChatClient builds it: the question, and the two typed parameters. */
	private static ChatClientRequest request() {
		return ChatClientRequest.builder()
			.prompt(new Prompt("Can C-1042 get a construction loan of $250,000?"))
			.context(Map.of(ChatMemory.CONVERSATION_ID, "c-1", PrecedentAdvisor.COMPANY_ID,
					"C-1042", PrecedentAdvisor.REQUESTED_AMOUNT, 250_000L))
			.build();
	}

	/** The three the engine requires, with the window Repeat Denial Escalation counts over. */
	private static Map<String, Policy> policies() {
		return Map.of(PolicyEngine.MINIMUM_CREDIT_SCORE,
				new Policy(PolicyEngine.MINIMUM_CREDIT_SCORE, "Minimum Credit Score", 620, 0, ""),
				PolicyEngine.DEBT_TO_INCOME_LIMIT,
				new Policy(PolicyEngine.DEBT_TO_INCOME_LIMIT, "Debt to Income Limit", 0.40, 0, ""),
				PolicyEngine.REPEAT_DENIAL_ESCALATION, new Policy(
						PolicyEngine.REPEAT_DENIAL_ESCALATION, "Repeat Denial Escalation", 2, 18,
						""));
	}

	private static ChatClientResponse response() {
		return ChatClientResponse.builder()
			.chatResponse(ChatResponse.builder()
				.generations(List.of(new Generation(new AssistantMessage("{}"))))
				.build())
			.build();
	}

	private static CallAdvisorChain chain(
			Function<ChatClientRequest, ChatClientResponse> downstream) {
		return new CallAdvisorChain() {
			@Override
			public ChatClientResponse nextCall(ChatClientRequest request) {
				return downstream.apply(request);
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

}
