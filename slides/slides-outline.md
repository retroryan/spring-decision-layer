
### Slide 1: Title

## The Decision Layer
### A Context Graph for Spring AI Agents

<!--
Open with the problem, not the tool. Agents share data. They do not share
the reasoning behind an answer. This talk is about the missing piece.
-->

---

### Slide 2: Agents Share Data, Not Reasoning

- Every agent call ends the same way: **an answer, and nothing else**
- The next agent gets the same records, none of the thinking
- **Reasoning has no persistent form** today

```
Agent A: reads the file, decides, answers
Agent B: reads the same file, decides again, from zero
```

<!--
Two agents looking at the same company get no benefit from each other.
Agent B repeats work Agent A already did, because nothing Agent A thought
survives the call. That repeated work is the cost this talk is about.
-->

---

### Slide 3: The Missing Piece Is a Decision Layer

- **Decision layer**: something that sits under every query
- Writes down **what was decided** and **what decided it**
- The next query **reads that record before it is answered**

<!--
Not a bigger prompt, and not a longer conversation history. A layer that
intercepts the query, decides or informs the decision, and commits a
structured fact before the model ever gets a vote.
-->

---

### Slide 4: Why a Context Graph

- A **living record of decision traces**, stitched across entities and time
- **Precedent becomes searchable**, instead of living in someone's head
- Records four things at the moment a decision is made: **what inputs were
  gathered, which policies applied, what exceptions were granted, and who
  approved**
- Captures **why it was allowed to happen**, not only what happened

<!--
This is Foundation Capital's definition of a context graph, and it is the
idea this whole talk is a working version of. The emphasis is on "why it
was allowed to happen." A system of record can tell you the current state.
Only a decision trace tells you why that state is what it is.
-->

---

### Slide 5: Why a Context Graph, Not a System of Record

| System of record | Rules alone | Decision trace |
|---|---|---|
| Holds current state | Describes general behavior | Shows how a rule applied **once** |
| Drops the reasoning | Never changes case by case | Stitched across entities and time |

- A rule says what **generally** happens
- A decision trace says what was allowed to happen **in this one case**

<!--
This is the whole demo in one contrast. The three loan policies are the
rules, and they never change between runs. What changes is the precedent
the graph holds, and that is what decides which rule wins the next time.
-->

---

### Slide 6: Three Types of Memory in a Context Graph

- **Long-term memory**: enterprise knowledge, policies, customers, accounts,
  business rules
- **Short-term memory**: conversation history, requests, actions already
  taken
- **Reasoning memory**: decision traces, the path from evidence to action,
  the tool calls, the outcome

<!--
This demo runs all three kinds of memory in one Neo4j instance, at once.
Long-term memory is the Company and Policy nodes. Short-term memory is
Spring AI's own chat memory, stored by Neo4jChatMemoryRepository. Reasoning
memory is the Decision nodes: what was decided, and what decided it. Only
the third one is precedent. The other two can grow forever and never
change an outcome.
-->

---

### Slide 7: Seven Benefits of a Context Graph

| Benefit | What it buys you |
|---|---|
| **More accurate answers** | Stronger evidence before the model responds |
| **More relevant context** | Retrieve what the task needs, leave the rest out |
| **Persistent context** | Context has a place to live beyond one prompt |
| **Explainability and governance** | Decision traces are inspectable, auditable |
| **Long-running workflows** | Work state persists across many steps |
| **Shared multi-agent memory** | A common space agents read and write |
| **Lower token cost** | Pull only what the next step needs, not everything |

<!--
Seven is a lot to read aloud. Pick three that this demo actually shows and
linger there: explainability (the precedent trail), shared multi-agent
memory (the ending), and lower token cost (the graph is filtered before
anything reaches the prompt, nothing is dumped in wholesale).
-->

---

### Slide 8: Where It Lives: a Spring AI Advisor

- **CallAdvisor**: the one seam every query already passes through
- Runs **before** the model call and **after** it
- Nothing above it needs to know the graph exists

```
Request -> [ Decision Layer Advisor ] -> Model -> Response
                    |
                    v
                 Neo4j
```

<!--
An advisor is the right tool because it is the one place in a Spring AI
ChatClient that sees every request. LoanOfficer, the agent above it, has no
idea Neo4j exists.
-->

---

### Slide 9: What the Advisor Does, in Order

1. **Read** the company and its decision history out of the graph
2. **Measure** the policies against this file
3. **Ask** the model to decide, as a specific underwriter
4. **Write** the decision before returning an answer

<!--
Step 4 happens before the explanation is even shaped for the transcript.
The decision is a fact the moment the model commits to it.
-->

---

### Slide 10: The Demo: a Construction Loan Underwriter

- One Spring AI agent, one Neo4j graph, one Anthropic key
- **The rules never change.** Three policies, plain arithmetic
- **What changes is precedent.** Apply twice, get two different answers

