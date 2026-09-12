---
issue: OBD-71
round: 1
reviewer: rev-platform
verdict: approved
gate: green
reviewed-commit: eb496a8
covers: [OBD-71]
hardware-verify: true
---

# OBD-71 round 1 — engine-off idle watchdog / battery-drain fix (combined-lens: correctness + platform + safety)

**Bottom line:** the never-stop-mid-drive guarantee holds at the code level. A `Stop` can only be
reached through a *fresh, present, non-stale* RPM==0 latched for ≥45 s with **no** `RUNNING` reading
in between, and the decode path proves a fresh RPM==0 is only produced when the ECU genuinely reports
0 (errors/timeouts become Skipped/stale → `AMBIGUOUS`, never a fresh 0). The drain fix is correctly
RPM-driven (immune to the cooling-data trickle that defeated OBD-69). OBD-69's watchdog is restored
byte-for-byte and both paths share one idempotent teardown. **Approved**, with one MAJOR
device-verification risk (ECO Start/Stop) that belongs to Taras's `hardware-verify` gate, not to the
code, plus two minor notes.

Verdict: **approved** · blockers: 0 · majors: 1 (device-verify, not a code defect) · minors: 2

---

## Why the safety-critical guarantee holds (the thing that mattered most)

Traced every path to `EngineOffAction.Stop`. The property that makes it safe is structural, in
`EngineOffPromptController.evaluate` (`EngineOffWatchdog.kt:111-135`):

- **Any single `RUNNING` reading wins the tick.** `when(rpm){ RUNNING -> reset() }` runs *first*
  (`:118`), zeroing `rpmZeroSinceMillis`, `keepMonitoring`, `absentSinceMillis`. Then
  `confirmedAtMillis = rpmZeroSinceMillis?.plus(...)` is `null` (`:126`) → the guard returns `None`
  (`:128`). So a `RUNNING` reading in a tick can **never** produce `Stop`, regardless of prior state.
  Verified by `EngineOffWatchdogTest` "RPM running never triggers anything" + "clears an in-progress
  debounce" + "also clears a chosen Keep monitoring".
