package com.example.loan;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.driver.Driver;
import org.neo4j.driver.QueryConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Puts seed.json into Neo4j at startup, ahead of the run that reads it.
 *
 * Everything MERGEs on a stable id, so starting the app ten times leaves the same four
 * companies, three policies, three underwriters, four historical denials, and the one exception
 * granted against one of them. The seeded decision is wired
 * exactly as {@link LoanGraph#saveDecision} wires a live one, so the traversal cannot tell
 * them apart.
 *
 * Idempotence is what makes the demo repeatable, and it is also what hides a deliberate change:
 * this runs before the advisor reads anything, so a relationship deleted between runs would be
 * back before the next run could notice it gone. Hence loan.seed.enabled, which ./run.sh --no-seed
 * sets to false for one run, leaving the graph exactly as it was left.
 */
@Component
@ConditionalOnProperty(name = "loan.seed.enabled", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE)
class GraphSeeder implements CommandLineRunner {

	/**
	 * One per property this module MERGEs or looks up by: a MERGE with no constraint behind it
	 * is a label scan, and two of them at once can write the same node twice. Constraint names
	 * are global on a shared database, hence the loan_ prefix.
	 */
	private static final List<String> CONSTRAINTS = List.of("""
			CREATE CONSTRAINT loan_company_id IF NOT EXISTS
			FOR (c:Company) REQUIRE c.companyId IS UNIQUE
			""", """
			CREATE CONSTRAINT loan_policy_key IF NOT EXISTS
			FOR (p:Policy) REQUIRE p.key IS UNIQUE
			""", """
			CREATE CONSTRAINT loan_application_id IF NOT EXISTS
			FOR (a:LoanApplication) REQUIRE a.applicationId IS UNIQUE
			""", """
			CREATE CONSTRAINT loan_decision_id IF NOT EXISTS
			FOR (d:Decision) REQUIRE d.decisionId IS UNIQUE
			""", """
			CREATE CONSTRAINT loan_exception_id IF NOT EXISTS
			FOR (e:Exception) REQUIRE e.exceptionId IS UNIQUE
			""", """
			CREATE CONSTRAINT loan_underwriter_id IF NOT EXISTS
			FOR (u:Underwriter) REQUIRE u.underwriterId IS UNIQUE
			""");

	/** One list parameter, UNWOUND in Cypher: one round trip and one plan per label, not row. */
	private static final String MERGE_COMPANY = """
			UNWIND $rows AS row
			MERGE (c:Company {companyId: row.companyId})
			SET c.name = row.name, c.creditRiskScore = row.creditRiskScore,
			    c.currentDebt = row.currentDebt, c.annualIncome = row.annualIncome
			""";

	/** Thresholds are rewritten on every start, so seed.json stays the source of truth. */
	private static final String MERGE_POLICY = """
			UNWIND $rows AS row
			MERGE (p:Policy {key: row.key})
			SET p.name = row.name, p.threshold = row.threshold,
			    p.windowMonths = row.windowMonths, p.description = row.description
			""";

	/**
	 * The wording is rewritten on every start, like the thresholds, so seed.json stays the source
	 * of truth for how each person reads a file. What is already on a DECIDED_BY edge is left
	 * alone, which is the point of putting it there.
	 */
	private static final String MERGE_UNDERWRITER = """
			UNWIND $rows AS row
			MERGE (u:Underwriter {underwriterId: row.underwriterId})
			SET u.name = row.name, u.title = row.title, u.yearsOnTheJob = row.yearsOnTheJob,
			    u.label = row.label, u.disposition = row.disposition
			""";

	private static final String MERGE_APPLICATION = """
			UNWIND $rows AS row
			MATCH (c:Company {companyId: row.companyId})
			MERGE (a:LoanApplication {applicationId: row.applicationId})
			SET a.requestedAmount = row.requestedAmount, a.submittedAt = row.submittedAt
			MERGE (c)-[:SUBMITTED]->(a)
			""";

	/**
	 * The policy hop is OPTIONAL because an approval has no policy that decided it. As a plain
	 * MATCH, a seeded approval would match nothing and the statement would quietly write no
	 * decision at all. Inside UNWIND it still runs per row, against that row's own policyKey.
	 *
	 * The underwriter hop is a plain MATCH, because every decision was made by somebody: an
	 * underwriterId that names nobody is a typo in seed.json, and the missing decision it leaves
	 * is a louder failure than a decision with no decider on it.
	 *
	 * The disposition is copied onto the edge from the node MERGEd moments ago in this same run,
	 * for the reason APPLIED_POLICY carries its two numbers: a later edit to how a person reads a
	 * file must not rewrite why the decisions they already made went the way they did.
	 */
	private static final String MERGE_DECISION = """
			UNWIND $rows AS row
			MATCH (a:LoanApplication {applicationId: row.applicationId})
			MATCH (u:Underwriter {underwriterId: row.underwriterId})
			OPTIONAL MATCH (p:Policy {key: row.policyKey})
			MERGE (d:Decision {decisionId: row.decisionId})
			SET d.outcome = row.outcome, d.reason = row.reason, d.decidedAt = row.decidedAt
			MERGE (d)-[:ABOUT]->(a)
			MERGE (d)-[decided:DECIDED_BY]->(u)
			SET decided.disposition = u.disposition
			FOREACH (policy IN CASE WHEN p IS NULL THEN [] ELSE [p] END |
			  MERGE (d)-[applied:APPLIED_POLICY]->(policy)
			  SET applied.observed = row.observed, applied.threshold = row.threshold)
			""";

	/**
	 * The decision is MATCHed rather than MERGEd: an exception against a decision that is not in
	 * the graph is a typo in seed.json, and writing an Exception hanging off nothing would hide
	 * it. Nothing here touches the denial itself, which is the point of an exception.
	 */
	private static final String MERGE_EXCEPTION = """
			UNWIND $rows AS row
			MATCH (d:Decision {decisionId: row.decisionId})
			MERGE (e:Exception {exceptionId: row.exceptionId})
			SET e.grantedBy = row.grantedBy, e.justification = row.justification,
			    e.grantedAt = row.grantedAt
			MERGE (e)-[:EXCEPTION_TO]->(d)
			""";

	private final Driver driver;

	/** The database {@link LoanGraph} names, so the seed lands where the demo reads it. */
	private final QueryConfig config;

	GraphSeeder(Driver driver, @Value("${loan.neo4j.database:}") String database) {
		this.driver = driver;
		QueryConfig.Builder builder = QueryConfig.builder();
		if (StringUtils.hasText(database)) {
			builder.withDatabase(database);
		}
		this.config = builder.build();
	}

	@Override
	public void run(String... args) {
		Seed seed = Seed.load();

		CONSTRAINTS.forEach(constraint -> run(constraint, Map.of()));

		merge(MERGE_COMPANY, seed.companies(),
				company -> Map.of("companyId", company.companyId(), "name", company.name(),
						"creditRiskScore", company.creditRiskScore(), "currentDebt",
						company.currentDebt(), "annualIncome", company.annualIncome()));

		merge(MERGE_POLICY, seed.policies(),
				policy -> Map.of("key", policy.key(), "name", policy.name(), "threshold",
						policy.threshold(), "windowMonths", policy.windowMonths(), "description",
						policy.description()));

		// Before the decisions, because each of those MATCHes the person who made it.
		merge(MERGE_UNDERWRITER, seed.underwriters(),
				underwriter -> Map.of("underwriterId", underwriter.underwriterId(), "name",
						underwriter.name(), "title", underwriter.title(), "yearsOnTheJob",
						underwriter.yearsOnTheJob(), "label", underwriter.label(), "disposition",
						underwriter.disposition()));

		merge(MERGE_APPLICATION, seed.applications(),
				application -> Map.of("applicationId", application.applicationId(), "companyId",
						application.companyId(), "requestedAmount", application.requestedAmount(),
						"submittedAt", when(application.monthsAgo())));

		merge(MERGE_DECISION, seed.decisions(), decision -> {
			// HashMap rather than Map.of: a seeded approval leaves the last three of these
			// null, and Map.of rejects nulls.
			Map<String, Object> row = new HashMap<>();
			row.put("decisionId", decision.decisionId());
			row.put("applicationId", decision.applicationId());
			row.put("outcome", decision.outcome());
			row.put("reason", decision.reason());
			row.put("decidedAt", when(decision.monthsAgo()));
			row.put("underwriterId", decision.underwriterId());
			row.put("policyKey", decision.policyKey());
			row.put("observed", decision.observed());
			row.put("threshold", decision.threshold());
			return row;
		});

		merge(MERGE_EXCEPTION, seed.exceptions(),
				exception -> Map.of("exceptionId", exception.exceptionId(), "decisionId",
						exception.decisionId(), "grantedBy", exception.grantedBy(), "justification",
						exception.justification(), "grantedAt", when(exception.monthsAgo())));
	}

	private <T> void merge(String cypher, List<T> records, Function<T, Map<String, Object>> row) {
		run(cypher, Map.of("rows", records.stream().map(row).toList()));
	}

	/**
	 * Seeded history is dated relative to the run, not on a calendar day, because Repeat Denial
	 * Escalation only counts denials inside a rolling window. A literal date in seed.json would
	 * drift out of that window and the shipped denial would stop being precedent.
	 *
	 * A java.time value, so the seeded decision and a live one are the same type in the graph.
	 */
	private static ZonedDateTime when(long monthsAgo) {
		return ZonedDateTime.now().minusMonths(monthsAgo);
	}

	private void run(String cypher, Map<String, Object> parameters) {
		this.driver.executableQuery(cypher)
			.withParameters(parameters)
			.withConfig(this.config)
			.execute();
	}

}
