# :app

Android application. Compose + Material 3, Hilt-wired end to end. Depends on `:core:model`
unconditionally; `:core:testing` only on the `demo` flavor (see "Build flavors" below).
`:core:ble` is a `debugImplementation`-only dependency (OBD-19's debug console, below) — the
main dashboard flow's real `ObdLink`/`:core:protocol` wiring is still OBD-25. No
`:core:protocol` at this stage.

## Build flavors (OBD-12)

One flavor dimension (`environment`), two flavors, both sharing `src/main/` (Compose UI,
theme, `MainActivity`, `ObdGaugeApplication` — none of it depends on either flavor):

- **`demo`** — `src/demo/kotlin/.../di/DataSourceModule.kt` binds `VehicleDataSource` to
  `FakeVehicleDataSource(Scenario.GRADE_CLIMB)` wrapped in `RestartAnchoredDataSource`
  (`src/demo/.../datasource/`), which stamps each `start()` with wall-clock time and supplies
  the EPOCH-re-anchoring `Clock` the stale-age math needs (see Known limitations). Zero
  Bluetooth permissions (the manifest declares none, common or flavor-specific).
  `:core:testing` is a `demoImplementation` dependency — see the HARD CONSTRAINT below.
- **`prod`** — `src/prod/kotlin/.../di/DataSourceModule.kt` binds `VehicleDataSource` to
  `StubVehicleDataSource` (`src/prod/.../datasource/`), a placeholder that sits
  `LinkState.Disconnected` with empty readings and no-op `start`/`stop`. Real `ObdLink` →
  `:core:protocol` → `VehicleDataSource` wiring lands in OBD-25.

**HARD CONSTRAINT**: `:core:testing` (and therefore `FakeVehicleDataSource`/`Scenario`) must
never reach the `prod` runtime classpath. Enforced by scoping the dependency to
`demoImplementation` in `app/build.gradle.kts` and keeping every reference to
`com.revel.obdgauge.testing` inside `src/demo/` or `src/testDemo/`. Verify with:
```
./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath | grep -Ei "junit|turbine|coroutines-test|core:testing"
```
(expect empty output).

Unit tests that exercise `FakeVehicleDataSource` (`DashboardViewModelTest`,
`DashboardScreenTest`, `DashboardScreenshotTest`, `ThresholdConfigTest`,
`ConnectionBannerTest`) live in `src/testDemo/` and only run as `testDemoDebugUnitTest`.
`GaugeFormattingTest`/`BoostArcTest` have no `:core:testing` dependency and stay in the common
`src/test/`, running for both `testDemoDebugUnitTest` and `testProdDebugUnitTest`. The `test`
aggregate task runs both variants; `testProdDebugUnitTest` only ever sees the two
flavor-agnostic suites.

Unit tests remain debug-variant-only (both flavors) via the existing
`androidComponents { beforeVariants(...) { variant.enableUnitTest = false } }` release gate.

`./gradlew :app:assembleDemoDebug` output: `app/build/outputs/apk/demo/debug/app-demo-debug.apk`.
`./gradlew :app:assembleProdDebug` output: `app/build/outputs/apk/prod/debug/app-prod-debug.apk`.
The plain `assembleDebug`/`test`/`ktlintCheck` aggregate tasks (as run by `tools/gate.sh`)
transparently cover both flavors. **Roborazzi's task names became flavor-qualified**:
`verifyRoborazziDebug` no longer exists — it's now `verifyRoborazziDemoDebug` (the only
variant with screenshot tests; `verifyRoborazziProdDebug` exists too and passes trivially,
since `DashboardScreenshotTest` lives in `src/testDemo/`).

`detekt`'s plain (non-variant-aware) task defaults to scanning `src/main/kotlin` only — this
was already true before OBD-12 (verified against `main`; test sources were never in its
scope). `app/build.gradle.kts`'s `detekt { source.setFrom(...) }` now also lists
`src/demo/kotlin` and `src/prod/kotlin` so the new DI wiring and stub don't silently escape
the gate; `src/test*/` intentionally stays out of scope, matching prior behavior.

## Public surface

- `ObdGaugeApplication` — `@HiltAndroidApp` root.
- `MainActivity` — `@AndroidEntryPoint`, single-activity. No `screenOrientation` lock in the
  manifest: landscape is the primary target (a phone on a dash mount), but portrait is fully
  supported, not just tolerated. Hosts `GaugeDashboard` fed by a Hilt-injected
  `DashboardViewModel`, or (OBD-21) `SettingsRoute` — a plain `mutableStateOf<Boolean>` toggle
  swaps between them, no nav library. Also applies `DashboardViewModel.keepScreenOn` to
  `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` live via a `LaunchedEffect` — see "Settings
  screen (OBD-21)" below.
- `ui/theme/` — Material 3 theme, dark by default (`ObdGaugeTheme(forceDark = true)`) since
  this app runs on a dash mount, usually at night. `Color.kt` also holds the threshold-zone
  palette (`GaugeGreen`/`GaugeAmber`/`GaugeRed`/`GaugeNeutral`/`GaugeTrackNeutral`/
  `GaugeStaleDim`); `Type.kt` holds `GaugeValueTextStyle`, the bespoke large numeric style for
  gauge tiles.
- `gauge/` — the OBD-10 gauge dashboard v1:
  - `ThresholdZone` / `GaugeThresholds` / `ThresholdConfig` — config-table threshold
    coloring (not hardcoded per gauge). Seed values: coolant green `<220` / amber `220-230`
    / red `>230`; trans green `<200` / amber `200-250` / red `>250` (the build plan's
    "amber 200-240 / red >250" leaves 240-250 unspecified — folded into amber here); oil
    green(normal) `<=235` / amber `>235`, no red band; boost neutral (no color coding).
    `ThresholdConfig.seed` is keyed by `ScenarioChannel`/`PidDefinition.id` strings.
    OBD-21 makes this user-editable via `AppSettings.thresholdOverrides`/`effectiveThresholds()`
    layered on top — `seed` itself is unchanged, still the default table.
    `classify(id, value, thresholds = seed)` gained an optional third parameter for that layering;
    every pre-OBD-21 two-arg call site is unaffected.
  - `GaugeFormatting.kt` — pure formatting functions (`formatGaugeValue`, `formatStaleText`,
    `unitSuffix`): whole-degree temps, one-decimal PSI (sign-correct for `-1 < value < 0`),
    "last seen Xs ago" for stale readings. Zero protocol math — display-unit conversion (OBD-21)
    happens one layer up, in `DashboardUiState.tileState`, before these functions ever see a
    value; see `gauge/UnitConversion.kt` and "Unit conversion" below.
  - `DashboardUiState.kt` — `GaugeTileUiState`/`DashboardUiState` plus the pure
    `toDashboardUiState(readings, connection, now, thresholds = ThresholdConfig.seed, units = UnitPreferences())`
    mapper shared by the ViewModel and tests (the last two params are OBD-21 additions with
    defaults matching pre-OBD-21 behavior exactly). `DashboardUiState.tileFor(id)` (OBD-20/21) is
    a new lookup helper `GaugeDashboard`'s gauge-order rendering and the sparkline wiring use.
    **OBD-42**: `toDashboardUiState` computes one `GaugeTileUiState` per `GAUGE_CATALOG` entry
    (not just the four fixed fields) — `coolant`/`transTemp`/`oilTemp`/`boost` are still named
    fields (every pre-OBD-42 call site is unaffected), and every *other* catalog id (today, just
    `rpm`) lands in the new `extraTiles: Map<String, GaugeTileUiState>` field, defaulted to
    `emptyMap()`. `tileFor(id)` falls back to `extraTiles[id]` for anything not one of the four —
    this is what lets a swap-picker mini-card and a tile that's just been swapped both resolve
    through the exact same lookup/formatting/classification code path (see "Gauge swap picker"
    below on why that single-code-path property is the whole point).
  - `DashboardPids.kt` — `DASHBOARD_PIDS`, the four `PidDefinition`s (`coolant`, `oilTemp`,
    `transTemp`, `boost`) this dashboard requests, plus `DASHBOARD_PIDS_BY_ID` (OBD-21, a
    `associateBy { it.id }` lookup other code uses instead of re-hardcoding a unit/label).
    `request`/`parse` are placeholder values unused by `FakeVehicleDataSource` (it replays by id,
    not by asking a dongle); the real mode-22 registry lives in `:core:protocol` and replaces
    this list at Phase-4 integration. **Deliberately NOT touched by OBD-42** — see `GaugeCatalog.kt`
    below for why the swap-picker's candidate list is a separate, broader file instead of an
    addition here.
  - `GaugeCatalog.kt` (OBD-42) — `GAUGE_CATALOG` (`DASHBOARD_PIDS` + `RPM_PID_DEFINITION`) and
    `GAUGE_CATALOG_BY_ID`, plus `candidateGaugesFor(currentId, gaugeOrder, catalog = GAUGE_CATALOG,
    isEligible = { true })`: the swap picker's candidate provider — `currentId` first, then every
    other `catalog` entry not already visible on a *different* tile and passing `isEligible`. See
    the "Gauge swap picker" section below for the full design (why `rpm` lives here and not in
    `DASHBOARD_PIDS`, and the `isEligible` verified-filter seam).
  - `DashboardViewModel` — `@HiltViewModel`; `StateFlow<DashboardUiState>` out, formatting
    only. Takes an injected `VehicleDataSource`, `java.time.Clock` (stale-text "seconds ago"
    math), and (OBD-21) `SettingsRepository` — combined three-way so a settings edit recolors/
    reformats `uiState` live. Also exposes `gaugeOrder`/`keepScreenOn` `StateFlow`s (derived from
    the same repository) and `sparklineFlow(id)` (OBD-20, backed by `SparklineHistoryHolder`) —
    see the "Sparklines"/"Settings screen" sections below. **OBD-42**: requests `GAUGE_CATALOG`
    (not `DASHBOARD_PIDS`) from `dataSource.start()`, and seeds `SparklineHistoryHolder` off
    `GAUGE_CATALOG` too — otherwise a swap candidate's channel (e.g. `rpm`) would never have
    readings/history to show at all, since `FakeVehicleDataSource` only emits ids it was asked
    for. Also exposes `swapGauge(oldId, newId)` — see "Gauge swap picker" below.
  - `DashboardScreen.kt` — `GaugeDashboard`: a settings-gear `Row` (OBD-21) alongside
    `ConnectionBanner` (OBD-11) at the top, then the tile layout (landscape: single `Row`;
    portrait: scrollable `Column`, tiles sized to content so nothing clips) in a
    `Modifier.weight(1f)` box below it. `safeDrawingPadding()` is applied once at the outer
    `Column`. Tiles are now rendered by iterating `gaugeOrder` (OBD-21, defaults to the original
    hardcoded coolant/trans/oil/boost order/visibility) through a `GaugeSlot` dispatcher that
    picks `GaugeTile` or `BoostTile` (arc, −2..+18 PSI, always neutral-colored — see
    `BoostArc.kt`) per id — always, whether or not that tile is picking (OBD-44: see "Gauge
    swap picker" below for why there is no longer a separate picker-mode composable to dispatch
    to). Each tile exposes a
    `testTag("gauge-<id>")` root with a `stateDescription` semantics property carrying the
    threshold zone name, plus `testTag("gauge-<id>-label")`/`testTag("gauge-<id>-value")` on the
    label/value text, and (OBD-20, when a `sparklines` entry exists for that id) a
    `testTag("gauge-<id>-sparkline")` leaf — this is how tests assert color/label/value/sparkline-
    presence without pixel-diffing. See "Gauge swap picker" below for the OBD-42 additions
    (`GaugeTileGrid`, `GaugeSlot`'s picker dispatch, `onLongPress`/`onTap` on `GaugeTile`/
    `BoostTile`, the `gauge-picker-scrim`).
  - `ConnectionBanner.kt` (OBD-11) — `ConnectionBanner(connection: LinkState)`, presentation
    only (no reconnect logic — that's OBD-23). `LinkState.Ready` renders nothing at all (no
    node); every other state renders a full-width bar: `Scanning`/`Connecting` get a spinner +
    status text, `Disconnected` a plain neutral line, `Error(cause)` the theme's error
    container plus a "Reconnecting…" affordance (intent, not a guarantee — presentation only).
    `testTag("connection-banner")` (present unless `Ready`) carries a `stateDescription` of the
    lowercase state name (`"disconnected"`/`"scanning"`/`"connecting"`/`"ready"`/`"error"`);
    `testTag("connection-banner-message")` and (`Error` only) `"connection-banner-reconnecting"`
    carry the text. `connectionBannerMessage`/`connectionBannerStateName` are pure functions,
    `internal` for test use.
