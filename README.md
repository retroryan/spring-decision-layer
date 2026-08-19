# The Decision Layer: A Context Graph for Spring AI

Agents share data but lose the reasoning behind it. The thinking behind an agent's answer lives in
a prompt and a response, then disappears, so the next agent inherits the same records and has to
reason from scratch. A decision layer persists that reasoning: something that intercepts every
query, writes down what was decided and what decided it, and resolves the next query through that
record.

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

## Quick Start

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
connection's home database. The app only ever seeds its own nodes, so an instance holding other
work is safe to point at.

Then run it with a company id and a requested loan amount:

```shell
./run.sh C-1042 250000
```

Any of these ids works:

| Id | Name |
| --- | --- |
| C-1042 | Ridgeline Builders |
| C-1077 | Cornerstone Concrete |
| C-1096 | Northgate Framing |
| C-1123 | Summit Ironworks |

Run the same command twice: the company's own numbers stay identical between the two runs, but the
graph changes between them, so the second answer reads precedent the first run just wrote. Full
financials for each company are in [`docs/reference.md`](docs/reference.md#the-companies).

Most tests run without a model call or a Neo4j instance:

```shell
./mvnw test
```

To wipe this demo's own nodes and let it reseed from scratch, see
[`docs/reference.md#reset`](docs/reference.md#reset).

## The Context Graph Definition, Mapped to This Graph

This example is a working version of the idea in Foundation Capital's
[Context Graphs: AI's Trillion-Dollar Opportunity](https://foundationcapital.com/ideas/context-graphs-ais-trillion-dollar-opportunity),
which defines a context graph as "a living record of decision traces stitched across entities and
time so precedent becomes searchable." Such a graph accumulates by recording, at the moment a
decision is made, "what inputs were gathered, which policies applied, what exceptions were granted,
and who approved" it, so the graph captures "*why* it was allowed to happen" and not only what
happened.

The article contrasts this with systems of record, which hold current state and drop the reasoning
that produced it, and with "rules alone": rules describe general behavior, while a decision trace
shows how a rule applied in one specific case. That contrast is the whole demo. The three policies
are the rules, and they never change. What changes between the first run and the second is the
precedent the graph holds, and precedent is what the underwriter weighs.

Mapped onto the code below, all four parts of that definition are nodes and relationships:

| The article | The graph |
| --- | --- |
| what inputs were gathered | the company's numbers and every measurement, pushed into the prompt as the file |
| which policies applied | `APPLIED_POLICY` on a denial and `WEIGHED_PAST` on an approval, both carrying the observed value and the threshold |
| what exceptions were granted | an `Exception` node joined by `EXCEPTION_TO` to the denial it set aside and by `GRANTED_BY` to the underwriter who granted it |
| who approved | an `Underwriter` node joined to the decision by `DECIDED_BY`, carrying the disposition as it read at the time |

Precedent becomes searchable through the traversal that counts standing denials before any verdict
exists, and through two read backs every run prints: which underwriter approves past which line, and
who has set aside whose denial. Both are walks between nodes, rather than counts kept in Java.

## The Advisor Chain: Reading Precedent, Recording Decisions

Precedent, in this demo, is not a rule. It is the record of what was actually decided before: the
denials still standing against this specific company, each one carrying the number it was measured
against and whether it was later set aside. A rule says what should happen in general; precedent is
what the bank has already done to this company in particular, and it is what the underwriter reads
before deciding again.

Spring AI models a chat interaction as a chain of `CallAdvisor` beans wrapped around the actual
model call: each advisor sees the request on its way in, can rewrite it, and sees the response on
its way out, before it reaches whatever called the chain. Chat memory is usually one of these
already. This demo adds two more, and the split between them is the architecture: one reads the
graph on the way in, the other records the answer on the way out. The model makes the decision;
both advisors only read or record it.

1. `PrecedentAdvisor` reads the company, the three `Policy` nodes, and the denials still standing
   inside the window the policy itself defines.
2. `PolicyEngine` measures each policy against this application and reports where the number sits
   against the threshold. It leaves the outcome to the model.
3. The run draws an underwriter from the roster in the graph.
4. The file and the person reading it are appended to the user message, and the model answers as
   that person. The answer comes back as a typed `LoanVerdict` record that the code reads directly.
5. `DecisionTraceAdvisor` joins the verdict to the engine's own measurements, filters the citations
   to the denials that were actually sent, and writes the `LoanApplication`, the `Decision`, its
   policy edge, its `DECIDED_BY` edge, and its `ESCALATED_FROM` edges in one statement.
6. If the verdict set one of the denials it was shown aside, a second statement writes that
   `Exception` with its `EXCEPTION_TO` and `GRANTED_BY` edges. Most runs skip this step.
7. The response is rebuilt down to the letter the applicant was sent, so chat memory stores prose
   rather than JSON.

The write happens after the model answers, because the decision exists only once the model has
made it. An earlier version of this example computed the outcome in Java, committed it, and asked
the model to explain a conclusion Java had already reached on its own. That is the flip: the graph
stopped being decoration on a verdict Java had already reached.

Java still owns the guardrails, but the model owns the decision. The measurements are the
engine's, so an edge can only claim a number the engine actually measured. The cited ids are
filtered to the ones that were sent, so a citation can only join the trace to a denial the model
actually saw, and a granted exception is checked the same way: an exception naming an id nobody
was shown would break the graph rather than record a real judgement call, so it is dropped
instead. A policy key that names a measurement which cleared writes no edge at all, so Java always
defers to the policy the underwriter actually named.

An advisor is the right seam because it is the one place every query already passes through.
`LoanOfficer` itself has no idea the graph exists: the layer underneath reads it, hands the model
what it found, and writes back what came of it. The order between the three advisors is the
architecture as well: chat memory outermost, then the graph reading in, then the decision layer
recording what came back, both of the latter outside the tool-calling loop so the loop never
re-enters either of them per tool round trip. An agent that wants only the context registers
`PrecedentAdvisor` and stops there.

## Configuring the Advisors

Nothing about `PrecedentAdvisor` and `DecisionTraceAdvisor` is wired up by hand at the call site.
Both are ordinary Spring beans, and what governs where each one sits in the chain and what it sees
on a given call is a small set of mechanisms, not a line-by-line assembly in `LoanOfficer`:

- **Registration: a bean, not a builder call.** Both advisors are `@Component`-annotated classes.
  Spring constructs them once, with `LoanGraph` and `PolicyEngine` injected in, and `LoanOfficer`
  takes them as constructor parameters and passes them straight to `.defaultAdvisors(...)` alongside
  `MessageChatMemoryAdvisor`.
- **Order: a method, not a position in the list.** Each advisor overrides `getOrder()` rather than
  relying on where it appears in `.defaultAdvisors(...)`. `PrecedentAdvisor` returns
  `ToolCallingAdvisor.DEFAULT_ORDER - 2` and `DecisionTraceAdvisor` returns `DEFAULT_ORDER - 1`, which
  is what actually keeps both outside the tool-calling loop: an advisor placed inside it would read
  the graph, or write to it, once per tool round trip instead of once per call.
- **Per-call parameters: passed in, not held on the bean.** `PrecedentAdvisor.COMPANY_ID` and
  `REQUESTED_AMOUNT` are not fields; `LoanOfficer` supplies them per call with
  `.advisors(a -> a.param(...))`, so the one bean instance serves every application without being
  rebuilt or holding state between them.
- **The model: pinned in configuration, not left to the starter default.** `application.yaml` sets
  `spring.ai.anthropic.chat.model` to `claude-sonnet-5` explicitly, since this is the model reading
  what `PrecedentAdvisor` assembled and deciding the loan that gets written back.
- **The lookback window: a graph property, not a constant.** How far back `PrecedentAdvisor` looks
  for standing denials is read off each `Policy` node's own window property through
  `PolicyEngine.denialWindowMonths(...)`, so widening or narrowing it is an edit to the graph, not a
  redeploy.

## Why a Graph: Precedent Is a Traversal

Nothing the underwriter needs to know is a field on the company. Every read that matters is several
hops out, and each hop is what the graph is here to buy:

- **Standing precedent: derived at read time.** How many denials still count against a company is
  two hops from the company, filtered by the exceptions hanging off each denial. The count is
  computed on every read, so a new decision changes the answer on its own.
- **Lineage: variable length, in one query.** `ESCALATED_FROM` read backwards returns everything a
  denial has driven since, at any depth, because a decision that cited a denial can itself be cited.
  The traversal answers what this decision has driven, which is a question about the decisions that
  came after it.
- **Cross-entity joins: facts that span several nodes.** Which underwriter approves past which line
  joins a person to a policy through decisions that belong to neither of them, and who set aside
  whose denial spans three nodes at once. Each is a sentence the graph assembles from the record.
- **Time: a comparison the database makes.** A policy's window is a `datetime` bound inside the
  same traversal, so precedent ages out on its own as the window slides.

This is the schema those walks run over:

```
(Company)-[:SUBMITTED]->(LoanApplication)<-[:ABOUT]-(Decision)-[:APPLIED_POLICY]->(Policy)
                                                    (Decision)-[:WEIGHED_PAST]->(Policy)
                                                    (Decision)-[:ESCALATED_FROM]->(Decision)
                                                    (Decision)-[:DECIDED_BY]->(Underwriter)
                                 (Exception)-[:EXCEPTION_TO]->(Decision)
                                 (Exception)-[:GRANTED_BY]->(Underwriter)
```

A `Decision` sits between the application it answers and the policy it weighed, either stopping the
loan (`APPLIED_POLICY`) or going past it on the record (`WEIGHED_PAST`). `ESCALATED_FROM` chains a
decision to the earlier denials it actually cited, so precedent is a walk rather than a count kept
in Java. `DECIDED_BY` and `GRANTED_BY` name the underwriter behind a decision or an exception, and
`EXCEPTION_TO` marks a denial that stops counting as precedent while leaving it, and the numbers it
was decided on, on file.

## Further Reading

- [`docs/graph.md`](docs/graph.md): the full relationship semantics, the traversal queries every
  run prints, the exception mechanic in detail, and queries for inspecting the graph by hand.
- [`docs/reference.md`](docs/reference.md): the three policies, the three underwriters, the four
  companies, the shape of the model's answer, and the reset query.
- [`docs/architecture.md`](docs/architecture.md): the case for deterministic graph traversal over
  similarity search, how the layer generalizes to more agents, what the demo deliberately leaves
  out, how the transcript and the decision trace differ though both live in Neo4j, and a
  file-by-file map of the code.

## Running the Full Test Matrix

`./run.sh` answers one application at a time. `test-all-companies.sh` answers many, in an order
chosen so that a denial written by one case is standing precedent for the next:

```shell
./test-all-companies.sh
```

By default it resets the graph to the seeded baseline (asking first), then runs fifteen loan cases
across all four companies, an amount that clears every policy and one that breaks debt to income
for each, plus immediate repeats on `C-1042` and `C-1123` so a denial just written becomes
precedent for the very next run instead of for some later one. Three more cases check the error
paths: an unknown company id, an unparseable amount, and no arguments at all.

`--tier boundary` drops the repeats, twelve cases with no dependence on run order. `--tier quick`
drops the boundary cases too, one documented run per company. `--no-reset` leaves the graph as it
is instead of asking to wipe it, `--skip-errors` drops the three non-loan cases, and `--yes` skips
the reset prompt for an unattended run. Every case's console output and a `summary.tsv` land under
`test-results/<timestamp>/`, gitignored, since each run costs a real Anthropic call.
