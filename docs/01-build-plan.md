# Sprinter OBD Gauge App — Agent Build Plan

Native Android (Kotlin) app that reads OBD-II data from an ELM327-compatible BLE dongle (Veepeak OBDCheck BLE+) on a Mercedes NCV3 Sprinter (OM642 V6, 722.6 5-speed) and displays live gauges: coolant temp, transmission temp, engine oil temp, and altitude-true boost (MAP − barometric). Built for maximum parallel agent work with contract-first interfaces so each agent self-tests independently before integration.

---

## Guiding constraints (apply to every agent)

- **Target modern Android.** targetSdk 36 (Android 16), minSdk 26. Kotlin 2.1+, Jetpack Compose with Material 3, coroutines + Flow everywhere, KSP (not kapt), Gradle version catalogs (`libs.versions.toml`). No deprecated APIs: no `startActivityForResult`, no `AsyncTask`, no Classic Bluetooth `BluetoothSocket` (the dongle is BLE).
- **Contract-first.** Phase 0 defines shared interfaces. Agents build against those interfaces and fakes, never against each other's in-progress code.
- **Every agent ships tests.** An agent's work is not done until its module passes its own test suite with no other agent's code present.
- **One writer per module.** No two agents edit the same Gradle module. Integration happens only in Phase 4 by the integration agent.
- **The ELM327 is half-duplex.** One command in flight at a time, responses terminated by the `>` prompt. Every design decision downstream of the transport respects this.

---

## Phase 0 — Orchestrator: scaffold and contracts (sequential, blocks everything)

One agent (or the orchestrator directly) sets up the skeleton all other agents build against.

### 0.1 Project scaffold
- Multi-module Gradle project:
  - `:app` — Compose UI, ViewModels, DI wiring
  - `:core:model` — pure Kotlin data classes, zero Android deps
  - `:core:protocol` — ELM327/OBD command layer (pure Kotlin + coroutines, no Android deps → unit-testable on JVM)
  - `:core:ble` — Android BLE transport (GATT)
  - `:core:testing` — shared fakes and fixtures (FakeObdLink, recorded transcripts)
  - `:core:logging` — (optional, Phase 5) Room persistence + CSV export
- Version catalog, Compose BOM, Hilt for DI (or Koin if the orchestrator prefers lighter weight — pick one, record the decision in `DECISIONS.md`).
- CI task: `./gradlew test` must run all JVM tests; `:app` gets a `connectedAndroidTest` lane stubbed for later.

### 0.2 The contracts (this is the critical deliverable)

Define in `:core:model` and `:core:protocol` interfaces. These are frozen after Phase 0; changing them requires an orchestrator decision logged in `DECISIONS.md`.

```kotlin
/** Raw byte pipe to the dongle. BLE agent implements this; protocol agent consumes it. */
interface ObdLink {
    val state: StateFlow<LinkState>          // Disconnected, Scanning, Connecting, Ready, Error(cause)
    suspend fun connect()
    suspend fun disconnect()
    /** Send one raw command, suspend until full response (terminated by '>') or timeout. */
    suspend fun sendRaw(command: String, timeout: Duration = 2.seconds): String
}

/** One gauge definition. Protocol agent owns the registry of these. */
data class PidDefinition(
    val id: String,                          // "coolant", "transTemp", "boost", "oilTemp", "baro", "rpm"
    val label: String,
    val unit: Unit,                          // PSI, FAHRENHEIT, RPM...
    val request: ObdRequest,                 // mode/PID or raw mode-22 frame + header
    val parse: (ByteArray) -> Double,        // scaling math
    val pollPriority: PollPriority           // FAST (boost, rpm) vs SLOW (temps)
)

/** What the UI consumes. Protocol agent implements against ObdLink; testing module fakes it. */
interface VehicleDataSource {
    val readings: StateFlow<Map<String, Reading>>   // keyed by PidDefinition.id
    val connection: StateFlow<LinkState>
    fun start(pids: List<PidDefinition>)
    fun stop()
}

data class Reading(val id: String, val value: Double, val timestamp: Instant, val stale: Boolean)
```

Derived/computed channels (boost = MAP − baro) are computed inside the `VehicleDataSource` implementation, not in the UI. The UI never does protocol math.

### 0.3 Shared fakes (in `:core:testing`)
- `FakeVehicleDataSource`: emits scripted scenarios — `IDLE`, `TOWN_HEAT_SOAK` (coolant 225 / oil 240 / trans 215 climbing), `GRADE_CLIMB` (boost sweeping 0→15, temps converging), `DISCONNECT_RECONNECT`. The UI agent builds entirely against this.
- `FakeObdLink`: replays recorded ELM327 transcripts (init sequence, `0105` coolant, `0133` baro, mode-22 responses) with configurable latency and injectable garbage/timeout frames. The protocol agent builds entirely against this.
- Fixture files: plain-text transcripts in `:core:testing/src/main/resources/transcripts/` (see Phase 3 acceptance for required set).

