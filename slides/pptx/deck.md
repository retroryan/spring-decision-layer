---
output: ../the-decision-layer.pptx
---

::: slide title
title: The Decision Layer
subtitle: Shared reasoning for Spring AI agents
:::

::: slide three_col_text
eyebrow: THE PROBLEM
title: The smartest model we have ever had
heading1: The model
body1: |-
  It reasons, plans, writes code, and calls tools better than most of the
  people it works alongside.
heading2: The results
body2: |-
  95% of enterprise GenAI programs report zero return.
heading3: The business
body3: |-
  The org chart, the approvals, and the escalation paths are all unchanged.
notes: |-
  The model is not the reason these projects fail.

  Source: MIT Project NANDA, The GenAI Divide: State of AI in Business 2025.
  95% of organizations studied reported zero return from enterprise GenAI
  investment. The report calls its own figures directional.
:::

::: slide four_col_text
eyebrow: GROUNDING
title: Ground an agent with a decision layer
heading1: Meaning
body1: |-
  A definition inside the company decides what “active customer” counts as.
  The agent guesses at it.
heading2: Source
body2: |-
  One of five systems holds the number people trust. The agent cannot tell
  which one.
heading3: Exception
body3: |-
  Someone approved a bend in the rule last quarter. That approval lives in a
  closed thread.
heading4: Judgement
body4: |-
  A person already settled a case like this one. The reason went nowhere the
  agent can read.
notes: |-
  Documents tell an agent what the company published. Decisions tell it how the
  company operates. A construction company asks for a $250,000 loan, and four
  things settle the case.

  That context is not missing from the company. It sits in the heads of the
  people who have been there long enough to know. Every company has one person
  everyone taps on the shoulder. The agent cannot tap them.

  The last two are decisions, and decisions are the half that can be captured.
  A decision layer writes each one down with what authorized it, then reads it
  back on the next query, so an agent starts where the last one finished
  instead of deciding from zero.

  Business meaning and authoritative source need an ontology and a source map.
  This talk builds the decision layer.
:::

::: slide three_col_text
eyebrow: WHY IT MATTERS
title: What grounded context buys you
heading1: Accuracy
body1: |-
  The agent decides on real evidence instead of a guess.
heading2: Governance
body2: |-
  Every decision names the policy and the person behind it.
heading3: Persistence
body3: |-
  Context has a place to live beyond one prompt.
notes: |-
  Each one needs the same thing: a record of how the company decides.
:::

::: slide four_col_text
eyebrow: SPRING AI
title: The decision layer arrives by injection
heading1: Injected
body1: |-
  Spring hands the agent precedentAdvisor and decisionTraceAdvisor. The agent
  registers them and stops there.
heading2: Implicit
body2: |-
  The Neo4j starter configures the driver and the chat memory repository. The
  agent class names neither.
heading3: No Cypher
body3: |-
  The agent holds no graph code and opens no session.
heading4: Reusable
body4: |-
  Any agent that registers the same two advisors gets the same decision layer.
notes: |-
  An agent adopts the decision layer by registering two beans in its builder.

  The agent asks one question. The advisor chain manages the decision context.
:::

::: slide quadrant_2x2
title: Look up, check, decide, record
heading1: |-
  Look up the company, its policies, its standing denials, and the underwriter
heading2: |-
  Check the application against each policy threshold
heading3: |-
  Decide through the model, returned as a typed LoanVerdict
heading4: |-
  Record the decision and its authorization path in Neo4j
notes: |-
  PrecedentAdvisor owns the read path. DecisionTraceAdvisor owns the write
  path.

  The model makes the judgement. Java controls the trace: an unknown policy key
  writes no edge, and an invented citation gets dropped.
:::

::: slide before_after
title: Connections are the evidence
left_head: A log
right_head: A graph
left_row1: What happened?
left_row2: Who decided it?
left_row3: When was it recorded?
right_row1: Which policy authorized it?
right_row2: Which exception changed its standing?
right_row3: Which later decisions did it govern?
notes: |-
  A log stores fields about a decision. A graph stores the links between
  decisions, and those links are what prove a past decision applies here.

  Both hold the decision, and the fields are the same in either store. Only the
  graph holds the links, so policy, exception, actor, and lineage stay attached
  to the decision. The path is the explanation, so application code stops
  reassembling it.

  Connection decides whether a past decision applies to this case.
:::

::: slide quadrant_2x2
title: The asset your agents build
heading1: |-
  Governance stops being a project. The audit is a traversal.
heading2: |-
  Each new agent starts ahead of the last, inheriting what the others settled.
heading3: |-
  The company learns where its own rules are wrong, and how often.
heading4: |-
  The record outlives the model. What the business decided does not change.
notes: |-
  The decision layer is not a feature the agents use. It is an asset they leave
  behind, and it is worth more every quarter it runs.

  Every decision already names the policy that authorized it and the person who
  signed it. The fifth agent inherits what the first four settled, so it costs
  less to stand up than the one before it. The traces show which policies get
  overridden in practice, and how often. Frontier models will keep changing.
  What the business decided, and why, does not change with them.

  Swap the model next year and the advantage stays, because the advantage was
  never the model.
:::

::: slide quote
quote: What organizational context do your agents still have to guess?
:::

::: slide closing
headline: Thank you!
contact: ryan.knight@neo4j.com
:::
