# PR Review Team & Merge Process — Sprinter OBD Gauge App

This doc defines the dedicated review team: who reviews, how PRs are routed, exactly how a PR gets sent back to its author with fix instructions, and how approved work reaches `main`. It supersedes §5 of the Teamwork doc.

Core principle: **authors never review or merge their own work, reviewers never write feature code, and nothing reaches `main` without passing through this process.** Review agents are standing Claude Code sessions whose only job is this pipeline.

---

## 1. The review team

Three specialist reviewer agents plus one merge agent. Specialization beats a generalist pool here because each PR gets checked through three genuinely different lenses instead of one shallow pass.

| Agent | Owns | Looks for |
|-------|------|-----------|
| **rev-correctness** | Does the code do what the issue says? | Every acceptance criterion mapped to a passing test; test quality (asserts behavior, not implementation); fixture coverage for new parser/protocol behavior; edge cases the AC implies but the tests skip |
| **rev-platform** | Is it correct *Android/Kotlin*? | Modern API usage (no deprecated BT/permission APIs, targetSdk 36 behaviors); coroutine hygiene (no `GlobalScope`, no blocking in suspend, cancellation-safe, structured concurrency); BLE realities (callbacks off main thread, GATT single-op discipline); Compose correctness (stability, recomposition, no state in composables); lifecycle + foreground-service rules |
| **rev-arch** | Does it fit the system? | Module isolation (diff stays inside owned module); Phase-0 contracts untouched (or properly labeled and pre-approved); dependency direction legal per the module graph; public surface documented; `MODULE.md` current; naming/shape consistent with existing code |
| **merge-agent** | The only identity that merges | Final gate mechanics (§6); merge queue ordering; post-merge verification; reverts |

Which reviewers a PR needs:

- Every PR: **rev-correctness** + **rev-arch** (2 approvals minimum).
- PRs touching `:app`, `:core:ble`, or any Android-flavored code: **rev-platform** additionally (3 approvals).
- `type:contract-change` or `DECISIONS.md`: all three reviewers **plus human (Taras)**.
- `hardware-verify` issues: review proceeds normally, but the merge-agent will not merge until the human checklist comment exists on the linked issue.

No self-review: a reviewer agent that authored any commit in the PR (shouldn't happen — reviewers don't write feature code — but defense in depth) must recuse and flag the orchestrator.

---

## 2. Routing and queue discipline

PRs enter the review queue when marked ready (not draft) and CI is green. Red CI = not reviewable; reviewers skip it and comment once: `CI red — review deferred until checks pass.`

```bash
# Each reviewer's work loop (run on a cadence, e.g. every 30 min or event-driven)
gh pr list --state open --search "-is:draft review:required" \
  --json number,title,labels,createdAt --jq 'sort_by(.createdAt)'
```

Routing rules:
- Oldest ready PR first (FIFO). No cherry-picking small PRs to farm approvals.
- A reviewer claims a PR by commenting `🔎 rev-<name> reviewing` so two reviewers don't duplicate a pass. Claims expire after 2 hours without a submitted review.
- Target SLA: first review verdict within one working session of the PR going ready. The orchestrator's daily sweep flags anything older:

```bash
gh pr list --state open --search "-is:draft review:required created:<$(date -d '-1 day' +%Y-%m-%d)"
```

Every reviewer runs the code, not just reads it:

```bash
gh pr checkout <n>
./gradlew :<module>:test    # trust nothing
gh pr diff <n>
```

---

## 3. The verdicts

A reviewer submits exactly one of:

- **Approve** — all items in that reviewer's lens pass. Approval comment must state *what was verified*, not just "LGTM":
  ```bash
  gh pr review 41 --approve -b "rev-correctness: AC1→InitStateMachineTest.happyPath, AC2→...timeoutRetries, AC3→...typedFailures. Fixture added for STOPPED frames. Verified locally green."
  ```
