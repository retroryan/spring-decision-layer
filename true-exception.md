# True Exception: Let the Model Decide

**Status**: Phase 1 is implemented and `./mvnw test` is green. Phases 2 and 3 are not started.
"Status and Progress" below records what is checked, what is still taken on trust, and what
Phase 1 knowingly broke for a later phase to repair.

## High Level Proposal

Today the Java policy engine decides the loan and the model writes a sentence about it. The
outcome is fixed before the model is called, so the graph is decoration. Flip it. The advisor
stops deciding and becomes a fact gatherer: it reads the company, the measured policy numbers,
and every past decision out of Neo4j, then hands all of that to the model and asks the model to
decide, acting like a human underwriter.

The payoff is the whole point of a context graph. Past decisions stored in the graph are what
change the next answer, not an `if` statement. And when the underwriter judges that an old denial
should stop being held against the company, the model grants a real `Exception` node against it.
That exception is read by the next run, so one judgement call made today quietly changes what
happens tomorrow. Nothing in the project creates an exception right now. This is what makes it
true.

## Judgement Is The Source, The Graph Is The Ratchet

A human underwriter given the same file twice does not always answer the same way, and that is not
a defect to be engineered out. It is the only mechanism by which precedent ever gets created. If
every run is deterministic the graph can only ever be read, never written to in a way that matters.

So the demo needs two things that sound opposed and are compatible:

- **The answer varies**, because a person decided it.
- **The variation happens once**, because the graph makes it permanent.

One run grants an exception. Every run after it reads a graph where that denial no longer counts,
with no coin flip at all, because the exception is now a fact the query reads. The randomness is
upstream of the precedent, and the precedent is what removes the need for randomness the next time.
That is the sentence the whole demo exists to earn, and a deterministic version cannot say it.

Which run grants it is not scheduled and cannot be. The demo seeds a position where an exception is
a defensible call and then lets whoever is on duty decide, so the order of outcomes is different
every time and the graph is the only thing that carries forward.

The design question is therefore not whether to have a source of variation. It is where the
variation is allowed to live. The answer below is: in the graph, as a person, not beside the graph
as hidden prompt state.

## What Changes

- **Advisor role**: `LoanPolicyAdvisor` goes from decider to fact gatherer. It reads the graph,
  builds a facts block, and lets the model answer.
- **Policy engine role**: `PolicyEngine` still does the arithmetic, but it only reports observed
  value against threshold. It no longer picks an outcome or a deciding policy. Its measurements
  still reach the graph, on whichever edge the outcome calls for.
- **Model role**: the model is the underwriter, and which underwriter it is changes per run.
- **Graph role**: past decisions and exceptions stop being a display listing and become the input
  that moves the answer. It also records the line an approval crossed, which is the one fact the
  current schema has no way to hold.
- **Write order**: the decision and its explanation are written once, after the model answers,
  because there is no decision until the model gives one.
- **A fourth node**: `Underwriter`, joined to the decision it made, and read back rather than only
  written. This is the part of the context graph definition the README currently admits is missing.

## The New Flow

- **Step 1, gather**: read the `Company`, the three `Policy` nodes, every past decision, and the
  precedent trail with its exceptions.
- **Step 2, measure**: compute credit score against the minimum, debt to income with this loan
  included, and the count of denials that still stand inside the rolling window.
- **Step 3, draw**: pick the underwriter on duty for this run, at random. There is no way to pin
  one, because a pinned outcome is the thing this document exists to stop doing.
- **Step 4, ask**: put the measurements, the history, and the underwriter's identity in the user
  message and ask for a verdict.
- **Step 5, receive**: get back a structured verdict, not prose to be parsed by hand.
- **Step 6, write**: write the `LoanApplication` and the `Decision` with its explanation, the
  `DECIDED_BY` edge to the underwriter, plus an `Exception` node with its `GRANTED_BY` edge if one
  was granted, plus `ESCALATED_FROM` edges to the denials the model cited.
- **Step 7, print**: show who decided, the facts given, the verdict, the reasoning, the precedent
  chain, and any exception granted.

## What Makes This a Graph and Not a Table

The exception ratchet is already graph-shaped: `FIND_PRIOR_DENIALS` carries
`NOT EXISTS { (:Exception)-[:EXCEPTION_TO]->(d) }`, so a granted exception changes the next read
with no code change at all. Writing `ESCALATED_FROM` from the model's own citations is stronger
still, because the graph then grows out of judgement rather than out of an `if`.

Three changes are needed on top of that, and without them the plan reproduces the flaw it opens by
criticizing. A node that is only ever written is decoration in exactly the way the current
`Decision` listing is decoration, and a fact the demo asserts out loud but never stores is worse.

- **Record the line an approval crossed**: today `APPLIED_POLICY` means the rule that caused the
  denial, so an approval carries no policy edge at all. The moment an underwriter approves over a
  number, the most interesting fact in the run has nowhere to live. Add
  `(Decision)-[:WEIGHED_PAST {observed, threshold}]->(Policy)`, written only when the outcome is
  `APPROVED` and that policy was measured below the line. `APPLIED_POLICY` keeps its meaning
  exactly, so `FIND_DECISIONS` and `FIND_PRECEDENT_TRAIL` need no changes, and the numbers ride on
  the edge for the same reason they already do on `APPLIED_POLICY`. Joined to `DECIDED_BY` it
  answers which underwriters approve past which policies, which is the demo's central claim as a
  traversal rather than as a sentence on a slide:

  ```cypher
  MATCH (u:Underwriter)<-[:DECIDED_BY]-(:Decision)-[:WEIGHED_PAST]->(p:Policy)
  RETURN u.name, p.name, count(*) AS approvals ORDER BY approvals DESC
  ```

