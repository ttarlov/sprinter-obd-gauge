---
issue: OBD-78
round: 3
reviewer: rev-ble
verdict: changes-requested
gate: green
reviewed-commit: 638026d
covers: [OBD-78]
hardware-verify: true
---

# OBD-78 — wedge recovery at the service layer — round 3

**B2 is resolved, and the relocation is right.** The service layer really is where this is
detectable, the `:core:ble` revert is clean (`BleObdLink`/`BleConfig` are byte-identical to `main`),
the pure/impure split matches `shouldStopForIdle`'s precedent, and the `launchWatchdog` merge is a
faithful generalisation. The primary scenario — a dongle that goes hard-silent — now recovers, and I
verified it end-to-end rather than by inspection.

**Two findings, one root cause.** `stale` conflates *"no data because the dongle is wedged"* with
*"no data because nothing has had a fair chance to produce any yet"*, and the latch clears on a
single sample. Together they produce an unbounded forced-reconnect loop that **defeats OBD-69's
idle-stop** — measured at **45 forced reconnects in 30 minutes with the idle-stop never firing** —
and a spurious teardown of a healthy link the moment it recovers from any outage longer than 30 s
(fuel stop, key-off, a slow first connect).

Gate: I re-ran `tools/gate.sh` on `638026d` → **PASS ×7**, confirming your result.

---

## BLOCKER B3 — wedge → reconnect → one sample → wedge is an unbounded loop that defeats OBD-69

`WedgeDecision.kt:36-41` with `ObdConnectionService.kt:359-375` and `IdleWatchdog.kt:14-23`.

The latch is cleared by **`!stale`**, and `stale` is false as soon as `lastDataAtMillis` is within
30 s. `applyReadingsToIdleSignal` stamps the tracker on **any non-empty readings map**
(`IdleWatchdog.kt:20-22`) — so **one** published sample re-arms the watchdog completely.

The cycle, every step of which is existing mainline behaviour:

1. Poll loop parks on a timeout; no more samples.
2. 30 s later: `Ready` + `stale` + `!pending` → **force `connect()`**, latch set.
3. `BleObdLink.connect()` releases the wedged session, reconnects → `Ready`.
4. `ConnectionServiceController` fires on the `Ready` **edge** → `dataSource.start(...)`
   (`ConnectionServiceController.kt:96-105`) → init runs → the first cycle publishes.
5. That single non-empty emission stamps the tracker → next tick sees `!stale` → **latch clears**.
6. The next command times out → `RealVehicleDataSource` parks again → back to step 2.

Period ≈ 40 s, forever. Each iteration is a full GATT teardown + scan/connect + handshake + ELM327
init, and each stamp also calls `refreshWakeLock` (`ObdConnectionService.kt:224`), so the CPU stays
held.

**And it re-creates OBD-71's root cause verbatim.** `issues/OBD-71.md:143-166` establishes that
*"every successful poll resets the 20-min timer → it never reaches 20 min → watchdog never fires"*.
That is exactly what step 5 does — on a parked van, with a reconnect storm attached.

**Measured.** Throwaway probe driving the real `wedgeReconnectDecision` + `shouldStopForIdle` +
`applyReadingsToIdleSignal` over 30 min of simulated time (written, run, deleted):

```
wedge → reconnect → ONE sample → wedge again
  forced reconnects in 30 min:            45
  OBD-69 idle-stop would ever fire:       false

control: dongle stays fully silent after the reconnect  ← the case the KDoc reasons about
  forced reconnects in 30 min:             1
  OBD-69 idle-stop would ever fire:       true
```

The control is the important half: the documented anti-storm property **is** correct for a
permanently silent dongle, exactly as `checkWedgeAndMaybeReconnect`'s KDoc claims. The claim just
does not cover the failure that actually loops — *"answers briefly, then wedges again"*, which is
the ordinary shape of both a clone whose serial state machine resets on BLE reconnect and a vehicle
going to sleep while the dongle keeps power (OBD-71's overnight case, where modules drop off the bus
one at a time and some PIDs keep answering for a while).

