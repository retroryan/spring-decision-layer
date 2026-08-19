package com.example.loan;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * The bank's measurements, as plain arithmetic. Nothing here reads the graph and nothing here
 * decides: the company, the denial count, and the thresholds all arrive as arguments, and what
 * comes back is where each number sits against its threshold.
 *
 * Whether a number below the line should stop a loan is the underwriter's call, so it is not
 * made here. What is made here is the measurement the underwriter is shown and the measurement
 * the graph stores on the edge, which is why both cannot disagree about it.
 */
@Component
class PolicyEngine {

	static final String MINIMUM_CREDIT_SCORE = "minimumCreditScore";

	static final String DEBT_TO_INCOME_LIMIT = "debtToIncomeLimit";

	static final String REPEAT_DENIAL_ESCALATION = "repeatDenialEscalation";

	/**
	 * How far back Repeat Denial Escalation counts. The caller needs it before it can read the
	 * history, and asking here keeps the missing-policy message in one place.
	 */
	long denialWindowMonths(Map<String, Policy> policies) {
		return require(policies, REPEAT_DENIAL_ESCALATION).windowMonths();
	}

	/**
	 * Every policy measured against one application, in the order the console prints them. No
	 * ordering between them is meaningful any more: an earlier version ranked Repeat Denial
	 * Escalation above the numeric policies to pick which one decided, and picking is exactly
	 * what moved to the model.
	 */
	List<PolicyResult> measure(Company company, long requestedAmount, long priorDenials,
			Map<String, Policy> policies) {

		return List.of(creditScore(company, require(policies, MINIMUM_CREDIT_SCORE)),
				debtToIncome(company, requestedAmount, require(policies, DEBT_TO_INCOME_LIMIT)),
				escalation(priorDenials, require(policies, REPEAT_DENIAL_ESCALATION)));
	}

	/** Thresholds are Policy nodes seeded from seed.json, so either can be edited away. */
	private Policy require(Map<String, Policy> policies, String key) {
		Policy policy = policies.get(key);
		if (policy == null) {
			throw new IllegalStateException("No Policy node with key '" + key
					+ "' in the graph. It is seeded from seed.json, so restore that file and "
					+ "restart: git checkout src/main/resources/seed.json");
		}
		return policy;
	}

	private PolicyResult creditScore(Company company, Policy policy) {
		long observed = company.creditRiskScore();
		long needed = Math.round(policy.threshold());
		return new PolicyResult(policy.key(), policy.name(), observed >= needed, observed,
				policy.threshold(), "score %d, needs %d".formatted(observed, needed));
	}

	/**
	 * Both operands are widened before they are added. Left as longs they would overflow on a
	 * large enough request, and a negative ratio passes an "under 40 percent" check.
	 */
	private PolicyResult debtToIncome(Company company, long requestedAmount, Policy policy) {
		double observed = ((double) company.currentDebt() + requestedAmount)
				/ company.annualIncome();
		return new PolicyResult(policy.key(), policy.name(), observed < policy.threshold(), observed,
				policy.threshold(), "%s with this loan, must be under %s"
					.formatted(percent(observed), percent(policy.threshold())));
	}

	/**
	 * The count arrives already scoped to the window, so this only has to name it. The number
	 * comes from the policy node, so the console and the Cypher cannot disagree about it.
	 */
	private PolicyResult escalation(long priorDenials, Policy policy) {
		long limit = Math.round(policy.threshold());
		return new PolicyResult(policy.key(), policy.name(), priorDenials < limit, priorDenials,
				policy.threshold(),
				"%d prior denial%s in the last %d months, escalates at %d".formatted(priorDenials,
						priorDenials == 1 ? "" : "s", policy.windowMonths(), limit));
	}

	/**
	 * 48% rather than 48.0%, 13.8% rather than 14%. Locale.ROOT, so a machine configured for a
	 * language that writes 13,8 does not change the output the tests assert on.
	 */
	private String percent(double ratio) {
		String text = String.format(Locale.ROOT, "%.1f", ratio * 100);
		return (text.endsWith(".0") ? text.substring(0, text.length() - 2) : text) + "%";
	}

}