- **Variable-length precedent chains**: `FIND_PRECEDENT_TRAIL` in `LoanGraph` matches
  `(later:Decision)-[:ESCALATED_FROM]->(d)` at depth one. Once the model cites freely, chains get
  deeper than one hop, and a depth-one match reports a parent link where there is a lineage.
  Change the pattern to `*1..` and return `length(path)` beside each descendant, so the console
  prints lineage by hop rather than a longer flat list that still reads as a parent link. One
  Cypher edit, no new nodes, and recursive traversal is the thing SQL is genuinely bad at.
- **Read the underwriter edges back**: one query and a handful of console lines for whose denials
  get excepted, and by whom. That is a join across `Underwriter`, `Decision`, and `Exception`
  that can only exist because a person decided, and it is what makes the fourth node earn its place. Writing
  the edges and never traversing them would leave `Underwriter` write-only. Both ends are nodes:
  `DECIDED_BY` names who made the denial and `GRANTED_BY` names who set it aside, so the query is a
  traversal rather than a traversal joined to a string property. The seeded decisions and the
  seeded exception are attributed to roster members rather than to a name that appears nowhere
  else, so the read returns rows on a fresh graph instead of staying empty until two live runs have
  happened, and every name in it is someone a later run can draw.

Deliberately out of scope, and worth a follow-up rather than a fold-in: every precedent read
filters by `companyId`. The graph's real edge is joining across entities, so "Dana approved a
similar file for a different builder last quarter" is the killer query, and it needs no new node
types, only a read that drops the company filter. It also changes what the demo is about, so it
belongs in its own pass.

## What the Model Gets

- **Its own identity**: which underwriter it is on this run, with the name, the title, the years
  on the job, and the disposition. Drawn per run, so it goes in the user message, not the system
  prompt.
- **The company numbers**: credit risk score, current debt, annual income, requested amount.
- **The policy guidance**: each policy by name, its threshold, and the observed value, marked as
  above or below the line. Guidance, not a gate.
- **The full history**: every past decision for this company with its date, outcome, amount, the
  policy that decided it, and whether it has been excepted.
- **The precedent trail**: for each denial, the policy behind it, the exception on it if any with
  the approver and the justification, and the later decisions it has already driven.
- **The standing denial count**: how many denials still count inside the window, and which
  decision ids they are, so the model can name what it is reacting to.
- **No verdict**: the current `VERDICT` block that says "the decision is final, explain it" is
  deleted.

## What the Model Sends Back

Use Spring AI structured output so the answer is a Java record, not text to be scraped.

- **outcome**: `APPROVED` or `DENIED`.
- **reason**: one short line naming what drove it, for storage on the `Decision` node.
- **decidingPolicyKey**: which policy weighed heaviest, or null if the numbers all cleared. On a
  denial it becomes the `APPLIED_POLICY` edge, and on an approval that crossed that line it becomes
  the `WEIGHED_PAST` edge, so the same field records either the rule that stopped the loan or the
  rule the underwriter went past.
- **citedDecisionIds**: which past decision ids influenced this call. The denials among them become
  the `ESCALATED_FROM` edges, so the graph records what the model actually leaned on while the edge
  keeps meaning what it means today.
- **explanation**: the two or three sentences for the applicant, unchanged in spirit from today.
- **exception**: null most of the time. When present it carries the `decisionId` being set aside
  and the justification for setting it aside. It is independent of `outcome`: an underwriter may
  deny today and still judge that an older denial should stop counting.
- **confidence**: `clear` or `borderline`. One field, and it is what lets the console show that a
  coin flip happened rather than hiding it.

## Two Wrinkles Worth Knowing Before Starting

Both are cheap to handle and expensive to discover late.

- **Structured output turns the transcript into JSON.** `MessageChatMemoryAdvisor` sits outside
  `LoanPolicyAdvisor`, so it stores whatever text the model returned. Once the explanation is a
  field inside a JSON verdict, the transcript section that `Application` prints stores and shows a
  JSON blob where it shows readable prose today. Fix it in the advisor: before returning up the
  chain, rebuild the response with an assistant message holding only the `explanation`. Chat memory
  then stores the letter, the console prints the letter, and the JSON never leaves the advisor.
  Accepting a JSON assistant message in the transcript is the fallback, and it costs one of the
  better parts of the current console output.
- **`decidingPolicyKey` needs a join the model cannot make.** `SAVE_DECISION` writes
  `APPLIED_POLICY {observed, threshold}` from the engine's `PolicyResult`. If the model picks the
  key, the advisor has to look up the engine's measurement for that key, and a model that names a
  policy which actually cleared writes a nonsense edge that the precedent trail then prints.
  Validate the key against the set of policies measured below the line, which is the same one-line
  `contains` as the exception check below. The key then routes the edge: `APPLIED_POLICY` on a
  denial, `WEIGHED_PAST` on an approval that crossed that line.
- **A denial can name no policy, and the console has to say so.** Validation can leave no key at
  all, either because the model returned null or because it named a policy that cleared. That is a
  legal outcome now: a pure judgement denial has nothing below the line to point at. Write no edge
  in that case rather than letting Java pick a policy the model did not choose. `FIND_DECISIONS`
  reads `policyName != null ? policyName : "every policy passed"`, so a `DENIED` row with no edge
  would print "every policy passed" beside it. The fallback text becomes "no policy named". A
  history-grounds denial is unaffected, because `repeatDenialEscalation` is genuinely below the
  line when the count trips, so it remains a valid key.

## The Underwriter Roster

