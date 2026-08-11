# Scrum Backlog — Sprinter OBD Gauge App

**Epic OBD-0: Van Telemetry Gauge App**
As a Sprinter Revel owner, I want a native Android app that reads live engine data (coolant, transmission, and oil temps, plus altitude-true boost) from a BLE OBD dongle and displays it as clear dash gauges with logging, so I can monitor a heavily loaded van across big elevation and temperature swings without bolt-on hardware limits.

Epic-level Definition of Done: prod-flavor app connects to the Veepeak BLE+ in the van, shows live verified gauges with threshold coloring, survives key-off/reconnect cycles unattended, logs drives to exportable files, and every mode-22 PID is either hardware-verified or explicitly flagged unverified in the UI.

Team: `orchestrator`, `ui-agent`, `protocol-agent`, `ble-agent`, `reviewer-agent`, plus a 3-agent research panel (Sprint 1 only) and `telemetry-agent` (Sprint 4). Human (Taras) owns hardware verification stories.

Story points: 1 / 2 / 3 / 5 / 8 (Fibonacci, relative effort).

---

## Sprint 0 — "Skeleton & Contracts" (orchestrator solo; everything else blocks on this)

Sprint goal: a compiling multi-module repo with frozen contracts and working fakes, plus the collaboration rails (issues, labels, CI, branch protection) from the Teamwork doc.

| ID | Story | Assignee | Pts | Acceptance criteria (abridged) |
|----|-------|----------|-----|-------------------------------|
| OBD-1 | Repo + workflow rails: labels, issue/PR templates, branch protection, CODEOWNERS, CI skeleton | orchestrator | 3 | `gh` queries from Teamwork doc §2/§8 all function; a dummy PR is blocked until checks pass |
| OBD-2 | Multi-module Gradle scaffold (`:app`, `:core:model`, `:core:protocol`, `:core:ble`, `:core:testing`), version catalog, Kotlin 2.1+, Compose BOM, targetSdk 36 | orchestrator | 3 | `assembleDebug` green in CI; module dependency graph matches plan (no illegal edges) |
| OBD-3 | Phase-0 contracts in `:core:model`: `ObdLink`, `VehicleDataSource`, `PidDefinition`, `Reading`, `LinkState` | orchestrator | 2 | KDoc on every member; `DECISIONS.md` records freeze; compiles with zero implementations |
| OBD-4 | `FakeVehicleDataSource` with scenario scripts: IDLE, TOWN_HEAT_SOAK, GRADE_CLIMB, DISCONNECT_RECONNECT | orchestrator | 3 | Each scenario emits deterministic, replayable value streams; unit test asserts sequences |
| OBD-5 | `FakeObdLink` transcript replayer with injectable latency, garbage frames, timeouts | orchestrator | 3 | Replays a synthetic init+PID transcript; fault injection covered by tests |

Sprint 0 velocity: 14 pts.

---

## Sprint 1 — "Decision & Demo Dashboard" (panel + ui-agent in parallel)

Sprint goal: the library-vs-custom decision is made and logged; a demo-flavor dashboard runs on a phone against fake data.

| ID | Story | Assignee | Pts | Acceptance criteria (abridged) |
|----|-------|----------|-----|-------------------------------|
| OBD-6 | Research position: adopt existing OBD library (obd-java-api / kotlin-obd-api / maintained forks) | panel-R1 | 2 | One-page position citing repo code; covers API shape, mode-22 support, license, maintenance |
| OBD-7 | Research position: custom ELM327 layer scoped to this app | panel-R2 | 2 | Honest LOC + test-burden estimate; enumerates exactly what must be built |
| OBD-8 | Constraints analysis: mode-22 + custom headers, BLE transport fit, half-duplex/coroutines, maintenance risk | panel-R3 | 3 | Both options scored on the 40/25/15/10/10 rubric from the build plan |
| OBD-9 | Debate round + decision PR to `DECISIONS.md` | orchestrator | 2 | Rebuttals on record as issue comments; decision merged with rationale; human-reviewed |
| OBD-10 | Gauge dashboard v1: numeric tiles (coolant/trans/oil/boost), threshold coloring from config, dark theme, landscape-first | ui-agent | 5 | Screenshot tests both orientations; TOWN_HEAT_SOAK renders amber/red correctly per thresholds |
| OBD-11 | Connection state banner + stale-data treatment driven by `LinkState`/`Reading.stale` | ui-agent | 2 | DISCONNECT_RECONNECT scenario walks banner through all states in a Compose test |
| OBD-12 | Demo build flavor wired to `FakeVehicleDataSource`; installable APK artifact from CI | ui-agent | 2 | `assembleDemo` produces APK in CI artifacts; app runs with no Bluetooth permissions granted |

