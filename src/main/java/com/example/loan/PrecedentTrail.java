package com.example.loan;

import java.time.Instant;
import java.util.List;

/**
 * One denial with the three hops the graph adds to it: the policy that decided it, the exception
 * that set it aside, and the later decisions it has since decided.
 *
 * grantedBy and justification are null unless an exception exists, and governed is empty until
 * this denial is one of the causes Repeat Denial Escalation fires on.
 */
record PrecedentTrail(String decisionId, Instant decidedAt, String policyName, String grantedBy,
		String justification, List<String> governed) {

	boolean excepted() {
		return this.grantedBy != null;
	}

}
