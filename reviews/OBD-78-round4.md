---
issue: OBD-78
round: 4
reviewer: rev-ble
verdict: approved
gate: green
reviewed-commit: e1eaaed
covers: [OBD-78]
hardware-verify: true
---

# OBD-78 — wedge recovery at the service layer — round 4

**Approved.** B3 and M1 are both resolved, and I verified them by re-running the round-3 probes
against the new logic rather than by reading. The round-3 storm — 45 forced reconnects in 30 minutes
with OBD-69's idle-stop never firing — is now **1 forced reconnect, idle-stop fires**. The
fuel-stop teardown that fired 0 s after recovery now gets a full 30 s session window.

Your "bounded to one, not literally never" framing is the right one and it is what the code does.
I swept the residual and it is better than bounded-to-one in the way that matters: see below.

Gate: I re-ran `tools/gate.sh` → **PASS ×7**, on `b28e8f7` and again on `524290f`.

**reviewed-commit bumped `b28e8f7` → `524290f` (round-4 addendum).** Since the approval the branch
took the minors this review asked for (m1–m5 + test #1). I re-reviewed the applied diff — it matches
what I asked for, and the one production-logic line is correct. Details and one new nit at the end;
the approval holds against the branch head.

---

## B3 — closed (§1)

The mechanism is sound and the arithmetic holds. `dataFlowing` requires `now - lastData <
staleAfterMillis`, so a lone sample keeps the health clock alive for at most one 30 s window before
`nextHealthy` resets to `0`; `sustainedAfterMillis` is 90 s. One sample therefore cannot re-arm the
latch, which is exactly the loop-closing property.

Traced the full round-3 interleaving against the new code:

| step | `stale` | `sustainedHealthy` | decision | latch |
|---|---|---|---|---|
| wedge, 30 s silent | true | false | **force** | set |
| forced reconnect, `Connecting` | — (`!linkReady`) | — | hold | set |
| back `Ready`, ONE sample lands | false | false (clock just started) | hold | **set** |
| +30 s, dongle wedged again | true | false (clock reset at the 30 s mark) | hold — **no second force** | set |

Re-ran the round-3 probe (written, run, deleted):

```
one-sample-per-reconnect:  forces in 30 min = 1,  idle-stop would fire = true   (was 45 / false)
fully silent (control):    forces in 30 min = 1,  idle-stop would fire = true
```

**The residual, swept.** I varied how much data the dongle delivers after each forced reconnect:

```
burst= 0s → 1 force /30min, idle-stop fires
burst=30s → 1 force /30min, idle-stop fires
burst=60s → 1 force /30min, idle-stop fires
burst=62s → 1 force /30min, idle-stop fires
burst=70s → 16 forces/30min, idle-stop does NOT fire
burst=90s → 14 forces/30min, idle-stop does NOT fire
```

The re-arm boundary sits between ~62 s and ~70 s of real data. Above it the behaviour looks like a
storm by count but **is not one, and I want to be explicit that I checked**: at `burst=70s` the link
delivers 70 s of live gauges per ~112 s cycle — data is flowing ~62 % of the time, so OBD-69 *not*
stopping is correct (the idle-stop exists to stop when there is no data, and there is data), and the
user gets working gauges with a ~6 s gap every couple of minutes. That is the feature working on a
flaky dongle, not the round-3 pathology. The pathology was recovery-with-no-recovery; this is
recovery.

**Accepted trade worth stating plainly:** a dongle that only ever manages sub-60 s bursts gets
**exactly one** rescue for the whole service lifetime, then sits dead until OBD-69's idle-stop or a
user restart. That is the deliberate price of killing the storm, and it is the right side of the
trade — but it is a behaviour change worth knowing about on the van.

---

## M1 — fixed (§2)

`stale = linkReady && now - max(readySinceMillis, lastDataAtMillis) >= staleAfterMillis` gives a
freshly-`Ready` session its own window regardless of how long the global stamp aged while the link
was gone. Probe, the same 5-minute fuel stop that fired at 0 s in round 3:

```
forced 30s after the link came back   (was: 0s)
```

30 s is a genuine window, not a formality — I checked it against the real init cost. The realistic
worst case is `ATZ` (~1 s) + four `AT` commands (ms) + `0100` in `SEARCHING…` (up to 10 s) ≈ 11 s,
comfortably inside. And a *timing-out* init does not consume the window at all: per round 2,
`BleObdLink` throws `BleLinkException`, which `Elm327InitStateMachine.send` maps to
`StepOutcome.Dropped`, so init aborts on the first timeout rather than spending 23 s working
through the sequence. Headroom is real.

**Edge-stamping cannot miss a fast reconnect** (§2). `launchReadySinceTracker` collects
`dataSource.connection` rather than sampling on the coarse tick, so the stamp is driven by the
transition, not by when the watchdog happens to look. The `Ready → Connecting → Ready` shape is
guaranteed: `attemptConnectOnce` `release()`s (no publish) and then `openSession` publishes
`Connecting` before the GATT connect/discover/CCCD/MTU handshake, so the intermediate state is
hundreds of ms to seconds wide — far outside `StateFlow` conflation. Worth noting this is the same
edge `ConnectionServiceController` already depends on for its restart, so the two agree by
construction; if one ever missed it, both would.

---

## New state — what I checked (§3)

1. **`stale` and `sustainedHealthy` are mutually exclusive**, so the `when` ordering is not
   load-bearing. `sustainedHealthy` requires `now - lastData < stale`; `stale` implies
   `now - lastData >= stale`. They cannot both be true. The KDoc's "checked top to bottom" reads as
   if precedence matters — it doesn't, which is a good property and worth saying rather than
   leaving a reader to wonder.
2. **`maxOf` edge cases are safe.** `readySinceMillis = 0` while not `Ready` never reaches the
   comparison, because `stale` is `&&`-gated on `linkReady` first. `readySince` in the future
   relative to `lastData` is exactly what `maxOf` is there for. All three timestamps
   (`readySinceMillis`, `idleTracker.lastDataAtMillis`, the watchdog's `now`) come from
   `SystemClock.elapsedRealtime()` — one monotonic domain, no wall-clock mixing, and it only resets
   on reboot, which takes the service with it.
3. **`dataHealthySinceMillis` and `wedgeReconnectPending` are single-coroutine.** Both are read and
   written only inside `checkWedgeAndMaybeReconnect`, called only from the wedge watchdog. Plain
   `var` is correct for those two. (`readySinceMillis` is the exception — see m1.)
4. **The health clock is written before the decision reads it**, and unconditionally — including on
   `demo`, where `linkAvailable` is false. Harmless: the clock ticks, nothing acts on it.
5. **`startWatchdogs()` is a faithful extraction.** `idleTracker.record(...)` still precedes every
   launch, and all four coroutines are on `serviceScope`, so `onDestroy` cancels them. Launch order
   does not matter — all four are queued long before the first 10 s delay elapses.
6. **The wedge watchdog still never breaks** (`breakOnResult = false`), which is required now more
   than before: the health clock only advances on its ticks.

---

## Minor / non-blocking

- **m1 (should-fix, one keyword).** `readySinceMillis` is the one genuinely cross-coroutine field in
  the new state — **written** by `launchReadySinceTracker`'s collector, **read** by the wedge
  watchdog, two coroutines on `Dispatchers.Default` — and it is a plain non-`@Volatile` `Long`. Its
  immediate neighbour `IdleActivityTracker.lastDataAtMillis` carries `@Volatile` with the KDoc
  *"Written from a coroutine and read from another, so the field is `@Volatile`"* — the identical
  situation, decided the other way. In practice ART on ARM64 will not tear an aligned long and the
  scheduler hand-off supplies the barrier, so I am not blocking on it; but it is one word, and the
  file next door already documents why.
- **m2 (same field, tidier fix for both).** `linkReady` and `readySinceMillis` are read from two
  sources that update independently — a direct `dataSource.connection.value` read on the tick, versus
  an async collector's field. There is a sub-millisecond window on the `Ready` edge where the tick
  sees `Ready` while `readySince` is still `0`, and I confirmed that shape forces:
  `readySince=0 while Ready -> stale=true force=true`. Odds are ~10⁻⁴ per reconnect and the latch
  bounds the damage to one spurious recovery, so it is genuinely minor — but deriving `linkReady`
  from `readySinceMillis != 0L` would make the two consistent by construction *and* fail in the safe
  direction (a not-yet-stamped edge reads as "not ready", so nothing fires). That single change
  closes m1's practical exposure too.
- **m3 (documentation accuracy).** `SUSTAINED_HEALTH_MILLIS`'s comment says data must "flow
  continuously for this long" (90 s). The measured effective threshold is **~60 s of real data**,
  because `dataFlowing` tolerates up to a 30 s gap — my sweep puts the boundary between 62 s and
  70 s, i.e. `SUSTAINED − STALE`. The stated invariant (`SUSTAINED > STALE`, so one sample can never
  re-arm) is correct and is the one that matters; but anyone later tuning these two numbers will
  reason about the wrong quantity unless the comment says the effective floor is the *difference*.
- **m4 (round-3 m1, still open).** `LinkController.connect()`'s KDoc still says **"User-gesture
  only"**, and the interface KDoc is where the ownership rule is documented-enforced.
  `ObdConnectionService` is an automated caller. I agree with the distinction you're drawing
  (data-liveness, not a `LinkState` reaction) — it just needs to be recorded *on the interface* as
  the one sanctioned automated caller, or the next reader sees a violation.
- **m5 (round-3 m2, still open).** The signal detects "no data at all", not "frozen gauges". A
  **partial** wedge — some PIDs still answering — keeps the poll loop running and (per OBD-71's root
  cause, `Reading.timestamp` being in `equals`) re-stamps the tracker every cycle, so such a link is
  never recovered. Worth one line in `checkWedgeAndMaybeReconnect`'s KDoc as a known bound.

---

## Tests

`WedgeInputsTest` (6 cases) and the extended `WedgeReconnectDecisionTest` (8) cover both halves well,
and the B3 loop-closed walk is exactly the right test to have written. The decision layer and the
input layer are each solid.

I said in round 3 I would gate on two tests. I am not blocking on them, and here is my reasoning:
both halves are now individually pinned, and I verified the *composition* independently by probe
(that is where the ~62–70 s boundary came from). What remains is regression protection, not a live
bug. But one of the three is small and guards the load-bearing invariant, so I would take it now:

1. **Bind the invariant to the production constants** (the one I would actually do). The
   `sustained > stale` assertion in `WedgeInputsTest` compares the test's *own* `private val`s
   (30_000/90_000), not `WEDGE_RECONNECT_STALE_MILLIS`/`SUSTAINED_HEALTH_MILLIS` — which are
   file-`private` in `ObdConnectionService.kt` and therefore invisible to it. So the single most
   load-bearing property of the B3 fix, the one its own comment writes in capitals
   ("STRICTLY GREATER"), is guarded by a test that would still pass with
   `SUSTAINED_HEALTH_MILLIS = 20_000L` in production. Making the two constants `internal` (same
   module, same package) and asserting on them closes it in about three lines.
2. **The composition test** — drive `wedgeInputs` + `wedgeReconnectDecision` + the idle tracker over
   simulated time and assert both emergent properties: forces stay bounded on a low-burst dongle,
   **and** OBD-69's idle-stop still fires. That is the property that took two rounds to get right and
   no single unit test expresses it. ~60 lines, pure, no Robolectric.
3. **`checkWedgeAndMaybeReconnect` wiring** — still untested, and it does more now: it reads `ready`
   from one source, `readySinceMillis` from another coroutine's field, and *writes back*
   `dataHealthySinceMillis`. That write-back is new stateful behaviour with zero coverage. Still
   closable without a prod harness — `linkController` is an `internal lateinit var`, so Robolectric
   can assign `Optional.of(fake)` after `create()`, the same seam `service.controller = …` uses at
   `ObdConnectionServiceTest:203`.

---

## Fix list

- [x] **B1** (round 1) — liveness fired on a healthy link once polling stopped. Resolved ✅ — the
      `:core:ble` watchdog was reverted entirely; the mechanism no longer exists.
- [x] **B2** (round 2) — the watchdog was unreachable in production (count topped out at 1).
      Resolved ✅ — moved to the service layer, where the signal survives the poll loop parking.
- [x] **B3** (round 3) — wedge→reconnect→one-sample→wedge looped forever and defeated OBD-69's
      idle-stop. Resolved ✅ — the sustained-health floor means one sample can never re-arm the
      latch. Measured: **45 forces/30 min → 1**, idle-stop restored from `false` to `true`.
- [x] **M1** (round 3) — a healthy link was torn down the instant it recovered from a >30 s outage.
      Resolved ✅ — session-relative clock. Measured: forced at **0 s → 30 s** after recovery, with
      ~11 s realistic init cost inside a 30 s window.
- [x] **m1–m5 + test 1** — all applied in `524290f` and re-reviewed ✅ (see the addendum below).
      Tests 2 and 3 stand as follow-ups; not blocking, and I agree with landing without them.
- [ ] n1 (new, cosmetic) — capture `readySinceMillis` into a local before its two reads. See below.
- [x] Gate re-run by me on `b28e8f7` **and** `524290f` → PASS ×7 ✅

---

## Addendum — applied minors, `b28e8f7` → `524290f`

Re-reviewed the applied diff. All five minors and test #1 landed as intended; nothing new of
substance. What I checked beyond reading:

- **m2 is the only production-logic change, and it is correct.**
  `val ready = readySinceMillis != 0L` replaces the second `dataSource.connection.value` read. I
  walked both directions of the equivalence against `launchReadySinceTracker`: every non-`Ready`
  state writes `0`, and the `Ready` edge writes `elapsedRealtime()`, so `!= 0L` **is** "Ready" as far
  as this watchdog is concerned, from a single source. Both failure directions are now benign —
  a not-yet-stamped `Ready` edge reads as not-ready and nothing fires (the fail-safe direction I
  asked for), and a not-yet-cleared drop keeps `lastData` recent enough that `stale` cannot trip. The
  `0` sentinel is safe because `elapsedRealtime()` is ms-since-boot and a foreground service cannot
  exist at boot+0 ms. The collector itself cannot die: `collect` on a `StateFlow` never completes,
  and the lambda is two field writes and a clock read.
- **test #1 actually binds — mutation-checked, not just read.** I set
  `SUSTAINED_HEALTH_MILLIS = 20 * 1000L` in the source and re-ran
  `:app:testDemoDebugUnitTest --tests '*WedgeInputsTest*'`: **FAILED**, as it must. Reverted; tree
  clean. That is the guard doing its job, and it is the one I most wanted.
- **m1** `@Volatile` is on the right field, and the KDoc now names the two coroutines. **m3** the
  constant's comment now points at `SUSTAINED − STALE` as the number to reason about. **m4** the
  sanctioned-automated-caller carve-out is on the interface where the rule lives. **m5** the
  partial-wedge bound is recorded on the check. All accurate as written.
- Gate re-run on `524290f`: **PASS ×7**.

**n1 (new, cosmetic, non-blocking).** `checkWedgeAndMaybeReconnect` reads the now-`@Volatile`
`readySinceMillis` **twice** — once for `ready`, once as the `wedgeInputs` argument — so in principle
the collector can zero it between the two adjacent statements, giving `ready = true` with
`readySinceMillis = 0` and a spurious force. That is the same inconsistency m2 set out to remove,
narrowed from a ~sub-millisecond window to a ~nanosecond one (roughly 10⁻⁴ → 10⁻⁹ per tick), and the
latch bounds it to one recovery. Capturing `val readySince = readySinceMillis` once and using it for
both closes it completely and is the natural shape now that the field is `@Volatile` — precisely
because a `@Volatile` field is one that can change under you. Cosmetic; not worth another round.

---

## Hardware checklist

Device verification is **Taras's**, and it stays the real gate — four rounds of pure-logic
verification say the decision is right, but nothing here has met an actual wedged dongle.

- [ ] On the van: reproduce the "won't reconnect until I cycle the key" state → confirm the app
      recovers on its own within ~30–40 s, no ignition cycle. This is the headline behaviour.
- [ ] **B3 watch:** after that recovery, keep watching. One rescue is expected; a *repeating*
      reconnect every ~40 s is not, and would mean the sustained floor is being satisfied by
      something I have not modelled.
- [ ] **M1 watch:** a fuel stop — key off a few minutes, key on. The link should come back and
      **stay** up; an immediate second drop-and-reconnect before data appears would be M1 surviving.
- [ ] Overnight, parked, dongle powered (folds into the OBD-71 test — same subsystem): confirm the
      service still idle-stops at ~20 min. Round 3 predicted it would not; round 4 predicts it does,
      and this is the observation that settles it.
- [ ] Worth noting in the log if the dongle turns out to be a sub-60 s-burst type: it gets exactly
      one rescue by design, then waits for the idle-stop. If that is what the van does, the
      `SUSTAINED_HEALTH_MILLIS` trade is the thing to revisit — not the design.