Three named people in `seed.json`, one drawn per run. This is what replaces temperature, and it is
where the variation lives. An earlier draft put the variation in ten rungs of committee sentiment
in a `stances.json`, and later drafts talked about reading that file before deleting it; no such
file exists, so the three dispositions are written from scratch. Relocating the draw into the
graph is what makes the source of variation and the missing fourth element of the context graph
definition turn out to be the same object.

- **They are people, not moods**: an id, a name, a title, years on the job, a short `label` for the
  console, and a `disposition`, which is the line that goes in the prompt. Something like Marcus
  Feld, seventeen years, worked through 2008, wants reasons to say no. Dana Whitfield, six years,
  growth-minded, willing to back a clean history against one number that misses. Priya Raman,
  eleven years, splits the difference and weighs the trail heavily.
- **They live in `seed.json`**: alongside the companies and policies, loaded by the existing
  `Seed` record and MERGEd by `GraphSeeder` like everything else. A new `SeedUnderwriter` record
  and one more MERGE. The wording can still be tuned without a rebuild.
- **They become nodes**: `(:Underwriter {underwriterId, name, title, yearsOnTheJob})` with a
  `loan_underwriter_id` constraint beside the other five,
  `(Decision)-[:DECIDED_BY]->(Underwriter)` written where `saveDecision` already writes, and
  `(Exception)-[:GRANTED_BY]->(Underwriter)` written beside a granted exception.
- **The disposition rides on the edge**: `DECIDED_BY {disposition: "..."}`, carrying the wording as
  it stood when the decision was made. Same reasoning as `APPLIED_POLICY {observed, threshold}`,
  which already stores the numbers at decision time so a later edit to a `Policy` node cannot
  rewrite history. Retuning a disposition must not retroactively change why an old decision went
  the way it did.
- **Where the persona goes**: appended to the last user message with the facts, not the system
  prompt. A per-run identity in a varying system prompt invalidates the prompt cache on every run.
  The system prompt keeps the role and the tolerance, which do not vary; the user message carries
  who is on duty today, which does.
- **Not forceable, on purpose**: the draw is random and there is no flag to pin it. An earlier
  draft added `--underwriter <id>` so an exception could be made to happen on cue, on the argument
  that running it five times hoping for a flip is not a demo. That is a scripted outcome wearing
  the language of judgement, and it turns the roster back into a mood dial with three settings.
  What the demo pins is the position, not the answer: seed a file where an exception is a
  defensible call, then run it until somebody makes it. Nothing is lost to reproducibility, because
  the graph records who decided every run.
- **Two levels of randomness, not one**: the draw moves which band the decision sits in, and the
  model's own default sampling still moves the answer inside that band. The same underwriter given
  the same genuinely borderline file will not always answer the same way. That is closer to a real
  person than a discrete rung ever gets, and it costs nothing.
- **Three is the roster**: enough to show a spectrum, cheap to keep distinct, and small enough that
  a handful of runs on one file lands on each of them, which is what makes the spread visible
  without a calibration matrix. A name in a decision letter is a signature rather than a leak, so
  there is nothing to scrub and no retry path, which a committee mood would have needed.

## The Human Touch

- **Judgement, not arithmetic**: the system prompt says the thresholds are guidance an experienced
  underwriter is allowed to weigh, and that a clean history can outweigh one number that misses by
  a little.
- **A stated tolerance**: the prompt names how far off is too far. One policy missed by a small
  margin is arguable. Two policies missed, or one missed badly, is not. This is in the system
  prompt because it does not vary by who is on duty.
- **Signed, not anonymous**: the explanation is allowed and encouraged to name the underwriter who
  decided it, because that is what a real letter does. The name also lands in
  `Exception.grantedBy`, which is a string today for exactly this reason, beside the `GRANTED_BY`
  edge that makes it traversable.
- **No temperature knob**: `temperature`, `top_p`, and `top_k` are removed on `claude-sonnet-5`,
  not merely restricted. Sending them returns a 400. The same is true of Opus 5, Opus 4.8 and 4.7,
  and Fable 5. `output_config.effort` and adaptive thinking are the only remaining dials on the
  model and neither is a randomness dial, so prompt-level variation is not a preference here, it is
  the only route left. Anthropic's stated replacement is to steer behaviour from the prompt.
- **Honesty about the flip**: the model reports `borderline` when it went either way on a close
  call, and the console prints that, so a viewer sees judgement rather than suspecting a bug.
- **Out of the transcript for free**: the advisor already sits inside `MessageChatMemoryAdvisor`,
  so what it appends to the user message never reaches stored chat memory. The persona block stays
  out of the transcript by the same ordering that keeps the verdict out of it today.
- **Recorded, not hidden**: the console prints which underwriter drew the run. A viewer who sees
  two different answers to identical input should be able to see who decided in the same output.

## The True Exception

- **When it fires**: the underwriter judges that a denial on file should stop being held against
  the company. That is a judgement about the record rather than a device for unblocking today's
  answer, so it does not depend on how this run came out. An earlier draft allowed it only where a
  standing denial was the single thing blocking an approval, which is narrower than what a person
  does. Denying today on the numbers while saying that a denial from nine months ago was about
  circumstances since resolved is ordinary underwriting, and it is the case that puts a judgement
  into the graph on a run that would otherwise have written none.
- **What it writes**: an `Exception` node with a generated `exceptionId`, `grantedBy` set to the
  underwriter who drew this run, `justification` in the model's own words, and `grantedAt` set to
  now, with one `EXCEPTION_TO` edge to the denial and one `GRANTED_BY` edge to the underwriter. The
  string stays for the console and for the seeded exception; the edge is what the read back
  traverses.
