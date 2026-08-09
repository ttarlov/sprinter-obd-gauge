---
id: OBD-28
title: Drive logging
module: app
owner: telemetry-agent
sprint: 4
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: telemetry/28-drive-logging
---

## Feature
Room-backed drive logging records all readings plus fused location/elevation at a coarse cadence, with bounded storage via an auto-prune policy.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Room schema stores readings (all gauges) plus fused location/elevation samples at a coarse, configurable cadence
- [ ] A logged drive can be replayed and consumed by the chart screen (OBD-29's data source)
- [ ] Auto-prune policy bounds total storage (e.g. oldest-drive-first eviction past a size/age cap)
- [ ] Prune policy is unit-tested (simulate storage exceeding cap, assert correct eviction)

## Self-test plan
Room DB tests (insert, query, prune-under-cap-exceeded scenario); a logged fake drive replays cleanly into chart-consumable data.

## Out of scope
Chart rendering itself (OBD-29); export (OBD-30).
