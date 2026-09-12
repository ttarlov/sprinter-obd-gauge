---
issue: OBD-78
round: 2
reviewer: rev-ble
verdict: changes-requested
gate: green
reviewed-commit: a905590
covers: [OBD-78]
hardware-verify: true
---

# OBD-78 — data-liveness reconnect watchdog — round 2

**B1 is resolved.** Cleanly, and the count approach is better code than the timer it replaces —
no coroutine to leak, no self-cancelling job, one less lifecycle to reason about. Both of my
round-1 probes are now impossible by construction.

**But it is over-resolved, and that is a new blocker.** The property that makes a frozen count
safe — *"a poll loop that stops freezes the count instead of leaving a timer armed"*
(`BleObdLink.kt:143-145`) — assumes the poll loop only stops for benign reasons. It doesn't. The
real poll loop **parks on the first unanswered command**, by explicit design. So on a wedged
dongle `consecutiveUnanswered` reaches exactly **1** and freezes there forever, against a default
threshold of **12**. The watchdog cannot fire on the van, for precisely the failure it was written
for.

Gate: I re-ran `tools/gate.sh` on `a905590` myself → **PASS ×7**. The suite is green and the
feature is inert; §3 below explains why the tests cannot see that.

---

## BLOCKER B2 — the watchdog is unreachable in production: the count can only ever reach 1

Not an interleaving — a caller-contract mismatch. `:core:ble` counts consecutive unanswered
commands, but nothing issues a second one.

**The chain, file:line:**

1. `BleObdLink.sendRaw` throws `BleLinkException(LinkError.Timeout, …)` on a silent dongle;
   `onLivenessOutcome` bumps the count to 1 (`BleObdLink.kt:470-482`).
2. `ObdLink.sendCatching` turns it into `RawResult.Failed` — it is **not** a
   `TimeoutCancellationException` (`GattSession.send:161-164` already converted it), so it lands in
   the broad `catch (e: Exception)` arm (`RawResult.kt:38-42`).
3. `RealVehicleDataSource.poll` maps **any** `RawResult.Failed` to `PollOutcome.LinkDown`
   (`RealVehicleDataSource.kt:302`) — the type's own KDoc says *"The link dropped **or timed out**
   mid-exchange. The poll loop parks"* (`PollOutcome.kt:41`).
4. `applyPoll` returns `false` (`:269-271`) → `runSession` publishes stale and **`return`s**
   (`:213-215`). The loop is gone. This is deliberate and documented: *"On a link drop the loop
   parks … It does not reconnect and does not spin retrying"* (`:65-69`). `HeaderScope.request`
   makes the same choice explicitly — *"two more doomed commands would only add timeouts"*
   (`HeaderScope.kt:85-89`).
5. Nothing restarts it. `ConnectionServiceController` restarts polling on **a transition into
   `LinkState.Ready`** and only that (`ConnectionServiceController.kt:96-105`, and its KDoc at
   `:44-56` spells out why the old `Disconnected` self-heal was deleted). During a wedge the link
   **never leaves `Ready`** — that is the entire premise of OBD-78 — so `becameReady` is never
   true. `DashboardViewModel` calls `start()` only from `onStart` on first collection
   (`DashboardViewModel.kt:114`); `Recorder` only when the user starts a recording.

Same story on a cold connect to an already-wedged dongle: init dies on command one.
`Elm327InitStateMachine.send` maps a `BleLinkException` to `StepOutcome.Dropped`, not `TimedOut`
(`InitStateMachine.kt:256-263`), so `InitFailure.LinkDown` → `initialize()` false → `runSession`
returns (`:227-233`). Even the documented single `ATZ` retry doesn't fire — it is gated on
`StepOutcome.TimedOut`, which `BleObdLink` cannot produce (`:191-193`).

**Proof.** Throwaway probe against the real `RealVehicleDataSource` with a link that throws
`IllegalStateException("no response to …")` on every command — what `BleObdLink` looks like from
the protocol layer during a wedge (written, run, deleted; nothing added to the branch):

```
PROBE healthy sendRaw calls over 2 min:                  728
PROBE sendRaw calls in the 10 MINUTES AFTER the wedge:     1
PROBE sendRaw calls in 10 min, wedged from the start:      1
```

728 → 1. The count tops out at 1 and stays there. Default threshold 12 is unreachable; so is 3,
and so is 2.

**This is a regression against round 1, not a pre-existing gap.** The timer version fired correctly
here — it armed on that single unanswered command and fired 25 s later regardless of whether
anything followed. The B1 fix removed the false positive by removing the mechanism that made the
true positive reachable. Round 1 fired when it shouldn't; round 2 never fires at all.

### On the shape of a fix (not prescribing one)

