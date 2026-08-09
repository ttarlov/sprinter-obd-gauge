# Position: Adopt `kotlin-obd-api` (eltonvs, v1.4.1) — not `obd-java-api`

## Recommendation

Adopt **`kotlin-obd-api`** (`com.github.eltonvs:kotlin-obd-api:1.4.1`, Apache-2.0) as the base of
`:core:protocol`, in hybrid form: use its `ObdCommand`/`ObdDeviceConnection` architecture and its
~50 pre-built standard-PID command classes, and **add ~4 new command classes** (two `ATCommand`
subclasses for `ATSH`/`ATCRA`, and Mode-22 `ObdCommand` subclasses per PID) using the exact same
pattern the library already uses for every other command. This is not a stretch of the library —
it's the intended extension point.

Reject `obd-java-api` (pires) outright: **archived since 2017**, no coroutine model, no built-in
single-flight guarantee, blocking-stream API only.

## Why kotlin-obd-api fits the frozen contracts

**It already matches `ObdLink`'s half-duplex requirement.** `ObdLink.sendRaw` in
`core/model/.../ObdLink.kt` mandates internal single-flight via mutex. `ObdDeviceConnection` does
exactly this: `runWithReadPolicy` wraps every command in `runMutex.withLock { ... }`
(`ObdDeviceConnection.kt:47`), so concurrent callers are serialized automatically — the exact
defense-in-depth property the BLE agent's contract also asks for.

**It's coroutine-native, not adapted.** `ObdDeviceConnection.run()` is a `suspend fun`
(`ObdDeviceConnection.kt:42`), uses `withContext(ioDispatcher)` around stream I/O
(`ObdDeviceConnection.kt:79-85`), and `delay()` for pacing — this drops into the app's
coroutines/Flow-everywhere requirement (`docs/01-build-plan.md` guiding constraints) with no
wrapper layer needed for cancellation-safety.

**Response parsing already handles ELM327 noise.** `ObdRawResponse` in
`command/Response.kt:9-33` runs a processor pipeline that strips whitespace, `BUS_INIT_PATTERN`
noise, and colons before exposing `bufferedValue: IntArray` (hex-byte-parsed). `Exceptions.kt`
maps `SEARCHING...`, `NO DATA`, `STOPPED`, `?`/unsupported-command, and non-numeric garbage to
typed exceptions (`BusInitException`, `NoDataException`, `StoppedException`,
`UnSupportedCommandException`, `NonNumericResponseException` — `Exceptions.kt:23-70`). That's
most of the "tolerant parser" self-test list in `docs/01-build-plan.md` §2B already written and
under test (`src/test/kotlin/.../ObdDeviceConnectionTest.kt` uses a scripted
`InputStream`/`OutputStream` pair to test exactly the "one command in flight" + read-loop
behavior our BLE adapter needs to replicate).

**API shape maps cleanly onto our `ObdRequest.StandardPid`.** Every command is an `ObdCommand`
subclass with `mode`/`pid` string fields and a `handler: (ObdRawResponse) -> String`
(`command/ObdCommand.kt:3-27`); e.g. `RPMCommand` (`engine/Engine.kt`) is `mode="01"`, `pid="0C"`,
handler does the `/4` scaling. Translating `PidDefinition.request: ObdRequest.StandardPid(mode,
pid)` into one of these, or the reverse, is a mechanical 1:1 mapping — not an abstraction fight.

**Mode-22 + custom headers: not shipped, but the extension point is proven and trivial.** There is
no `SetHeaderCommand`/`SetReceiveFilterCommand` in `command/at/Mutations.kt` today, and no mode-22
command anywhere in the tree (confirmed via GitHub code search across the repo — zero hits for
`ATSH`/`CRA`). But `ATCommand` is *itself* just `ObdCommand` with `mode="AT"` fixed
(`command/ATCommand.kt`), and every AT sub-command in `at/Mutations.kt` (`SetEchoCommand`,
`SetHeadersCommand`, `SetLineFeedCommand`, `SetTimeoutCommand`) is 5-8 lines: override `tag`,
`name`, `pid`. Adding `SetHeaderCommand(header: String)` (`pid = "SH $header"`) and
`SetReceiveFilterCommand(addr: String)` (`pid = "CRA $addr"`) is copy-paste of that exact pattern.
Likewise a Mode-22 PID is just an `ObdCommand` with `mode="22"`, `pid="1808"` (trans temp) and a
custom `handler` — structurally identical to every existing PID class in `engine/Engine.kt` or
`temperature/Temperature.kt`. This is the honest concession: **mode-22 support requires us to
write ~4 small classes**, not zero. It is not, however, evidence the library's architecture
resists it — the architecture is generic over mode/pid by design.

## License, maintenance, activity

- **License:** Apache-2.0 (`eltonvs/kotlin-obd-api` repo metadata, `license.spdx_id: Apache-2.0`)
  — permissive, compatible with a proprietary or open app either way.
