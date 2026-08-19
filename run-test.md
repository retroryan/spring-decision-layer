# Run Test: agents/context-graph against real Neo4j and real Anthropic credits

## Key requirement: keep the status table current

**This file is the record of the run. Every step below updates the table before moving on.**

Rules for whoever executes this, human or agent:

1. Set the step to `RUNNING` before starting it, and to `PASS`, `FAIL`, or `BLOCKED` with a
   one-line note the moment it finishes. Never batch the updates at the end.
2. `FAIL` and `BLOCKED` stop the run. Report and wait; do not continue to the next step.
3. Record what was observed, not what was expected. A step that produced the right verdict for the
   wrong reason is a `FAIL`.
4. Never write a secret into this file. `.env` values, the Aura host, and the API key stay out.
5. Log files go under the session scratchpad, not the repo. Reference them by name only.
6. **Reset whenever the graph is not in a known state.** Standing authorization: the five loan
   labels are this example's own, every one of them is rebuilt by `GraphSeeder` on the next start,
   and a run on top of unknown data proves nothing. Reset first and let the seeder rebuild, at any
   point in this plan, without asking again.

   ```cypher
   MATCH (n) WHERE n:Company OR n:Policy OR n:LoanApplication OR n:Decision OR n:Exception
   DETACH DELETE n
   ```

   This does not extend to `Session`, `Message`, or `Metadata`, which hold conversation history the
   seeder cannot rebuild, or to any label outside those eight.

### Status

| Step | What | Status | Note |
|------|------|--------|------|
| 0 | Preflight: connectivity and pre-existing label counts | PASS | Connected. The database already holds an older iteration of this same demo. See findings. |
| 1 | `./run.sh C-1042 250000` (first run, denied on numbers) | PASS | DENIED on Debt to Income Limit, seeded denial read from the graph. Two README drifts found, both cosmetic. |
| 2 | `./run.sh C-1042 250000` (second run, denied on history) | PASS | Repeat Denial Escalation decided it, and both earlier denials now show the third hop. Matches the README. |
| 3 | `./run.sh C-1123 250000` (approved, exception in force) | PASS | APPROVED at 16.7% with one counted denial, excepted denial listed and marked. Re-run after the fix on a freshly reseeded graph, identical both times. |
| 4 | Delete the one `EXCEPTION_TO` relationship | PASS | Done twice, same result: `EXCEPTION_TO` 1 to 0, `Exception` node untouched. |
| 5 | `./run.sh --no-seed C-1123 250000` (denied, exception gone) | PASS | DENIED by Repeat Denial Escalation at 2 counted denials. First attempt without the flag FAILED; option 1 fixed it. |
| 6 | `./run.sh C-1123 250000` and confirm the edge was reseeded | PASS | Relationship back as `X-1123-SEED -> D-1123-SEED-2`, marker back in the listing. Verdict stays DENIED: see finding 4. |
| 7 | Verification pass: logs against README | PASS | Six runs verified line by line. One defect fixed, three drifts fixed, one new finding open. Unit tests 25 green after the change. |
| 8 | Cleanup: reset to the documented seeded state | PASS | Five loan labels at 0 nodes. Chat memory kept: `Session` 13, `Message` 33, `Metadata` 33. |
| 9 | `--no-seed` against the emptied graph, proving seeding was skipped | PASS | Graph still at 0 nodes, no model call. Surfaced finding 5, a misleading message. |
| 10 | `./run.sh C-1042 250000` twice from clean, the README's headline sequence | PASS | Denied on numbers, then denied on history with the third hop appearing. Matches the README line for line. |
| 11 | `./run.sh C-1077 250000`, the approval path | PASS | APPROVED, score 81 and 13.8%, both matching the README's company table. |
| 12 | `./run.sh C-1096 250000`, the credit score denial | PASS | DENIED on Minimum Credit Score, 47 against 60, with 29.5% passing. |
| 13 | Error paths: unknown company, unparseable amount, no arguments | PASS | All three refuse or default correctly. Two message defects found, findings 5 and 6. |
| 14 | Final reset, leaving the seeded state for the next run to rebuild | PASS | Loan labels at 0. The next ordinary run reseeds, which is what the README's Reset section says. |

### Progress log

Newest entry last. One line per event, with what changed.

- Step 0 RUNNING: opening a read-only cypher-shell against the configured instance.
- Step 0: first attempt failed with "The client is unauthorized due to authentication failure".
  Cause was `docker --env-file`, which does not strip the quotes around the values in `.env`, so
  cypher-shell received a quoted password. Fixed by sourcing `.env` in the shell and passing
  `-e NAME` by name, which also keeps the secret out of `argv`. The plan's step 0 snippet is
  corrected below.
