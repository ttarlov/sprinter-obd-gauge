---
id: OBD-13
title: ELM327 init state machine
module: core/protocol
owner: protocol-agent
sprint: 2
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: protocol/13-14-init-registry
---

## Feature
An ELM327 init state machine (ATZ→ATE0→ATL0→ATS0→ATSP0→0100 verify) with typed failure states, running over `ObdLink`.

## Contract surface
None expected.

## Acceptance criteria
- [ ] State machine issues the full sequence: ATZ, ATE0, ATL0, ATS0, ATSP0, 0100 verify
- [ ] Happy-path init tested against the `FakeObdLink` (OBD-5) transcript
- [ ] Each failure mode (timeout, garbage response, unexpected banner, protocol-not-found) has a typed failure result and a corresponding test
- [ ] Init failures surface as typed errors, not exceptions/crashes
- [ ] State machine is cancellable and restartable (supports retry from a clean state)

## Self-test plan
Unit tests against `FakeObdLink` happy path plus each injected fault mode from OBD-5.

## Out of scope
Standard PID parsing (OBD-14); mode-22 (OBD-15); real BLE transport (OBD-17/18).
