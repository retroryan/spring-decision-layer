# The Graph

```
(Company)-[:SUBMITTED]->(LoanApplication)<-[:ABOUT]-(Decision)-[:APPLIED_POLICY]->(Policy)
                                                    (Decision)-[:WEIGHED_PAST]->(Policy)
                                                    (Decision)-[:ESCALATED_FROM]->(Decision)
                                                    (Decision)-[:DECIDED_BY]->(Underwriter)
                                 (Exception)-[:EXCEPTION_TO]->(Decision)
                                 (Exception)-[:GRANTED_BY]->(Underwriter)
```

Counting a company's standing denials is a traversal rather than a field on the company, and it is
read before anybody has decided anything:

```cypher
MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
      <-[:ABOUT]-(d:Decision {outcome: 'DENIED'})
WHERE d.decidedAt > datetime() - duration({months: $windowMonths})
  AND NOT EXISTS { (:Exception)-[:EXCEPTION_TO]->(d) }
RETURN d.decisionId AS decisionId
ORDER BY d.decidedAt
```

Each relationship type carries one claim:

- **`SUBMITTED`, `ABOUT`**: which company a file belongs to, and which file a decision answers.
- **`APPLIED_POLICY`**: this line stopped the loan. Carries the observed value and the threshold.
- **`WEIGHED_PAST`**: the loan was approved past this line. Same two properties, same target node, and
  the type is the whole difference. It is the fact a table of outcomes has nowhere to put: an
  underwriter went over a number, and the record says which number. Only one of the two is ever
  written, and both are skipped when the verdict named no policy, so every read treats the hop as
  optional.
- **`ESCALATED_FROM`**: the earlier denials this decision leaned on. The ids come from the verdict
  rather than from an `if`, so "the past changed this one" is a pattern you can match instead of a
  count you have to trust. Read at variable length, because a decision that cited a denial can
  itself be cited.
- **`DECIDED_BY`**: who decided. The disposition rides on the relationship rather than the node, for
  the same reason the two numbers ride on the policy edge: retuning how an underwriter reads a file
  must not rewrite why an old decision went the way it did.
- **`EXCEPTION_TO`**: an underwriter's judgement that a denial should not be held against the company
  later. The denial keeps its policy and its numbers and only stops counting as precedent, a
  distinction that is expressible because the decision and its standing are different things here.
- **`GRANTED_BY`**: who granted the exception, which is what makes it something a person did rather
  than a flag on a row.

```cypher
// an exception marks a denial; it never deletes it
WHERE NOT EXISTS { (:Exception)-[:EXCEPTION_TO]->(d) }
```

Two things hold the model together:

- **Time is data**: dates are Neo4j `datetime` values, so a policy's window is a comparison the
  database evaluates and precedent ages out on its own. `$windowMonths` is read off the `Policy`
  node, so the months a run reports and the months the Cypher counts over are the same number, and
  moving the window means editing `seed.json`.
- **Seeding is idempotent**: `GraphSeeder` `MERGE`s `src/main/resources/seed.json` at startup on
  stable ids, so starting the app ten times leaves the graph exactly as it was. Cypher goes straight
  to the `Driver` bean Spring Boot auto-configures from `spring.neo4j.*`.

## Walking Outward From One Decision

A listing is what a system of record gives you. What the relationships add is that any one decision
can be walked outward: to the policy that decided it, to the exception that set it aside, and to
every later decision it has since driven. One query, and every run prints it:

```cypher
MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
      <-[:ABOUT]-(d:Decision {outcome: 'DENIED'})
OPTIONAL MATCH (d)-[:APPLIED_POLICY]->(p:Policy)
OPTIONAL MATCH (e:Exception)-[:EXCEPTION_TO]->(d)
RETURN d.decisionId, d.decidedAt, p.name AS decided_by, e.grantedBy, e.justification,
       COLLECT {
         MATCH path = (later:Decision)-[:ESCALATED_FROM*1..]->(d)
         WITH later, min(length(path)) AS depth
         RETURN {depth: depth, decisionId: later.decisionId} AS step
         ORDER BY depth, later.decisionId
       } AS has_driven
ORDER BY d.decidedAt
```

The third hop is `ESCALATED_FROM` read backwards, and it is the one a table cannot answer. It
answers "what has this driven since," not "what decided this." It is variable length, so the
console prints a lineage indented by hop rather than the first link of a chain, and
`min(length(path))` reports a decision reachable two ways once, at its shortest distance. Recursion
over relationships is the read a relational schema has to write a recursive CTE for.

Widen the same idea one relationship further and it crosses companies, because a policy is shared
while the decisions under it stay tied to one company each:

```cypher
// every application this policy has stopped, across every company
MATCH (p:Policy {key: 'repeatDenialEscalation'})<-[applied:APPLIED_POLICY]-(d:Decision)
      -[:ABOUT]->(a:LoanApplication)<-[:SUBMITTED]-(c:Company)
RETURN c.name, a.requestedAmount, applied.observed, applied.threshold, d.decidedAt
ORDER BY d.decidedAt DESC
```

## Querying Underwriters

Both ends are nodes, so the claim the demo makes out loud is a query rather than a sentence on a
slide. Every run prints it, and it is why `Underwriter` is a node this example reads from as often
as it writes to:

```cypher
MATCH (u:Underwriter)<-[:DECIDED_BY]-(:Decision)-[:WEIGHED_PAST]->(p:Policy)
RETURN u.name, p.name AS approved_past, count(*) AS approvals
ORDER BY approvals DESC
```

Which underwriter approves past which line, and how often. `WEIGHED_PAST` only, because
`APPLIED_POLICY` on the same policy means the opposite: that the line stopped the loan.

