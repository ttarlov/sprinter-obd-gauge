---
id: OBD-43
title: Standard PIDs — engine load (0104) and throttle position (0111)
module: core/protocol
owner: protocol-agent
sprint: backlog
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: protocol/43-load-tps-pids
---

## Feature
Two additional standard mode-01 PIDs in the registry so OBD-42's swap catalog has real
engine-load and TPS gauges: 0104 (calculated load, A×100/255 %) and 0111 (throttle
position, A×100/255 %).

## Contract surface
None. Registry additions per the OBD-14 pattern (vendored/SAE cross-checked scaling,
boundary tests, PERCENT unit).

## Acceptance criteria
- [ ] Both PIDs in PidRegistry with SAE-verified scaling + boundary tests (0x00→0, 0xFF→100)
- [ ] Parser fixtures; property tests still green
- [ ] Ids follow the PidIds naming convention; PollPriority chosen and justified

## Self-test plan
Same harness as OBD-14: FakeObdLink transcripts + exact-value tests.

## Out of scope
UI (OBD-42); fake-source channels for these (add when demo needs them).
