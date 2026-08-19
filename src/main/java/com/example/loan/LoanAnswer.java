package com.example.loan;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What one run produces: who drew it, the verdict they reached, the measurements they were shown,
 * and the policy the verdict crossed if it named one. All four are printed, so a reader who gets
 * two different answers to identical input can see who decided in the same output.
 *
 * crossed is null when nothing below the line drove the call, which is a legal outcome now that
 * a denial can be reached on the pattern in a file rather than on a number.
 */
record LoanAnswer(Underwriter underwriter, LoanVerdict verdict, List<PolicyResult> measurements,
		@Nullable PolicyResult crossed) {
}
