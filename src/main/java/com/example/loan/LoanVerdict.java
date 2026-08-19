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
 * pattern in a file rather than on a number has no line to point at.
 */
record LoanVerdict(Outcome outcome, String reason, @Nullable String decidingPolicyKey,
		List<String> citedDecisionIds, String explanation, Confidence confidence) {

	enum Outcome {

		APPROVED, DENIED

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
