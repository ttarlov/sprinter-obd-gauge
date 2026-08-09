---
id: OBD-22
title: Capture real fixtures via debug console
module: core/testing
owner: protocol-agent
sprint: 3
status: open
type: fixture
hardware-verify: true
blocked-by: []
branch: protocol/22-real-fixtures
---

## Feature
Real hardware fixtures — init banner, standard PIDs at idle, mode-22 trans-temp exchange, UUID map, and a broken/unplug capture — are captured via the debug console and committed to `:core:testing`.

## Contract surface
None expected — fixtures are test data, not contract changes.

## Acceptance criteria
- [ ] Init banner transcript captured from the real Veepeak dongle via the debug console (OBD-19)
- [ ] Standard-PID transcripts captured at idle for all six OBD-14 PIDs
- [ ] Mode-22 trans-temp request/response transcript captured
- [ ] Real GATT UUID map (service/characteristic UUIDs actually found on the dongle) documented and committed
- [ ] Broken/unplug-mid-response capture recorded
- [ ] Fixtures committed to `:core:testing`
- [ ] All Sprint-2 protocol tests (OBD-13/14/15) re-run against the real transcripts and pass green

## Hardware checklist
(added by Taras during the bench/parked-van session — see STATUS.md OBD-22a/OBD-22b split for the two-part capture sequence)

## Self-test plan
Re-running the existing OBD-13/14/15 unit test suites with fixtures swapped from synthetic to real is the test.

## Out of scope
In-van live bring-up with engine running (OBD-26); oil-temp mode-22 discovery (unscheduled, OBD-35).