- **What it does not do**: it does not touch the denial. The denial stays on file with its policy
  and its numbers and stops counting, which is already how `FIND_PRIOR_DENIALS` reads the graph.
- **Why it closes the loop**: no code change is needed for the exception to matter. The existing
  query already skips excepted denials, so the next run reads a smaller denial count and can reach
  a different outcome.
- **A run that denies and grants nets out**: it removes one standing denial and adds another, so
  the count lands where it started. The ratchet turns at the speed judgement turns it, and some
  runs do not turn it at all. That is the honest behaviour, not a case to engineer around.
- **Provenance**: add a `source` property, `seed` or `underwriter`, so a granted exception is
  visibly different from the one in `seed.json`. `X-1123-SEED` stays in place, so a fresh graph
  still has one exception to read, and `source` is what tells the two apart.
- **No cap needed**: once a denial is excepted it stops appearing in the facts block, so it cannot
  be excepted twice. The graph enforces this already and nothing on top of it is required.
- **The position, not the script**: `C-1123` at `500000` clears every number and sits on exactly
  the standing denial count that Repeat Denial Escalation trips at, so the history is the only
  thing below the line. On that file denying, approving, and granting an exception are all
  defensible, and the draw decides which one happens. Run it repeatedly. Different names decide it,
  the answers differ, and the first exception anyone grants is read by every run after it. Nothing
  about the order is arranged, which is the claim; a three-run sequence that came out the same way
  every time would be evidence against it rather than for it.
- **What the seed has to provide**: one more denial on `C-1123`, so it holds two standing denials
  beside the one already excepted. Without it no company is in that position. `C-1042` misses Debt
  to Income at any interesting amount, so the numbers block it on every run forever and an
  exception there tidies the record without ever changing an outcome.

## Guardrails

One check, a one-liner on a structured field. Hard floors enforced in Java are deliberately not
here: this document spends its length arguing that Java should stop deciding, and a demo with no
adversarial user does not need Java vetoes reinstated at the end of it.

- **Exception must point somewhere real**: the `decisionId` on a granted exception has to be one
  of the denials that were sent in the facts block. A `List.contains`, and the same reasoning as
  the seeder using `MATCH` rather than `MERGE` for the decision. An exception pointing at nothing
  writes a dangling node, which is a broken graph rather than a bad judgement call.

Four things that look like guardrails and are cheaper as behaviour, or are not guardrails at all:

- **One exception per run is the record shape**: the verdict carries a single nullable `exception`
  field, so a run cannot return two. Nothing needs checking.
- **An exception on a denial is not an error**: the exception is a judgement about the record, so
  it stands on its own regardless of how the run came out. Rejecting one because the verdict was
  `DENIED` would be enforcing the narrow reading this document already dropped.
- **Unknown cited ids are filtered, not retried**: `SAVE_DECISION` already resolves citations with
  `OPTIONAL MATCH (earlier:Decision) WHERE earlier.decisionId IN $escalatedFrom`, so an id the
  graph does not hold is dropped on its own. Filtering the list is one line; a retry path is a loop.
  The same filter drops approvals, since `ESCALATED_FROM` means this decision was reached over a
  standing denial and an approval is not one.
- **A failed call fails the run**: this is a single-run CLI demo. A model timeout prints a stack
  trace and the operator runs it again. An earlier draft added an `UNDECIDED` outcome constant, a
  console branch, and a test for it, and then conceded that `FIND_PRIOR_DENIALS` filters on
  `DENIED`, so the row it writes is precedent nothing reads.

Numbers inside the prose are not validated, because matching figures against free text is fragile
and fails open where it matters. The check above is worth having precisely because it is on a
structured field.

## What We Give Up

- **Determinism**: the current design commits before the model is called, so a model failure costs
  the sentence and nothing else. That property is gone, and one write after the answer replaces it.
  Writing a pending decision first and filling in the outcome later would be a second write path
  for no gain.
- **Test shape**: `PolicyEngineTests` currently asserts outcomes. Those become assertions about
  the measurements and the facts block. Outcome level checks move to asserting internal agreement,
  meaning the verdict, the reason, and the cited decisions do not contradict each other.
- **README transcripts**: the recorded runs no longer reproduce word for word, and the integration
  test expectation in `ExampleInfo.json` has to be rewritten to check consistency rather than a
  fixed outcome.
- **The blunt prompt**: the "do not second guess" framing was doing real work. Removing it invites
  the model to reason, which is the goal and also the risk.
- **A rehearsable run**: there is no sequence of commands that produces an exception on cue. The
  demo shows a position and lets it play, so a walkthrough means running the same command several
  times and reading what the graph accumulated, not narrating a script.

## Files To Touch

- **`LoanPolicyAdvisor`**: gather, draw, ask, receive, write. The `VERDICT` constant goes away and
  a facts block plus a persona block replace it. It also rebuilds the assistant message down to
  the explanation before returning.
- **`PolicyEngine`**: keep the arithmetic, delete `evaluate`, `reasonFor`, the deciding-policy
  pick, and the `LoanDecision` record they exist to build. `APPROVED` and `DENIED` move to the
  verdict record before the record goes, because `LoanGraph` parameterizes `FIND_PRIOR_DENIALS` and
  `FIND_PRECEDENT_TRAIL` with `LoanDecision.DENIED` and `LoanGraphTests` uses both constants.
- **`LoanOfficer`**: new system prompt with the role framing and the tolerance, plus structured
  output on the call. The identity is not in here, because it varies per run.
