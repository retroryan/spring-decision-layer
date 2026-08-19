# Reference

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
running the same application again puts it in front of the same person. That is deliberate: the
only thing that changes between two runs of the same application is the precedent that arrived in
between, and a different underwriter on the second pass would leave a reader unable to say which of
the two moved the outcome. Different applications still reach different people, which is where the
spread between companies comes from.

The persona is appended to the user message beside the facts, not set on the system prompt. The
system prompt holds what does not vary, which is the role and how the fields are to be filled in.
A system prompt that changes per run is a prompt cache that misses per run.

## The Companies

Invented, and picked so that each one puts a different kind of pressure on the underwriter.

| Id | Name | Score | Debt | Income | At $250,000 |
| --- | --- | --- | --- | --- | --- |
| C-1042 | Ridgeline Builders | 72 | 710,000 | 2,000,000 | 48% debt to income, below the line, one standing denial |
| C-1077 | Cornerstone Concrete | 81 | 300,000 | 4,000,000 | every measurement above the line, nothing on file |
| C-1096 | Northgate Framing | 47 | 400,000 | 2,200,000 | credit score below the line |
| C-1123 | Summit Ironworks | 78 | 250,000 | 3,000,000 | every number clear, sitting on the escalation line |

`C-1042` owes 35.5% of its income on its own, which is above the line; the $250,000 being asked for
puts it at 48%. `C-1096` is denied on every run in practice, so after a few runs the credit score is
no longer the only thing below the line — it comes back on its own once those denials fall outside
the twelve-month window, or after the reset query below.

`C-1123` is the interesting one. Its numbers clear comfortably and it has three denials on file
from older, larger requests, one of which was excepted five months ago. Two still count, which is
exactly where Repeat Denial Escalation says to stop, so the only thing below the line is the
history. Approving means going past a line and saying so on the record. Denying a file whose every
number clears is defensible too, and which one happens is the underwriter's call — see
[`docs/graph.md`](graph.md#the-exception-and-what-happens-without-it) for how an exception changes
that count.

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
| `exception` | nothing on most runs: a standing denial this underwriter is setting aside, with the reasoning for it |

`decidingPolicyKey` is nullable on purpose. A denial reached on the pattern in a file rather than on
a number has no line to point at, and the console says so instead of naming a policy the
underwriter did not choose.

`exception` is the other nullable one, and it is the only field that is not about today's
application. It names a denial from before that should stop counting, which is why it sits beside
the outcome rather than inside it: a run can deny and set an older denial aside in the same breath,
and the two claims are written by two separate statements.

`confidence` earns its place on a file that misses a line by a little, where the answer is
genuinely not settled by arithmetic. `C-1077`, a company with nothing on file, asking for enough
to put its debt-to-income at 41.3% against a 40% limit with an otherwise clean record, has come
back both `APPROVED (borderline)` and `DENIED (borderline)` on different runs, with the same person
reading the same numbers — which is what an underwriter is and what a decision table is not.
`BORDERLINE` is the model saying so out loud, so a viewer who gets two different answers to the same
command sees judgement rather than suspects a bug. A margin like that 41.3% holds still where a
count does not: it reads the same on the tenth run, while an escalation count moves under you,
because each run's own denial becomes precedent for the next one. That ratchet is what makes a
file sitting exactly at a line a poor fixture to run repeatedly.

Thinking is turned off in `DecisionTraceAdvisor` rather than in `application.yaml`. Sonnet 5 thinks
adaptively unless told otherwise, thinking interleaves with the answer rather than finishing before
it, and `AnthropicChatModel` accumulates every text block into one string, so an abandoned draft and
the real answer can arrive concatenated. The merged document still parses, because the junk lands
inside a string value, which is how one run wrote a 996-character `reason` to the graph with
fragments of a discarded draft inside it. A field that quietly absorbs an abandoned draft is worse
than a run that fails outright, because it is stored, cited as precedent, and read back as though
somebody meant it.

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