The same shape answers who has set aside whose denial, across three nodes rather than two:

```cypher
MATCH (grantor:Underwriter)<-[:GRANTED_BY]-(e:Exception)-[:EXCEPTION_TO]->(d:Decision)
      -[:DECIDED_BY]->(decider:Underwriter)
RETURN grantor.name AS granted_by, decider.name AS decided_by, d.decisionId, e.justification
ORDER BY e.grantedAt
```

One person's judgement about another person's decision, and every end of it is a node, so the
sentence it makes is a walk rather than a property read twice. The same names also sit on the
`Exception` node itself, as a `grantedBy` string property for the console to print directly, since
a property value cannot be joined the way a relationship can.

## How an Exception Works

- **What it is**: an underwriter's judgement that a standing denial should stop counting against the
  company. The decision itself is left untouched.
- **What it changes**: only what the next run reads. Today's denial goes on the pile and an older one
  comes off it, so the count a future run sees holds steady instead of climbing, on a company nobody
  re-measured.
- **Who grants it**: the underwriter reading today's file. A verdict can carry an `exception` naming
  one of the denials it was shown, and the run writes it as its own statement.
- **Why it is a separate write**: deciding today's file and reweighing the record are different
  judgements, so a run can deny today's application while setting a year-old denial aside in the
  same breath. The file was measured before anybody read it, so granting an exception leaves today's
  answer exactly as it was.
- **Seeded or granted**: both land in the same listing, and a `source` property on the `Exception`
  node tells them apart. A verdict that names a denial it was never shown gets that exception
  dropped instead of written.

### C-1123, the file this is built for

- **Measurements**: clears every line.
- **History**: three denials from older, larger requests, one of which a second underwriter has
  already excepted. Two still count.
- **Why it sits on the line**: two standing denials is exactly where Repeat Denial Escalation draws.
  The numbers say yes and the record says wait, so both an approval and a denial are correct
  outcomes from there. An approval records the line it went past, via `WEIGHED_PAST`, which is the
  only place a person and a policy meet in the graph as a traversal rather than a tally kept in Java.

### Removing the exception

Take the exception away and every other fact about the company stays put:

```cypher
MATCH (:Exception {exceptionId: 'X-1123-SEED'})-[r:EXCEPTION_TO]->(:Decision) DELETE r
```

Then run the same application again with seeding off for that one run
(`./run.sh --no-seed C-1123 250000`), and the count moves from two standing denials to three on a
file where every other fact stays fixed: the marker is gone from the listing, and what was
`WEIGHED_PAST` at the escalation line now reads below it. `--no-seed` is needed because
`GraphSeeder` is idempotent and runs before the advisor reads anything, so an ordinary run would
`MERGE` the relationship back first and read the graph as though it had never been deleted. Running
the app again without the flag restores the relationship, but not the count: the decision the
`--no-seed` run wrote is real precedent now. The reset query in
[`docs/reference.md`](reference.md#reset) returns the graph to its seeded state.

One relationship is the difference between a file that sits at the line and a file that is over it,
and no rule changed to do it. That is what a decision trace holds that a rule cannot: the rule says
what generally happens, and the trace says what was allowed to happen in one case, and why.

## Querying the Graph Directly

`CALL db.schema.visualization()` is the wrong first query. It draws one node per label with its
constraints and indexes, not the actual `Decision` nodes you have written, so clicking one in Neo4j
Browser shows `name: "Decision"`, the label itself, rather than anything an underwriter said. These
queries hit real data instead.

Look up one company by its id, along with the applications it has submitted. Returning the path
rather than a list of fields is what makes Browser draw it as a graph, company and applications as
nodes, `SUBMITTED` as the edge between them, instead of a results table:

```cypher
MATCH p = (c:Company {companyId: 'C-1042'})-[:SUBMITTED]->(:LoanApplication)
RETURN p
```

Look up the loan officers. The graph calls them `Underwriter`, not `LoanOfficer`: that name belongs
to the Spring bean that talks to the model, and reusing it for the person who signs a decision would
make one word mean two different things in the same sentence.

```cypher
MATCH (u:Underwriter)
RETURN u.underwriterId, u.name, u.title, u.yearsOnTheJob, u.disposition
ORDER BY u.name
```

See every decision on file, oldest first:

```cypher
MATCH (d:Decision)
RETURN d.decisionId, d.outcome, d.confidence, d.decidedAt, d.reason
ORDER BY d.decidedAt
```

Pull one decision's letter and the trail it cited:

```cypher
MATCH (d:Decision {decisionId: 'D-7df0214e'})
OPTIONAL MATCH (d)-[:ESCALATED_FROM]->(cited:Decision)
RETURN d.explanation, collect(cited.decisionId) AS cited
```

Render an actual neighborhood in Browser's Graph tab, rather than the schema diagram, by
returning paths instead of fields:

```cypher
MATCH p = (c:Company {companyId: 'C-1042'})-[*1..3]-()
RETURN p
```

Every exception on file, seeded or granted, and who granted it:

```cypher
MATCH (e:Exception)-[:EXCEPTION_TO]->(d:Decision)
OPTIONAL MATCH (e)-[:GRANTED_BY]->(u:Underwriter)
RETURN e.exceptionId, e.source, coalesce(u.name, e.grantedBy) AS grantedBy,
       e.justification, d.decisionId
ORDER BY e.grantedAt
```

The company-scoped trace query under "Walking Outward From One Decision" above and the two read-back
queries under "Querying Underwriters" are the same kind of query, just already introduced where the
console output they mirror is explained.