Lowering the threshold is not available: at 1, a single 2 s timeout tears the link down, and one
`SEARCHING…` or slow PID becomes a reconnect storm. The deeper problem is that **`:core:ble`
cannot distinguish "wedged" from "nobody is polling" using command outcomes alone** — after the
first failure both look identical from inside `sendRaw`, because they *are* identical there. The
distinguishing fact lives one layer up and already exists as a typed signal:
`PollEvent.LinkDropped` means the loop parked *because of a link failure*, which is categorically
different from `VehicleDataSource.stop()` being called by its owner. Either that signal has to
reach the teardown decision, or the poll loop has to keep probing a `Ready` link after a timeout
(a changed park rule, or a low-rate keep-alive probe).

Flagging one trap for round 3: a keep-alive probe issued from inside `:core:ble` while `Ready`
would reintroduce the "runs a coroutine on an idle link" problem both previous cuts were built to
avoid, and would poll the dongle while the van is parked — straight into OBD-71's drain fix. The
honest read is that OBD-78's *scope* may be wrong: the fix probably does not live in `:core:ble`
alone.

---

## What I verified on the count mechanism itself (§2) — no other new bugs

The mechanics are sound. Everything below holds; it is only the reachability that fails.

1. **B1 is genuinely gone (§1).** `armLiveness`/`livenessJob` are deleted; `consecutiveUnanswered`
   only ever moves inside `sendRaw`'s body or `release()`. No timer exists to fire against a link
   nobody is asking anything. Both round-1 probes — a partial silent streak then backgrounding, and
   a healthy in-flight command cancelled by `WhileSubscribed` teardown — are now structurally
   impossible, not merely untriggered. Round-1 **m1** (dead-job `!= null` guard) and **m2**
   (self-cancelling job) are moot with the job.
2. **Re-entrancy from inside `commandMutex` + `withContext(dispatcher)` — safe, and strictly safer
   than round 1.** `onSessionTerminated` is fully non-suspending: `publish` writes a StateFlow;
   `scheduleReconnect` only *queues* onto `linkScope`, so on the single-threaded dispatcher the
   retry runs after `sendRaw` unwinds and releases both the dispatcher and the mutex; `release()`
   closes the session and cancels `sessionScope`. `commandMutex` is never re-acquired anywhere in
   that path, so no deadlock. Critically, `sendRaw`'s coroutine inherits the **caller's** job, not
   `sessionScope` — so unlike the round-1 timer, `release()` is no longer cancelling the coroutine
   it is running in. `GattSession.close()` → `failPending` is a no-op here because `send`'s own
   `finally` already cleared `responseAwaiter` (`GattSession.kt:167-175`).
3. **Double-fire with a real drop — impossible in both orders.** Liveness first: `release()` →
   `close()` sets `terminated = true` and `ready = false`, so a later `Disconnected` early-returns
   in `terminate()` and `onTerminated` is gated on `wasReady`. Drop first: `onSessionTerminated` →
   `release()` sets `session = null`, so `session !== active` short-circuits `onLivenessOutcome` —
   and the failure `failPending` hands `send` is `Gatt(status)`/`Unknown("dongle disconnected")`,
   not `Timeout`, so it would not count anyway. Two independent guards.
4. **The unguarded success reset is harmless.** `active.send(…).also { consecutiveUnanswered = 0 }`
   (`:456`) has no `session === active` check, so a response delivered just before a drop could
   reset a count belonging to a newer session. It cannot: `commandMutex` is still held, so no
   `sendRaw` can have run against the new session to make the count non-zero, and `release()`
   already zeroed it. Correct by the mutex rather than by the guard — worth a comment, not a change.
5. **`CancellationException` never counts.** `catch (failure: BleLinkException)` narrows correctly,
   and `GattSession.send` converts `TimeoutCancellationException` → `BleLinkException(Timeout)`
   before it can be mistaken for a caller giving up (`:161-164`). A `WhileSubscribed` teardown
   mid-command passes straight through.
6. **Confinement.** `consecutiveUnanswered` is touched only in `sendRaw`'s `withContext(dispatcher)`
   body and in `release()`, all callers dispatcher-confined; production is a single-thread executor
   (`di/BleModule.kt:78-84`). Plain `var` is correct.
7. **`release()` resets between sessions** (`:635`), so a partial count cannot follow a session
   across a reconnect. `threshold <= 0` disables, and covers negatives.

---

## Minor / non-blocking

- **m6 (new, coverage narrowing).** Only `LinkError.Timeout` counts (`:474`). The timer version was
  error-agnostic; this is not. Two wedge shapes now go uncounted forever:
  `LinkError.Unknown("$step was rejected by the BLE stack")` when `transport.write` returns false —
  a stack stuck on `ERROR_GATT_WRITE_REQUEST_BUSY` rejects every write *instantly*, so the poll loop
  would spin hot with no count — and `LinkError.Gatt(status)` from a write acked with a failure
  status (`GattSession.awaitStep:281-297`). Moot while B2 stands, but worth deciding deliberately
  rather than by omission.
