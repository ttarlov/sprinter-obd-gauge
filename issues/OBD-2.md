---
id: OBD-2
title: Multi-module Gradle scaffold
module: process
owner: orchestrator
sprint: 0
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: main
---

## Feature
A multi-module Gradle project (`:app`, `:core:model`, `:core:protocol`, `:core:ble`, `:core:testing`) compiles clean with a version catalog pinning Kotlin 2.1+, Compose BOM, and targetSdk 36.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Gradle project has `:app`, `:core:model`, `:core:protocol`, `:core:ble`, `:core:testing` modules
- [ ] Version catalog (`libs.versions.toml`) pins Kotlin 2.1+, Compose BOM, targetSdk 36
- [ ] Module dependency graph matches the plan: `:app` depends on all `:core:*`; `:core:protocol`/`:core:ble` depend on `:core:model`; no reverse or lateral illegal edges
- [ ] `./gradlew assembleDebug` succeeds locally
- [ ] `tools/gate.sh` wired to actually run `assembleDebug` (real check, replacing the OBD-1 stub)
- [ ] `MODULE.md` per module documents its purpose and allowed dependencies

## Self-test plan
`tools/gate.sh` run end to end; a deliberate illegal-edge commit (e.g. `:core:model` depending on `:core:ble`) is caught by `module-isolation.sh` or fails the build.

## Out of scope
Contracts/interfaces (OBD-3); fakes (OBD-4/5).
