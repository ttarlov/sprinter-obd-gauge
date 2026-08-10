---
id: OBD-12
title: Demo build flavor wired to fake data source
module: app
owner: ui-agent
sprint: 1
status: in-progress
type: feature
hardware-verify: false
blocked-by: [OBD-10, OBD-11]
branch: ui/11-12-banner-demo-flavor
---

## Feature
A `demo` Gradle build flavor wires the dashboard to `FakeVehicleDataSource` and produces an installable APK from CI, with no Bluetooth permissions required.

## Contract surface
None expected.

## Acceptance criteria
- [ ] `demo` product flavor added, DI-wires `FakeVehicleDataSource` in place of the real data source
- [ ] `assembleDemo` runs in CI and produces an APK artifact
- [ ] App launches and runs fully functional (dashboard + banner) with zero Bluetooth permissions granted
- [ ] `prod` flavor scaffold exists but is untouched/unwired (stub) at this point

## Self-test plan
CI job runs `assembleDemo`; manual install-and-launch check with all BT permissions denied confirms no crash or permission prompt.

## Out of scope
Real BLE wiring for the `prod` flavor (OBD-25).
