# Sprinter OBD Gauge App

Native Android (Kotlin/Compose) app reading live OM642 engine data — coolant, trans, and oil temps plus altitude-true boost — from a Veepeak OBDCheck BLE+ on the NCV3 Revel. Built by a team of Claude agents via a local-first git workflow.

**This directory becomes the repo root at Sprint 0** (`git init` here). Docs are already in their permanent home.

## Start here

| Doc | What it is |
|---|---|
| **[STATUS.md](STATUS.md)** | **The living checklist** — wave board, per-issue checkboxes, hardware gates (🖐 = Taras), budget ledger. Reconciled against repo state at every wave boundary. Read this first, always. |
| [docs/05-local-workflow.md](docs/05-local-workflow.md) | **The operating manual** — how work actually flows: issues/reviews as committed markdown, `tools/gate.sh` as CI, scripted squash-merge, model matrix + token budgets (§10), STATUS reconciliation rule (§6a). Supersedes docs 03/04 where they conflict. |
| [docs/01-build-plan.md](docs/01-build-plan.md) | Architecture & phases — modules, frozen Phase-0 contracts, fakes, the library-vs-custom decision panel, hardware bring-up protocol |
| [docs/02-backlog.md](docs/02-backlog.md) | Scrum backlog — OBD-1…34 with acceptance criteria, sprint goals, ceremonies |
| [docs/03-teamwork-github-workflow.md](docs/03-teamwork-github-workflow.md) | Original GitHub-hosted process (issues/PRs/CI via `gh`). Reference for conventions + the eventual publish path; transport layer replaced by doc 05 |
| [docs/04-review-process.md](docs/04-review-process.md) | Review team spec — reviewer lenses, finding format, severity semantics, escalation, merge gate. Mechanics adapted to local-first by doc 05 §5–6; the discipline applies verbatim |

## Standing rules

- Local `main` merges: pre-authorized, **this project only**. Any `git push` to a remote: per-action OK from Taras.
- 🖐 items in STATUS.md (hardware verification, contract changes, publish) are Taras's — never auto-closed.
- Kickoff format: `run Sprint <wave>, +<token budget>` (targets in STATUS.md wave board).
