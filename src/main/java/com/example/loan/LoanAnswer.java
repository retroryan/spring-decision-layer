package com.example.loan;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What one run produces: the verdict the model reached, the measurements it was shown, and the
 * policy its verdict crossed if it named one. All three are printed, so a reader can check the
 * judgement against the arithmetic instead of taking either on trust.
 *
 * crossed is null when nothing below the line drove the call, which is a legal outcome now that
 * a denial can be reached on the pattern in a file rather than on a number.
 */
record LoanAnswer(LoanVerdict verdict, List<PolicyResult> measurements,
		@Nullable PolicyResult crossed) {
}
