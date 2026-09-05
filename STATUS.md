# STATUS — Sprinter OBD Gauge App

> **The single source of truth for what's done and what's next.** The orchestrator updates this file at every wave start and wave end, and reconciles it against ground truth (`grep status: issues/`, `git branch -a`, `git log main`) before spawning any agent. A mismatch between this file and the repo is a process bug — fix the mismatch before doing new work. Agents get the relevant slice of this file in their brief.
>
> Lives at `~/projects/sprinter-obd-gauge/STATUS.md` — already the future repo root; Sprint 0 runs `git init` here and thereafter updates land as status commits.

**Last updated:** 2026-08-25 (**OBD-73 "dumb mode" face gauges — MERGED to main `39e178a`, gate GREEN pre+post, Pixel device-approved.** New `GaugeRenderStyle.FACE` (4th style chip): temp gauges show a hand-drawn MOOD face (happy→concerned→sad→crying from threshold bands) that **flushes yellow→red and sweats** continuously with a `faceHeat` ramp (Taras's friend's idea); **boost** shows an EXCITEMENT face whose **bug-eyes grow** (1.0×→2.3×) with boost. Canvas-drawn (NOT emoji — Garmin API-23 tofu risk), scales with the tile. Pure mappings (`FaceExpression.kt`) unit-tested (`FaceExpressionTest`/`BoostFaceShapeTest`, ~20 cases); 6 new Roborazzi refs (cool/warm/hot/danger + boost low/high). FACE offered only for temp gauges + boost. Codec unchanged (unknown token → DIGITAL). Reviewed+approved (`reviews/OBD-73-round1.md`). Taras: "haha that looks amazing!" / "all looks good." One unchecked hardware item: Garmin API-23 face render (next Garmin build; non-gating). main-channel APK `app-main-39e178a`. **NOT yet pushed to GitHub** (origin/main at `c3f18f1`, main 1 ahead). **Prior (same day):** OBD-72 selectable gauge styles + OBD-77 expand-in-place editor — MERGED to main `86e3417` (OBD-77 status flip `7b02723`), gate GREEN pre+post, both device-verified by Taras on the Garmin Overlander (prod) AND the Pixel ("Looks excellent on garmin" / "excellent. I like that look").** One feature branch `feat/72-gauge-styles` carried both issues; squash-merged via `merge.sh` (OBD-72 driving, staleness-dance bumped both reviews' `reviewed-commit`→`aa56851`). **What shipped:** (1) selectable per-gauge render styles — analog **needle** dial + LED **bar-arc** alongside digital, per-gauge scale model, style picker, backward-compatible persistence, threshold-coloring-sacred-across-styles (OBD-72); (2) needle readout relocated into the dial's open bottom gap with its own value scale (no clip/overlap at 1×1); (3) per-gauge history **sparklines removed** entirely (4Hz buffer + render plumbing + package + tests swept, `grep sparkline app/src`→0); (4) all editor controls scale with the tile; (5) **expand-in-place editor** (OBD-77) — the ⚙ badge lifts the editor OUT of the tile into a dashboard-level floating card that grows from the tile's own rect over a scrim, two-column STYLE|THRESHOLD in landscape, collapses on Done/back/tap-outside; ⇄ swap carousel untouched. Both reviewed+approved (`reviews/OBD-72-round1.md`, `OBD-77-round1.md`). Garmin **prod** test APK served (`builds/serve/obdgauge-GARMIN-api23-OBD72+77-gaugestyles-75ca80a.apk`, minSdk 23, throwaway branch `garmin-72-test`); main-channel demo APK rebuilt `app-main-7b02723`. **NOT pushed to GitHub** — awaiting Taras per-action OK. **NEXT: OBD-71 battery-drain fix still awaiting Taras's overnight device-verify** (separate branch `feat/71-overnight-reconnect`, Garmin prod build `dd7f9ee` served). **Prior (2026-08-24):** OBD-70 PID data logging — MERGED to main `d173412`, Pixel API 34 device-verified.** 1 Hz wide-CSV of the full mapped PID set, Record button by the ⚙ → confirm → CSV in `getExternalFilesDir("logs")` + `index.json` → Recordings screen (list + FileProvider share + delete). **Two device-only bugs found on hardware that 748 tests + 4 reviews missed, both fixed + verified on Pixel:** (1) crash — `SessionIndex` regex `OBJECT_PATTERN` had a bare `}`, legal on OpenJDK/Robolectric (host-JVM) but rejected by Android's ICU engine → `ExceptionInInitializerError` on every Record tap → escaped (840db1e); (2) CSV unit-labels — legend used SI (`PidDefinition.unit`) while values are display-converted → legend now mirrors `DisplayUnitDataSource` (`GAUGE_CATALOG_BY_ID[id]?.unit ?: pid.unit`) + float rounding (4f9cbef). Review chain r1→r4 all approved; hardware checklist in `issues/OBD-70.md`. **LESSON: JVM-vs-Android regex + host-JVM Robolectric are blind to Android-runtime-only bugs — real-device verify is the only gate (see task #9: harden the regex JSON codec).** Garmin prod APK rebuilt on new main: `garmin-overlander-api23` rebased (`4675bf0`, OBD-67+69+70, minSdk 23), served `builds/serve/obdgauge-GARMIN-api23-OBD70-logging-4675bf0.apk`; main-channel demo APK rebuilt. Temp branches retired. **NOT pushed to GitHub** — main is ~11 commits ahead of origin (OBD-70 merge + OBD-71/72/73/74 specs + research docs), awaiting Taras per-action OK. **NEXT: attack the battery drain (OBD-71) — now the priority.** Taras 2026-08-24: drain is OVERNIGHT/backgrounded (screen off), + ~10 min after ignition-off the Connect button won't reconnect (only quit+relaunch does) — ONE bug. Code-grounded hypothesis (`issues/OBD-71.md` Update): `BleObdLink.kt:427` keeps the link `Ready` on read-timeouts ('silent ECU is normal') → parked-with-dongle-present the link stays Ready + poll loop spins on timeouts all night = drain (a DIFFERENT path than the no-dongle case OBD-69 was verified against — why it passed but still drains), and the stale-`Ready` zombie makes Connect no-op (only fresh process recovers) = the wedge. Needs device repro to confirm, then fix (distinguish brief-silent-ECU vs dongle-gone → drop link; Connect forces fresh teardown; idle-stop tears down the LINK too). OBD-74 (quit-on-disconnect prompt) does NOT fix this (backgrounded). **Prior:** OBD-69 idle battery-saver MERGED `a3977a2`; OBD-67 rearrange grid MERGED `3cc2b3a`.

**OBD-67 MERGED to main `3cc2b3a` (2026-08-16) + PUSHED to GitHub (`4fc5266`) — FREEFORM REARRANGE GRID.** Long-press → jiggle rearrange mode: freeform drag-to-move (drop-to-place / same-size-swap / snap-back), per-tile × / ⇄ / ⚙ badges, top-bar "＋ Add", chip-resize that pushes neighbors + shift-left on right-column overflow, INDEPENDENT PER-ORIENTATION layouts (`gridLayoutsByColumns`). 13 device-driven fix rounds; Taras device-approved; clean van prod-debug on the Pixel; garmin-overlander-api23 rebased on main+OBD-67 (`f49040f`) & pushed. Prior: OBD-60 trans temp (byte 11, raw−50); GPS speed (OBD-61/61b); boost est. OBD-68 (drag-to-resize) still deferred, lower value now chip-resize pushes/shifts.

---

## Where we are

| | |
|---|---|
| Current phase | **SPRINT 3 DONE + oil temp live (OBD-50) + TRANS TEMP IDENTIFIED (OBD-60)** — app reads real coolant/RPM/oil/trans/load. 4 solid tiles + boost est. Remaining: Sprint 4, backlog |
| Repo | Gate green @ `299f15b`; develop synced; no open branches. VAN BUILD: builds/van/app-prod-debug.apk @ 299f15b (trans temp LIVE, byte-11 raw−50) — installed on the Pixel. Main-channel APK @ 299f15b in builds/main/ |
| Blockers | None. **GAUGE DASHBOARD OVERHAUL COMPLETE (main 2a27063, all Taras-approved on the Pixel):** (1) resizable spanning grid — >4 gauges, add/remove/resize-from-menu, side-by-side, clean default (OBD-62/63/64); (2) in-tile swipe swap-carousel — 75% cards + peek + zoom/pop, stable-ribbon ordering (open centered on current gauge), ghost-free select-pop (OBD-65); (3) per-gauge threshold editor — gear→3D-flip→yellow/red squares+ +/− stepper, persists to thresholdOverrides; pulsing red danger zone; seeded w/ researched defaults coolant 215/225 · trans 215/240 · oil 245/260 °F (OBD-66). **DEFERRED**: gesture phases (drag-to-move / drag-edge-resize). 🖐 OPEN — BOOST/MAP MAY NOT BE DEAD (research 2026-08-15, reverses OBD-52): ScanGauge officially reads Sprinter 3.0 boost via **mode-22 DID `20C4` at header 7E0 in the DEFAULT session** (a multi-value BLOCK: boost/MAP, rail P, DPF ΔP, exhaust back-P, EGTs). Our earlier `7F 22 31` was in the EXTENDED session (10 03) — DID is session-gated; wrong session, not wrong DID. `20C4` boost word ≈ kPa-abs, PSI-abs = raw×0.145. Our 2280xx failures = 2019+ W907/OM651 DIDs (wrong ECU), expected. **READY-TO-FIRE probe**: diagnostic capture hook saved at scratchpad/boost20c4/probe-20c4.patch (adds captureBoostBlock to RealVehicleDataSource: fires 2220C4 + canary 2220AC + 20A6/2087/202B in default session, logs raw block + rpm/maf/baro each cycle). Next van session (engine on/idle enough for first answer): apply patch → build prod-debug → install → adb logcat over WiFi → blip throttle → the byte that climbs is boost; save block for decoding into gauges (rail P, DPF, EGT, charge-air temp all bonus). Canary `2220AC` (oil temp) proves the 20xx family is served. If 20C4 still `7F 31` in default session → cal variant → aftermarket sensor. (b) verify trans label: research says OM642 V6 = **722.6/NAG1 5-speed**, not 722.9 (code KDoc says 722.9; decode unaffected). ⚠️ DEMO flavor shares prod appId → installing demo CLOBBERS the van build; reinstall builds/van/app-prod-debug.apk after. Device screencap needs the phone UNLOCKED (lock screen blocks it); screen re-locks fast. |
| Next action | Nothing pending. **Trans temp CONFIRMED** (OBD-60, record 21 30 byte 11, °C = raw − 50) across two independent drives — warm-restart (2495 samp) + true cold-start (5749 samp, 2026-08-14): max −49 °C load-decoupling from coolant kills the echo hypothesis dead; mechanism = coolant-warmed ATF heat exchanger (idle-coupled, load-decoupled). **GPS speed tile shipped** (OBD-61 + 61b): swap-in Speed gauge, auto-learns the tire-size correction factor from phone GPS, applies to displayed mph; 61b fixed a maxSdkVersion=30 cap that would've disabled it on Android 12+. Boost still `verified=false` (no independent MAP source on this van; high-load inputs healthy — MAF 152 g/s). Van APK @ 436cd19 installed. Sprint 4 available on request. |

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
| Maintenance — OBD-79 (foundation + manual tracker) | +600k | ✅ MERGED 2026-09-04 `5d83ad5` | ~1 build+2 review rounds | 1 merged / 0 rolled |
| Reliability — OBD-82 (Connect restarts service) | +250k | ✅ built+approved 2026-09-04, ⛔ awaiting Taras van-verify | ~1 build+1 review | 0 merged (in-review) |
| UX — OBD-83 (Immersive mode nav-bar toggle) | +250k | ✅ built+approved 2026-09-04 `aebee9a` (r2, +Settings/Recordings padding), ⛔ awaiting device-verify (Pixel+Garmin) | ~1 build+1 review | 0 merged (in-review) |

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
