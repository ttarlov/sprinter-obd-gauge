---
issue: OBD-10
round: 1
reviewers: [rev-correctness, rev-platform, rev-arch]
verdict: changes-requested
gate: green
reviewed-commit: af02ca3
---

Round-1 review of `ui/10-gauge-dashboard`. rev-arch: **approved** (isolation, contracts,
dependency direction, MODULE.md, commit hygiene all verified clean). rev-correctness and
rev-platform: **changes-requested**. Findings assembled by the orchestrator from the three
reviewer reports; reviewers were fresh isolated agents per doc 05 §5.4.

Items marked ✅-main were fixed by the orchestrator on `main` (orchestrator-owned modules)
in commits `48df1d4`/`79b0e7c` — the author picks them up by rebasing; they need no author
action beyond adopting them.

### [BLOCKER] B1 — Red threshold state unreachable by any test (rev-correctness)
**Where:** DashboardScreenTest.kt:48-59 vs ScriptStep.kt tails vs ThresholdConfig.kt seed table
**What's wrong:** TOWN_HEAT_SOAK tails (coolant 225 / oil 240 / trans 215) can never cross a
red boundary (coolant >230, trans >250, oil has no red). `ThresholdZone.RED` render path is
never executed by any test; only the pure classifier sees RED.
**Why it matters:** AC6 claims "renders amber and red states correctly" — a broken red branch
ships green.
**Required fix:** render a state built via `toDashboardUiState` from a hand-built red-valued
reading map and assert `stateDescription == "red"` + value text (avoids touching core/testing).
**Verify by:** new test in `:app:testDebugUnitTest`.

### [MAJOR] M1 — Screenshot verification not in the merge gate (rev-correctness) ✅-main
gate.sh now runs `:app:verifyRoborazziDebug` when the task exists. Author action: none
(rebase). Reference images are now load-bearing.

### [MAJOR] M2 — No gated test asserts an actual color; KDoc overclaims (rev-correctness)
**Where:** DashboardScreenTest.kt:22-27, 61-62; DashboardScreen.kt zoneColor
**Required fix:** with M1 fixed the screenshots become the color assertion; additionally make
`zoneColor` internal and unit-test each zone→theme-color mapping. Soften the KDoc to "zone
semantics".
**Verify by:** swapping two zoneColor branches locally must fail the suite.

### [MAJOR] M3 — Boost tile never asserted; BoostArc math untested (rev-correctness)
**Where:** DashboardScreenTest.kt:39-44,53-58; BoostArc.kt:34-36
**Required fix:** assert `gauge-boost-value` text + NEUTRAL zone in DashboardScreenTest;
extract the arc fraction computation to a testable function; unit-test −5, −2, 0, 8, 18, 25
(clamp behavior included).

### [MAJOR] M4 — Stale-data treatment never render-tested (rev-correctness)
**Where:** DashboardScreen.kt:105,115-122; only negative assertions exist
**Required fix:** drive a stale reading map through `toDashboardUiState` in a
DashboardScreenTest case; assert `gauge-coolant-stale` exists with expected "last seen Xs ago"
text. Must fail if the stale branch is deleted.

### [MAJOR] M5 — Portrait AC asserted only as "didn't throw" (rev-correctness)
**Required fix:** `@Config(qualifiers = "w360dp-h640dp-port")` DashboardScreenTest case
asserting all four `gauge-*-value` nodes displayed (scroll as needed) + landscape counterpart.

### [MAJOR] M6 — Lifecycle-blind collection (rev-platform)
**Where:** MainActivity.kt:33
**Required fix:** `collectAsStateWithLifecycle()`; declare
`androidx.lifecycle:lifecycle-runtime-compose` explicitly in app/build.gradle.kts.

### [MAJOR] M7 — Producer start/stop bound to VM lifetime, not subscription (rev-platform)
**Where:** DashboardViewModel.kt:29-31,42-44
**Required fix:** drive producer from subscription:
`dataSource.readings.onStart { dataSource.start(...) }.onCompletion { dataSource.stop() }`
upstream of `stateIn`, so `WhileSubscribed(5_000)` actually gates polling. Add a test:
`start` not called until `uiState` has a collector; `stop` after timeout with none.

### [MAJOR] M8 — System bars follow system theme while app forces dark (rev-platform)
**Where:** MainActivity.kt:29 (`enableEdgeToEdge()` no-arg)
**Required fix:** `enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
navigationBarStyle = SystemBarStyle.dark(TRANSPARENT))` while the theme is dark-only.

### [MAJOR] M9 — Prod domain code keys off :core:testing's ScenarioChannel (rev-platform) ✅-main (partially)
`PidIds` now exists in `:core:model`; ScenarioChannel aliases it. **Author action:** switch
ThresholdConfig.kt, DashboardUiState.kt, DashboardPids.kt imports to
`com.revel.obdgauge.model.PidIds`. After the fix,
`grep -rn "com.revel.obdgauge.testing" app/src/main/kotlin/` must return only
di/DataSourceModule.kt.

