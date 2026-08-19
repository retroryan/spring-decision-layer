package com.example.loan;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * One loan application per run. Run it twice with the same arguments and the second run reads
 * the first run's decision as precedent, so what the underwriter is looking at has changed even
 * though the arguments have not.
 */
@SpringBootApplication
public class Application {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
		.withZone(ZoneId.systemDefault());

	private static final String DEFAULT_COMPANY = "C-1042";

	private static final long DEFAULT_AMOUNT = 250_000;

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	CommandLineRunner demo(LoanGraph graph, LoanOfficer officer) {
		return args -> {
			String companyId = args.length > 0 ? args[0] : DEFAULT_COMPANY;
			long requestedAmount;
			try {
				requestedAmount = args.length > 1 ? amount(args[1]) : DEFAULT_AMOUNT;
			}
			catch (IllegalArgumentException ex) {
				System.out.printf("%s%nUsage: ./run.sh [--no-seed] [companyId] [amount]%n",
						ex.getMessage());
				return;
			}

			if (graph.findCompany(companyId).isEmpty()) {
				printUnknownCompany(graph, companyId);
				return;
			}

			printHistory(graph, companyId);
			LoanAnswer answer = officer.answer(companyId, requestedAmount);
			print(answer);
			printPrecedentTrail(graph, companyId);
			printApprovalsPastPolicies(graph);
			printTranscript(officer.transcript(answer.conversationId()));
		};
	}

	/**
	 * The ids come from the graph rather than a constant, so this cannot suggest the id that just
	 * failed. An empty graph is a different mistake and gets a different answer: --no-seed skips
	 * seeding, and on a graph that was never seeded there is nothing at all to decide about.
	 */
	private void printUnknownCompany(LoanGraph graph, String companyId) {
		List<String> known = graph.findCompanyIds();
		if (known.isEmpty()) {
			System.out.printf("No companies in the graph, so nothing has been seeded. Run without "
					+ "--no-seed to seed it.%n");
			return;
		}
		System.out.printf("No company with id %s. Try %s.%n", companyId, String.join(", ", known));
	}

	/** Printed before the decision because the decision reads it. */
	private void printHistory(LoanGraph graph, String companyId) {
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
	private void printPrecedentTrail(LoanGraph graph, String companyId) {
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
	private void printApprovalsPastPolicies(LoanGraph graph) {
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
	 * The measurements are the bank's arithmetic; the verdict below them is the underwriter's
	 * call on it. Printing both together is the point: the two can disagree now, and a denial
	 * where everything measured above the line or an approval that went past one is the demo
	 * rather than a bug.
	 */
	private void print(LoanAnswer answer) {
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

	private void printTranscript(List<Message> transcript) {
		System.out.println("\nTranscript for this run, from Spring AI chat memory");
		for (Message message : transcript) {
			System.out.printf("  %-10s %s%n", message.getMessageType(), oneLine(message.getText()));
		}
	}

	private static String oneLine(String text) {
		String flat = text.strip().replace("\n", " ");
		return flat.length() <= 88 ? flat : flat.substring(0, 85) + "...";
	}

	/** Accepts what people actually type. $250,000 and 250000 are the same request. */
	private static long amount(String text) {
		long parsed;
		try {
			parsed = Long.parseLong(text.replace("$", "").replace(",", "").trim());
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException(
					"'" + text + "' is not an amount. Try a whole number, like 250000.");
		}
		// A negative amount subtracts from what the company owes, turning a denial into an
		// approval.
		if (parsed <= 0) {
			throw new IllegalArgumentException("The amount has to be more than zero.");
		}
		return parsed;
	}

}
