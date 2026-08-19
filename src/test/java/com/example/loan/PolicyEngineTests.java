package com.example.loan;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules, checked against the numbers the demo actually ships.
 *
 * The companies and thresholds are read out of seed.json, the same file the graph is seeded
 * from, rather than copied into constants here, so editing the fixture cannot leave these
 * tests green and their assertions wrong. Nothing here touches Neo4j: the engine takes the
 * company, the prior denial count, and the policies as arguments, which is what lets the whole
 * rule set be tested without any storage at all.
 */
class PolicyEngineTests {

	private static final Seed SEED = Seed.load();

	private static final Map<String, Policy> POLICIES = SEED.policies()
		.stream()
		.collect(Collectors.toMap(Policy::key, Function.identity()));

	private static final long AMOUNT = 250_000;

	private final PolicyEngine engine = new PolicyEngine();

	@Test
	void approvesACompanyThatPassesEverything() {
		LoanDecision decision = evaluate("C-1077", AMOUNT, 0);

		assertThat(decision.outcome()).isEqualTo(LoanDecision.APPROVED);
		assertThat(decision.decidingPolicy()).isNull();
		assertThat(decision.results()).allMatch(PolicyResult::passed);
	}

	@Test
	void deniesOnCreditScore() {
		LoanDecision decision = evaluate("C-1096", AMOUNT, 0);

		assertThat(decision.outcome()).isEqualTo(LoanDecision.DENIED);
		assertThat(decision.decidingPolicy().key()).isEqualTo(PolicyEngine.MINIMUM_CREDIT_SCORE);
		assertThat(decision.decidingPolicy().detail()).isEqualTo("score 47, needs 60");
	}

	@Test
	void deniesOnDebtToIncome() {
		LoanDecision decision = evaluate("C-1042", AMOUNT, 1);

		assertThat(decision.outcome()).isEqualTo(LoanDecision.DENIED);
		assertThat(decision.decidingPolicy().key()).isEqualTo(PolicyEngine.DEBT_TO_INCOME_LIMIT);
		assertThat(decision.decidingPolicy().detail())
			.isEqualTo("48% with this loan, must be under 40%");
	}

	/**
	 * C-1042 owes 35.5 percent of its income on its own, which passes. It is the amount being
	 * asked for that fails it, so the number the caller types is doing real work.
	 */
	@Test
	void theRequestedAmountDecidesDebtToIncome() {
		assertThat(evaluate("C-1042", 1, 0).outcome()).isEqualTo(LoanDecision.APPROVED);
		assertThat(evaluate("C-1042", AMOUNT, 0).outcome()).isEqualTo(LoanDecision.DENIED);
	}

	/** The one policy that only exists because of memory. */
	@Test
	void twoPriorDenialsEscalate() {
		LoanDecision decision = evaluate("C-1042", AMOUNT, 2);

		assertThat(decision.decidingPolicy().key())
			.isEqualTo(PolicyEngine.REPEAT_DENIAL_ESCALATION);
		assertThat(decision.reason()).isEqualTo(
				"Failed Repeat Denial Escalation policy. This company has been denied 2 times in the "
						+ "last 12 months.");
	}

	@Test
	void escalationOverridesNumbersThatWouldOtherwisePass() {
		LoanDecision passing = evaluate("C-1077", AMOUNT, 0);
		LoanDecision escalated = evaluate("C-1077", AMOUNT, 2);

		assertThat(passing.outcome()).isEqualTo(LoanDecision.APPROVED);
		assertThat(escalated.outcome()).isEqualTo(LoanDecision.DENIED);
		assertThat(escalated.results().stream().filter(result -> !result.passed()))
			.singleElement()
			.extracting(PolicyResult::key)
			.isEqualTo(PolicyEngine.REPEAT_DENIAL_ESCALATION);
	}

	/**
	 * C-1123 is the exception case, in arithmetic. Two denials are on file and one of them was
	 * excepted, so one counts and the loan is approved; had the exception not been granted, the
	 * numbers would never have been reached, because history decides first.
	 */
	@Test
	void theExceptedDenialIsTheDifferenceBetweenApprovedAndDenied() {
		assertThat(evaluate("C-1123", AMOUNT, 1).outcome()).isEqualTo(LoanDecision.APPROVED);

		LoanDecision withoutTheException = evaluate("C-1123", AMOUNT, 2);

		assertThat(withoutTheException.outcome()).isEqualTo(LoanDecision.DENIED);
		assertThat(withoutTheException.decidingPolicy().key())
			.isEqualTo(PolicyEngine.REPEAT_DENIAL_ESCALATION);
	}

	/** Long arithmetic would overflow to a negative ratio here, which passes an under check. */
	@Test
	void anAbsurdAmountStillFailsDebtToIncome() {
		LoanDecision decision = evaluate("C-1077", Long.MAX_VALUE, 0);

		assertThat(decision.outcome()).isEqualTo(LoanDecision.DENIED);
		assertThat(decision.decidingPolicy().key()).isEqualTo(PolicyEngine.DEBT_TO_INCOME_LIMIT);
	}

	@Test
	void namesAPolicyThatWentMissingFromTheGraph() {
		Map<String, Policy> incomplete = Map.of(PolicyEngine.MINIMUM_CREDIT_SCORE,
				POLICIES.get(PolicyEngine.MINIMUM_CREDIT_SCORE));

		assertThatThrownBy(() -> this.engine.evaluate(company("C-1077"), AMOUNT, 0, incomplete))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(PolicyEngine.DEBT_TO_INCOME_LIMIT);
	}

	private LoanDecision evaluate(String companyId, long requestedAmount, long priorDenials) {
		return this.engine.evaluate(company(companyId), requestedAmount, priorDenials, POLICIES);
	}

	private static Company company(String companyId) {
		return SEED.companies()
			.stream()
			.filter(company -> company.companyId().equals(companyId))
			.findFirst()
			.orElseThrow();
	}

}
