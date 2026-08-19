package com.example.loan;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * One loan application per run. Run it twice with the same arguments and the second run is
 * denied by a different policy, because the first run's decision is in the graph.
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
				System.out.printf("%s%nUsage: ./run.sh [companyId] [amount]%n", ex.getMessage());
				return;
			}

			if (graph.findCompany(companyId).isEmpty()) {
				System.out.printf("No company with id %s. Try C-1042, C-1077, C-1096, or C-1123.%n",
						companyId);
				return;
			}

			printHistory(graph, companyId);
			print(officer.answer(companyId, requestedAmount));
			printPrecedentTrail(graph, companyId);
			printTranscript(officer.transcript());
		};
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
					decision.outcome(), decision.requestedAmount(),
					decision.policyName() != null ? decision.policyName() : "every policy passed",
					decision.excepted() ? "  (excepted, no longer counts)" : "");
		}
	}

	/**
	 * The three hops, printed for each denial on file. A listing is what a system of record
	 * gives you; this is what the relationships add to the same rows.
	 *
	 * Printed after the decision is written rather than before, so a denial that escalated shows
	 * what it has just decided instead of leaving the third hop empty for one more run.
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
					denial.policyName() != null ? denial.policyName() : "no policy on record");
			System.out.printf("    exception    %s%n", denial.excepted()
					? denial.grantedBy() + " -- " + denial.justification() : "none");
			System.out.printf("    has decided  %s%n", denial.governed().isEmpty()
					? "nothing yet" : String.join(", ", denial.governed()));
		}
	}

	/** The checklist is the computed decision; the paragraph is the model's account of it. */
	private void print(LoanAnswer answer) {
		LoanDecision decision = answer.decision();

		System.out.println("\nPolicies");
		for (PolicyResult result : decision.results()) {
			// Wide enough for the longest policy name in seed.json, so the column lines up.
			System.out.printf("  %-25s %s  (%s)%n", result.name() + ":",
					result.passed() ? "PASS" : "FAIL", result.detail());
		}

		System.out.printf("%n%s. %s%n", decision.outcome(), decision.reason());
		if (!answer.explanation().isBlank()) {
			System.out.printf("%n  %s%n", answer.explanation().strip().replace("\n", "\n  "));
		}
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
