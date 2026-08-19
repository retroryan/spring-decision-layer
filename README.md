# The Decision Layer: A Context Graph for Spring AI

Agents share data. They do not share reasoning. The thinking behind an agent's answer lives in a
prompt and a response and then it is gone, so the next agent inherits the same records and none of
the context the last one built. A decision layer is the missing persistence: something that
intercepts every query, writes down what was decided and what decided it, and resolves the next
query through that record.

This is one, small enough to read in a sitting. A construction bank decides loan applications.
Three policies are measured in plain Java, a named underwriter on the bank's roster decides the
loan, and the decision is written to a Neo4j graph that the next application reads before anyone
answers it. Apply twice with the same numbers and the second answer can come back different,
because the first answer is now part of the input. One Neo4j instance and an Anthropic key run all
of it.

The bank is the one deciding. The companies are the entities its decision traces are stitched
across, so what accumulates in the graph is the lender's own precedent: which policy applied to
which application, what number it was checked against, when, who decided it, and what was later
excepted.

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
precedent the graph holds, and precedent is what the underwriter weighs.

Mapped onto the code below, all four parts of that definition are nodes and relationships:

| The article | The graph |
| --- | --- |
| what inputs were gathered | the company's numbers and every measurement, pushed into the prompt as the file |
| which policies applied | `APPLIED_POLICY` on a denial and `WEIGHED_PAST` on an approval, both carrying the observed value and the threshold |
| what exceptions were granted | an `Exception` node joined to the decision it set aside by `EXCEPTION_TO` |
| who approved | an `Underwriter` node joined to the decision by `DECIDED_BY`, carrying the disposition as it read at the time |

