# :core:testing

Pure Kotlin/JVM. Zero Android dependencies, zero DI annotations (see `DECISIONS.md` D2).
Depends on `:core:model` only.

## Public surface

This module holds shared fakes and fixtures consumed by every other module's tests. Both
fakes are pure Kotlin/JVM, hold no Android imports, and take no wall-clock/randomness
dependency internally.

### `com.revel.obdgauge.testing.datasource` (OBD-4)

- `Scenario` — `IDLE`, `TOWN_HEAT_SOAK`, `GRADE_CLIMB`, `DISCONNECT_RECONNECT`.
- `ScenarioChannel` — the channel-id constants (`coolant`, `oilTemp`, `transTemp`, `boost`,
  `rpm`) the scripts emit; shaped like `PidDefinition.id` so callers can build matching
  `PidDefinition`s.
- `FakeVehicleDataSource(scenario, tickInterval, startInstant, scope)` implements
  `VehicleDataSource`. Replays the scenario's fixed script on `scope` (inject a `TestScope`'s
  `backgroundScope` for virtual-time control in tests); every `Reading` timestamp is derived
  from `startInstant + n * tickInterval`, never `Instant.now()`. `TOWN_HEAT_SOAK`'s script
  climbs coolant/oil/trans into amber-threshold territory (225/240/215 °F) for OBD-10's
  screenshot tests. `DISCONNECT_RECONNECT` walks `connection` through
  `Ready → Error → Scanning → Connecting → Ready`; readings freeze (value/timestamp frozen,
  `stale = true`) for the outage and only refresh once new data actually arrives after
  reconnect. `start()` replaces the active set and resets `readings` synchronously; `start`
  and `stop` are idempotent and cancellation-safe.

### `com.revel.obdgauge.testing.link` (OBD-5)

- `TranscriptEntry` / `TranscriptParser` — plain-text `REQUEST:`/`RESPONSE:`/`>`-terminated
  transcript format, parsed from a string (`parse`) or a classpath resource
  (`parseResource`).
- `Fault` — `Garbage`, `NoData`, `Stopped`, `Timeout`, `MidResponseDisconnect`, injectable per
  command (queued, FIFO, falls back to the scripted response once exhausted) or per 1-indexed
  call position (checked first).
- `FakeObdLink(transcript, defaultLatency, commandLatency, commandFaults, positionFaults,
  connectLatency)` implements `ObdLink`. Matches by command (case-insensitive), not position;
  an unmatched command returns ELM327's own `"?"` unknown-command reply. **Deliberately
  stricter than the `ObdLink` contract on half-duplex**: a production implementation must
  transparently serialize concurrent `sendRaw` calls, but this fake throws
  `IllegalStateException` if a second call arrives while one is in flight, as a
  defense-in-depth check on the protocol scheduler.
- Fixture: `src/main/resources/transcripts/elm327-init-and-pids.txt` — full ELM327 init
  (`ATZ`→`ATE0`→`ATL0`→`ATS0`→`ATSP0`→`0100`), standard PIDs (`0105` coolant, `010B` MAP,
  `0133` baro, `010C` rpm), and a mode-22 trans-temp exchange (`ATSH7E1`/`ATCRA7E9`/`220543`).

See `docs/01-build-plan.md` §0.3.

## Known limitations

- `FakeObdLink`'s command matching is exact-string (case-insensitive); it doesn't normalize
  whitespace or hex casing beyond `uppercase()`, so a caller's exact command formatting must
  match the transcript's.
- `FakeVehicleDataSource`'s scenario scripts are fixed narratives (not parameterized by
  arbitrary PID sets beyond filtering to the requested ids) — see `ScenarioScript.kt` if a
  future scenario needs new channels.
- JUnit, `kotlinx-coroutines-test`, and Turbine are wired as `implementation` dependencies
  (not `testImplementation`) since this module's whole job is to be a test-support library
  other modules' test source sets consume.
