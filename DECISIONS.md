# DECISIONS.md — Sprinter OBD Gauge App

Orchestrator-owned log of decisions that bind the team. Contract changes, library
choices, and process amendments land here BEFORE the work that depends on them.

---

## D2 — Dependency injection: Hilt (2026-08-09)

**Decision:** Hilt (with KSP), not Koin.

**Rationale:** The only automated enforcement in this workflow is `tools/gate.sh`
(build + JVM tests). Koin resolves its graph at runtime — a mis-wired dependency
surfaces only when the app launches on a device, which no agent can do headless.
Hilt validates the full graph at compile time, so a DI wiring mistake fails the
gate instead of shipping. In an agent-driven repo where merge confidence comes
entirely from the build, compile-time validation is decisive. KSP is already in
the stack (Room arrives in Sprint 4), so the build-cost delta is marginal.

**Scope note:** `:core:model`, `:core:protocol`, and `:core:testing` stay pure
Kotlin/JVM — no Hilt annotations outside `:app` and `:core:ble`. Constructor
injection everywhere; modules provide bindings at the `:app` edge.

---

## Contract freeze — Phase-0 interfaces (2026-08-09)

**Frozen** in `:core:model` (`com.revel.obdgauge.model`): `ObdLink`, `VehicleDataSource`,
`PidDefinition`, `ObdRequest`, `Reading`, `LinkState`/`LinkError`, `MeasurementUnit`,
`PollPriority`. Changing any of these from here on requires `type: contract-change` on the
issue, an entry in this file, and Taras sign-off BEFORE work starts (doc 05 §6.3).

Deviations from the build-plan sketch, deliberate:
- `PidDefinition.unit` typed as `MeasurementUnit` enum (the sketch's `Unit` clashes with
  `kotlin.Unit`).
- `ObdRequest` made a sealed interface: `StandardPid(mode, pid)` | `Mode22(header,
  rxFilter, request)` — carries the ATSH/ATCRA framing the Mercedes PIDs need.
- `PidDefinition.verified: Boolean = true` — mode-22 hypotheses ship `false` until
  hardware-verified (Phase 4), surfaced in the UI per OBD-27.
- `Reading.timestamp` is `java.time.Instant` (available API 26+, no desugaring needed).

---

## D1 — Library vs custom ELM327 layer

⬜ Open. Decided by the Sprint 1 research panel + rubric (OBD-9), Taras sign-off.

## D3 — Publish to GitHub

⬜ Deferred. Per-action OK from Taras; see docs/05-local-workflow.md §9.
