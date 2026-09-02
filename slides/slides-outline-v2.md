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

## Flow 1: The Problem Space

> James | Slides | 6 minutes | Slides 1-5

### Slide 1: Title

## The Decision Layer
### Shared reasoning for Spring AI agents

---

### Slide 2: Smarter Agents with Smarter Context

![Smarter Agents with Smarter Context](images/smarter-agents-smarter-context.svg)

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
- The Model Is Rented. The Agent Is Built by the Enterprise.
- Capable Models, Smarter Agents
- Context Is What Makes an Agent Smart
- Enterprise Context Is the Agent's Advantage
- Build Smarter Agents on a Commodity Model
- Every Decision Makes the Next Agent Smarter
- Smarter Agents from Shared Context
- Context Turns a Model into a Working Agent

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

---

### Slide 4: What Grounded Context Buys You

| Benefit | What it buys you |
|---|---|
| **More accurate answers** | The agent decides on real evidence instead of a guess |
| **Explainability and governance** | Every decision names the policy and the person behind it |
| **Persistent context** | Context has a place to live beyond one prompt |

**Each of these is a business outcome, and each one needs the same thing: a
record of how the company decides.**

---

### Slide 5: Towards Autonomous Agents

```text
+-----------------------------------------------------------+
|                                                           |
v                                                           |
CAPTURE  --------->  IMPROVE  --------->  AUTONOMOUS  ------+
outcome +            next request         act alone only
authorization        starts from          where the record
approvals,           the record           already settled it
overrides,                                ^
granted exceptions                        |
                                          autonomy gate reads
                                          the record, and is
                                          not built here
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

---

## Flow 2: Decision Layer Architecture

> Ryan | Slides | 10 minutes | Slides 6-9

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

---

### Slide 9: Traversable Context Is More Than a Decision Log

| A log can answer | A context graph can answer |
|---|---|
| What happened? | Which policy authorized it? |
| Who made the decision? | Which exception changed its standing? |
| When was it recorded? | Which later decisions did it govern? |

**The relationship is part of the evidence. In production that path keeps going,
from the decision out to the authoritative source and the business definition.**

---

## Flow 3: Spring AI Advisors

> James | Slides + demo | 5 minutes | Slides 10-12

### Slide 10: Spring AI Advisors Are the Interception Point

![Spring AI Advisors Are the Interception Point](images/advisor-interception-point.svg)

- A `CallAdvisor` wraps the model call
- It sees the request on the way in and the response on the way out
- It can enrich context without changing the agent above it

```text
+------------------------------------------------------+
|  Agent   chatClient.prompt() ... .call()             |
+------------------------------------------------------+
      |  request                       ^  response
      v                                |
+--------------------------+---------------------------+
|  advisor.before          |  advisor.after            |
|  enrich the request      |  record the decision      |
|  with graph context      |  back to the graph        |
+--------------------------+---------------------------+
      |                                ^
      v                                |
+------------------------------------------------------+
|                        Model                         |
+------------------------------------------------------+
```

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

---

## Flow 4: Recording a Decision in the Graph

> Ryan | Slides | 5 minutes | Slides 13-15

### Slide 13: Four Steps: Look Up, Check, Decide, Record

1. **Look up** the company, its policies, its standing denials, and the
   underwriter
2. **Check** the application against each policy threshold
3. **Decide** through the model, returned as a typed `LoanVerdict`
4. **Record** the decision and its authorization path in Neo4j

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

---

## Flow 5: Graph-Enriched Decision Search

> Ryan | Slides + demo | 10 minutes | Slides 16-20

### Slide 16: What Counts Is Connection, Not Wording

| Vector search | Graph traversal |
|---|---|
| Finds decisions that read alike | Finds decisions connected to this case |
| Ranks proximity to text | Follows authorization and lineage |
| Retrieves candidate context | Establishes why context governs |

**A past decision counts because of how it connects to this case, not because it
reads like it.**

---

### Slide 17: When an Old Denial Still Counts

An old denial counts against this application only when all three are true:

1. **Whose it is**: The denial belongs to this company.
2. **How old it is**: It falls inside the policy's time window.
3. **Whether it still stands**: No exception has waived it.

```cypher
MATCH (:Company {companyId: $companyId})-[:SUBMITTED]->(:LoanApplication)
      <-[:ABOUT]-(d:Decision {outcome: 'DENIED'})
WHERE d.decidedAt > datetime() - duration({months: $windowMonths})
  AND NOT EXISTS { (:Exception)-[:EXCEPTION_TO]->(d) }
RETURN d
```

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

---

## Flow 6: Summary

> James | Slides | 4 minutes | Slides 21-22

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

---

### Slide 22: Rented Intelligence Is Everywhere. The Enterprise's Decision Layer Is Not.

| Old school | New school |
|---|---|
| Business context lives in people, code, documents, and tickets | The decision layer makes it shared and queryable |
| Every agent rebuilds the business in its own prompt | Agents resolve context at runtime through the advisor |
| Each decision ends as an isolated answer | Decision traces make experience compound |

**A frontier model without business context is an articulate outsider. Give it a
decision layer, and every decision makes the next one better.**

---

## Q&A

> James + Ryan | Discussion | 5 minutes | Slide 23

### Slide 23: Questions

## What organizational context do your agents still have to guess?

