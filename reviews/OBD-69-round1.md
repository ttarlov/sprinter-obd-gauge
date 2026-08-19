---
issue: OBD-69
round: 2
reviewer: rev-platform
verdict: approved
gate: green
reviewed-commit: 41e9129
covers: [OBD-69]
---

> **reviewed-commit advanced 73f6027 → 41e9129 (staleness bookkeeping).** Production code is
> **unchanged** since the round-2 approval at 73f6027. Two commits landed on top, neither touching prod
> logic: `7690fe6` tightened the no-ref-count-leak Robolectric test per the round-2 "New minor" below
> (reviewer-directed, test-only); `41e9129` added the `## Hardware checklist` to the issue after Taras's
> Garmin drive-verify (docs-only). reviewed-commit points at 41e9129 so `merge.sh`'s staleness check sees
> only `reviews/` changes after it. The approved diff stands.

# OBD-69 round 2 — idle battery-saver (delta review of fix 73f6027)

**Verdict: approved.** Both round-1 must-fixes are resolved and the ref-count/refresh reasoning
holds at the Android-contract level. See the round-2 delta section at the bottom; the original
round-1 findings are preserved below it for the record.

---

# OBD-69 round 1 — idle battery-saver

The core of this change is right. The idle **decision** is pure, correctly bounded, and well-tested;
the **coroutine lifecycle** is clean (watchdog on `serviceScope`, cancelled in `onDestroy`, fires
exactly once); and the **idle-stop path reuses the proven user-stop teardown**, so the wake lock and
`PollKeepAlive` are released on the `onDestroy` that `stopSelf()` reaches. The "collect `readings`,
stamp only on non-empty" signal is the correct choice and does exactly what the KDoc claims — a
never-connected session publishes `emptyMap` (init fails → `publish(forceStale=true)` over empty
samples) and never stamps, so it trips from service start.

But the belt-and-suspenders backstop — dropping the wake lock from 12 h to a **25-min timeout that is
never refreshed** — silently regresses the one scenario the wake lock exists to protect: a real drive
with the screen off for longer than 25 minutes. That's the blocker. Details below, most severe first.

---

## Findings

### [blocker] `ObdConnectionService.kt:144` + `:317` + `:340` — the 25-min wake lock is never re-acquired, so it caps a live screen-off drive, not just an idle session

The wake lock is acquired **once**, in `onCreate` (`acquireWakeLock` → `acquire(WAKE_LOCK_TIMEOUT_MILLIS)`,
line 340), `setReferenceCounted(false)`, and is never re-acquired or refreshed anywhere. `acquire(timeout)`
auto-releases the lock `timeout` ms after that single call — an **absolute** cap measured from service
start, independent of whether data is flowing.

The idle path is fine: the watchdog fires at ~20–21 min (idle timeout + up to one 60 s tick), which is
*before* the 25-min lapse, and holds the CPU awake long enough for its own `delay`-based loop to run. So
for the targeted "vehicle off, dongle unreachable" case the fix works and releases everything cleanly at
`onDestroy`.

The problem is the **live** case. On a genuine drive, data flows, the idle timer keeps resetting, and the
service correctly stays up — but the wake lock still lapses at a fixed 25 minutes because nothing refreshes
it. This directly contradicts the wake lock's documented purpose (this file's own Doze KDoc, lines 69–89,
and OBD-24's "10-min screen-off AC"): *"screen may turn off, CPU must not sleep... acquired for the
service's entire lifetime."* Per that same KDoc, once the `PARTIAL_WAKE_LOCK` is gone with the screen off,
"a plain `delay()`-scheduled coroutine poll loop stops firing within seconds" of CPU suspend.

