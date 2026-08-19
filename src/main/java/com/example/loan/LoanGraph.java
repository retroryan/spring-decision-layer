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
 *                                                       (Decision)-[:WEIGHED_PAST]-&gt;(Policy)
 *                                                       (Decision)-[:ESCALATED_FROM]-&gt;(Decision)
 *                                  (Exception)-[:EXCEPTION_TO]-&gt;(Decision)
 * </pre>
 *
 * APPLIED_POLICY and WEIGHED_PAST carry the same two properties and point at the same node, and
 * the type is the whole difference: one says a line stopped the loan, the other says the loan
 * was approved past it. Only one of the two is ever written, and neither is written when the
 * verdict named no policy, so every read below treats that hop as optional.
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
	 * the exception that modified it, and the chain of later decisions it has since driven. The
	 * last one is ESCALATED_FROM read backwards, which is the whole argument for keeping decisions
	 * joined rather than merely stored.
	 *
	 * The chain is variable length rather than one hop, because a decision that cited a denial
	 * can itself be cited, and a fixed hop count would report the first link of a chain as though
	 * it were the whole thing. min(length(path)) is how far away the nearest route is, so a
	 * decision reachable two ways is reported once at its shortest distance. The COLLECT subquery
	 * rather than an OPTIONAL MATCH is what keeps a denial that has driven nothing returning an
	 * empty list instead of one row of nulls.
	 */
	private static final String FIND_PRECEDENT_TRAIL = """
			MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
			      <-[:ABOUT]-(d:Decision {outcome: $denied})
			OPTIONAL MATCH (d)-[:APPLIED_POLICY]->(p:Policy)
			OPTIONAL MATCH (e:Exception)-[:EXCEPTION_TO]->(d)
			RETURN d.decisionId AS decisionId, d.decidedAt AS decidedAt, p.name AS policyName,
			       e.grantedBy AS grantedBy, e.justification AS justification,
			       COLLECT {
			         MATCH path = (later:Decision)-[:ESCALATED_FROM*1..]->(d)
			         WITH later, min(length(path)) AS depth
			         RETURN {depth: depth, decisionId: later.decisionId} AS step
			         ORDER BY depth, later.decisionId
			       } AS governed
			ORDER BY d.decidedAt
			""";

	/**
	 * FOREACH over a list that is empty or holds one policy is how Cypher writes an optional
	 * relationship: a verdict that named no policy leaves $policyKey null, the OPTIONAL MATCH
	 * finds nothing, and neither policy edge is created. $approved routes the one that is,
	 * because the same key means two different things depending on how the run came out.
	 *
	 * $escalatedFrom is the ids the model cited, already filtered to the denials that were sent
	 * to it. The OPTIONAL MATCH resolves them, so an id that no longer exists is dropped here
	 * rather than failing the write.
	 */
	private static final String SAVE_DECISION = """
			MATCH (c:Company {companyId: $companyId})
			OPTIONAL MATCH (p:Policy {key: $policyKey})
			OPTIONAL MATCH (earlier:Decision) WHERE earlier.decisionId IN $escalatedFrom
			WITH c, p, collect(earlier) AS causes
			CREATE (c)-[:SUBMITTED]->(a:LoanApplication {applicationId: $applicationId,
			        requestedAmount: $requestedAmount, submittedAt: $at})
			CREATE (d:Decision {decisionId: $decisionId, outcome: $outcome, reason: $reason,
			        confidence: $confidence, explanation: $explanation, decidedAt: $at,
			        conversationId: $conversationId})
			CREATE (d)-[:ABOUT]->(a)
			FOREACH (policy IN CASE WHEN p IS NULL OR $approved THEN [] ELSE [p] END |
			  CREATE (d)-[:APPLIED_POLICY {observed: $observed, threshold: $threshold}]->(policy))
			FOREACH (policy IN CASE WHEN p IS NULL OR NOT $approved THEN [] ELSE [p] END |
			  CREATE (d)-[:WEIGHED_PAST {observed: $observed, threshold: $threshold}]->(policy))
			FOREACH (cause IN causes |
			  CREATE (d)-[:ESCALATED_FROM]->(cause))
			RETURN d.decisionId AS decisionId
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
	 * oldest first. Ids rather than a count, because these are the ids the model is shown and
	 * the only ones its citations are accepted from.
	 *
	 * @param windowMonths how far back to count, from the Repeat Denial Escalation policy node
	 */
	List<String> findPriorDenials(String companyId, long windowMonths) {
		return read(FIND_PRIOR_DENIALS, Map.of("companyId", companyId, "denied",
				LoanVerdict.Outcome.DENIED.name(), "windowMonths", windowMonths)).stream()
			.map(record -> record.get("decisionId").asString())
			.toList();
	}

	/**
	 * The same walk, one hop further, resolving APPLIED_POLICY to the policy's name. Only that
	 * edge, because this is the listing of what stopped past loans; the line an approval was
	 * granted past is on WEIGHED_PAST and is read by the trail rather than the listing.
	 */
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
				Map.of("companyId", companyId, "denied", LoanVerdict.Outcome.DENIED.name())).stream()
			.map(record -> new PrecedentTrail(record.get("decisionId").asString(),
					record.get("decidedAt").asZonedDateTime().toInstant(),
					record.get("policyName").asString(null),
					record.get("grantedBy").asString(null),
					record.get("justification").asString(null),
					record.get("governed").asList(step -> new PrecedentStep(
							step.get("depth").asLong(), step.get("decisionId").asString()))))
			.toList();
	}

	/**
	 * Called after the model answers, because there is no outcome until it does. One write
	 * rather than two: the sentence the applicant was told goes on the node with the verdict
	 * that produced it, instead of being attached a moment later.
	 *
	 * @param crossed the policy the verdict named, resolved to the engine's own measurement of
	 * it, or null when the verdict named none. The verdict supplies the key and the engine
	 * supplies the numbers, so the edge cannot claim a measurement nothing took.
	 * @param escalatedFrom the denials the model cited, already filtered by the caller to the
	 * ones it was shown. Empty when history did not move it.
	 * @param conversationId a property rather than a relationship, because the Session node
	 * belongs to Spring AI's chat memory schema and does not exist yet at this point in the
	 * request, so the two kinds of memory join on a shared key.
	 */
	String saveDecision(String companyId, long requestedAmount, LoanVerdict verdict,
			PolicyResult crossed, List<String> escalatedFrom, String conversationId) {

		// HashMap rather than Map.of, because three of these values are null when the verdict
		// named no policy and Map.of rejects nulls. The driver maps a null value to Cypher's null.
		Map<String, Object> parameters = new HashMap<>();
		parameters.put("companyId", companyId);
		parameters.put("applicationId", "A-" + shortId());
		parameters.put("decisionId", "D-" + shortId());
		parameters.put("requestedAmount", requestedAmount);
		// A java.time value, not its toString, so it is stored as a Neo4j datetime and
		// ORDER BY is a real temporal sort.
		parameters.put("at", ZonedDateTime.now());
		parameters.put("outcome", verdict.outcomeName());
		parameters.put("reason", verdict.reason());
		parameters.put("confidence", verdict.confidence().name());
		parameters.put("explanation", verdict.explanation());
		parameters.put("approved", verdict.approved());
		parameters.put("conversationId", conversationId);
		parameters.put("escalatedFrom", escalatedFrom);
		parameters.put("policyKey", crossed != null ? crossed.key() : null);
		parameters.put("observed", crossed != null ? crossed.observed() : null);
		parameters.put("threshold", crossed != null ? crossed.threshold() : null);

		List<Record> written = write(SAVE_DECISION, parameters);
		if (written.isEmpty()) {
			throw new IllegalStateException("No company with id " + companyId
					+ " in the graph, so nothing was written. Restart the app to reseed it.");
		}
		return written.get(0).get("decisionId").asString();
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
