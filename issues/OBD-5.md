---
id: OBD-5
title: FakeObdLink transcript replayer
module: core/testing
owner: orchestrator
sprint: 0
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: main
---

## Feature
`FakeObdLink` implements `ObdLink` from `:core:model` and replays a synthetic AT/PID transcript with injectable latency, garbage frames, and timeouts, so protocol and BLE code can be tested without hardware.

## Contract surface
None expected — implements the frozen `ObdLink` interface from OBD-3.

## Acceptance criteria
- [ ] `FakeObdLink` implements `ObdLink` against the OBD-3 contracts
- [ ] Replays a synthetic init + PID request/response transcript matching real ELM327 framing
- [ ] Injectable latency (configurable per-frame delay)
- [ ] Injectable garbage frames (malformed responses) and timeouts (no response within window)
- [ ] Unit tests cover each fault-injection mode independently and in combination

## Self-test plan
`:core:testing` unit tests replay a scripted transcript and assert correct frame sequencing under each fault condition.

## Out of scope
Real `ObdLink` BLE implementation (OBD-17/18); protocol parsing logic (OBD-13/14) — this issue only replays bytes, it doesn't parse them.
