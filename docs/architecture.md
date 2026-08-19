# Architecture

## Why Traversal, Not Vector Search

This demo embeds nothing, by design. Vector search retrieves decisions that *read* like this one:
similar wording, similar amounts, a similar-sounding company. Applicability, though, is a structural
fact, not a similarity score. A prior denial matters here for three reasons, and every one of them
comes from the decision's position in the graph rather than from how its text reads:

- **Ownership**: it belongs to this company.
- **Recency**: it falls inside the window a policy defines.
- **Standing**: no exception has set it aside.

The precedent this demo reads is selected by position in the record rather than by proximity to a
document, so what reaches the underwriter is everything that governs this case, not merely the
closest match a search could find. The two approaches compose in a larger system, and the order is
what matters: traverse to what applies, then rank what is left. Similarity is a good way to search
prose. It is a poor way to decide whether a rule has already been applied.

## How Another Agent Would Reuse This

This module runs one agent because one is enough to demonstrate the mechanism: the decision layer
sits underneath the conversation, not inside it. What makes it an architecture rather than a single
feature is that the layer is general enough for any agent to reuse, not tailored to the one sitting
above it. A portfolio risk agent answering "are we over-exposed to this sector?" could be added
today:

- **No new plumbing**: it would read the same `Decision` nodes the loan agent wrote, along with their
  policies, exceptions, escalations, and the people who decided them, and its answer would change
  because of reasoning it never saw produced.
- **No direct channel**: no shared prompt, no tool call, no message bus. The graph is the only
  channel between the two agents, and what travels through it is the reasoning behind every decision
  that came before, not merely data the two agents both happen to hold.
- **Reading and writing are separable**: the layer being two beans rather than one is what makes that
  concrete. An agent that wants the context without recording anything registers `PrecedentAdvisor`
  and stops there, connected by a typed record rather than a shared map key.

## What This Demo Leaves Out

- **Nothing withdraws an exception**: granting one is a statement an underwriter can make; taking it
  back is a query you run by hand, which is exactly what the delete shown under
  [the exception mechanics](graph.md#how-an-exception-works) does. A real audit record can tighten as
  well as loosen, so the honest way to revoke an exception is another node, not a deletion: deciding
  that an exception should not have been granted is itself a decision somebody made, and worth
  keeping.
- **A run can cite a denial and except it in the same answer**: both lists are checked against the
  denials the run was shown, but each is checked in isolation, so a verdict can lean on
  `D-1123-SEED-1` as the reason it escalated and set the same denial aside in the same answer. The
  graph then holds an `ESCALATED_FROM` edge to a denial that no longer counts. That is contradictory,
  not corrupt: the check that would catch it belongs beside the two filters this advisor already
  applies.
- **Precedent stops at the company**: every read filters by `companyId`, except for the two read backs
  at the foot of the console output, even though the graph's real strength is joining across
  entities. "Dana approved a similar file for a different builder last quarter" is a query this
  schema can already answer; this demo simply never asks it. Answering it needs no new node types,
  only a read that drops the company filter, but it changes what the demo is about, which is why it
  belongs in a pass of its own.

## Transcript vs. Decision Trace

Every run prints both, and both live in Neo4j:

- **Transcript**: Spring AI's own chat memory, stored by `Neo4jChatMemoryRepository` on a schema the
  library owns. It answers one question: "read back the messages in this conversation, in order."
- **Decision trace**: what was decided and what decided it. It is read before any verdict exists, and
  it is queryable by company, by policy, and by underwriter, not just by conversation. Only this one
  is precedent.

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

The conversation id is a fresh UUID per run, so no run replays a previous run's prose into its own
prompt: what survives across runs is the context graph, not generated English. Each decision is
stamped with the `conversationId` it was explained in, joining the two schemas as a property without
writing into either, because `Session` does not exist yet when the decision is written.

- **What memory stores**: the letter to the applicant.
- **What never reaches it**: the facts block, because the advisor that appends the facts sits inside
  the memory advisor, and the JSON, because the advisor that reads the verdict rebuilds the response
  down to the explanation on its way back out.
- **What a failure leaves behind**: nothing. A run that fails between the question and the answer
  deletes its own conversation, so a failed run never leaves a `Session` holding half an exchange.

## The Code

| File | What it does |
| --- | --- |
| `Application.java` | Reads the two arguments and runs the decision and the read backs in order |
| `DecisionConsole.java` | Everything a run prints: the history, the person, the measurements, the verdict, the trail, the two read backs, and the transcript |
| `LoanGraph.java` | The Cypher: read the company and the roster, count standing denials, walk the trail, write the decision and its edges, grant an exception |
| `GraphSeeder.java` | `MERGE`s seed.json into the graph at startup, idempotently |
| `PolicyEngine.java` | The three measurements, as arithmetic, with no storage and no verdict in sight |
| `PrecedentAdvisor.java` | Reads the graph and hands the model the file: facts, measurements, standing denials, who is on duty |
| `DecisionTraceAdvisor.java` | Declares the shape of the answer, then writes what came back to the graph as precedent |
| `LoanOfficer.java` | The `ChatClient`, its system prompt, the advisor chain, and the chat memory |
| The record types | The vocabulary: the seed, companies, policies, underwriters, past decisions, the trail, the two read backs, and the verdict |

```shell
./mvnw test
```

Seventy-nine tests and no model calls:

- **`PolicyEngineTests`**: reads the shipped companies and thresholds out of `seed.json`, so editing
  the fixture updates the assertions along with it instead of leaving them green but wrong. It
  asserts measurements only, because the engine no longer produces an outcome at all.
- **`PrecedentAdvisorTests`**: the file that gets assembled, and the draw that puts the same
  application in front of the same person.
- **`DecisionTraceAdvisorTests`**: the half that turns an answer into a trace. Which generation the
  verdict is read from, the policy key that resolves to no edge, the citations that get filtered, the
  exception naming a denial nobody was shown and dropped instead of written, the letter that replaces
  the JSON, and the conversation a failed run cleans up.
- **`LoanGraphTests`**: its Cypher against a Neo4j 5.26 container, proving what the example lives on.
  A decision written by one run is counted by the next and joined to it when history is what moved
  it, a citation of a citation comes back at depth two, a denial outside the window stops counting,
  an excepted denial stays on file while no longer counting, an exception an underwriter grants joins
  to both the denial and the person who granted it while one against a decision the graph does not
  hold fails instead of writing a dangling node, and the disposition on the edge is the wording as it
  stood when the decision was made. Docker has to be running for this half.
