# :core:protocol

Pure Kotlin/JVM. Zero Android dependencies, zero DI annotations (see `DECISIONS.md` D2).
Production dependencies: `:core:model` + `kotlinx-coroutines-core`. Tests additionally use
`:core:testing` (`FakeObdLink` + the shipped transcript fixture) and branch-local fixtures in
this module's own test resources.

This module owns everything between "raw string pipe" (`ObdLink`) and "typed readings". It is
the module where a subtle bug does the most damage: a mis-framed byte or a wrong constant does
not crash, it puts a *plausible* wrong number on a gauge on a mountain grade. Every design
choice below is biased toward refusing to produce a value over producing a doubtful one.

## Public surface

### `com.revel.obdgauge.protocol` — init (OBD-13)

- `InitStep` — the sequence, as an enum carrying its command:
  `RESET` (`ATZ`) → `ECHO_OFF` (`ATE0`) → `LINEFEEDS_OFF` (`ATL0`) → `SPACES_OFF` (`ATS0`) →
  `PROTOCOL_AUTO` (`ATSP0`) → `VERIFY` (`0100`).
- `InitFailure` — sealed: `NoResponse(step)`, `Rejected(step, raw)`, `NoProtocol(raw)`,
  `LinkDown(step, message)`.
- `InitResult` — sealed: `Success(banner, supportedPidBitmap, rawVerifyResponse)` with
  `supports(pid)` for decoding the `0100` bitmap, or `Failure(failure)`.
- `InitTimeouts(reset = 5s, atCommand = 2s, verify = 10s)`.
- `Elm327InitStateMachine(link, timeouts)` — `suspend fun run(): InitResult`.

Behaviour worth knowing before you use it:

- **Result, not exceptions.** Only `CancellationException` propagates. Timeouts, rejected
  commands, dead buses and dropped links are all typed values.
- **Stateless per run**, therefore cancellable and restartable: cancel a hung attempt and call
  `run()` again; it re-issues the full sequence from `ATZ`. The class holds no mutable fields.
- **No retry policy inside the machine** — backoff and re-attempts belong to the caller, which
  owns the UI. The one exception is a **single `ATZ` retry after a first-command timeout**: a
  cold or just-woken ELM327 commonly swallows the first byte it is sent, and failing the first
  connection of every day over that would be a bug users feel daily. Bounded to one attempt,
  only on `RESET`, only for a timeout — a dongle that *answers* wrongly is never retried.
- **Banner validation** accepts any `ATZ` response containing `ELM` or `OBD`; anything else
  (silence, `?`, an error line, garbage) is `Rejected(RESET, raw)` with the raw text attached
  for the debug console.
- It does **not** connect. Scan/connect/GATT belong to the `ObdLink` implementation.

### `com.revel.obdgauge.protocol` — registry + parser (OBD-14)

- `ProtocolPidIds` — `MAP = "map"`, `IAT = "iat"`, `SPEED = "speed"`: ids for the PIDs that the
  frozen `PidIds` contract does not name yet, following its naming convention exactly.
- `StandardPidSpec(definition, mode, pid, dataByteCount)` with `command` (`"0105"`),
  `responseMode` (`0x41`) and `responseHeader` (`"4105"`). `PidDefinition` carries no byte
  count — it is the frozen UI-facing contract — so the wire facts live here beside it.
- `PidRegistry` — `coolant` `0105`, `rpm` `010C`, `map` `010B`, `baro` `0133`,
  `intakeAirTemp` `010F`, `speed` `010D`; plus `all`, `definitions`, `byId(id)`,
  `byPid(mode, pid)`.
- `VendoredSaeScaling` — `temperatureCelsius(a)`, `engineRpm(a, b)`, `pressureKpa(a)`,
  `speedKmh(a)`, `dataByte(data, index)`.
- `ParseFailure` — sealed: `NoData`, `Stopped`, `UnableToConnect`, `UnknownCommand`, `Empty`,
  `BusError(raw)`, `NegativeResponse(requestMode, code)`, `NoMatchingFrame(expectedHeader, raw)`,
  `MalformedHex(raw)`, `UnexpectedDataLength(expected, actual)`, `ScalingError(message)`.
- `ParseOutcome<T>` — `Success(value)` | `Failure(reason)`, with `valueOrNull()` /
  `failureOrNull()`.
