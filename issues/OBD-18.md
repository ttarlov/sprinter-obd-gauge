---
id: OBD-18
title: GATT serial bridge
module: core/ble
owner: ble-agent
sprint: 2
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: ble/18-gatt-serial-bridge
---

## Feature
A GATT serial bridge — service/characteristic probing across a candidate UUID list with writable/notifiable fallback, CCCD setup, MTU 512, notification reassembly into `>`-terminated responses, and a single-flight request mutex.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Probes a candidate UUID list for writable/notifiable characteristics, with fallback ordering when the primary candidate isn't present
- [ ] CCCD (notification enable descriptor) written correctly before expecting notifications
- [ ] MTU negotiated to 512 where the peripheral supports it, with graceful fallback otherwise
- [ ] Notification fragments reassembled into complete `>`-terminated ELM327 responses
- [ ] Single-flight mutex ensures only one request is in-flight at a time (half-duplex safety)
- [ ] Reassembly and timeout logic unit-tested via a GATT test double
- [ ] Fragmented-response fixture (multi-notification split response) passes reassembly correctly

## Self-test plan
Unit tests against a GATT test double covering UUID fallback, CCCD setup, fragmented-response reassembly, and single-flight enforcement; timeout-path test.

## Out of scope
Real hardware UUID discovery (happens live during OBD-19/OBD-22 hardware sessions, feeding back into this UUID candidate list); reconnect logic (OBD-23).
