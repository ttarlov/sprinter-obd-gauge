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

## D1 — Library vs custom ELM327 layer

⬜ Open. Decided by the Sprint 1 research panel + rubric (OBD-9), Taras sign-off.

## D3 — Publish to GitHub

⬜ Deferred. Per-action OK from Taras; see docs/05-local-workflow.md §9.
