# The Decision Layer: A Context Graph for Spring AI

Agents share data. They do not share reasoning. The thinking behind an agent's answer lives in a
prompt and a response and then it is gone, so the next agent inherits the same records and none of
the context the last one built. A decision layer is the missing persistence: something that
intercepts every query, writes down what was decided and what decided it, and resolves the next
query through that record.

This is one, small enough to read in a sitting. A construction bank decides loan applications. The
rules are three lines of arithmetic, the model never gets a vote, and every decision is written to a
Neo4j graph that the next application reads before it is answered. Apply twice with the same numbers
and the second answer is different, because the first answer is now part of the input. One Neo4j
instance and an Anthropic key run all of it.

The bank is the one deciding. The companies are the entities its decision traces are stitched
across, so what accumulates in the graph is the lender's own precedent: which policy applied to
which application, what number it was checked against, when, and what was later excepted.

## Why a Context Graph

This example is a working version of the idea in Foundation Capital's
[Context Graphs: AI's Trillion-Dollar Opportunity](https://foundationcapital.com/ideas/context-graphs-ais-trillion-dollar-opportunity),
which defines a context graph as "a living record of decision traces stitched across entities and
time so precedent becomes searchable." Such a graph accumulates by recording, at the moment a
decision is made, "what inputs were gathered, which policies applied, what exceptions were granted,
and who approved" it, so the graph captures "*why* it was allowed to happen" rather than only what
happened.

The article's contrast is with systems of record, which hold current state and drop the reasoning
that produced it, and with "rules alone": rules describe general behavior, while a decision trace
shows how a rule applied in one specific case. That contrast is the whole demo. The three policies
are the rules, and they never change. What changes between the first run and the second is the
precedent the graph holds, and it changes which rule decides.

Mapped onto the code below: the inputs are the company's numbers, "which policies applied" is the
`APPLIED_POLICY` relationship carrying the observed value and the threshold it was checked against,
"what exceptions were granted" is an `Exception` node joined to the decision it set aside, and
precedent becomes searchable through the traversal that counts prior denials before a verdict
exists. Who approved is the one part of that definition this example leaves out, and
[what it would take to add it](#what-it-leaves-out) is a section of its own.

## What Runs Where

The decision layer is a Spring AI `CallAdvisor`. It is the whole mechanism: it sees every query,
and it decides before the request ever reaches the model.

1. Read the company and its decision history out of the graph.
2. Check the three policies in plain Java. PASS or FAIL per policy, and one deciding policy.
3. Write the decision to the graph, before the model is called.
4. Hand the model the verdict and ask it to explain the verdict in English.

Step 3 comes before step 4 on purpose: a decision is a fact the moment it is computed, so a model
that times out costs the sentence and nothing else and the next run still counts the denial. The
model cannot change an outcome, and the printed checklist sits beside its paragraph so you can see
whether it described the decision it was given.

An advisor is the right seam for this because it is the one place every query already passes
through. Nothing in `LoanOfficer` knows the graph exists, and no prompt carries the history: the
layer under the conversation reads it, decides on it, and writes to it.

## The Graph

```
(Company)-[:SUBMITTED]->(LoanApplication)<-[:ABOUT]-(Decision)-[:APPLIED_POLICY]->(Policy)
                                                     (Decision)-[:ESCALATED_FROM]->(Decision)
                                  (Exception)-[:EXCEPTION_TO]->(Decision)
```

Counting a company's prior denials is a traversal rather than a field on the company:

```cypher
MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
      <-[:ABOUT]-(d:Decision {outcome: 'DENIED'})
WHERE d.decidedAt > datetime() - duration({months: $windowMonths})
RETURN count(d) AS priorDenials
```

`APPLIED_POLICY` carries the observed value and the threshold it was checked against, so the graph
holds the arithmetic and not just the console text. An approved decision has no `APPLIED_POLICY` at
all, because nothing causes an approval the way a failing rule causes a denial, so every read
treats that hop as `OPTIONAL MATCH`.

`ESCALATED_FROM` joins a decision to the earlier decisions that made Repeat Denial Escalation
fire, and is written only on that outcome, so "the past changed this one" is a pattern you can
match rather than a count you have to trust.

`EXCEPTION_TO` is an underwriter's judgement that a denial should not be held against the company
later. It is not a correction and not a deletion: the denial stays on file with its policy and its
numbers, and stops counting as precedent. That distinction is only expressible because the decision
and its standing are different things in the graph.

```cypher
// an exception is not a deletion
WHERE NOT EXISTS { (:Exception)-[:EXCEPTION_TO]->(d) }
```

## Three Hops From One Decision

A listing is what a system of record gives you. What the relationships add is that any one decision
can be walked outward: to the policy that decided it, to the exception that set it aside, and to
every later decision it has since decided. One query, and every run prints it:

```cypher
MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
      <-[:ABOUT]-(d:Decision {outcome: 'DENIED'})
OPTIONAL MATCH (d)-[:APPLIED_POLICY]->(p:Policy)
OPTIONAL MATCH (e:Exception)-[:EXCEPTION_TO]->(d)
OPTIONAL MATCH (later:Decision)-[:ESCALATED_FROM]->(d)
RETURN d.decisionId, p.name AS decided_by, e.grantedBy, e.justification,
       collect(DISTINCT later.decisionId) AS has_decided
ORDER BY d.decidedAt
```

The third hop is `ESCALATED_FROM` read backwards, and it is the one a table cannot answer: not "what
decided this" but "what has this decided since." Widen the same idea one relationship further and it
crosses companies, because a policy is shared and the decisions under it are not:

```cypher
// every application this policy has decided, across every company
MATCH (p:Policy {key: 'repeatDenialEscalation'})<-[applied:APPLIED_POLICY]-(d:Decision)
      -[:ABOUT]->(a:LoanApplication)<-[:SUBMITTED]-(c:Company)
RETURN c.name, a.requestedAmount, applied.observed, applied.threshold, d.decidedAt
ORDER BY d.decidedAt DESC
```

## Why the Graph and Not Similarity

Nothing here is embedded, and that is the point rather than an omission. Vector search retrieves
decisions that *read* like this one: similar wording, similar amounts, a similar-sounding company.
Applicability is not a similarity score. A prior denial matters here because it belongs to this
company, because it falls inside a window a policy defines, and because no exception has set it
aside, and none of those three facts is recoverable from how the text of the decision looks.

The precedent this demo reads is selected by position in the record, not by proximity to a document,
so the answer is not "the closest thing we could find" but "everything that governs this case." The
two compose in a larger system, and the order is what matters: traverse to what applies, then rank
what is left. Similarity is a good way to search prose. It is a poor way to decide whether a rule
has already been applied.

Times are Neo4j `datetime` values, so that window is a temporal comparison the database evaluates,
and precedent ages out on its own instead of piling up forever. `$windowMonths` is read off the
`Policy` node, so the months the console names and the months the Cypher counts over are the same
number and moving the window means editing `seed.json`. The shipped denial is dated relative to the
run rather than to a calendar day, so it cannot age out of the window it exists to demonstrate.

Cypher goes straight to the `Driver` bean Spring Boot auto-configures from `spring.neo4j.*`, and
`GraphSeeder` `MERGE`s `src/main/resources/seed.json` at startup on stable ids, so starting the app
ten times leaves the graph exactly as it was.

## The Policies

| Policy | Rule |
| --- | --- |
| Minimum Credit Score | `creditRiskScore` must be 60 or higher |
| Debt to Income Limit | `(currentDebt + requestedAmount) / annualIncome` must be under 40% |
| Repeat Denial Escalation | Two or more denials in the last 12 months deny the next request |

The requested amount counts against the company, so the number you type does real work. Repeat
Denial Escalation is the one policy that exists only because of memory, and the only one carrying a
`windowMonths` property. Thresholds are properties on `Policy` nodes, queryable next to the
decisions checked against them; the comparisons are Java.

## The Companies

Invented, and picked so that one company fails each rule and one turns on an exception.

| Id | Name | Score | Debt | Income | At $250,000 |
| --- | --- | --- | --- | --- | --- |
| C-1042 | Ridgeline Builders | 72 | 710,000 | 2,000,000 | Fails Debt to Income, one denial on file |
| C-1077 | Cornerstone Concrete | 81 | 300,000 | 4,000,000 | Passes everything |
| C-1096 | Northgate Framing | 47 | 400,000 | 2,200,000 | Fails Minimum Credit Score |
| C-1123 | Summit Ironworks | 78 | 250,000 | 3,000,000 | Approved, and only because of an exception |

`C-1042` owes 35.5% of its income on its own, which passes; the $250,000 being asked for pushes it
to 48%. The `C-1096` row holds for its first two runs only: each run files another denial, so from
the third run on Repeat Denial Escalation is what decides it, and the credit score case comes back
after the reset query below or once those denials fall outside the twelve-month window.

`C-1123` is the interesting one. Its numbers pass comfortably, and it has two denials on file from
older, larger requests. One of them carries an exception, granted by an underwriter five months ago,
so one denial counts and history does not decide. Delete that one relationship and the same command
returns DENIED.

## Setup

Java 17 or later, an Anthropic API key from the
[Anthropic Console](https://console.anthropic.com/settings/keys), and a Neo4j 5 instance you do
not mind the demo writing to: Aura, Neo4j Desktop, or a container.

```shell
# Neo4j, in a terminal of its own
docker run -p 7687:7687 -p 7474:7474 -e NEO4J_AUTH=neo4j/password neo4j:5.26

# then, in this directory
cp .env.example .env
# add ANTHROPIC_API_KEY, NEO4J_URI, NEO4J_USERNAME, and NEO4J_PASSWORD
```

`NEO4J_DATABASE` is optional: set it to run against a database of the demo's own rather than the
connection's home database. The app seeds its own nodes and never empties anything, so an instance
holding other work is safe to point at.

## Run It Twice

```shell
./run.sh C-1042 250000
```

`C-1042` ships with one denial already on file, so the first run is its second denial.

```
Decision traces for C-1042, the precedent this run reads
  2026-05-18  DENIED    $400,000    Debt to Income Limit

Policies
  Minimum Credit Score:     PASS  (score 72, needs 60)
  Debt to Income Limit:     FAIL  (48% with this loan, must be under 40%)
  Repeat Denial Escalation: PASS  (1 prior denial in the last 12 months, escalates at 2)

DENIED. Failed Debt to Income Limit policy.
```

That date moves with you: the seeded denial is written three months before whenever you run it.

The fences in this README stop at the verdict line. Every run also prints the two or three
sentences the model writes, indented under the verdict, and then the precedent trail. On a first
run every row of that trail ends in `nothing yet`, which is the only reason it is left out here.

Run the same command again. Nothing about the company changed:

```
Decision traces for C-1042, the precedent this run reads
  2026-05-18  DENIED    $400,000    Debt to Income Limit
  2026-08-18  DENIED    $250,000    Debt to Income Limit

Policies
  Minimum Credit Score:     PASS  (score 72, needs 60)
  Debt to Income Limit:     FAIL  (48% with this loan, must be under 40%)
  Repeat Denial Escalation: FAIL  (2 prior denials in the last 12 months, escalates at 2)

DENIED. Failed Repeat Denial Escalation policy. This company has been denied 2 times in the last 12 months.

Precedent trail, now that this decision is on file
  D-1042-SEED  denied 2026-05-18
    decided by   Debt to Income Limit
    exception    none
    has decided  D-8c1e04ab
  D-3f9a2c71  denied 2026-08-18
    decided by   Debt to Income Limit
    exception    none
    has decided  D-8c1e04ab
  D-8c1e04ab  denied 2026-08-18
    decided by   Repeat Denial Escalation
    exception    none
    has decided  nothing yet
```

A different policy decided the second run, because the first run is in the graph. The trail is
printed on every run; this is the first one where the third hop has anything to say, because
`D-8c1e04ab` is the first decision that any earlier decision caused. Decision ids are printed as
they are, so a generated id and a seeded one do not line up in a column. Paste this into Neo4j
Browser to see what a run produced:

```cypher
MATCH (c:Company {companyId: 'C-1042'})-[:SUBMITTED]->(a:LoanApplication)<-[:ABOUT]-(d:Decision)
OPTIONAL MATCH (d)-[r:APPLIED_POLICY]->(p:Policy)
OPTIONAL MATCH (d)-[:ESCALATED_FROM]->(cause:Decision)
RETURN d.decidedAt, d.outcome, a.requestedAmount, p.name, r.observed, r.threshold,
       collect(cause.decisionId) AS caused_by, d.explanation
ORDER BY d.decidedAt
```

## The Exception, and What Happens Without It

`C-1123` passes every number, and has two denials on file from older, larger requests. One of them
was excepted, so history does not decide:

```shell
./run.sh C-1123 250000
```

```
Decision traces for C-1123, the precedent this run reads
  2025-11-18  DENIED    $1,400,000  Debt to Income Limit
  2026-02-18  DENIED    $1,000,000  Debt to Income Limit  (excepted, no longer counts)

Policies
  Minimum Credit Score:     PASS  (score 78, needs 60)
  Debt to Income Limit:     PASS  (16.7% with this loan, must be under 40%)
  Repeat Denial Escalation: PASS  (1 prior denial in the last 12 months, escalates at 2)

APPROVED. All policies passed.

Precedent trail, now that this decision is on file
  D-1123-SEED-1  denied 2025-11-18
    decided by   Debt to Income Limit
    exception    none
    has decided  nothing yet
  D-1123-SEED-2  denied 2026-02-18
    decided by   Debt to Income Limit
    exception    M. Alvarez, Senior Underwriter -- The debt was short-term bridge financing against a signed municipal contract, since repaid.
    has decided  nothing yet
```

Two denials are listed and one is counted. Take the exception away and nothing else changes:

```cypher
MATCH (:Exception {exceptionId: 'X-1123-SEED'})-[r:EXCEPTION_TO]->(:Decision) DELETE r
```

Then run it again with seeding off for that one run:

```shell
./run.sh --no-seed C-1123 250000
```

```
  Repeat Denial Escalation: FAIL  (2 prior denials in the last 12 months, escalates at 2)

DENIED. Failed Repeat Denial Escalation policy. This company has been denied 2 times in the last 12 months.
```

`--no-seed` is needed because `GraphSeeder` is idempotent and runs before the advisor reads
anything, so an ordinary run would `MERGE` the relationship back first and answer as though you had
never deleted it.

Running it again without the flag restores the relationship, though not the approval: the denial the
`--no-seed` run wrote is real precedent now, and it counts. The reset query under
[Reset](#reset) returns the graph to its seeded state.

One relationship is the difference between approved and denied, and no rule changed to do it. That
is what a decision trace holds that a rule cannot: the rule says what generally happens, and the
trace says what was allowed to happen in one case, and why.

## Transcript and Decision Trace, in One Database

Every run prints both, and both live in Neo4j. The **transcript** is Spring AI's own chat memory,
stored by `Neo4jChatMemoryRepository` on a schema the library owns, and it answers one question:
"read back the messages in this conversation, in order." A **decision trace** records what was
decided and what decided it, it is read before the next verdict exists, and it is queryable by
company and by policy rather than only by conversation. Only the second is precedent.

```cypher
// Transcript. Grows with every turn. Changes no outcome.
MATCH (s:Session {id: $conversationId})-[:HAS_MESSAGE]->(m:Message)
RETURN m.messageType, m.textContent ORDER BY m.idx

// Decision trace. Read before the verdict. Changes the outcome.
MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
      <-[:ABOUT]-(d:Decision {outcome: 'DENIED'})
WHERE d.decidedAt > datetime() - duration({months: $windowMonths})
RETURN count(d)
```

The conversation id is a fresh UUID per run, so no previous run's prose is replayed into this run's
prompt; what survives across runs is the context graph, not generated English. Each decision is
stamped with the `conversationId` it was explained in, joining the two schemas without writing into
either, as a property because `Session` does not exist yet when the decision is written. The
model's sentence is stored on the decision as `explanation` and is never read back into an outcome.

## Reset

Each run adds a `LoanApplication` and a `Decision`. Delete this example's labels and restart the
app, which reseeds:

```cypher
MATCH (n)
WHERE n:Company OR n:Policy OR n:LoanApplication OR n:Decision OR n:Exception
DETACH DELETE n
```

Chat memory transcripts are separate, under `Session`, `Message`, and `Metadata`.

## One Agent Here, Many in the Architecture

This module runs one agent, because one is enough to show the mechanism: the decision layer is
underneath the conversation, not inside it. What makes it an architecture rather than a feature is
that nothing about the layer is specific to the agent above it.

Add a portfolio risk agent that answers "are we over-exposed to this sector?" and it needs no new
plumbing and no handoff. It reads the same `Decision` nodes the loan agent wrote, with their
policies, their exceptions, and their escalations attached, and its answer changes because of
reasoning it never saw produced. Nothing passes between the two agents: no shared prompt, no tool
call, no message bus. The graph is the only channel, and what travels through it is not data the
agents both happen to hold but the reasoning behind every decision that came before.

That is the difference the layer buys. Shared data gets a second agent to the same records. Shared
decision traces get it to the same conclusions, including the ones that were reached once, written
down, and never explained again.

## What It Leaves Out

The article's definition records four things: what inputs were gathered, which policies applied,
what exceptions were granted, and who approved. This example does the first three. Who approved is
missing, because every outcome here is arithmetic and arithmetic has no one to hold responsible
for it.

The obvious next expansion is an `Underwriter` node and a `(Decision)-[:DECIDED_BY]->(Underwriter)`
relationship, written where `saveDecision` already writes the decision. `Exception.grantedBy` is a
string today for the same reason: the graph names a person without modelling one. Promoting it makes
"which underwriter's denials get excepted, and by whom" a traversal, which is precedent about the
deciders rather than about the companies.

Exceptions are seeded rather than granted, too. A `./run.sh --except <decisionId> "<justification>"`
mode would let one be issued live, which is a second entry point rather than another advisor, and
would not change the shape of anything already here.

## The Code

| File | What it does |
| --- | --- |
| `Application.java` | Reads two arguments, prints the history, the checklist, the trail, and both memories |
| `LoanGraph.java` | The Cypher: traverse the history, walk the trail, write the decision, attach the explanation |
| `GraphSeeder.java` | `MERGE`s seed.json into the graph at startup, idempotently |
| `PolicyEngine.java` | The three rules, as arithmetic, with no storage in sight |
| `PrecedentAdvisor.java` | Reads the graph and hands the model the file: facts, measurements, standing denials, who is on duty |
| `DecisionTraceAdvisor.java` | Declares the shape of the answer, then writes what came back to the graph as precedent |
| `LoanOfficer.java` | The `ChatClient`, its system prompt, the advisor chain, and the chat memory |
| The record types | The vocabulary: the seed, companies, policies, decisions, the trail, and the model's answer |

```shell
./mvnw test
```

Twenty-five tests and no model calls. `PolicyEngineTests` reads the shipped companies and thresholds
out of `seed.json`, so editing the fixture cannot leave the assertions green and wrong, and it needs
no database. `LoanGraphTests` runs its Cypher against a Neo4j container and proves the three claims
the example lives on: a decision written by one run is counted by the next and joined to it when
history is what decided, a denial outside the window stops counting, and an excepted denial stays on
file while no longer counting. Docker has to be running for that half.
