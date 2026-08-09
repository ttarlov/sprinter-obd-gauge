# :app

Android application. Compose + Material 3, Hilt-wired end to end.

## Public surface

- `ObdGaugeApplication` — `@HiltAndroidApp` root.
- `MainActivity` — `@AndroidEntryPoint`, single-activity, landscape-locked for the dash
  mount. Renders a placeholder `Text` via `ScaffoldGreeting` (constructor-injected, no
  dependencies) purely to prove the Hilt graph assembles and compiles; deleted once a real
  `VehicleDataSource`-backed ViewModel lands in Sprint 1.
- `ui/theme/` — Material 3 theme, dark by default (`ObdGaugeTheme(forceDark = true)`) since
  this app runs on a dash mount, usually at night.

## Known limitations

- No real UI yet. The gauge dashboard, connection banner, sparklines, and settings screen
  (OBD-10/11/20/21) are Sprint 1/2c work — see `docs/01-build-plan.md` §2A.
- The `demo` build flavor (wired to `FakeVehicleDataSource`, OBD-12) does not exist yet;
  `tools/gate.sh` checks for an `assembleDemoDebug` task and skips it if absent, per
  `docs/05-local-workflow.md` §2.
- Depends on `:core:ble` directly at scaffold time only so the Hilt graph has something to
  wire beyond the placeholder; real DI bindings (`ObdLink` → `VehicleDataSource` →
  ViewModel) arrive with Phase 4 integration.