- **Activity:** last commit **2026-07-31** (`30014eb6`, "ci: harden GitHub Actions supply chain"),
  with real substantive commits in the same window — `82e0f850` (2026-07-30, "Reduce response
  read latency and normalize ELM327 AT command formatting") and `4a1232b4` (2026-03-23, "fix: only
  use 2 bytes for RPM"). This is not a dormant repo; it is being actively maintained through the
  same month as this research. Latest tagged release `v1.4.1` (2026-02-24), published via JitPack.
- **Repo health:** 233 stars, 39 forks, not archived, ktlint+detekt CI gates added 2026-02-24
  (`61828ca3`), dedicated `ObdDeviceConnectionTest.kt` covering concurrent-command serialization
  with a scripted stream test double — the same test strategy `FakeObdLink` in `:core:testing` is
  meant to enable.
- **Issue tracker:** 10 open issues. None request mode-22/custom-header support — this cuts both
  ways (nobody's proven it in the wild for this project, but also nobody's reported it broken).
  Two open issues are directly relevant and worth conceding up front: **#20 "BLE SUPPORT"** and
  **#33 "Receive speed from bluetooth"** — both open, unresolved, confirming BLE is not a shipped
  transport (see gap below). **#29 "Need for an initialization of the ELM to setup expected
  settings"** is also open — the library does not own the init sequence; that stays ours either
  way per `docs/01-build-plan.md` §2B, so this isn't a net loss.

## Conceded gaps (for R2/R3 cross-examination)

1. **No BLE transport.** `ObdDeviceConnection`'s constructor takes `InputStream`/`OutputStream`
   directly (`ObdDeviceConnection.kt:29-33`) — Classic-Bluetooth-shaped, matching open issue #20.
   The `:core:ble` agent must still write a GATT-notification-to-`InputStream` adapter (buffer
   fed by `onCharacteristicChanged`, `available()`/`read()` implemented over that buffer) exactly
   as it would for a hand-rolled protocol layer. The library saves nothing on the BLE side.
2. **Mode-22 and custom headers are unshipped**, as detailed above — budget for ~4 new command
   classes plus the `PidDefinition.request.Mode22` → `ATSH`/`ATCRA`/mode-22-request sequencing.
3. **Contrast candidate `obd-java-api` (pires) is dead**: archived 2017 (repo `archived: true`),
   last push 2017-07-16 despite higher star count (627 vs 233 — stale popularity, not health).
   Its `ObdCommand` (`commands/ObdCommand.java`) takes raw `InputStream`/`OutputStream` per call
   with no coroutine model and no built-in mutex — any single-flight enforcement is the caller's
   job from scratch. Not a viable candidate; mentioned only because the issue names it.

## Citations

- `eltonvs/kotlin-obd-api` repo metadata via GitHub API: `pushed_at: 2026-07-31T14:49:24Z`,
  `license.spdx_id: apache-2.0`, `archived: false`, `open_issues_count: 10`, `stargazers_count: 233`.
- `src/main/kotlin/com/github/eltonvs/obd/command/ObdCommand.kt` (lines 3-27) — abstract command
  shape (`mode`, `pid`, `handler`, `rawCommand`).
- `src/main/kotlin/com/github/eltonvs/obd/command/ATCommand.kt` — `ATCommand : ObdCommand()`,
  `mode = "AT"`.
- `src/main/kotlin/com/github/eltonvs/obd/command/at/Mutations.kt` — `SetHeadersCommand`,
  `SetEchoCommand`, `SetTimeoutCommand` as the pattern to replicate for `ATSH`/`ATCRA`.
- `src/main/kotlin/com/github/eltonvs/obd/command/Response.kt` (lines 9-33) — `ObdRawResponse`
  processor pipeline (whitespace/bus-init/colon stripping, `bufferedValue`).
- `src/main/kotlin/com/github/eltonvs/obd/command/Exceptions.kt` (lines 23-70) —
  `BadResponseException.checkForExceptions` typed-error mapping for `SEARCHING`, `NO DATA`,
  `STOPPED`, unsupported command, non-numeric response.
- `src/main/kotlin/com/github/eltonvs/obd/connection/ObdDeviceConnection.kt` (lines 29-33 ctor,
  42-47 `run`/`runWithReadPolicy` with `runMutex.withLock`, 79-85 `sendCommand`) — single-flight
  mutex, coroutine `suspend fun`, `InputStream`/`OutputStream` transport assumption.
- `src/main/kotlin/com/github/eltonvs/obd/command/engine/Engine.kt` — `SpeedCommand`,
  `RPMCommand`, `MassAirFlowCommand` as representative standard-PID command definitions.
- `src/test/kotlin/com/github/eltonvs/obd/connection/ObdDeviceConnectionTest.kt` — scripted
  `InputStream`/`OutputStream` test double proving single-flight serialization under test.
- Commit log (`git log` via GitHub API, `eltonvs/kotlin-obd-api`): `30014eb6` (2026-07-31),
  `82e0f850` (2026-07-30), `4a1232b4` (2026-03-23), `5ea8605b`/release `v1.4.1` (2026-02-24).
- Open issues (github.com/eltonvs/kotlin-obd-api/issues): #20 "BLE SUPPORT", #33 "Receive speed
  from bluetooth", #29 "Need for an initialization of the ELM to setup expected settings" — all
  open as of this research.
- GitHub code search (`repo:eltonvs/kotlin-obd-api CRA` / `ATSH`) — zero results, confirming no
  built-in custom-header support.
- Contrast: `pires/obd-java-api` repo metadata (`archived: true`, `pushed_at:
  2017-07-16T13:10:13Z`) and `src/main/java/com/github/pires/obd/commands/ObdCommand.java`
  (lines 27-65) — blocking `InputStream`/`OutputStream` per-call API, no coroutine/mutex model.

## Rebuttal

**1. Conceded, with a narrower refutation.** R3 is right and I was wrong to cite
`SetHeadersCommand` (`at/Mutations.kt`) as the ATSH/ATCRA precedent — its `pid = "H${value.command}"`
sends `ATH0`/`ATH1`, the header-*display* toggle, not a transmit-header setter. That citation was
an error, not a defensible reading. R3 also confirms `rawCommand` is a hardcoded two-field
`mode`/`pid` join with no `ObdRawCommand`-equivalent escape hatch anywhere under `command/` — so
there is no *supported* mode-22/custom-header feature, full stop. What survives: R3's own report
notes "a dev could subclass `ATCommand` with a literal `pid = "SH 07E1"` to fake it" — that's
still true and still mechanically works (`pid` is an unvalidated open `String`), but it's
undocumented-internals abuse, not a feature, and I retract the implication that a ready pattern
already does this. Score this constraint as R3 scored it, not as I originally argued.

**2. Conceded for `ObdDeviceConnection`; one narrower adapter shape survives.** R3 is correct that
`ObdDeviceConnection` owns `InputStream`/`OutputStream` directly and doesn't consume anything
shaped like `sendRaw()`, so wrapping it behind `ObdLink` means a second reassembly/mutex layer on
top of what `:core:ble` already guarantees — real, not "erased," duplication. The one shape that
avoids it: don't adopt `ObdDeviceConnection` at all. `command/Response.kt` (`ObdRawResponse`,
`ObdCommand.handleResponse`) and `command/Exceptions.kt` operate on plain `String`/`IntArray` —
zero transport coupling. We can call `ObdLink.sendRaw(command.rawCommand)` ourselves and feed the
returned string straight into `ObdRawResponse(value, elapsedTime)` → `command.handleResponse(...)`.
No stream shim, no second mutex. This is a real answer to the duplication problem, but it means
"adopting the library" now means only its parser/command layer, not its connection layer — which
folds directly into point 3.

