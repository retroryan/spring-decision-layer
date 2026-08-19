package com.example.loan;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * The advisor that decides the loan, so the model never has to: read the company and its
 * history out of the graph, compute PASS or FAIL for each policy in plain Java, commit the
 * decision, and only then hand the model a request that already contains the verdict.
 *
 * Committing before the call rather than after is deliberate: a decision is a fact the moment
 * it is computed, so a model that times out costs the sentence and nothing else.
 *
 * The conversation id is read back out of the request rather than injected, because the chat
 * memory advisor further out in the chain put it there.
 */
@Component
class LoanPolicyAdvisor implements CallAdvisor {

	static final String COMPANY_ID = "companyId";

	static final String REQUESTED_AMOUNT = "requestedAmount";

	/** Response context key the computed answer is handed back to the caller under. */
	static final String ANSWER = "loanAnswer";

	/**
	 * Appended to the applicant's question. Blunt on purpose: any softer framing invites the
	 * model to weigh in on whether the conclusion it was handed was right.
	 */
	private static final String VERDICT = """

			---
			The decision below has already been made by the bank's policy engine.
			It is final. Explain it to the applicant. Do not re-check it, do not
			second-guess it, and do not invent a different outcome.

			Outcome: %s
			Reason: %s
			Prior denied decisions on file: %d

			Policy checks:
			%s
			%s""";

	private final LoanGraph graph;

	private final PolicyEngine engine;

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
	 * tool round trip and this one commits a decision every time it is entered. Inside
	 * MessageChatMemoryAdvisor, so the appended verdict never reaches the stored transcript.
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
		// invariant, since there is no decision to compute without the Company.
		Company company = this.graph.findCompany(companyId)
			.orElseThrow(() -> new IllegalArgumentException("No company with id " + companyId));
		// Policies first: the window Repeat Denial Escalation counts over is a property on its
		// Policy node, so the graph decides how far back the next read looks.
		Map<String, Policy> policies = this.graph.loadPolicies();
		List<String> priorDenials = this.graph.findPriorDenials(companyId,
				this.engine.denialWindowMonths(policies));

		LoanDecision decision = this.engine.evaluate(company, requestedAmount, priorDenials.size(),
				policies);

		// The ESCALATED_FROM edge is written only when history is what decided, so its presence
		// means one thing: these earlier decisions caused this one. On any other outcome the
		// history was read and did not decide.
		List<String> causes = decision.decidedBy(PolicyEngine.REPEAT_DENIAL_ESCALATION)
				? priorDenials : List.of();

		String decisionId = this.graph.saveDecision(companyId, requestedAmount, decision, causes,
				string(request, ChatMemory.CONVERSATION_ID));

		ChatClientResponse response = chain.nextCall(withVerdict(request, decision));

		String prose = text(response);
		if (!prose.isBlank()) {
			this.graph.attachExplanation(decisionId, prose);
		}

		// Handed back so the caller prints the checklist from the same object the graph got,
		// rather than evaluating a second time for display.
		return response.mutate().context(ANSWER, new LoanAnswer(decision, prose)).build();
	}

	private ChatClientRequest withVerdict(ChatClientRequest request, LoanDecision decision) {
		String checks = decision.results()
			.stream()
			.map(result -> "  %s: %s (%s)".formatted(result.name(), result.passed() ? "PASS" : "FAIL",
					result.detail()))
			.collect(Collectors.joining("\n"));

		String deciding = decision.decidingPolicy() != null
				? "\nThe policy that decided the outcome: %s\n"
					.formatted(decision.decidingPolicy().name())
				: "";

		String verdict = VERDICT.formatted(decision.outcome(), decision.reason(),
				decision.priorDenials(), checks, deciding);

		Prompt augmented = request.prompt()
			.augmentUserMessage(userMessage -> userMessage.mutate()
				.text(userMessage.getText() + verdict)
				.build());

		return request.mutate().prompt(augmented).build();
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

	/** Every step down to the text is optional in the API, and any of them can be absent. */
	private static String text(ChatClientResponse response) {
		if (response == null || response.chatResponse() == null
				|| response.chatResponse().getResult() == null) {
			return "";
		}
		String prose = response.chatResponse().getResult().getOutput().getText();
		return prose != null ? prose : "";
	}

}
