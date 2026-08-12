# Local-First Agent Workflow — Sprinter OBD Gauge App

Replaces the GitHub-hosted mechanics of the Teamwork and PR-Review docs with a fully local, git-native equivalent. The discipline is identical; the substrate is the repo itself. GitHub enters later, once, as a publish target — not as a dependency.

**Authorization scope:** merges to local `main` are pre-authorized for THIS project only (granted 2026-08-09). This does not extend to any other repo, and does not cover `git push` to any remote — pushing remains per-action OK.

---

## 1. What changed and why

| GitHub concept | Local replacement | Why it's not a downgrade |
|---|---|---|
| Issues | `issues/OBD-N.md` files, versioned in the repo | Agents already read files, not chat. Issues travel with the repo and survive in history |
| PRs | Branches + committed review records | The review artifact becomes permanent history instead of a database row GitHub owns |
| PR review threads | `reviews/OBD-N-round<K>.md` in the finding format from the PR-Review doc | Same format, more durable |
| Actions CI | `tools/gate.sh` — must pass before any merge | Same checks (build, test, lint, module-isolation), runs on this Mac |
| Branch protection | Merge procedure (§6) enforced by the merge-agent role | Single-identity GitHub protection was ceremony anyway; this is honest about it |
| Labels/milestones | Frontmatter in issue files + `sprint/` index files | Grep-able, diff-able |
| Bot identities | Named agent roles in one session; role recorded in every commit and review file | Identity was never real with one account |

Everything in the Build Plan (phases, contracts, module ownership) and the Scrum backlog (stories, ACs, sprints) is unchanged. Only the transport layer for process is swapped.

---

## 2. Repo layout (process surface only)

```
sprinter-obd-gauge/
├── issues/
│   ├── OBD-1.md … OBD-34.md        # one file per story, template below
│   └── _sprint-N.md                # sprint index: goal, story list, status
├── reviews/
│   └── OBD-12-round1.md            # one file per review round
├── research/                       # Phase-1 panel output (unchanged)
├── tools/
│   ├── gate.sh                     # the merge gate — build/test/lint/isolation
│   ├── module-isolation.sh         # changed-paths vs OWNERSHIP check
│   └── merge.sh                    # scripted merge procedure (§6)
├── OWNERSHIP                       # CODEOWNERS replacement, machine-read by module-isolation.sh
├── DECISIONS.md
└── (Gradle modules per the Build Plan)
```

`OWNERSHIP` format (path prefix → role):

```
/app/                ui-agent
/core/model/         orchestrator
/core/protocol/      protocol-agent
/core/ble/           ble-agent
/core/testing/       orchestrator protocol-agent
DECISIONS.md         orchestrator
issues/              orchestrator
reviews/             reviewer merge-agent
```

---

## 3. Issue files

`issues/OBD-N.md`, frontmatter carries what labels/milestones did:

```markdown
---
id: OBD-13
title: ELM327 init state machine
module: core/protocol
owner: protocol-agent
sprint: 2
status: open          # open | in-progress | in-review | changes-requested | merged | blocked
type: feature         # feature | contract-change | fixture | process
hardware-verify: false
blocked-by: []
branch: protocol/13-elm327-init
---

## Feature
<one sentence: what exists after this is merged>

## Contract surface
None expected. Anything else → type: contract-change + orchestrator sign-off BEFORE work starts.

## Acceptance criteria
- [ ] AC1 …
- [ ] AC2 …

## Self-test plan
Which fake, which fixtures.

## Out of scope
…
```