- `ResponseParser` — `parse(spec, raw): ParseOutcome<Double>`,
  `dataBytes(raw, responseMode, pid, expectedCount): ParseOutcome<List<Int>>`, and its general
  form `dataBytesForHeader(raw, responseHeader, requestMode, expectedCount)` (added by OBD-15 so
  the manufacturer path reuses one framing implementation; `dataBytes` now delegates to it).

### `com.revel.obdgauge.protocol` — Mercedes mode-22 (OBD-15)

- `XGaugeCode(name, txd, rxf, rxd, mth)` — one ScanGauge X-Gauge code, verbatim, with
  `decode(): XGaugeDecode`.
- `XGaugeDecode` — `canId`, `requestBytes`, `requestMode`, `responseHeader`, `rxFilterPci`,
  `rxFilterBytes`, `dataByteIndex`, `dataByteCount`, `requiredDataBytes`, `multiplier`,
  `divisor`, `adder`, plus `displayValue(raw)`, `isFahrenheitConversion`, `celsiusOffset`.
- `Mode22PidSpec(definition, canId, rxFilter, requestBytes, responseHeader, dataByteIndex,
  dataByteCount, source)` — the mode-22 counterpart of `StandardPidSpec`.
- `MercedesPidRegistry` — `TRANS_TEMP_CODE`, `transTemp`, `all`, `definitions`, `byId(id)`.
- `Mode22ResponseParser.parse(spec, raw): ParseOutcome<Double>`.
- `Mode22Config(defaultHeader = "7DF", rxFilterEnabled = true, atTimeout = 2s,
  requestTimeout = 2s)`.
- `Mode22Requester(link, config)` — `request(spec): PollOutcome`, `restoreHeaders(): Boolean`,
  `restorePending`.
- `PollOutcome` — sealed: `Value(value)`, `Skipped(reason)`, `HeaderRejected(command, raw)`,
  `LinkDown(message)`. Shared by both request paths.

### `com.revel.obdgauge.protocol` — scheduler + computed channels (OBD-16)

- `PolledPid` — sealed: `Standard(spec)` | `Manufacturer(spec)`.
- `PidCatalog` — `polled`, `definitions`, `computedBoost`, `byId(id)`, `isVerified(id)`,
  `dependenciesOf(id)`.
- `ComputedChannels.boost(map, baro): Reading?`.
- `PollConfig(cycleInterval = 200 ms, slowEveryNCycles = 5, commandTimeout = 2 s,
  fastStaleAfter = 2 s, slowStaleAfter = 15 s, initTimeouts, mode22)` with
  `staleAfter(priority)`.
- `PollSchedule` — `cycleMembers(pids, cycle, slowEveryNCycles)`, `includesSlow(cycle, n)`.
- `PollEvent` — sealed: `Initialized`, `InitFailed`, `ReadingSkipped`, `HeaderRejected`,
  `HeaderRestoreFailed`, `LinkDropped`, `UnknownPid`.
- `RealVehicleDataSource(link, scope, config, clock, onEvent)` — the `VehicleDataSource`
  implementation.

## The Mercedes mode-22 decode (OBD-15) — reasoning, not transcription

The 722.6's transmission temperature has no standard SAE PID; the only documentation is the
ScanGauge X-Gauge code the Sprinter community runs. Its four fields are held as **data**
(`XGaugeCode`) and decoded in code, so the arithmetic is executable and test-pinned rather than
retyped by eye.

**Field conventions are established on a control case, not assumed.** The published X-Gauge for
engine *coolant* — `TXD 07DF0105 / RXF 034105000000 / RXD 1808 / MTH 00010001FFD8` — has a right
answer known independently from SAE J1979 (`A − 40` °C at `41 05`). Decoding it with the
conventions below reproduces that answer exactly, which is what licenses using the same
conventions on trans temp:

| Field | Format | Coolant control case | Trans temp |
|---|---|---|---|
| TXD | 4-digit CAN id + request bytes | `07DF` + `01 05` → textbook broadcast mode-01 ✓ | `07E1` + `21 30` (KWP read, local id `0x30`, to the TCM) |
| RXF | ISO-TP PCI byte + leading response bytes, zero-padded | `03` + `41 05` ✓ | `03` + `22 00 …` — **anomalous, see below** |
| RXD | start bit, bit length | `18 08` = bit 24 = frame byte 3 = `A` ✓ | same ⇒ the byte after `61 30` |
| MTH | mul / div / **signed** adder | `0001/0001/FFD8` = `×1 −40` = SAE °C ✓ | `0009/0005/FFC6` = `× 9/5 − 58` |