### [MAJOR] M10 — Test libraries dexed into the APK (rev-platform) ✅-main
junit/turbine/coroutines-test moved to testImplementation in :core:testing. Author action:
none (rebase); flavor-scoping of the fake itself remains OBD-12 scope. Verify:
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -Ei "junit|turbine|coroutines-test"` → empty.

### [NIT] N1 — Trans boundary values 200.0/250.0 unpinned (rev-correctness)
### [NIT] N2 — Placeholder/Loading path ("—"/NEUTRAL on empty map) untested (rev-correctness)
### [NIT] N3 — `onCleared()` missing `super.onCleared()` (rev-platform)
### [NIT] N4 — Orientation branch reads LocalConfiguration, not constraints (rev-platform)
### [NIT] N5 — DashboardUiState unstable to Compose compiler; `@Immutable` (rev-platform)

## Fix list
- [x] B1: red-state render test
- [x] M2: zoneColor unit test + KDoc honesty
- [x] M3: boost tile assertion + arc fraction tests
- [x] M4: stale render test
- [x] M5: portrait/landscape displayed-assertions
- [x] M6: collectAsStateWithLifecycle + explicit dep
- [x] M7: subscription-driven start/stop + test
- [x] M8: explicit dark SystemBarStyle
- [x] M9: switch app imports to PidIds
- [x] N1-N5 (optional but cheap — do them unless one fights back)

## Author responses (round 1)

**B1** ✅ fixed in `f9de200` — new `DashboardScreenTest` case
(`hand-built red-valued readings render the red state`) drives coolant 235°F / trans 255°F
through `toDashboardUiState` directly (TOWN_HEAT_SOAK's tail never crosses red) and asserts
`stateDescription == "red"` on both tiles plus their value text.

**M2** ✅ fixed in `f9de200` — `zoneColor` is now `internal`, unit-tested per zone in
`DashboardScreenTest`, and the class KDoc no longer claims to assert "threshold-zone color"
(softened to "zone semantics"; actual color is now covered by the M1 screenshot gate plus
`zoneColor`'s own unit test).

**M3** ✅ fixed in `f9de200` — TOWN_HEAT_SOAK's screen test now asserts
`gauge-boost-value` text ("1.0 PSI") and `NEUTRAL` zone. `BoostArc`'s sweep math is extracted
to `boostArcFraction()` (`BoostArc.kt`) and unit-tested in new `BoostArcTest.kt` at -5, -2, 0,
8, 18, 25 (clamp behavior included).

**M4** ✅ fixed in `f9de200` — new `DashboardScreenTest` case drives a stale coolant reading
through `toDashboardUiState` and asserts `gauge-coolant-stale` renders "last seen 42s ago".

**M5** ✅ fixed in `f9de200` — `@Config`-qualified portrait/landscape `DashboardScreenTest`
cases assert all four `gauge-*-value` nodes are displayed (`performScrollTo()` in portrait).

**M6** ✅ fixed in `bddb685` — `MainActivity` collects via `collectAsStateWithLifecycle()`;
`androidx.lifecycle:lifecycle-runtime-compose` declared explicitly in `app/build.gradle.kts`.

**M7** ✅ fixed in `bddb685` — `DashboardViewModel.uiState` now drives
`dataSource.start()`/`stop()` from `onStart`/`onCompletion` upstream of `stateIn`, so
`WhileSubscribed(5_000)` actually gates polling. New `DashboardViewModelTest` case
(`producer starts only once uiState has a collector and stops after the subscription
timeout`) asserts both halves via a `RecordingVehicleDataSource` call-count wrapper.

**M8** ✅ fixed in `bddb685` — `enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(...),
navigationBarStyle = SystemBarStyle.dark(...))`, matching the theme's unconditional dark mode.

**M9** ✅ fixed in `a67956e` — `ThresholdConfig`, `DashboardUiState`, and `DashboardPids` now
import `com.revel.obdgauge.model.PidIds` directly; the one remaining KDoc mention of
`FakeVehicleDataSource` in `DashboardPids.kt` was reworded to prose so it no longer resolves
through `com.revel.obdgauge.testing`. `grep -rn "com.revel.obdgauge.testing"
app/src/main/kotlin/` now returns only `di/DataSourceModule.kt`, as required.

**N1** ✅ fixed in `f9de200` — added exact-boundary `ThresholdConfigTest` cases at trans
200.0 and 250.0 (both amber; boundaries are exclusive).

**N2** ✅ fixed in `f9de200` — `DashboardViewModelTest` now asserts an empty readings map
renders placeholder text (`NO_READING_TEXT`) and `NEUTRAL` zone.

**N3** ✅ fixed in `bddb685` — `DashboardViewModel.onCleared()` now calls
`super.onCleared()`.

**N4** ✅ fixed in `f9de200` — `GaugeDashboard` reads orientation from its own
`BoxWithConstraints` bounds instead of `LocalConfiguration`'s device screen size.

**N5** ✅ fixed in `bddb685` — `DashboardUiState` and `GaugeTileUiState` are now
`@Immutable`.
