---
issue: OBD-87
round: 1
reviewer: rev-correctness
verdict: approved
gate: green
reviewed-commit: 3c02d0d
covers: [OBD-87]
hardware-verify: false
---

Combined-lens review (correctness + platform + architecture) of the instant-MPG computed
gauge. I did not build this branch; I read it adversarially, hand-checked the arithmetic,
traced the guard/smoother logic, and re-ran the full gate.

**Verdict: approved.** Compute and guards are correct, the smoother is deterministic and its
dedup is real and tested, module isolation holds (only the additive enum touches `:core`), and
the gate is green on `3c02d0d`. The one thing the builder flagged — a cosmetic
`UnknownPid("instantMpg")` logcat line per session start — is genuinely cosmetic, non-blocking,
and has a clean in-`:app` fix worth a follow-up (N1 below).

## What I verified

### Compute correctness (`InstantMpgCompute`, `InstantMpgDataSource.kt:104-131`)
- Formula is `speed_mph / (fuelRate_Lph / 3.785411784)`, algebraically `speed * G / fuelRate`.
  Hand-checked both test vectors: 65 mph / 3.2 L/h = 65 × 3.785411784 / 3.2 = **76.891177** mpg
  (`InstantMpgDataSourceTest.kt:43`), and 60 mph / 3.0 L/h = **75.708236** (`:254`). Both exact.
  Brief's own 60 mph / 10 L/h → 22.7 mpg also holds. `LITERS_PER_US_GALLON = 3.785411784` is the
  exact legal US gallon (`:100`).
- **Uses the corrected mph, not raw km/h.** The decorator wraps `SpeedCorrectionDataSource`
  (`DataSourceModule.kt:81-86`) and is the outermost link returned by
  `provideVehicleDataSource`, so `readings["speed"]` is the GPS-corrected mph value. Chain order
  confirmed: `RealVehicleDataSource` (raw km/h) → `DisplayUnitDataSource` (→ mph) →
  `SpeedCorrectionDataSource` (× factor) → `InstantMpgDataSource`. Correct placement per the plan.
- `SPEED_ID`/`FUEL_RATE_ID` are read from `ProtocolPidIds.SPEED`/`.FUEL_RATE`
  (`InstantMpgDataSource.kt:85-86`), not string literals — no drift risk.

### Guards emit absence, never a fabricated number (`InstantMpgDataSource.kt:113-127`)
Each verified in code and pinned by a test:
- null speed or null fuelRate → `null` (`:113`; tests `:67`, `:72`).
- `fuelRate.value <= 0.0` → `null` — covers both the decel fuel-cut zero and a negative sensor
  dropout, so no `inf` (`:113`; tests `:77` zero, `:82` negative). The `<= 0` single guard is
  cleaner than a separate `!= 0` + sign check and provably can't reach the division.
- `speed.value == 0.0` with fuel flowing → **0.0**, a well-defined answer, not absence
  (`:120-121`; test `:87`).
- `!mpg.isFinite()` backstop → `null` (`:124`). Reachable in exactly one real case — a NaN speed
  (NaN `!= 0.0`, so it flows to the division and yields NaN) — which the backstop catches. Not
  dead code.
- Freshness = `minOf` of the two timestamps, `stale = a.stale || b.stale`, mirroring boost
  (`:116-117`; tests `:47`, `:59`). Correct.

### Smoother + double-seed dedup (`InstantMpgSmoother`, `InstantMpgDataSource.kt:143-178`)
- Deterministic: `accept(raw, now)` takes `now` explicitly; the decorator supplies
  `clock.instant()` (`:80`) and tests drive it with `Clock.fixed` / chosen `Instant`s. No
  internal wall-clock read.
- Window eviction math is correct FIFO: `while first-sample age > window: removeFirst`
  (`:167-169`). Hand-traced all four smoother tests (`:134`, `:150`, `:167`, `:187`) — eviction,
  gap-skip-without-reset, empty-window absence, and dedup all produce the asserted values.
- Gap handling: a `null` raw is skipped, not zeroed and not window-resetting — the tile keeps
  answering from surviving samples until they age out (`:158`; test `:151`). This is what stops
  the tile blanking on every coast.
- Absence when nothing survives: empty deque → `null` (`:172`; tests `:168`, `:181`).
- **Double-seed dedup is real and correct.** `stateIn(..., Eagerly, withInstantMpg(delegate.readings.value))`
  computes the seed by folding sample_0, then the StateFlow collector replays that same current
  value, folding an identical-timestamp sample_0'. Because a StateFlow's replay is always the
  collector's first observation, the duplicate is *always* the immediately-preceding sample, so
  the `samples.lastOrNull()?.timestamp == raw.timestamp` → `removeLast` guard (`:159-162`) is
  sufficient — it can't miss a non-adjacent duplicate for this scenario. Test `:187` proves the
  average stays 15.0 (2 distinct samples), not 13.33 (3). Verified the average isn't skewed by
  stale/duplicate samples.

### The `UnknownPid("instantMpg")` concern (builder flag #1) — judgment
Traced `RealVehicleDataSource.planFor` (`core/protocol/.../RealVehicleDataSource.kt:137-151`):
requested ids are resolved via `PidCatalog.byId(id) ?: overrides.extraChannels…`; if unresolved
and `id != PidIds.BOOST`, it emits `PollEvent.UnknownPid(id)` and `mapNotNull` **drops it**.
- (a) `instantMpg` is genuinely dropped from the wire poll list — `PidCatalog.byId("instantMpg")`
  is null (pinned by `InstantMpgDataSourceTest.kt:234`) and it isn't BOOST, so it never becomes a
  polled `PidSpec`. Not fetched.
