# STATUS — Sprinter OBD Gauge App

> **The single source of truth for what's done and what's next.** The orchestrator updates this file at every wave start and wave end, and reconciles it against ground truth (`grep status: issues/`, `git branch -a`, `git log main`) before spawning any agent. A mismatch between this file and the repo is a process bug — fix the mismatch before doing new work. Agents get the relevant slice of this file in their brief.
>
> Lives at `~/projects/sprinter-obd-gauge/STATUS.md` — already the future repo root; Sprint 0 runs `git init` here and thereafter updates land as status commits.

**Last updated:** 2026-08-12 (OBD-25 MERGED — SPRINT 3 SOFTWARE 100% COMPLETE. The van build exists.) — Sprints 0-2 complete + ad-hoc OBD-42/44/45: **39 merged / 55 issues** (OBD-44+46+47 PROMOTED to main ca896b5 — Taras feel-verdict accepted). Full stack: contracts, fakes, dashboard+banner+sparklines+settings+shrink-picker, BLE link+console, protocol layer w/ solved trans-temp decode, dual-channel build process live. No open branches. 🖐 Taras: feel-test the dev build (`builds/dev/app-dev-debug.apk`, installs beside master as "OBD Gauge Dev"); promotion develop→main on his accept.

---

## Where we are

| | |
|---|---|
| Current phase | **SPRINT 3 DONE + oil temp live (OBD-50)** — app reads real coolant/RPM/oil/load the moment it meets the van. Remaining: 🖐 van session (boost probe, trans snapshot, live demo), Sprint 4, backlog |
| Repo | Gate green @ `58c0f8e`; develop synced; no open branches. VAN BUILD: builds/van/app-prod-debug.apk (prod DI: BleObdLink → RealVehicleDataSource → DisplayUnitDataSource seam → dashboard; restart-on-Ready ownership; connect UX + runtime perms). Demo/master APK @ 58c0f8e in builds/main/ |
| Blockers | None in software. 🖐 Taras: (1) cold-start capture (trans verify + id swap), (2) next van session device checks: fresh-install FGS start, 10-min screen-off polling |
| Next action | 🖐 Next van session: LOGGED cold-start→warmup→drive (screen-on/WiFi) recording the FULL 21 30 record + coolant every few sec → rigorously re-identify the real trans-temp byte (OBD-51 reframed) OR conclude it's not cleanly available. Same drive calibrates boost VE (OBD-57). Van APK current @ 19612fa (trans blanked, honest). |

## Wave board

| Wave | Budget | Status | Actual spend | Merged / rolled |
|---|---|---|---|---|
| Sprint 0 — Skeleton & Contracts | +200k | ✅ done 2026-08-09 | ~230k (est.) | 5 merged / 0 rolled |
| Sprint 1 — Decision & Demo Dashboard | +400k | 🔄 partial 2026-08-09 (wound down over budget) | ~1.0M | 4 merged + 1 🖐 / OBD-11+12 rolled |
| Sprint 1b — OBD-11+12 (batched, Tier B) | +350k | ✅ done 2026-08-09 | ~390k | 2 merged / 0 rolled |
| Sprint 2a — Protocol (Tier A, 2 batched branches) | +900k | ✅ done 2026-08-11 | ~1.1M | 4 merged / 0 rolled |
| Sprint 2b — BLE (Tier A, 17+18 batched; 19 solo) | +900k | 🔄 17+18 merged 2026-08-10; OBD-19 rolled to 2b-2 | ~1.15M | 2 merged / OBD-19 rolled |
| Sprint 2c — UI charts/settings (Tier B, batched) | +400k | ✅ done 2026-08-11 | ~1.0M | 2 merged / 0 rolled |
| Ad-hoc — OBD-42 swap carousel | ~600k est | ✅ done 2026-08-11 | ~915k | 1 merged / 0 rolled |
| Ad-hoc — OBD-44 shrink animation + OBD-45 dual-channel builds | ~500k (orchestrator-set) | ✅ done 2026-08-11 — OBD-45 → main, OBD-44 → develop (promotion = Taras feel verdict) | ~930k | 2 merged / 0 rolled |
| Sprint 3 — Real Van (software half) | +900k / +400k / +300k | ✅ COMPLETE 2026-08-12: 43+49, 23+48, 24+27, 25 → 58c0f8e. 1031+ tests. | ~1.5M + ~290k + ~500k | 7 merged |
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
- [x] OBD-11: connection banner (all LinkState treatments, DISCONNECT_RECONNECT walk test) + stale-clock fix w/ regression coverage — merged 0fc2024 (Sprint 1b)
- [x] OBD-12: demo/prod flavors; :core:testing fenced off prod classpath (dex-verified); demo APK at app/build/outputs/apk/demo/debug/app-demo-debug.apk — merged 0fc2024 (Sprint 1b)
- [x] **Milestone: demo APK on the Pixel running a scripted grade climb** — installed + verified live on the Pixel 5 (dev phone) 2026-08-10: both orientations, trans-amber threshold crossing, boost arc sweeping, rotation preserves state