Note this is not the OBD-24 hazard — it does not fight the backoff, because it only fires while
`Ready` and the backoff lives in the non-`Ready` states. It is a second, independent route to the
same outcome the hazard statement was written about: an automated `connect()` loop outrunning the
component that owns retry policy.

---

## MAJOR M1 — a healthy link is torn down the instant it recovers from any outage > 30 s

Same root: `stale` is measured against a **global** last-data stamp with no notion of whether the
*current* session has had a chance to produce anything.

`idleTracker` is seeded at service start (`ObdConnectionService.kt:220`) and stamped only on
non-empty readings emissions. During any outage the poll loop is parked, so the stamp ages freely.
The moment the link returns to `Ready`, `lastDataAtMillis` is already minutes old — and the wedge
watchdog ticks every 10 s on its own schedule, independent of the link. So the first tick after
`Ready` fires unless a sample beats it, and `dataSource.start()`'s `mutableReadings.value =
emptyMap()` is an **empty** emission that deliberately does not stamp (`IdleWatchdog.kt:20`), so
nothing clears it during init.

Probe, same run — a 5-minute fuel stop, then a healthy link comes back:

```
forced a reconnect 0s after the link came back
tore down the freshly-recovered link: true
```

The race it has to win is only "init produces a sample before the next 10 s tick". On a cold
ELM327 that is `ATZ` (up to 5 s, and `Elm327InitStateMachine` retries it once) plus `0100` in
`SEARCHING…` (up to 10 s) — it loses that race often, and when it does it aborts a healthy in-flight
init and delays real data by a whole extra connect cycle. Every key-off/fuel-stop recovery is a
candidate, which is the single most common real-world event in this app (`ReconnectPolicy`'s KDoc
calls key-off "the case that motivated the issue"). Bounded to one extra reconnect by the latch, so
not a blocker on its own — but it is the same missing input as B3.

### The shared fix (shape only, not prescribing)

Two distinct things are missing:

- **A session-relative clock for M1.** The question is not "when did data last arrive" but "how long
  has *this* session been `Ready` with the poll loop running and produced nothing". Seeding/resetting
  the stamp on the `Ready` edge (or gating on time-since-Ready) makes a fresh link immune until it
  has genuinely had its 30 s.
- **A floor under B3.** A session-relative clock alone does not fix B3 — each new session in the loop
  does get its full 30 s of Ready-and-parked. The latch needs to survive a *single* sample: require
  sustained data before re-arming, or a minimum interval between forced reconnects, or a bounded
  number of forces per service lifetime so OBD-69's idle-stop remains the floor. Whichever is
  chosen, the invariant worth writing down is **"a forced reconnect can never reset OBD-69's idle
  timer"** — that is the property B3 violates.

---

## What I verified and could not break

1. **B2 is genuinely resolved (§1).** The detector no longer depends on the poll loop continuing to
   send. The full recovery chain holds: force → `BleObdLink.connect()` → `attemptConnectOnce`
   `release()`s the wedged session and publishes `Connecting` before `Ready`
   (`BleObdLink.kt:297-311`, `:542`) → `ConnectionServiceController`'s `becameReady` edge fires
   (`:99-104`) → `dataSource.start()` restarts the parked loop. The `Connecting` publish is what
   makes the edge real; without it `wasReady` would never fall and the restart would not happen. The
   silent-dongle control probe confirms the intended single recovery + idle-stop backstop.
2. **`:core:ble` revert is total.** `git diff main...HEAD -- core/` is empty; `BleObdLink`,
   `BleConfig` and `LivenessWatchdogTest` are back to `main`. Round-1 B1 and round-2 B2 are both
   moot at the source — nothing in `:core:ble` can tear down a link on data liveness any more.
3. **Not the OBD-24 hazard (§2).** The trigger is data-flow, not a `LinkState` transition, and it
   only fires while `Ready` — the backoff lives entirely in the non-`Ready` states, so this cannot
   race it, and the measured 30-`start()`/60 s shape is structurally unreachable.
   `ConnectionServiceController` still holds no `LinkController` reference; the service, which
   legitimately has one, is the caller. That part of the ownership argument is sound.
4. **`launchWatchdog` is a faithful merge.** `breakOnResult = true` + `WATCHDOG_INTERVAL_MILLIS` +
   `::checkIdleAndMaybeStop` reproduces the old `launchIdleWatchdog` exactly. Both loops are on
   `serviceScope`, so `onDestroy`'s cancel stops them; neither can outlive the service.
5. **Composition, engine-off with the dongle losing power (§3).** The OBD-71 teardown drops the
   radio → not `Ready` → `wedgeReconnectDecision` returns `forceReconnect = false` with the latch
   untouched. Never fires. Correct. (Engine-off with the dongle *keeping* power is B3's territory.)
6. **Composition, a live recording that goes silent (§3).** This recovers rather than stops, so the
   recording continues across the gap — the right call, and it needs no equivalent of OBD-71's
   recording-inhibits-stop seam.
7. **A parked loop does not stamp itself alive.** `mutableReadings` changes only inside
   `RealVehicleDataSource.publish`, which only runs inside `runSession` — so once the loop parks
   there are no further emissions, and `start()`'s `emptyMap()` clear is excluded by
   `applyReadingsToIdleSignal`'s non-empty guard. The staleness signal is sound for a total wedge.
   (§4 — see m2 for the one shape where it is not.)
8. **Latch state is single-writer.** `wedgeReconnectPending` is written only by
   `checkWedgeAndMaybeReconnect`, called only from its one watchdog coroutine, so the plain `var` is
   safe despite `Dispatchers.Default` moving it between threads — the neighbouring
   `IdleActivityTracker.lastDataAtMillis` needs `@Volatile` because it genuinely has two coroutines
   on it. Worth one line of comment recording why the choice differs.
9. **`serviceScope.launch { controller.connect() }` is the right scope.** It outlives the reconnect
   and is not the one-shot scope `disconnectLinkOnUserStop` uses, exactly as the comment says; the
   `CoroutineExceptionHandler` on `serviceScope` catches a throwing `connect()`.

---

## Minor / non-blocking

- **m1 (contract drift).** `LinkController.connect()`'s KDoc says **"User-gesture only"**, and the
  interface KDoc (`LinkController.kt:25-29`) is where the ownership rule is enforced by
  documentation. `ObdConnectionService` is now an automated, non-gesture caller. The distinction
  you're relying on (data-liveness vs `LinkState` reaction) is a good one and I agree with it — but
  it belongs *on the interface*, as the one sanctioned automated caller, or the next person to read
  that KDoc will read the new code as a violation.
- **m2 (coverage limitation, §4).** The signal detects "no data at all", not "frozen gauges". A
  **partial** wedge — some PIDs still answering — keeps the loop running, and per OBD-71's root
  cause `Reading.timestamp` is in `equals` so every successful poll re-stamps the tracker. Such a
  link is never recovered. That is precisely the trap `launchDataIdleReset`'s KDoc warns about
  (*"if a future publish varied a field each cycle … a parked-but-alive loop would stamp forever"*),
  and it is worth stating in `checkWedgeAndMaybeReconnect`'s KDoc as a known bound rather than left
  for someone to discover on the van.
- **m3 (accuracy).** The window is measured from the **park**, not from the last real sample: the
  loop's final `publish(forceStale = true)` carries the previous, non-empty samples map, so it
  stamps once on the way down. Benign (shifts detection by well under a cycle), but the KDoc's *"no
  fresh sample has arrived for [WEDGE_RECONNECT_STALE_MILLIS]"* is not literally what is measured.
- **m4 (nit).** The wedge watchdog never terminates (`breakOnResult = false`), so it wakes every 10 s
  for the service's whole life — 6× the idle watchdog's rate, and unlike it, forever. Free while the
  wake lock is held anyway; noting it because the old watchdog's self-termination was deliberate.

---

## Tests (§5)

`WedgeReconnectDecisionTest` is good work — six cases including the full-episode walk, and it pins
the latch semantics precisely. The decision function is not where the bugs are.

**The wiring has zero coverage, and that is where both findings live.** Nothing exercises
`checkWedgeAndMaybeReconnect`, which is what gathers the two inputs that are wrong: `stale` from an
`idleTracker` seeded at service start (M1) and re-stamped by one sample (B3). You're right that
`ObdConnectionServiceTest` lives in `testDemo` where `linkController` is `Optional.empty()`, so
`linkAvailable = false` and the force path is dead — **but the gap is closable without a prod
harness**: `linkController` is an `internal lateinit var`, so a Robolectric test can assign
`Optional.of(fakeController)` after `create()`, the same seam `service.controller = …` already uses
at `ObdConnectionServiceTest:203`. I'd treat that as required rather than optional here — an
untested branch that calls `connect()` on a real link is the highest-consequence line in the change.

Concretely missing:

1. **The composition test that catches B3** — drive `applyReadingsToIdleSignal` and
   `checkWedgeAndMaybeReconnect` together over simulated time with a "one sample per reconnect"
   dongle; assert a bounded number of forces **and** that OBD-69's idle-stop still fires. My probe
   was ~40 lines of pure code, no Robolectric.
2. **The M1 test** — link `Ready` again after a long outage, no sample yet: must not force until the
   current session has had its window.
3. **`checkWedgeAndMaybeReconnect` wiring** — with an injected `Optional.of(fake)`: forces exactly
   once when `Ready` + stale, calls `connect()` on the fake, sets the latch; and never forces when
   `linkController` is empty (the demo path, which is the current default and therefore silently
   untested in both directions).
4. **`launchWatchdog`'s new `breakOnResult` parameter** — untested in both modes. That the wedge
   watchdog keeps running after firing (and the idle one stops) is now a parameter rather than
   inlined behaviour, and a regression there is invisible.

---

## Fix list

- [x] **B1** (round 1) — liveness fired on a healthy link once polling stopped. Resolved ✅ — the
      `:core:ble` watchdog is reverted entirely; the mechanism no longer exists.
- [x] **B2** (round 2) — the watchdog was unreachable in production (count topped out at 1). Resolved
      ✅ — moved to the service layer, where the signal survives the poll loop parking. Recovery
      chain verified end-to-end, control probe confirms the single-recovery + idle-stop backstop.
- [ ] **B3 — wedge → reconnect → one sample → wedge is an unbounded loop that defeats OBD-69's
      idle-stop.** `WedgeDecision.kt:38` (`!stale` clears the latch) + `IdleWatchdog.kt:20-22` (one
      non-empty map stamps). Measured: 45 forced reconnects in 30 min, idle-stop never fires,
      wake lock re-held each cycle. Directly re-creates OBD-71's documented root cause.
- [ ] **M1 — a healthy link is torn down the instant it recovers from a >30 s outage.** `stale` has
      no notion of whether the current session has had a fair chance. Measured: forced 0 s after the
      link came back from a 5-minute fuel stop.
- [ ] Test 1 (the composition test that catches B3) and test 3 (the wiring, via
      `Optional.of(fake)`) — I'd gate round 4 on these two.
- [ ] m1 · m2 · m3 · m4, and tests 2 and 4 — non-blocking, take or leave with a note.
- [x] Gate re-run by me on `638026d` → PASS ×7 ✅

---

## Hardware checklist

Device verification is **Taras's**, not mine, and it stays the real gate. The primary scenario
should now pass; the two findings are about what happens either side of it.

- [ ] On the van: reproduce the "won't reconnect until I cycle the key" state → confirm the app now
      recovers on its own within ~30–40 s, no ignition cycle. **This should now work** — it is the
      silent-dongle case, which the control probe confirms behaves as designed.
- [ ] **B3 watch:** after that recovery, leave it running and watch for a *repeating* reconnect
      every ~40 s. If the gauges flicker back for a moment each cycle, that is B3 on real hardware.
- [ ] **M1 watch:** a fuel stop — key off for a few minutes, key on. If the link comes back and then
      immediately drops and reconnects once more before data appears, that is M1.
- [ ] Overnight, parked, dongle powered (the OBD-71 test): confirm the service still idle-stops at
      ~20 min. B3 predicts it does not. This one folds into the OBD-71 overnight test since it is
      the same subsystem, and it is the observation that matters most.
