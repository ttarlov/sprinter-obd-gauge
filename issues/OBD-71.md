---
id: OBD-71
title: Overnight cold-reconnect — app won't auto-reconnect after sitting overnight without a manual relaunch
module: app
owner: ble-agent
sprint: reliability
status: open
type: bug
hardware-verify: true
blocked-by: []
branch: feat/71-overnight-reconnect
---

## What (observed, not yet root-caused)

Reported by Taras on the Garmin Overlander (API 23) after the OBD-69 drive-verify, 2026-08-18:

- **Within a session**, engine off → engine on reconnects fine — the app finds the dongle again within a
  few minutes, unattended. ✅
- **After sitting overnight**, the next engine start does **not** auto-reconnect. The app has to be
  **quit and relaunched once**, after which it reconnects normally.

So the reconnect machinery works in the warm case but is wedged in the overnight-cold case. Deferred by
Taras from OBD-69 (which fixed the *battery drain*; this is a separate reconnect-lifecycle bug).

## Why this is plausible (leads to investigate — not conclusions)

OBD-69 now **stops the connection service after ~20 min of no data** (idle watchdog) and drops the wake
lock. Overnight the service is intentionally down. On the next engine start the questions are:

1. **What is supposed to re-arm the connection when the service is down and the app is only in the
   background/paused?** Reconnect today is driven by `ConnectionServiceController` on Activity
   `onResume`/cold-start (`app/src/main/kotlin/com/revel/obdgauge/app/service/ConnectionServiceController.kt`,
   `MainActivity`). If nothing re-`startForegroundService`s while the app sits backgrounded overnight, the
   dongle powering back up has nothing listening — matching "must relaunch once." This may be the
   OBD-69 idle-stop's expected-but-unwanted interaction, i.e. the "auto-resume on detected vehicle power"
   item OBD-69 explicitly left out of scope.
2. **BLE bond / GATT staleness after a long gap** — a stale `BluetoothGatt`/bond or a reconnect-policy
   state (`core/ble/.../ReconnectPolicy.kt`, `BleObdLink`) that survives the process but wedges after
   hours, cleared only by a fresh process. Check whether the reconnect loop is actually still running
   overnight or was cancelled with the service.
3. **Android 6.0.1 Doze/app-standby** putting the backgrounded app into a state where its scanning is
   deferred until the app is foregrounded again.

Determine which before designing a fix — could be (1) alone (the service is simply down and nothing wakes
it), which reframes the fix as a lightweight resume trigger rather than a BLE bug.

## Direction (pending root-cause)

Likely a bounded auto-resume path so the overnight-cold case reconnects without a manual relaunch — e.g. a
cheap wake on vehicle-power/BLE-advertisement detection, or a low-frequency background reconnect attempt
that doesn't reintroduce the OBD-69 overnight drain. **Must not regress OBD-69** — any always-on scanning
brings back the exact battery drain OBD-69 fixed; keep the "idle → stop" contract intact and only re-arm on
a real signal. Coordinate with the OBD-69 idle-watchdog and (if in flight) OBD-70's recording-inhibits-idle
seam in `ObdConnectionService`.

## Testing

- **Root-cause first** (device, 🖐 Taras): reproduce overnight; before relaunching, check via `adb`
  whether the service/reconnect loop is alive (`dumpsys activity services | grep -i obd`,
  `dumpsys power | grep -i wake`) — is nothing listening, or is a wedged BLE link listening but failing?
  That answer picks the fix.
- Unit/Robolectric per the eventual fix (reconnect-policy state machine, resume trigger decision) — pure
  where possible, matching the OBD-69 idle-watchdog test style.
- **Device acceptance (🖐 Taras):** after an overnight sit, the next engine start reconnects with **no
  manual relaunch**, AND the overnight battery drain stays gone (OBD-69 not regressed).

## Out of scope

- Full trip-detection / motion-based auto-start (a heavier feature; this is just the overnight-cold
  reconnect gap).