**Why `−58` is evidence for the decode rather than against it.** It is neither the SAE `−40` nor
the `+32` of a °C→°F conversion, which is exactly why it must be solved rather than eyeballed:

```
  MTH says          display = raw × 9/5 − 58
  °F is defined as  °F      = °C × 9/5 + 32
  assume            °C      = raw − k
  then              °F      = (raw − k) × 9/5 + 32 = raw × 9/5 + (32 − 9k/5)
  match the adder   32 − 9k/5 = −58  ⇒  9k/5 = 90  ⇒  k = 50
```

`k = 50` falls out **exactly**, on integers. The X-Gauge code is therefore precisely the °C→°F
conversion of a raw byte holding °C with a **−50** offset — a Mercedes convention, distinct from
the SAE −40 that `0105` uses. Had the raw been SAE-style, the adder would have been `−40`
(`FFD8`), which is what the coolant control case carries. Two more fields corroborate the byte
position independently: RXF's PCI byte says the reply is 3 bytes (`61 30 XX` — one data byte),
and RXD points at that same byte.

**What is published: °C = raw − 50**, natural unit, consistent with the table above. The 50 is
not typed in — it is derived from `MTH` at class-init by `XGaugeDecode.celsiusOffset`, and a test
asserts the published °C converts back to the ScanGauge's °F for all 256 raw values. Plausible
range: −50 °C … 205 °C, a warm 722.6 landing near 80–110 °C (raw `130`–`160`).

**The one open ambiguity is RXF, and it is not in the value path.** Under the convention the
control case establishes, `032200000000` should read `03 61 30 00 00 00`; `0x22` is not the
`0x61` a `21 30` request must answer with, and is not any mode byte that request can produce.
Rather than invent a meaning, RXF is not used:

- `ATCRA` is derived from ISO 15765-4 instead — a physical request to `7E1` is answered by `7E9`
  (`+ 8`) — which is independently checkable and is what `ATCRA` actually takes (a CAN id, not a
  frame pattern). `MercedesPidRegistry.responseIdFor` returns `null` outside `7E0..7E7`, so no
  filter is invented for e.g. the `7DF` broadcast.
- Correctness does not rest on it: `ATCRA` is a noise filter, and a value is accepted only if a
  **byte-aligned `61 30`** is found in the reply. A wrong or absent filter yields a skipped
  reading, never a wrong number. `Mode22Config(rxFilterEnabled = false)` turns `ATCRA` off
  entirely if bring-up finds it suppressing real answers.
- **For OBD-22:** capture what `7E1` / `21 30` actually answers, and settle RXF.

**Header discipline.** Each manufacturer read is one self-restoring sequence —
`ATSH7E1` → `ATCRA7E9` → `2130` → `ATCRA` → `ATSH7DF` — because `ATSH`/`ATCRA` are sticky dongle
state, not per-command arguments. A header left at `7E1` would address every subsequent `0105` to
the transmission controller (fail-safe: `NO DATA`, never a wrong value — but the standard gauges
would go quiet for the session). A restore the dongle did not acknowledge is remembered in
`restorePending` and retried at the top of the next poll cycle. Atomicity comes from the
scheduler being a single sequential coroutine; the class adds no locking, since two interleaved
*sequences* would corrupt each other's header state regardless of a mutex around `sendRaw`.

Commands are written without a separating space (`ATSH7E1`). The ELM327 strips whitespace, so
both forms are equivalent on a genuine chip; this form matches the datasheet examples and the
shipped fixture. Note `ATSH` (set transmit header) is **not** `ATH` (print received ids) —
headers stay **off**, which is what keeps `ResponseParser`'s framing assumptions intact.

**Codes deliberately not ported.** The build plan's mode-22 *boost source*
(`TXD 07DF018670`, `MTH 00910BB8____`) is not implemented: OBD-16 computes boost from the
standard `010B`/`0133` pair, which is altitude-correct and SAE-verified, and that code's
transcription is visibly incomplete (the literal `____`) with an RXD that does not decode
consistently. Oil temp is unscheduled (OBD-35). Note also that `:app`'s placeholder
`DASHBOARD_PIDS` and `:core:testing`'s shipped fixture carry a *different* trans-temp
hypothesis (`22 05 43` → `62 05 43 XX`); this module implements the X-Gauge code the build plan
§2B specifies. OBD-22 decides which, if either, the van answers.

