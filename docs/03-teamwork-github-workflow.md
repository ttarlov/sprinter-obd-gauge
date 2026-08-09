# Agent Teamwork & PR Workflow — Sprinter OBD Gauge App

How a team of Claude Code agents collaborates on this project through a shared GitHub repo using the `gh` CLI. Every piece of work enters the codebase the same way: a feature request (GitHub Issue) → a branch → a PR → automated checks → review → squash-merge. No agent ever pushes to `main`.

---

## 1. Repo setup (done once by the orchestrator agent)

```bash
gh repo create sprinter-obd-gauge --private --clone
cd sprinter-obd-gauge
git switch -c setup/scaffold
# ...commit Phase 0 scaffold, contracts, fakes...
gh pr create --fill
```

Repo configuration (orchestrator, via `gh` where possible):

```bash
# Protect main: PRs only, required checks, no force push
gh api repos/{owner}/{repo}/branches/main/protection -X PUT --input protection.json

# Labels that drive the workflow
gh label create "type:feature"        -c 1D76DB
gh label create "type:contract-change" -c B60205   # changes frozen Phase-0 interfaces — needs orchestrator
gh label create "agent:ui"            -c 0E8A16
gh label create "agent:protocol"      -c 5319E7
gh label create "agent:ble"           -c F9D0C4
gh label create "agent:integration"   -c FBCA04
gh label create "agent:telemetry"     -c C2E0C6
gh label create "sprint:0"; gh label create "sprint:1"; gh label create "sprint:2"; gh label create "sprint:3"; gh label create "sprint:4"
gh label create "blocked"             -c 000000
gh label create "hardware-verify"     -c E99695   # needs a human in the van to close
```

Branch protection rules on `main`:
- Require a PR with at least **1 approving review** (the reviewer agent — see §5).
- Required status checks: `build`, `unit-tests`, `lint` (ktlint/detekt), `module-isolation`.
- Squash merge only; branch auto-delete on merge.

CODEOWNERS enforces the one-writer-per-module rule mechanically:

```
/app/                @ui-agent
/core/model/         @orchestrator
/core/protocol/      @protocol-agent
/core/ble/           @ble-agent
/core/testing/       @orchestrator @protocol-agent
DECISIONS.md         @orchestrator
```

(Each "agent" maps to a bot account or a designated Claude Code session identity; if all agents share one account, enforce ownership via the PR template checklist + reviewer agent instead.)

---

## 2. Feature requests are GitHub Issues

Every unit of work starts as an issue created from this template (`.github/ISSUE_TEMPLATE/feature.yml`):

```markdown
## Feature
<one sentence: what exists after this is merged>

## Module
:app | :core:protocol | :core:ble | :core:testing | :core:logging

## Contract surface
List every Phase-0 interface this touches. "None" is the expected answer.
Anything else requires label `type:contract-change` and orchestrator sign-off BEFORE work starts.

## Acceptance criteria
- [ ] testable statement 1
- [ ] testable statement 2

## Self-test plan
How this is proven WITHOUT other agents' modules (which fake, which fixtures).

## Out of scope
What this deliberately does not do.
```

Orchestrator seeds the backlog (see the Scrum doc) and assigns via labels:

```bash
gh issue create --title "OBD-12: ELM327 init state machine" \
  --label "type:feature,agent:protocol,sprint:2" \
  --body-file issues/OBD-12.md
```

Agents pick up work by querying their label:

```bash
gh issue list --label "agent:protocol" --label "sprint:2" --state open
gh issue view 12 --comments      # read full context before starting
```

---

## 3. Branch and commit conventions

- Branch: `<agent>/<issue-number>-<slug>` → `protocol/12-elm327-init`
- Commits: Conventional Commits, issue-linked — `feat(protocol): init state machine (#12)`
- One issue per PR, one PR per issue. If work grows, split the issue first (`gh issue create`, cross-link), don't grow the PR.
- Rebase on `main` before opening the PR; agents resolve their own conflicts (conflicts should be rare by design — module isolation means overlapping edits signal a workflow violation worth flagging in the PR body).

---

## 4. The PR

```bash
git push -u origin protocol/12-elm327-init
gh pr create \
  --title "feat(protocol): ELM327 init state machine (#12)" \
  --body-file .github/pr_body.md \
  --label "agent:protocol,sprint:2"
```

PR template (`.github/pull_request_template.md`):

