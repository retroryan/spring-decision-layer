package com.example.loan;

import java.util.List;

/**
 * What {@link PrecedentAdvisor} assembles before anyone has decided anything, and what
 * {@link DecisionTraceAdvisor} records the answer to. It travels between the two on the request
 * context, which is what that map is for, and it travels as one typed record rather than as five
 * loose keys, so neither advisor can read a key the other never wrote.
 *
 * The split between the two advisors is the reason this type exists at all. One advisor reads the
 * graph and one writes it, and the second needs exactly what the first found: the measurements,
 * so an edge cannot claim a number nothing measured, and the denials that were actually sent, so
 * a citation cannot name one that was not.
 *
 * The conversation id is in here because reading the request is the first advisor's job. The chat
 * memory advisor further out put it on the context, and passing it along with the rest of the file
 * is what keeps the second advisor reading the response and nothing else.
 */
record LoanFile(String conversationId, Company company, long requestedAmount,
		List<PolicyResult> measurements, List<String> priorDenials, Underwriter underwriter) {

	String companyId() {
		return this.company.companyId();
	}

}