- **Request changes** — one or more Blocker/Major findings (see §4). This is the send-it-back path.
- **Comment only** — nits and suggestions with zero blockers. Does not gate the PR; author may address or decline with a reply. Reviewers must not use request-changes for nit-only findings — that's how review ping-pong starts.

---

## 4. Sending a PR back: the change-request protocol

This is the heart of the process. Vague feedback wastes an agent round-trip; the request-changes format makes the fix instructions executable.

### 4.1 Finding format

Every finding in a request-changes review uses this structure, posted as inline comments on the exact lines where possible, with a summary table in the review body:

```markdown
### [BLOCKER|MAJOR|NIT] <short title>
**Where:** `core/protocol/src/main/kotlin/.../ResponseParser.kt:87-94`
**What's wrong:** Parser assumes single-frame responses; a fragmented `41 05` split across
two notifications produces NumberFormatException (see fixture `transcripts/fragmented_0105.txt`).
**Why it matters:** Violates OBD-14 AC3 ("parser never throws on malformed input") and will
crash on the real Veepeak, which fragments at 20 bytes pre-MTU.
**Required fix:** Accumulate until the `>` terminator before parsing; add
`fragmented_0105.txt` to the parser property-test corpus.
**Verify by:** New test in ResponseParserTest exercising the fixture; existing suite stays green.
```

Severity semantics:
- **BLOCKER** — wrong behavior, failed AC, contract violation, module-boundary breach, crash path. Must be fixed.
- **MAJOR** — works but creates real debt: missing test for claimed behavior, swallowed exception, API misuse that will bite later. Must be fixed or explicitly waived by the orchestrator in a comment.
- **NIT** — style, naming, minor simplification. Author's discretion; never blocks.

Review body ends with a machine-scannable summary the author can work through as a checklist:

```markdown
## Fix list (re-request review when all BLOCKER/MAJOR are ✅)
- [ ] B1: Fragmented-response crash — ResponseParser.kt:87
- [ ] B2: AC3 has no covering test
- [ ] M1: Timeout swallows CancellationException — ObdSession.kt:120
- [ ] N1 (optional): rename `doParse` → `parseFrame`
```

```bash
gh pr review 41 --request-changes --body-file review-41.md
gh pr edit 41 --add-label "changes-requested"
```

### 4.2 What the authoring agent does

The PR goes back to the agent that wrote it — same agent identity, same branch. The author:

1. Reads the full review: `gh pr view 41 --comments`
2. Fixes every BLOCKER and MAJOR **on the same branch** (new commits; no force-push, so the reviewer can diff just the delta).
3. Replies to each finding individually — resolution or rebuttal, never silence:
   ```markdown
   B1 ✅ Fixed in a3f9c21 — parser now buffers to `>`; fixture added to corpus.
   M1 ✅ Fixed in a3f9c21 — rethrow CancellationException before generic catch.
   N1 — declined: `doParse` matches the naming of `doInit` in the same file; happy to rename both in a follow-up issue.
   ```
   A rebuttal on a BLOCKER/MAJOR must argue the finding is *incorrect*, not inconvenient. If author and reviewer disagree after one rebuttal round, escalate (§5).
4. Re-requests review from the same reviewer:
   ```bash
   gh pr edit 41 --remove-label "changes-requested"
   gh api repos/{owner}/{repo}/pulls/41/requested_reviewers -f 'reviewers[]=rev-correctness'
   ```

### 4.3 Re-review scope

The reviewer re-checks: (a) each finding against its fix commit, (b) a quick regression scan of the new commits only, (c) CI still green. A re-review is not a fresh full review — new unrelated findings on unchanged code are only allowed if they're BLOCKERs missed the first time, and the reviewer must say so explicitly. This keeps rounds converging instead of drifting.

### 4.4 Round limits

- **Two** request-changes rounds from the same reviewer on the same PR → automatic escalation to the orchestrator, who either arbitrates the disagreement, splits the issue, or pairs the author agent with more context. Endless review loops are a process failure, not an author failure.
- A PR idle for 3 days in `changes-requested` gets an orchestrator ping; 7 days → closed, issue reopened, work reassessed at the next sprint boundary.