```markdown
Closes #<issue>

## What
<2-4 sentences>

## Self-test evidence
- [ ] `./gradlew :core:protocol:test` green locally — paste summary line
- [ ] New tests cover every acceptance criterion (map them: AC1 → TestClass.testName)
- [ ] No files changed outside my owned module(s)
- [ ] No changes to Phase-0 contracts (or `type:contract-change` + orchestrator approval linked)

## Fixtures touched
Added/updated transcripts or scenario scripts, if any.

## Known limitations
What the next issue on this surface should pick up.
```

Rules:
- **Draft PRs early** (`gh pr create --draft`) are encouraged for visibility; mark ready only when self-tests pass.
- A PR that touches another agent's module is closed on sight and the diff moved to an issue for that module's owner.
- Max PR size guidance: ~600 changed lines excluding tests/fixtures. Bigger → split the issue.

---

## 5. Review: the reviewer agent

A dedicated **reviewer agent** (a Claude Code session whose only job is review) processes the queue:

```bash
gh pr list --state open --search "review:required" --json number,title,labels
gh pr checkout 34
gh pr diff 34
./gradlew test        # trust nothing, run it
gh pr review 34 --approve  -b "AC1-4 verified against tests; module isolation clean."
# or
gh pr review 34 --request-changes -b "AC3 unverified: no test for NO DATA frames. Parser test asserts on happy path only."
```

Reviewer agent checklist (kept in `docs/REVIEW_CHECKLIST.md`, versioned like code):
1. Every acceptance criterion in the linked issue maps to a passing test in the diff.
2. Diff stays inside the module the issue names; contracts untouched unless labeled.
3. No `!!`, no swallowed exceptions, no `GlobalScope`, no blocking calls in coroutines, no deprecated Bluetooth/permission APIs.
4. Public surface documented; `MODULE.md` updated if the surface changed.
5. Fixtures: any new parser behavior has a transcript fixture, not just inline strings.

Human (Taras) reserves the right to review anything, and is the **required** reviewer for: `type:contract-change`, anything labeled `hardware-verify`, and the Phase-1 library decision PR to `DECISIONS.md`.

---

## 6. CI gates (GitHub Actions, `ci.yml`)

- `build`: `./gradlew assembleDebug assembleDemo`
- `unit-tests`: `./gradlew test` (JVM tests for :core:model, :core:protocol, :core:ble logic classes)
- `lint`: ktlint + detekt + Android Lint, zero new warnings policy
- `module-isolation`: a script that fails the build if the PR's changed paths cross module ownership boundaries (reads CODEOWNERS, compares against `gh pr diff --name-only`)
- Screenshot tests for `:app` run on PRs labeled `agent:ui` (Roborazzi or Paparazzi on JVM — no emulator needed in CI)

A red check blocks merge, no exceptions, no admin-merges. If CI is wrong, fix CI in its own PR.

---

## 7. Cross-agent communication happens in artifacts, not chats

- **Questions about a contract** → comment on the contract's defining issue or open a `type:contract-change` issue. Never resolved in a PR thread on unrelated work.
- **Discovered constraints** (e.g., BLE agent learns the Veepeak fragments at 20 bytes without MTU negotiation) → recorded in the module's `MODULE.md` AND posted as a comment on any open issue it affects: `gh issue comment 18 -b "..."`.
- **Decisions** → `DECISIONS.md` via PR, one decision per PR, so the log stays reviewable.
- **Blocked work** → label `blocked` + a comment naming the blocking issue number. Orchestrator sweeps `gh issue list --label blocked` at sprint boundaries.

## 8. Sprint mechanics with `gh`

```bash
# Sprint board = milestone + labels
gh api repos/{owner}/{repo}/milestones -f title="Sprint 2 — Protocol & BLE" -f due_on="..."
gh issue edit 12 --milestone "Sprint 2 — Protocol & BLE"

# Standup (orchestrator, daily): open PRs, blocked issues, sprint burndown
gh pr list --state open --json number,title,isDraft,labels
gh issue list --milestone "Sprint 2 — Protocol & BLE" --state open

# Sprint close: everything unmerged rolls to next milestone with a comment explaining why
```

Definition of Done for any issue = PR merged + CI green + acceptance boxes checked in the issue + `MODULE.md` current. Issues labeled `hardware-verify` additionally require a checklist comment from the in-van test before closing.
