---
id: OBD-74
title: Quit-on-disconnect — 20s countdown prompt when comms end, then fully quit (toggleable)
module: app
owner: ui-agent
sprint: reliability
status: open
type: feature
hardware-verify: true
blocked-by: []
branch: feat/74-quit-on-disconnect
---

> **SUPERSEDED BY OBD-71 (2026-08-24).** The device repro proved comms are NOT lost when parked — the dongle
> keeps answering, so a "comms lost" trigger never fires. OBD-71's fix re-uses this issue's prompt+countdown
> idea but triggers it on **engine-off (RPM==0)** instead, with a silent auto-stop when the user is absent
> (the actual overnight-drain fix). Do not build OBD-74 separately; it's folded into OBD-71.

## What

When OBD communication is confirmed lost (Taras shuts the engine off), **immediately** show a foreground
dialog: *"Lost connection — keep the app running?"* with a **20-second countdown** and a **Keep running**
button. If the user taps it, dismiss and stay up (re-arm on the next disconnect). If the countdown reaches
zero with no tap, the app **fully quits** — service stopped, wake lock released, notification cleared,
screen-on flag dropped, activity finished + removed from recents. A **settings toggle** turns the whole
behavior on/off.

Filed by Taras 2026-08-24 — he is **still seeing battery drain** after OBD-69, and wants an aggressive,
user-in-the-loop teardown the moment the van is parked.

## Why (and why it likely fixes the residual drain)

OBD-69 stops the background **service** (and releases its wake lock) after **20 minutes** of no data. Two
gaps remain that this addresses:

1. **The screen.** OBD-69 does nothing about the foreground Activity. Dash-mounted with `keepScreenOn`
   (OBD-24) and the app in front, engine off → the **screen stays lit and awake**, which is a large drain
   that stopping the *service* never touches. Fully quitting drops the `keepScreenOn` flag and the UI. This
   is the most likely source of the residual drain — verify on-device (see below).
2. **The 20-minute window.** Even for the service, OBD-69 holds the wake lock + keeps the reconnect loop
   scanning for up to 20 min after every engine-off. A 20-**second** prompt collapses that window when the
   user is present.

So this is the **fast, foreground, user-vetoable** layer; OBD-69's idle watchdog remains the **backgrounded
backstop**. They must be coherent, not fight (see below).

## Implementation

- **Detect "comms lost."** Drive off the existing `LinkState` / `VehicleDataSource.connection` +
  `readings`. Trigger = transition from a had-comms state to disconnected / no-data, **debounced** by a
  short confirmation window (≈5–10 s of confirmed no-comms) so a transient BLE blip mid-drive does NOT pop
  the dialog. Only arm after comms were actually established (never on the initial connect attempt). Pure,
  testable decision (a `Clock`/state function), same discipline as OBD-69's `IdleWatchdog`.
- **Foreground-only dialog.** The countdown dialog only makes sense when the Activity is resumed and the
  screen is on. If comms drop while **backgrounded / screen-off**, there's no one to see it → fall through
  to OBD-69's automatic path (or, when this toggle is on, a faster auto-teardown without the dialog since
  the "Keep running" veto is meaningless with no viewer). Decide at build: the clean rule is *dialog if
  foreground, silent auto-teardown if not*.
- **The countdown.** 20 s (named constant), visible ticking. `Keep running` → dismiss, stay up, re-arm for
  the next disconnect. Timeout → teardown.
- **"Fully quits" = clean teardown, not a hard kill.** Reuse OBD-69's service-stop path (`stopSelf` →
  `onDestroy` releases the wake lock + `PollKeepAlive`), clear the foreground notification, drop
  `keepScreenOn`, then `finishAndRemoveTask()` on the Activity so it's gone from recents. Do **NOT**
  `Process.killProcess` / `System.exit` (looks like a crash, discouraged, and unnecessary — a backgrounded
  app with no wake lock and no screen costs ~nothing). Note the option in case Taras insists on the process
  literally disappearing, but the battery goal is met by the clean teardown.
- **Toggle** in `AppSettings` (default **OFF** — auto-quitting is surprising behavior to ship on by default;
  Taras opts in). Persist via `SettingsRepository.update` + `SettingsCodec`, switch in `SettingsScreen`.
- **Coherence with OBD-69 / OBD-71:** reuse the same teardown; make sure the new quick teardown and the
  20-min watchdog can't double-fire or race. Note the [[obd_gauge_project]] OBD-71 overnight-reconnect quirk
  — a full quit here means the next start is a cold start (same relaunch OBD-71 is about); keep them
  consistent.

## ⚠️ Pair this with an actual drain diagnosis (don't ship blind)

"Still draining" + OBD-69 supposedly working = there's a real culprit to name, and if the app drains while
**backgrounded/screen-off** (overnight), this foreground prompt won't help (no one taps it). Before/with the
build, confirm the source on the real device: **Garmin Settings → Battery** (is the OBD app the top overnight
consumer?) and `adb shell dumpsys power | grep -i wake` / `dumpsys batterystats`. If it's the screen-on-while-
parked case, this feature nails it; if it's a held wake lock while backgrounded, that's an OBD-69 hole to fix
too (tie to OBD-71). Name the drain, then confirm the fix removes it.

## Testing

- Pure unit: the comms-lost decision (debounce window, arm-only-after-connected, no-fire on transient blip)
  with a fake clock/state — mirrors `IdleWatchdogTest`.
- Robolectric: teardown path (service stopped, wake lock released, notification cleared, `finishAndRemoveTask`
  called) on timeout; `Keep running` cancels it; foreground-vs-background gating.
- Roborazzi: the countdown dialog.
- Device (🖐 Taras — the real gate): engine off with the app foreground + toggle ON → dialog + 20 s → app
  quits, **screen goes off**; `Keep running` keeps it up; and the **overnight battery drain is gone** (the
  actual acceptance). `hardware-verify: true`.

## Out of scope

- Configurable countdown length / custom message (v1 = fixed 20 s).
- Auto-restart on reconnect / motion detection (that's the OBD-71 direction).
- Hard process-kill (`Process.killProcess`) unless Taras explicitly wants it.
