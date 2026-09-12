---
issue: OBD-82
round: 1
reviewer: rev-correctness
verdict: approved
gate: green
reviewed-commit: ae28b34
covers: [OBD-82]
hardware-verify: true
---

# OBD-82 — Connect must restart the foreground service (in-session reconnect) — round 1

**Verdict: approved.** Zero blockers, zero majors. The fix is a one-line addition to
`MainActivity.connectNow()` — `ContextCompat.startForegroundService(...)` before `link.connect()`,
mirroring `onCreate` — plus a unit test that pins the "already-Ready at collection start" resume
path. I verified the builder's central claim adversarially: **the fix is NOT a no-op in the reported
scenario, and no `ConnectionServiceController`/`ObdConnectionService` change is needed.** Gate
**GREEN** — I re-ran `tools/gate.sh` myself: PASS on all 7 (`assembleDebug`, `test`, `ktlintCheck`,
`detekt`, `verifyRoborazzi`, `assembleDemoDebug`, `module-isolation`).

Note on the merge gate: `hardware-verify: true`. This approval clears the *code*; the issue's device
gate (🖐 Taras: engine-off long enough to trip the idle/engine-off stop → tap Connect → gauges
resume without a quit+relaunch) is a separate, non-automatable precondition for merge and is not
satisfied by this review.

## What I verified (correctness-first)

1. **The already-Ready race — the crux — resolves correctly, claim VERIFIED.**
   `ConnectionServiceController.start()` (`service/ConnectionServiceController.kt:89-109`):
   - `wasReady` is declared `var wasReady = false` **inside** the `scope.launch { }` block
     (`:96`) — a coroutine-local, **not** a class field, **not** shared or retained across
     `start()` calls. Every service restart builds a brand-new controller
     (`ObdConnectionService.onCreate:182-189`, `ConnectionServiceController(...).also { it.start() }`),
     so its collector always begins with `wasReady == false`, regardless of what the link was doing
     before this instance existed. Confirmed per-`start()`-invocation.
   - `combine(dataSource.readings, dataSource.connection)` over two `StateFlow`s (`:97`): a
     `StateFlow` always holds a current value, so `combine` emits the current tuple **synchronously
     on first collection**. If `connection.value == Ready` at that moment, the first emission
     computes `becameReady = (Ready && !false) = true`; `intendsToRun` was set `true` at `:91`
     before the launch, so `:101-104` re-issues `dataSource.start()` and the parked poll loop
     resumes — no fresh Connecting→Ready transition required. Confirmed the first combined emission
     reflects the current Ready state.
   - Call accounting: `:93` is one unconditional `dataSource.start()`; `:104` is the
     `becameReady`-driven restart. So an already-Ready link at `start()` yields exactly **two**
     `start()` calls — which is what the new test asserts.
   - **If the reasoning were wrong** (e.g. `wasReady` seeded from the link's real prior state, an
     edge-only impl), the first emission would read `becameReady == false`, the resume would never
     fire, and OBD-82's "Connect does nothing" would persist. It isn't wrong — the local-`false`
     init is exactly what makes an already-Ready link a `becameReady` edge for each fresh service.
2. **The fix actually resumes after a service stop (end-to-end).** OBD-69 idle-stop / OBD-71
   engine-off-stop both reach `stopSelf()` → `onDestroy` → `controller.stop()` (poll loop parked).
   A later Connect tap now calls `startForegroundService`, which — because the service is stopped —
   makes Android **create a fresh `ObdConnectionService`**, whose `onCreate` builds a new controller
   and calls `start()`. That fresh `start()` is what re-issues `dataSource.start()` and (via the
   already-Ready path above, or a normal Connecting→Ready edge) resumes polling. The builder's
   claim that no service-side change is needed holds: `onCreate` already starts a fresh controller.
3. **Ordering (`startForegroundService` before `link.connect()`) is correct and race-safe.** Placing
   the service start first means the fresh controller is (re)built and collecting before the link is
   asked to move. But correctness does **not** depend on winning that race: even if `connect()` drove
   the link to `Ready` before the new collector attaches, the per-launch `wasReady=false` turns that
   already-`Ready` first emission into a `becameReady` — the exact case test #1 pins. So the fix is
   robust in both orderings; the chosen order is simply the cleaner of the two.
