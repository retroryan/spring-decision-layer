package com.example.loan;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.neo4j.driver.Driver;
import org.neo4j.driver.QueryConfig;
import org.neo4j.driver.Record;
import org.neo4j.driver.RoutingControl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The context graph, in Neo4j.
 *
 * <pre>
 * (Company)-[:SUBMITTED]-&gt;(LoanApplication)&lt;-[:ABOUT]-(Decision)-[:APPLIED_POLICY]-&gt;(Policy)
 *                                                       (Decision)-[:ESCALATED_FROM]-&gt;(Decision)
 *                                  (Exception)-[:EXCEPTION_TO]-&gt;(Decision)
 * </pre>
 *
 * An approved decision has no APPLIED_POLICY relationship at all, because nothing causes an
 * approval the way a failing rule causes a denial, so every read below treats that hop as
 * optional.
 *
 * {@code spring.neo4j.*} has no property for the target database, and
 * {@code spring.data.neo4j.database} belongs to Spring Data Neo4j, which is not on this
 * module's classpath, so {@code loan.neo4j.database} is this module's own setting for it.
 */
@Component
class LoanGraph {

	private static final String FIND_COMPANY = """
			MATCH (c:Company {companyId: $companyId})
			RETURN c.companyId AS companyId, c.name AS name, c.creditRiskScore AS creditRiskScore,
			       c.currentDebt AS currentDebt, c.annualIncome AS annualIncome
			""";

	/**
	 * Only used to answer an unknown company id. Naming the ids the graph actually holds keeps the
	 * message from suggesting the id that just failed, and tells an empty graph apart from a typo.
	 */
	private static final String FIND_COMPANY_IDS = """
			MATCH (c:Company)
			RETURN c.companyId AS companyId
			ORDER BY companyId
			""";

	private static final String LOAD_POLICIES = """
			MATCH (p:Policy)
			RETURN p.key AS key, p.name AS name, p.threshold AS threshold,
			       p.windowMonths AS windowMonths, p.description AS description
			""";

	/**
	 * Two filters, and they fail differently. The window is why decidedAt is a Neo4j datetime
	 * rather than ISO text: the comparison is temporal, so precedent ages out on its own instead
	 * of piling up forever. The EXCEPTION_TO check is why an exception is not a deletion: the
	 * denial stays on file with its policy and its numbers and stops counting.
	 */
	private static final String FIND_PRIOR_DENIALS = """
			MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
			      <-[:ABOUT]-(d:Decision {outcome: $denied})
			WHERE d.decidedAt > datetime() - duration({months: $windowMonths})
			  AND NOT EXISTS { (:Exception)-[:EXCEPTION_TO]->(d) }
			RETURN d.decisionId AS decisionId
			ORDER BY d.decidedAt
			""";

	private static final String FIND_DECISIONS = """
			MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(a:LoanApplication)
			      <-[:ABOUT]-(d:Decision)
			OPTIONAL MATCH (d)-[:APPLIED_POLICY]->(p:Policy)
			OPTIONAL MATCH (e:Exception)-[:EXCEPTION_TO]->(d)
			RETURN d.decidedAt AS decidedAt, d.outcome AS outcome,
			       a.requestedAmount AS requestedAmount, p.name AS policyName,
			       e IS NOT NULL AS excepted
			ORDER BY d.decidedAt
			""";

	/**
	 * The three hops a graph adds to a stored decision, in one query: the policy that decided it,
	 * the exception that modified it, and every later decision it has since governed. The last one
	 * is ESCALATED_FROM read backwards, which is the whole argument for keeping decisions joined
	 * rather than merely stored.
	 */
	private static final String FIND_PRECEDENT_TRAIL = """
			MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
			      <-[:ABOUT]-(d:Decision {outcome: $denied})
			OPTIONAL MATCH (d)-[:APPLIED_POLICY]->(p:Policy)
			OPTIONAL MATCH (e:Exception)-[:EXCEPTION_TO]->(d)
			OPTIONAL MATCH (later:Decision)-[:ESCALATED_FROM]->(d)
			RETURN d.decisionId AS decisionId, d.decidedAt AS decidedAt, p.name AS policyName,
			       e.grantedBy AS grantedBy, e.justification AS justification,
			       collect(DISTINCT later.decisionId) AS governed
			ORDER BY d.decidedAt
			""";

