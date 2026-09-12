---
issue: OBD-78
round: 1
reviewer: rev-ble
verdict: changes-requested
gate: green
reviewed-commit: 6452c2e
covers: [OBD-78]
hardware-verify: true
---

# OBD-78 — data-liveness reconnect watchdog — round 1

**Verdict: changes-requested.** One blocker. The state-machine integration is careful and I could
not break it on any of the interleavings I walked — confinement, double-fire, stale teardown,
generation gating and recoverability are all correct. What is wrong is the **fire predicate**: the
refactor from a windowed loop to a one-shot timer (`6452c2e`) silently dropped the "we are still
being polled" half of the condition, so the watchdog now tears down a **healthy, Ready link** on the
ordinary path of the user backgrounding the app. That is the exact class of bug this issue exists to
remove, aimed at the wrong target.

Gate: I re-ran `tools/gate.sh` myself → **PASS** on all 7 checks (assembleDebug, test, ktlintCheck,
detekt, verifyRoborazzi, assembleDemoDebug, module-isolation). The blocker is a behaviour bug the
suite does not cover, not a red build.

---

## BLOCKER B1 — a leftover arm tears down a healthy link once polling stops

`core/ble/src/main/kotlin/com/revel/obdgauge/ble/BleObdLink.kt:451-458` (arm/cancel in `sendRaw`)
with `:467-483` (`armLiveness`).

The issue spec defines the fire condition as a window that **saw attempts but no responses**
(`issues/OBD-78.md:36-41`), and states the invariant twice: *"No `sendRaw` in a window →
`sawAttempt` false → never fires, so a backgrounded/not-polling `Ready` link is left alone"*
(`:47`), and the OBD-71 composition claim *"Engine off → no `sendRaw` → this watchdog idle"*
(`:57`).

The shipped one-shot measures something different: **time since the first unanswered attempt**. It
knows nothing about attempts made after it was armed, and only one exit disarms it —

```kotlin
armLiveness(active)
active.send(command, timeout).also {   // <-- .also is on the VALUE
    livenessJob?.cancel()
    livenessJob = null
}
```

`.also` runs only when `send` **returns**. Two ordinary exits skip it and leave the job armed:

1. `send` throws — `LinkError.Timeout` on a silent ECU, which is the very case the arm exists for.
2. the caller is **cancelled** inside `send` — `withTimeout(deferred.await())`
   (`gatt/GattSession.kt:159`) is a cancellable suspension; the `CancellationException` unwinds
   through `withContext(dispatcher)` and `commandMutex.withLock`, and `.also` never runs.

If no further `sendRaw` arrives within `livenessTimeout`, the timer fires against a session that is
alive, `Ready`, and answering fine. `session === target` is still true — the guard is about
identity, not health — so `onSessionTerminated(LIVENESS_STALL …)` runs, `Error` is published,
`retrying` goes true, the GATT client is closed and a reconnect is booked.

### This is the mainline background path, not a corner

`DashboardViewModel.uiState` is `stateIn(..., WhileSubscribed(STOP_TIMEOUT_MILLIS))` with
`.onCompletion { stopUnlessKeptAlive() }` (`app/.../gauge/DashboardViewModel.kt:114-121`), and
`RealVehicleDataSource.stop()` is `session?.cancel()` with the KDoc *"Cancels the poll loop.
Idempotent; **does not touch the link**"* (`core/protocol/.../RealVehicleDataSource.kt:130-135`).
So: leave the dashboard without a foreground-service keep-alive → 5 s later the poll job is
cancelled, statistically most often *inside* a `sendRaw` (`PollSchedule`: 200 ms `cycleInterval`,
2 s `commandTimeout`, ~6 PIDs a cycle) → 25 s later a perfectly good link is torn down.

Variant 2 needs no cancellation at all: one command times out, then polling stops for any reason —
which is precisely OBD-71's engine-off teardown, and the interaction the issue claims composes.

### Proof

Throwaway probe against this HEAD (written, run, deleted — nothing added to the branch). Fake
dongle answering `OK` normally throughout; only the poll loop stopped:

```
probe B — healthy dongle, poll loop cancelled mid-command
  states: [Ready, Error(cause=Unknown(message=liveness stall: no answer in 25s)), Connecting, Ready]
  transport.closeCount: 1
probe A — one timed-out command, then no polling at all
  liveness: 25s of poll commands unanswered on a Ready link; forcing a drop
  reconnect scheduled: attempt 1 of 720 in 1s (cause Unknown(message=liveness stall: …))
```

Cost per occurrence: a GATT client closed and reopened, a transient `Error` + `retrying` banner, a
full re-handshake, and `:core:protocol`'s ELM327 init re-run on the next `start()`. Under
`PollKeepAlive` it is masked (polling continues, so a success disarms).

### Note on the fix (not prescribing the shape)

