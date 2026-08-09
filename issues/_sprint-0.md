---
sprint: 0
title: Skeleton & Contracts
---

# Sprint 0 — Skeleton & Contracts

Sprint goal: a compiling multi-module repo with frozen contracts and working fakes, plus the collaboration rails (issues, local workflow directories, gate/merge scripts) from doc 05.

| ID | Title | Owner | Pts | Status |
|----|-------|-------|-----|--------|
| OBD-1 | Repo and workflow rails | orchestrator | 3 | merged |
| OBD-2 | Multi-module Gradle scaffold | orchestrator | 3 | merged |
| OBD-3 | Phase-0 contracts freeze | orchestrator | 2 | merged |
| OBD-4 | FakeVehicleDataSource scenarios | orchestrator | 3 | merged |
| OBD-5 | FakeObdLink transcript replayer | orchestrator | 3 | merged |

Sprint 0 velocity: 14 pts.

**Demo/milestone:** No review demo — Sprint 0 is infrastructure. Exit criteria: `tools/gate.sh` green on `main`, all five issues merged, `issues/OBD-*.md` + `_sprint-N.md` indexes committed.

## Ledger

| Planned target | Actual spend | Merged vs rolled | Escalations |
|---|---|---|---|
| +200k | ~230k (est. — 3 Sonnet agents + Fable orchestrator; no per-agent metering this wave) | 5 merged / 0 rolled | 0 |
