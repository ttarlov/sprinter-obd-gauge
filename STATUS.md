# STATUS — Sprinter OBD Gauge App

> **The single source of truth for what's done and what's next.** The orchestrator updates this file at every wave start and wave end, and reconciles it against ground truth (`grep status: issues/`, `git branch -a`, `git log main`) before spawning any agent. A mismatch between this file and the repo is a process bug — fix the mismatch before doing new work. Agents get the relevant slice of this file in their brief.
>
> Lives at `~/projects/sprinter-obd-gauge/STATUS.md` — already the future repo root; Sprint 0 runs `git init` here and thereafter updates land as status commits.

**Last updated:** 2026-08-09 — Sprint 1 wave ended early on budget wind-down. OBD-6/7/8/10 merged, D1 signed off (custom+vendor), OBD-11/12 rolled. Gate green on main.

---

## Where we are

| | |
|---|---|
| Current phase | **Sprint 1 partial** — dashboard merged; wound down over budget |
| Repo | Gate green on main; no open branches |
| Blockers | None |
| Next action | Taras: "run Sprint 1b, +350k" (OBD-11+12, one batched Tier-B branch → demo APK) |

## Wave board

| Wave | Budget | Status | Actual spend | Merged / rolled |
|---|---|---|---|---|
| Sprint 0 — Skeleton & Contracts | +200k | ✅ done 2026-08-09 | ~230k (est.) | 5 merged / 0 rolled |
| Sprint 1 — Decision & Demo Dashboard | +400k | 🔄 partial 2026-08-09 (wound down over budget) | ~1.0M | 4 merged + 1 🖐 / OBD-11+12 rolled |
| Sprint 1b — OBD-11+12 (batched, Tier B) | +350k | ⬜ not started | — | — |
| Sprint 2a — Protocol (Tier A, 2 batched branches) | +900k | ⬜ not started | — | — |
| Sprint 2b — BLE (Tier A, 17+18 batched; 19 solo) | +900k | ⬜ not started | — | — |
| Sprint 2c — UI charts/settings (Tier B, batched) | +400k | ⬜ not started | — | — |
| Sprint 3 — Real Van (software half, mostly Tier A) | +900k | ⬜ blocked: needs 2a+2b | — | — |
| Sprint 4 — Telemetry & Hardening (Tier B/C) | +1.2M | ⬜ not started | — | — |

Targets recalibrated 2026-08-09 per D4 (risk-tiered review + batching, doc 05 §5.5/§10.3);
original table was ~3.5× optimistic against Sprint 0–1 actuals.

Status legend: ⬜ not started · 🔄 in progress · ✅ done · ⛔ blocked (name the blocker)

---

## Sprint 0 — Skeleton & Contracts (orchestrator + Sonnet scaffold agent)

- [x] Persisted agent definitions in `.claude/agents/` (ui, protocol, ble, telemetry, researcher, rev-correctness, rev-platform, rev-arch, merge) — created 2026-08-09 pre-kickoff
- [x] Repo init; `OWNERSHIP`; issue/review directory structure
- [x] `tools/gate.sh` (build, test, ktlint+detekt, module-isolation) — green on scaffold
- [x] `tools/module-isolation.sh` (changed paths vs OWNERSHIP, vs merge-base)
- [x] `tools/merge.sh` (full §6 procedure incl. stale-approval + hardware-verify checks) — syntax-checked; first live run will be Sprint 1's first merge
- [x] OBD-2: multi-module Gradle scaffold — Kotlin 2.1.21, AGP 8.13.2, Gradle 8.13 wrapper, Compose BOM 2026.06.01, Hilt 2.57.2 (D2: Hilt over Koin, see DECISIONS.md), targetSdk 36 — `assembleDebug` green
- [x] OBD-3: Phase-0 contracts frozen in `:core:model` + KDoc + `DECISIONS.md` freeze entry (deviations logged: `MeasurementUnit` rename, sealed `ObdRequest`, `verified` flag)
- [x] OBD-4: `FakeVehicleDataSource` — all 4 scenarios, virtual-clock deterministic, 7 tests
- [x] OBD-5: `FakeObdLink` transcript replayer — latency + 5 fault modes + strict half-duplex, synthetic ELM327 fixture, 17 tests
- [x] All 34 issue files committed; `_sprint-N.md` indexes; this file now repo-tracked
- [x] **Exit:** gate green on main; wave ledger entry written (`issues/_sprint-0.md`)

**Prereq on Taras's Mac:** ✅ verified 2026-08-09 — JDK 17.0.19 (Homebrew, `JAVA_HOME=/opt/homebrew/opt/openjdk@17/...` — system default is JDK 25, gate.sh handles this), Android SDK at `/opt/homebrew/share/android-commandlinetools` (platforms 35+36).

## Sprint 1 — Decision & Demo Dashboard

- [x] OBD-6: library-advocate position (`research/library-position.md`) — Sonnet R1
- [x] OBD-7: custom-layer position (`research/custom-position.md`) — Sonnet R2
- [x] OBD-8: constraints analysis, 40/25/15/10/10 rubric (`research/constraints.md`) — Sonnet R3
- [x] OBD-9: panel unanimous; D1 signed off by Taras 2026-08-09 — custom + vendored tables. Merged.
- [x] OBD-10: gauge dashboard v1 merged (`cd59774`) — 38 tests incl. Roborazzi screenshot verify now in gate; 3 review rounds (1 blocker + 9 major found & fixed)
- [ ] OBD-11: connection banner + stale-data treatment — **rolled** (budget wind-down)
- [ ] OBD-12: demo flavor wired to fake; installable APK — **rolled** (budget wind-down)
- [ ] **Milestone: demo APK on the Pixel running a scripted grade climb** — needs OBD-11/12 (Sprint 1b)