A plain `try { … } finally { livenessJob?.cancel() }` would be worse, not better: it disarms on
every timed-out command and **disables the watchdog entirely**. Re-arming on each attempt instead of
only the first does not help either — it still fires a window after the *last* attempt, whatever
made that the last one. The predicate genuinely needs the attempts-in-window notion back: the timer
must only be allowed to fire if the link is *still being asked something*. The cheapest honest form
is the spec's own — a `lastAttemptAt`/`sawAttempt` the fire path checks — but that is the
implementer's call.

---

## What I verified and could NOT break

1. **Confinement (§1) — clean.** Production `@LinkDispatcher` is a single-thread executor
   (`di/BleModule.kt:78-84`); tests use `StandardTestDispatcher`. Every write to `livenessJob` is on
   it: `armLiveness` is reachable only from `sendRaw`'s `withContext(dispatcher)` body; the `.also`
   runs after `send` resumes, still inside that `withContext`; `release()`'s clear (`:636-637`) is
   reached only from dispatcher-confined callers (`disconnect`/`attemptConnectOnce`/`openSession`/
   `onSessionTerminated`). The launched body runs in `sessionScope = CoroutineScope(dispatcher + …)`
   (`:559`), so its `session === target` read and `onSessionTerminated` call are on the dispatcher
   too. No `@Volatile` needed, no data race. `sessionScope` and `session` are assigned and cleared as
   a non-suspending pair, so `armLiveness`'s `sessionScope ?: return` can never pick up a scope
   belonging to a different session.
2. **Single-flight vs the arm/cancel pair — clean.** `commandMutex` means one `sendRaw` body at a
   time, so the check-then-arm and the `.also` cancel cannot interleave with another caller. The
   `.also` can never cancel a *newer* session's timer: reaching a new session requires a `release()`
   (which nulls the field) plus another `sendRaw`, and that one is blocked on the mutex behind the
   in-flight call.
3. **Double-fire with a real drop — impossible, both orders.** Liveness first: `release()` →
   `GattSession.close()` sets `terminated = true` and `ready = false`, and `terminate()` early-returns
   on `terminated` and only invokes `onTerminated` `if (wasReady)` — so a later real `Disconnected`
   cannot re-enter. Real drop first: `onSessionTerminated` → `release()` cancels and nulls
   `livenessJob` before it can run.
