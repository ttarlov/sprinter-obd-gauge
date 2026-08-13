---
id: OBD-23
title: Reconnect state machine
module: core/ble
owner: ble-agent
sprint: 3
status: in-review
type: feature
hardware-verify: false
blocked-by: []
branch: ble/23-48-reconnect-traffic
---

## Feature
A reconnect state machine with exponential backoff, resume-on-device-found, and key-off recovery.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Exponential backoff on failed reconnect attempts, with a sane cap
- [ ] Resume-on-found: reconnects automatically when the remembered device reappears in scan results
- [ ] Key-off recovery: handles the dongle disappearing (vehicle key-off) and reappearing (key-on) without manual intervention
- [ ] State machine fully unit-tested — all transitions, all failure/recovery paths
- [ ] Soak script survives 50 scripted disconnect/reconnect cycles without state corruption or leak

## Self-test plan
Unit tests for every state transition; scripted 50-cycle soak test asserting no crashes, leaks, or stuck states.

## Out of scope
Foreground service integration (OBD-24, consumes this state machine but is a separate issue); real hardware validation (OBD-26).
