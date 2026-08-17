---
id: OBD-69
title: Idle battery-saver — stop the connection service after 20 min of no dongle data
module: app
owner: ui-agent
sprint: reliability
status: open
type: feature
hardware-verify: true
blocked-by: []
branch: feat/69-idle-battery-saver
---

## What

Auto-stop the background OBD connection service after a configurable idle timeout (**default 20 minutes**)
of receiving no data from the dongle, so a phone/tablet left running the app with the vehicle off does
not have its CPU held awake all night. When the timeout fires, the service releases its wake lock, cancels
the reconnect loop, and drops its foreground notification — letting the device Doze normally. Reopening
the app restarts the connection.

## Why (root cause — confirmed in code, not speculative)

Reported symptom: a Garmin Overlander tablet (Android 6.0.1 / API 23, our sideloaded second-display
target — see [[garmin_overlander_sideload]]) was drained overnight while running this app; it normally
isn't. The mechanism is in `app/src/main/kotlin/com/revel/obdgauge/app/service/ObdConnectionService.kt`:

- It is a **foreground service** that acquires a **`PowerManager.PARTIAL_WAKE_LOCK`** for its lifetime —
  purpose: "screen may turn off, **CPU must not sleep**" — so it can keep polling with the screen off.
- The wake lock's safety-net timeout is **`WAKE_LOCK_TIMEOUT_MILLIS = 12 * 60 * 60 * 1000` — 12 hours**
  (`ObdConnectionService.kt:271`). The persistent foreground notification also exempts the process from
  Android **Doze** (introduced in API 23, so it applies on the Garmin).
- `core/ble/.../ReconnectPolicy.kt` retries with exponential backoff but **never gives up** — with the
  van off and the dongle unpowered/unreachable, it keeps scanning (spaced out, but forever) while the
  wake lock keeps the CPU awake.
- The service only stops today on `onTaskRemoved` (user swipes the app away) or the notification's Stop
  action → `stopSelf()`. Left simply open, it runs until the 12-hour wake lock expires or the battery dies.

Net: app open + vehicle off = CPU held awake for up to 12 hours retrying a dongle that isn't answering.
That is the overnight drain.

## Implementation

**Reuse the existing teardown — only add the trigger.** `stopSelf()` → `onDestroy` already releases the
wake lock (`ObdConnectionService.kt:299`) and tears down the poll loop (`PollKeepAlive.release`); the
reconnect job cancels with the service scope. The new work is an idle watchdog that calls the existing
stop path.

1. **Track last-data-received time.** Add a signal updated whenever a valid sample/response arrives from
   the dongle (the data source publishes samples; surface a `lastDataAtMillis` the service/controller can
   read — via `PollKeepAlive`/`ConnectionServiceController` or a small shared holder). Reset on every
   received sample; initialize to service-start time (so "never connected" also counts toward the timeout).
2. **Idle watchdog** (pure, unit-testable decision + a coroutine that drives it). A `Clock`-injected
   pure function: given `lastDataAtMillis`, `now`, and `idleTimeout`, decide `shouldStop`. A watchdog
   coroutine in the service/controller checks it on a coarse interval (e.g. every 60 s via
   `withFrameNanos`-free `delay`); when it returns true, call the service's existing stop path
   (`stopSelf()` after `stopForeground`). Keep the check pure so it's testable with a fake clock — do NOT
   bury the timing logic in the coroutine.
3. **Config:** `IDLE_TIMEOUT_MILLIS` default `20 * 60 * 1000`. Define as a named constant now; a
   user-facing setting is out of scope for v1 (leave the seam).
4. **Backstop:** drop `WAKE_LOCK_TIMEOUT_MILLIS` from 12 h to ~**25 min** (just above the idle timeout),
   so even if the watchdog ever fails the CPU can't be held all night. The watchdog is the primary fix;
   this is belt-and-suspenders.
5. **Resume:** confirm that after an idle-stop, reopening the app (Activity launch/`onResume` →
   `ConnectionServiceController` re-`startForegroundService`) restarts the service and reconnects cleanly.
   This is the normal cold-start path; verify it isn't broken by the new stop.
6. **Foreground state on stop:** clearing the notification (via `stopForeground`) is the default. Optional
   nicety: if the app is in the **foreground** when the idle-stop fires, show a "Paused to save battery —
   tap to reconnect" state instead of silently going idle (so a user actively watching isn't confused).
   Keep v1 minimal; a non-foreground stop just clears cleanly.

Do NOT force-kill the app process — Android apps don't self-kill cleanly, and it isn't needed. Releasing
the wake lock and stopping the foreground service is what ends the drain; an idle app with the screen off
and the CPU free to Doze costs nothing.

## Testing

- **Pure unit** (`app/src/test/...` and/or `core`): the idle-decision function with a fake `Clock` — no
  data for > timeout ⇒ stop; recent data ⇒ keep; timer resets on new data; counts from service start when
  no data ever arrives. Off-by-one at exactly the timeout boundary.
- **Service/lifecycle** (Robolectric, extend the existing `ObdConnectionService`/reconnect tests): the
  watchdog calls `stopSelf` after the timeout; the wake lock is released and `PollKeepAlive` released on
  that path; a data arrival before the timeout prevents the stop.
- **Device (🖐 Taras — the real acceptance):** leave the app open with no dongle (or dongle unpowered);
  confirm within ~20 min the service stops — foreground notification clears, and the wake lock is gone
  (verify via `adb shell dumpsys power | grep -i wake` showing the OBD wake lock no longer held, and the
  device is allowed to Doze). Then the real test: **leave it overnight on the Garmin and confirm the
  battery no longer drains.** Also confirm reopening the app reconnects normally. (Before building, worth
  a one-time check: Garmin **Settings → Battery** to confirm the OBD app is the top overnight consumer.)

## Done when

Gate green; the idle-decision + service-stop path is unit- and Robolectric-tested; the 12 h wake-lock
timeout is reduced to a ~25 min backstop; and **Taras confirms on the Garmin overnight that the drain is
gone and reopening reconnects** (`hardware-verify: true` — never auto-closed).

## Out of scope (v1)

- User-configurable timeout UI (leave the constant seam).
- Auto-resume on detected vehicle power / motion (reopening the app is the resume path for now).
- Force-killing the app process.