## Scheduler semantics (OBD-16)

`RealVehicleDataSource` runs **one coroutine per session**: initialize once, then repeat — poll
this cycle's PIDs in order one command at a time, publishing after each; derive boost; wait
`cycleInterval`. Sequential is required, not a simplification: the ELM327 is half-duplex.
`StateFlow` gives latest-value semantics, so a 60 fps UI and a 10 Hz bus never queue.

- **Cadence:** FAST every cycle, SLOW when `cycle % slowEveryNCycles == 0` — which includes cycle
  0, so baro (and therefore boost) exists from the first moment rather than N cycles in. Order
  within a cycle is the caller's requested order, which is what makes the command sequence
  assertable.
- **Selection by id.** `start(pids)` uses only `PidDefinition.id`; framing and scaling always
  come from `PidCatalog`, so a placeholder definition passed in by a screen cannot change how a
  value is computed. Unknown ids emit `PollEvent.UnknownPid`. Asking for `boost` automatically
  pulls in `map` and `baro`.
- **Staleness** is recomputed on *every* publish from each reading's own age against
  `staleAfter(priority)` — FAST and SLOW have separate windows, since a shared one would either
  flap on SLOW channels or hide a dead FAST one. A channel that stops answering therefore dims on
  its own, keeping its last good value; nothing is ever zeroed or interpolated.
- **Refusals:** a parse failure skips the reading and emits `PollEvent.ReadingSkipped`. A dongle
  that rejects `ATSH` costs only the manufacturer channel (`PollEvent.HeaderRejected`); the
  standard gauges carry on.
- **Lifecycle,** per the pinned contract: `start` cancels the previous loop and *joins* it before
  the new one clears `readings`, so repeated `start` cannot leave two loops publishing; `stop` is
  idempotent; `start` after `stop` is a clean session that re-runs init.
- **Link drop → the loop parks:** everything is marked stale, `PollEvent.LinkDropped` is emitted,
  and the coroutine returns without throwing. No reconnect and no retry spin — reconnection is
  `:core:ble`'s job (OBD-23), and hammering a dead link would drain a parked van's battery.
  Polling resumes when the owner calls `start` again.

## Computed boost (OBD-16)

`boost = MAP − baro`, both `kPa` **absolute**, so it is referenced to the air the engine is
actually breathing and is correct from Death Valley to Loveland Pass. Every fixed-offset
shortcut ("subtract 101.3") is wrong by ~20 kPa (3 psi) at 6 000 ft and ~32 kPa (4.6 psi) at
10 000 ft — on a loaded Sprinter, the difference between "normal pull" and "something is wrong".
Tested at all three elevations.

Naturally signed: at idle, manifold pressure sits well below ambient, so boost is negative. That
is vacuum, a real reading, and it is not clamped — a gauge that floors at zero hides a leaking
intake.

Freshness: the boost `Reading` is timestamped by its **oldest** input and is stale if *either*
input is stale, so a fresh MAP over a ten-minute-old baro from 3 000 ft lower cannot present as
current. No boost reading exists at all until both inputs do.

## Unit strategy

**Every PID parses to its natural SI-ish unit**, recorded in `PidDefinition.unit`:

| Channel | PID | Unit | Notes |
|---|---|---|---|
| coolant | `0105` | `CELSIUS` | `A − 40` |
| intakeAirTemp | `010F` | `CELSIUS` | `A − 40` |
| map | `010B` | `KPA` | absolute |
| baro | `0133` | `KPA` | absolute |
| rpm | `010C` | `RPM` | `(256A + B) / 4` |
| speed | `010D` | `KMH` | `A` |
| transTemp | `2130` (mode-22) | `CELSIUS` | `A − 50`, **unverified** — see the decode section |
| boost | computed | `KPA` | `MAP − baro`, gauge (signed) |

Display conversion to °F, PSI, or mph is the **UI's** job. Two reasons: protocol math stays in
one unit system, and boost (`MAP − baro`, OBD-16) subtracts two quantities that are already
commensurate and absolute — which is what makes it correct at any elevation instead of assuming
a sea-level offset.

**Mismatch to resolve at Phase-4 integration:** `:app`'s `DASHBOARD_PIDS`
(`app/src/main/.../gauge/DashboardPids.kt`) declares `coolant` as `FAHRENHEIT` and `boost` as
`PSI`. That file documents itself as a placeholder whose `request`/`parse`/`pollPriority` are
unused by `FakeVehicleDataSource` and which "the real registry replaces at Phase-4
integration", so this is not a live conflict today — but whoever wires the real chain must
either convert at the ViewModel/UI boundary or change those `unit` values, not silently assume
the numbers already match. The gauges' amber/red thresholds are expressed in °F, so the
conversion has to land somewhere deliberate.

