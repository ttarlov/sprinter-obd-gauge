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
  - `DashboardScreen.kt` — `GaugeDashboard`: `ConnectionBanner` (OBD-11) as the top sibling,
    then the tile layout (landscape: single `Row`; portrait: scrollable `Column`, tiles sized
    to content so nothing clips) in a `Modifier.weight(1f)` box below it. `safeDrawingPadding()`
    is applied once at the outer `Column` (moved here from each orientation branch in OBD-10 —
    now that the banner is a sibling rather than nested inside the tile layout, one application
    covers both). `GaugeTile`, `BoostArc` (sweep arc, −2..+18 PSI, always neutral-colored — see
    `BoostArc.kt`). Each tile exposes a `testTag("gauge-<id>")` root with a `stateDescription`
    semantics property carrying the threshold zone name, plus `testTag("gauge-<id>-value")` on
    the value text — this is how tests assert color without pixel-diffing.
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
  `GaugeFormattingTest`, `BoostArcTest` — plain JVM, no Robolectric, `src/test/` (flavor-common).
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
  renders nothing — these references are unchanged pixel-for-pixel from OBD-10 despite the
  `GaugeDashboard` layout restructuring, confirmed by re-recording and diffing byte-identical).
  References committed under `src/testDemo/screenshots/` (moved from `src/test/screenshots/`
  alongside the test file, OBD-12). Regenerate with `./gradlew :app:recordRoborazziDemoDebug`;
  verify with `./gradlew :app:verifyRoborazziDemoDebug` (see "Build flavors" for why the task
  name changed from the pre-flavor `verifyRoborazziDebug`). `captureRoboImage` no-ops (doesn't
  compare or write) under a plain `testDemoDebugUnitTest` run — only the dedicated Roborazzi
  tasks (or `-Proborazzi.test.record=true` / `-Proborazzi.test.verify=true`) actually
  record/compare, so the base build gate never fails on an environment-sensitive pixel diff.
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
- Live sparklines and the settings screen (OBD-20/21) are not built yet.
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
