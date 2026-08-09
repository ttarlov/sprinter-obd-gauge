# OBD-8 — Constraints analysis (neutral, R3)

Scoring both options against the four deciding constraints from `docs/01-build-plan.md` §Phase 1, using the frozen contracts in `core/model/src/main/kotlin/com/revel/obdgauge/model/` as the integration target. I take no position on which option to pick — this is inputs for the debate round and orchestrator decision (OBD-9).

Two library candidates exist and diverge sharply on constraint 4, so I evaluated both directly against source, not README claims.

## Evidence

**pires/obd-java-api** (Java)
- Archived by owner 2017-11-10 ("read-only"); author states he's "no longer involved... don't expect feedback." 244 commits, 11 stale open issues, Apache-2.0.
- Transport: `ObdCommand.run(InputStream in, OutputStream out)` — `out.write((cmd+"\r").getBytes())`, then a blocking byte-by-byte read loop until `>`. No timeout mechanism in the base class (`src/.../commands/ObdCommand.java`).
- `protocol/ObdRawCommand.java` — a genuine raw-passthrough command: `new ObdRawCommand(command)` sends any literal string. This is the one place the library offers real flexibility for headers/mode-22, but it contributes nothing beyond what a hand-rolled `sendRaw()` call already does — it's a bypass, not a feature.
- No `ATSH`/`ATCRA`-specific command class found among the 19 files in `protocol/` (headers on/off toggle only, via `HeadersOffCommand.java`).

**eltonvs/kotlin-obd-api** (Kotlin)
- Active: commits through 2026-07-31 (9 days before today), multiple contributors beyond the original author (eltonvs, Meriemi-git, lukeduncan-scot, ojacquemart per commit history), 8 open issues, Apache-2.0.
- `ObdCommand.kt`: `rawCommand` is hardcoded as `listOf(mode, pid).joinToString(" ")` — a rigid two-field shape, not an arbitrary-string escape hatch. No `ObdRawCommand`-equivalent class exists anywhere under `command/` (checked: `at/`, `control/`, `egr/`, `engine/`, `fuel/`, `pressure/`, `temperature/` + top-level files).
- `command/at/Mutations.kt`: `SetHeadersCommand(value: Switcher)` sends `ATH0`/`ATH1` — this toggles whether header bytes are *displayed* in the response, not the CAN transmit header address. No `ATSH`/`ATCRA` class exists. A dev could subclass `ATCommand` with a literal `pid = "SH 07E1"` to fake it, but that's writing custom code against the library's internals, not using a supported feature.
- `ObdDeviceConnection.kt`: `run()` is `Mutex`-guarded (real half-duplex serialization) but **owns the transport directly** — `outputStream.write(...)`, `inputStream.available()` polling loop. It does not consume anything shaped like `ObdLink.sendRaw()`; it wants raw `InputStream`/`OutputStream`, confirmed via direct source fetch.

Both libraries are Classic-Bluetooth-socket-shaped (`InputStream`/`OutputStream`), confirmed via source, not just README.

## Scoring (0–10 per option × weight)

| Constraint | Weight | Library | Custom | Library × wt | Custom × wt |
|---|---|---|---|---|---|
| 1. Mode-22 + custom headers | 40% | 2 | 10 | 0.80 | 4.00 |
| 2. BLE transport fit | 25% | 2 | 10 | 0.50 | 2.50 |
| 3. Half-duplex/coroutine fit | 15% | 4 | 9 | 0.60 | 1.35 |
| 4. Maintenance risk | 10% | 7* | 7 | 0.70 | 0.70 |
| 5. Time saved, standard PIDs | 10% | 6 | 4 | 0.60 | 0.40 |
| **Total** | 100% | | | **3.20 / 10** | **8.95 / 10** |

\*Constraint 4's library score assumes **kotlin-obd-api** is the one adopted (the only defensible pick — see evidence above). If `obd-java-api` is chosen instead, that cell drops to ~1/10 and the library total falls to ~2.55/10.

### Rationale for the low library scores on 1–2 (65% of the rubric)
- **C1**: Neither library has a first-class primitive for "set TXD header, set RXF filter, send raw mode-22 request" — the exact shape the frozen `ObdRequest.Mode22(header, rxFilter, request)` contract (`core/model/.../ObdRequest.kt`) already models natively. `obd-java-api`'s raw escape hatch technically works but delivers zero library value for the app's core differentiator; `kotlin-obd-api` can't even do the escape hatch without subclassing against undocumented internals.
- **C2**: Both assume a synchronous blocking socket. Bridging BLE GATT notify/write to that shape means either (a) a real `InputStream` backed by a byte queue fed from the notification callback — ~100-150 LOC that duplicates the reassembly/single-flight work `:core:ble` already owns per the `ObdLink` contract, or (b) blocking calls wrapped in `runBlocking` inside stream methods, an anti-pattern risking ANR/deadlock. Either way the shim becomes a second reassembly layer sitting on top of the one the frozen contract mandates — not "erasing the library's value" outright, but leaving the transport-facing 25% close to a wash.

### Why custom isn't a clean 10 across the board
- C3 custom scores 9, not 10: a correct half-duplex scheduler (SEARCHING/NO DATA/multi-frame/timeout edge cases) is real, untested-until-written work that a *fitting* library would have battle-tested.
- C5 favors library 6 vs custom 4: `kotlin-obd-api`'s PID packages (`engine/`, `fuel/`, `pressure/`, `temperature/`) do encode correct SAE scaling formulas worth reading as reference — but they return the library's own `ObdResponse`, not `PidDefinition.parse: (ByteArray) -> Double`, so it's copy-the-formula value, not drop-in integration.

## What would change these scores
- **C1** flips upward for library if either project ships a parameterized `ATSH <addr>` / `ATCRA <addr>` command class plus a raw mode-22 builder — neither exists as of 2026-08-09 (source-checked, not just README).
- **C2** flips upward for library if a BLE-native fork/transport adapter surfaces in the ecosystem — none found via search.
- **C3** flips upward for library if `kotlin-obd-api` adds a mode that consumes an injected `suspend fun sendRaw(String): String` instead of owning `InputStream`/`OutputStream` directly.
- **C4** is bimodal, not gradual: it hinges entirely on *which* library gets picked. `obd-java-api` is dead (archived 2017); `kotlin-obd-api` is genuinely alive. This is the one constraint where "adopt a library" isn't a single answer.
- **C5** flips toward custom if the standard-PID set turns out larger than the ~6 PIDs scoped in the build plan (more PIDs = more value from a pre-built catalog).
