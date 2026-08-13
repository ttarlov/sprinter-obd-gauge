---
id: OBD-48
title: Headless OBD traffic logging — full TX/RX to logcat/file in debug builds
module: core/ble
owner: ble-agent
sprint: 3
track: small
status: in-progress
type: feature
hardware-verify: false
blocked-by: []
branch: ble/23-48-reconnect-traffic
---

## Feature
Every ObdLink TX line and RX line (with timestamps + link-state transitions) logged in
debug builds under a dedicated logcat tag (e.g. `ObdTraffic`), so capture sessions run
screen-off via `adb logcat` — no console UI, no screen-scraping, lock-proof.

## Why (hardware session 2026-08-12)
The first drive capture failed completely: phone locked mid-drive, 15 poll cycles tapped
a dark screen, zero data. Console output only exists on-screen; ObdBle logs link events
only. Headless traffic logging is the prereq for the OBD-41 discovery logger and any
future drive capture.

## Acceptance criteria
- [ ] All TX/RX through the real ObdLink visible via `adb logcat -s ObdTraffic` in debug
      builds; release builds compile it out (classpath/dex-verified like OBD-19's console)
- [ ] Timestamps + link-state transitions interleaved in order
- [ ] No behavior change to the link itself (pure tap); existing BLE tests green

## Out of scope
On-device file sink, upload, UI.