---

## 5. Escalation & disagreement

| Situation | Resolver | Mechanism |
|-----------|----------|-----------|
| Author rebuts a BLOCKER/MAJOR; reviewer holds | orchestrator | Comment thread on the PR tagging both positions; orchestrator verdict is final and logged |
| Two reviewers give conflicting required fixes | orchestrator | Same; if the conflict reveals a contract ambiguity, it becomes a `type:contract-change` issue first |
| Finding implies a Phase-0 contract is wrong | orchestrator + human | PR parked (`blocked`); contract issue opened; PR resumes after the contract decision merges |
| Reviewer suspects cross-module breach CI missed | rev-arch verdict is authoritative | Fix the `module-isolation` CI check in a follow-up PR so it catches it next time |

Escalations are comments and issues, never side-channel. The orchestrator's arbitration comment must state the rule it applied so the same dispute doesn't recur.

---

## 6. Merging to main

Only the **merge-agent** merges. Approving reviewers do not merge — separating "is it good" from "does it land now" prevents race conditions between parallel PRs.

Merge-agent loop:

```bash
# Candidates: fully approved, CI green, no changes-requested, not blocked
gh pr list --state open --search "review:approved -label:changes-requested -label:blocked" \
  --json number,title,labels,reviews
```

Pre-merge gate (all must hold):
1. Required approvals present per §1 matrix (2 or 3, + human where required).
2. CI green **on the current head** — approvals predating the last commit are stale; merge-agent re-requests review if code changed after approval.
3. Linked issue's acceptance boxes are checked by the author.
4. `hardware-verify` label → human checklist comment exists on the issue.
5. Branch is current with `main`; if behind, merge-agent comments and the author rebases (mechanical rebases with no conflicts may be performed by the merge-agent directly and noted).

Merge mechanics:
- **Squash merge only**; final commit message = PR title (Conventional Commit form) + `Closes #<issue>`.
- **One at a time, oldest first**, and merge-agent waits for `main`'s post-merge CI before merging the next — a manual merge queue. Two PRs can each be green against an old `main` and still break together; serializing catches semantic conflicts immediately.
  ```bash
  gh pr merge 41 --squash --delete-branch
  gh run watch $(gh run list --branch main --limit 1 --json databaseId --jq '.[0].databaseId')
  ```
- If `main` goes red after a merge: **revert first, diagnose second.**
  ```bash
  gh pr create --title "revert: <original title> (#41)" --body "Auto-revert: main red after merge. See run <link>. Reopens #<issue>."
  ```
  The revert PR needs one reviewer approval (any), merges ahead of the queue, and the original issue reopens with the failure attached.

Post-merge: merge-agent comments on the issue with the merge SHA, confirms auto-close, and updates the sprint milestone burndown in the daily sweep thread.

---

## 7. Anti-patterns (enforced by the orchestrator's retro sweep)

- **Rubber-stamping:** approvals without verified-item lists are invalid; merge-agent treats them as missing.
- **Nit-blocking:** request-changes with zero BLOCKER/MAJOR findings gets converted to comment-only by the orchestrator and noted in retro.
- **Scope creep in review:** "while you're in there, also add X" is a new issue, not a finding. Reviewers file it themselves: `gh issue create --title "Follow-up from #41: X"`.
- **Fixing in review:** reviewers never push commits to an author's branch. Instructions in, code out — the authoring agent owns its module's code, full stop.
- **Stale-approval merges:** any commit after an approval invalidates it. No exceptions, including "just fixed a typo."

---

## 8. Metrics the orchestrator tracks (informational, per retro)

- Rounds-to-merge per PR (target: ≤2; rising average = findings arriving too late or ACs too vague — fix the issue templates, not the agents)
- Time in `changes-requested` (author responsiveness)
- Reverts per sprint (target: 0; each one gets a one-paragraph post-mortem in the retro issue)
- Findings by severity per reviewer (a reviewer producing only NITs isn't reviewing; only BLOCKERs may be reviewing too late)