**3. Quantified.** Once `ObdDeviceConnection` is off the table (point 2) and mode-22/headers are
conceded unsupported (point 1), what's left to adopt from kotlin-obd-api against R2's ~590 LOC /
~53 test-case estimate: the `Response.kt`/`Exceptions.kt` parser+exception-typing pair maps almost
1:1 onto R2's "tolerant response parser" line (~130 LOC, ~15 cases, R2's own "highest-value test
investment") and needs no adapter — genuine, high-confidence transfer. The six standard-PID
formulas (`engine/Engine.kt`, `temperature/Temperature.kt`, `pressure/Pressure.kt`) are worth
reading as a correctness reference, but R3 is right they return `ObdResponse`, not
`PidDefinition.parse: (ByteArray) -> Double` — so it's copy-the-scaling-math value against R2's
~80 LOC / ~12 cases, not drop-in, call it half credit. Init (~120 LOC), mode-22 framing (~150 LOC),
and the poll scheduler (~110 LOC) are unaffected — hand-written either way, exactly as R2 scoped.
Net: the library concretely buys roughly 150-190 of R2's 590 LOC (~25-30%), concentrated entirely
in the parser, not the 40%-weighted mode-22 constraint or the 25%-weighted transport constraint
where the rubric actually decides this.

**Final position: amended.** Not "adopt kotlin-obd-api as the base of `:core:protocol`" — that
claim doesn't survive R3's C1/C2 evidence or my own citation error. Amended to: **build custom per
R2's scope** (init, mode-22 framing, scheduler, all hand-written against `ObdLink`/`ObdRequest`
directly), **plus a narrow hybrid**: take a dependency on (or vendor, under Apache-2.0 with
attribution) kotlin-obd-api's `command/Response.kt` + `command/Exceptions.kt` for response
cleaning and typed-error classification, and use its standard-PID formulas as a reviewed reference
when writing the six `PidDefinition.parse` functions. This matches the "hybrid outcomes" language
in `docs/01-build-plan.md` §Phase 1 step 5 and R2's position almost exactly — the disagreement
that's left is narrow (parser reuse vs. full reimplementation of ~130 LOC), not architectural.
