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
		save("C-1042", denial(), List.of());

		assertThat(new LoanGraph(driver, DATABASE).findPriorDenials("C-1042", window())).hasSize(2);
	}

	@Test
	void anExplanationAttachesToTheDecisionAlreadyWritten() {
		String decisionId = save("C-1042", denial(), List.of());

		this.graph.attachExplanation(decisionId, "Denied on debt to income.");

		Record stored = query("MATCH (d:Decision {decisionId: $id}) RETURN d.explanation AS e",
				Map.of("id", decisionId)).get(0);

		assertThat(stored.get("e").asString()).isEqualTo("Denied on debt to income.");
	}

	/** No APPLIED_POLICY relationship is written, so the history comes back with no name. */
	@Test
	void anApprovalIsStoredWithNoDecidingPolicy() {
		save("C-1077", new LoanDecision(LoanDecision.APPROVED, "All policies passed.", null,
				List.of(), 0), List.of());

		PastDecision stored = this.graph.findDecisions("C-1077").get(0);

		assertThat(stored.outcome()).isEqualTo(LoanDecision.APPROVED);
		assertThat(stored.policyName()).isNull();
	}

	@Test
	void historyComesBackOldestFirst() {
		save("C-1042", denial(), List.of());

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

		String decisionId = save("C-1042", escalation(), causes);

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
			.allMatch(decision -> LoanDecision.DENIED.equals(decision.outcome()));

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
	 * exception that set it aside, and the later decisions that denial has since decided.
	 */
	@Test
	void theTrailWalksToThePolicyTheExceptionAndWhatTheDenialHasSinceDecided() {
		String escalated = save("C-1042", escalation(), List.of("D-1042-SEED"));

		// Two denials on file now: the seeded one, and the escalation that cites it.
		assertThat(this.graph.findPrecedentTrail("C-1042")).hasSize(2)
			.anySatisfy(denial -> {
				assertThat(denial.decisionId()).isEqualTo("D-1042-SEED");
				assertThat(denial.policyName()).isEqualTo("Debt to Income Limit");
				assertThat(denial.excepted()).isFalse();
				assertThat(denial.governed()).containsExactly(escalated);
			});

		assertThat(this.graph.findPrecedentTrail("C-1123")).hasSize(2)
			.anySatisfy(denial -> {
				assertThat(denial.decisionId()).isEqualTo("D-1123-SEED-2");
				assertThat(denial.grantedBy()).isEqualTo("M. Alvarez, Senior Underwriter");
				assertThat(denial.justification()).contains("bridge financing");
				assertThat(denial.governed()).isEmpty();
			});
	}

	/** Everything else read the history and was not decided by it, so nothing is joined. */
	@Test
	void aDecisionMadeByArithmeticIsJoinedToNothing() {
		String decisionId = save("C-1042", denial(), List.of());

		assertThat(causedBy(decisionId)).isEmpty();
	}

	/**
	 * The transcript and the decision are two schemas in one database. This is the key that
	 * makes that worth anything: from a decision you can find the conversation it was
	 * explained in, and from a conversation you can find what it decided.
	 */
	@Test
	void theDecisionCarriesTheConversationItWasExplainedIn() {
		String decisionId = save("C-1042", denial(), List.of());

		Record stored = query("MATCH (d:Decision {decisionId: $id}) RETURN d.conversationId AS c",
				Map.of("id", decisionId)).get(0);

		assertThat(stored.get("c").asString()).isEqualTo(CONVERSATION);
	}

	/**
	 * Stored as a temporal type rather than as ISO text, so ORDER BY is a real sort and a
	 * reader can ask for the denials inside the last twelve months.
	 */
	/** Backs the unknown-id message: an empty graph and a typo have to be told apart. */
	@Test
	void theGraphNamesTheCompaniesItHolds() {
		assertThat(this.graph.findCompanyIds()).containsExactly("C-1042", "C-1077", "C-1096",
				"C-1123");
	}

	@Test
	void timesAreNeo4jDatetimesAndNotStrings() {
		save("C-1042", denial(), List.of());

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

	private String save(String companyId, LoanDecision decision, List<String> causes) {
		return this.graph.saveDecision(companyId, 250_000, decision, causes, CONVERSATION);
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

	private static LoanDecision denial() {
		PolicyResult failed = new PolicyResult(PolicyEngine.DEBT_TO_INCOME_LIMIT,
				"Debt to Income Limit", false, 0.48, 0.40, "48% with this loan, must be under 40%");
		return new LoanDecision(LoanDecision.DENIED, "Failed Debt to Income Limit policy.", failed,
				List.of(failed), 1);
	}

	private static LoanDecision escalation() {
		PolicyResult failed = new PolicyResult(PolicyEngine.REPEAT_DENIAL_ESCALATION,
				"Repeat Denial Escalation", false, 2, 2,
				"2 prior denials in the last 12 months, escalates at 2");
		return new LoanDecision(LoanDecision.DENIED, "Failed Repeat Denial Escalation policy.",
				failed, List.of(failed), 2);
	}

}
