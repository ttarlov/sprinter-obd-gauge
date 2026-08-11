---
id: OBD-14
title: Standard PID registry and parser
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
A standard PID registry and parser covering coolant (0105), RPM (010C), MAP (010B), baro (0133), IAT (010F), and speed (010D), with SAE-formula scaling.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Registry defines all six PIDs (0105, 010C, 010B, 0133, 010F, 010D) with request bytes and response byte-length
- [ ] Parser applies the correct SAE scaling formula per PID, verified against known reference values
- [ ] Parser handles a `NO DATA` response without throwing
- [ ] Parser handles a `SEARCHING...` response without throwing
- [ ] Parser handles a `STOPPED` response without throwing
- [ ] Property test asserts the parser never throws on arbitrary garbage byte input

## Self-test plan
Unit tests per PID against reference values; property-based test (garbage input) for the never-throws guarantee.

## Out of scope
Mode-22 PIDs (OBD-15); computed boost (OBD-16, consumes this PID's output but is a separate issue).
