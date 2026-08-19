package com.example.loan;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What one run produces: who drew it, the verdict they reached, the measurements they were shown,
 * the policy the verdict crossed if it named one, and any exception that run actually granted.
 * All are printed, so a reader who gets two different answers to identical input can see who
 * decided in the same output.
 *
 * crossed is null when nothing below the line drove the call, which is a legal outcome now that a
 * denial can be reached on the pattern in a file rather than on a number.
 *
 * exception is the verdict's own field once {@link DecisionTraceAdvisor#grantedException} has
 * checked it against the denials this run was actually shown, so what is printed here is what was
 * written to the graph rather than what the model merely claimed.
 *
 * The conversation id comes back with the rest because it is what the two kinds of memory join on:
 * it is a property on the Decision node the run just wrote and the key the Session is stored under,
 * so a caller holding this record can read either side.
 */
record LoanAnswer(String conversationId, Underwriter underwriter, LoanVerdict verdict,
		List<PolicyResult> measurements, @Nullable PolicyResult crossed,
		LoanVerdict.@Nullable Pardon exception) {
}
