package com.example.loan;

import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.neo4j.autoconfigure.Neo4jConnectionDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Everything one run prints, kept out of {@link Application} because where the
 * {@code @SpringBootApplication} class sits is what decides the component scan root, and no
 * column width is a good reason to edit that file.
 *
 * The format strings are the contract rather than an implementation detail:
 * {@code integration-tests/ExampleInfo.json} matches this output with regexes, so a heading
 * reworded here fails a check that nothing under {@code src/test} covers.
 *
 * The graph arrives as a parameter rather than as a field, so the order these are called in stays
 * {@link Application}'s decision. Two of them are printed after the decision is written on
 * purpose, and that only reads as deliberate where the calls are in one place.
 */
@Component
class DecisionConsole {

	private static final int SECTION_WIDTH = 76;

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
		.withZone(ZoneId.systemDefault());

	/** Which model the decision and follow-up sections are calling, named rather than left implicit. */
	private final String modelId;

	private final String modelHost;

	/** Which graph the context-graph sections are reading from, named for the same reason. */
	private final String graphHost;

	private final String graphDatabase;

	/**
	 * The connection details bean rather than {@code ${spring.neo4j.uri}}: the property is never
	 * bound under {@code TestApplication}, where a {@code @ServiceConnection} testcontainer
	 * supplies the bolt URL directly, and that is exactly how {@code run.sh} starts this app.
	 * {@link Neo4jConnectionDetails} is what {@link LoanGraph}'s {@code Driver} is built from
	 * either way, so reading it here shows the same graph the driver actually reaches.
	 */
	DecisionConsole(@Value("${spring.ai.openai.chat.model}") String modelId,
			@Value("${spring.ai.openai.base-url}") String modelBaseUrl,
			Neo4jConnectionDetails neo4jConnectionDetails,
			@Value("${loan.neo4j.database:}") String neo4jDatabase) {
		this.modelId = modelId;
		this.modelHost = host(modelBaseUrl);
		this.graphHost = host(String.valueOf(neo4jConnectionDetails.getUri()));
		// Left unset, LoanGraph falls back to the server's home database, which is "neo4j" on a
		// default install; named here to match, not to duplicate LoanGraph's own fallback logic.
		this.graphDatabase = StringUtils.hasText(neo4jDatabase) ? neo4jDatabase : "neo4j";
	}

	private static String host(String uri) {
		try {
			return URI.create(uri).getHost();
		}
		catch (RuntimeException ex) {
			return uri;
		}
	}

	private String modelDetail() {
		return "Model: %s (%s)".formatted(this.modelId, this.modelHost);
	}

	private String graphDetail() {
		return "Graph: %s, database %s".formatted(this.graphHost, this.graphDatabase);
	}

	/**
	 * The demo is read from a terminal, where a pause before an external call or a graph traversal
	 * otherwise looks exactly like a hang. Each major stage therefore introduces both what is about
	 * to happen and what the following rows mean.
	 */
	void printRunStart(String companyId, long requestedAmount) {
		printSection("LOAN DECISION DEMO",
				"Preparing %s's request for $%,d and checking the context graph."
					.formatted(companyId, requestedAmount), graphDetail());
	}

	/** Printed immediately before the first model call, after the graph context has been shown. */
	void printDecisionModelCall() {
		printSection("MODEL CALL: UNDERWRITING THE FILE",
				"Sending the file, policy measurements, and standing precedent to the model.",
				modelDetail());
	}

	/**
	 * The ids come from the graph rather than a constant, so this cannot suggest the id that just
	 * failed. An empty graph is a different mistake and gets a different answer: --no-seed skips
	 * seeding, and on a graph that was never seeded there is nothing at all to decide about.
	 */
	void printUnknownCompany(LoanGraph graph, String companyId) {
		List<String> known = graph.findCompanyIds();
		if (known.isEmpty()) {
			System.out.printf("No companies in the graph, so nothing has been seeded. Run without "
					+ "--no-seed to seed it.%n");
			return;
		}
		System.out.printf("No company with id %s. Try %s.%n", companyId, String.join(", ", known));
	}

	/** Printed before the decision because the decision reads it. */
	void printHistory(LoanGraph graph, String companyId) {
		printSection("1. CONTEXT GRAPH: PRECEDENT ON FILE",
				"Printing earlier decisions that will be supplied to the model as precedent.",
				graphDetail());
		List<PastDecision> decisions = graph.findDecisions(companyId);
		System.out.printf("%nDecision traces for %s, the precedent this run reads%n", companyId);
		if (decisions.isEmpty()) {
			System.out.println("  Nothing on file yet.");
			return;
		}
		for (PastDecision decision : decisions) {
			System.out.printf("  %s  %-8s  $%,-10d %s%s%n", DATE.format(decision.decidedAt()),
					decision.outcome(), decision.requestedAmount(), decision.line(),
					decision.excepted() ? "  (excepted, no longer counts)" : "");
		}
	}

