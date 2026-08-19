package com.example.loan;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * One loan application per run. Run it twice with the same arguments and the second run reads
 * the first run's decision as precedent, so what the underwriter is looking at has changed even
 * though the arguments have not.
 *
 * What is left in here is the two arguments and the order things happen in. Everything printed is
 * in {@link DecisionConsole}, because this class's package is what sets the component scan root
 * and is the one thing about it worth being careful with.
 */
@SpringBootApplication
public class Application {

	private static final String DEFAULT_COMPANY = "C-1042";

	private static final long DEFAULT_AMOUNT = 250_000;

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	CommandLineRunner demo(LoanGraph graph, LoanOfficer officer, DecisionConsole console) {
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
				console.printUnknownCompany(graph, companyId);
				return;
			}

			console.printHistory(graph, companyId);
			LoanAnswer answer = officer.answer(companyId, requestedAmount);
			console.print(answer);
			console.printPrecedentTrail(graph, companyId);
			console.printApprovalsPastPolicies(graph);
			console.printExceptionGrants(graph);
			console.printTranscript(officer.transcript(answer.conversationId()));
			console.printFollowUp(officer, answer.conversationId());
		};
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