- **m7 (new, cross-module coupling).** The default of 12 is justified as *"at a ~2 s command timeout
  … ~24 s of dead air"* (`BleConfig.kt:73-79`), but `:core:ble` does not own that 2 s —
  `PollSchedule.commandTimeout` in `:core:protocol` does, and `sendRaw` takes the timeout from its
  caller. A count in one module tuned against a duration in another, with nothing enforcing the
  relationship, is the coupling B2 is the acute form of.
- **m3 (round 1, still open).** `BleObdLink.LIVENESS_STALL` still has **zero references outside its
  own declaration**, despite its KDoc claiming it is stable "so a test (and a drive log) can match
  on it". All five tests assert on `RecordingLogger` strings; none asserts the published
  `LinkState.Error` cause or `link.retrying`, which is what actually pins the RECOVERABLE
  classification.
- **m8 (nit).** `BleConfig.livenessFailureThreshold`'s KDoc says *"Set to `0` to disable"* while the
  code accepts `<= 0`. Harmless; the doc is the narrower contract.

---

## Tests (§3)

The five cases genuinely prove the count semantics — threshold fires, partial streak does not, an
answer resets, idle never fires, `0` disables — and the B1 regression test is a real test of a real
property. Round-1 gaps 2 (a late answer resets), 4 (idle) and 5 (disable) are now covered.

**But the suite encodes a false model of the caller, which is exactly why it is green while the
feature is inert.** `pollOnce` (`LivenessWatchdogTest:66-69`) drives `sendRaw` repeatedly with
`runCatching`, ignoring failures — a poll loop that keeps going after a timeout. The real one parks
after the first (B2). Every test that reaches the threshold does so by calling `pollOnce` three
times in a row, which the app never does. The fixture proves the counter counts; nothing proves
anything ever counts to 12.

`:core:ble` cannot import `:core:protocol` (module isolation), so this cannot be fixed inside
`LivenessWatchdogTest` — it needs a test on the `:core:protocol` side (how many commands does the
loop issue after a wedge?) or an `:app` integration test over the real pair. **That test is the one
that matters**, and its absence is what let B2 through.

Also still missing:

1. **A cancelled command does not count** — pins the `catch (failure: BleLinkException)` narrowing
   against someone widening it to `Exception`.
2. **A non-`Timeout` `BleLinkException` does not count** — pins m6's narrowing as deliberate.
3. **A real drop with a partial count** — assert exactly one `Error`, one `reconnect scheduled`, no
   second teardown, count reset.
4. **`disconnect()` with a partial count, then reconnect** — the next session starts clean
   (`release()`'s reset).
5. **The cause, not the log** — `state.value` is `Error` whose message starts with
   `BleObdLink.LIVENESS_STALL`, and `retrying == true` (round-1 m3).

---

## Fix list

- [x] **B1 — liveness fires on a healthy link once polling stops.** Resolved ✅ — timer replaced by
      `consecutiveUnanswered`; both round-1 probes are now impossible by construction, and round-1
      m1/m2 died with the job.
- [ ] **B2 — the watchdog cannot fire in production.** The poll loop parks after the first
      unanswered command (`RealVehicleDataSource.kt:302` → `:269-271` → `:213-215`) and nothing
      restarts it while the link stays `Ready` (`ConnectionServiceController.kt:96-105`), so
      `consecutiveUnanswered` maxes out at 1 against a threshold of 12. Measured: 728 commands in
      2 min healthy → 1 in the 10 min after the wedge. Needs a decision about *where* this feature
      lives, not a threshold tweak.
- [ ] The caller-contract test (`:core:protocol` or `:app`) — the missing test that would have
      caught B2, and the one I would gate round 3 on.
- [ ] m6 · m7 · m3 · m8, and tests 1–5 above — non-blocking, take or leave with a note.
- [x] Gate re-run by me on `a905590` → PASS ×7 ✅ (green, and green is not evidence here — see §3)

---

## Hardware checklist

Device verification is **Taras's**, not mine, and it remains the real gate. It is also now the
decisive one: B2 predicts the van test **fails** — the app will still sit `Ready` with dead gauges
and still need a key-cycle. If it passes on the van, my model of the poll loop is wrong somewhere
and I want to see the log.

- [ ] On the van: reproduce the "won't reconnect until I cycle the key" state → confirm the app
      recovers on its own within ~25–30 s. **Expect this to fail at `a905590`.**
- [ ] Capture the drive log around the wedge: how many commands does the app send after the gauges
      freeze? B2 says one. That number settles the design question for round 3.
- [ ] Once B2 is fixed: background the app on a healthy link (no recording / foreground service)
      for a minute — the link must stay `Ready`, no `Error` flash, no reconnect. This is round-1 B1
      staying fixed.
- [ ] Once B2 is fixed: engine off (OBD-71 path) — no liveness-triggered reconnect between the poll
      loop stopping and OBD-71's own teardown.
