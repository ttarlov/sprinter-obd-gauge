---
id: OBD-24
title: Foreground service for connection and poll loop
module: app
owner: ble-agent
sprint: 3
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: ble/24-foreground-service
---

## Feature
A `connectedDevice`-type foreground service runs the connection and poll loop with the screen off, showing a persistent notification with a headline reading.

## Contract surface
None expected. Note: lives under `:app` (Android `Service` + notification are app-level components) though owned by ble-agent, consistent with the OBD-19 cross-boundary precedent.

## Acceptance criteria
- [ ] Foreground service declared with `connectedDevice` type per Android 14+ requirements
- [ ] Poll loop continues uninterrupted for at least 10 minutes with the screen off, verified on-device
- [ ] Persistent notification shows a live headline reading (e.g. coolant temp) and updates as data changes
- [ ] Doze-mode behavior documented (what happens to poll cadence under Doze, and why it's acceptable or mitigated)

## Self-test plan
On-device manual test — screen off for 10+ minutes, confirm poll loop and notification stay live; documented Doze behavior is a written artifact, not automatable.

## Out of scope
Reconnect logic itself (OBD-23, this issue integrates it into a service); battery soak measurement (OBD-33).