- **New record for the verdict**: the shape the model returns.
- **`LoanGraph`**: the `Exception` write and its two edges, the `DECIDED_BY` write, the
  `WEIGHED_PAST` write beside the existing `APPLIED_POLICY` FOREACH, a read for the roster, a read
  back across both `DECIDED_BY` and `GRANTED_BY`, a read joining `DECIDED_BY` to `WEIGHED_PAST`,
  `*1..` on the precedent trail returning `length(path)`, and a change to `saveDecision` so the
  outcome and the explanation both come from the model's answer in one write. `ATTACH_EXPLANATION`
  and `attachExplanation` are deleted with the second write. The comment above `SAVE_DECISION`
  describing `ESCALATED_FROM` needs updating, since the ids now arrive from the model and are
  filtered to denials in Java rather than implied by the deciding policy.
- **`PrecedentTrail`**: `governed` stops being a `List<String>` once the trail returns a depth
  beside each descendant, so the record carries id and depth pairs and
  `Application.printPrecedentTrail` stops joining it with `String.join`.
- **`seed.json`**: an `underwriters` array, plus a `SeedUnderwriter` record on `Seed` and one more
  MERGE and one more constraint in `GraphSeeder`. One more standing denial on `C-1123`, with a
  `monthsAgo` inside the twelve-month window so it actually counts, which puts the company at the
  escalation threshold. The seeded decisions and `X-1123-SEED` are attributed to roster members
  rather than to `M. Alvarez`: the denials to the most cautious underwriter, the exception to the
  most permissive one, so `grantedBy` changes and the read back returns one person setting aside
  another person's call on a graph nobody has run against yet. No fourth historical underwriter,
  which keeps the roster three and keeps every name in the read back drawable by a later run.
- **`Application`**: print who decided, the facts given, the verdict, the confidence, the precedent
  chain, the line any approval crossed, who excepted whose denials, and any exception granted. No
  new flags to parse. Two wordings have to change: the checklist has to survive a denial where
  every measurement passed, since judgement can reach one and the current phrasing reads as a
  contradiction, and `printHistory` has to stop printing "every policy passed" beside a `DENIED`
  row that names no policy. That fallback becomes "no policy named".
- **`application.yaml`**: no sampling settings, since they are removed on Sonnet 5. The model pin
  comment needs rewriting, because reproducible transcripts stop being the reason it is pinned.

## Settled Questions

Carried as open questions in an earlier draft, and answered here so the phases below have no
branches in them.

- **Push, not pull**: the history goes in the prompt rather than behind read tools. Sending it is
  simpler and keeps the demo readable. Tools would show the model choosing what precedent to look
  at, which is a different and larger demo.
- **Who may grant**: whoever drew the run, on any run, whatever the outcome. The exception is a
  judgement about whether a denial should still count, not a lever for today's answer.
- **The crossed line is its own edge**: `WEIGHED_PAST` records a policy an approval went past, and
  `APPLIED_POLICY` keeps meaning the rule that caused a denial. One field on the verdict routes to
  whichever edge the outcome calls for, and no existing query changes meaning.
- **A denial may name no policy**: validation can leave no usable key, so no policy edge is written
  and the console says "no policy named" rather than having Java choose a policy the model did not.
- **Seeded rows get roster names**: the seeded decisions and `X-1123-SEED` are attributed to the
  three, so the read back is populated on a fresh graph without a fourth historical underwriter.
- **No pinning**: the draw is random and there is no flag to override it. The demo pins the
  position in the seed and lets the roster decide.
- **One write, not two**: the decision and its explanation are written together after the answer
  comes back, so `ATTACH_EXPLANATION` goes away.
- **Structured output is native**: `AnthropicChatOptions` implements `StructuredOutputChatOptions`,
  so `outputSchema` maps to `output_config.format` and the JSON is valid by construction. No
  format instructions in the prompt, and no hand-rolled parsing.
- **Repeat exceptions**: no cap beyond what the graph enforces on its own.
- **Roster size**: three.
- **The seeded exception stays**: `X-1123-SEED` remains, and `source` distinguishes a granted
  exception from it.
- **Cross-company precedent**: a follow-up, not part of this work.

## Status and Progress

Phase 1 is implemented. `./mvnw test` is green: 33 tests, 22 of them against a real Neo4j 5.26 in
Testcontainers. Phases 2 and 3 have not been started. What follows is what the tree does now,
what is checked, and what is still taken on trust, so the next phase starts from the code rather
than from this document.

### What Phase 1 shipped

- **The flip**: `LoanPolicyAdvisor` reads the graph, measures, appends facts, and calls the model.
  `saveDecision` runs after `chain.nextCall` and carries the explanation, so there is one write.
  `ATTACH_EXPLANATION` and `attachExplanation` are gone.
- **The verdict**: `LoanVerdict(outcome, reason, decidingPolicyKey, citedDecisionIds, explanation,
  confidence)`, with `Outcome` and `Confidence` as nested enums so the schema itself refuses a
  third case. `LoanDecision` is deleted, and `measure` plus the three arithmetic methods are all
  that is left of `PolicyEngine`.
- **Both policy edges**: `APPLIED_POLICY` on a denial, `WEIGHED_PAST` on an approval that crossed
  the line it named, and neither when the key resolves to nothing.
- **The chain**: `ESCALATED_FROM*1..` inside a `COLLECT` subquery, `PrecedentTrail.governed` as a
  `List<PrecedentStep>`, and a console that indents each step by its depth.

### What the tests pin

- The one write carries the explanation and the confidence, so no decision exists unexplained.
- Both edge types on the same policy key, and the verdict that gets neither.
- A cited id the graph does not hold is dropped rather than failing the write.
- A citation of a citation comes back at depth 2, and a denial nothing has cited comes back as an
  empty list rather than as a row of nulls.
- Every measurement, against the numbers in `seed.json`, with no test asserting an outcome.

### What is not verified

