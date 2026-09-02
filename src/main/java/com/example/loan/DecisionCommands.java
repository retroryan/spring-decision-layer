package com.example.loan;

import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * One loan application per invocation, as a Spring Shell command rather than a
 * {@code CommandLineRunner}. The difference is the whole point: a runner fires the moment the
 * context is up, so merely booting the application, in a test or anywhere else, decided a loan and
 * called the model. A command runs only when it is named, so the application can start with nothing
 * happening until {@code decide} is typed (or passed as an argument in non-interactive mode).
 *
 * Run it twice with the same arguments and the second run reads the first run's decision as
 * precedent, so what the underwriter is looking at has changed even though the arguments have not.
 * Everything printed is in {@link DecisionConsole}.
 */
@Component
class DecisionCommands {

	private final LoanGraph graph;

	private final LoanOfficer officer;

	private final DecisionConsole console;

	DecisionCommands(LoanGraph graph, LoanOfficer officer, DecisionConsole console) {
		this.graph = graph;
		this.officer = officer;
		this.console = console;
	}

	@Command(name = "decide",
			description = "Decide a construction loan for a company, and write the decision to the graph.")
	public void decide(
			@Argument(index = 0, defaultValue = "C-1042",
					description = "The company id, for example C-1042.") String companyId,
			@Argument(index = 1, defaultValue = "250000",
					description = "The requested loan amount, e.g. 250000 or $250,000.") String amountText) {

		long requestedAmount;
		try {
			requestedAmount = amount(amountText);
		}
		catch (IllegalArgumentException ex) {
			System.out.printf("%s%nUsage: decide [companyId] [amount]%n", ex.getMessage());
			return;
		}

		this.console.printRunStart(companyId, requestedAmount);
		if (this.graph.findCompany(companyId).isEmpty()) {
			this.console.printUnknownCompany(this.graph, companyId);
			return;
		}

		this.console.printHistory(this.graph, companyId);
		this.console.printDecisionModelCall();
		LoanAnswer answer = this.officer.answer(companyId, requestedAmount);
		this.console.print(answer);
		this.console.printPrecedentTrail(this.graph, companyId);
		this.console.printApprovalsPastPolicies(this.graph);
		this.console.printExceptionGrants(this.graph);
		this.console.printTranscript(this.officer.transcript(answer.conversationId()));
		this.console.printFollowUp(this.officer, answer.conversationId());
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
