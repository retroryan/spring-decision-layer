package com.example.loan;

import java.util.List;

/**
 * The verdict for one application: every policy that was checked, and the single policy that
 * decided the answer. The deciding policy is null when everything passed, because nothing
 * causes an approval the way a failing rule causes a denial.
 */
record LoanDecision(String outcome, String reason, PolicyResult decidingPolicy,
		List<PolicyResult> results, long priorDenials) {

	static final String APPROVED = "APPROVED";

	static final String DENIED = "DENIED";

	boolean decidedBy(String policyKey) {
		return this.decidingPolicy != null && policyKey.equals(this.decidingPolicy.key());
	}

}
