# :app

Android application. Compose + Material 3, Hilt-wired end to end. Depends on `:core:model`
and `:core:testing` only (see OBD-10 scope note below); no `:core:protocol`/`:core:ble` at
this stage.

## Public surface

- `ObdGaugeApplication` — `@HiltAndroidApp` root.
- `MainActivity` — `@AndroidEntryPoint`, single-activity. No `screenOrientation` lock in the
  manifest: landscape is the primary target (a phone on a dash mount), but portrait is fully
  supported, not just tolerated. Hosts `GaugeDashboard` fed by a Hilt-injected
  `DashboardViewModel`.
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
    `ThresholdConfig.seed` is keyed by `ScenarioChannel`/`PidDefinition.id` strings, ready for
    OBD-21 to make user-editable.
  - `GaugeFormatting.kt` — pure formatting functions (`formatGaugeValue`, `formatStaleText`,
    `unitSuffix`): whole-degree temps, one-decimal PSI (sign-correct for `-1 < value < 0`),
    "last seen Xs ago" for stale readings. Zero protocol math.
  - `DashboardUiState.kt` — `GaugeTileUiState`/`DashboardUiState` plus the pure
    `toDashboardUiState(readings, connection, now)` mapper shared by the ViewModel and tests.
  - `DashboardPids.kt` — `DASHBOARD_PIDS`, the four `PidDefinition`s (`coolant`, `oilTemp`,
    `transTemp`, `boost`) this dashboard requests. `request`/`parse` are placeholder values
    unused by `FakeVehicleDataSource` (it replays by id, not by asking a dongle); the real
    mode-22 registry lives in `:core:protocol` and replaces this list at Phase-4 integration.
  - `DashboardViewModel` — `@HiltViewModel`; `StateFlow<DashboardUiState>` out, formatting
    only. Takes an injected `VehicleDataSource` and `java.time.Clock` (the latter only for
    stale-text "seconds ago" math, injected so tests can fix it).
  - `DashboardScreen.kt` — `GaugeDashboard` (landscape: single `Row`; portrait: scrollable
    `Column`, tiles sized to content so nothing clips), `GaugeTile`, `BoostArc` (sweep arc,
    −2..+18 PSI, always neutral-colored — see `BoostArc.kt`). Each tile exposes a
    `testTag("gauge-<id>")` root with a `stateDescription` semantics property carrying the
    threshold zone name, plus `testTag("gauge-<id>-value")` on the value text — this is how
    tests assert color without pixel-diffing.
- `di/DataSourceModule.kt` — Hilt bindings: `VehicleDataSource` → `FakeVehicleDataSource(
  Scenario.GRADE_CLIMB)` (demo wiring, sanctioned for OBD-10 per the build plan; OBD-12
  formalizes a `demo` build flavor), `Clock` → `Clock.systemDefaultZone()`.

## Tests

- `ThresholdConfigTest`, `GaugeFormattingTest` — plain JVM, no Robolectric.
- `DashboardViewModelTest` — plain JVM; drives `FakeVehicleDataSource` + `DashboardViewModel`
  under `kotlinx-coroutines-test`. Asserts IDLE tail (green) and TOWN_HEAT_SOAK tail (amber
  coolant/oil/trans, per seed thresholds).
- `DashboardScreenTest` — Robolectric + compose-ui-test. Same two scenarios, rendered through
  `GaugeDashboard`, asserted via `testTag`/`stateDescription` and value text.
- `DashboardScreenshotTest` — Roborazzi, landscape (`w800dp-h360dp-land`) and portrait
  (`w360dp-h640dp-port`), TOWN_HEAT_SOAK tail state. References committed under
  `src/test/screenshots/`. Regenerate with `./gradlew :app:recordRoborazziDebug`; verify with
  `./gradlew :app:verifyRoborazziDebug`. `captureRoboImage` no-ops (doesn't compare or write)
  under a plain `testDebugUnitTest` run — only the dedicated Roborazzi tasks (or
  `-Proborazzi.test.record=true` / `-Proborazzi.test.verify=true`) actually record/compare, so
  the base build gate never fails on an environment-sensitive pixel diff.
- `app/src/test/resources/robolectric.properties` pins `sdk=34` for all Robolectric tests in
  this module (independent of `compileSdk`/`targetSdk` 36).

## Known limitations

- Connection state banner, live sparklines, and the settings screen (OBD-11/20/21) are not
  built yet — out of scope for OBD-10 per `issues/OBD-10.md`. `DashboardUiState.connection`
  carries the `LinkState` for OBD-11 to consume; it isn't rendered yet.
- `DataSourceModule` wires `FakeVehicleDataSource(Scenario.GRADE_CLIMB)` unconditionally —
  there is no `prod` binding yet. Phase-4 integration replaces this with the real
  `ObdLink`→`VehicleDataSource` chain for a `prod` flavor while a `demo` flavor (OBD-12) keeps
  this fake wiring.
- `DataSourceModule`'s `Clock` is `Clock.systemDefaultZone()` (real wall clock), but
  `FakeVehicleDataSource`'s default `startInstant` is `Instant.EPOCH` — "last seen Xs ago"
  would render a nonsensical huge number if a demo scenario ever went stale. Not an issue
  today: `GRADE_CLIMB` never disconnects. Worth revisiting if the demo default scenario ever
  changes to `DISCONNECT_RECONNECT`.
- `DASHBOARD_PIDS`' `request`/`parse`/`pollPriority` fields are placeholders (unused by the
  fake); do not treat them as verified PID definitions. The real registry is
  `:core:protocol`'s responsibility.
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