- (b) The only effect is one `PollEvent.UnknownPid` per session `start()`. Purely cosmetic
  logcat; no functional impact. Boost avoids it only because it has the explicit
  `id != PidIds.BOOST` carve-out; `instantMpg` has none.
- (c) **A clean in-`:app` fix exists, no `:core:protocol` change needed.** `InstantMpgDataSource`
  is the layer that synthesizes `instantMpg`, so it can strip its own computed id before
  delegating downward — `override fun start(pids) = delegate.start(pids.filterNot { it.id == INSTANT_MPG_PID_ID })`.
  This removes the log at its source and is arguably more correct (no downstream layer should be
  asked to poll a value produced above it). The existing pass-through `start` test
  (`InstantMpgDataSourceTest.kt:293`) uses `PidCatalog.definitions`, which never contains
  `instantMpg`, so the filter wouldn't break it.
- **Verdict: acceptable-as-is, non-blocking.** Recommend the in-`:app` filter as a follow-up
  (N1), not a required fix.

### `fuelRate` is correctly polled now
`FUEL_RATE_PID_DEFINITION` is added to `GAUGE_CATALOG` (`GaugeCatalog.kt:190`), so it flows into
`ActivePollSet.activePids()` (`ActivePollSet.kt:51`). `fuelRate` resolves in `PidCatalog` —
`PidRegistry.fuelRate` (id `ProtocolPidIds.FUEL_RATE`, PID `0x5E`) is a registered
`StandardPidSpec` (`PidRegistry.kt:397-406`, listed at `:454`) — so it goes on the wire and
produces no UnknownPid. Confirmed.

### Additive enum, catalog wiring, no conversion
- `MILES_PER_GALLON` appended at the **end** of `MeasurementUnit` (`MeasurementUnit.kt:57`);
  nothing reordered or renamed. `git diff --stat -- core/` shows this is the *only* `:core`
  change (11 insertions, additive) — no `:core:protocol`/`:core:ble` touch, module isolation holds.
- Both new defs are swap-only (in `GAUGE_CATALOG`, absent from `DASHBOARD_PIDS`) and neutral (no
  `ThresholdConfig.seed` entry → `classify` returns NEUTRAL). Pinned by `GaugeCatalogTest.kt`
  new tests + parity `FUEL_RATE_PID_ID == ProtocolPidIds.FUEL_RATE`
  (`GaugeCatalogProdParityTest.kt`). MPG `verified = false`, fuelRate `verified = true`.
- MPG is **not** unit-converted: `DisplayUnitDataSourceTest.kt:126` asserts an `instantMpg`
  reading passes through unchanged (no `PROTOCOL_UNITS` entry), and the compute sits a layer
  outside `DisplayUnitDataSource` anyway. `UnitConversion` untouched. Confirmed pass-through.

### Demo flavor + Roborazzi
- `InstantMpgDataSource` lives in `app/src/prod/` only; `grep` finds no `InstantMpg` reference in
  `app/src/demo/`. Demo's DI chain is unwrapped, so the MPG tile shows `—` in demo — acceptable
  per the issue's flavor note.
- Only `gauge_add_palette.png` re-recorded. Correct: the swap/add palette is flavor-common (built
  from `GAUGE_CATALOG`), so it now lists the two new offerable gauges; no other screenshot depends
  on catalog membership. `verifyRoborazzi` PASS.

### Gate (re-run on 3c02d0d, first run, no flake)
```
PASS assembleDebug · PASS test · PASS ktlintCheck · PASS detekt
PASS verifyRoborazzi · PASS assembleDemoDebug · PASS module-isolation
GATE: PASS
```

## Fix list

Verified:
- ✅ Compute formula hand-checked correct (`speed * G / fuelRate`; 65/3.2=76.891177, 60/3.0=75.708236, exact) and it consumes the GPS-corrected mph (decorator is outermost, wraps `SpeedCorrectionDataSource`).
- ✅ All four guards emit absence, never a fabricated number, each test-pinned (null inputs; `fuelRate<=0` covering zero decel-cut and negative dropout; `speed==0`→well-defined 0; `!isFinite` NaN backstop).
- ✅ `InstantMpgSmoother` deterministic (explicit injected `now`); window eviction, gap-skip-without-reset, empty-window absence, and the double-seed dedup all correct and tested — average not skewed by stale/duplicate samples.
- ✅ `MILES_PER_GALLON` strictly additive (appended at enum end; the only `:core` change, 11 lines) — module isolation holds, no `:core:protocol`/`:core:ble` touch.
- ✅ Both defs swap-only + neutral (in `GAUGE_CATALOG`, absent from `DASHBOARD_PIDS`, no `ThresholdConfig.seed`); `FUEL_RATE_PID_ID` parity test == `ProtocolPidIds.FUEL_RATE`; MPG `verified=false`, fuelRate `verified=true`; MPG not unit-converted (pass-through, sits outside `DisplayUnitDataSource`); fuelRate correctly polled (resolves in `PidRegistry`).
- ✅ Demo has no `InstantMpg` wiring → MPG tile shows `—` (acceptable per issue); only `gauge_add_palette.png` re-recorded (catalog-driven palette); `verifyRoborazzi` PASS.
- ✅ Gate green on 3c02d0d — my own re-run, first pass, no flake (all 7 checks PASS).

Non-blocking notes:
- N1 (optional, follow-up): eliminate the cosmetic `UnknownPid("instantMpg")` logcat line by
  filtering `INSTANT_MPG_PID_ID` out of the list `InstantMpgDataSource.start` delegates
  downstream. Purely in-`:app`, no `:core:protocol` change. Non-blocking.
- N2 (optional, note): `InstantMpgSmoother` assumes monotonic sample timestamps for front-only
  eviction; readings are timestamped monotonically in practice, so no action needed — recording
  only so a future out-of-order source doesn't surprise anyone.