	/**
	 * FOREACH over a list that is empty or holds one policy is how Cypher writes an optional
	 * relationship: for an approval $policyKey is null, the OPTIONAL MATCH finds nothing, and
	 * no APPLIED_POLICY is created. Likewise $escalatedFrom is empty unless history decided.
	 */
	private static final String SAVE_DECISION = """
			MATCH (c:Company {companyId: $companyId})
			OPTIONAL MATCH (p:Policy {key: $policyKey})
			OPTIONAL MATCH (earlier:Decision) WHERE earlier.decisionId IN $escalatedFrom
			WITH c, p, collect(earlier) AS causes
			CREATE (c)-[:SUBMITTED]->(a:LoanApplication {applicationId: $applicationId,
			        requestedAmount: $requestedAmount, submittedAt: $at})
			CREATE (d:Decision {decisionId: $decisionId, outcome: $outcome, reason: $reason,
			        decidedAt: $at, conversationId: $conversationId})
			CREATE (d)-[:ABOUT]->(a)
			FOREACH (policy IN CASE WHEN p IS NULL THEN [] ELSE [p] END |
			  CREATE (d)-[:APPLIED_POLICY {observed: $observed, threshold: $threshold}]->(policy))
			FOREACH (cause IN causes |
			  CREATE (d)-[:ESCALATED_FROM]->(cause))
			RETURN d.decisionId AS decisionId
			""";

	private static final String ATTACH_EXPLANATION = """
			MATCH (d:Decision {decisionId: $decisionId})
			SET d.explanation = $explanation
			RETURN count(d) AS updated
			""";

	private final Driver driver;

	/** Reads are routed to a follower where the cluster has one. Writes are not. */
	private final QueryConfig read;

	private final QueryConfig write;

	/**
	 * Naming the database skips the home database resolution the server does per session. Left
	 * unset it is the empty string, which the builder rejects, so both configs fall back to
	 * that resolution rather than naming a database that does not exist.
	 */
	LoanGraph(Driver driver, @Value("${loan.neo4j.database:}") String database) {
		this.driver = driver;
		QueryConfig.Builder readBuilder = QueryConfig.builder().withRouting(RoutingControl.READ);
		QueryConfig.Builder writeBuilder = QueryConfig.builder();
		if (StringUtils.hasText(database)) {
			readBuilder.withDatabase(database);
			writeBuilder.withDatabase(database);
		}
		this.read = readBuilder.build();
		this.write = writeBuilder.build();
	}

	Optional<Company> findCompany(String companyId) {
		return read(FIND_COMPANY, Map.of("companyId", companyId)).stream()
			.map(record -> new Company(record.get("companyId").asString(),
					record.get("name").asString(), record.get("creditRiskScore").asLong(),
					record.get("currentDebt").asLong(), record.get("annualIncome").asLong()))
			.findFirst();
	}

	List<String> findCompanyIds() {
		return read(FIND_COMPANY_IDS, Map.of()).stream()
			.map(record -> record.get("companyId").asString())
			.toList();
	}

	Map<String, Policy> loadPolicies() {
		return read(LOAD_POLICIES, Map.of()).stream()
			.map(record -> new Policy(record.get("key").asString(), record.get("name").asString(),
					record.get("threshold").asDouble(), record.get("windowMonths").asLong(0),
					record.get("description").asString()))
			.collect(Collectors.toMap(Policy::key, Function.identity()));
	}

	/**
	 * Walks SUBMITTED and then ABOUT, and returns what came back denied inside the window,
	 * oldest first. Ids rather than a count, because the ids are what {@link #saveDecision}
	 * joins the next decision to.
	 *
	 * @param windowMonths how far back to count, from the Repeat Denial Escalation policy node
	 */
	List<String> findPriorDenials(String companyId, long windowMonths) {
		return read(FIND_PRIOR_DENIALS, Map.of("companyId", companyId, "denied",
				LoanDecision.DENIED, "windowMonths", windowMonths)).stream()
			.map(record -> record.get("decisionId").asString())
			.toList();
	}

