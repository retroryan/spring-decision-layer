package com.example.loan;

import java.time.Instant;

/**
 * One decision read back out of the graph for the history listing. The policy name is null when
 * the decision named no line, weighedPast tells the line an approval was granted past from the
 * line a denial was stopped by, and excepted marks a denial an underwriter has since set aside,
 * which is still on file and no longer counts as precedent.
 */
record PastDecision(Instant decidedAt, String outcome, long requestedAmount, String policyName,
		boolean weighedPast, boolean excepted) {

	/**
	 * How the line reads in a listing. An approval says which line it went past, a denial names
	 * the line that stopped it, and a decision that named none was reached on the file itself.
	 */
	String line() {
		if (this.policyName == null) {
			return "the file as a whole";
		}
		return this.weighedPast ? "approved past " + this.policyName : this.policyName;
	}
}
