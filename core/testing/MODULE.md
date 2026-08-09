# :core:testing

Pure Kotlin/JVM. Zero Android dependencies, zero DI annotations (see `DECISIONS.md` D2).
Depends on `:core:model` only.

## Public surface

Empty at Sprint 0. This module holds shared fakes and fixtures consumed by every other
module's tests:

- `FakeVehicleDataSource` (OBD-4) — scripted scenarios (`IDLE`, `TOWN_HEAT_SOAK`,
  `GRADE_CLIMB`, `DISCONNECT_RECONNECT`) the UI agent builds entirely against.
- `FakeObdLink` (OBD-5) — recorded ELM327 transcript replayer with latency + garbage/timeout
  injection, the protocol agent builds entirely against.
- Fixture transcripts under `src/main/resources/transcripts/`.

See `docs/01-build-plan.md` §0.3.

## Known limitations

- No fakes exist yet — scaffold only. JUnit, `kotlinx-coroutines-test`, and Turbine are
  wired as `implementation` dependencies (not `testImplementation`) since this module's
  whole job is to be a test-support library other modules' test source sets consume.
