---
id: OBD-25
title: Prod-flavor DI wiring
module: app
owner: orchestrator
sprint: 3
status: open
type: feature
hardware-verify: false
blocked-by: [OBD-18, OBD-16]
branch: orchestrator/25-prod-di-wiring
---

## Feature
Prod-flavor dependency injection wires the real `ObdLink` through the real `VehicleDataSource` into ViewModels, leaving the demo flavor untouched.

## Contract surface
None expected — pure wiring against the frozen OBD-3 contracts, no interface changes.

## Acceptance criteria
- [ ] `prod` flavor DI graph wires real `ObdLink` (OBD-17/18) → real `VehicleDataSource` implementation → ViewModels
- [ ] `demo` flavor DI graph (OBD-12) is unmodified and still uses `FakeVehicleDataSource`
- [ ] JVM end-to-end test: real protocol logic (OBD-13/14/15/16) running over real captured fixtures (OBD-22) produces the expected UI state
- [ ] No Android-instrumentation dependency for this end-to-end test (runs in plain JVM where possible)

## Self-test plan
JVM end-to-end test — fixtures in, ViewModel state out, asserted against expected values.

## Out of scope
In-van live verification (OBD-26); foreground service integration (already covered by OBD-24).
