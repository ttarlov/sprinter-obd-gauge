---
id: OBD-10
title: Gauge dashboard v1
module: app
owner: ui-agent
sprint: 1
status: in-progress
type: feature
hardware-verify: false
blocked-by: []
branch: ui/10-gauge-dashboard
---

## Feature
A dashboard screen renders numeric tiles for coolant, transmission, oil temp, and boost with config-driven threshold coloring, dark theme, landscape-first layout.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Four numeric tiles (coolant, trans temp, oil temp, boost) render from a `VehicleDataSource` (fake, in this sprint)
- [ ] Threshold coloring (green/amber/red) is driven by a config table, not hardcoded per gauge
- [ ] Dark theme applied throughout
- [ ] Landscape-first layout (primary target orientation); portrait doesn't crash or clip
- [ ] Screenshot tests cover both orientations
- [ ] TOWN_HEAT_SOAK fake scenario (OBD-4) renders amber and red states correctly per configured thresholds

## Self-test plan
Compose screenshot tests against `FakeVehicleDataSource` scenarios (OBD-4), specifically TOWN_HEAT_SOAK for threshold-color verification.

## Out of scope
Connection state banner (OBD-11); sparkline charts (OBD-20); settings-driven threshold edits (OBD-21).
