# :core:protocol

Pure Kotlin/JVM. Zero Android dependencies, zero DI annotations (see `DECISIONS.md` D2).
Production dependencies: `:core:model` + `kotlinx-coroutines-core`. Tests additionally use
`:core:testing` (`FakeObdLink` + the shipped transcript fixture).

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
- `ResponseParser` — `parse(spec, raw): ParseOutcome<Double>` and
  `dataBytes(raw, responseMode, pid, expectedCount): ParseOutcome<List<Int>>`.

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

88 JVM tests, `./gradlew :core:protocol:test`:

- `VendoredSaeScalingTest` — 15, exact-value boundary checks written against SAE, not against
  the vendored source.
- `PidRegistryTest` — 14.
- `ResponseParserTest` — 28, values + tolerance + every typed failure.
- `ResponseParserPropertyTest` — 3 tests, 4500 seeded pseudo-random cases: the parser never
  throws, and never returns a value outside the range its PID's formula can physically produce.
- `Elm327InitStateMachineTest` — 28, happy path with command-order assertions, retry policy,
  every injected fault at every step, cancel/restart.

Line coverage measured ad hoc with JaCoCo (not wired into the build): **100 % on the parser**
(`ResponseParser`, its file-level helpers, and `ParseFailure`/`ParseOutcome`), 100 % on
`PidRegistry` and `VendoredSaeScaling`, ~99 % module-wide (2 synthetic lines in an enum and a
companion). The repo has no coverage plugin configured; if the wave exit criterion needs a
recorded number, JaCoCo or Kover has to be added to the build first.

## Known limitations

- A `?` glued to the end of a data line (`41 05 5A?`) voids that line (typed failure, never a
  wrong value) — real ELM327s emit `?` on its own line, so this is theoretical.
- Headers-on (`ATH1`) responses are NOT guarded by construction: 3-digit 11-bit headers happen
  to fail safe via digit parity, but a 29-bit header (`18DAF110...`) parses as if headers were
  off. The init sequence never enables headers; OBD-15 must keep it that way or add framing.
- ELM indexed long-responses are reassembled in arrival order; the index digit is not used to
  reorder (real dongles emit in order; out-of-order input fails safe on length).

- **Nothing here has met real hardware.** Every value is verified against the SAE standard and
  the synthetic transcript fixture, not against the van's ECU. Hardware verification is OBD-22;
  until then, treat "verified" as "verified against the standard".
- **CAN-ID headers are assumed off** (`ATH0`, the ELM327 default this machine never changes).
  With headers on, a `7E8` frame id would be concatenated into the payload and break byte
  alignment. Mode-22 work (OBD-15) needs `ATSH`/`ATCRA` and owns that case.
- **`0100` only.** The init verify reads the first supported-PID bitmap (`0x01..0x20`), so
  `InitResult.Success.supports(0x33)` (baro) is `false` — that PID lives in the `0120`/`0140`
  bitmaps this machine does not request. `false` means "not advertised in this bitmap", not
  "unsupported".
- **No `VehicleDataSource`, no scheduler, no mode-22 PIDs** — OBD-15/16, next branch.
- The banner check is deliberately the one cosmetic strictness in the machine; if bring-up
  meets a working dongle whose `ATZ` says neither `ELM` nor `OBD`, widen `BANNER_TOKENS` rather
  than dropping the check.
