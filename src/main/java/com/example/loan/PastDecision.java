package com.example.loan;

import java.time.Instant;

/**
 * One decision read back out of the graph for the history listing. The policy name is null for
 * an approval, and excepted marks a denial an underwriter has since set aside, which is still on
 * file and no longer counts as precedent.
 */
record PastDecision(Instant decidedAt, String outcome, long requestedAmount, String policyName,
		boolean excepted) {
}
