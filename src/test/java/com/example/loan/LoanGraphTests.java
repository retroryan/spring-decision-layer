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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
			.allSatisfy(decision -> assertThat(decision.policyName()).isNull())
			.allSatisfy(decision -> assertThat(decision.line()).isEqualTo("the file as a whole"));
	}

	/**
	 * The listing resolves both policy edges, so an approval names the line it was granted past
	 * instead of reporting no policy at all. Reading only APPLIED_POLICY printed "no policy
	 * named" for a decision that had recorded its line correctly, and that phrase is the one this
	 * design reserves for the fact having been lost.
	 */
	@Test
	void anApprovalInTheListingNamesTheLineItWasGrantedPast() {
		save("C-1077", approval(), belowTheLine(), List.of());

		assertThat(this.graph.findDecisions("C-1077")).singleElement().satisfies(decision -> {
			assertThat(decision.policyName()).isEqualTo("Debt to Income Limit");
			assertThat(decision.weighedPast()).isTrue();
			assertThat(decision.line()).isEqualTo("approved past Debt to Income Limit");
		});
	}

	/** The same line read back off the other edge is the line that stopped the loan, not one
	 * anybody was granted past. */
	@Test
	void aDenialInTheListingNamesTheLineThatStoppedIt() {
		save("C-1077", denial(), belowTheLine(), List.of());

		assertThat(this.graph.findDecisions("C-1077")).singleElement().satisfies(decision -> {
			assertThat(decision.weighedPast()).isFalse();
			assertThat(decision.line()).isEqualTo("Debt to Income Limit");
		});
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
	 *
	 * Three denials on file and one of them excepted is also the position the demo is built on:
	 * two still count, which is exactly where Repeat Denial Escalation trips, on a company whose
	 * other numbers all clear.
	 */
	@Test
	void anExceptedDenialStaysOnFileAndStopsCounting() {
		assertThat(this.graph.findDecisions("C-1123")).hasSize(3)
			.allMatch(decision -> LoanVerdict.Outcome.DENIED.name().equals(decision.outcome()));

		assertThat(this.graph.findPriorDenials("C-1123", window()))
			.containsExactly("D-1123-SEED-1", "D-1123-SEED-3");
	}

	/** The excepted denial is marked where it is listed, so the count and the listing agree. */
	@Test
	void theListingSaysWhichDenialWasExcepted() {
		assertThat(this.graph.findDecisions("C-1123")).extracting(PastDecision::excepted)
			.containsExactly(false, true, false);
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

		assertThat(this.graph.findPrecedentTrail("C-1123")).hasSize(3)
			.anySatisfy(denial -> {
				assertThat(denial.decisionId()).isEqualTo("D-1123-SEED-2");
				assertThat(denial.grantedBy()).isEqualTo("Dana Whitfield, Commercial Underwriter");
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
	 * The roster is read out of the graph and not out of the file, because the draw and the fourth
	 * node of the context graph are the same object. Three people, each with the wording that goes
	 * in the prompt.
	 */
	@Test
	void theRosterComesBackFromTheGraphAsPeople() {
		List<Underwriter> roster = this.graph.findUnderwriters();

		assertThat(roster).extracting(Underwriter::name)
			.containsExactly("Dana Whitfield", "Marcus Feld", "Priya Raman");
		assertThat(roster).allSatisfy(underwriter -> {
			assertThat(underwriter.underwriterId()).startsWith("U-");
			assertThat(underwriter.title()).isNotBlank();
			assertThat(underwriter.label()).isNotBlank();
			assertThat(underwriter.disposition()).isNotBlank();
			assertThat(underwriter.yearsOnTheJob()).isPositive();
		});
		assertThat(rosterMember("Marcus Feld").yearsOnTheJob()).isEqualTo(17);
	}

	/**
	 * A decision knows who made it, which is the fact the schema had nobody to hold before. It is
	 * written by the same statement that writes the decision, so no decision exists for even a
	 * moment without a decider on it.
	 */
	@Test
	void aDecisionIsJoinedToTheUnderwriterWhoMadeIt() {
		Underwriter priya = rosterMember("Priya Raman");

		String decisionId = save("C-1042", denial(), belowTheLine(), List.of(), priya);

		Record stored = query("""
				MATCH (:Decision {decisionId: $id})-[:DECIDED_BY]->(u:Underwriter)
				RETURN u.underwriterId AS underwriterId, u.name AS name
				""", Map.of("id", decisionId)).get(0);

		assertThat(stored.get("underwriterId").asString()).isEqualTo(priya.underwriterId());
		assertThat(stored.get("name").asString()).isEqualTo("Priya Raman");
	}

	/**
	 * The wording rides on the edge for the same reason the two numbers ride on APPLIED_POLICY:
	 * retuning a disposition in seed.json must not rewrite why a decision made last month came out
	 * the way it did. The node moves on; the edge keeps what the underwriter was reading at the
	 * time.
	 */
	@Test
	void theDispositionOnTheEdgeIsTheWordingAsItStoodAtDecisionTime() {
		Underwriter drawn = onDuty();
		String decisionId = save("C-1042", denial(), belowTheLine(), List.of(), drawn);

		query("MATCH (u:Underwriter {underwriterId: $id}) SET u.disposition = $retuned",
				Map.of("id", drawn.underwriterId(), "retuned", "Retuned since this was decided."));

		assertThat(dispositionOn(decisionId)).isEqualTo(drawn.disposition());
		assertThat(rosterMember(drawn.name()).disposition())
			.isEqualTo("Retuned since this was decided.");
	}

	/**
	 * Every name in the seeded history is somebody a later run can draw, so the read back is
	 * populated on a graph nobody has run against yet and no fourth underwriter appears that the
	 * roster cannot explain. The denials are the cautious one's; the exception setting one of them
	 * aside is the permissive one's.
	 */
	@Test
	void theSeededHistoryIsAttributedToPeopleTheRosterCanExplain() {
		List<String> deciders = query("""
				MATCH (:Decision)-[:DECIDED_BY]->(u:Underwriter)
				RETURN DISTINCT u.name AS name
				""", Map.of()).stream().map(record -> record.get("name").asString()).toList();

		assertThat(deciders).containsExactly("Marcus Feld");

		Record granted = query("""
				MATCH (e:Exception)-[:GRANTED_BY]->(u:Underwriter)
				RETURN e.grantedBy AS grantedBy, u.name AS grantorName
				""", Map.of()).get(0);

		assertThat(granted.get("grantedBy").asString()).contains("Dana Whitfield");
		assertThat(granted.get("grantorName").asString()).isEqualTo("Dana Whitfield");
	}

	/**
	 * The traversal the fourth node exists for: which underwriter approves past which line, and
	 * how often. A denial on the same policy is joined by APPLIED_POLICY instead, so it is
	 * counted by nothing here, which is what makes the number mean approvals rather than
	 * decisions.
	 */
	@Test
	void theReadBackSaysWhichUnderwriterApprovesPastWhichLine() {
		Underwriter dana = rosterMember("Dana Whitfield");
		Underwriter marcus = rosterMember("Marcus Feld");
		save("C-1042", approval(), belowTheLine(), List.of(), dana);
		save("C-1042", approval(), belowTheLine(), List.of(), dana);
		save("C-1042", approval(), belowTheLine(), List.of(), marcus);
		save("C-1042", denial(), belowTheLine(), List.of(), marcus);

		assertThat(this.graph.findApprovalsPastPolicies()).containsExactly(
				new UnderwriterApprovals("Dana Whitfield", "Debt to Income Limit", 2),
				new UnderwriterApprovals("Marcus Feld", "Debt to Income Limit", 1));
	}

	/** Nobody has approved past a line on a freshly seeded graph, and the read says so. */
	@Test
	void theReadBackIsEmptyUntilSomebodyApprovesPastALine() {
		assertThat(this.graph.findApprovalsPastPolicies()).isEmpty();
	}

	/**
	 * The write grantException owns: an Exception node CREATEd fresh and joined both to the
	 * denial it sets aside and to the underwriter granting it, marked source 'underwriter' so
	 * it reads differently from the one seed.json ships.
	 */
	@Test
	void grantingAnExceptionJoinsItToTheDenialAndTheGrantor() {
		Underwriter dana = rosterMember("Dana Whitfield");

		this.graph.grantException("D-1123-SEED-1", "Since resolved.", dana);

		Record stored = query("""
				MATCH (e:Exception)-[:EXCEPTION_TO]->(:Decision {decisionId: $id})
				MATCH (e)-[:GRANTED_BY]->(:Underwriter {underwriterId: $underwriterId})
				RETURN e.source AS source, e.grantedBy AS grantedBy, e.justification AS justification
				""", Map.of("id", "D-1123-SEED-1", "underwriterId", dana.underwriterId())).get(0);

		assertThat(stored.get("source").asString()).isEqualTo("underwriter");
		assertThat(stored.get("grantedBy").asString()).isEqualTo("Dana Whitfield, Commercial Underwriter");
		assertThat(stored.get("justification").asString()).isEqualTo("Since resolved.");
	}

	/** A decisionId nothing in the graph holds is a bug in the caller, not a silent no-op. */
	@Test
	void grantingAnExceptionAgainstADecisionThatDoesNotExistFailsRatherThanWritingNothing() {
		Underwriter dana = rosterMember("Dana Whitfield");

		assertThatIllegalStateException()
			.isThrownBy(() -> this.graph.grantException("D-DOES-NOT-EXIST", "Since resolved.", dana))
			.withMessageContaining("D-DOES-NOT-EXIST");
	}

	/** The read back: every grant on file, who made it, and whose denial it set aside. */
	@Test
	void theReadBackListsWhoHasSetAsideWhoseDenial() {
		Underwriter dana = rosterMember("Dana Whitfield");

		this.graph.grantException("D-1123-SEED-1", "Second chance.", dana);

		assertThat(this.graph.findExceptionGrants()).hasSize(2)
			.anySatisfy(grant -> {
				assertThat(grant.decisionId()).isEqualTo("D-1123-SEED-1");
				assertThat(grant.justification()).isEqualTo("Second chance.");
				assertThat(grant.decidedBy()).isEqualTo("Marcus Feld");
				assertThat(grant.grantedBy()).contains("Dana Whitfield");
			});
	}

	/** The seeded grant is on the read back before anything in a run has granted another. */
	@Test
	void theReadBackHoldsTheSeededGrantOnAFreshGraph() {
		assertThat(this.graph.findExceptionGrants()).singleElement().satisfies(grant -> {
			assertThat(grant.decisionId()).isEqualTo("D-1123-SEED-2");
			assertThat(grant.decidedBy()).isEqualTo("Marcus Feld");
			assertThat(grant.grantedBy()).contains("Dana Whitfield");
		});
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
				"LoanApplication.applicationId", "Decision.decisionId", "Exception.exceptionId",
				"Underwriter.underwriterId");
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
		return save(companyId, verdict, crossed, causes, onDuty());
	}

	private String save(String companyId, LoanVerdict verdict, PolicyResult crossed,
			List<String> causes, Underwriter underwriter) {
		return this.graph.saveDecision(companyId, 250_000, verdict, underwriter, crossed, causes,
				CONVERSATION);
	}

	/**
	 * Read out of the graph rather than built by hand, because the DECIDED_BY edge MATCHes the
	 * Underwriter node: an id seed.json does not hold would write no decision at all. Which of
	 * the three it is does not matter to a test that is about the edge.
	 */
	private Underwriter onDuty() {
		return this.graph.findUnderwriters().get(0);
	}

	/** By name, for the two tests where which person decided is the thing being asserted. */
	private Underwriter rosterMember(String name) {
		return this.graph.findUnderwriters()
			.stream()
			.filter(underwriter -> underwriter.name().equals(name))
			.findFirst()
			.orElseThrow();
	}

	private String dispositionOn(String decisionId) {
		return query("""
				MATCH (:Decision {decisionId: $id})-[decided:DECIDED_BY]->(:Underwriter)
				RETURN decided.disposition AS disposition
				""", Map.of("id", decisionId)).get(0).get("disposition").asString();
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
				"Your debt load is too high for this.", LoanVerdict.Confidence.CLEAR, null);
	}

	private static LoanVerdict approval() {
		return new LoanVerdict(LoanVerdict.Outcome.APPROVED, "Strong enough to carry the extra.",
				PolicyEngine.DEBT_TO_INCOME_LIMIT, List.of(),
				"Approved, and here is why we were comfortable.",
				LoanVerdict.Confidence.BORDERLINE, null);
	}

	private static LoanVerdict escalation() {
		return new LoanVerdict(LoanVerdict.Outcome.DENIED, "Too many denials still standing.",
				PolicyEngine.REPEAT_DENIAL_ESCALATION, List.of(),
				"We cannot lend again while the earlier denials still stand.",
				LoanVerdict.Confidence.CLEAR, null);
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