## Sprint 2a — Protocol (Opus)

- [x] OBD-13: init state machine w/ cold-dongle ATZ retry policy, every failure typed+tested — merged d0c05e0
- [x] OBD-14: 6 standard PIDs; SAE scalings independently re-derived; vendoring caught kotlin-obd-api quarter-rpm truncation bug; per-line-first framing (review MAJOR fixed pre-merge); 4500-case property test — merged d0c05e0
- [x] OBD-15: X-Gauge MTH decode SOLVED algebraically (raw = °C−50, Mercedes convention; coolant code as control case; confirmed by exact-arithmetic re-derivation, 0/256 mismatches); header set/restore discipline w/ intra-cycle retry; unverified via PidCatalog — merged fcf9e5e. 🖐 Residual: display-unit assumption + RXF ambiguity + 21 30 vs 22 05 43 hypothesis — OBD-22 settles all three on hardware
- [x] OBD-16: RealVehicleDataSource — FAST/SLOW scheduler, altitude-true boost (101/81/69 kPa + vacuum), pinned-contract lifecycle incl. multi-threaded join probe — merged fcf9e5e
- [x] Parser+scheduler coverage >90% (ad-hoc JaCoCo: parser 100%, scheduler 97.6%, mode-22 98.9%)

## Sprint 2b — BLE (Opus)

- [x] OBD-17: permission flow (12+ split, location-services gate), 2-pass filtered scanner + throttle budget, remembered-device fast path w/ corruption-proof store — merged d838cc7
- [x] OBD-18: GATT serial bridge — 6-candidate UUID probe + fallback, CCCD, MTU 512, exhaustive-split reassembly, single-flight, generation-gated session lifecycle, debt bookkeeping w/ quiet-window expiry — merged d838cc7, 135 tests
- [x] OBD-19: debug console merged (6dcc740) — "OBD Console" launcher entry in debug builds only, ConsoleSession tested against FakeObdLink, release classpath/manifest/dex verified clean. 🖐 Hardware verification = the Sprint-2 demo: type ATZ at the real Veepeak
- [x] Reassembly/scan/session logic extracted pure & tested against GATT doubles (reconnect state machine itself is OBD-23)

## Sprint 2c — UI round 2 (Sonnet)

- [x] OBD-20: 5-min sparklines — pure ring buffer + downsampler, per-gauge StateFlows (recomposition-isolation mutation-tested), gap breaks — merged cc35306; true frame timing deferred to OBD-34 device macrobenchmark (documented)
- [x] OBD-21: settings — gauge order/visibility, thresholds (natural-unit storage, drift-proof toggles through 4 review rounds), units, keep-screen-on, poll rate; DataStore w/ corruption handler; live recolor end-to-end — merged cc35306

## Ad-hoc / backlog (feature requests filed after Sprint 2)