	/**
	 * The three hops, printed for each denial on file. A listing is what a system of record
	 * gives you; this is what the relationships add to the same rows.
	 *
	 * Printed after the decision is written rather than before, so a denial that was cited shows
	 * what it has just driven instead of leaving the third hop empty for one more run.
	 *
	 * The third hop is a chain rather than a line, indented by how many citations away each
	 * decision is. Run the same company enough times and the indentation grows, which is the
	 * recursive traversal showing up in the output rather than in a comment about Cypher.
	 */
	void printPrecedentTrail(LoanGraph graph, String companyId) {
		List<PrecedentTrail> trail = graph.findPrecedentTrail(companyId);
		printSection("4. CONTEXT GRAPH: DECISION LINEAGE",
				"Printing how each denial connects to its policy, exception, and later decisions.",
				graphDetail());
		if (trail.isEmpty()) {
			System.out.println("\nNo denial lineage is on file for this company yet.");
			return;
		}
		System.out.printf("%nPrecedent trail, now that this decision is on file%n");
		for (PrecedentTrail denial : trail) {
			System.out.printf("  %s  denied %s%n", denial.decisionId(),
					DATE.format(denial.decidedAt()));
			System.out.printf("    decided by   %s%n",
					denial.policyName() != null ? denial.policyName() : "no policy named");
			System.out.printf("    exception    %s%n", denial.excepted()
					? denial.grantedBy() + " -- " + denial.justification() : "none");
			if (denial.governed().isEmpty()) {
				System.out.printf("    has driven   nothing yet%n");
				continue;
			}
			System.out.printf("    has driven%n");
			for (PrecedentStep step : denial.governed()) {
				System.out.printf("      %s%s%n", "  ".repeat((int) step.depth() - 1),
						step.decisionId());
			}
		}
	}

	/**
	 * The read back, and the reason the Underwriter node is not something this demo only ever
	 * writes to: one traversal from the person, through the decision they made, to the line that
	 * decision was granted past. Empty until somebody has approved past a line, which is worth
	 * saying rather than printing a heading with nothing under it.
	 */
	void printApprovalsPastPolicies(LoanGraph graph) {
		printSection("5. CONTEXT GRAPH: APPROVALS PAST POLICY LINES",
				"Printing which underwriters have approved applications past a measured line.",
				graphDetail());
		List<UnderwriterApprovals> approvals = graph.findApprovalsPastPolicies();
		System.out.printf("%nWhich underwriter approves past which line%n");
		if (approvals.isEmpty()) {
			System.out.println("  Nobody has been approved past a line yet.");
			return;
		}
		for (UnderwriterApprovals row : approvals) {
			// Wide enough for the longest name in seed.json and the longest policy name.
			System.out.printf("  %-16s %-25s %d%n", row.name(), row.policyName(), row.approvals());
		}
	}

	/**
	 * The read back for {@code GRANTED_BY}: every exception on file, who granted it and whose
	 * denial it waived. Printed after the precedent trail for the same reason that one is
	 * printed after the decision: a grant this run just made shows up in the same listing as one
	 * seeded ahead of it.
	 */
	void printExceptionGrants(LoanGraph graph) {
		printSection("6. CONTEXT GRAPH: EXCEPTIONS TO DENIALS",
				"Printing every prior denial that an underwriter has waived.",
				graphDetail());
		List<ExceptionGrant> grants = graph.findExceptionGrants();
		System.out.printf("%nWho has waived whose denial%n");
		if (grants.isEmpty()) {
			System.out.println("  Nobody has waived a denial yet.");
			return;
		}
		for (ExceptionGrant grant : grants) {
			// Wide enough for the longest "name, title" in seed.json (Dana Whitfield, Commercial
			// Underwriter), so the column lines up.
			System.out.printf("  %-40s waived denial %-14s (decided by %s)%n", grant.grantedBy(),
					grant.decisionId(), grant.decidedBy());
		}
	}

