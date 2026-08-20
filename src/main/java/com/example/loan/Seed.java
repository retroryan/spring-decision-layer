package com.example.loan;

import java.util.List;

/**
 * What the graph starts with, read from seed.json on the classpath by {@link SeedConfig}: the
 * companies and policies the bank does not invent as it goes, the three underwriters one of whom
 * decides each run, plus the applications, the denials that answered them, and the one exception
 * granted against a denial, so there is precedent the first time the demo runs.
 *
 * Nothing here writes. {@link GraphSeeder} MERGEs this into Neo4j at startup, and the tests
 * read the same numbers, so a hand edit to the file cannot leave assertions green and wrong.
 */
record Seed(List<Company> companies, List<Policy> policies, List<SeedUnderwriter> underwriters,
		List<SeedApplication> applications, List<SeedDecision> decisions,
		List<SeedException> exceptions) {

	/**
	 * One request for money that was already on file before the demo ever ran.
	 *
	 * Dated in months before the run rather than on a calendar day, because Repeat Denial
	 * Escalation only counts denials inside a rolling window. A fixed date would drift out of
	 * that window and quietly stop being precedent.
	 */
	record SeedApplication(String applicationId, String companyId, long requestedAmount,
			long monthsAgo) {
	}

	/**
	 * One of the three people on the roster, written as a person rather than as a setting. The
	 * disposition is the line that goes in the prompt and the label is the short form the console
	 * prints, so both can be retuned by editing this file with no rebuild.
	 */
	record SeedUnderwriter(String underwriterId, String name, String title, long yearsOnTheJob,
			String label, String disposition) {
	}

	/**
	 * Its policyKey and the two numbers beside it become the APPLIED_POLICY relationship, and its
	 * underwriterId becomes the DECIDED_BY relationship. Every seeded denial names one of the
	 * three, so the read back has people in it that a later run can draw rather than a name that
	 * appears nowhere else.
	 */
	record SeedDecision(String decisionId, String applicationId, String outcome, String reason,
			long monthsAgo, String underwriterId, String policyKey, Double observed,
			Double threshold) {
	}

	/**
	 * An underwriter's judgement that a denial should not be held against the company later. It
	 * does not undo the decision: the denial stays on file with its policy and its numbers, and
	 * stops counting as precedent.
	 *
	 * underwriterId is one of the three on the roster, so {@code GRANTED_BY} joins to a real node
	 * and the read back returns a row on a graph nobody has run against yet. grantedBy stays a
	 * string alongside it, for the console, the same way a decision keeps its disposition text
	 * beside {@code DECIDED_BY}.
	 */
	record SeedException(String exceptionId, String decisionId, String grantedBy,
			String underwriterId, String justification, long monthsAgo) {
	}

}
