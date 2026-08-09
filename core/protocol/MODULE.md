# :core:protocol

Pure Kotlin/JVM. Zero Android dependencies, zero DI annotations (see `DECISIONS.md` D2).
Depends on `:core:model` only.

## Public surface

Empty at Sprint 0. This module is the ELM327/OBD command layer: init state machine, the
`PidDefinition` registry (standard + Mercedes mode-22 PIDs), the poll scheduler, and the
response parser — implementing `VehicleDataSource` over an `ObdLink`. See
`docs/01-build-plan.md` §2B (Agent 2B) for the full spec; work starts in Sprint 2a
(OBD-13…16), gated on the library-vs-custom decision (OBD-9) except for standard-PID work,
which is decision-independent.

## Known limitations

- No implementation yet — scaffold only.
- `testImplementation(project(":core:testing"))` is wired so this module's own tests can
  consume `FakeObdLink` once OBD-5 lands; `:core:protocol` never depends on `:core:testing`
  at `implementation` scope (main-source dependency stays `:core:model` only).
