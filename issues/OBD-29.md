---
id: OBD-29
title: Post-drive charts
module: app
owner: telemetry-agent
sprint: 4
status: open
type: feature
hardware-verify: false
blocked-by: [OBD-28]
branch: telemetry/29-post-drive-charts
---

## Feature
Post-drive charts show per-gauge timelines against the elevation profile for a logged drive, with a correlated cursor across gauges and elevation.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Renders per-gauge timeline charts (coolant/trans/oil/boost) for a real logged climb from OBD-28
- [ ] Elevation profile chart rendered alongside the gauge timelines, time-aligned
- [ ] A cursor/scrubber moving on one chart correlates position across all gauge charts and the elevation chart
- [ ] Handles a logged drive with gaps (disconnect periods) without breaking the chart

## Self-test plan
Renders against a real logged climb captured through OBD-28's Room store; cursor correlation covered by a UI test.

## Out of scope
Export (OBD-30); named-climb recognition/historical comparison (unscheduled, OBD-36).