- **No live model call has been made.** `ANTHROPIC_API_KEY` was unset in the environment the work
  was done in, so the "Verifiably true" checks under Phase 1 are outstanding. Nothing has confirmed
  that Anthropic accepts the generated schema on the wire, or that the model returns a
  `decidingPolicyKey` that resolves against the measurements.
- **The prompt is unexercised.** The facts block, the stated tolerance, and the field guide have
  been written and read, never answered.

### Learned while implementing, and worth keeping

- **The facts block has to print the policy key.** The first version printed only the display name
  while the prompt asked for a key and the advisor matched on a key. A near miss there is silent:
  nothing resolves, no edge is written, and the console says "no policy named" as though the
  underwriter had decided on the file as a whole, which is the demo's central fact quietly lost.
  The key now leads each measured line and the prompt says to copy it exactly.
- **`@Nullable` is what makes a component optional.** jspecify's annotation on `decidingPolicyKey`
  is read by Spring AI's schema module and keeps the property out of `required`. Without it the
  model has to send the field on every call, including the runs where there is no line to name.
- **The schema has no `$defs`.** Confirmed against the generated output rather than assumed, so
  the stripping in `AnthropicChatOptions.Builder.outputSchema` cannot leave a dangling `$ref`.
  Both enums inline as string enumerations, and `additionalProperties` is false.
- **`COLLECT` rather than `OPTIONAL MATCH` on the chain.** An `OPTIONAL MATCH` with a `collect`
  returns one row of nulls for a denial nothing has cited, which the console would print as a
  step. `min(length(path))` reports a decision reachable two ways once, at its shortest distance.
- **`ChatResponse.Builder.from` copies the generations**, so `generations` has to be set after it
  rather than before, or the rebuilt response still carries the JSON.

### Broken by Phase 1, repaired by a later phase

Recorded here rather than fixed early, because both belong to phases that rewrite the same files
for their own reasons. Neither affects `./mvnw test`.

- **`integration-tests/ExampleInfo.json` no longer matches the console.** Three `successRegex`
  entries look for `PASS` or `FAIL`, one looks for `\nPolicies\n` where the heading is now
  `Policies, as measured`, and one looks for `has decided` where the trail now says `has driven`.
  `expectedBehavior` still describes a run whose outcome Java computed before the model was
  called. Phase 3 owns the rewrite, and until it lands the integration test fails on a working
  demo.
- **`application.yaml` and `README.md` still describe the old design.** The model pin comment
  gives reproducible transcripts as its reason, and the README still has the four-step flow, the
  run-it-twice transcripts, and a "what it leaves out" section that says exceptions are only ever
  seeded. Phase 2 owns the first and Phase 3 owns the second.

## Phased Implementation Plan

Three phases. Each one runs, each one shows something on the console that the phase before it could
not, and each one adds one traversal the graph could not do before. An earlier draft ran to five;
the guardrail phase collapsed to a single `contains` check, and console, test, and doc work is the
tail of each phase rather than a phase of its own.

### Phase 1: The Flip, the Structured Verdict, and the Precedent Chain

- **Status**: done, and green under `./mvnw test`. Everything below the model call is covered by
  tests; the "Verifiably true" run at the end of this phase is still outstanding, because no live
  model call has been made.
- **Goal**: the model picks the outcome, the advisor stops picking it, the answer arrives as a Java
  record, and the graph reports lineage instead of a parent link.
- **Files**: `LoanPolicyAdvisor`, `PolicyEngine`, `LoanDecision`, `LoanOfficer`, `LoanAnswer`,
  `LoanGraph`, `PrecedentTrail`, `Application`, `PolicyEngineTests`, `LoanGraphTests`, new verdict
  record. `Application` and `LoanGraphTests` are here rather than later because deleting
  `LoanDecision` and reshaping `PrecedentTrail.governed` both reach them in this phase.
- **One phase, not two**: an earlier draft split this, with the first half reading the outcome word
  off the first line of the prose as a deliberate stopgap. That is scaffolding built to be deleted
  one phase later. Structured output is available on day one, so start with the record.
- **The write moves**: `saveDecision` is called after `chain.nextCall` instead of before, because
  there is no outcome until the model answers, and it carries the explanation with it so there is
  one write rather than two. `getOrder` stays where it is.
- **The facts block replaces `VERDICT`**: same append point on the user message, new content. The
  company numbers, each policy with its observed value and threshold, the denial count, and the
  ids of the denials that still stand. Each measured line leads with the policy `key` and not only
  its display name, because `decidingPolicyKey` is matched against the key exactly and a model left
  to derive `debtToIncomeLimit` from "Debt to Income Limit" fails silently rather than loudly.
- **The shape**: `outcome`, `reason`, `decidingPolicyKey`, `citedDecisionIds`, `explanation`,
  `confidence`. The `exception` field is added in Phase 3.
- **Where the parsing lives**: the advisor sees a `ChatClientResponse`, not a typed entity, so the
  advisor owns the conversion. `LoanOfficer` keeps returning `chatClientResponse` and keeps reading
  `LoanAnswer` out of the response context. An earlier draft called this the fiddliest part of the
  plan, on the belief that the format instruction had to be appended to the prompt by hand.
  `AnthropicChatOptions` implements `StructuredOutputChatOptions`, so setting `outputSchema` on the
  request options sends `output_config.format` natively and the response text is valid JSON by
  construction. The advisor generates the schema from the record, sets it on the options, and
  converts the text back. Nothing about format goes in the prompt. Three details to get right:
  mutate the existing options rather than building fresh ones, or the model pin and everything else
  from `application.yaml` is dropped; convert with `BeanOutputConverter` rather than a bare
  `ObjectMapper`, because its default cleaner strips thinking tags and markdown fences for free and
  adaptive thinking is on by default; and print the generated schema once, because
  `AnthropicChatOptions.Builder.outputSchema` silently strips `$schema` and `$defs`, and a
  surviving `$ref` with its definitions removed is a broken schema. Spring AI generates with
  victools `OptionPreset.PLAIN_JSON`, which inlines nested types, so the nested `exception` object
  should produce no `$defs` at all. Worth confirming rather than assuming.
