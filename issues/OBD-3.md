---
id: OBD-3
title: Phase-0 contracts freeze
module: core/model
owner: orchestrator
sprint: 0
status: merged
type: contract-change
hardware-verify: false
blocked-by: []
branch: main
---

## Feature
The Phase-0 contracts (`ObdLink`, `VehicleDataSource`, `PidDefinition`, `Reading`, `LinkState`) are frozen in `:core:model` — the surface every other module builds against for the rest of the epic.

## Contract surface
This IS the contract freeze. Every interface/data class listed below is the surface. Any change to these types after this issue merges requires a new `type: contract-change` issue plus a `DECISIONS.md` entry and Taras sign-off (doc 05 §8).

## Acceptance criteria
- [ ] `ObdLink`, `VehicleDataSource`, `PidDefinition`, `Reading`, `LinkState` defined in `:core:model` with full KDoc on every public member
- [ ] Module compiles with zero implementations (interfaces/data classes only, no logic)
- [ ] `DECISIONS.md` records the freeze: what's frozen, why, and the amendment process
- [ ] No other module references cross-module contract types outside this frozen set

## Self-test plan
Compile check only (`:core:model:compileKotlin`); no runtime tests possible pre-implementation.

## Out of scope
Implementations of these contracts (Sprints 1–2); fakes (OBD-4/5) implement against this frozen surface but are separate issues.