	/** The same walk, one hop further, resolving APPLIED_POLICY to the policy's name. */
	List<PastDecision> findDecisions(String companyId) {
		return read(FIND_DECISIONS, Map.of("companyId", companyId)).stream()
			.map(record -> new PastDecision(
					record.get("decidedAt").asZonedDateTime().toInstant(),
					record.get("outcome").asString(), record.get("requestedAmount").asLong(),
					record.get("policyName").asString(null), record.get("excepted").asBoolean()))
			.toList();
	}

	/** What {@link #FIND_PRECEDENT_TRAIL} walks, one row per denial on file. */
	List<PrecedentTrail> findPrecedentTrail(String companyId) {
		return read(FIND_PRECEDENT_TRAIL,
				Map.of("companyId", companyId, "denied", LoanDecision.DENIED)).stream()
			.map(record -> new PrecedentTrail(record.get("decisionId").asString(),
					record.get("decidedAt").asZonedDateTime().toInstant(),
					record.get("policyName").asString(null),
					record.get("grantedBy").asString(null),
					record.get("justification").asString(null),
					record.get("governed").asList(id -> id.asString())))
			.toList();
	}

	/**
	 * Called before the model runs, so what the bank decided is in the graph whether or not a
	 * sentence about it ever gets written.
	 *
	 * @param escalatedFrom the earlier decisions that caused this one, empty when history did
	 * not decide it. Only the caller knows which policy actually decided.
	 * @param conversationId a property rather than a relationship, because the Session node
	 * belongs to Spring AI's chat memory schema and does not exist yet at this point in the
	 * request, so the two kinds of memory join on a shared key.
	 */
	String saveDecision(String companyId, long requestedAmount, LoanDecision decision,
			List<String> escalatedFrom, String conversationId) {

		PolicyResult deciding = decision.decidingPolicy();

		// HashMap rather than Map.of, because three of these values are null for an approval
		// and Map.of rejects nulls. The driver maps a null value to Cypher's null.
		Map<String, Object> parameters = new HashMap<>();
		parameters.put("companyId", companyId);
		parameters.put("applicationId", "A-" + shortId());
		parameters.put("decisionId", "D-" + shortId());
		parameters.put("requestedAmount", requestedAmount);
		// A java.time value, not its toString, so it is stored as a Neo4j datetime and
		// ORDER BY is a real temporal sort.
		parameters.put("at", ZonedDateTime.now());
		parameters.put("outcome", decision.outcome());
		parameters.put("reason", decision.reason());
		parameters.put("conversationId", conversationId);
		parameters.put("escalatedFrom", escalatedFrom);
		parameters.put("policyKey", deciding != null ? deciding.key() : null);
		parameters.put("observed", deciding != null ? deciding.observed() : null);
		parameters.put("threshold", deciding != null ? deciding.threshold() : null);

		List<Record> written = write(SAVE_DECISION, parameters);
		if (written.isEmpty()) {
			throw new IllegalStateException("No company with id " + companyId
					+ " in the graph, so nothing was written. Restart the app to reseed it.");
		}
		return written.get(0).get("decisionId").asString();
	}

	/**
	 * Not called the reason: the reason is computed and committed before the model is called,
	 * and this is the sentence the applicant was told afterward.
	 */
	void attachExplanation(String decisionId, String explanation) {
		long updated = write(ATTACH_EXPLANATION,
				Map.of("decisionId", decisionId, "explanation", explanation))
			.get(0)
			.get("updated")
			.asLong();
		if (updated == 0) {
			throw new IllegalStateException("No decision " + decisionId + " to explain.");
		}
	}

	private List<Record> read(String cypher, Map<String, Object> parameters) {
		return this.driver.executableQuery(cypher)
			.withParameters(parameters)
			.withConfig(this.read)
			.execute()
			.records();
	}

	private List<Record> write(String cypher, Map<String, Object> parameters) {
		return this.driver.executableQuery(cypher)
			.withParameters(parameters)
			.withConfig(this.write)
			.execute()
			.records();
	}

	/**
	 * Short enough to paste into Neo4j Browser. A truncated UUID can repeat, and the uniqueness
	 * constraints on these ids turn that from a silent duplicate into a failed write.
	 */
	private static String shortId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
