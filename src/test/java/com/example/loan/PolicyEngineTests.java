package com.example.loan;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The measurements, checked against the numbers the demo actually ships. Nothing here asserts an
 * outcome, because nothing here produces one any more: what the engine does is say where a
 * number sits against its threshold, and who that ought to persuade is the underwriter's problem.
 *
 * The companies and thresholds are read out of seed.json, the same file the graph is seeded
 * from, rather than copied into constants here, so editing the fixture cannot leave these tests
 * green and their assertions wrong. Nothing here touches Neo4j: the engine takes the company,
 * the prior denial count, and the policies as arguments, which is what lets the whole rule set
 * be tested without any storage at all.
 */
class PolicyEngineTests {

	private static final Seed SEED = Seed.load();

	private static final Map<String, Policy> POLICIES = SEED.policies()
		.stream()
		.collect(Collectors.toMap(Policy::key, Function.identity()));

	private static final long AMOUNT = 250_000;

	private final PolicyEngine engine = new PolicyEngine();

	@Test
	void measuresEveryPolicyOnEveryFile() {
		assertThat(measure("C-1077", AMOUNT, 0)).extracting(PolicyResult::key)
			.containsExactly(PolicyEngine.MINIMUM_CREDIT_SCORE, PolicyEngine.DEBT_TO_INCOME_LIMIT,
					PolicyEngine.REPEAT_DENIAL_ESCALATION);
	}

	@Test
	void aCompanyCanClearEveryLine() {
		assertThat(measure("C-1077", AMOUNT, 0)).allMatch(PolicyResult::passed);
	}

	@Test
	void marksTheCreditScoreBelowTheLine() {
		assertThat(below("C-1096", AMOUNT, 0)).singleElement()
			.satisfies(result -> {
				assertThat(result.key()).isEqualTo(PolicyEngine.MINIMUM_CREDIT_SCORE);
				assertThat(result.detail()).isEqualTo("score 47, needs 60");
			});
	}

	@Test
	void marksDebtToIncomeBelowTheLine() {
		assertThat(below("C-1042", AMOUNT, 0)).singleElement()
			.satisfies(result -> {
				assertThat(result.key()).isEqualTo(PolicyEngine.DEBT_TO_INCOME_LIMIT);
				assertThat(result.detail()).isEqualTo("48% with this loan, must be under 40%");
			});
	}

	/**
	 * C-1042 owes 35.5 percent of its income on its own, which clears. It is the amount being
	 * asked for that puts it below the line, so the number the caller types is doing real work.
	 */
	@Test
	void theRequestedAmountDecidesDebtToIncome() {
		assertThat(below("C-1042", 1, 0)).isEmpty();
		assertThat(below("C-1042", AMOUNT, 0)).extracting(PolicyResult::key)
			.containsExactly(PolicyEngine.DEBT_TO_INCOME_LIMIT);
	}

	/** The one policy that only exists because of memory. */
	@Test
	void twoPriorDenialsPutEscalationBelowTheLine() {
		assertThat(below("C-1077", AMOUNT, 0)).isEmpty();
		assertThat(below("C-1077", AMOUNT, 2)).singleElement()
			.satisfies(result -> {
				assertThat(result.key()).isEqualTo(PolicyEngine.REPEAT_DENIAL_ESCALATION);
				assertThat(result.detail())
					.isEqualTo("2 prior denials in the last 12 months, escalates at 2");
			});
	}

	/**
	 * Nothing ranks the policies any more. Two lines can be below at once, and which of them
	 * mattered is the judgement the engine stopped making: it reports both and says neither
	 * decided.
	 */
	@Test
	void twoLinesCanBeBelowAtOnceAndNeitherIsTheDecidingOne() {
		assertThat(below("C-1096", AMOUNT, 2)).extracting(PolicyResult::key)
			.containsExactly(PolicyEngine.MINIMUM_CREDIT_SCORE,
					PolicyEngine.REPEAT_DENIAL_ESCALATION);
	}

	/**
	 * C-1123 is the exception case, in arithmetic. Three denials are on file and one of them was
	 * excepted, so two count and escalation lands below the line, which is the position the demo
	 * is built on. The count arrives already scoped, so the exception is what the difference
	 * between these two calls stands for: granting one more takes this company back above it.
	 */
	@Test
	void theExceptedDenialIsTheDifferenceBetweenAboveAndBelowTheLine() {
		assertThat(below("C-1123", AMOUNT, 1)).isEmpty();
		assertThat(below("C-1123", AMOUNT, 2)).extracting(PolicyResult::key)
			.containsExactly(PolicyEngine.REPEAT_DENIAL_ESCALATION);
	}

	/** Long arithmetic would overflow to a negative ratio here, which clears an under check. */
	@Test
	void anAbsurdAmountStillFallsBelowDebtToIncome() {
		assertThat(below("C-1077", Long.MAX_VALUE, 0)).extracting(PolicyResult::key)
			.containsExactly(PolicyEngine.DEBT_TO_INCOME_LIMIT);
	}

	/** The observed value and the threshold are what the graph stores on the edge. */
	@Test
	void theMeasurementCarriesTheNumbersTheEdgeGets() {
		PolicyResult credit = measure("C-1042", AMOUNT, 0).get(0);

		assertThat(credit.observed()).isEqualTo(72);
		assertThat(credit.threshold()).isEqualTo(60);
	}

	@Test
	void namesAPolicyThatWentMissingFromTheGraph() {
		Map<String, Policy> incomplete = Map.of(PolicyEngine.MINIMUM_CREDIT_SCORE,
				POLICIES.get(PolicyEngine.MINIMUM_CREDIT_SCORE));

		assertThatThrownBy(() -> this.engine.measure(company("C-1077"), AMOUNT, 0, incomplete))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(PolicyEngine.DEBT_TO_INCOME_LIMIT);
	}

	private List<PolicyResult> measure(String companyId, long requestedAmount, long priorDenials) {
		return this.engine.measure(company(companyId), requestedAmount, priorDenials, POLICIES);
	}

	/** What the model is shown as below the line, and the only keys its verdict may name. */
	private List<PolicyResult> below(String companyId, long requestedAmount, long priorDenials) {
		return measure(companyId, requestedAmount, priorDenials).stream()
			.filter(result -> !result.passed())
			.toList();
	}

	private static Company company(String companyId) {
		return SEED.companies()
			.stream()
			.filter(company -> company.companyId().equals(companyId))
			.findFirst()
			.orElseThrow();
	}

}
