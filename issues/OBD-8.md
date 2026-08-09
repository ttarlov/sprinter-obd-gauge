---
id: OBD-8
title: Constraints analysis — library vs custom
module: research
owner: researcher
sprint: 1
status: open
type: process
hardware-verify: false
blocked-by: []
branch: research/8-constraints-analysis
---

## Feature
A constraints analysis scores both options (library vs custom) against the 40/25/15/10/10 rubric from the build plan, covering mode-22 support, BLE transport fit, coroutine/half-duplex concerns, and maintenance risk.

## Contract surface
None expected — a research document only.

## Acceptance criteria
- [ ] `research/constraints.md` created
- [ ] Analyzes mode-22 + custom-header support for both options
- [ ] Analyzes BLE transport fit (half-duplex request/response over notify/write characteristics) for both options
- [ ] Analyzes coroutine/concurrency model fit for both options
- [ ] Analyzes maintenance risk (bus factor, dependency drift) for both options
- [ ] Both options scored against the 40/25/15/10/10 weighted rubric with a final numeric comparison

## Self-test plan
N/A — research artifact. Scoring math is checkable arithmetic, reviewed during OBD-9.

## Out of scope
The two position papers (OBD-6/7 — this issue synthesizes, doesn't re-argue); the decision itself (OBD-9).
