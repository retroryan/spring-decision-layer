package com.example.loan;

import java.time.Instant;
import java.util.List;

/**
 * One denial with the three hops the graph adds to it: the policy that decided it, the exception
 * that set it aside, and the chain of later decisions it has since driven.
 *
 * grantedBy and justification are null unless an exception exists, and governed is empty until
 * some later decision cites this denial.
 */
record PrecedentTrail(String decisionId, Instant decidedAt, String policyName, String grantedBy,
		String justification, List<PrecedentStep> governed) {

	boolean excepted() {
		return this.grantedBy != null;
	}

}
