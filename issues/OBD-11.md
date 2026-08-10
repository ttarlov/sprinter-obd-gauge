---
id: OBD-11
title: Connection state banner and stale-data treatment
module: app
owner: ui-agent
sprint: 1
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: ui/11-12-banner-demo-flavor
---

## Feature
A connection-state banner and stale-data treatment on gauge tiles, driven by `LinkState` and `Reading.stale`.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Banner reflects each `LinkState` value (connected / connecting / disconnected / error, per the OBD-3 contract) with distinct visual treatment
- [ ] Gauge tiles visually indicate staleness when `Reading.stale == true`
- [ ] DISCONNECT_RECONNECT fake scenario (OBD-4) drives the banner through every state transition in sequence
- [ ] Compose UI test walks the full DISCONNECT_RECONNECT scenario asserting banner state at each step

## Self-test plan
Compose UI test against the `FakeVehicleDataSource` DISCONNECT_RECONNECT scenario.

## Out of scope
Real reconnect logic (OBD-23) — this is presentation only, driven by whatever `LinkState` the data source emits.
