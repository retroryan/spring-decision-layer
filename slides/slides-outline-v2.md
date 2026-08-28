# The Decision Layer: Slide Outline v2

## Internal Flow Map

> These are production headers and speaker notes, not audience-facing slides.

| Flow section | Format | Speaker | Time | Slides |
|---|---|---|---:|---:|
| The problem space | Slides | James | 6 min | 1-5 |
| Decision Layer Architecture | Slides | Ryan | 10 min | 6-9 |
| Spring AI Advisors | Slides + demo | James | 5 min | 10-12 |
| Recording a Decision in the Graph | Slides | Ryan | 5 min | 13-15 |
| Graph-Enriched Decision Search | Slides + demo | Ryan | 10 min | 16-20 |
| Summary | Slides | James | 4 min | 21-22 |
| Q&A | Discussion | James + Ryan | 5 min | 23 |

<!--
Narrative job: By the end, Java and Spring developers should understand how a
Spring AI Advisor backed by Neo4j gives agents access to organizational context
and reusable decision memory, because intelligence without business meaning,
policy, and precedent cannot reliably change how a company operates.

Content framing sources:

- /Users/ryanknight/projects/aws/neo4j-aws-graphrag-workshop/slides/overview-business-story/01-business-case-slides.md
- /Users/ryanknight/projects/cloud-integration/knowledge-layer/reference/knowledge-layer-official.md
- MIT Project NANDA, The GenAI Divide: State of AI in Business 2025
-->

## Flow 1: The Problem Space

> James | Slides | 6 minutes | Slides 1-5

<!--
Section notes: Build the case for a decision layer in five steps. Slide 1 names
the talk. Slide 2 sets rented model capability against stalled agent results and
names the cause: nobody captured how the company operates. Slide 3 names the four
kinds of business context that ground an agent. Slide 4 says what that grounding
buys. Slide 5 points at where it leads: agents that act on their own inside
proven bounds. James hands off to Ryan after Slide 5.

Flow 1 took its extra minute from the Summary section, so James still has 15
minutes total across his three sections.
-->

### Slide 1: Title

## The Decision Layer
### Shared reasoning for Spring AI agents

<!--
Owner: James
Section: The problem space

Open with the gap this talk exists to close. Enterprises can rent a frontier
model by the token and have centralized their data. The way the business decides
things has stayed the same. This talk builds the layer that captures decisions,
using Spring AI advisors and a Neo4j context graph.
-->

---

### Slide 2: Smarter Agents with Smarter Context

```text
       COMMODITY                  UNIQUE ENTERPRISE VALUE
 +--------------------+          +----------------------+
 |   Frontier model   |    +     |   Business context   |
 |  identical for     |          |   yours alone, and   |
 |  every competitor  |          |   built by you       |
 +--------------------+          +----------------------+
            \                              /
             v                            v
          +----------------------------------+
          |            Your agent            |
          |  as intelligent as its context   |
          +----------------------------------+
```

Frontier models now match or beat skilled people across a wide range of tasks.
The agents built on them keep stalling anyway.[^nanda]

- **Commodity capability**: Frontier models have become a commodity. Every
  competitor rents the same one, and it reasons, plans, writes code, and calls
  tools better than anything available five years ago.
- **Stalled results**: The agents built on that model stall before they change
  how work happens. Pilots demo well and stop there.
- **The half nobody built**: Enterprises handed the agent their data and kept
  their operating knowledge. How a process runs, which policy governs a step,
  and who can approve an exception was never written down anywhere the agent can
  read.

**Rented intelligence is the same for everyone. The business context an
enterprise builds around it is the advantage.**

