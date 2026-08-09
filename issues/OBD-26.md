---
id: OBD-26
title: In-van bring-up checklist
module: app
owner: orchestrator
sprint: 3
status: open
type: feature
hardware-verify: true
blocked-by: [OBD-25, OBD-22]
branch: orchestrator/26-in-van-bringup
---

## Feature
The in-van bring-up checklist is executed on the real vehicle: ignition-on reads, idle sanity, cold-soak convergence, a drive test, and kill tests, with every mode-22 PID flipped to verified or explicitly logged unverified.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Ignition-on reads confirmed (app connects and shows live data on key-on)
- [ ] Idle sanity check: boost reads ≈0, RPM reads ~780
- [ ] Cold-soak temp convergence observed and recorded
- [ ] Drive test: boost reads mid-teens under load, temps show a healthy pattern across the drive
- [ ] Kill tests (engine off mid-drive, dongle unplug) handled without app crash or stuck state
- [ ] Every mode-22 PID (from OBD-15) is flipped to `verified` with a recorded raw response, or explicitly left `unverified` with the raw response logged

## Hardware checklist
(populated by Taras with observed values per doc 05 §6.2)

## Self-test plan
N/A — this issue's acceptance criteria ARE the hardware checklist; no automated substitute.

## Out of scope
Chaos/battery soak (OBD-33, a separate longer-duration hardware session); oil-temp PID discovery (unscheduled, OBD-35).