## Sprint 2a — Protocol (Opus)

- [ ] OBD-13: ELM327 init state machine, typed failures, every failure mode tested
- [ ] OBD-14: standard PID registry + parser (0105/010C/010B/0133/010F/010D); property test: never throws; SAE scaling verified
- [ ] OBD-15: mode-22 Mercedes PIDs (ATSH/ATCRA framing; trans-temp from X-Gauge codes); `unverified` flag plumbing
- [ ] OBD-16: computed boost (MAP − baro) + FAST/SLOW scheduler; 3 baro fixtures
- [ ] Parser+scheduler coverage >90%

## Sprint 2b — BLE (Opus)

- [ ] OBD-17: Android 12+ permission flow + filtered scanner + remembered-device fast path
- [ ] OBD-18: GATT serial bridge — UUID probe w/ fallback, CCCD, MTU 512, `>`-terminated reassembly, single-flight mutex; fragmented fixture passes
- [ ] OBD-19: debug console screen (raw AT REPL) — buildable without hardware; **verification is the Sprint-2 hardware demo**
- [ ] Reconnect/reassembly logic extracted & unit-tested against GATT doubles

## Sprint 2c — UI round 2 (Sonnet)

- [ ] OBD-20: 5-min sparkline strips, no jank at 4 Hz (frame timing test)
- [ ] OBD-21: settings screen (gauges, thresholds, units, keep-screen-on, poll rate; DataStore; live recolor test)

## Sprint 3 — Real Van

Software (no hardware):
- [ ] OBD-23: reconnect state machine — backoff, resume, key-off recovery; 50-cycle scripted soak
- [ ] OBD-24: foreground service (`connectedDevice`), persistent notification, screen-off polling
- [ ] OBD-25: prod-flavor DI wiring; JVM end-to-end test over real fixtures
- [ ] OBD-27: unverified-PID badge + raw-response viewer — ui-agent

Hardware gates — **🖐 TARAS**:
- [ ] 🖐 OBD-22a (dongle powered, engine off — bench or parked van): init banner, UUID map, fragmentation capture, error frames → fixtures committed
- [ ] 🖐 OBD-22b (ignition on): standard PIDs at idle, mode-22 trans-temp exchange, unplug-mid-response capture → all Sprint-2 protocol tests re-run green on real transcripts
- [ ] 🖐 OBD-26: in-van bring-up checklist — idle sanity (boost ≈0, RPM ~780), cold-soak convergence, drive test (boost mid-teens), kill tests; each mode-22 PID flipped verified/unverified with raw response
- [ ] **Milestone: live coolant + boost + trans temp on the dash mount, engine running**

## Sprint 4 — Telemetry & Hardening

- [ ] OBD-28: Room drive logging + fused location, bounded storage — telemetry (Sonnet)
- [ ] OBD-29: post-drive charts vs elevation profile — telemetry
- [ ] OBD-30: CSV + GPX export via SAF — telemetry
- [ ] OBD-31: threshold alerts with hysteresis — ui-agent
- [ ] OBD-32: preset profiles ("Loaded Revel — summer" default) — ui-agent
- [ ] OBD-33: chaos & battery soak — scripted portion buildable; 🖐 overnight measurement needs hardware
- [ ] OBD-34: Baseline Profile, splash, permission onboarding — ui-agent

---

## Open decisions

| # | Decision | Owner | Status |
|---|---|---|---|
| D1 | Library vs custom ELM327 layer (OBD-9) | Panel → orchestrator → 🖐 Taras | ✅ custom + vendored kotlin-obd-api scaling tables (Taras-approved 2026-08-09) |
| D2 | Hilt vs Koin | orchestrator, logged in DECISIONS.md | ✅ Hilt (2026-08-09) — compile-time graph validation is the only enforcement an agent-driven repo has |
| D3 | Publish to GitHub | 🖐 Taras, per-action | ⬜ deferred |

## Standing rules (validate every wave)

1. Local `main` merges pre-authorized (this project only). Any `git push`: per-action OK from Taras.
2. Model matrix + budget table: workflow doc §10. Escalations spend from the wave pool.
3. 🖐 items are Taras's — never auto-closed, never worked around.
4. Reconcile this file ↔ repo state at wave start AND wave end; update "Last updated" line every edit.

## Budget ledger

| Wave | Target | Actual | Escalations | Notes |
|---|---|---|---|---|
| Sprint 0 | +200k | ~230k (est.) | 0 | Calibration wave. 3 Sonnet agents (scaffold, issues, fakes) + Fable orchestrator; no per-agent token metering available — estimate. ~15% over target; issue-file generation (39 files) was the unbudgeted chunk. |
| Sprint 1 | +400k | ~1.0M | 0 tier / 1 arb | 2.5× over — wind-down invoked after ui-agent alone burned 352k (measured). Measured: ui 352k, fix-round 176k, reviews 68.5k+72.5k+104.5k = 774k; panel+rebuttals+rev-arch+orchestrator est. ~230k. Lessons for recalibration: (a) a Compose module with screenshot-test infra setup is a 350k task, not 60k — budget UI-bootstrap waves accordingly or split infra-setup from feature work; (b) a rigorous 2-round Opus review cycle costs ~250k per branch — the +400k table row cannot fund 3 UI branches + a panel; (c) review quality was worth it: 1 blocker + 9 majors were real. Table needs recalibration before Sprint 2. |
