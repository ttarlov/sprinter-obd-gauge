---
id: OBD-34
title: Startup polish
module: app
owner: ui-agent
sprint: 4
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: ui/34-startup-polish
---

## Feature
Startup polish — Baseline Profile, Splash Screen API, and permission-education onboarding before the system BLE permission dialog.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Baseline Profile generated and included, cold-start improvement measured before/after
- [ ] Android 12+ Splash Screen API used for launch (no custom splash Activity hack)
- [ ] Onboarding screen explains why BLE permissions are needed before the system permission dialog appears
- [ ] Cold-start improvement is measurably documented (numbers, not just "feels faster")

## Self-test plan
Startup benchmark (Macrobenchmark or equivalent) before/after Baseline Profile; manual onboarding-flow walkthrough.

## Out of scope
Any settings/threshold/alert functionality (covered by OBD-21/31/32) — this issue is startup experience only.