- Step 0 PASS: connected. Label counts before any run: `Company` 10, `Policy` 4,
  `LoanApplication` 1, `Decision` 1, `Exception` 0. Chat memory: `Session` 6, `Message` 19,
  `Metadata` 19.
- Step 0 finding: this is not unrelated work. It is an earlier version of this same example, and
  it has been run against this database roughly six times. Details under findings.
- Step 1 HELD pending the reset decision, which deletes nodes from a real instance and is not
  mine to make.
- Reset approved and executed under rule 6. The five loan labels are at 0 nodes. `Session` 6,
  `Message` 19, `Metadata` 19 deliberately kept.
- Step 1 PASS: seeder rebuilt the graph, `C-1042` denied on Debt to Income Limit at 48% against
  40%, with the seeded denial dated three months back and Repeat Denial Escalation passing at 1.
  Log `run-1.log`.
- Step 1 drift 1: the trail block **is** printed on a first run, listing `D-1042-SEED` and the
  decision just written, both with `has decided  nothing yet`. The README says this block is
  trimmed on a first run. The README is wrong, not the code.
- Step 1 drift 2: decision ids are printed with `%s  denied`, so ids of different length do not
  line up. The README's trail examples pad them into a column. Cosmetic, README only.
- Step 2 RUNNING: same command again, expecting history rather than numbers to decide.
- Step 2 PASS: nothing about the company changed and a different policy decided. Repeat Denial
  Escalation FAILed at 2 prior denials against a limit of 2, the reason names the 12 month window,
  and the trail shows `has decided  D-7889356e` on both earlier denials, which is the
  `ESCALATED_FROM` hop read backwards. The model's paragraph explains that policy and no other.
  Log `run-2.log`.
- Step 3 RUNNING: `C-1123`, whose approval depends on one relationship.
- Step 3 PASS: APPROVED. Both denials are listed, the second carries
  `(excepted, no longer counts)`, and Repeat Denial Escalation reads 1 prior denial rather than 2.
  The trail resolves the exception to its approver and justification. Every figure matches the
  README, including 16.7% and score 78. Log `run-3.log`.
- Step 4 RUNNING: deleting the single `EXCEPTION_TO` relationship, nothing else.
- Step 4 PASS: `EXCEPTION_TO` went from 1 to 0 and the `Exception` node count stayed at 1. No
  policy, threshold, company, or decision was edited.
- Step 5 RUNNING: the identical command from step 3, with one relationship missing.
- Step 5 **FAIL**: the outcome did not change. Still APPROVED, the second denial still printed
  `(excepted, no longer counts)`, and the trail still resolved the approver and justification.
  Log `run-5.log`.
- Step 5 diagnosis: `GraphSeeder` is a `CommandLineRunner` at `HIGHEST_PRECEDENCE`, so every start
  runs `MERGE (e)-[:EXCEPTION_TO]->(d)` before the advisor reads anything. A relationship deleted
  between runs is restored by the next run, in the same process, ahead of the query that was
  supposed to notice it gone. Confirmed after the run: `X-1123-SEED -> D-1123-SEED-2`, 1
  relationship.
- Step 5 consequence: the README's exception demo, delete the edge and run it again, cannot work
  through `./run.sh` as written. This is a defect in the example, not in the graph model. The
  arithmetic it claims is real and `PolicyEngineTests` covers it; what is missing is any way to
  reach that state from the command line.
- Option 1 applied: `GraphSeeder` is now `@ConditionalOnProperty(name = "loan.seed.enabled",
  matchIfMissing = true)`, `application.yaml` declares `loan.seed.enabled: true`, and `run.sh`
  takes `--no-seed`, which passes `-Dloan.seed.enabled=false` as a JVM system property rather than
  an application argument, because `Application` reads its arguments positionally and would take
  `--loan.seed.enabled=false` for a company id. Module compiles.
- README fixed for all three drifts: the abridged fences are now declared as abridged, the trail
  ids are unpadded to match `%s  denied`, and the exception section uses `--no-seed` and explains
  why an ordinary run cannot show the flip.
- Step 5 retry RUNNING with the flag. Step 3 is being re-run first, because the graph currently
  holds the approval that step 3 wrote and the relationship the failed step 5 restored.
- Reset under rule 6 before retrying, so the fix was proved against a known state rather than on
  top of the failed attempt.