- [x] OBD-42: in-place gauge swap — long-press carousel picker — merged 71ab896
- [x] OBD-45: dual-channel builds — develop branch + dev/test APK alongside master — merged ecbafa9
- [x] OBD-44: picker-entry shrink animation — merged to **`develop`** be735ac (first D5 dev-channel feature). 2-round Tier-B review: round-1 BLOCKER (anisotropic squash → illegible settled card, screenshot ref had blessed it) + 3 MAJORs (all vacuous/unpinned tests, mutation-proven) fixed + mutation-verified. 🖐 Taras feel verdict = promotion gate
- [ ] OBD-43: standard PIDs — engine load (0104) + throttle position (0111) — protocol-agent, backlog
- [ ] OBD-40: user-defined PID gauges (add gauges from inside the app) — ui-agent, backlog
- [ ] OBD-41: live PID discovery session → committed OM642 code database — orchestrator, backlog (🖐 needs the van)

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
| D5 | Dual-channel builds: `develop` + side-by-side dev/main APKs | Taras (process), orchestrator (side-by-side) | ✅ live 2026-08-11 (OBD-45; doc 05 §6b) |
| D6 | Small-track ad-hoc: agent builds, orchestrator reviews | Taras | ✅ 2026-08-11 (doc 05 §6c) — :app/tooling only, never Tier A |

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
| Sprint 1b | +350k | ~390k | 0 | Tier-B validated: combined-lens review found 2 real majors (incl. a mutation-proven coverage hole) at ~60% of flat-matrix cost. Build still the big line item (252k). ~11% over — trend right. |
| Sprint 2b w1 | +900k | ~1.15M | 1 arb | Tier A earned it: 2 blockers + 9 majors round 1; round 2 caught a relocated leak + unbounded debt. 17 mutations run across 3 rounds. Overrun drivers: 2 agent stalls (stream watchdog on long Gradle runs — mitigate with -q), and a redesign-grade fix round. |
| Sprint 2b-2 | +300k | ~410k | 0 | Console: clean build, approved round 1, tier boundary held (zero existing-file edits). Overrun driver: flavor×buildType source-set plumbing (~2x est. build cost — recalibration input: ANY new source-set/variant work costs ~300k, not 150k). |
| Sprint 2a | +900k | ~1.1M | 0 | Both waves: majors fixed pre-merge (truncated-frame framing; intra-cycle header restore). MTH decode solved + independently re-derived. First mutation-tested-then-rewritten test (M1 v1 didn't bite — process caught its own weak fix). ~22% over. |
| Sprint 2c | +400k | ~1.0M | 2 arb | 2.5x over: Sonnet UI build ran 422k (pattern: UI builds are the persistent hot spot — 352/252/313/422k across sprints) and the threshold-field editing UX took 4 review rounds, with the reviewer twice catching regressions introduced by fixes (round-2 clear-retype, round-3 mid-edit unit toggle). Each was a silent wrong-number path in a dash gauge — the rounds were worth it. Recalibrate: Tier-B UI waves need ~2x their row. |
| OBD-42 wave (feature request) | ~600k est | ~915k | 2 arb-assisted rounds | Long-press swap carousel. Swap correctness held from round 1 (4/4 mutations); reviews caught stale-picker-state (eaten back press), catalog-drift resurrection via forward-simulation, and a zero-tile unrecoverable state. UI-wave 2x pattern holds. |
| OBD-44/45 wave (ad-hoc pair) | ~500k (orch-set) | ~930k | 0 | infra 119k / ui build+fix 602k / Opus review 158k / orch ~50k. Review earned it again: BLOCKER (2.9:1 anisotropic squash, blessed by its own re-recorded screenshot) + 3 vacuous-test MAJORs incl. a mutation that survived every unit test and was caught only by the defective reference image. Cost postmortem → D6 small track (builder agent + orchestrator review, ~150-250k target) for future tiny asks; full builder+reviewer shape has a proven ~450k floor. |
| OBD-46 small-track (D6 first run) | 150-250k | ~200k (build 181k + orch review) | 0 | Corner-parity bug (anisotropic scale squashing radius+border — builder measured 2.9:1 border distortion and fixed beyond brief) + shrink 220→300ms. D6 shape validated: brief-prescribed git flow, orchestrator review, on-device verify. |
| OBD-47 small-track (swap-grow) | 150-250k | ~300k (build 282k + orch review incl. live mutation) | 0 | Swap-in hard cut → grow-in from tapped card's rect via cross-remount handoff registry; same spec/stack as shrink. Priciest small-track yet — remount plumbing; still ~1/3 of full track. |
| Sprint 3 fix wave | +400k | ~290k (ble 79k + app 173k + orch close-outs) | 0 | FIRST UNDER-BUDGET WAVE: builder continuations in kept worktrees + orchestrator close-outs instead of fresh reviewer spawns. Both round-1 blockers killed with layered mutation proof (ble even caught its own compile-error fake mutation). |
| OBD-25 integration wave | +300k | ~500k (build 328k — unit-mismatch discovery + keep-alive redesign; scoped Tier-A review 132k; orch arbitration) | 1 arb | Review found the exact charter risk: boost kPa→PSI seam had ZERO coverage (bypass mutation survived 372 tests) — 6-line orchestrator fix, mutation-verified. Reviewer also proved the ownership pin real (1→41 when reverted). |
| OBD-50 verified PIDs | small | ~260k (build) + orch mutation-review | 1 arb | Oil temp SOLVED (015C standard, closes OBD-35) + 6 live-verified PIDs. Scaling mutation-verified. Module-isolation violation (protocol-agent → 12 :app files) accepted as forced flag-flip cascade; root cause = verified-flag duplication → OBD-53 filed. |
