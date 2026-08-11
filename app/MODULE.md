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
  - `DashboardPids.kt` — `DASHBOARD_PIDS`, the four `PidDefinition`s (`coolant`, `oilTemp`,
    `transTemp`, `boost`) this dashboard requests, plus `DASHBOARD_PIDS_BY_ID` (OBD-21, a
    `associateBy { it.id }` lookup other code uses instead of re-hardcoding a unit/label).
    `request`/`parse` are placeholder values unused by `FakeVehicleDataSource` (it replays by id,
    not by asking a dongle); the real mode-22 registry lives in `:core:protocol` and replaces
    this list at Phase-4 integration.
  - `DashboardViewModel` — `@HiltViewModel`; `StateFlow<DashboardUiState>` out, formatting
    only. Takes an injected `VehicleDataSource`, `java.time.Clock` (stale-text "seconds ago"
    math), and (OBD-21) `SettingsRepository` — combined three-way so a settings edit recolors/
    reformats `uiState` live. Also exposes `gaugeOrder`/`keepScreenOn` `StateFlow`s (derived from
    the same repository) and `sparklineFlow(id)` (OBD-20, backed by `SparklineHistoryHolder`) —
    see the "Sparklines"/"Settings screen" sections below.
  - `DashboardScreen.kt` — `GaugeDashboard`: a settings-gear `Row` (OBD-21) alongside
    `ConnectionBanner` (OBD-11) at the top, then the tile layout (landscape: single `Row`;
    portrait: scrollable `Column`, tiles sized to content so nothing clips) in a
    `Modifier.weight(1f)` box below it. `safeDrawingPadding()` is applied once at the outer
    `Column`. Tiles are now rendered by iterating `gaugeOrder` (OBD-21, defaults to the original
    hardcoded coolant/trans/oil/boost order/visibility) through a `GaugeSlot` dispatcher that
    picks `GaugeTile` or `BoostTile` (arc, −2..+18 PSI, always neutral-colored — see
    `BoostArc.kt`) per id. Each tile exposes a `testTag("gauge-<id>")` root with a
    `stateDescription` semantics property carrying the threshold zone name, plus
    `testTag("gauge-<id>-value")` on the value text, and (OBD-20, when a `sparklines` entry
    exists for that id) a `testTag("gauge-<id>-sparkline")` leaf — this is how tests assert
    color/sparkline-presence without pixel-diffing.
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
against `DASHBOARD_PIDS_BY_ID` on every decode (see "Settings screen" above), so an id the
catalog drops is silently pruned and a new one the catalog gains shows up (visible, appended)
automatically — no OBD-25 migration action needed for that field specifically.

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
  `DashboardScreenshotTest`, `ConnectionBannerTest`, `DataSourceModuleClockTest` —
  `src/testDemo/` (need `FakeVehicleDataSource`/`Scenario`, so `testDemoDebugUnitTest`-only;
  see "Build flavors").
  `GaugeFormattingTest`, `BoostArcTest`, `UnitConversionTest`, `SparklineBufferTest`,
  `SparklineGapTest`, `SparklineHistoryHolderTest`, `SparklineRecompositionTest`,
  `DashboardUiStateTest`, `SettingsCodecTest`, `SettingsRepositoryTest`, `SettingsScreenTest`,
  `LiveRecolorTest` — plain JVM or Robolectric, no `:core:testing` dependency, `src/test/`
  (flavor-common — run under both `testDemoDebugUnitTest` and `testProdDebugUnitTest`).
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