## Vendoring (DECISIONS.md D1)

`VendoredSaeScaling.kt` carries an Apache-2.0 header naming kotlin-obd-api v1.4.1
(master @ `30014eb`) and the exact source files the constants came from; the repository-root
`NOTICE` records the same. kotlin-obd-api is **not** a runtime dependency — the constants were
transcribed and reimplemented.

Each formula was then cross-checked against the public SAE J1979 definition independently.
**Five of six agree exactly.** The sixth, engine RPM, agrees on formula *and* constant but not
on arithmetic: kotlin-obd-api computes `Long / Int`, which truncates in Kotlin, discarding the
quarter-rpm resolution SAE specifies (raw `00 01` → SAE `0.25`, kotlin-obd-api `0`). This
module implements the SAE-exact floating-point divide. The divergence is documented in the
file's KDoc and pinned by a test.

## Parser tolerance

Tolerated, all of it seen from real dongles: `\r` / `\n` / both, arbitrary interleaved
whitespace, stray `>` prompts, `SEARCHING...`, `BUS INIT`, progress dots, command echo
remnants, headers with (`41 05 5A`) or without (`41055A`) spaces, lowercase hex, extra trailing
data bytes, and ELM's indexed multi-line long-response format (`0:` / `1:` continuation lines,
with the ISO-TP length line dropped so it cannot shift byte alignment).

Refused, as a typed failure rather than a value: a header that matches only at an **unaligned**
(odd-nibble) offset, a frame for a different PID, a frame short of the PID's data length, hex
that will not frame into whole bytes, `7F` negative responses, and any scaling lambda that
throws or returns a non-finite number.

## Test inventory

186 JVM tests, `./gradlew :core:protocol:test`:

- `VendoredSaeScalingTest` — 15, exact-value boundary checks written against SAE, not against
  the vendored source.
- `PidRegistryTest` — 14.
- `ResponseParserTest` — 30, values + tolerance + every typed failure.
- `ResponseParserPropertyTest` — 3 tests, 4500 seeded pseudo-random cases: the parser never
  throws, and never returns a value outside the range its PID's formula can physically produce.
- `Elm327InitStateMachineTest` — 28, happy path with command-order assertions, retry policy,
  every injected fault at every step, cancel/restart.
- `XGaugeCodeTest` — 15, the field decode step by step, anchored on the coolant control case,
  plus the °C ⇄ ScanGauge-°F round trip over all 256 raw values and every malformed-field refusal.
- `MercedesPidRegistryTest` — 10, wiring, scaling, unverified flag, `ATCRA` derivation.
- `Mode22ResponseParserTest` — 14, values + wire noise + every refusal, including the
  headers-on cases and a multi-byte UDS identifier.
- `Mode22RequesterTest` — 10, exact command sequences for happy path, refusal, `ATSH`/`ATCRA`
  rejection, link drop, failed-restore retry, filter disabled.
- `ComputedChannelsTest` — 10, boost at three elevations, vacuum, staleness and timestamp
  propagation.
- `PollScheduleTest` — 10, cadence over twelve cycles, order preservation, config validation.
- `PidCatalogTest` — 8, resolution and `isVerified`.
- `RealVehicleDataSourceTest` — 19, over `RecordingObdLink` + `FakeObdLink` on virtual time:
  init-once-then-poll, exact FAST/SLOW command sequences, the mode-22 sequence inside a cycle,
  boost dependency expansion, staleness, skip-not-poison, unknown PIDs, init failure, link drop
  and park, and the pinned restart/idempotence contract.

Line coverage measured ad hoc with JaCoCo (added to `build.gradle.kts`, measured, and reverted —
the repo has no coverage plugin wired in):

| Area | Line | Branch |
|---|---|---|
| Scheduler + computed channels (OBD-16) | **97.6 %** (203/208) | 94.3 % |
| Mode-22 decode/parse/framing (OBD-15) | **98.9 %** (186/188) | 87.3 % |
| Both combined | 98.2 % (389/396) | 90.2 % |
| Module-wide | 97.8 % | 89.9 % |

