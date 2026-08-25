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

## Update 2026-08-24 — new symptoms + code-grounded root-cause hypothesis (drain + wedge are ONE bug)

Taras reports: the drain is **overnight, screen off / backgrounded** (NOT screen-on-while-parked), AND
**~10 minutes after ignition-off, the "Connect" button no longer reconnects — only a full quit + relaunch
does.** These are almost certainly the same bug.

**Prime suspect (in code):** `core/ble/.../BleObdLink.kt:427` — *"A timeout leaves the link `Ready` — a
silent ECU is normal traffic, not a dead link."* Plus `:133` — the reconnect attempt counter *"Resets on
success"* (reaching `Ready`). Consequence when the van is parked with the dongle plugged in (OBD port
powered, ECU asleep — the REAL overnight scenario):

- The link reaches `Ready` and **stays `Ready`** because read timeouts are treated as normal, not as a dead
  link. The poll loop keeps sending commands and eating timeouts indefinitely → **an active BLE connection +
  a spinning poll loop all night = the drain.** This is a DIFFERENT code path than the "no dongle" case
  OBD-69 was device-verified against (clean BLE fail → backoff → watchdog stops the service). With the
  dongle PRESENT, the link never leaves `Ready`, so the "20 min of no data → stop" watchdog may be defeated
  or the link never tears down — **that's why OBD-69 tested OK but the real overnight case still drains.**
- The **stale-`Ready` "zombie"** also explains the wedge: after the dongle actually dies/reboots (port loses
  power on key-off on some vans — see `:49`) or the GATT connection drops unnoticed, the app still believes
  it's `Ready`. Tapping **Connect** likely no-ops against an already-`Ready` state, so it can't recover —
  only a fresh process resets the link to `Disconnected` and forces a clean connect. Matches "quit fixes it,
  Connect doesn't."

**Confirm on device before fixing** (don't guess — same lesson as OBD-70's device-only crash): park repro
(or bench: dongle powered, ECU absent), `adb logcat` + `dumpsys power | grep -i wake` + `dumpsys activity
services` over ~20–25 min, capturing: does the link stay `Ready`? does the poll loop keep running? does
OBD-69's watchdog fire at 20 min and release the wake lock + disconnect? what state is the link in when
Connect fails? The Pixel is reachable at adb `192.168.1.176:5555`; the drain is on the Garmin + van dongle.

**Fix direction (pending confirm):** distinguish "brief silent ECU on a live dongle" (stay `Ready` a short
while) from "prolonged silence / dongle gone" (drop to `Disconnected`, let OBD-69 idle-stop tear everything
down INCLUDING the BLE link); make **Connect force a fresh teardown+reconnect** rather than no-op on a stale
`Ready`; ensure the idle watchdog stops the link, not just the service. Must not regress OBD-69.

**Note:** OBD-74 (quit-on-disconnect prompt) does NOT fix this — the drain is backgrounded/overnight, so a
foreground countdown no one sees can't help. This (OBD-71) is the actual drain fix.

## Out of scope

- Full trip-detection / motion-based auto-start (a heavier feature; this is just the overnight-cold
  reconnect gap).