- Step 3 re-run PASS: APPROVED, score 78, 16.7%, one counted denial. Identical to the first time.
- Step 4 PASS again: `EXCEPTION_TO` 1 to 0.
- Step 5 PASS with `--no-seed`: **DENIED**, Repeat Denial Escalation FAIL at 2 prior denials
  against a limit of 2. The `(excepted, no longer counts)` marker is gone from the history listing
  and the trail reads `exception    none` for `D-1123-SEED-2`. Same company, same numbers, same
  request, one relationship missing, opposite outcome. Log `run-5b.log`.
- Step 6 PASS: an ordinary run restored the relationship, `X-1123-SEED -> D-1123-SEED-2`, and the
  marker and the approver both came back in the output. Log `run-6.log`.
- Unit tests re-run after the change: `LoanGraphTests` 16, `PolicyEngineTests` 9, no failures.
- Finding 4 written into the README. Steps 9 to 13 added: the runs and error paths that six earlier
  runs never exercised.
- Step 8 PASS: five loan labels emptied, chat memory deliberately kept. Seven runs of this session
  left `Session` 13, `Message` 33, `Metadata` 33 behind, which the seeder neither writes nor needs.
- Step 9 RUNNING: `--no-seed` on the emptied graph. If the flag works there is nothing to read, so
  the run should refuse by name and never call the model.
- Step 9 PASS: the graph was still at 0 nodes after the run, so seeding really was skipped, and the
  run refused before reaching the model. No credits spent.
- Step 10 RUNNING: two ordinary runs of `C-1042` from a clean graph, the sequence the README leads
  with.
- Step 10 PASS: run one DENIED on Debt to Income Limit at 48%, run two DENIED on Repeat Denial
  Escalation at 2 of 2, with `has decided  D-52b65464` appearing on both earlier denials. Every
  figure, date and column matches the README. Logs `run-10a.log`, `run-10b.log`.
- Steps 11 to 13 RUNNING: the paths six earlier runs never touched. `C-1077` approves on numbers
  alone, `C-1096` fails on credit score, and the three argument paths are refusals that never reach
  the model.
- Step 11 PASS: `C-1077` APPROVED on numbers alone, score 81 and 13.8%, 0 prior denials. The
  README's company table gives 81, 300,000 and 4,000,000, which is 13.75% with the loan. Agrees.
- Step 12 PASS: `C-1096` DENIED on Minimum Credit Score, 47 against 60, while Debt to Income passed
  at 29.5%. This is the only run in the session where the credit rule decided anything.
- Step 13 PASS: unknown company refused by name, `not-a-number` refused with a usage line, and no
  arguments fell back to `C-1042` at $250,000, which by then had three denials on file and was
  denied by escalation at 3 of 2. The first two never reached the model.
- Step 14 PASS: loan labels back to 0 nodes, chat memory kept. The graph is now where the README's
  Reset section leaves it: empty of this example's labels, rebuilt by the next ordinary run. A talk
  demo can start from `./run.sh C-1042 250000` and get the first-run output verbatim.
- Run complete. Every step PASS. One defect found and fixed, four README drifts found and fixed,
  two message defects open as findings 5 and 6.

### Findings 5 and 6, open: two messages the flag made wrong

Neither is in the graph or the rules. Both are one line of `Application.java`, and neither is
applied, because they are outside what option 1 asked for.

**Finding 5.** `./run.sh --no-seed C-1042 250000` on an emptied graph prints
`No company with id C-1042. Try C-1042, C-1077, C-1096, or C-1123.` It suggests the id that just
failed, because the list is a constant and the real problem is that nothing is seeded. Reachable
only with `--no-seed`, which is new. Proposed: when no `Company` exists at all, say the graph is
empty and to run without `--no-seed`, and keep the current message for a genuinely unknown id.

**Finding 6.** The unparseable-amount path prints `Usage: ./run.sh [companyId] [amount]`, which no
longer describes `run.sh`. Proposed: `Usage: ./run.sh [--no-seed] [companyId] [amount]`.
- Original halt, kept for the record: halted at step 5 per rule 2.

### Finding 4, written: restoring the relationship does not restore the approval

Step 6 put the relationship back, and `C-1123` was still DENIED. That is correct behaviour, not a
bug: the `--no-seed` run wrote a real denial, so the counted total is 2 again, this time
`D-1123-SEED-1` plus the new one. The README does not claim the verdict flips back, but a reader
will infer it. The honest way back to the documented state is the reset query, which the seeder then
rebuilds. Approved and written into the exception section of the README:

> Running it again without the flag restores the relationship, though not the approval: the denial
> the `--no-seed` run wrote is real precedent now, and it counts. The reset query under Reset
> returns the graph to its seeded state. Step 6 not run: it existed to prove the seeder restores the edge,
  which step 5 proved by being defeated by it. Cleanup held so the defect stays reproducible.

### Findings to resolve

**Defect: the exception demo is unreachable from the command line.** Options, in the order I would
pick them, none applied yet:

1. Guard the seeder with a property, `loan.seed.enabled`, default true, and give `run.sh` a
   `--no-seed` flag. The README's delete then works on the next run, and its existing sentence
   about restarting restoring the relationship becomes the documented recovery path. Smallest
   change that makes the claim true.
2. Seed the relationship only when the `Exception` node is new, so a deliberate delete survives.
   Quieter to write and harder to explain: idempotent seeding is the reason the rest of the demo
   can be re-run at all.
3. Drop the delete from the README and contrast `C-1123` against a fifth company with identical
   numbers and no exception. No code change, no destructive step, but one more company to seed.

**README drifts, all cosmetic, all in the README rather than the code:**

- The first-run block says the trail is trimmed. The trail is printed on the first run, with
  `has decided  nothing yet` on both rows.
- Trail examples pad decision ids into a column. The code prints `%s  denied` with no padding, so
  `D-14ce642c  denied` sits two spaces out from `D-1123-SEED-1  denied`.
- The run fences stop at the verdict line and omit the indented paragraph the model writes, which
  is always printed.

**Verified correct against the README, character for character:** every date, amount, percentage,
score, threshold, and column position in steps 1 to 3, including `48%`, `16.7%`, `score 72`,
`score 78`, the `(excepted, no longer counts)` marker, the escalation reason naming the 12 month
window, and the `ESCALATED_FROM` hop appearing as `has decided  D-7889356e` on both earlier
denials.

---

## What this touches

`.env` points at a remote Aura instance, database `neo4j`. This is not a throwaway container.

- **Schema.** `GraphSeeder` creates five uniqueness constraints: `loan_company_id`,
  `loan_policy_key`, `loan_application_id`, `loan_decision_id`, `loan_exception_id`. Constraint
  names are global per database. `Neo4jChatMemoryRepository` creates its own for `Session` and
  `Message`.
- **Writes.** The seed MERGEs 4 `Company`, 3 `Policy`, 3 `LoanApplication`, 3 `Decision`, and 1
  `Exception`, so it is idempotent across restarts. Each run then adds one `LoanApplication`, one
  `Decision`, and a `Session` with its `Message` nodes. The app never deletes anything.
- **Credits.** One `claude-sonnet-5` call per run, roughly 1k input and 150 output tokens. Six runs
  is about two to four cents.

## Step 0: preflight, read only, no credits

Confirms the connection and snapshots what already exists under the labels this example uses, so
the later steps can tell what the demo added.

```shell
set -a && source .env && set +a
export NEO4J_ADDRESS="$NEO4J_URI"
docker run --rm -i -e NEO4J_ADDRESS -e NEO4J_USERNAME -e NEO4J_PASSWORD -e NEO4J_DATABASE \
  neo4j:5.26 cypher-shell --format plain <<'CYP'
MATCH (n) WHERE n:Company OR n:Policy OR n:LoanApplication OR n:Decision OR n:Exception
RETURN labels(n)[0] AS label, count(*) AS before ORDER BY label;
CYP
```

`-e NAME` with no value passes the sourced variable through by name, so the password never reaches
`argv` and the quotes in `.env` are stripped by the shell first. Do not use `--env-file` here: it
passes values literally, quotes included, and cypher-shell then fails authentication. The
`neo4j:5.26` image is already cached locally from Testcontainers.

### Step 0 findings

The database holds an earlier iteration of this example, on the schema this module used before the
window and the exception existed:

| Observed | Effect on this run |
|----------|--------------------|
| 10 `Company` nodes, including `C-1013` through `C-1104` | Seven are not in `seed.json`. Harmless to the run, but the README's company table no longer describes the database. |
| Every company carries `avgDaysToPay` | Unread by `FIND_COMPANY`. Harmless. `SET` does not remove it, so it survives reseeding. |
| A fourth `Policy`, `paymentSpeed`, threshold 45 | `loadPolicies` does `MATCH (p:Policy)` and loads it. It has `name`, `description`, and `threshold`, and `windowMonths` reads through `asLong(0)`, so it maps without throwing and the engine ignores the unknown key. Harmless, but it will appear in any policy query pasted from the README. |
| All four policies have `windowMonths` NULL | Rewritten from `seed.json` on the next start. Self-healing. |
| `D-1042-SEED` dated `2026-05-14T09:15Z`, a fixed calendar time | Rewritten to run time minus three months by `GraphSeeder`. Self-healing, and the reason the relative dating was added. |
| `A-1042-SEED` at 400,000, `submittedAt` fixed | Same. Self-healing. |
| `loan_exception_id` constraint absent, 0 `Exception` nodes | Created and seeded on the next start. |
| `Session` 6, `Message` 19, `Metadata` 19 | Older transcripts. The run prints only its own conversation, so they do not interfere. |

Nothing here breaks the run. What it costs is fidelity: the output will be correct while the
database contains seven companies and a policy that the README does not mention.

### Decision required before step 1

Deleting nodes from a real instance is not a decision to make unilaterally.

- **Recommended: reset the five loan labels, then let the seeder rebuild.** Gives output that
  matches the README exactly. Removes the seven stale companies, `paymentSpeed`, and the
  pre-window `D-1042-SEED`.

  ```cypher
  MATCH (n) WHERE n:Company OR n:Policy OR n:LoanApplication OR n:Decision OR n:Exception
  DETACH DELETE n
  ```

- **Alternative: run on top of what is there.** Nothing fails, and step 7 has to allow for the
  extra companies and the fourth policy when comparing against the README.

Chat memory is a separate call either way: `MATCH (n) WHERE n:Session OR n:Message OR n:Metadata
DETACH DELETE n`. Leaving it is fine.

**Stop condition.** If this returns counts for labels that hold unrelated work, stop. These label
names are generic enough to collide with something else in the same database.

## Steps 1 to 6: the runs

Each run's stdout is captured to `run-N.log` in the scratchpad, so step 7 can diff against the
README instead of relying on eyeballing.

| # | Command | Expected |
|---|---------|----------|
| 1 | `./run.sh C-1042 250000` | DENIED, Debt to Income Limit. One seeded denial listed. |
| 2 | `./run.sh C-1042 250000` | DENIED, Repeat Denial Escalation. Trail shows `has decided  D-...`. |
| 3 | `./run.sh C-1123 250000` | APPROVED. Second denial marked `(excepted, no longer counts)`. |
| 4 | `MATCH (:Exception {exceptionId: 'X-1123-SEED'})-[r:EXCEPTION_TO]->(:Decision) DELETE r` | One relationship gone, nothing else changed. |
| 5 | `./run.sh C-1123 250000` | DENIED, Repeat Denial Escalation. |
| 6 | `./run.sh C-1042 1`, then read the graph | `GraphSeeder` MERGEd `X-1123-SEED`'s edge back. |

Step 6 uses a one dollar request because it is the cheapest way to trigger a reseed, and it adds no
precedent that confuses C-1123.

### Two things expected to need reconciling

- **Step 6 will not restore APPROVED for C-1123**, even though the relationship returns. Step 5
  wrote a third denial, so two are counted again. The README says only that restarting "restores
  it, because `GraphSeeder` MERGEs the relationship back", which is true of the edge, but a reader
  will infer the verdict flips back. If step 6 confirms this, propose a fix and wait for approval
  before editing.
- **Step 1's trail block.** The code prints the trail whenever `findPrecedentTrail` returns
  anything, and C-1042 has a seeded denial, so a trail with `has decided  nothing yet` is expected
  on the first run. The README says the first-run block is trimmed. Either the README needs the
  honest version or the print needs a guard. Report which; do not choose.

## Step 7: verification pass

Against the captured logs: every amount, percentage, and column position compared to the README
fences character by character, the `(excepted, no longer counts)` marker, all three trail hops, and
that each verdict paragraph explains the checklist it was handed rather than inventing a different
outcome, policy, or figure. Drift is reported as a list, not silently fixed.

## Step 8: cleanup

Recommendation is to leave the data in place: it is MERGE idempotent, so re-running never
duplicates, and the constraints are `loan_` prefixed. To clear it instead, the README reset query
plus `MATCH (n) WHERE n:Session OR n:Message OR n:Metadata DETACH DELETE n` removes both halves.

## Out of scope

The integration test is not part of this pass. It needs `git sparse-checkout add integration-testing`
and `jbang`, neither of which is set up here.