[^nanda]: MIT Project NANDA, [*The GenAI Divide: State of AI in Business
2025*](https://www.steelcase.com/content/uploads/2025/10/v0.1_State_of_AI_in_Business_2025_Report.pdf).
95% of the organizations studied reported zero return from enterprise GenAI
investment. Preliminary findings from 52 organizational interviews, 153 surveyed
leaders, and 300+ public initiatives. The report calls its own figures
directional.

**Alternate titles for this slide**

- Smarter Agents with Smarter Context
- Smarter Agents on the Same Model
- The Model Is Rented. The Agent Is Yours.
- Capable Models, Smarter Agents
- Context Is What Makes an Agent Smart
- Enterprise Context Is the Agent's Advantage
- Build Smarter Agents on a Commodity Model
- Every Decision Makes the Next Agent Smarter
- Smarter Agents from Shared Context
- Context Turns a Model into a Working Agent

<!--
Owner: James

Lead with capability, then the stalled result, then the cause. The room already
believes the models are good. Spend the time on why that has changed so little.

Keep the improvement on the agent, never on the model. A decision layer changes
nothing about the weights. It changes what the agent assembles before the call.

Say the reconciling sentence here, because Slide 21 is titled "Lighter Agents Run
on a Shared Decision Layer" and the room will notice: the agent's behavior gets
smarter while its implementation gets lighter, because the business context moves
out of the prompt and into the decision layer.

Keep the study in the footnote. Say the number once if it helps, then move on.
Defending a statistic burns the opening. The claim that carries the talk is the
cause bullet: business process knowledge was never captured in a form an agent
can read.

The fix bullet now lives on Slide 5. "Records what was decided and what
authorized it" is the definition Ryan builds on from Slide 6 forward.

The alternate titles are a picking list for the deck, not stage content. Delete
the block before the slides go out.
-->

---

### Slide 3: Grounding an Agent in How the Business Decides

Grounding an agent in documents tells it what the company published. Grounding it
in decisions tells it how the company actually operates.

Four kinds of business context settle a real case. An agent grounded in all four
is grounded in the thing that makes this enterprise valuable.

Take the case this talk builds: a construction company asks for a $250,000 loan,
and an underwriting agent has to decide.

- **Business meaning**: A definition inside the company decides what "active
  customer" counts as. The agent guesses at it.
- **Authoritative source**: One of five systems holds the number people trust.
  The agent has no way to tell which one.
- **Standing exception**: Someone approved a bend in the rule for this account
  last quarter. That approval lives in a closed thread.
- **Prior judgement**: A person already settled a case like this one and gave a
  reason. The reason went nowhere the agent can read.

**Every company has one person everyone taps on the shoulder. The agent cannot
tap them.**

Capture those four, and the agent is grounded in how this business really works.
That grounding is the advantage an enterprise builds for itself.

**Alternate bullets, capture-framed** (example, pick one set)

- **Business meaning**: The ontology holds what counts as a prior denial in this
  company, so the agent reads the definition instead of inventing one.
- **Authoritative source**: The mapping names the system of record for the
  applicant's debt figure, so the agent queries the number people trust.
- **Standing exception**: An underwriter excepted a denial for this company last
  quarter. The graph holds that exception, so the denial stops counting.
- **Prior judgement**: A decision trace holds how the last thin-margin file was
  settled and which policy authorized it, so the agent starts from precedent.

<!--
Owner: James

Use the human expert as the concrete image. They know what "active customer"
really means, which system to trust, when a policy bends, who can approve the
exception, and how the last case went. Agents make avoidable mistakes because
none of that reaches them.

Grounding is the word that carries this slide. The room already ties it to
retrieval over documents, so draw the line out loud. Document grounding gives the
agent what the company published. Decision grounding gives it what the company
decided. This talk builds the second one, and the distinction pre-sells the
vector-versus-traversal argument on Slide 16.

The deck holds two versions of the four bullets. The first set states the gap and
lands harder on a cold room. The capture-framed set states the mechanism and
matches the slide title. Pick one before the deck ships and delete the other.

Meaning and source get captured in the ontology. Exceptions and prior judgements
get captured in decision traces, which is the slice this repository implements.

The loan case runs through the whole talk. Naming it here means Slides 14 to 18
land on a domain the room already knows.

Call the missing capability business context or organizational context, and hold
that vocabulary for the whole talk. Avoid "context layer" as the architecture
term. In the official Neo4j framing a context layer is a metrics layer with
document retrieval bolted on, and that is the thing it rules out.
-->

---

### Slide 4: What Grounded Context Buys You

| Benefit | What it buys you |
|---|---|
| **More accurate answers** | The agent decides on real evidence instead of a guess |
| **Explainability and governance** | Every decision names the policy and the person behind it |
| **Persistent context** | Context has a place to live beyond one prompt |

**Each of these is a business outcome, and each one needs the same thing: a
record of how the company decides.**

<!--
Owner: James

Three benefits, read aloud in about thirty seconds. Accuracy is what the business
asks for, governance is what lets it reach production, and persistence is what
makes the first two hold over time.

The engineering benefits of a context graph land later, where the talk actually
demonstrates them. Long-running workflows are on Slide 15. Relevant context and
lower token cost are on Slide 19. Shared multi-agent memory is on Slide 21.

Adapted from v1 Slide 7, which listed all seven benefits at once. Seven is too
many to read aloud, and the business three are the ones this section needs.
-->

---

### Slide 5: Towards Autonomous Agents

```text
Capture business context  ->  Improve the context  ->  Autonomous agents
```

- **The fix this talk builds**: A decision layer records what was decided and
  what authorized it, then hands that record to the next request.
- **Capture**: Every decision writes down its outcome and its authorization.
  Human approvals, overrides, and granted exceptions land in the same record.
- **Improve**: Each request starts from that record, so the context behind the
  next decision is stronger than the context behind the last one.
- **Autonomous**: The agent acts on its own for the cases where the record
  already shows how those cases were settled and what authorized the outcome.

**Autonomy is earned by a record, not granted by a prompt.**

<small>Where this goes: this talk builds the capture step. An autonomy gate reads
from it.</small>

<!--
Owner: James
Handoff to Ryan after the closing line.

This slide sets the direction the rest of the talk serves. Trusting an agent to
act alone requires evidence about how similar cases went and what authorized
those outcomes. That evidence is exactly what a decision trace holds.

Scope it honestly, and the slide says so in the footer. This repository does not
implement a confidence gate or a human-in-the-loop routing step. It implements
the capture mechanism that any such gate would read from.

The fix bullet moved here from Slide 2. Slide 2 now ends on the cause, which
keeps it inside its 75 seconds and gives this slide the mechanism it needs.

Ryan picks up with the implementation: what sits in Neo4j, and the advisor loop
that reads and writes it.
-->

---

## Flow 2: Decision Layer Architecture

> Ryan | Slides | 10 minutes | Slides 6-9

<!--
Section notes: This section is implementation only. James has already made the
business-context case in Flow 1, so Ryan opens with the graph primer, then what
sits in Neo4j, then the loop that reads and writes it, then the schema, then why
the relationships have to be traversable. Introduce no new business framing here.
Ryan hands off to James after Slide 9.

Assume no graph database experience in the room. Slide 6 is the only slide that
teaches graph vocabulary, and everything after it reuses those three words.
-->

### Slide 6: Anatomy of a Decision Graph

![Anatomy of a Decision Graph](images/decision-graph-anatomy.svg)

- **Node**: A node is a thing. A company, a loan application, a policy, an
  underwriter, a decision.
- **Relationship**: A relationship is a typed claim about two nodes.
  `APPLIED_POLICY` says this decision used this rule.
- **Property**: A property is a fact stored on a node or on a relationship.
  `observed: 2` and `threshold: 2` sit on the relationship itself.

The picture makes four claims: what was decided, what authorized it, what
modified it, and what it relied on.

**Everything this talk records is a node, a relationship, or a property. No new
machinery.**

<!--
Owner: Ryan
Section: Decision Layer architecture

This is the only slide that teaches graph vocabulary, so assume nobody in the
room has used a graph database. Three words, then reuse them for the rest of the
talk.

Walk the picture once, following the arrows. A company submitted an application.
A decision is about that application. The decision applied a policy, was decided
by an underwriter, and escalated from a prior denial. An exception can change
that prior denial's standing without deleting it.

Land on properties on relationships. That is the part with no relational
equivalent, and it is where the policy measurement lives: `observed: 2` against
`threshold: 2` belongs to the act of applying the policy, not to the decision and
not to the policy. Java code writes exactly one of APPLIED_POLICY or WEIGHED_PAST
per policy, which the picture calls out.

The four claims map to edges. APPLIED_POLICY or WEIGHED_PAST carries the
authority, DECIDED_BY names the person, ESCALATED_FROM cites the precedent, and
EXCEPTION_TO preserves a denial while changing its standing.
-->

---

### Slide 7: Three Kinds of Memory in One Graph

```text
Long-term memory     Company, Policy, Underwriter nodes
Short-term memory    Spring AI chat memory, Neo4jChatMemoryRepository
Reasoning memory     Decision and Exception nodes, and their relationships
```

- **Long-term memory**: The graph holds enterprise knowledge. Companies,
  policies, underwriters, and the thresholds that govern them are all nodes.
- **Short-term memory**: Spring AI stores the conversation itself through
  `Neo4jChatMemoryRepository`, in the same database.
- **Reasoning memory**: `Decision` nodes hold what was decided and what
  authorized it. This is the memory that becomes precedent.

**One Neo4j instance runs all three. Only reasoning memory changes the next
outcome.**

<!--
Owner: Ryan
Section: Decision Layer architecture

Open on what is in the database rather than on a new abstraction. Three kinds of
memory, one instance, and the audience sees all three in Neo4j Browser during the
demos.

The point that matters: long-term and short-term memory can grow forever and
never change an outcome. Reasoning memory is the only one the next decision reads
as precedent, and it is the one a normal stack never captures.

Scope the demo honestly. This repository implements the decision-memory slice in
a small domain. It does not implement a full enterprise ontology, a source map,
or a policy access layer.

This slide is adapted from v1 Slide 6.
-->

---

### Slide 8: The Decision Layer Is the Memory Loop

![The Decision Layer Is the Memory Loop](images/decision-layer-memory-loop.svg)

```text
Loan Agent -----------+
Portfolio Risk Agent -+--> [ Context-Aware Advisor ] --> Model
Service Agent --------+                |
                                        v
                    +---- Neo4j Context Graph ----+
                    | long-term + short-term      |
                    | + reasoning memory          |
                    +-----------------------------+
```

- **Resolve** the context that governs the request
- **Record** what was decided and what authorized it
- **Reuse** the trace as precedent for the next agent

<!--
Owner: Ryan

The Advisor is the executable seam. The Context Graph holds the three kinds of
memory from Slide 7. The resolve-record-reuse loop is what this talk calls the
Decision Layer.

The repository demonstrates one agent so the mechanism stays visible. The
architecture generalizes because another agent can register the read
capability, the write capability, or both. No direct agent handoff, shared
prompt, or message bus is required.
-->

---

### Slide 9: Traversable Context Is More Than a Decision Log

| A log can answer | A context graph can answer |
|---|---|
| What happened? | Which policy authorized it? |
| Who made the decision? | Which exception changed its standing? |
| When was it recorded? | Which later decisions did it govern? |

**The relationship is part of the evidence. In production that path keeps going,
from the decision out to the authoritative source and the business definition.**

<!--
Owner: Ryan
Handoff to James.

A flat record can hold all the fields and still force application code to
reconstruct their meaning. Traversal keeps policy, exception, actor, lineage,
and time connected as the decision record grows. The production extension is a
governed path from decision to meaning, source, policy, and ownership.
-->

---

## Flow 3: Spring AI Advisors

> James | Slides + demo | 5 minutes | Slides 10-12

<!--
Section notes: Introduce CallAdvisor as middleware around ChatClient, explain
why order matters, and use the first demo to show the advisor chain in the
code. James hands off to Ryan after the demo on Slide 12.
-->

### Slide 10: Spring AI Advisors Are the Interception Point

- A `CallAdvisor` wraps the model call
- It sees the request on the way in and the response on the way out
- It can enrich context without changing the agent above it

```text
request -> advisor.before -> model -> advisor.after -> response
```

<!--
Owner: James
Section: Spring AI Advisors

Assume no prior Spring AI exposure. Explain an advisor as middleware around a
ChatClient call. The agent asks its normal question; the advisor handles the
cross-cutting context work.
-->

---

### Slide 11: Advisor Order Defines the Lifecycle

![Advisor Order Defines the Lifecycle](images/advisor-order-lifecycle.svg)

```text
MessageChatMemoryAdvisor
  -> PrecedentAdvisor
    -> DecisionTraceAdvisor
      -> ToolCallingAdvisor
        -> Model
```

- Memory stays outermost
- Graph context is assembled before the decision is recorded
- Both decision advisors run once per turn, outside the tool loop

<!--
Owner: James

The ordering is deliberate. PrecedentAdvisor uses
ToolCallingAdvisor.DEFAULT_ORDER - 2. DecisionTraceAdvisor uses
ToolCallingAdvisor.DEFAULT_ORDER - 1. If either sat inside the tool loop, reads
or writes could repeat for every tool round trip.
-->

---

### Slide 12: The Decision Layer Arrives by Injection

```java
this.chatClient = builder
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),
        precedentAdvisor,
        decisionTraceAdvisor)
    .build();
```

- **Implicit dependency**: The Neo4j starter auto-configures the driver and the
  chat memory repository. This class names neither.
- **Injected advisors**: Spring hands the agent `precedentAdvisor` and
  `decisionTraceAdvisor` as beans. The agent registers them and stops there.
- **One reusable pattern**: Any agent that registers the same two advisors gets
  the same decision layer. There is no Cypher and no graph code in the agent.

**The agent asks one question. The advisor chain manages the decision context.**

<!--
Owner: James
Demo 1, about 2 minutes.
Handoff to Ryan after the demo.

Show LoanOfficer configuration, then one call carrying conversationId,
companyId, and requestedAmount as advisor parameters. Focus on the seam and
ordering, not every line of code.

The seam is the point. Nothing in this class imports Neo4j, opens a session, or
writes a query. The starter supplies the driver, Spring supplies the advisors,
and the builder call is the whole integration. Any other agent adopts the
decision layer with these same three lines.
-->

---

## Flow 4: Recording a Decision in the Graph

> Ryan | Slides | 5 minutes | Slides 13-15

<!--
Section notes: Walk through the Context-Aware Advisor lifecycle, emphasize the
typed boundary between its read and write halves, and show how an answer becomes
context for the next query.
-->

### Slide 13: Four Steps: Look Up, Check, Decide, Record

1. **Look up** the company, its policies, its standing denials, and the
   underwriter
2. **Check** the application against each policy threshold
3. **Decide** through the model, returned as a typed `LoanVerdict`
4. **Record** the decision and its authorization path in Neo4j

<!--
Owner: Ryan
Section: Recording a decision in the graph

A production decision layer interprets business meaning, locates authoritative
data, enforces policy, and updates memory. This focused demo starts at the last
step, with a small domain already modeled in the graph.

Step 2 is plain arithmetic in PolicyEngine. It compares the credit score to its
minimum, the debt-to-income ratio to its limit, and the prior denial count to the
escalation threshold. It computes the numbers and decides nothing.

The implementation splits the lifecycle across two CallAdvisor beans.
PrecedentAdvisor owns the read path. DecisionTraceAdvisor owns structured output
and the write path. Together they are the Context-Aware Advisor pattern.
-->

---

### Slide 14: Java Records Are the Contract with the Model

- **Typed input**: `companyId` and `requestedAmount` arrive as advisor
  parameters, not as text buried in the prompt.
- **Typed handoff**: `LoanFile` is the record the read half fills in and the
  write half reads back.
- **Typed output**: Spring AI turns `LoanVerdict` into a JSON schema, and the
  provider holds the model to it. The prompt never asks for a format.
- **Nothing extra gets written**: Java stores the policy numbers it computed and
  the decision IDs the model actually saw.

**The Java record fixes the shape of the conversation. The model makes the
judgement inside it.**

<!--
Owner: Ryan

This is the answer to "is it just a prompt trick?" The model owns the decision,
while Java owns trace integrity. Unknown policy keys create no policy edge,
invented citations are filtered, and an exception must name a denial in the
resolved file.
-->

---

### Slide 15: Each Decision Becomes Context for the Next

![Memory Lets the System Compound](images/memory-compounds.svg)

```text
Query N
  graph context -> model decision -> structured trace
                                      |
                                      v
Query N+1
  enriched graph context <------------+
```

- The write happens only after the model has decided
- The response is reduced to the applicant-facing explanation
- The structured trace remains available across conversations and agents
- **Long-running workflows**: Work state lives in the graph, so a process can
  span many steps, many sessions, and many agents

**The next request starts with what prior work already proved.**

<!--
Owner: Ryan

Distinguish chat memory from decision memory. Chat memory stores the exchange in
one conversation. The decision trace is queryable by company, policy,
underwriter, exception, and lineage. Only the trace becomes precedent.

This is where the long-running workflow benefit from Slide 4 becomes concrete.
Nothing here depends on a session staying open, because the state lives in the
graph rather than in a conversation.
-->

---

## Flow 5: Graph-Enriched Decision Search

> Ryan | Slides + demo | 10 minutes | Slides 16-20

<!--
Section notes: Contrast semantic resemblance with structural applicability,
walk the authorization path, explain traverse-then-rank, and use the second demo
to show a newly written decision becoming precedent.
-->

### Slide 16: What Counts Is Connection, Not Wording

| Vector search | Graph traversal |
|---|---|
| Finds decisions that read alike | Finds decisions connected to this case |
| Ranks proximity to text | Follows authorization and lineage |
| Retrieves candidate context | Establishes why context governs |

**A past decision counts because of how it connects to this case, not because it
reads like it.**

<!--
Owner: Ryan
Section: Graph-Enriched Decision Search

This is not an argument against vector search. The claim is about sequence:
first traverse to what applies, then use similarity to rank or expand the
eligible context when needed.
-->

---

### Slide 17: When an Old Denial Still Counts

An old denial counts against this application only when all three are true:

1. **Whose it is**: The denial belongs to this company.
2. **How old it is**: It falls inside the policy's time window.
3. **Whether it still stands**: No exception has set it aside.

```cypher
MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
      <-[:ABOUT]-(d:Decision {outcome: 'DENIED'})
WHERE d.decidedAt > datetime() - duration({months: $windowMonths})
  AND NOT EXISTS { (:Exception)-[:EXCEPTION_TO]->(d) }
RETURN d
```

<!--
Owner: Ryan

None of these facts depends on similar wording. The policy window is stored on
the graph, the company relationship establishes ownership, and EXCEPTION_TO
changes standing without deleting history.
-->

---

### Slide 18: One Query Returns the Whole Authorization Path

```text
Current query
  -> Company
  -> Application
  -> Prior Decision
  -> Policy that governed it
  -> Exception that modified it
  -> Later decisions it influenced
```

- Policy explains the authority
- Exception explains the modification
- Lineage explains where the decision became precedent

**Complete grounding comes from position in the decision record.**

<!--
Owner: Ryan

Use one concrete denial as the starting point. Walk APPLIED_POLICY to the rule,
EXCEPTION_TO backward to any exception, and ESCALATED_FROM backward to every
later decision it drove. This is the central graph argument in the abstract.
-->

---

### Slide 19: Narrow by Traversal First, Then Rank

```text
Query
  -> traverse identity, policy, time, standing, and lineage
  -> collect the decisions that can govern this case
  -> optionally rank the eligible set by semantic similarity
  -> ground the model with the decision path
```

- Traversal supplies eligibility and proof
- Vector search can supply relevance within that boundary
- **More relevant context**: The agent retrieves what this case needs and leaves
  the rest out
- **Lower token cost**: The graph filters before anything reaches the prompt, so
  nothing gets dumped in wholesale

<!--
Owner: Ryan

The demo embeds nothing, by design, so the structural advantage is visible.
Frame this slide as the production extension: graph and vector search compose,
but graph constraints come first when the question is whether a decision
applies.

Two of the benefits James promised on Slide 4 pay off here. Relevant context and
lower token cost are one mechanism seen from two sides. Narrowing by traversal
means the prompt carries less and proves more.
-->

---

### Slide 20: Run It Twice and the Context Changes

```shell
./run.sh C-1042 250000
./run.sh C-1042 250000
```

- Same company, amount, policies, and underwriter
- The first run writes a decision trace
- The second run reads that decision as standing precedent
- The graph changed, so the context can change the answer

<!--
Owner: Ryan
Demo 2, about 3 minutes.

Reset to the seeded baseline before the session. Run the same command twice and
show the decision trail in the console or Neo4j Browser. Underwriter assignment
is deterministic for the same company and amount, so precedent is the variable
that changed. Do not promise a particular outcome from the model. The durable
proof is that run two retrieves the trace written by run one and includes it in
the next decision context.
-->

---

## Flow 6: Summary

> James | Slides | 4 minutes | Slides 21-22

<!--
Section notes: Return to the complete multi-agent architecture, show lighter
agents over a shared decision layer, and close the loop opened on Slide 2. Model
capability produces business value only when the agent can read how the company
decides and add to that record.
-->

### Slide 21: Lighter Agents Run on a Shared Decision Layer

![Lighter Agents Run on a Shared Decision Layer](images/lighter-agents-shared-layer.svg)

```text
Specialized Spring AI agents
          |
          v
Context-Aware Advisor layer
  resolve -> record -> reuse
          |
          v
+------------------- DECISION LAYER --------------------+
| Neo4j Context Graph                                   |
| long-term        | short-term      | reasoning        |
| companies, policy| conversation    | decision traces  |
+-------------------------------------------------------+
```

- Agents read shared context instead of rebuilding it in every prompt
- Advisors resolve the context each request needs at runtime
- Every decision adds reusable organizational memory
- **Shared multi-agent memory**: One graph every agent reads from and writes to,
  with no handoff, shared prompt, or message bus

**The agent's behavior gets smarter as its implementation gets lighter. The
business context lives in the decision layer instead of the prompt.**

<!--
Owner: James
Section: Summary

Return to the multi-agent frame from the opening. The decision layer is the
shared substrate. The Context-Aware Advisor is how a Spring AI agent consumes it.
The Neo4j context graph is where the three kinds of memory from Slide 7 persist.

The design principle in practical form: lighter agents over a smarter shared
decision layer. Business context gets recorded once rather than copied into ten
prompts and allowed to drift.

Close the loop with Slide 2, which promised smarter agents. Both are true and the
bold line says why. Behavior gets smarter because the substrate got smarter, and
the agent code gets thinner at the same time.

Shared multi-agent memory is the last of the Slide 4 benefits to land. Say that
the graph is the only channel two agents need, because what travels through it is
the reasoning behind a decision rather than the data the decision was about.
-->

---

### Slide 22: Rented Intelligence Is Everywhere. Your Decision Layer Is Not.

| Old school | New school |
|---|---|
| Business context lives in people, code, documents, and tickets | The decision layer makes it shared and queryable |
| Every agent rebuilds the business in its own prompt | Agents resolve context at runtime through the advisor |
| Each decision ends as an isolated answer | Decision traces make experience compound |

**A frontier model without business context is an articulate outsider. Give it a
decision layer, and every decision makes the next one better.**

<!--
Owner: James

Close the loop with Slide 2. The title is the same claim: rented intelligence is
identical for every competitor, and the decision layer is the half built here.

The durable unit is an auditable decision trace, holding evidence, policy,
exception, actor, outcome, and lineage. Hidden model reasoning is not the durable
unit, because it leaves nothing behind.

The advantage is a faithful, governed record of how this organization decides and
what it has learned.
-->

---

## Q&A

> James + Ryan | Discussion | 5 minutes | Slide 23

### Slide 23: Questions

## What organizational context do your agents still have to guess?

<!--
Owner: James and Ryan
Q&A: 5 minutes

Use a question that extends the talk instead of a generic thank-you slide.
Likely discussion areas: advisor ordering, trace schema, graph and vector search
together, governance, and adding a read-only second agent.
-->
