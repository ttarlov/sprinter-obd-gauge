---
id: OBD-4
title: FakeVehicleDataSource scenarios
module: core/testing
owner: orchestrator
sprint: 0
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: main
---

## Feature
`FakeVehicleDataSource` implements `VehicleDataSource` from `:core:model` and replays four deterministic scenario scripts (IDLE, TOWN_HEAT_SOAK, GRADE_CLIMB, DISCONNECT_RECONNECT) for UI and integration tests.

## Contract surface
None expected — implements the frozen `VehicleDataSource` interface from OBD-3, adds no new public contract.

## Acceptance criteria
- [ ] `FakeVehicleDataSource` implements `VehicleDataSource` against the OBD-3 contracts
- [ ] Four named scenarios (IDLE, TOWN_HEAT_SOAK, GRADE_CLIMB, DISCONNECT_RECONNECT) each emit a scripted, deterministic value stream
- [ ] Scenario streams are replayable — same script, same sequence, every run, no wall-clock dependency
- [ ] Unit tests assert exact emitted sequences per scenario
- [ ] TOWN_HEAT_SOAK script includes values that cross amber/red thresholds (needed by OBD-10's screenshot tests)

## Self-test plan
`:core:testing` unit tests assert each scenario's emitted `Reading` sequence matches its script exactly, including ordering.

## Out of scope
Real `VehicleDataSource` implementation (Sprint 2/3); UI consumption (OBD-10/11/12).
