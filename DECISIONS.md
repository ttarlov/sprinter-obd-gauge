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

## D1 — Library vs custom ELM327 layer: custom + vendored tables (2026-08-09, Taras-approved)

**Decision:** custom ELM327 layer in `:core:protocol`, scoped exactly to this
app (init state machine, 6 standard PIDs, 2-3 mode-22 Mercedes PIDs, tolerant parser,
single-flight scheduler — ~590 LOC est.), **vendoring** kotlin-obd-api's standard-PID
scaling constants and its Response/Exceptions parsing patterns under Apache-2.0 with
attribution. No runtime library dependency.

**Panel record** (`research/`): library-position.md (R1), custom-position.md (R2),
constraints.md (R3). Rubric outcome: library 3.20 / custom ~8.75 weighted. The case
turned on the 40%-weighted constraint: neither obd-java-api (archived 2017) nor
kotlin-obd-api (alive, v1.4.1) ships ATSH/ATCRA header control or mode-22 support —
kotlin-obd-api's `SetHeadersCommand` is the ATH display toggle, not ATSH, with no raw
escape hatch — and its transport owns raw streams, which would bypass the frozen
`ObdLink` boundary. After rebuttals BOTH advocates converged on custom + vendoring.

**What the vendoring buys:** community-verified SAE scaling for the six standard PIDs
(neutralizes the hand-derived-formula risk R2 conceded) and battle-tested
SEARCHING/NO DATA/STOPPED error typing as reference. Mode-22 MTH scaling remains
hypothesis-until-hardware (Sprint 3 flips `verified`).

**Signed off** by Taras 2026-08-09 ("approved"). OBD-9 merged; OBD-15 unblocked.

## D4 — Risk-tiered review policy (2026-08-09, Taras-approved)

**Decision:** replace the flat two-Opus-per-branch review matrix with consequence-tiered
review — full matrix for `:core:protocol`/`:core:ble`/contract-touching work (Tier A),
one combined-lens Opus reviewer for established `:app` work (Tier B), Sonnet + scripts for
telemetry/polish (Tier C) — plus small-issue batching onto shared branches. Evidence-driven
ratchet moves modules between tiers. Full spec: docs/05-local-workflow.md §5.5.

**Rationale:** Sprint-1 data. Review caught 1 blocker + 9 real majors on a self-reported
green branch (process validated), but a single combined-lens round proved equivalent at
~60% cost, and flat provisioning priced the remaining ~22 branches at ~5M tokens of review
alone. Anything that computes a displayed gauge value stays Tier A permanently.

**Impact:** remaining-project estimate drops from ~6–8M to ~4.7M tokens (recalibrated
table in doc 05 §10.3).

---

## D3 — Publish to GitHub

⬜ Deferred. Per-action OK from Taras; see docs/05-local-workflow.md §9.

## D5 — Dual-channel builds: `develop` + side-by-side dev/main APKs (proposed by Taras 2026-08-11)

**Status:** proposed by Taras 2026-08-11; side-by-side install decision made by orchestrator
same day.

**Decision:** two long-lived build channels. Ad-hoc feature branches merge to `develop`
(same issue frontmatter + risk-tiered review as everything else, doc 05 §5.5) — every
`develop` merge produces a dev APK. `main` stays the promoted, phone-install branch — every
`main` merge produces the master APK. Promotion `develop`→`main` happens only on Taras's
explicit acceptance, never automatically.

**Side-by-side install (orchestrator decision, 2026-08-11):** the dev APK must install
ALONGSIDE the master build, not replace it, so a sideloaded test build can never clobber
what's on Taras's phone by accident. Implemented as a Gradle property switch —
`-Pchannel=dev` applies `applicationIdSuffix ".dev"` + a visibly distinct launcher label
("OBD Gauge Dev") — deliberately NOT a new `flavorDimension`, to keep the existing
demo/prod × debug/release matrix untouched. When the property is absent, `defaultConfig` is
unaffected: this is enforced by `tools/gate.sh` staying green with zero variant drift
(OBD-45 AC).

**Merge-integration choice:** `tools/merge.sh` prints a mandatory post-merge instruction
(`tools/channel-build.sh <dev|main>`) rather than invoking it automatically. Rationale in
docs/05-local-workflow.md §D5.

**Implementation:** OBD-45 (`tools/channel-build.sh`, `app/build.gradle.kts` channel
switch, `tools/merge.sh` `MERGE_TARGET_BRANCH` + post-merge instruction, `builds/`
gitignored). Full spec: docs/05-local-workflow.md §D5.

## D6 — Small-track ad-hoc: agent builds, orchestrator reviews (Taras, 2026-08-11)

**Status:** decided by Taras 2026-08-11, prompted by the OBD-44/45 wave costing ~625k for a
"tiny" request (the full builder+reviewer shape has a ~450k floor on :app work).

**Decision:** small ad-hoc features get a lighter shape: a spawned builder agent in a
worktree (isolation stays), with the orchestrator (1) prescribing the complete workflow and
git process in the spawn brief — branch, base, merge target, commit rules, gate — and
(2) performing the quality review itself instead of spawning a reviewer, with targeted
verification/mutations and a normal review record so merge.sh is unchanged.

**Bounds:** single-module :app/tooling work only; no contract surface, no protocol/BLE, no
displayed-value computation — Tier A never rides the small track (§5.5 outranks). BLOCKER
findings or scope creep into excluded surface escalate to a spawned Tier-B reviewer.
`track: small` recorded in issue frontmatter. Full spec: docs/05-local-workflow.md §6c.