Rules:
- Status transitions are commits. `status: merged` is set by the merge-agent in the merge commit itself, so issue state and code state can never disagree.
- The orchestrator seeds a sprint by committing its issue files + `_sprint-N.md` in one commit on `main` (process files on main don't need the full gate — `tools/gate.sh --process` runs a fast subset: no build, just isolation + markdown sanity).
- Querying the backlog = grep: `grep -l "sprint: 2" issues/ | xargs grep -l "status: open"`.

---

## 4. Branches and commits (unchanged from Teamwork doc §3)

- Branch: `<role>/<issue>-<slug>` → `protocol/13-elm327-init`. Created from current `main`.
- Conventional Commits, issue-linked: `feat(protocol): init state machine (OBD-13)`.
- One issue per branch, one branch per issue. Work grows → split the issue first.
- Author rebases on `main` before requesting review. Conflicts are a workflow smell — flag them in the review request.
- **Every commit message ends with a role trailer:** `Role: protocol-agent`. This is the identity system now — it makes "who wrote this" grep-able and makes the no-self-review rule checkable (§5).

---

## 5. Review

Review happens on the branch, before it ever touches `main`. No PR object — the review *request* is the issue hitting `status: in-review` with its `branch:` field set; the review *record* is a committed file.

### 5.1 Reviewer lenses (from PR-Review doc §1, unchanged)

- **rev-correctness** — every AC maps to a passing test; test quality; fixture coverage. Every branch.
- **rev-arch** — module isolation, contracts untouched, dependency direction, `MODULE.md` current. Every branch.
- **rev-platform** — Android/Kotlin correctness (coroutine hygiene, BLE realities, Compose, lifecycle). Branches touching `:app`, `:core:ble`, or Android-flavored code.
- Reviewers check out the branch and **run the code**: `./gradlew :<module>:test`. Trust nothing.

### 5.2 The review record

`reviews/OBD-13-round1.md`, committed **on the feature branch** by the reviewer role (so review history squashes into main with the work):

```markdown
---
issue: OBD-13
round: 1
reviewers: [rev-correctness, rev-arch]
verdict: changes-requested    # approved | changes-requested
gate: green                    # gate.sh result on the reviewed commit
reviewed-commit: a3f9c21
---

### [BLOCKER] Fragmented-response crash
**Where:** core/protocol/src/…/ResponseParser.kt:87-94
**What's wrong:** …
**Why it matters:** violates AC3 …
**Required fix:** …
**Verify by:** …

## Fix list
- [ ] B1: … 
- [ ] M1: …
- [ ] N1 (optional): …
```

Severity semantics (BLOCKER / MAJOR / NIT), rebuttal rules, re-review scope (fix commits + regression scan only), and the two-round escalation limit all carry over verbatim from the PR-Review doc §4.

### 5.3 Author response

Fix commits on the same branch (no force-push — reviewer diffs the delta), plus responses appended to the review file: `B1 ✅ fixed in <sha>`. Round 2 review appends `verdict: approved` with the verified-item list. Approvals without a verified-item list are invalid — the merge step rejects them.

### 5.4 No self-review

The reviewer roles never author feature commits; the `Role:` trailer makes this auditable: a review file is invalid if its reviewer's role appears as author of any non-review commit on the branch. In practice (one Claude session playing all roles) this means: **feature authoring and review of the same branch never happen in the same agent context** — the reviewer is a fresh subagent that gets only the issue file, the diff, and the checklist. Contamination is the failure mode; context isolation is the countermeasure.

### 5.5 Risk-tiered review (D4, Taras-approved 2026-08-09 — supersedes the flat §5.1 matrix)

Review depth follows consequence-of-being-wrong, not habit. Sprint-1 calibration: the full
two-Opus treatment found 1 blocker + 9 majors on a "green" branch (worth it), but a single
combined-lens Opus round found the same class of issues at ~60% of the cost.

| Tier | Scope | Review |
|---|---|---|
| **A** | `:core:protocol`, `:core:ble`, anything touching `:core:model` contracts, DI/prod wiring (OBD-25) | Full matrix: rev-correctness + rev-platform (Opus, separate contexts) + rev-arch (Haiku). Mutation spot-checks required in round 2 |
| **B** | `:app` feature work in established patterns | ONE combined-lens Opus reviewer + rev-arch (Haiku). Mutation spot-checks on new test suites |
| **C** | Sprint-4 telemetry, polish, docs/process | One Sonnet reviewer + scripts; escalate to Tier B on structural smell |

Rules:
- **Ratchet, not faith:** a module drops one tier after two consecutive reviews with ≤1 major;
  any BLOCKER anywhere bumps its module up one tier for the rest of the sprint.
- **A wrong gauge is Tier A forever:** anything that computes or scales a displayed value
  never drops below Tier A. A plausible-but-wrong trans temp is the project's worst failure mode.
- **Batching (amends §4):** small, cohesive, same-module issues MAY share one branch and one
  review (e.g. OBD-11+12, OBD-20+21). The review record lists all issue ids; merge.sh runs
  per lead issue with `Closes` lines for each. One issue per branch remains the default for
  anything Tier A or > 3 pts.
- Everything else stands: fresh reviewer contexts (§5.4), delta-only re-reviews, two-round cap,
  Haiku rev-arch everywhere (near-free), verified-item lists required for approval.

---

## 6. The merge gate and merge procedure

Only the **merge-agent** role merges. `tools/merge.sh OBD-13` does, in order, and aborts on any failure:

1. Issue `status: in-review`→ latest review file has `verdict: approved` **with** a verified-item list, from the required reviewer set (§5.1 matrix), and `reviewed-commit` == branch head (stale approvals invalid — any commit after approval, including "just a typo," voids it).
2. `hardware-verify: true` → a `## Hardware checklist` section with observed values exists in the issue file, added by Taras. **No automation path around this.**
3. `type: contract-change` → `DECISIONS.md` entry merged first + Taras sign-off noted in the issue.
4. Rebase branch on `main`; if conflicts, back to the author (mechanical, conflict-free rebases the merge-agent may do itself, noted in the merge commit).
5. `tools/gate.sh` on the rebased branch: `assembleDebug assembleDemo`, `test`, ktlint + detekt, `module-isolation.sh` (changed paths vs `OWNERSHIP`, diffed against merge-base). Red gate = no merge, no exceptions. If the gate is wrong, fix the gate on its own branch.
6. **Squash merge:** `git merge --squash` → single commit: issue title in Conventional Commit form + `Closes OBD-13` + `Role: merge-agent`. Same commit flips the issue to `status: merged`.
7. **Post-merge verify:** `tools/gate.sh` again on `main`. Red main → `git revert` immediately, issue back to `open` with the failure pasted in. Revert first, diagnose second.
8. Delete the branch. One merge at a time, oldest approved first — serialization catches semantic conflicts between parallel branches, same as the manual merge queue in the PR-Review doc.

`main` is never committed to directly except: Sprint-0 scaffold bootstrap, issue/sprint process commits (§3), and reverts. Everything else arrives via §6.

---

## 6a. STATUS.md — the living checklist

`STATUS.md` (repo root: `~/projects/sprinter-obd-gauge/STATUS.md`) is the orchestrator's single source of truth: wave board, per-issue checklists, hardware gates (🖐 = Taras), open decisions, budget ledger.

- **Wave start:** reconcile STATUS.md against ground truth (`grep status: issues/`, `git branch -a`, `git log main`) BEFORE spawning anything. Mismatch = process bug; fix it first.
- **Wave end:** check off completed items, fill the ledger row, update the wave board, commit as a status commit on main.
- **Agent briefs** quote the relevant STATUS slice, so agents see what's done and don't rebuild it.
- 🖐 items are never auto-closed.

## 6b. Ad-hoc feature channel (D5)

(OBD-45, proposed by Taras 2026-08-11.) Not every piece of work fits the sprint board —
sometimes Taras wants something built ad hoc, outside the current wave. D5 gives that a home
without touching the sprint-track process on `main`.

**Nothing about issues or review changes.** An ad-hoc feature request gets an `issues/OBD-N.md`
file with the same frontmatter, the same risk tier (§5.5), the same reviewer lenses, the same
`reviews/OBD-N-round<K>.md` records as anything on the sprint board. The only field that
differs in practice is `branch:` (still `<role>/<issue>-<slug>`) and where `tools/merge.sh`
squash-merges it to.

**Two channels, two branches:**

| Channel | Branch | Merge target | Produces |
|---|---|---|---|
| **dev** | ad-hoc feature branches | `develop` | dev APK — `applicationIdSuffix ".dev"`, "OBD Gauge Dev" label, installs alongside the master build |
| **main** | sprint-track feature branches (unchanged) | `main` | master APK — the build on Taras's phone |

- **Every `develop` merge produces the dev APK.** Run `tools/merge.sh <OBD-N>` with
  `MERGE_TARGET_BRANCH=develop` (default is `main` — sprint-track merges are untouched); the
  script rebases/gates/squash-merges onto `develop` instead of `main` and prints the mandatory
  next step (below).
- **Promotion `develop` → `main` happens only on Taras's explicit acceptance.** There is no
  automatic promotion path — it's a human call, not a merge-count or time threshold. When
  Taras accepts a dev-channel build, the promotion itself is a normal `tools/merge.sh` run
  targeting `main` (or a fast-forward if `develop` is already clean ahead of `main`), which
  produces the master APK the same way any other `main` merge does.
- **The Gradle switch is a property, not a flavor:** `-Pchannel=dev` (see
  `app/build.gradle.kts`) — deliberately not a new `flavorDimension`, so the existing
  demo/prod × debug/release matrix is untouched and absent the property, output is unaffected.
  `tools/channel-build.sh <dev|main>` assembles demo-debug for the given channel, refuses a
  dirty tree, asserts the current branch matches the channel (`dev` ↔ `develop`, `main` ↔
  `main`) unless `--any-branch`, and copies the APK + a metadata sidecar (commit, branch,
  timestamp, versionName, applicationId) to `builds/<channel>/` (gitignored — local sideload
  artifacts, not repo content).

**Why `tools/merge.sh` prints the channel-build instruction instead of running it:**
`merge.sh` runs under `set -euo pipefail` with a single contract — "abort loudly, no partial
merges, no exceptions" — and its two gate runs (steps 5 and 7) already prove the build is
sound before anything lands. Folding a *third*, channel-flavored Gradle invocation into that
same atomic script raises the failure surface of a script whose entire design point is a
narrow, well-understood failure surface: a `channel-build.sh` failure for reasons that have
nothing to do with merge correctness (stale `local.properties`, `ANDROID_HOME` unset in a
fresh shell, a full disk, a `find`-for-`aapt2` miss) would abort a script that has *already
squash-merged and verified* — muddying "did the merge fail" with "did the convenience artifact
fail." It also roughly triples the wall-clock/token cost of every merge for something that
gates nothing (the APK is a sideload convenience, not a merge precondition). Printing a
mandatory, un-skippable instruction — enforced by the orchestrator actually running it, the
same way §6a's STATUS reconciliation is enforced by process discipline rather than a script —
keeps `merge.sh`'s blast radius exactly at "merge correctness" while still making "every
`develop`/`main` merge produces a fresh APK" a real, checked-off step in the transcript, not
best-effort. This is the "documented post-merge instruction" option from OBD-45's two
alternatives, not "`merge.sh` invokes `channel-build.sh` automatically."

**`develop` branch:** already created from `main` (OBD-45). No further scaffolding — it's an
ordinary long-lived branch, gated by the same `tools/gate.sh` as `main`.

## 6c. Small-track ad-hoc (D6)

(Taras, 2026-08-11, after the OBD-44/45 wave ran ~625k on a "tiny" request.) The full
builder-agent + reviewer-agent shape has a ~450k floor for anything touching `:app` —
fixed overhead (doc reads, repeated gradle runs, gate, screenshots) dominates regardless of
feature size. For genuinely small asks that's the wrong tool. The small track:

- **Builder: still a spawned agent in a worktree** (isolation is non-negotiable), but the
  orchestrator's spawn brief **prescribes the entire workflow and git process** — branch
  name and base, merge target, commit granularity + `Role:` trailer, which gradle tasks to
  run, gate requirement, no-merge rule. The brief IS the workflow contract; the agent
  follows it, not its own process judgment.
- **Review: the orchestrator, not a spawned reviewer.** Diff read hunk-by-hunk plus
  targeted verification (run the tests, mutation-check anything that smells unpinned).
  Review record written to `reviews/OBD-N-round1.md` on the branch as usual — the merge.sh
  contract is unchanged.
- **Qualifies:** single-module `:app` or tooling work with no contract surface, no
  protocol/BLE changes, and nothing that computes or transforms a displayed value. Tier A
  work NEVER rides the small track — §5.5 tiering outranks this section. The orchestrator
  makes the call and records `track: small` in the issue frontmatter.
- **Escalation valve:** if the orchestrator's review finds a BLOCKER, or the diff turns out
  to touch qualifying-exclusion surface, the round escalates to a spawned Tier-B reviewer —
  the small track is a cost optimization, not a quality waiver.
- **Merge target:** `develop`, like all ad-hoc work (§6b); dev APK on every merge.
- **Cost expectation:** ~150-250k per small feature, vs ~450-650k on the full track.

### 6c.1 Micro track (D7, Taras 2026-08-12): minimal-context execution

D6's first runs (OBD-46 ~200k, OBD-47 ~300k) showed the cost is NOT the code — it's
context: builders reading whole files, running the full gate, and iterating with a growing
conversation. When the orchestrator has already done the design thinking, the builder is
executing a spec, not solving a problem. Three sizes now, picked by the orchestrator and
recorded as `track:` in frontmatter:

- **`track: micro`** — fully-specified changes with no new logic paths: constants, copy,
  colors, durations, parametric tweaks, mechanical renames. The ORCHESTRATOR edits
  directly on a branch — no agent at all. Targeted test class run once; no new tests for
  feel-only changes. Review record states `micro — orchestrator-authored` with the diff
  summary; §5.4's no-self-review rule is EXPLICITLY waived at this size only, because the
  safety net is structural: merge.sh still runs the full gate twice, the existing suite
  still pins behavior, and Taras's on-device check is the real acceptance. Hard bounds:
  never touches protocol/BLE/contracts, never anything computing a displayed value, never
  a new code path. If the edit grows a second idea, stop and re-tier. Target: ~20-40k.
- **`track: small`** — needs an agent (real code, but shaped by an orchestrator
  diagnosis). The brief now MUST be surgical: exact files + line regions to read (the
  agent reads ONLY those — no whole-file sweeps, no doc reads), the diagnosis, the
  intended mechanism, and targeted test tasks ONLY — the builder never runs full gate.sh
  (merge.sh runs it twice; a gate failure at merge bounces back, which is cheaper than
  every builder paying the gate every time). Model: Haiku/low-effort when the mechanism is
  fully specified; Sonnet when the agent must make layout/API judgment calls. Orchestrator
  review as in §6c. Target: ~60-120k.
- **Full track** (§5.5) — anything architectural. OBD-47 is the calibration example: "make
  the pop match the shrink" sounded small but required cross-remount state design — that's
  a design problem wearing a small hat, and pretending otherwise just moves the cost into
  fix rounds.

The orchestrator states the chosen track and its expected cost when filing the issue, and
the ledger records actual vs expected — mis-tiering is a process bug to learn from, not
hide.

## 7. Agent execution model (how this actually runs)

- One orchestrating Claude session. Feature roles are **named subagents in isolated git worktrees** — parallel branches never collide in the working tree. Reviewer roles are fresh subagents per round (context isolation, §5.4).
- A "sprint" is a session run, not two weeks. Standup = the orchestrator's sweep between waves: open branches, `status: blocked`, gate health.
- Session ends mid-sprint → nothing is lost: issues, reviews, branch state are all in the repo. The next session cold-starts from `git branch -a` + `grep status: issues/`. Handoffs are artifacts, not chat history — same principle as before, now load-bearing.
- Retro: `issues/_retro-sprint-N.md`, orchestrator turns concrete friction into Sprint-N+1 issues.

## 8. Human gates (unchanged, cannot be automated away)

1. `hardware-verify` issues: Taras in the van, checklist values pasted into the issue file (§6.2). Gates the Sprint-2 demo, most of Sprint 3, Phase 4 bring-up.
2. `type: contract-change` and `DECISIONS.md`: Taras sign-off.
3. **Any `git push` to any remote: per-action OK.** Local main merges are pre-authorized for this project; publishing is not.

## 9. GitHub later (the publish path)

When the code is worth publishing:

1. `gh repo create sprinter-obd-gauge --private` + `git push -u origin main`. Full history arrives — including every issue and review file, which is the point: the process record publishes itself.
2. Turn on Actions with a `ci.yml` that just calls `tools/gate.sh` — the gate was written to be the CI, so there's no translation step.
3. From that point forward, new work MAY switch to real PRs/issues if there's ever a second contributor or bot identities. Until then, the local loop keeps running unchanged with GitHub as a mirror.
4. No backfill: past merges do not become retroactive PRs. The `reviews/` directory is the record; it's better than a PR anyway.

---

## 10. Model assignments & token guards

Every subagent runs on an explicit model tier — the cheapest model competent for the role. Relative cost per token: Haiku ≈ 1/10 Fable, Sonnet ≈ 1/3, Opus ≈ 1/2.

### 10.1 Model matrix

| Role | Model | Why |
|---|---|---|
| Orchestrator (main session) | Fable | Sequencing, arbitration, escalations ONLY. Never writes feature code, never reviews — its tokens are the most expensive in the system |
| Sprint-0 scaffold | Sonnet | Mechanical Gradle/CI/template setup; well-trodden territory |
| ui-agent | Sonnet | Compose/Material 3 is exactly where Sonnet is near-Opus on coding |
| protocol-agent | Opus | Parser correctness, init state machine, mode-22 framing — the module where a subtle bug surfaces as a wrong gauge in the van. Highest correctness-per-dollar payoff |
| ble-agent | Opus | GATT edge cases, reconnect state machine, notification reassembly — the hardest module by the Build Plan's own admission |
| telemetry-agent (Sprint 4) | Sonnet | Room/charts/export, conventional Android work |
| Research panel R1–R3 | Sonnet | Reading repos and summarizing; the *decision* (OBD-9) is synthesized by the orchestrator |
| rev-correctness | Opus | Judging whether tests actually cover ACs is subtle; cheap models rubber-stamp |
| rev-platform | Opus | Coroutine hygiene / BLE realities / Compose recomposition — precisely the findings Sonnet-tier reviewers miss |
| rev-arch | Haiku | Module isolation and contract-touch are script-checked (`module-isolation.sh`); the agent verifies MODULE.md currency and dependency direction — mechanical |
| merge-agent | Haiku | Runs `merge.sh`, reads gate output, writes the merge/status commit. Near-deterministic |

### 10.2 Token guards (structural, not aspirational)

1. **Scripts before agents.** Anything deterministic is bash, not a model: the gate, the merge procedure, backlog greps, standup sweeps, burndown. An agent is only spawned where judgment is required. This is the single biggest guard.
2. **Tight briefs, no exploration.** A spawned agent receives the issue file content, the branch name, and the relevant file list — never "look around the repo and figure it out." Reviewer agents get the issue + diff + checklist only.
3. **Escalation ladder, not retry loops.** An agent that fails its own gate twice, or a review that reaches round 2 with an unresolved BLOCKER, escalates one tier (Sonnet→Opus, Opus→orchestrator arbitration) instead of looping at the same tier. Two same-tier failures = the model is under-powered for the task; more retries just burn tokens on the same wall.
4. **Round cap stands** (§5, two request-changes rounds max). Review ping-pong is the classic token sink; the cap converts it into one orchestrator arbitration.
5. **Re-reviews check the delta only** (§5.2). A round-2 reviewer reads fix commits, not the whole branch again.
6. **Wave discipline.** Max 3–4 concurrent feature agents. Wider waves multiply merge-queue rebases (each rebase = re-run gate = tokens + time).
7. **Orchestrator stays thin.** Between waves the orchestrator's job is grep + spawn + read reports. If it catches itself reading module source to "check" a subagent, that's the reviewer's job at 1/2 the price — spawn one.
8. **No agent reads what a script already asserted.** Gate green means green; reviewers don't re-derive lint findings, the merge-agent doesn't re-read the diff.
9. **Per-wave summary.** After each wave the orchestrator reports to Taras: issues merged, rounds used, escalations triggered. Escalations trending up = briefs are too vague or a model tier is misassigned — fix the process, not the agents (same principle as the PR-review doc's metrics section).

### 10.3 Token budgets — per WAVE, enforced as a turn target

Budgets are set **per wave**, not per agent (a mid-task cap turns spent tokens into a half-finished branch — pure waste) and not per issue (too fine to estimate; spend tracks reading + review rounds, not story points). Taras sets the target when kicking off a wave (e.g. "go, +300k"); it is a hard ceiling on that turn.

Recalibrated 2026-08-09 after Sprints 0–1 actuals (0: ~230k vs 200k; 1: ~1.0M vs 400k).
Working units observed: module bootstrap ≈ 350k; reviewed branch ≈ 300–400k at the old flat
matrix, ≈ 200–250k at Tier B; a Tier-A two-Opus cycle ≈ 250k of any branch's cost. Original
table was ~3.5× optimistic; this one assumes §5.5 tiering + batching.

| Wave | Target | Notes |
|---|---|---|
| Sprint 0 (scaffold/contracts/fakes/gate) | ~~+200k~~ actual ~230k | done |
| Sprint 1 (panel + OBD-10) | ~~+400k~~ actual ~1.0M | done; OBD-11/12 rolled to 1b |
| Sprint 1b (OBD-11+12, one batched Tier-B branch) | +350k | demo-APK milestone |
| Sprint 2a (protocol, Tier A, batch 13+14 and 15+16) | +900k | |
| Sprint 2b (BLE, Tier A, batch 17+18; 19 solo) | +900k | |
| Sprint 2c (UI, Tier B, batch 20+21) | +400k | |
| Sprint 3 (reconnect/service/DI — Tier A; OBD-27 Tier B) | +900k | Rest is human-gated |
| Sprint 4 (telemetry + hardening, Tier B/C, batched) | +1.2M | |

Rules:
- **No agent starts that can't plausibly finish** within remaining budget. At ~20% remaining: stop spawning, land in-flight reviews/merges only, report.
- **Escalations spend from the same pool** — an Opus re-run of a failed Sonnet task is budgeted work, not overflow. If escalations would blow the cap, the wave ends short and the issue rolls to the next wave.
- **Ledger:** each `issues/_sprint-N.md` records per wave: planned target, actual spend, issues merged vs rolled, escalation count. Two waves over target → recalibrate the table, don't keep overriding.
- Targets are calibrated after Sprints 0–1 actuals; the table above is the starting hypothesis, not doctrine.

## 11. What this deliberately gives up

- **Mechanical enforcement of review-before-merge.** It's procedural (merge.sh + role discipline), not cryptographic. With one identity it was never going to be otherwise — this design is just honest about it.
- **Async cadence.** No 30-minute reviewer polling; reviews happen when the orchestrator schedules the wave. Irrelevant locally.
- **A place to click.** No web UI for the backlog. `grep` and the sprint index files are the board. If that ever chafes, that's the signal to publish (§9), not to add tooling.