**Failure scenario:** Garmin dash mount, driving, screen timed off (the explicitly-supported screen-off
polling case). At 25 minutes the wake lock auto-releases; the CPU is free to suspend between radio events;
the poll loop stalls; gauges freeze on stale data until the screen comes back on and the OS re-holds a
wake lock. No test covers this (Robolectric doesn't model CPU suspend, per the KDoc), and it fails
silently. The old 12 h value avoided this precisely because it exceeded any plausible session; 25 min sits
*inside* the normal operating range, so the backstop now overlaps live use.

The spec did ask for "~25 min," but it framed it as a pure backstop "even if the watchdog ever fails" — it
didn't account for the lock never being refreshed, which makes the same timeout also a hard cap on real
sessions. Faithfully implementing the number reproduced the spec's blind spot.

**Fix (small):** refresh the lock while the session is genuinely active, so it lapses ~25 min after data
*stops* rather than 25 min after service *start*. Cleanest: re-acquire on each non-empty stamp inside
`launchDataIdleReset` (re-`acquire(WAKE_LOCK_TIMEOUT_MILLIS)` on the same non-reference-counted lock resets
its timer), or pass a `refresh: () -> Unit` into the watchdog and call it whenever `checkIdleAndMaybeStop`
returns `false`. Either keeps a live drive awake indefinitely while still letting an idle session's lock
lapse as the belt-and-suspenders backstop. Add a Robolectric assertion that a data stamp re-holds the lock.

### [major] `ObdConnectionService.kt:358-368` — `launchDataIdleReset`'s `isNotEmpty` guard (a load-bearing correctness decision) has no direct test

The whole soundness of the signal rests on two behaviors of this collector: an **empty** emission must
*not* stamp (or a never-connected session's `emptyMap` publishes would perpetually reset the timer and the
service would never idle-stop), and a **non-empty** emission must stamp (or a live drive would wrongly
idle-stop). Both live only in the `if (readings.isNotEmpty())` guard at line 365. Neither Robolectric test
asserts it: both drive `checkIdleAndMaybeStop` *directly*, bypassing the collector. The demo fake's live
emissions do run the collector (the tests even compensate for it by reading `now` relative to the tracker's
current stamp), but nothing pins that an empty map leaves the stamp untouched. A future refactor could drop
or invert the guard and every test stays green while the never-connected drain case silently breaks.

**Fix:** a plain unit test over the guard (extract the `isNotEmpty → record` decision, or a
coroutines-test that feeds a fake `readings` StateFlow `emptyMap → non-empty → emptyMap` and asserts the
tracker only advanced on the non-empty step).

### [minor] `ObdConnectionService.kt:364` — the reset signal is "map non-empty," not "data is fresh"; correct here only because `StateFlow` dedupes

Worth recording so it isn't mistaken for a general "activity" signal. `RealVehicleDataSource` republishes
the *entire* readings map every poll cycle, including when every value is stale-but-unchanged. That does
**not** keep the timer alive only because `StateFlow` suppresses equal emissions: once all readings have
gone stale and stopped changing, the map compares equal and the collector stops receiving — so the timer
ages and the watchdog can still fire on a connected-but-flatlined dongle. Good outcome, but it depends on
`StateFlow`'s distinct-until-changed and on staleness being the only thing that mutates a parked map. If a
future change makes `publish` vary a field each cycle (e.g. a per-publish timestamp), a parked-but-alive
loop would stamp forever and the drain would return. Consider a comment noting the dependence, or stamp on
a monotonic "new sample count" rather than map non-emptiness.

### [nit] `ObdConnectionService.kt:154` — the onCreate stamp races the controller's first emissions, harmlessly

`idleTracker.record(elapsedRealtime())` (154) runs after `controller.start()` (151) has already called
`dataSource.start()`, and before `launchDataIdleReset` subscribes. Any emissions between are latest-value
so the collector picks up the current map on subscribe; the explicit stamp and the first collected stamp
are both ~now. No defect — noting only that the "count from start" initialization is doing its job via two
near-simultaneous writes, which is fine given `@Volatile`.

---

## Fix list (must-fix before approve)

- [x] **[blocker]** ✅ Fixed in 73f6027. New top-level `refreshWakeLock(wakeLock)` re-`acquire`s the
  (non-reference-counted) lock, wired into the readings collector via a `refresh` lambda passed to
  `launchDataIdleReset` and applied only on non-empty samples. The 25-min timeout now lapses ~25 min after
  data *stops*, so a live screen-off drive stays awake indefinitely while an idle session's lock still
  lapses as the backstop. Reasoning verified — see round-2 delta.
- [x] **[major]** ✅ Fixed in 73f6027. The guard is extracted to the pure
  `applyReadingsToIdleSignal(readings, now, tracker, refresh)` and the collector routes through it (no
  parallel path). Two `IdleWatchdogTest` cases assert both halves: empty leaves the stamp stale and does
  not refresh; non-empty stamps and refreshes.

## Verified this round (no change needed)

- ✅ **Idle decision** (`shouldStopForIdle`): `>=` boundary inclusive, counts from service-start for
  never-connected, resets on new data — all 7 pure cases correct; monotonic `SystemClock.elapsedRealtime`
  used consistently for the stamp (154, 365), the watchdog `now` (156), and the check. `elapsedRealtime`
  (counts deep sleep) is the right clock for a wall-time idle gap.
- ✅ **Coroutine lifecycle / no leak:** watchdog and readings collector both on `serviceScope`, cancelled in
  `onDestroy`; watchdog `break`s on first `true` and `stopSelf → onDestroy → serviceScope.cancel()` also
  cancels it — fires exactly once, cannot outlive the service.
- ✅ **Idle-stop reaches the release:** `checkIdleAndMaybeStop` → `disconnectLinkOnUserStop()` +
  `stopForeground(REMOVE)` + `stopSelf()` → `onDestroy` releases the wake lock and `PollKeepAlive`. No path
  fires the watchdog while skipping `onDestroy`. Robolectric test pins the release-on-`onDestroy` timing.
- ✅ **Reconnect disarm:** the idle-stop reuses `disconnectLinkOnUserStop` (same order as the ACTION_STOP
  path), off-`serviceScope` one-shot so the cancel doesn't kill the disconnect; no-op on demo
  (`linkController` empty); does not touch the user-Stop / `onTaskRemoved` / cold-start paths.
- ✅ **Watchdog can't fire on a live connection:** a live source publishes non-empty readings each cycle →
  stamps → never idle; only a parked/unreachable dongle (loop returns, emissions stop) ages the timer.
- ✅ **Resume & regressions:** cold `onCreate` re-arms the watchdog + re-acquires the lock + reconnects;
  `START_NOT_STICKY`, `onStartCommand`, `onTaskRemoved` unchanged; `postNotification` refactor
  (`areNotificationsSafeToPost` inlined) is behavior-preserving.

---

# Round 2 — delta review of 73f6027 (APPROVED)

Scoped to `git diff 5bd96aa..73f6027`. Both must-fixes resolved; the round-1 minor is now documented
in the code.

## Blocker — verified fixed, and the ref-count reasoning holds

`refreshWakeLock(wakeLock)` calls `wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MILLIS)`, wired into the readings
collector (`launchDataIdleReset(..) { refreshWakeLock(wakeLock) }`) and invoked only on non-empty samples
via `applyReadingsToIdleSignal`.

- **Non-reference-counted acquire semantics — correct.** The lock is `setReferenceCounted(false)`
  (unchanged, `acquireWakeLock`). Per Android's contract, with reference counting off, `acquire()` on an
  already-held lock is idempotent and only re-arms the auto-release timer, and a *single* `release()`
  clears it no matter how many `acquire()` calls preceded. So repeated refreshes cannot stack a count, and
  `onDestroy`'s one `releaseWakeLock` (`if isHeld release()`) fully clears it. No leak.
- **Net behavior — correct.** Live drive: data every few seconds → timer re-armed continuously → never
  lapses (screen-off drive stays awake indefinitely, satisfying the Doze KDoc / OBD-24). Data stops:
  no non-empty emissions → no refresh → lock lapses ~25 min after *last data*, while the watchdog fires
  first at ~20 min and releases via `onDestroy`. If the watchdog ever fails, the lock self-releases at
  ~25 min after last data. The backstop and the primary fix no longer overlap live use.
- **Post-`onDestroy` race — safe.** The `refresh` lambda reads the `wakeLock` field at call time and
  `refreshWakeLock` is null-safe; `onDestroy` nulls the field before `serviceScope.cancel()`, so a late
  emission is a no-op. The one narrow window (collector reads the old reference just after
  `releaseWakeLock`) would re-hold a lock that self-releases via its own timeout — bounded and documented.
- **Empty does not refresh — correct and important.** `applyReadingsToIdleSignal` returns before both the
  stamp and the refresh on an empty map, so a never-connected session never re-arms the lock; its single
  onCreate acquire (25 min) is out-lived by the ~20-min watchdog stop.

## Major — verified fixed

Guard extracted to the pure `applyReadingsToIdleSignal`; the collector routes through it (no un-tested
parallel path). `IdleWatchdogTest` pins both halves directly: empty leaves the stamp at `START` and
`refreshed == false`; non-empty advances the stamp and sets `refreshed == true`. The never-connected
idle-stop guarantee is now protected by an assertion a refactor can't silently break.

## Round-1 minor — now documented

The `launchDataIdleReset` KDoc now spells out that a connected-but-flatlined dongle still idles out only
because `StateFlow` dedupes equal emissions (a parked loop republishes an equal map → collector stops
receiving → stamp ages), and flags the future hazard (a per-cycle-varying field would stamp forever, so
stamp on a monotonic sample count if so). Accurate description; resolves the round-1 minor.

## New minor (non-blocking) — the Robolectric ref-count test doesn't exercise the case its name claims

`a data sample re-holds the wake lock, and one release still clears it - no ref-count leak` does
`release() → refreshWakeLock (acquire) → destroy (release)` — a balanced acquire/release pair that never
*stacks* two acquires without an intervening release, so it ends `isHeld == false` even for a
hypothetically reference-counted lock. It verifies the useful thing (refresh re-holds a lapsed lock;
teardown clears it), but it would not catch a regression that flipped `setReferenceCounted` to `true`. To
actually pin the no-leak property, drop the leading `release()`: refresh while still held (from onCreate),
then assert a single `destroy()` release leaves `isHeld == false`. The guarantee itself is sound via the
Android contract; this is test-strength only, not a defect in the fix. Non-blocking.