- `di/DataSourceModule.kt` — now flavor-specific; see "Build flavors" above. Was a single
  `src/main/` file through OBD-10; split for OBD-12.

## Sparklines (OBD-20)

Per-gauge 5-minute rolling strip chart, `app/src/main/.../sparkline/`:

- `SparklineBuffer` (`SparklineBuffer.kt`) — pure Kotlin, no Compose/Android dependency. A ring
  buffer (`ArrayDeque<SparklinePoint>`) with two independent bounds: `trim(now)` drops anything
  older than `window` (5 minutes) relative to `now`, and a hard `capacity` (2 400, double the
  1 200 samples a steady 4 Hz feed produces in 5 minutes) catches bursty/out-of-order timestamps
  before the time-window trim would. `add(point)` appends then trims relative to the point's own
  timestamp — deliberately not wall-clock `Instant.now()`, so it behaves identically fed by a
  real clock or `FakeVehicleDataSource`'s scripted virtual one. `add` is also a no-op when
  `point`'s timestamp matches the most recently added sample's (review round-1 M4 — see
  `SparklineHistoryHolder`'s note below for why this matters).
- `downsampleSparkline(points, maxPoints = 120)` — deterministic, O(maxPoints) evenly-spaced-index
  downsampling (always keeps the first/last point), independent of the demo's default pixel
  budget flag `DEFAULT_MAX_SPARKLINE_POINTS`. Tested for window-trim correctness, determinism,
  and bound enforcement at a simulated 4 Hz × 300 s in `SparklineBufferTest`.
- `SparklineHistoryHolder` — owns one `SparklineBuffer` per gauge id and republishes a
  downsampled snapshot to a **per-id `StateFlow<List<SparklinePoint>>`**, fed from
  `DashboardViewModel`'s existing three-way `combine()` step (`onReadings(readings, now)` runs
  once per emission, before mapping to `DashboardUiState`). A stale or missing reading is simply
  not added to its buffer — `SparklineChart` turns the resulting time gap into a rendered break,
  so disconnects are never silently interpolated across. Because that `combine()` fires on *any*
  of its three inputs — including a settings edit or a connection-state change with the exact
  same readings map — `onReadings` can be called repeatedly with an unchanged reading; without
  `SparklineBuffer.add`'s same-timestamp dedup (review round-1 M4), each such tick would inject a
  duplicate point at an identical x-position. Covered by `SparklineHistoryHolderTest`.
- `SparklineChart` (Compose `Canvas`) — draws already-downsampled `points`, auto-scaled to their
  own min/max value and time span. The `Path` is created once via `remember` and `.reset()` on
  each draw rather than reallocated; the `Stroke` and each point's epoch-millis are likewise
  hoisted into `remember`s outside the draw block rather than recomputed on every draw pass. A
  gap between consecutive points wider than 2 s (several multiples of the 4 Hz interval, so
  ordinary jitter never trips it) breaks the line (`moveTo` instead of `lineTo`) instead of
  interpolating across it — the predicate is `isSparklineGap` (`internal`, pure, Compose-free),
  unit-tested on both sides of the boundary in `SparklineGapTest`.

**Why sparkline data lives outside `DashboardUiState`.** `DashboardUiState`/`GaugeTileUiState`
stay exactly as pure/immutable as before OBD-20 — sparkline points are **not** a field on either.
`DashboardScreen.kt`'s `GaugeDashboard`/`GaugeTile`/`BoostTile` accept the per-id `StateFlow`
itself as a parameter and pass the *reference* straight through, untouched; only the leaf
`GaugeSparklineStrip` composable calls `.collectAsStateWithLifecycle()` on it. Because a `Flow`
reference doesn't change identity when its `.value` changes, none of the ancestor composables
re-read anything on a tick — Compose's recomposition scoping confines each update to that one
leaf. This is the architecture `SparklineRecompositionTest` guards (see "Tests" below); if a
future change ever lifts the `.collectAsState()` call up to `GaugeDashboard` (e.g. to pass a
plain `List<SparklinePoint>` down as a value), that test fails immediately.

**Honest "no jank" note (OBD-20 AC):** what's verified here is headless — a recomposition-count
test proving the *architecture* isolates 4 Hz ticks to one leaf, and a pure cost-bound test on
`downsampleSparkline`. Neither measures actual GPU/compositor frame timing on a real device.
That requires a device macrobenchmark, tracked as OBD-34 — do not read "recomposition is
isolated" as "frames never drop."

## Settings screen (OBD-21)