<!--
Small on purpose. The mechanism is the point, not the domain. A bank
deciding loan applications is just a specific enough example to be
concrete.
-->

---

### Slide 11: The Graph Model

```
(Company)-[:SUBMITTED]->(LoanApplication)<-[:ABOUT]-(Decision)
                                                (Decision)-[:APPLIED_POLICY]->(Policy)
                                                (Decision)-[:ESCALATED_FROM]->(Decision)
                                                (Decision)-[:DECIDED_BY]->(Underwriter)
                             (Exception)-[:EXCEPTION_TO]->(Decision)
```

- **Decision**: the fact, with its outcome and its explanation
- **APPLIED_POLICY**: which rule decided it, and the numbers checked
- **EXCEPTION_TO**: a denial that stopped counting, still on file

<!--
Every arrow here is a relationship the next query can walk. That is the
difference between this and a decisions table.
-->

---

### Slide 12: Three Hops From One Decision

- Start at a denial
- **Hop 1**: the policy that caused it
- **Hop 2**: the exception that set it aside, if any
- **Hop 3**: every later decision this one has since driven

<!--
A table gives you a listing. A graph lets you walk outward from any one
row: what decided it, what excused it, and what it has decided since. That
third hop, reading ESCALATED_FROM backwards, is the one a table cannot
answer.
-->

---

### Slide 13: Why the Graph, Not Vector Search

| Vector search | Graph traversal |
|---|---|
| Finds decisions that **read alike** | Finds decisions that **apply** |
| Similarity to a document | **Position** in the decision record |
| Good at searching prose | Poor at proving a rule was already applied |

- **Applicability is not a similarity score**
- A denial matters because it belongs to this company, falls in this
  window, and has not been excepted, none of which lives in the wording

<!--
Nothing in this demo is embedded, and that's deliberate. A prior denial
counts because of three structural facts, not because it sounds similar to
this application. Similarity and structure compose, but structure has to
come first: traverse to what applies, then rank what's left.
-->

---

### Slide 14: Run It Twice

- Same command, same numbers, different answer
- First run: **one prior denial**, passes on history
- Second run: **two prior denials in the window**, Repeat Denial
  Escalation now decides it

<!--
Nothing about the company changed between the two runs. What changed is
the graph: the first run's decision is now precedent the second run reads.
This is the moment that makes the abstract claim concrete on stage.
-->

---

### Slide 15: A Person Decides, Not Just Arithmetic

- The model plays **a specific underwriter**, drawn at random each run
- Each one has **years on the job and a disposition**
- Same numbers, different underwriter, **a defensible different answer**

<!--
The policies are guidance the model weighs, not gates it obeys. Two lines
below at once is not arguable. One line missed narrowly, against a clean
history, is a judgement call, and who is on duty can move it.
-->

---

### Slide 16: Judgement Becomes Precedent

- An exception does not delete a denial, it **stops it from counting**
- **The denial stays on file.** Only its standing changes
- One exception granted today changes **every run after it**

<!--
This is the ratchet. A human judgement, once made, becomes a fact the
query reads, with no code change. Note for the room: today this repo's
exceptions are seeded, not granted live by the model mid-talk. If that
work has landed by the time you give this talk, show it live here. If not,
say so plainly and show the seeded one instead.
-->

---

### Slide 17: Built the Spring AI Way

- **Ordering**: runs once per turn, placed ahead of tool-calling
- **Typed parameters in**, not text parsed back out of a sentence
- **Native structured output**, not JSON begged for in the prompt
- **Chat memory stays clean**: the advisor rewrites its own response
  before handing it back up the chain

<!--
Worth a slide for the engineers in the room who will ask "is this just a
prompt trick." It is not. This is the advisor API used the way it is
meant to be used: request in, decide, mutate, respond.
-->

---

### Slide 18: One Agent Here, Many in the Architecture

- This repo runs **one agent**, on purpose, to keep the mechanism visible
- A second agent, say **portfolio risk**, reads the **same** `Decision`
  nodes, with no new plumbing and no handoff
- **Nothing passes between them.** No shared prompt, no tool call, no
  message bus

<!--
Say clearly: this is the argument for the ending, not a live second agent.
The graph is the only channel two agents would need, because what
travels through it is the reasoning behind a decision, not just the data
the decision was about.
-->

---

### Slide 19: Shared Reasoning, Not Just Shared Data

- **Shared data** gets a second agent to the same records
- **Shared decision traces** get it to the same conclusions, including
  ones reached once and never explained again
- The graph is not a faster memory. It is **where the reasoning lives**

<!--
Close on the distinction the whole talk is built on. Shared data is a
fact. Shared reasoning is a decision layer, and it is the thing that was
missing.
-->