Precedent becomes searchable through the traversal that counts standing denials before any verdict
exists, and through the read back that names which underwriter approves past which line. Exceptions
are the one row the graph holds and the underwriter does not yet write, which is the first item in
[what it leaves out](#what-it-leaves-out).

## What Runs Where

The decision layer is two Spring AI `CallAdvisor` beans, and the split is the architecture. One
reads the graph on the way in. One records the answer on the way out. Neither decides.

1. `PrecedentAdvisor` reads the company, the three `Policy` nodes, and the denials still standing
   inside the window the policy itself defines.
2. `PolicyEngine` measures each policy against this application and reports where the number sits
   against the threshold. It picks no outcome.
3. The run draws an underwriter from the roster in the graph.
4. The file and the person reading it are appended to the user message, and the model answers as
   that person. The answer comes back as a `LoanVerdict` record, not prose to be scraped.
5. `DecisionTraceAdvisor` joins the verdict to the engine's own measurements, filters the citations
   to the denials that were actually sent, and writes the `LoanApplication`, the `Decision`, its
   policy edge, its `DECIDED_BY` edge, and its `ESCALATED_FROM` edges in one statement.
6. The response is rebuilt down to the letter the applicant was sent, so chat memory stores prose
   rather than JSON.

The write comes after the model answers, because there is no decision until it does. An earlier
version of this example computed the outcome in Java, committed it, and asked the model to explain
a conclusion it had no part in. That is the flip: the graph stopped being decoration on a verdict
Java had already reached.

Java still owns two things, and neither is the decision. The measurements are the engine's, so an
edge cannot claim a number nothing measured. The cited ids are filtered to the ones that were sent,
so a citation cannot join the trace to something that is not there. A policy key that names a
measurement which cleared resolves to nothing and writes no edge, rather than letting Java pick a
policy the underwriter did not name.

An advisor is the right seam because it is the one place every query already passes through.
Nothing in `LoanOfficer` knows the graph exists. The layer underneath reads it, hands the model
what it found, and writes back what came of it. The order between the three advisors is the
architecture as well: chat memory outermost, then the graph reading in, then the decision layer
recording what came back, both of the latter outside the tool-calling loop so neither is re-entered
per tool round trip. An agent that wants the context and not the recording registers the first bean
and stops there.

## The Graph

```
(Company)-[:SUBMITTED]->(LoanApplication)<-[:ABOUT]-(Decision)-[:APPLIED_POLICY]->(Policy)
                                                    (Decision)-[:WEIGHED_PAST]->(Policy)
                                                    (Decision)-[:ESCALATED_FROM]->(Decision)
                                                    (Decision)-[:DECIDED_BY]->(Underwriter)
                                 (Exception)-[:EXCEPTION_TO]->(Decision)
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

`APPLIED_POLICY` and `WEIGHED_PAST` carry the same two properties and point at the same node, and
the type is the whole difference. One says a line stopped the loan. The other says the loan was
approved past it, which is the fact a table of outcomes has nowhere to put: an underwriter went
over a number and the record says which number. Only one of the two is ever written, and neither is
written when the verdict named no policy, so every read treats that hop as optional.

`ESCALATED_FROM` joins a decision to the earlier denials the underwriter actually leaned on. The
ids come from the verdict rather than from an `if`, so "the past changed this one" is a pattern you
can match rather than a count you have to trust, and the chain is read at variable length because a
decision that cited a denial can itself be cited.

`DECIDED_BY` names the person. The disposition rides on the relationship rather than being read
back off the node, for the same reason the two numbers ride on the policy edge: retuning how an
underwriter reads a file must not rewrite why an old decision went the way it did.

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

The third hop is `ESCALATED_FROM` read backwards, and it is the one a table cannot answer: not
"what decided this" but "what has this driven since." It is variable length, so the console prints
a lineage indented by hop rather than the first link of a chain, and `min(length(path))` reports a
decision reachable two ways once, at its shortest distance. Recursion over relationships is the
read a relational schema has to write a recursive CTE for.

Widen the same idea one relationship further and it crosses companies, because a policy is shared
and the decisions under it are not:

```cypher
// every application this policy has stopped, across every company
MATCH (p:Policy {key: 'repeatDenialEscalation'})<-[applied:APPLIED_POLICY]-(d:Decision)
      -[:ABOUT]->(a:LoanApplication)<-[:SUBMITTED]-(c:Company)
RETURN c.name, a.requestedAmount, applied.observed, applied.threshold, d.decidedAt
ORDER BY d.decidedAt DESC
```

## Who Decided, as a Traversal

Both ends are nodes, so the claim the demo makes out loud is a query rather than a sentence on a
slide. Every run prints it, and it is why `Underwriter` is not a node this example only ever writes
to:

```cypher
MATCH (u:Underwriter)<-[:DECIDED_BY]-(:Decision)-[:WEIGHED_PAST]->(p:Policy)
RETURN u.name, p.name AS approved_past, count(*) AS approvals
ORDER BY approvals DESC
```

Which underwriter approves past which line, and how often. `WEIGHED_PAST` only, because
`APPLIED_POLICY` on the same policy means the opposite: that the line stopped the loan. The same
shape would answer whose denials get excepted and by whom, once an exception is something an
underwriter grants rather than something the seed carries. See
[what it leaves out](#what-it-leaves-out).

## Why the Graph and Not Similarity

Nothing here is embedded, and that is the point rather than an omission. Vector search retrieves
decisions that *read* like this one: similar wording, similar amounts, a similar-sounding company.
Applicability is not a similarity score. A prior denial matters here because it belongs to this
company, because it falls inside a window a policy defines, and because no exception has set it
aside, and none of those three facts is recoverable from how the text of the decision looks.

The precedent this demo reads is selected by position in the record, not by proximity to a document,
so what reaches the underwriter is not "the closest thing we could find" but "everything that
governs this case." The two compose in a larger system, and the order is what matters: traverse to
what applies, then rank what is left. Similarity is a good way to search prose. It is a poor way to
decide whether a rule has already been applied.

Times are Neo4j `datetime` values, so that window is a temporal comparison the database evaluates,
and precedent ages out on its own instead of piling up forever. `$windowMonths` is read off the
`Policy` node, so the months the console names and the months the Cypher counts over are the same
number and moving the window means editing `seed.json`. The shipped denials are dated relative to
the run rather than to a calendar day, so they cannot age out of the window they exist to
demonstrate.

Cypher goes straight to the `Driver` bean Spring Boot auto-configures from `spring.neo4j.*`, and
`GraphSeeder` `MERGE`s `src/main/resources/seed.json` at startup on stable ids, so starting the app
ten times leaves the graph exactly as it was.

## The Policies

| Policy | Measured as |
| --- | --- |
| Minimum Credit Score | `creditRiskScore` against 60 |
| Debt to Income Limit | `(currentDebt + requestedAmount) / annualIncome` against 40% |
| Repeat Denial Escalation | standing denials in the last 12 months against 2 |

These are guidance the underwriter weighs, not gates that answer for them. Each measurement comes
back as above the line or below the line, and what that ought to persuade is somebody's judgement.
A number below the line is a reason to deny rather than an instruction to, and a file that clears
every line can still be denied on the pattern in its history. Thresholds are properties on `Policy`
nodes, queryable next to the decisions checked against them. The comparisons are Java.

The requested amount counts against the company, so the number you type does real work. Repeat
Denial Escalation is the one policy that exists only because of memory, and the only one carrying a
`windowMonths` property.

## The Underwriters

Three people on the roster, seeded as nodes and read back out of the graph, so the person named on
the console is the same node the decision is joined to.

| Id | Name | Title | Years | Reads a file as |
| --- | --- | --- | --- | --- |
| U-FELD | Marcus Feld | Senior Underwriter | 17 | cautious, worked through 2008 |
| U-WHITFIELD | Dana Whitfield | Commercial Underwriter | 6 | growth-minded, backs a clean record |
| U-RAMAN | Priya Raman | Senior Underwriter | 11 | splits the difference, weighs the record |

The disposition is a paragraph in `seed.json` written as a person rather than as a temperature
setting, and it is what goes in the prompt. It replaces a sampling knob, because there is not one
to turn: `temperature`, `top_p`, and `top_k` are removed on `claude-sonnet-5` and sending one
returns a 400.

Who is on duty is drawn from the company id and the requested amount rather than at random, so
running the same application again puts it in front of the same person. That is deliberate, and it
is what makes the second run mean something: the only thing that changed between the two runs is
the precedent that arrived in between, and a different underwriter on the second pass would leave a
reader unable to say which of the two moved the outcome. Different applications still reach
different people, which is where the spread between companies comes from.

The persona is appended to the user message beside the facts, not set on the system prompt. The
system prompt holds what does not vary, which is the role and how the fields are to be filled in.
A system prompt that changes per run is a prompt cache that misses per run.

## What Comes Back

The answer is a Java record, and Anthropic's `output_config.format` is what makes it valid by
construction. Spring AI generates the schema from `LoanVerdict`, and because
`AnthropicChatOptions` implements `StructuredOutputChatOptions`, the schema goes on the wire as
`output_config.format` rather than being asked for in the prompt.

| Field | What it carries |
| --- | --- |
| `outcome` | `APPROVED` or `DENIED`, as an enum, so a schema cannot return a third case |
| `reason` | one line naming what drove it, stored on the `Decision` node |
| `decidingPolicyKey` | the policy that weighed heaviest, or nothing when no number drove the call |
| `citedDecisionIds` | the denials the underwriter leaned on, which become the `ESCALATED_FROM` edges |
| `explanation` | the letter to the applicant, signed by the person who decided it |
| `confidence` | `CLEAR` or `BORDERLINE`, so the console can show that a close call was close |

`decidingPolicyKey` is nullable on purpose. A denial reached on the pattern in a file rather than on
a number has no line to point at, and the console says so instead of naming a policy the
underwriter did not choose.

Thinking is turned off in `DecisionTraceAdvisor` rather than in `application.yaml`. Sonnet 5 thinks
adaptively unless told otherwise, thinking interleaves with the answer rather than finishing before
it, and `AnthropicChatModel` accumulates every text block into one string, so an abandoned draft and
the real answer can arrive concatenated. The merged document still parses, because the junk lands
inside a string value, which is how one run wrote a 996-character `reason` to the graph with
fragments of a discarded draft inside it. A field that quietly absorbs an abandoned draft is worse
than a run that fails outright, because it is stored, cited as precedent, and read back as though
somebody meant it.

## The Companies

Invented, and picked so that each one puts a different kind of pressure on the underwriter.

| Id | Name | Score | Debt | Income | At $250,000 |
| --- | --- | --- | --- | --- | --- |
| C-1042 | Ridgeline Builders | 72 | 710,000 | 2,000,000 | 48% debt to income, below the line, one standing denial |
| C-1077 | Cornerstone Concrete | 81 | 300,000 | 4,000,000 | every measurement above the line, nothing on file |
| C-1096 | Northgate Framing | 47 | 400,000 | 2,200,000 | credit score below the line |
| C-1123 | Summit Ironworks | 78 | 250,000 | 3,000,000 | every number clear, sitting on the escalation line |

`C-1042` owes 35.5% of its income on its own, which is above the line; the $250,000 being asked for
puts it at 48%. `C-1096` is denied on every run in practice, so from the third run on the credit
score is no longer the only thing below the line. It comes back on its own once those denials fall
outside the twelve-month window, or after the reset query below.

`C-1123` is the interesting one. Its numbers clear comfortably and it has three denials on file
from older, larger requests, one of which was excepted five months ago. Two still count, which is
exactly where Repeat Denial Escalation says to stop, so the only thing below the line is the
history. Approving means going past a line and saying so on the record. Denying a file whose every
number clears is defensible too, and which one happens is the underwriter's call rather than
something this README can promise you.

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

`C-1042` ships with one denial already on file, so the first run is looking at a company that has
been turned down once.

```
Decision traces for C-1042, the precedent this run reads
  2026-05-19  DENIED    $400,000    Debt to Income Limit

On duty for this run
  Marcus Feld, Senior Underwriter, 17 years on the job (cautious, worked through 2008)

Policies, as measured
  Minimum Credit Score:     above the line  (score 72, needs 60)
  Debt to Income Limit:     below the line  (48% with this loan, must be under 40%)
  Repeat Denial Escalation: above the line  (1 prior denial in the last 12 months, escalates at 2)

DENIED (clear). Debt to income reaches 48% with this loan against a 40% limit.
  line crossed   Debt to Income Limit (48% with this loan, must be under 40%)
```

The outcome, the reason line, the confidence, and the letter are the underwriter's, so they move
between runs. Everything else in that fence is fixed by the code. Dates move with you too: the
seeded denial is written three months before whenever you run it.

Every run also prints the letter, indented under the verdict, then the precedent trail, then who
approves past which line, then the transcript. Those are left out above because the first run has
little to say in them. Run the same command again. Nothing about the company changed, and the
same person reads it:

```
Decision traces for C-1042, the precedent this run reads
  2026-05-19  DENIED    $400,000    Debt to Income Limit
  2026-08-19  DENIED    $250,000    Debt to Income Limit

On duty for this run
  Marcus Feld, Senior Underwriter, 17 years on the job (cautious, worked through 2008)

Policies, as measured
  Minimum Credit Score:     above the line  (score 72, needs 60)
  Debt to Income Limit:     below the line  (48% with this loan, must be under 40%)
  Repeat Denial Escalation: below the line  (2 prior denials in the last 12 months, escalates at 2)

DENIED (clear). Two standing denials inside the window, and the debt ratio has not moved.
  line crossed   Repeat Denial Escalation (2 prior denials in the last 12 months, escalates at 2)

Precedent trail, now that this decision is on file
  D-1042-SEED  denied 2026-05-19
    decided by   Debt to Income Limit
    exception    none
    has driven
      D-3f9a2c71
        D-8c1e04ab
  D-3f9a2c71  denied 2026-08-19
    decided by   Debt to Income Limit
    exception    none
    has driven
      D-8c1e04ab
  D-8c1e04ab  denied 2026-08-19
    decided by   Repeat Denial Escalation
    exception    none
    has driven   nothing yet

Which underwriter approves past which line
  Nobody has been approved past a line yet.

Transcript for this run, from Spring AI chat memory
  USER       Can C-1042 get a construction loan of $250,000?
  ASSISTANT  Ridgeline Builders, your request for $250,000 is denied. With this loan your...
```

A different line decided the second run, because the first run is in the graph. The trail is
printed on every run; this is the first one where the third hop has anything to say, because
`D-8c1e04ab` is the first decision any earlier decision drove. The indentation is the depth:
`D-3f9a2c71` cited the seeded denial directly, and `D-8c1e04ab` cited `D-3f9a2c71`, so it comes
back two hops from the seed. Which denials appear under `has driven` at all is down to which ones
the underwriter cited, because those citations are the edges. Decision ids are printed as they are,
so a generated id and a seeded one do not line up in a column.

The transcript holds the letter and not the JSON, and not the facts block either. The advisor
rebuilds the response down to the explanation before chat memory sees it. Paste this into Neo4j
Browser to see what a run produced:

```cypher
MATCH (c:Company {companyId: 'C-1042'})-[:SUBMITTED]->(a:LoanApplication)<-[:ABOUT]-(d:Decision)
OPTIONAL MATCH (d)-[applied:APPLIED_POLICY]->(stopped:Policy)
OPTIONAL MATCH (d)-[weighed:WEIGHED_PAST]->(crossed:Policy)
OPTIONAL MATCH (d)-[:DECIDED_BY]->(u:Underwriter)
OPTIONAL MATCH (d)-[:ESCALATED_FROM]->(cause:Decision)
RETURN d.decidedAt, d.outcome, d.confidence, a.requestedAmount, u.name,
       stopped.name AS stopped_by, crossed.name AS approved_past,
       coalesce(applied.observed, weighed.observed) AS observed,
       collect(cause.decisionId) AS cited, d.reason
ORDER BY d.decidedAt
```

## The Exception, and What Happens Without It

`C-1123` clears every number and has three denials on file from older, larger requests. One of them
was excepted, so two count, which is exactly the line Repeat Denial Escalation draws:

```shell
./run.sh C-1123 250000
```

```
Decision traces for C-1123, the precedent this run reads
  2025-11-19  DENIED    $1,400,000  Debt to Income Limit
  2026-02-19  DENIED    $1,000,000  Debt to Income Limit  (excepted, no longer counts)
  2026-05-19  DENIED    $1,200,000  Debt to Income Limit

On duty for this run
  Marcus Feld, Senior Underwriter, 17 years on the job (cautious, worked through 2008)

Policies, as measured
  Minimum Credit Score:     above the line  (score 78, needs 60)
  Debt to Income Limit:     above the line  (16.7% with this loan, must be under 40%)
  Repeat Denial Escalation: below the line  (2 prior denials in the last 12 months, escalates at 2)
```

Three denials are listed and two are counted. What happens next is a judgement call on a file where
the numbers say yes and the record says wait, so both answers are correct outcomes. An approval
records the line it went past, and the run that reaches one is the run that fills in the read back:

```
APPROVED (borderline). Every measurement clears with room, and the older denials were larger requests since resolved.
  line crossed   Repeat Denial Escalation (2 prior denials in the last 12 months, escalates at 2)

Which underwriter approves past which line
  Marcus Feld      Repeat Denial Escalation  1
```

That last section is the only place in the output where a person and a policy meet, and it is a
traversal of two relationships rather than a tally kept in Java. On a graph nobody has approved
past a line in, it says so rather than printing an empty heading.

Take the exception away and nothing else about the company changes:

```cypher
MATCH (:Exception {exceptionId: 'X-1123-SEED'})-[r:EXCEPTION_TO]->(:Decision) DELETE r
```

Then run it again with seeding off for that one run:

```shell
./run.sh --no-seed C-1123 250000
```

```
Decision traces for C-1123, the precedent this run reads
  2025-11-19  DENIED    $1,400,000  Debt to Income Limit
  2026-02-19  DENIED    $1,000,000  Debt to Income Limit
  2026-05-19  DENIED    $1,200,000  Debt to Income Limit
  2026-08-19  APPROVED  $250,000    approved past Repeat Denial Escalation

Policies, as measured
  Repeat Denial Escalation: below the line  (3 prior denials in the last 12 months, escalates at 2)
```

The marker is gone from the listing and the count went from two to three, on a file where nothing
else moved. The run before it is on file too, and the listing says which line it was granted past
rather than which line stopped it, because those are two different claims and two different
relationships. Had that run denied instead, the count here would read four. `--no-seed` is needed because `GraphSeeder` is idempotent and runs before the advisor
reads anything, so an ordinary run would `MERGE` the relationship back first and read the graph as
though you had never deleted it.

Running it again without the flag restores the relationship, though not the count: the decision the
`--no-seed` run wrote is real precedent now. The reset query under [Reset](#reset) returns the graph
to its seeded state.

One relationship is the difference between a file that sits at the line and a file that is over it,
and no rule changed to do it. That is what a decision trace holds that a rule cannot: the rule says
what generally happens, and the trace says what was allowed to happen in one case, and why.

## The Same Numbers, Two Answers

Run a file that misses one line by a little and the answer is genuinely not settled by arithmetic.
`C-1077` has nothing on file and asks for more than it can carry:

```shell
./run.sh C-1077 1350000
```

```
Decision traces for C-1077, the precedent this run reads
  Nothing on file yet.

On duty for this run
  Priya Raman, Senior Underwriter, 11 years on the job (splits the difference, weighs the record)

Policies, as measured
  Minimum Credit Score:     above the line  (score 81, needs 60)
  Debt to Income Limit:     below the line  (41.3% with this loan, must be under 40%)
  Repeat Denial Escalation: above the line  (0 prior denials in the last 12 months, escalates at 2)
```

41.3% against a 40% limit, with a clean record behind it. The same command has come back both
`APPROVED (borderline)` and `DENIED (borderline)` on different runs, with the same person reading
the same numbers, which is what an underwriter is and what a decision table is not. `BORDERLINE` is
the model saying so out loud, so a viewer who gets two answers sees judgement rather than suspecting
a bug.

A margin holds still where a count does not. That 41.3% is the same on the tenth run, while an
escalation count moves under you: each run's own denial is precedent for the next one, so a file
that sits at the line does not sit there twice. The ratchet working is what makes it a poor fixture
to run repeatedly.

## Transcript and Decision Trace, in One Database

Every run prints both, and both live in Neo4j. The **transcript** is Spring AI's own chat memory,
stored by `Neo4jChatMemoryRepository` on a schema the library owns, and it answers one question:
"read back the messages in this conversation, in order." A **decision trace** records what was
decided and what decided it, it is read before any verdict exists, and it is queryable by company,
by policy, and by underwriter rather than only by conversation. Only the second is precedent.

```cypher
// Transcript. Grows with every turn. Changes no outcome.
MATCH (s:Session {id: $conversationId})-[:HAS_MESSAGE]->(m:Message)
RETURN m.messageType, m.textContent ORDER BY m.idx

// Decision trace. Read before anybody decides. Changes the outcome.
MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
      <-[:ABOUT]-(d:Decision {outcome: 'DENIED'})
WHERE d.decidedAt > datetime() - duration({months: $windowMonths})
  AND NOT EXISTS { (:Exception)-[:EXCEPTION_TO]->(d) }
RETURN count(d)
```

The conversation id is a fresh UUID per run, so no previous run's prose is replayed into this run's
prompt; what survives across runs is the context graph, not generated English. Each decision is
stamped with the `conversationId` it was explained in, joining the two schemas without writing into
either, as a property because `Session` does not exist yet when the decision is written.

What chat memory stores is the letter. The facts block never reaches it, because the advisor that
appends the facts sits inside the memory advisor, and the JSON never reaches it either, because the
advisor that reads the verdict rebuilds the response down to the explanation on its way back out. A
run that fails between the question and the answer deletes its own conversation, so a failed run
does not leave a `Session` holding half an exchange.

## Reset

Each run adds a `LoanApplication` and a `Decision`. Delete this example's labels and restart the
app, which reseeds all six:

```cypher
MATCH (n)
WHERE n:Company OR n:Policy OR n:Underwriter OR n:LoanApplication OR n:Decision OR n:Exception
DETACH DELETE n
```

Let the seeder run before deciding anything again. On a graph with no `Underwriter` nodes there is
nobody to put the file in front of, so a `--no-seed` run after a reset reports that rather than
deciding. Chat memory transcripts are separate, under `Session`, `Message`, and `Metadata`.

## One Agent Here, Many in the Architecture

This module runs one agent, because one is enough to show the mechanism: the decision layer is
underneath the conversation, not inside it. What makes it an architecture rather than a feature is
that nothing about the layer is specific to the agent above it.

Add a portfolio risk agent that answers "are we over-exposed to this sector?" and it needs no new
plumbing and no handoff. It reads the same `Decision` nodes the loan agent wrote, with their
policies, their exceptions, their escalations, and the people who decided them attached, and its
answer changes because of reasoning it never saw produced. Nothing passes between the two agents:
no shared prompt, no tool call, no message bus. The graph is the only channel, and what travels
through it is not data the agents both happen to hold but the reasoning behind every decision that
came before.

The layer being two beans rather than one is what makes that concrete. An agent that wants the
context without recording anything registers `PrecedentAdvisor` and stops there. Reading precedent
and writing it are two capabilities, and the file that travels between them is a typed record
rather than a shared map key.

## What It Leaves Out

Exceptions are seeded rather than granted. The read side of the ratchet is complete: an excepted
denial stays on file, stops counting, and every query already skips it, so one exception changes
what every later run reads with no code change at all. What is missing is the underwriter being
able to grant one. That means an `exception` field on the verdict carrying the denial being set
aside and the justification for it, one write creating the `Exception` node with its `EXCEPTION_TO`
edge and a `GRANTED_BY` edge to whoever drew the run, and one check that the denial named was one
of the denials that were actually sent. `Exception.grantedBy` is a string today, which is what the
console prints and what the seeded exception carries. The edge is what would make the same fact a
traversal.

Precedent stops at the company. Every read filters by `companyId`, and the graph's real edge is
joining across entities, so "Dana approved a similar file for a different builder last quarter" is
the query this schema can answer and this demo does not ask. It needs no new node types, only a
read that drops the company filter, and it changes what the demo is about, which is why it belongs
in a pass of its own.

## The Code

| File | What it does |
| --- | --- |
| `Application.java` | Reads two arguments, prints the history, the person, the measurements, the verdict, the trail, the read back, and the transcript |
| `LoanGraph.java` | The Cypher: read the company and the roster, count standing denials, walk the trail, write the decision and its edges |
| `GraphSeeder.java` | `MERGE`s seed.json into the graph at startup, idempotently |
| `PolicyEngine.java` | The three measurements, as arithmetic, with no storage and no verdict in sight |
| `PrecedentAdvisor.java` | Reads the graph and hands the model the file: facts, measurements, standing denials, who is on duty |
| `DecisionTraceAdvisor.java` | Declares the shape of the answer, then writes what came back to the graph as precedent |
| `LoanOfficer.java` | The `ChatClient`, its system prompt, the advisor chain, and the chat memory |
| The record types | The vocabulary: the seed, companies, policies, underwriters, past decisions, the trail, and the verdict |

```shell
./mvnw test
```

Seventy-one tests and no model calls. `PolicyEngineTests` reads the shipped companies and thresholds
out of `seed.json`, so editing the fixture cannot leave the assertions green and wrong, and it
asserts no outcomes, because the engine no longer produces one. `PrecedentAdvisorTests` covers the
file that gets assembled and the draw that puts the same application in front of the same person.
`DecisionTraceAdvisorTests` covers the half that turns an answer into a trace: which generation the
verdict is read from, the key that resolves to no edge, the citations that get filtered, the letter
that replaces the JSON, and the conversation a failed run cleans up. `LoanGraphTests` runs its
Cypher against a Neo4j 5.26 container and proves what the example lives on: a decision written by
one run is counted by the next and joined to it when history is what moved it, a citation of a
citation comes back at depth two, a denial outside the window stops counting, an excepted denial
stays on file while no longer counting, and the disposition on the edge is the wording as it stood
when the decision was made. Docker has to be running for that half.