4. **Stale teardown after `disconnect()` / a newer `connect()` / `release()` — prevented, twice
   over.** `disconnect()` bumps generation then `release()`s with no suspension between (the
   invariant `onSessionTerminated`'s KDoc at `:602-613` documents), and `release()` cancels the job.
   `connect()` → `attemptConnectOnce()` `release()`s *before* `++generation` (`:297-298`). Even with
   the job's continuation already queued on the single dispatcher thread, `delay` is cancellable, so
   a cancelled job never runs its body. `session === target` is a real second net rather than
   decoration, and `publish`/`scheduleReconnect`'s generation gates are the third.
5. **`forgetRememberedDevice()` — consistent, not a bug.** It bumps generation *without* releasing a
   live session (`:212-226`), so an armed timer survives it. If it fires: `session === target` holds,
   `publish(generation, …)` passes, and `scheduleReconnect` refuses at `!autoReconnect` (the forget
   disarmed it). Result is `Error` + release with no retry — identical to a real drop after a forget.
6. **Recoverability (§5) — correct.** `LinkError.Unknown` → `classifyUnknown` →
   `else -> RECOVERABLE` (`ReconnectPolicy.kt:118-124`). `"liveness stall:"` collides with none of
   the three terminal/special prefixes (`LOCATION_OFF`, `BLE_UNSUPPORTED`, `THROTTLED`). Observed
   live in the probe: `reconnect scheduled: attempt 1 of 720 in 1s`.
7. **Virtual-clock termination (§6) — the scrapped design is not back.** One-shot, no
   self-rescheduling `delay`; `armLiveness` is unreachable except from `sendRaw`, so an idle `Ready`
   link runs **zero** liveness coroutines and `advanceUntilIdle()` cannot hang. Confirmed by the
   third test and by reading every call site.
8. **No single command can self-trip.** Longest production command timeout is `InitStateMachine`'s
   `verify = 10.seconds` (`:145`); poll commands are `2.seconds`. Both are comfortably inside a 25 s
   window, so a legitimately slow `0100`/`SEARCHING…` cannot be killed mid-flight.

---

## Minor / non-blocking

- **m1 (robustness, `BleObdLink.kt:468`).** The guard is `livenessJob != null`, not "is active".
  Today every cancel path also nulls, so it holds. But a `release(target, scope)` where
  `target !== session` skips the clear at `:631-638` while `scope.cancel()` still kills the job —
  leaving a non-null dead job that **permanently disables the watchdog for the rest of that
  session**, silently. No such call site exists now (all explicit `release(opened, scope)` calls pass
  the just-assigned `session`), but `livenessJob?.isActive != true` costs nothing and cannot rot.
  The B1 fix will touch this line anyway.
- **m2 (fragility, `:636` / `:473-482`).** On the fire path the job cancels *itself* — `release()`
  cancels `livenessJob` and then `sessionScope`, both from inside the job's own body. It works only
  because nothing after either cancellation suspends. That is the same hazard `onSessionTerminated`'s
  KDoc spells out for the `GattSession` callback, and it deserves the same explicit warning in
  `armLiveness`: adding any suspension point inside the launched body after `onSessionTerminated`
  would skip `target.close()` and leak a GATT client interface.
- **m3 (test coverage of the contract).** `BleObdLink.LIVENESS_STALL` has **zero references outside
  its own declaration** — its KDoc claims it is stable "so a test (and a drive log) can match on it",
  but no test does. All three tests assert on `RecordingLogger` strings only; none asserts the
  published `LinkState.Error` cause or `link.retrying`. Those are what actually pin the RECOVERABLE
  classification the feature depends on.
- **m4 (stale comment).** `LivenessWatchdogTest:88` — `link.disconnect() // stop the
  (production-perpetual) watchdog so runTest can settle`. Left over from the pre-`6452c2e` loop; the
  watchdog is one-shot now and the comment says the opposite of the KDoc at `BleObdLink.kt:140-147`.
- **m5 (churn, only if the fix keeps this shape).** Every `sendRaw` on a healthy link `launch`es and
  immediately cancels a coroutine. At the default `PollSchedule` that is tens of launch/cancel pairs
  per second on the link thread for a timer that is only interesting when nothing is answering —
  small, but it lands on the Garmin Overlander (API 23).

---

## Test adequacy (§7)

The three tests are well-constructed and each proves what it claims — wedge fires + books a
reconnect, healthy traffic never fires, idle-unpolled never fires. They just do not cover the
*transition between* the second and third cases, which is where B1 lives.

Concretely missing:

1. **The B1 regression, both shapes** — (a) one timed-out command then zero polling; (b) a healthy
   in-flight command whose caller is cancelled (`WhileSubscribed` teardown), then the dongle answers
   fine. Assert: no `liveness` log, no `reconnect scheduled`, state still `Ready`, `closeCount`
   unchanged, a full window later.
2. **A late success cancels a pending fire** — poll silently to `window − 1s`, then answer; assert no
   teardown at `window + 1s`. The "cancelled on any success" claim is currently only tested against
   traffic that was *never* silent.
3. **A real drop while armed** — `transport.dropConnection()` mid-window; assert exactly one `Error`,
   exactly one `reconnect scheduled`, and that nothing further fires a window later against the
   *new* session.
4. **`disconnect()` mid-window** — assert no `liveness` log and no reconnect a full window after the
   disconnect. This is the direct test of the `release()` disarm + `session === target` guard.
5. **`livenessTimeout = Duration.ZERO` disables it** — the documented off-switch
   (`BleConfig.kt:73`) has no coverage at all.
6. **The cause, not the log** — assert `state.value` is `Error` whose `LinkError.Unknown.message`
   starts with `BleObdLink.LIVENESS_STALL`, and `link.retrying == true`. That is what pins §5.
7. **A slow command does not self-trip** — a `sendRaw` with a timeout near `livenessTimeout` that
   answers just in time.

---

## Fix list

- [ ] **B1 — liveness fires on a healthy link once polling stops.** `BleObdLink.kt:451-458` +
      `:467-483`. Restore the "still being polled" half of the fire predicate; a bare `finally`
      disarm would disable the watchdog instead. Ship with regression test (1) above, both shapes.
- [ ] m1 · m2 · m3 · m4 · m5 — non-blocking, take or leave with a note.
- [ ] Tests 2–7 above (2, 3 and 4 are the ones I would insist on for a reconnect-path change).
- [x] Gate re-run by me on `6452c2e` → PASS ×7 ✅ (build is green; B1 is uncovered behaviour, not a
      red test)

---

## Hardware checklist

Unchanged and still open — device verification is **Taras's**, not mine, and it is the real gate:

- [ ] On the van: reproduce the "won't reconnect until I cycle the key" state → confirm the app
      recovers on its own within ~25–30 s.

Two additions once B1 is fixed, because they are the failure this round found and only a device can
confirm it is gone:

- [ ] Van, healthy link: open the dashboard, then background the app (**no** recording / foreground
      service) and leave it a minute. The link must stay `Ready` — no `Error` flash, no
      reconnect in the log.
- [ ] Van, engine off (OBD-71 path): confirm the two watchdogs do not fight — no liveness-triggered
      reconnect between the poll loop stopping and OBD-71's own teardown.
