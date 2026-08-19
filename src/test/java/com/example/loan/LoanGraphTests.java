package com.example.loan;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim this example lives on, checked against real Cypher: a decision written by one run
 * is counted by the next one, and the graph says which earlier decisions caused it.
 *
 * The database is a container rather than the reader's own instance, so these tests cannot
 * touch anything a demo run cares about, and the traversal is exercised for real instead of
 * being described in a comment. Docker has to be running for {@code ./mvnw test}.
 *
 * Every test starts from an empty database and reseeds it with {@link GraphSeeder}, which is
 * the same code path the app takes at startup.
 */
@Testcontainers
class LoanGraphTests {

	@Container
	static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5.26").withoutAuthentication();

	private static final String CONVERSATION = "conversation-1";

	/**
	 * Named rather than left to the connection to resolve, which is what the driver
	 * recommends and what a reader who sets NEO4J_DATABASE gets. It is the container's own
	 * default database, so naming it changes nothing here except that the path taken is the
	 * configured one.
	 */
	private static final String DATABASE = "neo4j";

	private static Driver driver;

	private LoanGraph graph;

	@BeforeEach
	void seedAnEmptyGraph() {
		if (driver == null) {
			driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.none());
		}
		driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
		new GraphSeeder(driver, DATABASE).run();
		this.graph = new LoanGraph(driver, DATABASE);
	}

	@AfterAll
	static void closeTheDriver() {
		if (driver != null) {
			driver.close();
			driver = null;
		}
	}

	@Test
	void findsPriorDenialsByWalkingTheGraph() {
		assertThat(this.graph.findPriorDenials("C-1042", window())).containsExactly("D-1042-SEED");
		assertThat(this.graph.findPriorDenials("C-1077", window())).isEmpty();
	}

	/**
	 * Precedent ages out. The seeded denial is three months old, so a one-month window walks the
	 * same relationships and finds nothing, which is the difference between a rolling window and
	 * a count of everything ever recorded.
	 */
	@Test
	void aDenialOlderThanTheWindowIsNotCounted() {
		assertThat(this.graph.findPriorDenials("C-1042", 1)).isEmpty();
		assertThat(this.graph.findPriorDenials("C-1042", window())).containsExactly("D-1042-SEED");
	}

	@Test
	void aDecisionWrittenNowIsCountedByTheNextRead() {
		save("C-1042", denial(), belowTheLine(), List.of());

		assertThat(new LoanGraph(driver, DATABASE).findPriorDenials("C-1042", window())).hasSize(2);
	}

	/**
	 * One write, not two. The sentence the applicant was told arrives on the node with the
	 * verdict that produced it, so there is no moment where a decision exists unexplained.
	 */
	@Test
	void theExplanationIsWrittenWithTheDecisionItExplains() {
		String decisionId = save("C-1042", denial(), null, List.of());

		Record stored = query("""
				MATCH (d:Decision {decisionId: $id})
				RETURN d.explanation AS e, d.confidence AS c
				""", Map.of("id", decisionId)).get(0);

		assertThat(stored.get("e").asString()).isEqualTo("Your debt load is too high for this.");
		assertThat(stored.get("c").asString()).isEqualTo("CLEAR");
	}

	/**
	 * The line an approval was granted past, which is the fact the schema had nowhere to put
	 * before: the same policy and the same numbers a denial would carry, on the edge type that
	 * says the loan was approved anyway.
	 */
	@Test
	void anApprovalOverALineIsJoinedToTheLineItCrossed() {
		PolicyResult crossed = belowTheLine();

		String decisionId = save("C-1042", approval(), crossed, List.of());

		Record stored = query("""
				MATCH (:Decision {decisionId: $id})-[w:WEIGHED_PAST]->(p:Policy)
				RETURN p.key AS key, w.observed AS observed, w.threshold AS threshold
				""", Map.of("id", decisionId)).get(0);

		assertThat(stored.get("key").asString()).isEqualTo(PolicyEngine.DEBT_TO_INCOME_LIMIT);
		assertThat(stored.get("observed").asDouble()).isEqualTo(crossed.observed());
		assertThat(stored.get("threshold").asDouble()).isEqualTo(crossed.threshold());
		assertThat(appliedPolicies(decisionId)).isEmpty();
	}

	/**
	 * The same key, the other outcome, the other edge. One is written or the other is, never
	 * both, because the type is the whole difference between a line that stopped a loan and a
	 * line a loan was granted past.
	 */
	@Test
	void aDenialOnTheSameLineIsJoinedByTheOtherEdgeType() {
		String decisionId = save("C-1042", denial(), belowTheLine(), List.of());

		assertThat(appliedPolicies(decisionId)).containsExactly(PolicyEngine.DEBT_TO_INCOME_LIMIT);
		assertThat(weighedPast(decisionId)).isEmpty();
	}

	/**
	 * A verdict can name no policy at all, either because nothing was below the line or because
	 * the underwriter denied on the pattern in a file rather than on a number. Neither edge is
	 * written, and the history listing has to survive it.
	 */
	@Test
	void aVerdictThatNamesNoPolicyIsJoinedToNoPolicyAtAll() {
		String approved = save("C-1077", approval(), null, List.of());
		String denied = save("C-1077", denial(), null, List.of());

		assertThat(appliedPolicies(approved)).isEmpty();
		assertThat(weighedPast(approved)).isEmpty();
		assertThat(appliedPolicies(denied)).isEmpty();
		assertThat(weighedPast(denied)).isEmpty();
		assertThat(this.graph.findDecisions("C-1077")).hasSize(2)
			.allSatisfy(decision -> assertThat(decision.policyName()).isNull());
	}

	@Test
	void historyComesBackOldestFirst() {
		save("C-1042", denial(), belowTheLine(), List.of());

		List<PastDecision> history = this.graph.findDecisions("C-1042");

		assertThat(history).hasSize(2);
		assertThat(history.get(0).decidedAt()).isBefore(history.get(1).decidedAt());
		assertThat(history.get(0).requestedAmount()).isEqualTo(400_000);
		assertThat(history.get(0).policyName()).isEqualTo("Debt to Income Limit");
	}

	/**
	 * The point of the ESCALATED_FROM edge: the decision names the earlier decisions that
	 * made it come out the way it did, rather than leaving a reader to infer them from a
	 * count that happens to match.
	 */
	@Test
	void aDecisionMadeByHistoryIsJoinedToTheDecisionsThatCausedIt() {
		List<String> causes = this.graph.findPriorDenials("C-1042", window());

		String decisionId = save("C-1042", escalation(), atTheThreshold(), causes);

		assertThat(causedBy(decisionId)).containsExactly("D-1042-SEED");
	}

	/**
	 * The ids come from the model now, so an id the graph does not hold has to be dropped rather
	 * than fail the write. The advisor filters citations to the denials it sent; this is the
	 * second net under that, in the Cypher itself.
	 */
	@Test
	void anIdTheGraphDoesNotHoldIsDroppedRatherThanFailingTheWrite() {
		String decisionId = save("C-1042", escalation(), atTheThreshold(),
				List.of("D-1042-SEED", "D-DOES-NOT-EXIST"));

		assertThat(causedBy(decisionId)).containsExactly("D-1042-SEED");
	}

	/**
	 * An exception is not a deletion. The denial stays on file with its policy and its numbers,
	 * and stops being precedent, which is the difference between correcting a record and
	 * overriding what it means for the next decision.
	 */
	@Test
	void anExceptedDenialStaysOnFileAndStopsCounting() {
		assertThat(this.graph.findDecisions("C-1123")).hasSize(2)
			.allMatch(decision -> LoanVerdict.Outcome.DENIED.name().equals(decision.outcome()));

		assertThat(this.graph.findPriorDenials("C-1123", window()))
			.containsExactly("D-1123-SEED-1");
	}

	/** The excepted denial is marked where it is listed, so the count and the listing agree. */
	@Test
	void theListingSaysWhichDenialWasExcepted() {
		assertThat(this.graph.findDecisions("C-1123")).extracting(PastDecision::excepted)
			.containsExactly(false, true);
	}

	/**
	 * The three hops the talk turns on, in one traversal: the policy that decided a denial, the
	 * exception that set it aside, and the later decisions that denial has since driven.
	 */
	@Test
	void theTrailWalksToThePolicyTheExceptionAndWhatTheDenialHasSinceDriven() {
		String escalated = save("C-1042", escalation(), atTheThreshold(), List.of("D-1042-SEED"));

		// Two denials on file now: the seeded one, and the escalation that cites it.
		assertThat(this.graph.findPrecedentTrail("C-1042")).hasSize(2)
			.anySatisfy(denial -> {
				assertThat(denial.decisionId()).isEqualTo("D-1042-SEED");
				assertThat(denial.policyName()).isEqualTo("Debt to Income Limit");
				assertThat(denial.excepted()).isFalse();
				assertThat(denial.governed()).containsExactly(new PrecedentStep(1, escalated));
			});

		assertThat(this.graph.findPrecedentTrail("C-1123")).hasSize(2)
			.anySatisfy(denial -> {
				assertThat(denial.decisionId()).isEqualTo("D-1123-SEED-2");
				assertThat(denial.grantedBy()).isEqualTo("M. Alvarez, Senior Underwriter");
				assertThat(denial.justification()).contains("bridge financing");
				assertThat(denial.governed()).isEmpty();
			});
	}

	/**
	 * The reason the trail is variable length. A decision that cited a denial can itself be
	 * cited, and the denial at the root of that has driven both of them: one directly, one at a
	 * remove. A single hop would report the first link as though it were the whole chain, which
	 * is the read a relational schema needs a recursive CTE for.
	 */
	@Test
	void theTrailFollowsCitationsOfCitationsAndSaysHowFarAway() {
		String first = save("C-1042", escalation(), atTheThreshold(), List.of("D-1042-SEED"));
		String second = save("C-1042", escalation(), atTheThreshold(), List.of(first));

		assertThat(this.graph.findPrecedentTrail("C-1042"))
			.filteredOn(denial -> denial.decisionId().equals("D-1042-SEED"))
			.singleElement()
			.extracting(PrecedentTrail::governed)
			.isEqualTo(List.of(new PrecedentStep(1, first), new PrecedentStep(2, second)));
	}

	/** A denial nothing has cited comes back with an empty chain, not with a row of nulls. */
	@Test
	void aDenialNothingHasCitedHasDrivenNothing() {
		assertThat(this.graph.findPrecedentTrail("C-1042")).singleElement()
			.extracting(PrecedentTrail::governed)
			.isEqualTo(List.of());
	}

	/** A verdict that cited nothing is joined to nothing, whatever it read on the way. */
	@Test
	void aDecisionThatCitedNothingIsJoinedToNothing() {
		String decisionId = save("C-1042", denial(), belowTheLine(), List.of());

		assertThat(causedBy(decisionId)).isEmpty();
	}

	/**
	 * The transcript and the decision are two schemas in one database. This is the key that
	 * makes that worth anything: from a decision you can find the conversation it was
	 * explained in, and from a conversation you can find what it decided.
	 */
	@Test
	void theDecisionCarriesTheConversationItWasExplainedIn() {
		String decisionId = save("C-1042", denial(), belowTheLine(), List.of());

		Record stored = query("MATCH (d:Decision {decisionId: $id}) RETURN d.conversationId AS c",
				Map.of("id", decisionId)).get(0);

		assertThat(stored.get("c").asString()).isEqualTo(CONVERSATION);
	}

	/** Backs the unknown-id message: an empty graph and a typo have to be told apart. */
	@Test
	void theGraphNamesTheCompaniesItHolds() {
		assertThat(this.graph.findCompanyIds()).containsExactly("C-1042", "C-1077", "C-1096",
				"C-1123");
	}

	/**
	 * Stored as a temporal type rather than as ISO text, so ORDER BY is a real sort and a
	 * reader can ask for the denials inside the last twelve months.
	 */
	@Test
	void timesAreNeo4jDatetimesAndNotStrings() {
		save("C-1042", denial(), belowTheLine(), List.of());

		List<Record> times = query("""
				MATCH (:Company {companyId: 'C-1042'})-[:SUBMITTED]->(a:LoanApplication)
				      <-[:ABOUT]-(d:Decision)
				RETURN d.decidedAt AS decidedAt, a.submittedAt AS submittedAt
				""", Map.of());

		assertThat(times).hasSize(2).allSatisfy(record -> {
			assertThat(record.get("decidedAt").type().name()).isEqualTo("DATE_TIME");
			assertThat(record.get("submittedAt").type().name()).isEqualTo("DATE_TIME");
		});

		Record granted = query("MATCH (e:Exception) RETURN e.grantedAt AS grantedAt", Map.of())
			.get(0);

		assertThat(granted.get("grantedAt").type().name()).isEqualTo("DATE_TIME");
	}

	/** Every property this module MERGEs on, so a MERGE is a seek and never a scan. */
	@Test
	void everyMergeKeyIsConstrained() {
		List<String> constrained = query("SHOW CONSTRAINTS YIELD labelsOrTypes, properties "
				+ "RETURN labelsOrTypes[0] + '.' + properties[0] AS key", Map.of()).stream()
			.map(record -> record.get("key").asString())
			.toList();

		assertThat(constrained).contains("Company.companyId", "Policy.key",
				"LoanApplication.applicationId", "Decision.decisionId");
	}

	/** Seeding again is what happens on every app start, and it has to change nothing. */
	@Test
	void reseedingAddsNothing() {
		new GraphSeeder(driver, DATABASE).run();
		new GraphSeeder(driver, DATABASE).run();

		assertThat(this.graph.findPriorDenials("C-1042", window())).hasSize(1);
		assertThat(this.graph.findDecisions("C-1042")).hasSize(1);
		assertThat(this.graph.loadPolicies()).hasSize(3);
	}

	@Test
	void companiesAndTheirNumbersComeBackFromTheGraph() {
		Company company = this.graph.findCompany("C-1042").orElseThrow();

		assertThat(company.name()).isEqualTo("Ridgeline Builders");
		assertThat(company.creditRiskScore()).isEqualTo(72);
		assertThat(this.graph.findCompany("C-9999")).isEmpty();
	}

	private String save(String companyId, LoanVerdict verdict, PolicyResult crossed,
			List<String> causes) {
		return this.graph.saveDecision(companyId, 250_000, verdict, crossed, causes, CONVERSATION);
	}

	private List<String> appliedPolicies(String decisionId) {
		return policiesJoinedBy(decisionId, "APPLIED_POLICY");
	}

	private List<String> weighedPast(String decisionId) {
		return policiesJoinedBy(decisionId, "WEIGHED_PAST");
	}

	/**
	 * The relationship type is interpolated rather than parameterised, because Cypher does not
	 * take a type as a parameter. It is a constant in this file either way.
	 */
	private List<String> policiesJoinedBy(String decisionId, String type) {
		return query("MATCH (:Decision {decisionId: $id})-[:" + type + "]->(p:Policy) "
				+ "RETURN p.key AS key", Map.of("id", decisionId)).stream()
			.map(record -> record.get("key").asString())
			.toList();
	}

	private List<String> causedBy(String decisionId) {
		return query("""
				MATCH (:Decision {decisionId: $id})-[:ESCALATED_FROM]->(cause:Decision)
				RETURN cause.decisionId AS decisionId
				""", Map.of("id", decisionId)).stream()
			.map(record -> record.get("decisionId").asString())
			.toList();
	}

	private static List<Record> query(String cypher, Map<String, Object> parameters) {
		return driver.executableQuery(cypher).withParameters(parameters).execute().records();
	}

	/** Read back rather than hardcoded, so editing seed.json cannot leave these assertions stale. */
	private long window() {
		return this.graph.loadPolicies().get(PolicyEngine.REPEAT_DENIAL_ESCALATION).windowMonths();
	}

	/**
	 * The verdicts stand in for what a model returns. They are written by hand rather than by
	 * calling one, because what these tests are about is the graph: no assertion here depends on
	 * a model having reached the outcome, only on the outcome having been written faithfully.
	 */
	private static LoanVerdict denial() {
		return new LoanVerdict(LoanVerdict.Outcome.DENIED, "Debt load is too high for this file.",
				PolicyEngine.DEBT_TO_INCOME_LIMIT, List.of(),
				"Your debt load is too high for this.", LoanVerdict.Confidence.CLEAR);
	}

	private static LoanVerdict approval() {
		return new LoanVerdict(LoanVerdict.Outcome.APPROVED, "Strong enough to carry the extra.",
				PolicyEngine.DEBT_TO_INCOME_LIMIT, List.of(),
				"Approved, and here is why we were comfortable.",
				LoanVerdict.Confidence.BORDERLINE);
	}

	private static LoanVerdict escalation() {
		return new LoanVerdict(LoanVerdict.Outcome.DENIED, "Too many denials still standing.",
				PolicyEngine.REPEAT_DENIAL_ESCALATION, List.of(),
				"We cannot lend again while the earlier denials still stand.",
				LoanVerdict.Confidence.CLEAR);
	}

	/** The engine's measurement of the line those verdicts name, which is what the edge gets. */
	private static PolicyResult belowTheLine() {
		return new PolicyResult(PolicyEngine.DEBT_TO_INCOME_LIMIT, "Debt to Income Limit", false,
				0.48, 0.40, "48% with this loan, must be under 40%");
	}

	private static PolicyResult atTheThreshold() {
		return new PolicyResult(PolicyEngine.REPEAT_DENIAL_ESCALATION, "Repeat Denial Escalation",
				false, 2, 2, "2 prior denials in the last 12 months, escalates at 2");
	}

}