Sprint 1 velocity: 18 pts.
**Sprint 1 review demo:** phone running the dashboard through a scripted grade climb — first visible milestone.

---

## Sprint 2 — "Protocol & Pipe" (protocol-agent ∥ ble-agent; ui-agent continues)

Sprint goal: the protocol layer fully works over fakes; the BLE link connects to real hardware via the debug console.

| ID | Story | Assignee | Pts | Acceptance criteria (abridged) |
|----|-------|----------|-----|-------------------------------|
| OBD-13 | ELM327 init state machine (ATZ→ATE0→ATL0→ATS0→ATSP0→0100 verify) with typed failures | protocol-agent | 3 | Happy path + each failure mode tested against `FakeObdLink` |
| OBD-14 | Standard PID registry + parser: coolant 0105, RPM 010C, MAP 010B, baro 0133, IAT 010F, speed 010D | protocol-agent | 3 | Parser survives NO DATA / SEARCHING / STOPPED / garbage (property test: never throws); scaling verified per SAE formulas |
| OBD-15 | Mode-22 Mercedes PID support: header control (ATSH/ATCRA), request framing, byte extraction, MTH-style scaling; trans-temp definition from proven X-Gauge code | protocol-agent | 5 | Trans-temp request/parse verified against synthetic transcript; PIDs carry `unverified` flag surfaced to UI |
| OBD-16 | Computed boost channel: MAP − live baro, with poll priorities (FAST/SLOW scheduler) | protocol-agent | 3 | Boost correct across three baro fixtures (sea level / 6k ft / 10k ft); scheduler ordering tested |
| OBD-17 | BLE permissions + scanner: Android 12+ runtime flow, filtered scan, remembered-device fast path | ble-agent | 3 | State machine unit-tested; denial paths emit typed `LinkState.Error` |
| OBD-18 | GATT serial bridge: service/characteristic probe (candidate UUID list + writable/notifiable fallback), CCCD, MTU 512, notification reassembly to `>`-terminated responses, single-flight mutex | ble-agent | 5 | Reassembly + timeout logic unit-tested via GATT test double; fragmented-response fixture passes |
| OBD-19 | Debug console screen (debug builds): raw AT command REPL over the live link | ble-agent | 2 | Human can type ATZ and see the dongle banner — this story is the Sprint 2 hardware demo |
| OBD-20 | Sparkline strip charts (5-min rolling) per gauge | ui-agent | 3 | Renders from fake scenarios; no jank at 4 Hz updates (frame timing test) |
| OBD-21 | Settings screen: gauge selection/order, thresholds, units °F/°C + PSI/kPa, keep-screen-on, poll rate | ui-agent | 3 | Settings persist (DataStore); threshold edits recolor dashboard live in test |

Sprint 2 velocity: 30 pts (three agents in parallel).
**Sprint 2 review demo:** debug console talking to the actual Veepeak in the van; dashboard demo with charts + settings.

---

## Sprint 3 — "Real Van" (fixture capture → integration; heavy human-verify sprint)

Sprint goal: the prod flavor shows live, verified gauges from the actual van and survives real-world disconnect chaos.

| ID | Story | Assignee | Pts | Acceptance criteria (abridged) |
|----|-------|----------|-----|-------------------------------|
| OBD-22 | Capture real fixtures via debug console: init banner, standard PIDs at idle, mode-22 trans-temp exchange, UUID map, broken/unplug capture | protocol-agent + human | 2 | Fixtures committed to `:core:testing`; all Sprint-2 protocol tests re-run green on real transcripts (`hardware-verify`) |
| OBD-23 | Reconnect state machine: exponential backoff, resume-on-found, key-off recovery | ble-agent | 5 | State machine fully unit-tested; soak script survives 50 scripted disconnect cycles |
| OBD-24 | Foreground service (`connectedDevice` type): connection + poll loop with screen off, persistent notification with headline reading | ble-agent | 3 | Poll continues 10 min screen-off on device; notification updates; Doze behavior documented |
| OBD-25 | Prod-flavor DI wiring: real `ObdLink` → real `VehicleDataSource` → ViewModels; demo flavor untouched | integration (orchestrator) | 3 | JVM end-to-end test: real protocol over real fixtures into asserted UI state |
| OBD-26 | In-van bring-up checklist executed: ignition-on reads, idle sanity (boost ≈0, RPM ~780), cold-soak temp convergence, drive test (boost mid-teens, healthy temp pattern), kill tests | human + integration | 3 | Checklist comment on issue with observed values; each mode-22 PID flipped verified or logged unverified with raw response (`hardware-verify`) |
| OBD-27 | Unverified-PID UX: badge + raw-response viewer so a failed PID informs instead of lying | ui-agent | 2 | Unverified gauge visually distinct; tapping shows last raw frame |