	/**
	 * The measurements are the bank's arithmetic; the verdict below them is the underwriter's
	 * call on it. Printing both together is the point: the two can disagree now, and a denial
	 * where everything measured above the line or an approval that went past one is the demo
	 * rather than a bug.
	 */
	void print(LoanAnswer answer) {
		printSection("3. MODEL RESPONSE: DECISION RECORDED",
				"Printing the model's verdict, the policy measurements, and the applicant letter.");
		LoanVerdict verdict = answer.verdict();
		Underwriter underwriter = answer.underwriter();

		// Who decided leads, because a reader who sees two different answers to identical
		// arguments has to be able to see who answered in the same output.
		System.out.printf("%nOn duty for this run%n  %s, %s, %d years on the job (%s)%n",
				underwriter.name(), underwriter.title(), underwriter.yearsOnTheJob(),
				underwriter.label());

		System.out.println("\nPolicies, as measured");
		for (PolicyResult result : answer.measurements()) {
			// Wide enough for the longest policy name in seed.json, so the column lines up.
			System.out.printf("  %-25s %s  (%s)%n", result.name() + ":",
					result.passed() ? "above the line" : "below the line", result.detail());
		}

		System.out.printf("%n%s (%s). %s%n", verdict.outcomeName(),
				verdict.confidence().name().toLowerCase(Locale.ROOT), verdict.reason());
		System.out.printf("  line crossed   %s%n", crossedLine(answer));
		if (answer.exception() != null) {
			System.out.printf("  exception      denial %s waived, because %s%n",
					answer.exception().decisionId(), answer.exception().justification());
		}
		if (!verdict.explanation().isBlank()) {
			System.out.printf("%n  %s%n", verdict.explanation().strip().replace("\n", "\n  "));
		}
	}

	/**
	 * The one sentence this whole example exists to print. An approval that names a line names
	 * the line it was granted past, which is the fact a decision table has nowhere to put, and a
	 * denial that names none was reached on judgement rather than on a number.
	 */
	private static String crossedLine(LoanAnswer answer) {
		if (answer.crossed() == null) {
			return "none, this was a judgement call on the file as a whole";
		}
		return "%s (%s)".formatted(answer.crossed().name(), answer.crossed().detail());
	}

	void printTranscript(List<Message> transcript) {
		printSection("7. CHAT MEMORY: PERSISTED EXCHANGE",
				"Printing the user question and applicant letter stored by Spring AI chat memory.");
		System.out.println("\nTranscript for this run, from Spring AI chat memory");
		for (Message message : transcript) {
			System.out.printf("  %-10s %s%n", message.getMessageType(), indented(message.getText()));
		}
	}

	/** Every message in full. A transcript that ends in "..." proves nothing about what memory carried. */
	private static String indented(String text) {
		return text.strip().replace("\n", "\n             ");
	}

	/**
	 * A second turn on the conversation the decision was just reached under, with nothing about
	 * the file repeated. What makes this a demo of memory rather than of the model: the file, the
	 * measurements, and the persona are not in this question, only in the transcript printed just
	 * above it, and the answer below can only be grounded in that if chat memory actually carried
	 * it forward.
	 */
	void printFollowUp(LoanOfficer officer, String conversationId) {
		String question = "In one sentence, what would have had to be different for the opposite "
				+ "outcome?";
		printSection("8. MODEL CALL: MEMORY FOLLOW-UP",
				"Asking a second question without repeating the file, to demonstrate chat memory.",
				modelDetail());
		System.out.printf("%nFollow-up on the same conversation, nothing about the file repeated%n");
		System.out.printf("  asked      %s%n", question);
		// The point of this line is that it proves memory carried the file forward, so it is
		// printed in full: truncating it mid-sentence would hide the proof.
		String answer = officer.followUp(conversationId, question);
		printSection("9. MODEL RESPONSE: MEMORY FOLLOW-UP",
				"Printing the answer grounded in the conversation that Spring AI carried forward.");
		System.out.printf("  answered   %s%n", answer.strip().replace("\n", "\n             "));
	}

	private static void printSection(String title, String description) {
		printSection(title, description, null);
	}

	/** {@code detail} is the identity of the model or graph the section is about, when there is one. */
	private static void printSection(String title, String description, String detail) {
		System.out.printf("%n╭%s╮%n", "─".repeat(SECTION_WIDTH));
		printSectionLine(title);
		printSectionLine(description);
		if (detail != null) {
			printSectionLine(detail);
		}
		System.out.printf("╰%s╯%n", "─".repeat(SECTION_WIDTH));
	}

	private static void printSectionLine(String text) {
		int contentWidth = SECTION_WIDTH - 2;
		String content = text.length() > contentWidth
				? text.substring(0, contentWidth - 1) + "…" : text;
		System.out.printf("│  %-" + contentWidth + "s│%n", content);
	}

}
