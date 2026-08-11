---
id: OBD-21
title: Settings screen
module: app
owner: ui-agent
sprint: 2
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: ui/20-21-sparklines-settings
---

## Feature
A settings screen for gauge selection/order, thresholds, units (°F/°C, PSI/kPa), keep-screen-on, and poll rate, persisted via DataStore.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Gauge selection and ordering configurable and persisted
- [ ] Threshold values editable per gauge and persisted
- [ ] Unit toggle — °F/°C for temps, PSI/kPa for boost — persisted and reflected across the dashboard
- [ ] Keep-screen-on toggle persisted and applied
- [ ] Poll rate configurable and persisted
- [ ] Settings persist across app restart (DataStore-backed)
- [ ] Editing a threshold live-recolors the dashboard in a Compose test (no restart required)

## Self-test plan
DataStore persistence test (write, restart, read back); Compose test for live threshold-edit recoloring.

## Out of scope
Threshold preset profiles like "Loaded Revel — summer" (OBD-32, ships a default table on top of this screen's per-value editing).