**Phase 0 exit criteria:** project compiles, contracts merged, fakes emit data, `DECISIONS.md` exists. Everything after this fans out.

---

## Phase 1 — Library decision panel (3 research agents + debate, parallel with Phase 2 UI work)

Decision to make: **build a custom ELM327 command layer vs adopt an existing library** (candidates: `obd-java-api` and its Kotlin forks such as `kotlin-obd-api`, plus any currently maintained alternatives the agents find).

This decision only blocks the **protocol agent**, so run the panel while the UI agent (Phase 2A) is already working.

### Panel structure
- **Agent R1 — advocate for existing library.** Research `obd-java-api` / `kotlin-obd-api` and any maintained forks: API shape, coroutine friendliness, license, last commit activity, issue tracker health, how commands/responses are modeled, test coverage.
- **Agent R2 — advocate for custom layer.** Scope what a minimal bespoke ELM327 layer actually is for THIS app (init sequence, ~6 standard PIDs, 2-3 Mercedes mode-22 PIDs, one parser, one poll loop). Estimate LOC and test burden honestly.
- **Agent R3 — constraints analyst (neutral).** Research the constraints that actually decide this case:
  1. **Mode-22 manufacturer-specific requests with custom headers** (`ATSH`/`ATCRA` per the van's TXD/RXF codes) — does the library support arbitrary headers and raw mode-22, or only standard mode-01 PIDs?
  2. **BLE transport** — libraries in this space were written for Classic Bluetooth `InputStream`/`OutputStream`. What adapter shim is needed to feed them from GATT notifications, and does that shim erase the library's value?
  3. Half-duplex sequencing, timeout handling, and whether the library fights or fits structured concurrency.
  4. Maintenance risk: is the project alive in 2026?

### Debate protocol
1. R1 and R2 each produce a one-page position (`research/library-position.md`, `research/custom-position.md`) with citations to actual repo code, not READMEs.
2. R3 produces `research/constraints.md` scoring both options against the four constraints above.
3. One structured debate round: R1 and R2 each get a rebuttal pass against the other's position, must concede or refute each constraint R3 raised.
4. Orchestrator decides using this rubric, weighted for THIS use case:
   - Mode-22 + custom header support: **40%** (the whole point of the app is the Mercedes PIDs)
   - BLE fit without a lossy adapter: **25%**
   - Coroutine/Flow ergonomics: **15%**
   - Maintenance/bus-factor: **10%**
   - Time saved on standard PIDs: **10%**
5. Decision + rationale logged in `DECISIONS.md`. Hybrid outcomes are allowed (e.g., "custom layer, but copy the library's PID formula tables under its license").

**Expected shape of the outcome (do not pre-decide, but plan for it):** the constraints analyst will likely find that existing libraries handle standard mode-01 PIDs well and mode-22-with-custom-headers poorly, and assume stream-based Classic transport. The plan below is written to work under either outcome — the protocol agent's contract (`VehicleDataSource` over `ObdLink`) doesn't change.

---

## Phase 2 — Parallel fan-out (three independent agents)

### Agent 2A — UI agent (`:app`, consumes `:core:model` + `:core:testing` only)

Builds the entire user-facing app against `FakeVehicleDataSource`. Zero Bluetooth, zero protocol knowledge. Can start the moment Phase 0 lands.

Deliverables:
- **Gauge dashboard** (Compose, Material 3, dark theme default — this runs in a van at night):
  - Large numeric tiles for coolant, trans, oil, boost; boost also gets a sweep/arc indicator (−2 to +18 PSI range).
  - Threshold coloring per gauge, driven by a config object, seeded with: coolant green <220 / amber 220-230 / red >230; trans green <200 / amber 200-240 / red >250; oil amber >235; thresholds user-editable in settings.
  - Stale-data treatment: value dims + "last seen Xs ago" when `Reading.stale`.
  - Connection state banner (scanning / connecting / connected / reconnecting) driven by the `LinkState` flow.
- **Live sparkline/strip chart** per gauge (last 5 minutes, in-memory).
- **Settings screen**: gauge selection + ordering, thresholds, units (°F/°C, PSI/kPa), keep-screen-on toggle, polling rate.
- **Landscape layout** — phone mounted sideways on a dash is the primary form factor. Edge-to-edge, insets handled, predictive back enabled.
- ViewModel layer: `StateFlow` in, Compose state out. No business logic beyond formatting.

Self-test requirements (all pass with no BLE/protocol code present):
- Compose UI tests: each scenario script (`IDLE`, `TOWN_HEAT_SOAK`, `GRADE_CLIMB`, `DISCONNECT_RECONNECT`) renders correct values, colors, and banners.
- Screenshot tests for the dashboard in both orientations.
- A `demo` build flavor that ships wired to the fake — this flavor survives to production as the app's demo mode.

### Agent 2B — Protocol agent (`:core:protocol`, consumes `:core:model` + `FakeObdLink`)

Owns everything between "raw string pipe" and "typed readings." Pure Kotlin, JVM-unit-testable, no Android imports. Starts as soon as the Phase 1 decision lands (standard-PID work can even start before, since it's decision-independent).

Deliverables:
- **Init sequence** as a state machine: `ATZ` → `ATE0` → `ATL0` → `ATS0` → `ATSP0` (auto protocol) → verify with `0100`. Each step validates the response; failures produce typed errors (`InitFailure.NoEcho`, `InitFailure.NoProtocol`...).
- **PID registry** (`PidDefinition` instances):
  - Standard mode-01: coolant `0105`, RPM `010C`, MAP `010B`, baro `0133`, intake temp `010F`, speed `010D`.
  - **Mercedes mode-22 set for the OM642/722.6** translated from the van's proven X-Gauge codes — each is (set header `ATSH`/receive filter `ATCRA` per TXD/RXF) + mode-22 request + byte extraction per RXD + scaling per MTH:
    - Trans temp: TXD `07E12130`, RXF `032200000000`, RXD `1808`, MTH `00090005FFC6`
    - Boost source (manifold pressure): TXD `07DF018670`, RXF `05418670`, RXD `3810`, scale per MTH `00910BB8____` (offset handled in software, see next bullet)
  - These are hypotheses to verify on hardware in Phase 4, not gospel — structure the registry so a PID can be marked `unverified` and surfaced as such in the UI.
- **Computed channels**: `boost = MAP_absolute − baro` (both polled; baro at SLOW priority since it only changes with elevation). This replaces every fixed-offset hack — correct at any altitude from Death Valley to Loveland Pass.
- **Polling scheduler**: single-flight sequential loop over active PIDs, FAST PIDs (boost, RPM) every cycle, SLOW PIDs (temps, baro) every Nth cycle. Structured concurrency: the whole loop is one coroutine, cancellation-safe, backpressure-free (latest-value semantics via `StateFlow`).
- **Response parser**: tolerant of multi-frame responses, `SEARCHING...`, `NO DATA`, `STOPPED`, `?`, interleaved whitespace/CR, and echo remnants. Malformed frames produce a typed error and a skipped reading, never a crash and never a poisoned value.
- If Phase 1 chose a library: this agent wraps it behind the same contracts and writes the GATT-stream adapter it needs. The deliverable list above doesn't change, only what's hand-written vs delegated.

Self-test requirements:
- JVM unit tests against `FakeObdLink` transcripts: full init happy path, every parser edge case above, timeout → retry → skip behavior, poll-priority scheduling, boost computation across three baro values (14.7 / 11.8 / 10.1 PSI equivalents).
- Property test: parser never throws on arbitrary garbage strings.
- Target: >90% line coverage on parser and scheduler.

### Agent 2C — BLE agent (`:core:ble`, consumes `:core:model` only)

Implements `ObdLink` over Android BLE/GATT. The hardest module; isolate it completely.

Deliverables:
- **Modern permission flow**: Android 12+ `BLUETOOTH_SCAN` (with `neverForLocation` flag) + `BLUETOOTH_CONNECT` runtime permissions; graceful degradation messaging surfaced through `LinkState.Error`.
- **Scanner**: filtered BLE scan (by advertised name prefix and/or service UUID), timeout, and a "remember last device address" fast path so daily reconnects skip scanning.
- **GATT serial emulation**: connect → discover services → identify the vendor serial service. Do NOT hardcode a single UUID: probe a known-candidates list (common ELM327-BLE UUIDs, e.g. FFF0/FFF1/FFF2 and FFE0/FFE1 families) and fall back to "first service with a writable + notifiable characteristic pair," logging what was found. Enable notifications (write CCCD), request MTU 512, then bridge:
  - `sendRaw()` = write command + `\r` to the write characteristic, suspend via `suspendCancellableCoroutine`, accumulate notification chunks until `>` arrives or timeout. Responses arrive fragmented across notifications — reassembly is this module's job, so the protocol layer always sees complete responses.
  - Strict single-flight enforced here too (mutex) — defense in depth with the protocol scheduler.
- **Connection state machine** with auto-reconnect: exponential backoff, resumes on device-found, emits every transition on `state`. Handles the daily van reality: key-off kills the dongle mid-poll, user walks away, comes back an hour later — the app recovers without manual intervention.
- **Foreground service** (`foregroundServiceType="connectedDevice"`, required declaration on Android 14+) hosting the connection + poll loop so gauges keep updating with the screen off or the app backgrounded. Persistent notification shows connection state + one headline reading. Respect Doze; document battery behavior.
- No protocol knowledge: this module moves strings, nothing else.

Self-test requirements:
- Unit tests for the reconnect state machine and notification-reassembly logic (both are pure logic, extract them from the GATT callbacks specifically so they're testable).
- Instrumented smoke test using a mock/emulated GATT layer (e.g., a `BluetoothGattCallback` test double) covering: fragmented response reassembly, timeout, disconnect-mid-command.
- A tiny debug console screen (debug builds only) that lets a human type raw AT commands and see responses — this becomes the primary hardware-bring-up tool in Phase 4.

---

## Phase 3 — Fixture capture (small agent or human-in-the-loop task, can overlap Phase 2)

Before integration, capture REAL transcripts from the actual van + Veepeak using any existing terminal tool (nRF Connect, Serial Bluetooth Terminal) or the 2C debug console the moment it runs:
1. Full init sequence output verbatim.
2. `0105`, `010B`, `0133`, `010C` responses at idle.
3. The mode-22 trans-temp exchange including header setup.
4. The vendor service/characteristic UUID map of the actual dongle.
5. A deliberately broken capture: unplug mid-response.

Feed these into `:core:testing` as the canonical fixtures. Every protocol test that ran on synthetic transcripts re-runs on real ones. This is cheap and de-risks the single biggest unknown (what THIS dongle actually says) before integration day.

---

## Phase 4 — Integration agent (sequential, one agent)

Only now do modules meet.

1. Wire `:core:ble` `ObdLink` → `:core:protocol` `VehicleDataSource` → `:app` ViewModels via DI. The `demo` flavor keeps the fake; the `prod` flavor gets the real chain.
2. Integration test on JVM: real protocol layer over `FakeObdLink` replaying the Phase 3 REAL fixtures end-to-end into UI state assertions.
3. **Hardware bring-up protocol** (in-van checklist the agent writes and a human executes):
   - Ignition on, engine off: connect, init completes, coolant/baro/MAP read; boost ≈ 0.
   - Engine idle: RPM ~780, boost ≈ 0 ± 0.5, temps plausible vs ambient.
   - Cold-soak check (next morning): coolant ≈ oil ≈ trans ≈ ambient → validates the mode-22 codes on hardware.
   - Drive test: boost climbs into mid-teens on a hard pull, never 30+; temps track the known-healthy pattern (oil slightly over coolant, trans below).
   - Kill tests: key off mid-drive-cycle, walk out of range, dongle unplug — app recovers on its own each time.
4. Any mode-22 PID that fails on hardware gets flagged `unverified`, logged with its raw response, and does NOT block release of the standard-PID gauges.

---

## Phase 5 — Hardening + telemetry extras (parallel again, optional agents)

- **Agent 5A — logging/telemetry**: Room-backed ride logging of all readings + GPS/elevation (fused location, coarse cadence), CSV/GPX export via `Storage Access Framework`, and a post-drive chart screen ("what did coolant do on that climb"). This is the feature layer that makes the app better than any off-the-shelf gauge for a 10k-lb rig doing CO↔CA elevation swings.
- **Agent 5B — chaos & battery**: scripted disconnect/reconnect soak tests, overnight foreground-service battery measurement, ANR/StrictMode audit, Baseline Profiles for startup.
- **Agent 5C — polish**: app icon, onboarding (permission education screens), threshold presets ("Loaded Revel summer" as the default profile), optional audible alerts ("EOT crossed 235").

---

## Orchestration mechanics

- **Dependency graph:** P0 → {P1, P2A} → P2B(needs P1) ∥ P2C ∥ P3 → P4 → P5. Maximum concurrent agents: 3-4 in Phase 2, plus the 3-agent panel if run simultaneously with 2A.
- **Handoffs are artifacts, not conversations:** contracts in `:core:model`, decisions in `DECISIONS.md`, research in `research/`, fixtures in `:core:testing`. An agent picking up work reads files, not chat history.
- **Definition of done per agent:** module compiles standalone, its test suite passes standalone, a `MODULE.md` documents its public surface and known limitations, and no TODOs on the contract boundary.
- **First runnable milestone** (worth targeting explicitly): `demo` flavor of the UI on a phone with fake data — achievable from Phase 2A alone, days in. Second milestone: live coolant on real hardware (2B + 2C + P4 step 3a). Everything else layers on a working spine.
