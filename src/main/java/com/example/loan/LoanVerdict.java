package com.example.loan;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What the model sends back, as a Java record rather than prose to be scraped. Spring AI
 * generates a JSON schema from these components and Anthropic's {@code output_config.format}
 * enforces it, so nothing about the format has to be asked for in the prompt.
 *
 * The two enums are the only guardrails on the outcome: a schema that names its cases cannot
 * come back with a third one, which is cheaper than a Java check that rejects it afterwards.
 * {@code decidingPolicyKey} is the one nullable component, because a denial reached on the
 * pattern in a file rather than on a number has no line to point at. {@code exception} is the
 * other: null on most runs, because most runs decide today's file and nothing more.
 */
record LoanVerdict(Outcome outcome, String reason, @Nullable String decidingPolicyKey,
		List<String> citedDecisionIds, String explanation, Confidence confidence,
		@Nullable Pardon exception) {

	enum Outcome {

		APPROVED, DENIED

	}

	/**
	 * A judgement about the record rather than a device for unblocking today's answer: an
	 * underwriter may deny today and still decide that an older denial should stop counting. It
	 * is independent of {@link #outcome}, which is why it is its own nullable component rather
	 * than something folded into a denial's fields.
	 *
	 * Named Pardon rather than Exception so the type cannot be mistaken for {@code
	 * java.lang.Exception} at a glance; the domain word "exception" stays everywhere else, on the
	 * JSON field, the prompt, and the graph's own {@code Exception} node label.
	 *
	 * @param decisionId the standing denial being set aside, which has to be one of the ids the
	 * facts block listed as still counting; {@link DecisionTraceAdvisor} drops anything else
	 * rather than writing a node with nothing real to point at
	 * @param justification the underwriter's own reasoning for setting it aside
	 */
	record Pardon(String decisionId, String justification) {
	}

	/**
	 * Whether another underwriter could reasonably have landed the other way. The field exists
	 * so the console can show that a close call was close, rather than printing a coin flip as
	 * though it were arithmetic.
	 */
	enum Confidence {

		CLEAR, BORDERLINE

	}

	boolean approved() {
		return this.outcome == Outcome.APPROVED;
	}

	/** The word the graph stores, and the word {@code FIND_PRIOR_DENIALS} filters on. */
	String outcomeName() {
		return this.outcome.name();
	}

}