Both exceed the wave's >90 % exit criterion. The residual misses are data-class `copy`/`equals`
synthetics and one `error(…)` line that is unreachable by construction.

### Test fixtures

`core/protocol/src/test/resources/transcripts/mode22-trans-temp.txt` — a synthetic full-session
transcript (init → standard PIDs → the framed mode-22 exchange → restore). It lives here rather
than in `:core:testing` because this branch does not own that module. **It is a prediction, not
a capture:** the trans-temp reply is what this module's decode says a 722.6 controller will send.
**OBD-22 supersedes it** with real captures, at which point these fixtures should move to
`:core:testing` and every test here re-run against the real bytes.

## Known limitations

- The trans-temp decode's residual assumption is the display UNIT: the control case pins the
  X-Gauge field formats, and the math (integer k=50; a °C reading would show ~176 for a warm
  box; a °F-over-SAE-raw code would carry adder −40) strongly supports "raw = °C−50 shown in
  °F" — but the unit itself is the one link not backed by ground truth. OBD-22 settles it.
- Requesting `boost` also publishes its inputs (`map`, `baro`) in `readings` — dependency
  expansion is visible to consumers that iterate the map.

- A `?` glued to the end of a data line (`41 05 5A?`) voids that line (typed failure, never a
  wrong value) — real ELM327s emit `?` on its own line, so this is theoretical.
- Headers-on (`ATH1`) responses are NOT guarded by construction, and OBD-15 kept it that way
  rather than adding framing: `ATSH`/`ATCRA` change *which* ECU is addressed and *which* replies
  pass, never whether ids are printed, so headers remain off on both paths. If `ATH1` were set
  anyway, an 11-bit id fails safe on digit parity and a 29-bit id is four whole bytes so
  alignment survives and the real frame is still found at its true offset — neither shifts a
  byte. Both cases are now pinned by tests rather than left as an assumption.
- ELM indexed long-responses are reassembled in arrival order; the index digit is not used to
  reorder (real dongles emit in order; out-of-order input fails safe on length).
- **The trans-temp code is a hypothesis**, `verified = false`, and its RXF field does not decode
  consistently (see the decode section). It is also *a* hypothesis, not the only one in the repo
  — `:app` and `:core:testing` carry a `22 05 43` variant. OBD-22 settles both questions.
- **Poll-rate defaults are guesses**, not measurements: `cycleInterval = 200 ms` and
  `slowEveryNCycles = 5` were chosen for a typical BLE round trip, and Sprint 3 tunes them
  against real hardware latency. SLOW channels are all polled on the *same* cycle, which
  periodically lengthens one cycle; spreading them round-robin is a plausible Sprint-3 change.
- `RealVehicleDataSource` **never connects or disconnects** the link — a deliberate deviation
  from `VehicleDataSource.stop()`'s "release the underlying connection" wording. Scan, connect,
  and reconnect belong to `:core:ble` (OBD-23) and the DI owner; Phase-4 integration decides who
  calls `disconnect`. It also needs a caller-supplied `CoroutineScope`, since the frozen `start`
  is not a suspend function.
- `PidCatalog.computedBoost` exists only so a caller has a `PidDefinition` for the boost channel;
  its `request` is a structural placeholder and its `parse` deliberately throws (trapped into a
  typed `ScalingError`, never a number). The scheduler routes by id and never calls it.

- **Nothing here has met real hardware.** Every value is verified against the SAE standard and
  the synthetic transcript fixture, not against the van's ECU. Hardware verification is OBD-22;
  until then, treat "verified" as "verified against the standard".
- **CAN-ID headers are assumed off** (`ATH0`, the ELM327 default this machine never changes).
  With headers on, a `7E8` frame id would be concatenated into the payload and break byte
  alignment. Mode-22 work (OBD-15) needs `ATSH`/`ATCRA` and owns that case.
- **`0100` only.** The init verify reads the first supported-PID bitmap (`0x01..0x20`), so
  `InitResult.Success.supports(0x33)` (baro) is `false` — that PID lives in the `0120`/`0140`
  bitmaps this machine does not request. `false` means "not advertised in this bitmap", not
  "unsupported". The scheduler does not gate polling on this bitmap: a PID the ECU does not
  implement answers `NO DATA` and is skipped, which is the same outcome by a safer route.
- The banner check is deliberately the one cosmetic strictness in the machine; if bring-up
  meets a working dongle whose `ATZ` says neither `ELM` nor `OBD`, widen `BANNER_TOKENS` rather
  than dropping the check.
