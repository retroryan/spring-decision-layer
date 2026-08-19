package com.example.loan;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

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

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
		.withZone(ZoneId.systemDefault());

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
		if (trail.isEmpty()) {
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
	 * denial it set aside. Printed after the precedent trail for the same reason that one is
	 * printed after the decision: a grant this run just made shows up in the same listing as one
	 * seeded ahead of it.
	 */
	void printExceptionGrants(LoanGraph graph) {
		List<ExceptionGrant> grants = graph.findExceptionGrants();
		System.out.printf("%nWho has set aside whose denial%n");
		if (grants.isEmpty()) {
			System.out.println("  Nobody has set aside a denial yet.");
			return;
		}
		for (ExceptionGrant grant : grants) {
			// Wide enough for the longest "name, title" in seed.json (Dana Whitfield, Commercial
			// Underwriter), so the column lines up.
			System.out.printf("  %-40s set aside %-14s (decided by %s)%n", grant.grantedBy(),
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
			System.out.printf("  exception      %s set aside, because %s%n",
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
		System.out.printf("%nFollow-up on the same conversation, nothing about the file repeated%n");
		System.out.printf("  asked      %s%n", question);
		// The point of this line is that it proves memory carried the file forward, so it is
		// printed in full: truncating it mid-sentence would hide the proof.
		System.out.printf("  answered   %s%n",
				officer.followUp(conversationId, question).strip().replace("\n", "\n             "));
	}

}
