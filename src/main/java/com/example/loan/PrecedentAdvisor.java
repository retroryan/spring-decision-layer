package com.example.loan;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * The read half of the decision layer: everything the graph knows that bears on this application,
 * assembled into the file the model is handed. The company and its numbers, the bank's policies
 * measured against them, the denials still counting, and which of the underwriters on the roster
 * is reading it.
 *
 * Facts and nothing else. Every earlier version of this block told the model what the outcome was,
 * and the point of this one is that it does not know until it decides. Nothing here writes, and
 * nothing here concludes.
 *
 * This is the advisor the talk points at when it says the context graph plugs in underneath an
 * agent: it adds retrieved context to a request on its way past and is otherwise invisible to the
 * agent above it, which never learns where any of it came from.
 *
 * @see DecisionTraceAdvisor for the write half, and for why the two are separate
 */
@Component
class PrecedentAdvisor implements CallAdvisor {

	static final String COMPANY_ID = "companyId";

	static final String REQUESTED_AMOUNT = "requestedAmount";

	/** Request context key the assembled file is handed down the chain under. */
	static final String FILE = "loanFile";

	/**
	 * Appended to the applicant's question. The key on the left is load bearing: the model copies
	 * it back as decidingPolicyKey and it is matched exactly, so the name is only there for the
	 * letter.
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
	 * system prompt. The identity varies by application, and a system prompt that varies
	 * invalidates the prompt cache. What does not vary stays in the system prompt: the role and
	 * how far off is too far.
	 */
	private static final String PERSONA = """

			---
			Who is on duty today. You are %s, %s, %d years on the job.

			%s
			""";

	private final LoanGraph graph;

	private final PolicyEngine engine;

	PrecedentAdvisor(LoanGraph graph, PolicyEngine engine) {
		this.graph = graph;
		this.engine = engine;
	}

	@Override
	public String getName() {
		return "precedent";
	}

	/**
	 * Outside the tool-calling loop, because an advisor placed under it is re-entered once per
	 * tool round trip and this one would read the graph again and append the file to a message
	 * that already has it. Inside MessageChatMemoryAdvisor, so the appended facts never reach the
	 * stored transcript.
	 *
	 * One place further out than {@link DecisionTraceAdvisor}, which is the whole ordering
	 * argument between the two: the file has to exist before the advisor that records the answer
	 * to it can read it off the request.
	 */
	@Override
	public int getOrder() {
		return ToolCallingAdvisor.DEFAULT_ORDER - 2;
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
		Underwriter underwriter = draw(this.graph.findUnderwriters(), companyId, requestedAmount);

		LoanFile file = new LoanFile(string(request, ChatMemory.CONVERSATION_ID), company,
				requestedAmount, measurements, priorDenials, underwriter);

		return chain.nextCall(withFile(request, file));
	}

	/**
	 * The file this advisor assembled, for the advisor downstream that records the answer to it.
	 * A typed accessor rather than a cast on a string key at the far end, so the contract between
	 * the two advisors is a method signature and the failure is a sentence instead of a
	 * ClassCastException.
	 */
	static LoanFile fileIn(ChatClientRequest request) {
		if (!(request.context().get(FILE) instanceof LoanFile file)) {
			throw new IllegalStateException("PrecedentAdvisor did not run, so no file was "
					+ "assembled and there is nothing to decide about. Check that it is still "
					+ "registered as a default advisor on this ChatClient.");
		}
		return file;
	}

	/**
	 * Who is on duty for this application, drawn from the roster in the graph and drawn the same
	 * way every time: the index comes from the file rather than from a random number, so running
	 * the same application again puts it in front of the same person.
	 *
	 * That is deliberate and it is the point of the second run. What changed between the two runs
	 * is the precedent that arrived in between, and a different underwriter on the second pass
	 * would leave a reader unable to say which of the two moved the outcome. Different
	 * applications still reach different people, which is where the variation between companies
	 * comes from.
	 *
	 * String.hashCode is specified rather than implementation defined and FIND_UNDERWRITERS is
	 * ordered by name, so the same application draws the same underwriter on any JVM against any
	 * seeded graph, which is what keeps the transcripts in the README honest.
	 */
	static Underwriter draw(List<Underwriter> roster, String companyId, long requestedAmount) {
		if (roster.isEmpty()) {
			throw new IllegalStateException("No Underwriter nodes in the graph, so there is nobody "
					+ "to put this file in front of. They are seeded from seed.json, so restart "
					+ "the app to seed them.");
		}
		int spread = 31 * companyId.hashCode() + Long.hashCode(requestedAmount);
		return roster.get(Math.floorMod(spread, roster.size()));
	}

	/**
	 * The facts and who is reading them, appended to the last user message. The facts because that
	 * is where they have always gone, and the persona because it varies per application and the
	 * system prompt is cached.
	 *
	 * The file also goes on the request context, which is how {@link DecisionTraceAdvisor} gets
	 * the measurements it needs to write an edge without measuring anything itself.
	 */
	private ChatClientRequest withFile(ChatClientRequest request, LoanFile file) {
		Company company = file.company();

		// The key leads, because decidingPolicyKey is matched against it exactly and the model
		// cannot be left to derive minimumCreditScore from "Minimum Credit Score". A near miss
		// there is silent: no policy edge is written, and the run prints "no policy named" as
		// though the underwriter had decided on the file as a whole.
		// Both columns are wide enough for the longest key and name in seed.json.
		String measured = file.measurements()
			.stream()
			.map(result -> "  %-24s %-26s %-15s %s".formatted(result.key(), result.name(),
					result.passed() ? "above the line" : "below the line", result.detail()))
			.collect(Collectors.joining("\n"));

		String facts = FACTS.formatted(company.name(), company.companyId(),
				company.creditRiskScore(), company.currentDebt(), company.annualIncome(),
				file.requestedAmount(), measured,
				file.priorDenials().isEmpty() ? "none" : String.join(", ", file.priorDenials()));

		Underwriter underwriter = file.underwriter();
		String persona = PERSONA.formatted(underwriter.name(), underwriter.title(),
				underwriter.yearsOnTheJob(), underwriter.disposition());

		// The persona last, so the file reads as a file and the person reading it comes after it,
		// which is the order a real one arrives in.
		Prompt augmented = request.prompt()
			.augmentUserMessage(userMessage -> userMessage.mutate()
				.text(userMessage.getText() + facts + persona)
				.build());

		return request.mutate().prompt(augmented).context(FILE, file).build();
	}

	/**
	 * The company and the amount arrive as typed advisor parameters rather than being parsed back
	 * out of the applicant's sentence, which would only add a way for the two to disagree.
	 *
	 * Reading the request is this advisor's half of the split, so the conversation id is read here
	 * too and carried on the file. It is read rather than injected because
	 * {@link MessageChatMemoryAdvisor}, further out in the chain, is what put it there.
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

}
