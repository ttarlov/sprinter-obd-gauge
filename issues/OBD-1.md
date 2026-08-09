---
id: OBD-1
title: Repo and workflow rails
module: process
owner: orchestrator
sprint: 0
status: open
type: process
hardware-verify: false
blocked-by: []
branch: main
---

## Feature
The repo has the local-workflow scaffolding — `issues/`, `reviews/`, `research/`, `tools/` directories, `OWNERSHIP`, and stub gate/merge scripts — described in doc 05, replacing the GitHub-hosted Teamwork mechanics with git-native equivalents.

## Contract surface
None expected.

## Acceptance criteria
- [ ] `issues/`, `reviews/`, `research/`, `tools/` directories created per doc 05 §2 repo layout
- [ ] `OWNERSHIP` file created mapping path prefixes to owning roles (doc 05 §2)
- [ ] `tools/gate.sh` stub exists and runs; `--process` mode (isolation + markdown sanity, no build) passes on the empty scaffold
- [ ] `tools/module-isolation.sh` stub exists and diffs changed paths against `OWNERSHIP`
- [ ] `tools/merge.sh` stub exists implementing the §6 procedure skeleton with clear abort points at each check
- [ ] `DECISIONS.md` created (header only) at repo root

## Self-test plan
Manual dry run: commit a change under an unauthorized path and confirm `module-isolation.sh` flags it; run `gate.sh --process` on the empty scaffold and confirm exit 0.

## Out of scope
Real build/test/lint checks in `gate.sh` — those get wired in once Gradle modules exist (OBD-2).
