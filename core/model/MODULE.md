# :core:model

Pure Kotlin/JVM. Zero Android dependencies, zero DI annotations (see `DECISIONS.md` D2).

## Public surface

The frozen Phase-0 contracts (`docs/01-build-plan.md` §0.2, `DECISIONS.md`), package
`com.revel.obdgauge.model`:

- `LinkState` / `LinkError` — connection lifecycle and typed failure reasons.
- `ObdLink` — raw byte/string pipe to the dongle (implemented by `:core:ble`).
- `MeasurementUnit` — value units (named to avoid clashing with `kotlin.Unit`).
- `ObdRequest` (`StandardPid`, `Mode22`) — how to ask for a value.
- `PollPriority` — `FAST` / `SLOW` scheduling tier.
- `PidDefinition` — one gauge's request + parse function + metadata.
- `Reading` — one parsed value, as consumed by the UI.
- `VehicleDataSource` — what the UI consumes (implemented by `:core:protocol`).

Every public member carries KDoc; read the source, it's short.

## Known limitations

- Interfaces only — zero implementations live here by design. `:core:protocol` and
  `:core:ble` implement `VehicleDataSource` and `ObdLink` respectively; `:core:testing`
  provides fakes.
- These types are frozen: changing any of them requires an orchestrator decision logged in
  `DECISIONS.md` before the change lands (`docs/05-local-workflow.md` §6 step 3).