- **The native path falls through rather than failing closed**: an earlier reading of
  `ChatModelCallAdvisor.augmentWithFormatInstructions` had it returning early on the native branch,
  so a schema that failed to apply would leave the model with no format instruction at all. Read
  against the 2.0.0 source, it returns early only when the options implement
  `StructuredOutputChatOptions`. Otherwise it falls through and appends `outputFormat` to the user
  message, which is the literal string `null` when nothing set it. The advisor therefore sets
  `STRUCTURED_OUTPUT_SCHEMA`, `STRUCTURED_OUTPUT_NATIVE`, and `OUTPUT_FORMAT` together: the first
  two drive the native path, and the third keeps the fallback readable if it is ever taken.
- **Keep the transcript readable**: rebuild the response with an assistant message holding only the
  `explanation` before returning up the chain, so `MessageChatMemoryAdvisor` stores the letter
  rather than the JSON. See the wrinkles section above.
- **`PolicyEngine` keeps the arithmetic**: `creditScore`, `debtToIncome`, and `escalation` stay.
  `evaluate`, `reasonFor`, the deciding-policy pick, and the `LoanDecision` record are deleted in
  this phase rather than left sitting unused, since leaving a second decision path in the tree is
  what this phase exists to remove. `APPROVED` and `DENIED` move to the verdict record first:
  `LoanGraph` passes `LoanDecision.DENIED` into `FIND_PRIOR_DENIALS` and `FIND_PRECEDENT_TRAIL`, and
  `LoanGraphTests` uses both. `PolicyEngineTests` moves to asserting measurements here, for the
  same reason.
- **`ESCALATED_FROM` comes from the model**: the edges are written from `citedDecisionIds` rather
  than from `decidedBy(REPEAT_DENIAL_ESCALATION)`, so the graph records what the model leaned on.
  Unknown ids are filtered out of the list rather than retried, and so are approvals, since the
  edge means this decision was reached over a standing denial.
- **`decidingPolicyKey` is validated, then routed**: the key has to be one of the policies measured
  below the line, so the edge gets the engine's real measurement and the precedent trail cannot
  print a policy that actually cleared. A denial writes `APPLIED_POLICY {observed, threshold}` as
  today. An approval that crossed that line writes `WEIGHED_PAST {observed, threshold}` instead,
  which is the fact the current schema cannot hold. No usable key means no edge, and the console
  says "no policy named" rather than "every policy passed".
- **`*1..` on the trail**: `FIND_PRECEDENT_TRAIL` changes from `(later:Decision)-[:ESCALATED_FROM]->(d)`
  to a variable-length pattern returning `length(path)` beside each decision, so the console prints
  the chain a citation started, indented by depth, rather than its first hop.
- **Verifiably true**: run `C-1042` at `250000` and at `1`. The outcome changes with the amount,
  and the `Decision` node carries what the model said, not what the engine computed. Run it three
  times and the third decision's citations show up as a depth-annotated chain under `has decided`,
  not as one hop. The transcript still prints readable prose rather than JSON, from a single write.
- **Deliberately still broken**: every run is answered by the same anonymous voice, so two
  identical runs give much the same answer. Nothing grants an exception.

### Phase 2: The Underwriter Roster

- **Status**: not started. One carried-over item from Phase 1: the model pin comment in
  `application.yaml` still gives reproducible transcripts as the reason, which stopped being true
  the moment the model started deciding.
- **Goal**: a named person decides, which person changes per run, and borderline cases go both
  ways because of it.
- **Files**: `seed.json`, `Seed`, `GraphSeeder`, `LoanGraph`, `LoanPolicyAdvisor`, `LoanOfficer`,
  `Application`, `application.yaml`.
- **Start with the dispositions**: three people, written as people, written from scratch. An
  earlier draft said to read `stances.json` first and then delete it. There is no such file in the
  tree or in the history. Everything else in this phase is plumbing that carries one person into
  the prompt.
- **The seed grows**: an `underwriters` array, a `SeedUnderwriter` record, one MERGE, and a
  `loan_underwriter_id` constraint beside the other five. The seeded decisions go to the most
  cautious roster member and `X-1123-SEED` to the most permissive, so the read back has two
  different people in it and no fourth name appears that the roster cannot explain. `C-1123` gets
  one more standing denial, which is what puts a company at the escalation threshold with every
  other number clear. Its `monthsAgo` has to fall inside the twelve-month window or
  `FIND_PRIOR_DENIALS` will not count it and the position stops being borderline.
- **The draw**: random per run, with no flag to pin it. Nothing to parse.
- **The persona block**: name, title, years, and disposition appended to the last user message with
  the facts. Not the system prompt, which keeps the role framing and the tolerance.
- **The new edge**: `(Decision)-[:DECIDED_BY]->(Underwriter)` carrying the `disposition` as it
  stood at decision time, written in the same statement that already writes the decision.
- **No sampling settings**: they are removed on `claude-sonnet-5` and return a 400, so leave them
  out of `application.yaml` and rewrite the model pin comment, since reproducible transcripts stop
  being the reason it is pinned.