Sprint 3 velocity: 18 pts.
**Sprint 3 review demo:** live coolant, boost, and trans temp on the dash mount, engine running — the app is real now.

---

## Sprint 4 — "Telemetry & Hardening" (telemetry-agent joins; parallel again)

Sprint goal: the features no off-the-shelf gauge has, plus production robustness.

| ID | Story | Assignee | Pts | Acceptance criteria (abridged) |
|----|-------|----------|-----|-------------------------------|
| OBD-28 | Drive logging: Room-backed recording of all readings + fused location/elevation at coarse cadence | telemetry-agent | 5 | A logged drive replays into the chart screen; storage bounded (auto-prune policy tested) |
| OBD-29 | Post-drive charts: per-gauge timelines against elevation profile ("what did coolant do on that climb") | telemetry-agent | 5 | Renders a real logged climb; correlated cursor across gauges/elevation |
| OBD-30 | Export: CSV + GPX via Storage Access Framework | telemetry-agent | 2 | Exported CSV round-trips into a spreadsheet; GPX opens in a maps app |
| OBD-31 | Audible/notification alerts on thresholds (e.g., EOT crossing 235) with hysteresis | ui-agent | 3 | Alert fires once per excursion, not per sample; test covers hysteresis |
| OBD-32 | Threshold preset profiles, "Loaded Revel — summer" as shipped default | ui-agent | 2 | Preset applies the worry-threshold table; user overrides persist separately |
| OBD-33 | Chaos & battery soak: scripted disconnect storms, overnight foreground-service battery measurement, StrictMode/ANR audit | ble-agent | 3 | Soak report committed; zero ANRs; battery drain documented with numbers |
| OBD-34 | Startup polish: Baseline Profile, splash API, permission-education onboarding | ui-agent | 3 | Cold start measurably improved; onboarding explains BLE permissions before the system dialog |

Sprint 4 velocity: 23 pts.
**Sprint 4 review demo:** a real logged mountain drive charted against elevation, exported to CSV.

---

## Backlog (unscheduled, post-epic candidates)

| ID | Story | Notes |
|----|-------|-------|
| OBD-35 | Oil-temp mode-22 PID discovery for the 5-speed OM642 | Known-unknown: no proven code for this drivetrain; needs experimentation via debug console |
| OBD-36 | Named-climb recognition ("Eisenhower approach") comparing current vs historical thermal runs | Builds on OBD-28/29 data |
| OBD-37 | OBDLink LX/MX+ support (Classic BT transport variant) | Second `ObdLink` implementation; contracts already allow it |
| OBD-38 | Fuel-burn vs terrain correlation | Needs fuel-rate PID validation on OM642 |
| OBD-39 | Android Auto / dashboard-mode display | Investigate app-category eligibility first |
| OBD-40 | User-defined PID gauges (add gauges in-app: X-Gauge-style code entry → live tile, unverified-by-default) | Requested 2026-08-10; issue filed. Depends on OBD-15/21/27 |
| OBD-41 | Live PID discovery session → committed OM642 code database (Claude drives the link, Taras supplies ground truth; read-only probing) | Requested 2026-08-10; issue filed. Depends on OBD-18/19; feeds OBD-40's picker and the protocol registry |
| OBD-42 | In-place gauge swap: long-press → gauge sinks into its frame, carousel of verified mini gauge-cards inside the tile, tap to swap | Requested 2026-08-11; ui-agent wave started same day |
| OBD-43 | Standard PIDs 0104 engine load + 0111 TPS for the swap catalog | Companion to OBD-42; tiny protocol-agent task |

---

## Ceremonies (mapped to agent reality)

- **Sprint planning:** orchestrator files the sprint's issues with full templates, assigns labels/milestone, posts a planning summary comment on the epic issue.
- **Daily standup:** orchestrator sweep — open PRs, `blocked` label, burndown from milestone — posted as a comment thread, not a meeting.
- **Review:** each sprint's demo criteria above; demo-flavor APK or in-van evidence attached to the milestone.
- **Retro:** one issue per sprint titled `Retro: Sprint N`; every agent posts what slowed it down (contract friction, fixture gaps, CI flakes); orchestrator turns concrete items into Sprint-N+1 stories.
- **Velocity is informational, not a quota** — agent throughput varies with task parallelism, and Sprint 3 is human-gated regardless of points.