`app/src/main/.../settings/`, reachable from `GaugeDashboard`'s new gear button
(`TextButton` + a "⚙" glyph — plain text, not a `material-icons` dependency, matching this
codebase's icon-free style so far) via `MainActivity`'s own `mutableStateOf<Boolean>` screen
swap (no nav library, same minimal style `MainActivity`'s KDoc already describes).

- `AppSettings.kt` — the persisted data model: `GaugeOrderEntry` (id + visible, ordered list,
  `DEFAULT_GAUGE_ORDER` mirrors the dashboard's pre-OBD-21 hardcoded order coolant/trans/oil/
  boost), `UnitPreferences` (temperature/pressure display unit), `PollRate` (250/500/1000 ms
  choices), `keepScreenOn: Boolean`, and `thresholdOverrides: Map<String, GaugeThresholds>`
  layered on top of `ThresholdConfig.seed` via `AppSettings.effectiveThresholds()`.
- `SettingsRepository` / `DataStoreSettingsRepository` — mirrors `:core:ble`'s
  `RememberedDeviceStore`/`DataStoreRememberedDeviceStore` split exactly: an interface (so tests
  can substitute an in-memory double) plus a Preferences-DataStore-backed implementation that
  takes a `DataStore<Preferences>` rather than a `Context`, so it's headlessly testable against a
  temp file (`SettingsRepositoryTest`, no Robolectric). `SettingsModule.kt` (`settings/di/`)
  provisions the real one — its own file (`obd_app_settings`, separate from `:core:ble`'s
  `obd_ble_link`) with a `ReplaceFileCorruptionHandler`, same "a van cuts power mid-write"
  rationale as `BleProvidersModule`. Read failures decode to `AppSettings()` defaults, never
  throw (`SettingsCodec.kt`'s file KDoc).
- `SettingsCodec.kt` — manual (no serialization-library dependency) encode/decode between
  `AppSettings` and Preferences `Key`s; pure functions, unit-tested directly against
  `mutablePreferencesOf()` with zero DataStore/file IO (`SettingsCodecTest`). Decoding
  `gaugeOrder` **reconciles it against `DASHBOARD_PIDS_BY_ID`** on every read (review round-1
  M5): an id the current catalog doesn't recognize is dropped, and any known id missing from the
  persisted order is appended, visible — see the "Unit conversion" section's OBD-25 caveat below,
  which this reconciliation deliberately narrows (gauge order self-heals against catalog drift;
  thresholds still don't).
- `SettingsViewModel` — `@HiltViewModel`; `StateFlow<AppSettings>` out (from
  `SettingsRepository.settings`), one mutator method per setting, each round-tripping through
  `SettingsRepository.update` (never mutating local state directly) — this is what lets the
  dashboard and the settings screen converge on the same persisted value live.
- `SettingsScreen` — stateless (`AppSettings` in, event callbacks out), same "test the screen,
  not the ViewModel/Activity" split `DashboardScreen.kt`/`ConsoleScreen.kt` use.
  `SettingsRoute` wires the real `SettingsViewModel` (via plain `viewModel()`, not
  `hilt-navigation-compose` — `:app` doesn't depend on that artifact, mirroring
  `MainActivity`'s own `DashboardViewModel` wiring) for `MainActivity`. `GaugesSection`,
  `ThresholdsSection`, `UnitsSection`, `KeepScreenOnSection`, `PollRateSection` are `private` —
  `SettingsScreenTest` drives the assembled screen (`.performScrollTo()` for anything below the
  fold), not the sections in isolation; see "Tests" below.
- Threshold fields (`ThresholdField`) are a hand-rolled `BasicTextField` (border + label above,
  kept intentionally lightweight — not a workaround for anything; see "Tests" below for the
  earlier, wrong hypothesis this KDoc used to carry). A blank or unparsable field **commits
  nothing** — the last valid (persisted or default) value stays in effect (review round-1 M6: an
  earlier version committed `null` for a blank field, silently clearing that boundary and, for
  coolant specifically, leaving every reading permanently AMBER with no UI path back except
  "reset thresholds to defaults"). Clearing a boundary intentionally has no dedicated affordance
  today — "reset to defaults" is the only supported way back.
- Keep-screen-on: `MainActivity` observes `DashboardViewModel.keepScreenOn` (itself derived from
  `SettingsRepository.settings`) in a `LaunchedEffect` and calls
  `window.addFlags/clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` — live, no
  restart needed either direction.
- Poll rate: `AppSettings.pollRate` (`PollRate.HZ_4/HZ_2/HZ_1`, i.e. 250/500/1000 ms) is
  persisted and surfaced in the UI, but **nothing in `:app` reads it yet** — `demo`'s
  `FakeVehicleDataSource` replays a fixed script regardless, and the real poll scheduler is
  `:core:protocol`'s (OBD-25/the Phase-4 integration). The value is there for that scheduler to
  read once it exists; wiring it through is out of this issue's scope.

### Unit conversion

`gauge/UnitConversion.kt` (pure, `UnitConversionTest`-covered): `MeasurementUnit.kind()`
classifies a unit as `TEMPERATURE`/`PRESSURE`/`OTHER`; `UnitConversion.convert(value, from, to)`
converts between the two temperature units or the two pressure units (identity otherwise).

**The "wire unit" design, and why it's not hardcoded Celsius/kPa.** A `Reading`'s value arrives
already scaled to whatever `PidDefinition.unit` its channel declares — read from
`DashboardPids.DASHBOARD_PIDS_BY_ID` at every call site, never assumed. Today (the `demo` flavor)
that's `FAHRENHEIT`/`PSI`, matching what `FakeVehicleDataSource`'s scripts actually emit;
`:core:protocol`'s real registry parses to `CELSIUS`/`KPA` instead (its `MODULE.md`'s "Unit
strategy" section flags this exact mismatch as unresolved until OBD-25 rewires
`DASHBOARD_PIDS`). Rather than hardcode SI units as some notion of "the natural unit" and force a
conversion on every reading today (when the wire unit already *is* Fahrenheit/PSI — converting
would be pure noise, and would require also rescaling `ThresholdConfig.seed`'s literal values,
which are written in Fahrenheit-scale numbers matching current `DASHBOARD_PIDS`, not Celsius),
this module treats **whatever `PidDefinition.unit` currently says** as the source of truth for
both readings and stored thresholds:

- `DashboardUiState.tileState` converts the *displayed* value only:
  `displayUnit = units.displayUnitFor(wireUnit)`, `displayValue = convert(reading.value, wireUnit, displayUnit)`.
  `ThresholdConfig.classify` still compares the **raw wire-unit** `reading.value` against
  wire-unit-scaled thresholds — classification never runs a conversion, so `BoostArc`'s fixed
  −2..18 PSI sweep range (unaffected by the user's kPa/PSI display choice — it reads
  `GaugeTileUiState.rawValue`, always wire-unit) and every existing threshold test keep working
  unchanged.
- Settings screen `ThresholdField`s show/accept values in the **display** unit and convert to/
  from the **wire** unit only at that edit boundary (`ThresholdField`'s KDoc) — so toggling
  °F↔°C or PSI↔kPa in Units never rewrites a stored `thresholdOverrides` value, satisfying the
  OBD-21 AC verbatim.

**Known caveat for OBD-25/Phase-4 integration:** when the real `:core:protocol` wiring lands and
`DASHBOARD_PIDS`' declared units flip from `FAHRENHEIT`/`PSI` to `CELSIUS`/`KPA` (per
`core/protocol/MODULE.md`'s flagged mismatch), `ThresholdConfig.seed`'s literal numbers must be
converted too (they're presently Fahrenheit-scale) — and any **user-saved** `thresholdOverrides`
persisted before that rewiring will misinterpret (their numbers were saved assuming a
Fahrenheit/PSI wire unit; classify would then compare them against Celsius/kPa readings). This
repo's `DataStoreSettingsRepository` has no migration/versioning for that scenario today — flagged
here rather than silently left for whoever does OBD-25 to discover. A reasonable fix at that
point: reset `thresholdOverrides` (or migrate them by converting each stored value from the old
wire unit to the new one) as part of the OBD-25 changeset.

This gap does **not** extend to `gaugeOrder`: unlike thresholds, gauge order is reconciled
against `DASHBOARD_PIDS_BY_ID`/`GAUGE_CATALOG_BY_ID` on every decode (see "Settings screen"
above and "Gauge swap picker" below for the OBD-42 refinement), so an id the catalog drops is
silently pruned and a new one the catalog gains shows up (visible, appended) automatically — no
OBD-25 migration action needed for that field specifically.

## Gauge swap picker (OBD-42)

Long-press a gauge tile → it enters an in-place picker mode: the tile's own frame stays put, but
its content sinks into a horizontal, snapping carousel of smaller "sunken" gauge cards — the
current gauge first, then swap-in candidates. Tap a candidate to swap it into that slot,
persisted the same way OBD-21's settings screen persists everything else. `app/src/main/.../gauge/`:

- `GaugeCatalog.kt` — see the "Public surface" section above for `GAUGE_CATALOG`/
  `GAUGE_CATALOG_BY_ID`/`candidateGaugesFor`. `RPM_PID_DEFINITION` (`PidIds.RPM`, unit `RPM`,
  `PollPriority.FAST`) is the demo flavor's swap-in candidate — `FakeVehicleDataSource`'s
  scripts already emit an `rpm` channel unused by the pre-OBD-42 dashboard (`Scenario.kt`'s
  `ScenarioChannel.RPM`). It renders NEUTRAL-colored automatically: `ThresholdConfig.seed` has
  no entry for `rpm`, and `ThresholdConfig.classify` returns `NEUTRAL` for any id absent from
  its threshold table (the same mechanism `boost` already relies on) — no threshold-table change
  needed for "neutral thresholds" (an OBD-42 AC).
  **The verified-filter seam**: `candidateGaugesFor`'s `isEligible` parameter defaults to `{ true }`
  (permissive — "demo: all fake channels count", `issues/OBD-42.md`'s Contract surface). Prod
  wiring (OBD-25+) should pass a predicate reading each candidate's verified status — today that's
  `PidDefinition.verified` (already a field on the frozen contract type); if/when a dedicated
  `PidCatalog.isVerified` hook exists, wire that instead. **Not implemented here** — documented
  as the seam, per the issue's explicit scope. **Review round-1 NIT, flagged for whoever wires
  OBD-25**: `DASHBOARD_PIDS`' `oilTemp`/`transTemp` both have `verified = false` (mode-22
  hypotheses, per `DashboardPids.kt`) despite being two of the four gauges visible by default. A
  naive `isEligible = { pid -> pid.verified }` would exclude them from every OTHER tile's
  candidate list — not "unswappable," since they're already visible and `candidateGaugesFor`
  never offers an already-visible id elsewhere anyway, but they'd become **unrecoverable** if a
  user ever swapped one away (nothing would let it back in). Whoever wires this predicate needs
  to either special-case already-core ids or hold off wiring it until `oilTemp`/`transTemp` are
  hardware-verified.
- `DashboardScreen.kt` — `GaugeTileGrid` (the landscape `Row`/portrait `Column` loop extracted
  out of `GaugeDashboard`'s own body) still `key`s each `visibleIds` entry by its own id
  (unchanged, OBD-21's reorder-in-settings needs that stable identity). `GaugeSlot`'s picker-mode
  dispatch was rewritten for OBD-44 (see "OBD-44: picker-entry shrink animation" below) — it no
  longer swaps between two different composables via `AnimatedContent`; `GaugeTile`/`BoostTile`
  is now the ONE composable that renders for `id`, always, whether or not that tile is picking.
  `GaugeSlot` falls back to `GaugeTileUiState.placeholder(id, GAUGE_CATALOG_BY_ID[id]?.label ?:
  id)` instead of skipping the render entirely when `uiState.tileFor(id)` is null (review round-1
  MINOR M3): `DashboardUiState.Loading` only carries the four core placeholders, so a tile freshly
  swapped to a non-core id (e.g. `rpm`) would otherwise render as a gap in the layout for every
  frame before the first real reading arrives.
  `GaugeTile`/`BoostTile` gained `onLongPress`/`onTap` params, wired through
  `GaugeTileInteraction.kt`'s `Modifier.gaugeTileInteraction` — its own file (not just its own
  function) so neither tile composable trips detekt's `LongMethod`/`TooManyFunctions`, and so the
  originally-duplicated block lives in exactly one place. It's a plain
  `Modifier.pointerInput { detectTapGestures(...) }` — **not** `clickable`/`combinedClickable`:
  those force a semantics merge boundary (`mergeDescendants = true`) that would fold each tile's
  own child testTags (`-label`/`-value`/`-stale`/`-sparkline`) into one merged node and break
  every existing `onNodeWithTag` lookup on them (this broke, and was caught by, the existing
  `DashboardScreenTest`/`ConnectionBannerTest`/`SparklineRecompositionTest` suite the first time
  this file tried `combinedClickable` — no dedicated regression test needed since the whole
  existing suite already guards it). Trade-off: no automatic ripple, acceptable for a dash-mount
  app — but review round-1's NIT pass added `onClick`/`onLongClick` **semantics actions** (inside
  the same `semantics {}` block that already carries `stateDescription`, so no new merge
  boundary — semantics actions and the `clickable` modifier family are independent concerns) so
  the long-press-to-swap interaction is at least discoverable to TalkBack; before that NIT, the
  feature was entirely invisible to accessibility services even though touch already worked.
  OBD-44 replaced the direct `gaugeTileInteraction` call inside `GaugeTile`/`BoostTile` with
  `GaugePicker.kt`'s `Modifier.pickerAwareInteraction`, which dispatches to
  `gaugeTileInteraction` while normal and to a plain tap-only-dismiss contract while picking (see
  below) — `onTap` still fires unconditionally in the normal branch (a no-op when no tile is
  picking), which is how tapping a *different* live tile also dismisses an open picker, one of
  the "tap outside the tile" affordances (a fifth exists too — see "Known limitations" below).
  `GaugeDashboard` itself owns `pickerTileId` (which tile, if any, is picking — at most one), a
  `BackHandler(enabled = pickerTileId != null)` for the back-gesture dismiss path, and a
  full-size invisible `testTag("gauge-picker-scrim")` `Box` (drawn *behind* the tile `Column` in
  z-order, so every tile's own pointer input still wins within its own bounds — the scrim is only
  reachable through the gaps) for the tap-outside dismiss path.
  **Review round-1 M1**: a completed swap replaces `pickerTileId`'s own gaugeOrder entry, which
  tears that id's `key(id)`-scoped subtree down (`GaugePickerChrome`'s own `LaunchedEffect`
  included) *before* its 220 ms-delayed `onDismiss()` ever runs — left unhandled, `pickerTileId`
  stays pinned to an id no longer on screen forever, so the invisible scrim + armed `BackHandler`
  silently eat the next back press/outside-tap, and if that id ever returns to `gaugeOrder`
  (any slot, not just its original one — a stale `pickerTileId` matches by id, not by position)
  its tile mounts already in picker mode. Fixed with a `LaunchedEffect(visibleIds)` in
  `GaugeDashboard` that clears `pickerTileId` the instant it's no longer in the visible id set,
  independent of the picker's own (now purely cosmetic) local dismiss timing — pinned by
  `GaugeSwapPickerTest`'s two `M1 -` cases.
- `GaugePicker.kt` — `GaugePickerChrome` (OBD-44's rename of the pre-existing `GaugePickerTile`:
  the frame background, caption, and a `LazyRow` with `rememberSnapFlingBehavior` for snap-to-card
  scrolling + `contentPadding` for edge peek of the OTHER candidates only — the current gauge's own
  card is no longer rendered here, see below) and `GaugeMiniCard` (one sunken candidate card:
  label + live value, zone-tinted like a full `GaugeTile` — "feels alive, not like a menu",
  `issues/OBD-42.md`; review round-1 NIT: honors `isStale` the same way `GaugeTile` does — dimmed
  value text plus a small `staleText` line when stale, so a mini-card never asserts a number more
  confidently than the real dashboard tile would for the same reading). Three dismiss paths live
  partly here, partly in `GaugeDashboard`: tapping the *current* gauge (now the live, shrunk
  `GaugeTile`/`BoostTile` itself — see "OBD-44" below) calls `onDismiss` directly; the back
  gesture and tap-outside are `GaugeDashboard`'s (see above). Tapping any *other* mini-card fires
  `onSelectCandidate` (persistence) **immediately**, then locally animates that card scaling up to
  `SELECTED_RISE_SCALE` for `SWAP_RISE_ANIMATION_MS` (220 ms) before calling `onDismiss` — see
  this section's "Known limitations" below for the honest caveat on what this local animation can
  and can't guarantee. **Unchanged by OBD-44 on purpose**: `issues/OBD-44.md`'s AC is explicit
  that selecting a DIFFERENT candidate keeps this exact pre-existing "rise" treatment; only the
  CURRENT gauge's own entry/exit got the new shrink treatment.
- `AppSettings.kt` — `AppSettings.withGaugeSwapped(oldId, newId)`: pure, replaces the `gaugeOrder`
  entry named `oldId` with `newId`, keeping its `visible`/position, no-op if `oldId` isn't
  present. `DashboardViewModel.swapGauge(oldId, newId)` is the only production caller, round-
  tripping it through `SettingsRepository.update` — the *exact* path `SettingsViewModel`'s own
  mutators use (`setGaugeVisible`, `moveGauge`, etc.), per the issue's "swap should reuse this
  persistence path" instruction. A no-op when `oldId == newId`.
- `SettingsCodec.kt` — `reconcileGaugeOrder` went through two shapes before landing (review
  round-1 M2). The first OBD-42 version widened *which* ids survive a decode (the broader
  `GAUGE_CATALOG_BY_ID`, so a persisted `"rpm"` isn't dropped as "unknown" the way a truly retired
  id is) but kept OBD-21's original auto-backfill behavior gated on slot *count* — an attempt to
  tell "genuine catalog drift" (a new `DASHBOARD_PIDS` entry added after an order was last saved)
  apart from "a swap deliberately removed a core id from its slot" by whether the persisted order
  had fewer slots than the core catalog expected. Review round-1 probed that with a **larger**
  catalog (a hypothetical 5th `DASHBOARD_PIDS` entry, OBD-43's actual shape) and broke it: a
  4-slot swapped order against a 5-id catalog looks "short a slot" too, so it decoded to 6
  entries with the swapped-away gauge resurrected as a duplicate. There is no shape-of-the-list
  signal that reliably tells those two cases apart, so the fix drops auto-backfill **entirely**:
  `reconcileGaugeOrder(order, catalog: Map<String, PidDefinition> = DASHBOARD_PIDS_BY_ID)` now
  only drops ids not in `catalog` (the production call site always passes `GAUGE_CATALOG_BY_ID`;
  the parameter — defaulting to the narrower `DASHBOARD_PIDS_BY_ID` — exists so catalog drift is
  directly testable with a synthetic bigger-than-today catalog, without touching the real
  `DASHBOARD_PIDS`). `DEFAULT_GAUGE_ORDER` already covers fresh installs (built straight from the
  current `DASHBOARD_PIDS` at file-load time, never reconciled against a stale persisted order),
  and a catalog gauge that's new to an *existing* install is discoverable through the swap picker
  instead (`candidateGaugesFor` reads the live catalog directly) — the coherent OBD-42 story:
  gauge visibility only ever changes via an explicit user action (Settings' visibility toggle, or
  the picker), never via decode-time inference. Pinned by `SettingsCodecTest`'s "M2 regression"
  case (a synthetic 5-id catalog + a persisted 4-slot swapped order decodes to exactly 4 entries,
  no resurrected core id) — verified to fail against the slot-count heuristic and pass against
  this fix.
- `SettingsScreen.kt` — `GaugesSection`'s label lookup switched from `DASHBOARD_PIDS_BY_ID` to
  `GAUGE_CATALOG_BY_ID`, so a swapped-in id (e.g. `"rpm"`) shows its real label instead of the
  raw id string if a user opens Settings after swapping. `ThresholdsSection` is unaffected — it
  only ever iterates the three fixed temperature ids, none of which OBD-42 can swap away from
  each other's thresholds (thresholds are per-id, not per-slot).

### Known limitations (OBD-42)

- The swap-select "confirm, rise, dismiss" local animation in `GaugePickerChrome` fires
  `onSelectCandidate` (persistence) immediately but only calls `onDismiss` after a fixed 220 ms —
  giving the real `gaugeOrder` write a head start to round-trip through `SettingsRepository`
  before the picker actually closes and `key(id)` swaps the tile's composable instance to the new
  id. This is a **timing heuristic, not a guarantee**: correctness never depends on it (the
  persisted value is what's authoritative either way, and review round-1 M1's `LaunchedEffect`
  self-heal keeps `pickerTileId` itself always eventually correct regardless of this timing), but
  an unusually slow DataStore write (heavy disk contention, a very old device) could still show a
  brief flash of the *old* gauge's data between the picker closing and the swapped tile catching
  up. No test pins this specific timing window — see `GaugeSwapPickerTest`'s correctness/
  persistence tests for what *is* pinned.
- A swapped-in gauge (e.g. `rpm` after a swap) gets a working sparkline (`SparklineHistoryHolder`
  is seeded off `GAUGE_CATALOG`, not just `DASHBOARD_PIDS` — see the "Public surface" section
  above), but `MainActivity`'s own `sparklines` map is likewise keyed off `GAUGE_CATALOG` now, a
  small but real behavior change from pre-OBD-42 (previously only the core four had sparkline
  flows wired at all, even though the underlying holder capacity was unaffected).
- **A fifth, incidental dismiss path**: device rotation. `pickerTileId` lives in `remember {}`
  state scoped to `GaugeDashboard`'s own composition, not `rememberSaveable`; Android's default
  rotation handling (no `android:configChanges` override in the manifest) recreates `MainActivity`
  on an orientation change, tearing down and rebuilding the whole composition — `pickerTileId`
  resets to `null` along with it. Not deliberately engineered (the four AC-specified dismiss
  paths are what's tested), but real: a user mid-picker who rotates the device loses picker mode
  as a side effect, same as if they'd tapped outside.
- **Coolant (or any core gauge) is recoverable only through the picker once swapped away.**
  Unlike thresholds (which have a dedicated "reset to defaults" button, `SettingsScreen.kt`),
  there is no settings-screen affordance to restore `gaugeOrder` to `DEFAULT_GAUGE_ORDER` — a
  swapped-away core gauge comes back only if a user explicitly swaps *something* back to it via
  some tile's picker (as `GaugeSwapPickerTest`'s M1 cases do). This is consistent with the
  "gauge visibility only changes via explicit user action" story above, but worth flagging as a
  real UX gap: a user who doesn't understand the picker (or forgets which tile to long-press) has
  no other way back.

### OBD-44: picker-entry shrink animation

Taras's own words: "I want the original gauge to sort of shrink into the frame of the card sort
of like when you do multi tasking on android or iOS." OBD-42's fade+scale-toward-0.85
`AnimatedContent` crossfade (`gaugePickerContentTransition`, now deleted) read as a generic sink,
not a switcher-style shrink into a specific target, and it crossfaded between two DIFFERENT
composables (`GaugeTile` and `GaugePickerTile`'s current-card) — a content pop the AC explicitly
rules out ("not a crossfade to a different mini composable").

**Architecture — one composable, never swapped.** `GaugeSlot` no longer branches on `isPicking`
to choose between two composables. `GaugeTile`/`BoostTile` is the ONE instance rendering `id`
across the tile's entire picker lifecycle — mounted once, never torn down and remounted when
picker mode opens or closes. What changes is purely visual, driven by a single
`rememberPickerShrinkProgress(isPicking, label)` (`GaugePicker.kt`) — an `animateFloatAsState`
wrapper, 0f (normal tile) → 1f (fully in picker/mini-card state) — applied to that one instance's
own `Modifier.pickerShrinkLayer(progress, fullSize, targetBounds, shape, elevation)`: a single
`graphicsLayer` that scales+translates the WHOLE tile (background, border, content, all of it)
from filling the tile's own bounds down to sitting exactly inside `targetBounds`, with `shape`
(interpolated corner radius) and `shadowElevation` riding the same layer — the AC's "corner
radius and elevation/shadow interpolate alongside scale." Because it's one `graphicsLayer` on one
never-recreated composable, the value `Text` is the same composition node the whole time: a
reading pushed mid-animation recomposes it immediately, live, mid-shrink — pinned by
`GaugeSwapPickerTest`'s `gauge shrinks continuously through mid-animation and stays live while it
does` (see "Round-2 review fixes" below for how this test's timing was made non-vacuous — pauses
`mainClock`, triggers the long-press, advances a small delta from wherever the gesture itself left
the clock, pushes a fresh reading into the live data source, and asserts the picker-card-tagged
value text already reflects it, then confirms that check really did land mid-flight).

**Where "the mini-card position" comes from.** `GaugePickerChrome` reserves the current gauge's
landing slot as an invisible (`alpha(0f)`), untagged (`GaugeMiniCard(..., tagged = false)`) real
mini-card — sized exactly like a real one (same label/value/stale-line content) rather than a
guessed constant — and reports its position via `onGloballyPositioned`. `GaugeSlot` converts that
into a `Rect` in its OWN `GaugeTile`/`BoostTile` coordinate space (`LayoutCoordinates
.localPositionOf`, anchored on that same live tile's own pre-transform coordinates, captured via
a second `onGloballyPositioned` positioned OUTSIDE/before the `pickerShrinkLayer` modifier in the
chain so it reports the untransformed layout slot) and feeds it in as `targetBounds`. Scaling and
translating around each rect's own center makes the corners land exactly on `targetBounds` at
`progress == 1f` — not an approximation.

**Same node, different identity once settled.** `GaugePicker.kt`'s
`Modifier.pickerAwareInteraction` and `pickerAwareValueTag` switch `GaugeTile`/`BoostTile`'s own
outer/`value` testTags between the normal (`gauge-$id`/`gauge-$id-value`, long-press-to-enter +
pass-through tap) and picker-card (`gauge-picker-card-$id`/`-value`, tap-only-to-dismiss,
matching `GaugeMiniCard`'s pre-existing contract exactly) identities the instant `isPicking`
flips — so the settled shrink target IS the picker's "current" card as far as
`onNodeWithTag`/TalkBack are concerned, never a second, separately-tagged node fighting the first
for a lookup match. Every existing `GaugeSwapPickerTest`/`GaugeSwapDemoTest`/
`GaugePickerScreenshotTest` assertion on those tags passed unmodified against this rewrite.

**Reduced motion.** `rememberPickerShrinkAnimationSpec` reads
`Settings.Global.ANIMATOR_DURATION_SCALE` (Compose's own animation clock, unlike the View system,
does NOT honor this automatically — a real, documented gap) and resolves to `snap()` at scale 0,
so the shrink/grow becomes an instant jump to the correct end state with no intermediate frames —
no window in which a composable can be left stuck mid-scale. Pinned by
`GaugeSwapPickerTest`'s `animator duration scale 0 snaps to the picker-card end state the instant
the long-press registers` (Robolectric's `Settings.Global` shadow is a real, consistent in-memory
store, so `putFloat` before composing and `getFloat` from `rememberPickerShrinkAnimationSpec`
agree) — see "Round-2 review fixes" below for why this test drives the press manually instead of
using the `longClick()` convenience.

**A Compose `Box` gotcha this rewrite ran into and fixed**: `GaugeSlot`'s outer `Box` now has TWO
children when picking — `GaugePickerChrome` (`Modifier.matchParentSize()`, must never influence
this `Box`'s own resolved size) and the live tile (must either fill it, in landscape's
weight-driven layout, or drive it from its own content height, in portrait's scrollable-column
layout — exactly `AnimatedContent`'s old behavior). `Box`'s default `propagateMinConstraints =
false` LOOSENS a plain (non-`matchParentSize`) child's min constraints to 0 regardless of what the
`Box` itself received — which left the live tile sized to wrap its own content instead of filling
the weighted landscape slot (caught by `DashboardScreenshotTest`'s landscape reference going
red across nearly the whole tile in the Roborazzi diff — the fix is `Box(modifier, 
propagateMinConstraints = true)`). Verified byte-for-byte unaffected: `DashboardScreenshotTest`'s
two references (which never enter picker mode) are untouched — `pickerShrinkLayer`, the border,
and every interpolated visual are no-ops whenever `progress == 0f`, so a normal tile renders
through the exact same code path it always did. Only `gauge_picker_mode.png`
(`GaugePickerScreenshotTest`) needed re-recording, since the settled picker-mode frame's current
card genuinely looks different now (a scaled-down full tile, not `GaugeMiniCard`'s own smaller
font sizing) — by design, per the AC.

**Known limitation**: the first composition frame after a long-press, before
`GaugePickerChrome`'s ghost has reported its position via `onGloballyPositioned`, briefly renders
the full tile un-transformed (no guessed position) rather than animating from frame one; the
transform picks up the next frame, comfortably inside the ~220 ms shrink and not perceptible in
manual verification. The interpolated corner radius is not optically compensated for the
concurrent scale (i.e. the on-screen radius mid-animation is not pixel-exact against what it
would be if drawn at that size natively) — acceptable for a sub-quarter-second transient; only
the settled end states are test-pinned, per the issue's own self-test plan.

#### Round-2 review fixes

- **BLOCKER B1 (anisotropic squash)**: `pickerShrinkLayer`'s independent per-axis scale is exactly
  right for the OUTER surface's bounds (a rounded rect legitimately morphing from the tile's own
  portrait-tall aspect to the mini-card slot's landscape-wide one isn't itself a defect), but
  applying that SAME scale to the label/value CONTENT read as a vertically-crushed smear once the
  two aspects diverged enough (measured: 2.87× distortion in the review's landscape config).
  Fixed with `Modifier.pickerShrinkContentCounterScale` (`GaugePicker.kt`), applied to the content
  `Column` only: computes the uniform (`min` of the two per-axis targets) scale `pickerShrinkLayer`
  would need for an UNDISTORTED shrink, then divides it by the outer transform's own per-axis
  scale — nested `graphicsLayer`s compose multiplicatively, so the content's net scale becomes
  uniform (letterboxed within the surface) regardless of how anisotropic the outer transform is.
  Pinned by `GaugeSwapPickerTest`'s new aspect-ratio test (the settled current-card's own bounds,
  compared against a real `GaugeMiniCard` sitting beside it in the same carousel) and visually by
  the re-recorded `gauge_picker_mode.png`.
- **MAJOR M1 (vacuous reduced-motion test)**: the round-1 version called `waitForIdle()`, which
  auto-advances through a `tween` too, so it never actually exercised the `snap()` branch
  (mutation-confirmed: deleting that branch didn't fail it). A first fix attempt — pause
  `mainClock`, `longClick()`, advance one frame — was ALSO vacuous: `longClick()`'s own synthetic
  gesture advances `mainClock` by roughly its long-press timeout (~630 ms) as part of recognizing
  the gesture at all, comfortably longer than `PICKER_SHRINK_MS` (220 ms), so a real tween would
  have settled too. The real fix drives the press manually (`down()` + polled `advanceEventTime`-
  free `mainClock.advanceTimeBy` steps against `onRoot()`, not the tile's own testTag — which
  switches identity mid-gesture) and checks bounds the INSTANT the picker-card identity appears —
  under `snap()` the shrink must already be (near) settled at that instant; under any tween it
  cannot be.
- **MAJOR M2 (mid-shrink test landing near settle)**: `longClick()`'s ~630 ms internal clock
  advance meant a round-1 fixed delta from an assumed `t = 0` landed ~5 ms before the animation
  actually settled — passing even though it wasn't exercising "mid-flight" at all. Fixed to advance
  a small delta from `mainClock.currentTime` captured right after the gesture, and to assert
  mid-flight-ness explicitly: captured bounds before the press, shortly after, and once settled,
  asserting the "shortly after" bounds are strictly between the other two on both height (canary
  for a broken/incomplete scale) and vertical center position (canary for a broken/zeroed
  translation — independent `graphicsLayer` properties, so a height-only check wouldn't catch a
  translation regression).
- **MAJOR M3 (single-surface property unpinned)**: added `LocalGaugeTileMountProbe`
  (`DashboardScreen.kt`) — a test-only `CompositionLocal<() -> Unit>`, no-op by default, that
  `GaugeTile`/`BoostTile` fire once via `remember { }` (i.e. once per composition MOUNT, never on
  an in-place recomposition). A new test asserts the fired count stays constant across a
  long-press + dismiss cycle, directly catching a regression as narrow as wrapping either tile's
  call site in `key(isPicking) { }` — which value/tag assertions alone would eventually read
  correctly against too.
- **Mutation ledger** (`cp`-backup/restore, never `git checkout`, per process): mutation B
  (delete the `snap()` branch) → killed by the rewritten M1 test. Mutation D (zero out
  `translationX`/`translationY` in `pickerShrinkLayer`) → killed by the rewritten M2 test's
  position-delta assertion. Mutation E (`key(isPicking)` around `GaugeSlot`'s
  `GaugeTile`/`BoostTile` call sites) → killed by the new M3 mount-probe test.
- **N1** (`GaugeSlot`'s `onCurrentSlotPositioned`): guards `localPositionOf` with
  `anchor.isAttached && slotCoordinates.isAttached` — a callback can fire after either side has
  left the layout tree, and `localPositionOf` on a detached `LayoutCoordinates` throws.
- **N2** (`GaugePickerChrome`): candidate taps now check `isPicking`, not just `progress > 0f` —
  the chrome stays composed (fading out) for the whole reverse grow-back animation after a
  dismiss, during which `isPicking` has already flipped false; without the extra check a candidate
  tapped during that window could fire a swap-select on an already-closing tile.
- **N5** (`pickerShrinkVisuals`): `contentPadding` is no longer interpolated — it's a real
  `Modifier.padding`, and animating it toward `MINI_CARD_PADDING_DP` shrank the picking tile's own
  reported layout height mid-animation (portrait's height is content-driven), visibly jumping
  every tile below it in the scrollable column before the shrink even reached its target.
- **N6** (`pickerAwareInteraction`): the picking branch now carries the same `stateDescription`/
  `onClick` semantics the normal branch does (minus `onLongClick`, meaningless while picking) —
  matches `GaugeMiniCard`'s own historical gap, but cheap to close here.
- **N7**: `rememberPickerShrinkVisuals` renamed to `pickerShrinkVisuals` — it never actually
  `remember`ed anything (every call recomputes fresh, and `progress` changes every animation
  frame anyway, so memoizing would never hit its cache).
- **Declined, with rationale**: **N3** (a real `snap()` still leaves a 1-frame window where
  `progress == 1f` but `targetBounds` hasn't arrived yet, since that's a separate layout-pass
  dependency, not an animation-timing one) — not fixed; the KDoc on
  `rememberPickerShrinkAnimationSpec`/`pickerShrinkLayer` was corrected instead to describe this
  precisely rather than overclaim "no intermediate frames whatsoever." **N4** (label/stale/
  sparkline testTags stay `gauge-$id-*` always, never switching to a `gauge-picker-card-$id-*`
  equivalent, unlike the outer/value tags) — left as-is: no test or TalkBack behavior depends on
  those specific tags switching, and matching `GaugeMiniCard`'s OWN convention exactly would mean
  DROPPING the label tag entirely while picking (it has none), a larger behavior change than
  round 2's scope justified.

## Debug console (OBD-19, `src/debug/`)

A debug-build-only "OBD Console" raw AT-command REPL, the Sprint 2 hardware-bring-up tool —
its own launcher icon (a separate `<activity>` + `LAUNCHER` intent-filter in
`src/debug/AndroidManifest.xml`, labeled "OBD Console"), reachable without any of the main
app's own nav/setup. `src/debug/` is a **build-type** source set (orthogonal to the
`environment` flavor dimension — see "Build flavors" above), so it's part of both
`demoDebug` and `prodDebug`, and absent from every release build; `debugImplementation(project(
":core:ble"))` in `build.gradle.kts` keeps `:core:ble` off both release classpaths entirely
(verify with `:app:dependencies --configuration demoReleaseRuntimeClasspath|prodReleaseRuntimeClasspath
| grep -i "core:ble"`, expect empty, mirroring the `:core:testing` HARD CONSTRAINT check above).

Owned by `ble-agent` per `issues/OBD-19.md`'s flagged cross-boundary exception (this is
debug-only tooling, not a UI feature) — `OWNERSHIP` lists `/app/src/debug/` as co-owned by
`ble-agent ui-agent` for exactly this reason.

- `console.ConsoleActivity` — `@AndroidEntryPoint`. Owns the runtime-permission flow
  `:core:ble` deliberately never does itself (see core/ble/MODULE.md's "Permissions"): a
  connect tap checks `BleObdLink.missingPermissions` and only prompts
  (`ActivityResultContracts.RequestMultiplePermissions`) for what's actually missing; a denial
  is logged to the console (`ConsoleViewModel.recordPermissionDenied`), never a crash.
- `console.ConsoleViewModel` — `@HiltViewModel`; thin edge around `:core:ble`'s
  `ConsoleSession` (all real logic lives there, see core/ble/MODULE.md's `console` package
  section). Injects the concrete `BleObdLink` (not the `ObdLink` interface) because it also
  needs `missingPermissions` and `forgetRememberedDevice`, neither part of the frozen `ObdLink`
  contract.
- `console.ConsoleScreen` — the whole UI as one stateless composable (scrollback, quick-command
  chips for `ATZ`/`ATE0`/`ATI`/`0100`/`010C`, input + send, connect/disconnect/forget buttons,
  a link-state banner) so it's testable without a `ViewModel`/Hilt/Activity in the loop.
  Deliberately self-contained — its own dark `MaterialTheme`, its own banner — rather than
  reusing anything from `gauge/`'s `ConnectionBanner`: this tool must keep working independent
  of the main dashboard UI.
- `console.di.DebugObdLinkModule` — `src/debug/`-scoped `@Binds ObdLink -> BleObdLink`. The
  first place in `:app` that binds `ObdLink` at all (OBD-25 will do the same for the release
  dashboard flow, per flavor).
- `console.ConsoleEntryFormatting` — pure display formatting (`formatConsoleTimestamp`,
  `formatConsoleEntryBody`, `formatLinkStateName`) for `:core:ble`'s `ConsoleEntry`, mirroring
  `gauge/GaugeFormatting.kt`'s split between pure formatting and Compose.

## Tests

- `ThresholdConfigTest`, `DashboardViewModelTest`, `DashboardScreenTest`,
  `DashboardScreenshotTest`, `ConnectionBannerTest`, `DataSourceModuleClockTest`,
  `GaugeSwapDemoTest` (OBD-42), `GaugePickerScreenshotTest` (OBD-42) —
  `src/testDemo/` (need `FakeVehicleDataSource`/`Scenario`, so `testDemoDebugUnitTest`-only;
  see "Build flavors").
  `GaugeFormattingTest`, `BoostArcTest`, `UnitConversionTest`, `SparklineBufferTest`,
  `SparklineGapTest`, `SparklineHistoryHolderTest`, `SparklineRecompositionTest`,
  `DashboardUiStateTest`, `SettingsCodecTest`, `SettingsRepositoryTest`, `SettingsScreenTest`,
  `LiveRecolorTest`, `GaugeCatalogTest` (OBD-42), `AppSettingsSwapTest` (OBD-42),
  `GaugeSwapPickerTest` (OBD-42) — plain JVM or Robolectric, no `:core:testing` dependency,
  `src/test/` (flavor-common — run under both `testDemoDebugUnitTest` and `testProdDebugUnitTest`).
  `ConsoleScreenTest` (OBD-19) — Robolectric + compose-ui-test, `src/testDebug/` (a build-type
  source set — like `src/debug/` itself, it's shared by both flavors' debug variants, so it
  runs under both `testDemoDebugUnitTest` and `testProdDebugUnitTest` without needing
  `:core:testing`). Drives `ConsoleScreen` directly (no `ViewModel`/Hilt/Activity), asserting
  scrollback rendering/order, the link-state banner, send-button enablement (blank input, and
  while a command is in flight — quick-command chips too), and that a chip tap calls `onSend`
  with its command. `ConsoleActivity`/`ConsoleViewModel` themselves have no dedicated test —
  Hilt-in-Robolectric scaffolding doesn't exist elsewhere in this module yet (see
  `docs/05-local-workflow.md` if that changes); the composable is where the substance is, per
  the same "test the screen, not the Activity" split `DashboardScreenTest`/`ConnectionBannerTest`
  already use above.
- `DataSourceModuleClockTest` (OBD-11 round-1 M2) — exercises the real demo DI providers:
  injected clock reads ≈ `EPOCH` at provisioning and re-anchors ≈ `EPOCH` after a delayed
  `start()`; fails loudly if wall-clock wiring (`systemDefaultZone`) ever returns.
- `DashboardViewModelTest` — plain JVM; drives `FakeVehicleDataSource` + `DashboardViewModel`
  under `kotlinx-coroutines-test`. Asserts IDLE tail (green) and TOWN_HEAT_SOAK tail (amber
  coolant/oil/trans, per seed thresholds).
- `DashboardScreenTest` — Robolectric + compose-ui-test. Same two scenarios, rendered through
  `GaugeDashboard`, asserted via `testTag`/`stateDescription` and value text.
- `ConnectionBannerTest` (OBD-11) — Robolectric + compose-ui-test. Hand-built `LinkState`
  values cover each banner treatment in isolation (mirrors `DashboardScreenTest`'s hand-built
  RED-zone test for states a scripted scenario doesn't hit); one scenario-driven test replays
  the real `Scenario.DISCONNECT_RECONNECT` script, recording every `(readings, connection)`
  combination the fake emits (not just its tail state, via `combine(...).collect{}` on a
  `TestScope`) and renders each milestone through a single composition (`setContent` may only
  be called once per test — subsequent milestones drive a `mutableStateOf`, not repeated
  `setContent` calls), asserting banner presence/content and stale-text correctness at each of
  `Ready → Error(Timeout) → Scanning → Connecting → Ready`. Its expected "last seen Xs ago"
  values are hand-derived from the script's fixed tick arithmetic (documented inline per
  assertion) using a `TickingClock`-style test `Clock` anchored to the `TestScope`'s virtual
  time — the test-side counterpart of the `demo` flavor's DI clock fix below.
- `DashboardScreenshotTest` — Roborazzi, landscape (`w800dp-h360dp-land`) and portrait
  (`w360dp-h640dp-port`), TOWN_HEAT_SOAK tail state (`connection = Ready`, so the OBD-11 banner
  renders nothing). **Updated for OBD-20/21** — the gear button and a synthetic per-gauge
  sparkline trend (`sampleSparklines()`, 20 points at a 250 ms/4 Hz-equivalent cadence, well
  under `SparklineChart`'s 2 s gap threshold so the reference shows a continuous line, not 20
  disconnected dots) are now visible in both references; regenerated deliberately
  (`./gradlew :app:recordRoborazziDemoDebug`) and re-verified — no longer byte-identical to the
  pre-OBD-20 images. References committed under `src/testDemo/screenshots/`. Regenerate with
  `./gradlew :app:recordRoborazziDemoDebug`; verify with `./gradlew :app:verifyRoborazziDemoDebug`
  (see "Build flavors" for why the task name changed from the pre-flavor
  `verifyRoborazziDebug`). `captureRoboImage` no-ops (doesn't compare or write) under a plain
  `testDemoDebugUnitTest` run — only the dedicated Roborazzi tasks (or
  `-Proborazzi.test.record=true` / `-Proborazzi.test.verify=true`) actually record/compare, so
  the base build gate never fails on an environment-sensitive pixel diff.
- `SparklineBufferTest` — plain JVM: window trim (including the exact-boundary case), a
  same-timestamp `add()` being a no-op (review round-1 M4's dedup fix), downsample
  determinism/no-op/first-last-point guarantees, a bound-enforcement test at a simulated 4 Hz for
  the full 300 s window (asserts the buffer never exceeds ~1 200 points even under 3x that many
  pushes), and a downsample-cost bound test feeding 50 000 points and asserting the output is
  still exactly 120 (OBD-20 AC: "no jank... verified" pure half — see the "Sparklines" section's
  honest caveat about what this does and doesn't prove).
- `SparklineGapTest` — plain JVM: `isSparklineGap`'s 2 s boundary on both sides, plus the
  exactly-at-threshold case (not a gap — the comparison is strictly `>`).
- `SparklineHistoryHolderTest` — plain JVM: a fresh reading appends; a stale reading only trims,
  never appends; re-feeding the *identical* reading (the exact "settings-only combine tick"
  scenario review round-1 M4 flagged) adds no duplicate point; a missing id still trims against
  the emission's `now`; distinct new timestamps still accumulate normally.
- `SparklineRecompositionTest` — Robolectric + compose-ui-test, flavor-common. The other OBD-20
  "no jank" AC half: a counting `Modifier.composed { SideEffect { ... } }` passed as
  `GaugeDashboard`'s own `modifier` param — so it counts recompositions of `GaugeDashboard`'s own
  composable body directly, not a proxy — pumped with 20 ticks on an independent
  `MutableStateFlow` sparkline; asserts that count stays at `1` throughout (review round-1 M3:
  the prior version counted only the *test's own* wrapping scope, which a mutation lifting
  `collectAsStateWithLifecycle()` up into `GaugeDashboard` couldn't fail, since that wrapper
  never read the flow either way — measuring `GaugeDashboard`'s own scope closes that gap). A
  second test confirms the leaf still renders once data arrives.
- `DashboardUiStateTest` — plain JVM (review round-1 M1): a 235°F (wire-unit) coolant reading
  stays classified `RED` even with Celsius selected as the display unit — pins that
  `ThresholdConfig.classify` runs against the raw wire-unit `Reading.value`, never the
  display-converted value (235 F ≈ 112.8 C, which falls under every seed boundary — a
  misclassification this test would catch immediately).
- `UnitConversionTest` — plain JVM: F↔C and PSI↔kPa round-trips, `kind()` classification for
  every `MeasurementUnit`, `displayUnitFor`, and that `UnitPreferences()`'s defaults are a
  no-op against every `DASHBOARD_PIDS_BY_ID` entry's declared unit (guards the "no visual
  regression from OBD-21" claim above).
- `SettingsCodecTest` — plain JVM, no DataStore/file IO: round-trips a full `AppSettings`
  (including threshold overrides with null boundaries) through `mutablePreferencesOf()`; confirms
  an empty/absent gauge-order string decodes to `DEFAULT_GAUGE_ORDER`, not `emptyList()`; and
  (review round-1 M5) both reconciliation directions — a persisted id no longer in
  `DASHBOARD_PIDS_BY_ID` is dropped, and a known id missing from a persisted order is appended,
  visible, at the end, with the persisted entries' own order/visibility left untouched.
- `SettingsRepositoryTest` — plain JVM against a real Preferences DataStore on a temp file
  (mirrors `:core:ble`'s `RememberedDeviceStoreTest`, no Robolectric): defaults-before-any-write,
  a write surviving a **simulated restart** (a fresh `DataStoreSettingsRepository` instance
  reading the same file — see the "two DataStore instances, same file" caveat below), layered
  updates, and corruption-reads-as-defaults.
- `SettingsScreenTest` — Robolectric + compose-ui-test. Drives the fully assembled `SettingsScreen`
  directly (no `ViewModel`/Hilt/Activity): back button, gauge visibility toggle, gauge reorder,
  threshold editing (both at the default display unit and with Celsius selected, confirming the
  wire-unit conversion), a blank field committing nothing (review round-1 M6), reset-to-defaults,
  the units toggle, keep-screen-on, and poll rate. Anything below the fold uses
  `.performScrollTo()` before `.performClick()`/`.performTextReplacement()` — see "Known
  limitations" below for why that matters here specifically.
- `LiveRecolorTest` — Robolectric + compose-ui-test, against a real `DataStoreSettingsRepository`
  (temp file) and a small hand-rolled `VehicleDataSource` double emitting one fixed AMBER-zone
  coolant reading. Composes the real `DashboardViewModel` + `SettingsViewModel` + `GaugeDashboard`
  + `SettingsScreen` together, edits the coolant green-max field high enough to flip the *same*
  reading's zone, and asserts the dashboard tile's `stateDescription` recolors to GREEN without
  any restart — the OBD-21 AC verbatim.
- `GaugeCatalogTest` (OBD-42) — plain JVM: `GAUGE_CATALOG` is exactly `DASHBOARD_PIDS` plus
  `rpm`; `rpm` is absent from `DASHBOARD_PIDS`/`DASHBOARD_PIDS_BY_ID` but present in
  `GAUGE_CATALOG_BY_ID`; `rpm`'s unit/absent-threshold-entry NEUTRAL classification;
  `candidateGaugesFor` puts the current id first, excludes ids visible on another tile, offers a
  *hidden* (not just absent) id elsewhere, never excludes the current id itself, and both the
  default-permissive and a rejecting `isEligible` predicate (the verified-filter seam).
- `AppSettingsSwapTest` (OBD-42) — plain JVM: `AppSettings.withGaugeSwapped` replaces the right
  entry keeping visibility/position, no-ops when `oldId` isn't present, and leaves every other
  entry's instance untouched.
- `SettingsCodecTest` gained OBD-42 cases: a persisted swap to a non-core id (`rpm`) survives
  decode instead of being dropped as "unknown"; `rpm` is never auto-backfilled into an order that
  never named it; the pre-existing "drops a retired id"/"missing id" cases were rewritten for
  review round-1 M2's no-auto-backfill behavior (a missing known id now just stays missing,
  never gets appended); and the "M2 regression" case (a synthetic 5-id catalog + a persisted
  4-slot swapped order decodes to exactly 4 entries, no resurrected core id) — see "Gauge swap
  picker" above for the full story `reconcileGaugeOrder`'s design went through.
- `DashboardViewModelTest` gained two OBD-42 cases: `swapGauge` replaces the id at the right
  gaugeOrder position, keeps visibility, and the dashboard's `uiState` shows a real (non-
  placeholder) value for the swapped-in id — pinning that `GAUGE_CATALOG` (not `DASHBOARD_PIDS`)
  actually reaches `dataSource.start()`; and `swapGauge` is a no-op when `oldId == newId`.
- `DashboardUiStateTest` gained two OBD-42 cases: `extraTiles` carries a correctly labeled/
  formatted/NEUTRAL-classified `rpm` tile off the same `toDashboardUiState` code path as the
  core four, and `tileFor` returns `null` (not a placeholder, not a crash) for an id that's
  neither a core field nor in `extraTiles`.
- `GaugeSwapPickerTest` (OBD-42) — Robolectric + compose-ui-test (`createAndroidComposeRule`, not
  the plain `createComposeRule()` most other tests here use, specifically so the back-gesture
  test can reach the hosting Activity's `OnBackPressedDispatcher` via `activityRule`), against a
  hand-rolled `VehicleDataSource` double emitting coolant/trans/oil/boost **and rpm** readings (so
  the picker's live-value AC is actually exercised) and — for the persistence test only — a real
  temp-file `DataStoreSettingsRepository`. Covers, per `issues/OBD-42.md`'s self-test plan: long-
  press opens picker mode on that tile only (rest of the dashboard stays live); the carousel's
  candidate list (current first, core ids visible elsewhere excluded); tap-to-swap persisting and
  **surviving a recreated repository** (mirrors `SettingsRepositoryTest`'s "cancel the first
  DataStore scope, open a second over the same file" restart simulation); all three dismiss paths
  (current mini-card, back gesture, tap-outside-via-the-scrim) plus a fourth (tapping a different
  live tile); the four-surface swap-correctness pin (value/label/unit-suffix/threshold-coloring
  all come from the new pid, asserted against a coolant reading that's RED pre-swap so a "still
  red, still coolant" bug would be caught); and both orientations. Two `M1 -` cases pin the
  `pickerTileId` self-heal (review round-1 M1): the scrim is gone after a completed swap, and an
  id restored into gaugeOrder *via the ViewModel directly* (not a second long-press, which would
  itself overwrite `pickerTileId` as a side effect and mask the bug) doesn't reopen its picker.
  One `M3 -` case pins the Loading-state placeholder fallback (review round-1 MINOR M3): a
  `gaugeOrder` naming a swapped-in id renders that tile (as a placeholder) even before any real
  `DashboardUiState` has arrived. `DEFAULT_GAUGE_ORDER` (all four
  core gauges visible) is used throughout so every picker has exactly two candidates — current
  plus rpm — deliberately small enough to stay within `LazyRow`'s initial composition window;
  `.performScrollTo()` is still used before touching the (peeking, second) rpm card regardless,
  per this module's known off-screen-node pitfall (see `SettingsScreenTest`'s note above).
- `GaugeSwapDemoTest` (OBD-42, `src/testDemo/`) — the AC's "demo flavor demonstrates it" made
  concrete: a *real* `FakeVehicleDataSource(Scenario.GRADE_CLIMB)` wired through the real
  `DashboardViewModel`, proving `GAUGE_CATALOG` actually reaches `dataSource.start()` end to end
  (regressing that back to `DASHBOARD_PIDS` would make this fail with the placeholder text, not
  just a unit-level assertion). Drives the fake's replay to completion via a `TestCoroutineScheduler`
  + `StandardTestDispatcher` and a plain (non-suspend) `advanceUntilIdle()` call — real wall-clock
  `waitUntil` polling was tried first and doesn't reliably work here, since `FakeVehicleDataSource`'s
  default scope ticks via real `delay()` on `Dispatchers.Default` while the ViewModel's own
  `combine()`/Compose recomposition pipeline runs on Robolectric's paused main looper; a shared
  virtual-time scheduler sidesteps that entirely.
- `GaugePickerScreenshotTest` (OBD-42, `src/testDemo/`) — Roborazzi, one new reference
  (`gauge_picker_mode.png`, landscape, TOWN_HEAT_SOAK tail, deliberately requesting
  `GAUGE_CATALOG` so rpm's mini-card shows a real value): a coolant tile long-pressed into picker
  mode, the rest of the dashboard still visibly live. Regenerate with the same
  `./gradlew :app:recordRoborazziDemoDebug` / verify with `:app:verifyRoborazziDemoDebug` as
  `DashboardScreenshotTest`; this file's addition left `dashboard_landscape.png`/
  `dashboard_portrait.png` byte-identical (verified via `git status` after recording).
- `app/src/test/resources/robolectric.properties` pins `sdk=34` for all Robolectric tests in
  this module (independent of `compileSdk`/`targetSdk` 36).

## Known limitations

- `DataSourceModule`'s clock/`FakeVehicleDataSource` timeline mismatch (OBD-10 era) is fixed
  via `RestartAnchoredDataSource` (`src/demo/.../datasource/`): a decorator that records the
  wall-clock instant of every `start()` and derives the injected `Clock` as
  `EPOCH + (now − lastStart)`, so the clock re-anchors in lockstep with the fake's virtual
  timeline on every `WhileSubscribed` background/foreground restart. Regression-covered by
  `DataSourceModuleClockTest` (provisioning + restart re-anchor; fails under the original
  `systemDefaultZone` wiring).
- `DASHBOARD_PIDS`' `request`/`parse`/`pollPriority` fields are placeholders (unused by the
  fake); do not treat them as verified PID definitions. The real registry is
  `:core:protocol`'s responsibility.
- `SettingsScreen`'s outer `Column` is a `verticalScroll` taller than the viewport (five sections
  plus dividers) — `SettingsScreenTest` needs `.performScrollTo()` before interacting with
  anything below the fold (an earlier version of this file misdiagnosed the resulting off-screen
  silent no-op clicks as a Robolectric/`BasicTextField` rendering limitation and worked around it
  by testing sections in isolation instead of fixing the test; review round-1 M2 corrected this —
  see `SettingsScreenTest`'s file KDoc).
- OBD-20's "no jank at 4 Hz" is verified headlessly only (recomposition-scope isolation +
  downsample cost bound) — real frame timing needs a device macrobenchmark (OBD-34). See the
  "Sparklines" section above.
- OBD-21's threshold storage is pegged to whatever unit `DASHBOARD_PIDS` currently declares
  (today Fahrenheit/PSI), not a fixed SI unit — see "Unit conversion" above for the design
  rationale and the **known migration gap** for whenever OBD-25 changes those declared units.
- `ConnectionBanner` has no dedicated light-theme/rotation screenshot coverage (only the
  existing `DashboardScreenshotTest` references, which happen to be in the `Ready`/hidden
  state) — `ConnectionBannerTest`'s Robolectric assertions (testTag/stateDescription/text) are
  the source of truth for its visual states instead.
- `createComposeRule()` (compose-ui-test) is deprecated in favor of a v2 API using
  `StandardTestDispatcher`; left as-is for OBD-10 since it compiles and passes today. Revisit
  on the next compose-ui-test bump.
- `roborazzi` was pinned to 1.60.0 (not the newest, 1.71.0): Roborazzi bumped its own Kotlin
  compiler to 2.3.21 in 1.61.0, which is metadata-incompatible with this project's Kotlin
  2.1.21. 1.60.0 is the last release built with Kotlin 2.0.21. Revisit when this project's
  Kotlin version moves.
- In this coroutines-test setup, a `TestScope.backgroundScope` coroutine parked in `delay()`
  was observed not to resume under a plain `advanceUntilIdle()` (confirmed via `currentTime`
  staying at 0); `advanceTimeBy(explicit duration)` and the test body's own `this` scope both
  work correctly. Tests here drive `FakeVehicleDataSource` on `this` (safe — its scripts are
  finite) and reserve `backgroundScope` for the one genuinely-infinite job
  (`viewModel.uiState.collect {}`), whose suspension is a direct Flow handoff rather than a
  scheduled delay and so isn't subject to the same issue.
