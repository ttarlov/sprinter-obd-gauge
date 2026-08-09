---
id: OBD-20
title: Sparkline strip charts
module: app
owner: ui-agent
sprint: 2
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: ui/20-sparkline-charts
---

## Feature
A 5-minute rolling sparkline strip chart per gauge.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Each gauge tile has an accompanying sparkline showing the trailing 5 minutes of readings
- [ ] Renders correctly from all four `FakeVehicleDataSource` scenarios (OBD-4)
- [ ] No dropped frames or jank at a 4 Hz update rate, verified by a frame-timing test
- [ ] Data older than the 5-minute window is pruned (bounded memory)

## Self-test plan
Frame-timing test at 4 Hz update rate against fake scenarios; visual render test per scenario.

## Out of scope
Post-drive historical charts against elevation (OBD-29 — a different chart entirely, for logged drives).