4. **Idempotency on an already-running service is harmless.** A repeat `startForegroundService`
   while the service is alive is delivered to `onStartCommand` (`ObdConnectionService.kt:221-231`),
   which acts **only** on `ACTION_STOP` and otherwise does nothing (returns `START_NOT_STICKY`) — it
   does not build or re-`start()` a second controller, so there is no duplicate poll loop. The
   existing controller keeps collecting. (Minor wording note, non-blocking: `MainActivity`'s new
   KDoc says "`ConnectionServiceController.start()` no-ops while already running" — accurate for the
   path where it *is* called, though in the already-running case `onStartCommand` doesn't call it at
   all; the net effect — no duplicate loop — is what the comment is asserting and it's correct.)
5. **The added test exercises the real path and would fail an edge-only controller.**
   `ConnectionServiceControllerTest`'s "resumes polling when the link is already Ready at collection
   start" (`:199-211`) presets `dataSource.setConnection(LinkState.Ready)` **before** `start()`, then
   asserts `startCallCount == 2`. With only two `start()` sites (unconditional `:93` + `becameReady`
   `:104`) and no post-start connection changes, `2` is reachable **only** if the first emission's
   already-Ready state produces a `becameReady` restart — so the assertion uniquely pins the fix and
   would drop to `1` (fail) under an edge-only implementation. Not a tautology. The assertion is
   also consistent with the sibling tests' accounting (`start … exactly once` → 1;
   `reaches Ready` via a transition → 2). `UnconfinedTestDispatcher` makes the launched collector run
   eagerly enough to process the initial emission before the assertion, matching every other test in
   the file (and the gate's green `test` run).
6. **Scope + isolation clean.** Diff is exactly two files (`MainActivity.kt`,
   `ConnectionServiceControllerTest.kt`) — no controller/service source change, no
   `DashboardViewModel` change (the optional complementary safety-net was correctly left out as
   out-of-scope). No `:core:ble`/`:core:protocol` imports added (the symbols used —
   `ContextCompat`, `Intent`, `ObdConnectionService` — were already imported for `onCreate`).
   `module-isolation` gate step PASS.

## Fix list

- ✅ Already-Ready resume — `wasReady` is coroutine-local and `false` per `start()`; `combine`'s
  synchronous first emission over the Ready `StateFlow` yields `becameReady`, resuming the parked
  poll loop. The fix is not a no-op in the reported scenario.
- ✅ End-to-end resume after a service stop — a fresh `startForegroundService` recreates the service,
  whose `onCreate` starts a new controller; no service-side change required, as claimed.
- ✅ Ordering (service-start before `connect()`) correct and race-safe in both orderings.
- ✅ Idempotency on an already-running service — `onStartCommand` acts only on `ACTION_STOP`; no
  duplicate poll loop.
- ✅ New test genuinely exercises the already-Ready path (`startCallCount == 2`) and would fail an
  edge-only controller — a real regression pin, not a tautology.
- ✅ Scope + module isolation — two files only, no `:core:ble`/`:core:protocol` imports, no
  out-of-scope changes.

## Tests

- Pure JUnit: `ConnectionServiceControllerTest` gains "resumes polling when the link is already Ready
  at collection start" (asserts `startCallCount == 2`), alongside the existing storm/backoff/edge
  suite it slots into. `test` gate step green.
- No Roborazzi/UI change (the fix is Activity connect-path glue with no visual surface).

## Hardware checklist

`hardware-verify: true` — the merge gate requires the issue's `## Hardware checklist` with Taras's
observed values: reproduce symptom 2 (engine off long enough for the idle/engine-off stop, or wait
out the 20-min idle-stop) → tap **Connect** → gauges resume **without** a quit+relaunch. This code
review does not and cannot satisfy that gate; it remains Taras's on-device call, foldable into the
same van trip as OBD-71/OBD-78 verification.