- **`AMBIGUOUS` never advances toward a stop by itself.** `AMBIGUOUS -> Unit` (`:120`) — it neither
  starts nor clears the debounce clock. A BLE dropout / a mode-22/KWP cycle that displaces the RPM
  poll for a beat cannot manufacture an engine-off. It only lets an *already-latched* `OFF` clock keep
  aging (see minor #1 for the one edge this opens).
- **A fresh RPM==0 is the only trigger, and it means the engine is actually not combusting.** Confirmed
  against the decode path: `applyPoll` (`RealVehicleDataSource.kt:256-273`) stores a `Sample` **only**
  on `PollOutcome.Value` (a `ResponseParser` success). A `NO DATA`/parse error →
  `PollOutcome.Skipped` (`:261`) → the sample is *not* updated, ages, and goes `stale` →
  `rpmSignal` returns `AMBIGUOUS` (`EngineOffWatchdog.kt:23`), not `OFF`. A link failure →
  `PollOutcome.LinkDown` → `forceStale=true` on the whole map (`:210-213`, `:332`) → `AMBIGUOUS`. So a
  "PID returns 0 on error" / "bad frame decodes to 0" does **not** reach the `OFF` branch. `OFF`
  requires a valid 010C decode of 0 — i.e., the engine is genuinely stopped/stalled/auto-stopped.
- **Staleness protects the congested-link case.** When RPM stops answering mid-drive, the last
  successful sample stays at its last value (e.g. 2400) and reads `RUNNING` until it crosses the
  freshness window, then flips straight to `AMBIGUOUS` — it never passes through a fresh 0.

Net: false-stop-while-actually-driving is not reachable from a bad frame, a dropout, a congested link,
or a displaced poll cycle. The only genuine-RPM-0 events are engine-off, stall, and **ECO Start/Stop**
— which is finding #1.

## Drain fix, Keep-monitoring, two-watchdog interaction — all verified

- **Fixes the overnight drain (the defeat scenario).** The stamp/grace are driven by
  `rpmSignal(dataSource.readings.value)` (`ObdConnectionService.kt:341`), **not** "any data." Pinned by
  `ObdConnectionServiceTest` "engine-off confirmed, user absent, past the silent grace stops — even
  with non-RPM data still emitting" (`:433`), which keeps a `coolant` reading trickling and still
  stops. This is exactly the mechanism the root-cause trace said OBD-69's "any non-empty emission
  re-stamps the timer" could never catch.
- **Keep-monitoring cannot defeat the drain.** `evaluateAbsent` (`EngineOffWatchdog.kt:146-149`) does
  **not** consult `keepMonitoring` — only `evaluatePresent` does (`:141`). So Keep suppresses while
  present; the instant presence flips false the absent path runs a fresh 30 s grace and stops.
  Verified by "Keep monitoring then going absent falls through to a fresh silent grace". No
  keep-alive-forever path (the only way to stay up is the user deliberately holding the app foreground
  **and** screen interactive all night — that's the explicit heat-soak feature, and a normal screen
  timeout ends it).
- **Two watchdogs converge safely.** Both call the extracted `stopForIdle()`
  (`ObdConnectionService.kt:369-373`): `disconnectLinkOnUserStop` + `stopForeground(STOP_FOREGROUND_REMOVE)`
  + `stopSelf()`, all idempotent against an already-stopping service; `onDestroy` cancels
  `serviceScope` (`:303`) and releases the wake lock, so a second tick from the other watchdog is a
  no-op. The engine-off watchdog releases the wake lock **only** via this shared `onDestroy` path — it
  never touches OBD-69's `refreshWakeLock`, so there's no interaction with the wake-lock refresh.
- **OBD-69 restored exactly.** `IdleWatchdog.kt` is untouched vs `main` (empty diff);
  `launchDataIdleReset` / `launchIdleWatchdog` / `IDLE_TIMEOUT_MILLIS` (20 min) / the ~25 min
  wake-lock backstop are all intact. The only change to the idle path is that
  `checkIdleAndMaybeStop` now calls the shared `stopForIdle()` instead of inlining the identical three
  lines — behavior-preserving. Recording-inhibit is applied to **both** watchdogs
  (`checkIdleAndMaybeStop:320` and `evaluate`'s `recording ->` guard `:128`).
- **Lazy grace seeding is correct at 1 Hz.** `evaluateAbsent` seeds `absentSinceMillis` on the first
  tick that observes confirmed-off-and-absent (`:147`), not retroactively. At the production
  `ENGINE_OFF_WATCHDOG_INTERVAL_MILLIS = 1000L` cadence the grace is at most ~1 s long, never skipped
  or doubled; the pure contract ("tick me densely") is honored by the real 1 s loop
  (`launchEngineOffWatchdog:588-593`). The dedicated test comment at
  `EngineOffWatchdogTest.kt:167-172` documents this and it checks out.
- **`@Suppress`es are justified, not masking smells.** `TooManyFunctions` on the service and
  `LongParameterList` on the ViewModel ctor both track real, cohesive growth (one more genuine Hilt
  collaborator / three small decision-and-act methods), consistent with the existing
  `TooManyFunctions` on the same classes.

---

## Findings (most-severe-first)

### 1 — MAJOR (device-verify, not a code defect): ECO Start/Stop auto-shutoff at a long light can fire a false teardown while "present"

`EngineOffWatchdog.kt:162` — `ENGINE_OFF_DEBOUNCE_MILLIS = 45s`.

The Winnebago Revel's Sprinter almost certainly has Mercedes ECO Start/Stop, which genuinely cuts the
engine (RPM → a real, fresh 0) at a stop. The code correctly reads that as engine-off — it *is* off —
but it can't distinguish "auto-stopped at a red light" from "parked." Concrete failure:

- Van at a long light (60-120 s is common), phone mounted, screen on, app foreground ⇒ `isUserPresent()`
  returns **true**.
- ECO Start/Stop cuts the engine. After 45 s of sustained fresh RPM==0 the debounce confirms → present
  branch → `ShowPrompt` 20 s countdown.
- Driver is watching the road, not the phone, and doesn't tap "Keep monitoring" in 20 s → `Stop` →
  service torn down, link disconnected.
- Light goes green, engine restarts, but the gauges are dead until a manual reconnect/relaunch —
  a mid-traffic regression of exactly the "driving stays alive" contract the spec promises.

This is the one real-world path to a false stop, and it sits squarely inside Taras's `hardware-verify`
gate — so it does not block the code, but it must be the **#1 on-vehicle check** before that gate
clears. Fix options if it reproduces, in order of preference:

- Gate the engine-off trigger on "the vehicle has been stationary a while" using the GPS `speedSource`
  the app already has — but note a red-light stop is also speed 0, so this only helps if combined with
  "no recent motion within the last N seconds," and still won't separate a 2-minute light from a park.
- Extend the debounce past the van's observed max auto-stop dwell (measure it; ECO stop/start commonly
  auto-restarts to protect the battery — if that ceiling is < the debounce, the restart's `RUNNING`
  reading resets everything and the problem evaporates). This is the cheapest fix if the ceiling is low.
- Accept the present-path 20 s prompt as the guard and make it more noticeable (haptic), treating the
  rare tap-miss as acceptable. Weakest.

Recommend Taras explicitly run test-case (a)/(c) at a genuinely long light with ECO Start/Stop active
before clearing `hardware-verify`.

### 2 — MINOR: `AMBIGUOUS` preserves a latched debounce indefinitely (stall + coincident dropout that swallows the restart)

`EngineOffWatchdog.kt:120` — `AMBIGUOUS -> Unit`.

The debounce is documented as "OFF continuously for 45 s," but it's implemented as "first OFF, no
`RUNNING` since, 45 s elapsed" — `AMBIGUOUS` rides through without re-confirming `OFF`. That's the
intended hiccup-tolerance, but it opens a narrow window: engine stalls (fresh `OFF` latches the
clock), driver restarts within a few seconds, **and** the restart's `RUNNING` readings all happen to
be lost to a coincident BLE dropout (all `AMBIGUOUS`) for the rest of the 45 s. The stale clock then
confirms engine-off while the engine is actually running. Requires three coincident conditions
(stall + immediate restart + a dropout precisely covering the restart's RPM polls), so severity is
low, but it is the one theoretical crack in the guarantee. If you want to close it cheaply: have
`AMBIGUOUS` *not* clear the clock (as now) but require the confirmation tick to also see a
**non-stale** state, or reset the debounce if `AMBIGUOUS` persists beyond some bound (a long pure-
`AMBIGUOUS` stretch is a link problem OBD-69's backstop already owns, not an engine-off). Not
blocking; worth a comment acknowledging the implemented contract is "no RUNNING since," not "continuous
OFF."

### 3 — MINOR: a null `PowerManager` is read as "absent," biasing toward a silent stop

`ObdConnectionService.kt:355-357` — `getSystemService<PowerManager>()?.isInteractive ?: false`.

If `PowerManager` were ever null, `isUserPresent()` is false → the absent path can silently stop even
with the user watching. `PowerManager` is effectively always present on a real device, so this is
theoretical, but the null-defaults-to-absent choice is the less-safe direction for the "don't yank it
from under a watching user" intent. Leave as-is or default the null case to the present branch; noting
for completeness.

---

## Confirmations (checked, no action)

- Presence semantics (`isForeground && isInteractive`) behave correctly across all four corners:
  fg+screen-off → absent (matches "screen off = walked away" proxy); bg+screen-on → absent; OS-backgrounded
  (Doze) → absent → the drain stop. Only fg+interactive keeps the prompt alive.
- `ShowPrompt(deadlineMillis)` is an absolute deadline from confirmation; presence toggling in/out of the
  20 s window recomputes the same deadline (present) or abandons it for a fresh 30 s grace (absent) — no
  countdown reset exploit, no double-fire.
- Coroutine/thread-safety mirrors `RecordingBridge`/`IdleActivityTracker`: `@Volatile activeController`,
  `MutableStateFlow` bridges, watchdog on `serviceScope` (cancelled with the service), `disconnectLinkOnUserStop`
  deliberately off-scope so the disarm survives cancellation. `attach(null)` in `onDestroy` clears the dialog.
- `EngineOffPromptDialog` `onDismissRequest = {}` correctly prevents back/outside-tap from silently
  cancelling monitoring; the host reduces the deadline to a pure `Int` countdown (snapshot-testable).

Gate: green per author (GATE PASS, EngineOffWatchdogTest 20/20, ObdConnectionServiceTest 26/26);
not independently re-run per brief. `hardware-verify: true` remains Taras's gate — **not cleared**.

---

## Fix list (must-fix before approve)

- [x] ✅ Never-stop-mid-drive guarantee verified sound at the code level — `RUNNING` always resets and
  returns `None`; a fresh RPM==0 is only produced by a genuine engine-off (decode errors → stale →
  `AMBIGUOUS`). No code change required to approve.
- [x] ✅ Drain fix confirmed RPM-driven (immune to cooling-data trickle) and OBD-69 restored exactly;
  shared `stopForIdle()` idempotent; recording inhibits both watchdogs.
- [ ] MAJOR device-verify (Taras, gated by `hardware-verify`, non-blocking for code merge): confirm
  ECO Start/Stop at a long light does not fire a false teardown while the phone is mounted/present;
  if it does, apply one of finding #1's mitigations before clearing `hardware-verify`.
- [ ] MINOR (optional): document/close the `AMBIGUOUS`-preserves-latched-clock edge (finding #2).
- [ ] MINOR (optional): reconsider null-`PowerManager` defaulting to absent (finding #3).
