---
id: OBD-33
title: Chaos and battery soak
module: core/ble
owner: ble-agent
sprint: 4
status: open
type: feature
hardware-verify: true
blocked-by: []
branch: ble/33-chaos-battery-soak
---

## Feature
A chaos and battery soak: scripted disconnect storms, overnight foreground-service battery measurement, and a StrictMode/ANR audit.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Scripted disconnect-storm test (rapid repeated disconnect/reconnect beyond the 50-cycle soak in OBD-23) run and results committed
- [ ] Overnight foreground-service battery drain measured on-device with actual numbers documented
- [ ] StrictMode violations audited and resolved or explicitly justified
- [ ] Zero ANRs observed during the soak window
- [ ] Soak report committed to the repo

## Hardware checklist
(overnight measurement — populated by Taras)

## Self-test plan
Scripted disconnect-storm portion is automatable and unit/integration-tested; battery/ANR measurement is manual on-device, no automated substitute.

## Out of scope
Fixing root causes discovered during the soak beyond this issue's scope — file follow-up issues if the soak surfaces new bugs rather than scope-creeping this one.
