---
id: OBD-25
title: Prod-flavor DI wiring
module: app
owner: ui-agent
sprint: 3
status: in-review
type: feature
hardware-verify: false
blocked-by: [OBD-18, OBD-16]
branch: ui/25-prod-di-wiring
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

## Addendum (2026-08-12, wave start — everything learned since this was written)

1. **Self-heal ownership MUST be resolved in this change** — the full hazard statement is
   at the end of reviews/OBD-24-round1.md (committed on main). Key subtlety discovered at
   fix-round 2: `RealVehicleDataSource.connection` forwards `link.state`, which the VM's
   start/stop does NOT touch — so the controller's Ready→Disconnected trigger may never
   fire, or fire for link reasons. Ownership must land as: the reconnect machine
   (:core:ble, OBD-23) is the single authority over the LINK; the service keeps the
   SOURCE started while it intends to run; nobody calls ObdLink.connect() in a loop.
2. **Onboarding/connect flow**: prod needs the runtime permission request
   (BLUETOOTH_SCAN/CONNECT at the connect moment, per OBD-17's flow) and a user-visible
   connect entry (wire the OBD-11 banner's action or equivalent). Remembered-device fast
   path first; scan when none.
3. **End-to-end fixtures are now REAL**: use the 2026-08-12 captures
   (docs/hardware/session-2026-08-12.md + :core:protocol's TcuRecordCaptures) — coolant
   94°C, rpm ~727, load ~56%, throttle 83%, baro 82 kPa through a scripted FakeObdLink →
   assert the exact UI state, including boost surfacing as typed-unavailable (MAP NO
   DATA) and NO trans-temp reading (DecodeFalsified gate) with badges correct.
4. **Prod-debug is the van build** (console remains available in it); demo untouched.
   Note: tools/channel-build.sh builds demo-debug — van sideloads app-prod-debug.apk
   manually until a channel row exists for it (out of scope here).