- **Check the thinking default**: Sonnet 5 runs adaptive thinking when `thinking` is omitted, which
  Sonnet 4.6 did not. Confirm what Spring AI sends before blaming the prompt for the answers.
- **Verifiably true**: run `C-1123` at `500000` several times. Different names decide it and the
  close call lands both ways, with the reason tracking the person rather than the arithmetic. Run a
  company that clears every number by a distance and the answer holds steady whoever draws it, so
  the spread is in the judgement and not in the plumbing.
- **The traversal payoff**: one query joining the new edge to the one Phase 1 added.

  ```cypher
  MATCH (u:Underwriter)<-[:DECIDED_BY]-(:Decision)-[:WEIGHED_PAST]->(p:Policy)
  RETURN u.name, p.name, count(*) AS approvals ORDER BY approvals DESC
  ```

  Which underwriter approves past which line, and how often. It is three console lines, it needs no
  new nodes, and it is the reason `Underwriter` is not a write-only node the moment it is added.
- **Deliberately still broken**: an underwriter who wants to approve past a standing denial has no
  way to say so on the record. They either approve and leave the denial counting against the next
  application, or they deny.

### Phase 3: The Granted Exception, the Read Back, and the Docs

- **Status**: not started, and it now carries a repair as well as a feature. Phase 1 replaced
  `PASS` and `FAIL` with above and below the line, which this phase had scheduled, so
  `integration-tests/ExampleInfo.json` and `README.md` describe a console and a flow that no
  longer exist.
- **Goal**: an underwriter can set aside one standing denial, the next run reads the smaller count,
  the graph answers who excepted whose denials, and what is printed, asserted, and documented
  matches what the code does.
- **Files**: verdict record, `LoanGraph`, `LoanPolicyAdvisor`, `Application`, `LoanGraphTests`,
  `README.md`, `integration-tests/ExampleInfo.json`.
- **The record grows**: an `exception` field carrying the `decisionId` being set aside and the
  justification. Null on most runs, and independent of `outcome`, so a denial can carry one.
- **The new write**: one statement in `LoanGraph` that `MATCH`es the denial and creates the
  `Exception` node with its `EXCEPTION_TO` edge and a `GRANTED_BY` edge to the drawn underwriter.
  `MATCH` rather than `MERGE` on the denial, for the reason `MERGE_EXCEPTION` already uses it.
  `grantedBy` stays as a string beside the edge, and a `source` property tells a granted exception
  apart from a seeded one.
- **The one check**: the exception target has to be one of the denials that were sent in the facts
  block. A `contains` in the advisor. One per run is the record shape, and an exception on a denial
  is allowed.
- **The read back**: one query walking
  `(grantor:Underwriter)<-[:GRANTED_BY]-(e:Exception)-[:EXCEPTION_TO]->(d:Decision)-[:DECIDED_BY]->(decider:Underwriter)`,
  printed as console lines naming who set aside whose denial. Both edges are needed: `DECIDED_BY`
  says who made the call being set aside and `GRANTED_BY` says who set it aside, and the sentence
  is only interesting when the two are different people. This is what stops `Underwriter` being a
  write-only node, and it is the traversal that only exists because a person decided.
- **No constraint work**: `loan_exception_id` is already created by `GraphSeeder`, so a live
  `Exception` node is covered.
- **No query work on the denial count**: `FIND_PRIOR_DENIALS` already skips excepted denials, and
  `FIND_PRECEDENT_TRAIL` already reads `grantedBy` and `justification`.
- **Console**: lead with who decided, then the exception read back and any exception granted.
  Phase 1 already did the rest, ahead of schedule and for a reason: `PASS` and `FAIL` could not
  survive a denial where every measurement was above the line, so they became above and below the
  line in the same phase that made such a denial possible. The verdict, the confidence, the line
  crossed, and the depth-indented chain all print today.
- **`PolicyEngineTests`**: already moved to measurements in Phase 1, alongside the deletion of
  `evaluate`. Nothing left to do here.
- **`LoanGraphTests`**: add coverage for the exception write and both its edges, the `DECIDED_BY`
  and `GRANTED_BY` writes and the read back that walks them, and the join from `DECIDED_BY` to
  `WEIGHED_PAST`. Phase 1 already covers the `WEIGHED_PAST` write itself, the verdict that names no
  policy and gets neither edge, the depth-annotated chain, and the outcome arriving from a verdict
  rather than an engine result. Every existing traversal test stays as it is.
- **New tests**: internal agreement on a verdict, meaning the outcome, the reason, the cited ids,
  and any exception do not contradict each other.
- **`ExampleInfo.json`**: the `successRegex` list matches `PASS`, `FAIL`, and
  `(APPROVED|DENIED)\. `, all three of which can now be wrong on any given run. Rewrite them
  against the parts of the console that are structural rather than decided, and rewrite
  `expectedBehavior`, which currently asserts the Java engine computes the verdict, to describe a
  run whose outcome is the model's and whose parts agree with each other.
- **README**: rewrite the four-step flow, the run-it-twice transcripts, and the "what it leaves
  out" section, which says who approved is missing and that exceptions are seeded rather than
  granted. Both are now false, and the `Underwriter` node it proposes as the obvious next expansion
  has been built and is traversed.
- **Verifiably true**: run `C-1123` at `500000` until somebody grants an exception, which is a
  property of the position rather than a scripted sequence. Once one is granted: the precedent
  trail names the underwriter under `exception`, the history listing marks that denial as excepted,
  the read back names who set aside whose denial, and every run after it reads one fewer standing
  denial without a line of code changing. `./mvnw test` is green, and a run's console output reads
  as one story from who is on duty, through the facts, to the verdict, to the exception.
- **What is left**: cross-company precedent, which is its own pass and its own demo.
