# :core:ble

Android library. Implements `ObdLink` (`:core:model`) over Android BLE/GATT. Hilt annotations
are permitted here per `DECISIONS.md` D2 (the only `core` module besides `:app`).

Delivered in OBD-17 (permissions + scanner + remembered device) and OBD-18 (GATT serial
bridge). **This module moves strings and nothing else** — no ELM327 command knowledge, no
parsing, no PID awareness (`docs/01-build-plan.md` §2C). `:core:protocol` runs the init
sequence over `sendRaw` once the link reports `Ready`.

## Public surface

| Type | Role |
|---|---|
| `BleObdLink` | The `ObdLink` implementation. Plus `missingPermissions`, `retrying`, `rememberedDevice()`, `forgetRememberedDevice()` for `:app`. |
| `BleConfig` | Timeouts, MTU request, scan-pass plan. Injected, so tests and `:app` can retune. |
| `BleLinkException(error: LinkError)` | What `sendRaw` throws. `IOException` subclass; the payload is the same typed `LinkError` vocabulary `LinkState.Error` uses. |
| `BleLogger` / `AndroidBleLogger` | Connect/probe narrative. Logcat tag `ObdBle`. |
| `BlePermissionPolicy` | Which runtime permissions are needed at a given API level. Pure. |
| `ConnectPlanner`, `ConnectPreconditions`, `ConnectPlan` | Direct-connect vs scan vs abort. Pure. |
| `ReconnectPolicy`, `Recoverability` | OBD-23: which failures are worth retrying, and the backoff schedule. Pure. |
| `gatt.ResponseAssembler` | Notification chunks → `>`-terminated responses. Pure. |
| `gatt.SerialProfileProbe`, `SerialProfile`, `CandidateProfile` | UUID probing. Pure. |
| `gatt.GattTransport` / `GattTransportFactory` / `GattEvent` | The seam over `BluetoothGatt`. |
| `scan.BleScanner`, `ScanStateMachine`, `DongleFilter`, `DiscoveredDevice`, `ScanOutcome` | Scanning, and its decision logic. |
| `permission.BleEnvironment` | BLE support / adapter state / missing permissions. |
| `store.RememberedDeviceStore`, `BluetoothAddress` | Fast-path persistence and address validation. |
| `di.BleModule`, `di.LinkDispatcher` | Internal bindings. **`ObdLink` is deliberately not bound here** — `:app` does that per flavor in OBD-25. |
| `console.ConsoleSession`, `console.ConsoleEntry`, `console.RememberedDeviceForgetter` | OBD-19's debug-console REPL controller. Pure logic over `ObdLink` (no Android imports); `app/src/debug/` is the only consumer. |
| `traffic.TrafficLog`, `traffic.TrafficEntry`, `traffic.RxFate` | OBD-48's headless capture tap. Observed only; the logcat sink behind it exists in the **debug variant only**. |

`BleObdLink` is constructor-injected; `:app` only needs
`@Binds fun bind(impl: BleObdLink): ObdLink`.

## `console` package (OBD-19)

`ConsoleSession` is the controller behind the debug-build-only "OBD Console" raw AT-command
REPL (`app/src/debug/`, see app/MODULE.md) — the Sprint 2 hardware-bring-up tool, not a
production feature. It is typed against `ObdLink` (not `BleObdLink`), so it is exercised
against `FakeObdLink` on the JVM exactly like everything else in this module; the debug-only
Activity is the only place it meets Android.

- `ConsoleEntry` — a sealed scrollback line: `CommandSent`, `ResponseReceived`,
  `LinkStateChanged`, `ErrorOccurred`. Every variant is timestamped from an injected
  `java.time.Clock`.
- `ConsoleSession(link, scope, forgetter, historyLimit = 500, clock)` — `sendCommand` appends a
  `CommandSent` then exactly one of `ResponseReceived`/`ErrorOccurred`; `connect`/`disconnect`
  passthrough to `link`; a background collector on `scope` logs every `link.state` value
  (including the link's ambient state at construction — deliberately not filtered, see the
  class's KDoc for why a "skip the first value" filter is a race, not a rule) as a
  `LinkStateChanged` entry. `entries`/`linkState`/`commandInFlight` are `StateFlow`s; the
  scrollback is trimmed to `historyLimit`, oldest dropped first.
- **In-flight policy: refuse, don't queue.** `FakeObdLink` enforces half-duplex strictly (throws
  on an overlapping `sendRaw` — see its own KDoc), so a second `sendCommand` call while one is
  outstanding is refused immediately with an `ErrorOccurred` entry rather than queued — a human
  typing raw commands at a dongle benefits more from instant, visible feedback than from a
  silent queue. The `:app` UI additionally disables its send affordance while `commandInFlight`
  is true; the refusal is a safety net (chip-taps, test races), not the primary defense.
- **Timeout handling is `ObdLink`-implementation-agnostic.** `ObdLink.sendRaw`'s KDoc leaves the
  timeout exception implementation-defined: `FakeObdLink` lets `TimeoutCancellationException`
  escape, `BleObdLink` translates its own timeout into `BleLinkException(LinkError.Timeout)`
  first. `sendCommand` catches `TimeoutCancellationException` specifically (logs it, does not
  rethrow — a `TimeoutCancellationException` is also a `CancellationException`, and rethrowing
  it would incorrectly cancel the caller) ahead of a plain `CancellationException` catch (which
  *does* rethrow, to keep structured concurrency intact for a genuine scope teardown) ahead of a
  generic `Exception` catch for everything else (`BleLinkException` included).
- `forgetRememberedDevice()` delegates to an optional `RememberedDeviceForgetter` — a
  one-method seam, not a dependency on `BleObdLink`, since `ObdLink` itself has no such concept.
  `:app`'s DI wiring supplies `RememberedDeviceForgetter { bleObdLink.forgetRememberedDevice() }`
  alongside the same `BleObdLink` instance bound to `ObdLink`.
- `recordError(message, command = null)` is public and non-suspending: it exists for the
  `:app` edge to log outcomes `:core:ble` cannot see itself — e.g. a denied runtime permission,
  reported by `ConsoleActivity`'s `ActivityResultContracts` callback.

## `traffic` package (OBD-48) — headless capture

Hardware session 1 lost a whole drive capture because the OBD-19 console only renders traffic on
a screen and the phone locked mid-drive (`docs/hardware/session-2026-08-12.md`). `TrafficLog` is
the fix: an ordered tap that records **every TX line, every RX line and every link-state
transition** to logcat, so a capture runs screen-off.

```
adb logcat -c && adb logcat -s ObdTraffic
04:04:31.482 ==  Ready
04:04:31.501 TX  0105
04:04:31.622 RX  41 05 86\r41 05 86\r41 05 86\r\r
04:04:33.640 --  no response to "010B" within 2s
04:04:33.901 RX  NO DATA\r\r   [dropped: paid response debt]
```

- **Pure tap.** Nothing in the module reads a return value from it, branches on one, or waits on
  one. `TrafficTapTest` runs the same script twice — once tapped, once with `TrafficLog.NONE` —
  and asserts identical responses, identical wire chunks and identical client-close counts.
- **Complete, including the drops.** A response discarded to pay a response debt, or an
  unsolicited one, is captured and marked (`RxFate`) — those are exactly the pathologies a drive
  capture exists to catch, and a capture that showed only delivered responses would show a gap
  with no reason beside it.
- **Ordered.** Every call site is on the link dispatcher, so TX/RX/state interleave truthfully.
  `BleObdLink.publish` is the sole writer of the state flow, which is what makes "no transition
  can be missed" structural rather than a convention.
- **One entry, one logcat line.** ELM327 responses are full of `\r`; they are escaped, not
  emitted raw, so a captured response cannot break into records that no longer say what they
  answered.
- **Debug fence.** `defaultTrafficLog()` is declared once per build type: `src/debug/` returns
  `LogcatTrafficLog`, `src/release/` returns `TrafficLog.NONE`. Not a `BuildConfig.DEBUG` branch
  — `:app` builds with `isMinifyEnabled = false`, so a runtime branch would leave the sink, the
  `ObdTraffic` tag and every format literal in the release dex. Verified by inspecting both
  AARs' `classes.jar`, with the debug one as the positive control (same treatment OBD-19's
  console got). `:core:ble`'s detekt `source` lists the two build-type source sets explicitly,
  since the non-variant-aware `detekt` task defaults to `src/main/kotlin` only.
- `TrafficFormat` is tested from `src/testDebug/` — a test of it under `src/test/` would fail to
  compile the release unit-test variant, which is itself part of the fence's proof.

## Architecture

Everything that can be pure is pure, and the Android classes are literal adapters. That split
is what makes a Bluetooth module reviewable and lets its whole test suite run headless:

```
BleObdLink ── ConnectPlanner (pure) ── BleEnvironment ──→ AndroidBleEnvironment
     │                                 BleScanner     ──→ AndroidBleScanner ── ScanStateMachine (pure)
     │                                                                       └─ DongleFilter (pure)
     └── GattSession ── SerialProfileProbe (pure)
                     ├─ ResponseAssembler (pure)
                     └─ GattTransport ──────────────────→ AndroidGattTransport (BluetoothGattCallback)
```

## Threading model

- One **link dispatcher** (`@LinkDispatcher`, a single-thread executor in production, a
  `StandardTestDispatcher` in tests). Every mutation of link state happens on it: the state
  flow, the session reference, the response assembler, every pending waiter.
- GATT callbacks arrive on **binder threads** and do exactly one thing: `trySend` a `GattEvent`
  onto an unbounded channel. A single consumer coroutine on the link dispatcher drains it.
  Callback state and coroutine state are therefore the same state, on one thread — no locks, no
  visibility questions.
- `connect`, `disconnect` and `sendRaw` hop onto the dispatcher with `withContext`. `state` is a
  `StateFlow` and is safe to read from anywhere.
- **Single-flight** is enforced by a `Mutex` *inside* `sendRaw`, per the `ObdLink` contract:
  concurrent callers are serialized transparently, and the second command is not written until
  the first has been answered, has timed out, or the link has dropped. A cancelled caller
  releases the mutex the same way.
- Every framework request has a timeout, because the BLE stack's characteristic failure is not
  an error code but silence.
- **Abandoned commands leave a debt.** A timeout does not un-send a command — a `SEARCHING...`
  that runs past two seconds is ordinary, and the answer still arrives. `GattSession` counts
  those owed responses (and owed write acks) and discards exactly that many before honouring a
  new awaiter. Without it the late answer satisfies the *next* command's wait and every reading
  from then on is the previous command's: a permanent off-by-one that never self-corrects.
- **The session is tracked from creation, not from Ready.** Android grants a process roughly 32
  GATT client interfaces and only `close()` returns one, so a handshake cancelled by a dead
  scope must still be reachable. A connect generation counter means a `disconnect()` issued
  mid-handshake wins rather than being overwritten by the attempt it cancelled.
- **Write flow control**: a chunk is never issued while another is unacked. An ack that never
  arrives fails the whole command with `Timeout` (the link stays `Ready`) rather than writing
  over it — API 33+ rejects an overlapped write with `ERROR_GATT_WRITE_REQUEST_BUSY`, so
  "tolerating" a missing ack is data loss on every modern device.

## Connect flow

1. Preconditions → `ConnectPlanner`: BLE support, then **permissions**, then adapter state
   (permissions before adapter because on Android 12+ both reading adapter state and scanning
   throw `SecurityException` without them, and "PermissionDenied" is the more actionable
   report), then remembered address.
2. **Remembered-device fast path** — a stored address connects directly, no scan. If it fails,
   it falls back to scanning rather than giving up; the memory is an optimisation, never a
   constraint. The address is only stored after a link reaches `Ready`.
3. **Location services** (below Android 12 only): with them off, a scan returns nothing at all —
   no error, no callback — which would otherwise be misreported as `DeviceNotFound` and send the
   user hunting a dongle fault that does not exist. The fast path is unaffected: it never scans.
4. Scan: pass 1 filtered by candidate service UUIDs (cheap, battery-friendly), pass 2 unfiltered
   with software name/UUID matching — only if pass 1 found nothing, because plenty of clones
   advertise a name and no service UUID. A pass that failed for a *reason* is not retried.
   `preferredAddress` (the remembered one) wins over the filter.
5. `connectGatt` → `discoverServices` → UUID probe → `setCharacteristicNotification` + **CCCD
   write** → `requestMtu(512)` → `Ready`.

## Auto-reconnect (OBD-23)

`connect()` does not mean "try once", it means **be connected**. It arms auto-reconnect, and a
recoverable failure — now or eight hours from now — schedules another attempt instead of parking
for good. Each retry re-runs the whole connect flow above, so the remembered-device fast path
*is* the resume path and the scan fallback *is* the resume-on-found path; there is no second
connect implementation to keep in step with the first.

**The classification rule is: retry unless a retry is provably futile** — futile meaning the
module would be asking the same question of the same unchanged world.

| Cause | | Why |
|---|---|---|
| `Unknown("dongle disconnected")`, `Gatt(status)` | retry | key-off, out of range, stack hiccup |
| `DeviceNotFound`, `Timeout` | retry | the van is off, or parked out of range |
| `BluetoothOff` | retry | **usually a stack restart, not a person** — see below |
| `Unknown(ScanStateMachine.THROTTLED)` | retry | the rolling 30 s window drains by itself |
| `Unknown(ReconnectPolicy.ABNORMAL_ATTEMPT)` | retry | an attempt that threw; the budget bounds the believing |
| `PermissionDenied` | stop | the app may not even look until it is granted |
| `Unknown(ConnectPlanner.LOCATION_OFF / BLE_UNSUPPORTED)` | stop | a settings screen, or a phone with no radio |
| `disconnect()`, `forgetRememberedDevice()` | stop | the user said so |

Round 1 stated this as "anything only a human can fix is terminal", which reads well and is
wrong in one important place. The dominant cause of `BluetoothOff` is **not** a user reaching
for the toggle — it is the Bluetooth stack restarting, which reports the adapter off for a few
seconds. `planConnect` re-reads `isBluetoothEnabled()` on every attempt and the first backoff is
one second, so the module sampled almost exactly that window: one unlucky read disarmed
auto-reconnect permanently and silently, and the link never returned even after the adapter did.
Retrying costs one `isEnabled()` read per backoff cycle and touches no radio.

`PermissionDenied` stays terminal on the narrower ground that the app may not look at all until
it is granted, and `:app` owns that dialog and re-calls `connect()` on the grant — the module is
not the thing waiting.

- **Backoff**: 1 s doubling to a 60 s ceiling, ±25 % jitter. The jitter is not thundering-herd
  insurance (there is one dongle) — it desynchronises from *its* cycle, so a dongle that reboots
  on a fixed period and a phone that retries on a fixed period cannot lock into a phase where
  every attempt lands in the dead window and stay there.
- **Budget**: 720 attempts ≈ 12 hours, then it parks in `LinkState.Error` and waits to be asked
  again. Bounded rather than infinite for two reasons: a dongle left at home should stop costing
  radio eventually, and an unbounded self-rescheduling delay makes a virtual clock
  non-terminating — which would quietly turn any future test's `advanceUntilIdle()` into a hang.
- **No `Reconnecting` state, but `retrying` tells you.** `LinkState` is a frozen `:core:model`
  contract, so the wait is spent in `LinkState.Error(cause)` — the cause stays visible the whole
  time — and the attempt itself moves through `Connecting`/`Scanning` like any other connect.
  That made "still trying, sit tight" and "gave up, press Retry" observationally identical, so
  `BleObdLink` exposes a module-level `retrying: StateFlow<Boolean>` alongside
  `missingPermissions`. **`state is Error && !retrying` is exactly the Retry affordance**;
  neither half means it alone.
- **Every attempt has an exception boundary.** `scanner.scan` (OEM stacks throw
  `IllegalStateException` when the adapter dies mid-sweep — `AndroidBleScanner` guards only
  `SecurityException`), `isBluetoothEnabled` (`SecurityException` on API 31+) and `connectGatt`
  can all throw. Before OBD-23 the only caller was a `viewModelScope`, which has a boundary; a
  scheduled retry has none, and the throw left the link on `Scanning` forever with nothing
  scheduled and nothing logged. An abnormal exit is now one more failed attempt: typed
  `Error(Unknown(ReconnectPolicy.ABNORMAL_ATTEMPT …))`, then reschedule-or-disarm out loud.
  `CancellationException` is rethrown untouched. A `CoroutineExceptionHandler` on `linkScope` is
  the second layer, for when the recovery path itself throws.
- **The generation counter gates the retry too.** `scheduleReconnect` refuses a stale attempt
  before anything else, because booking a retry *is* work and a stale attempt does no work. The
  case that proves it is not decorative: when the winning attempt has also failed, the state flow
  already holds an `Error`, so every later guard waves the loser through — and unchecked it books
  a second retry, orphaning the first scheduled job rather than replacing it. Pinned by
  `ReconnectTest."a stale attempt that finishes after the winner has already failed books
  nothing"`, which was written *because* an earlier version of that test failed to kill the
  mutation.

### Scan budget

Android allows an app 5 `startScan` calls per rolling 30 seconds; exceed it and the app gets
nothing back for the rest of the window. Two passes per connect means three attempts in half a
minute is enough to trip it — exactly what a user does when the dongle is not plugged in. So
`ScanBudget` refuses the sweep before it is spent rather than discovering the throttle
afterwards, and the resulting error is recognisable via `ScanStateMachine.isThrottled` so `:app`
can say "wait 30 seconds" instead of "no dongle found".

## UUID probe order

Candidates are tried in this order; the chosen profile is logged (`serial profile chosen: …`).

| # | Family | Service | Notify | Write |
|---|---|---|---|---|
| 1 | Veepeak / vLinker / Vgate | `FFF0` | `FFF1` | `FFF2` |
| 2 | FFF0, single characteristic | `FFF0` | `FFF1` | `FFF1` |
| 3 | HM-10 clone, split | `FFE0` | `FFE1` | `FFE2` |
| 4 | HM-10 transparent UART | `FFE0` | `FFE1` | `FFE1` |
| 5 | LELink family | `18F0` | `2AF0` | `2AF1` |
| 6 | Nordic UART Service | `6E400001-…` | `6E400003-…` | `6E400002-…` |

Fallback: the first service that is not Generic Access/Attribute, Device Information, Battery
or Nordic DFU and exposes both a notifiable and a writable characteristic (which may be the
same one). Logged as `via=Fallback`.

**Write type is chosen per characteristic**, never hardcoded: `WRITE_TYPE_NO_RESPONSE` when the
characteristic advertises it, `WRITE_TYPE_DEFAULT` otherwise — some ELM327 clones reject
`DEFAULT` writes outright, and the failure mode is silent.

## Response reassembly

`ResponseAssembler` accumulates until `>` and returns the response with the prompt stripped and
non-empty lines joined by `\r` — byte-identical in shape to what `FakeObdLink` replays, so
`:core:protocol` cannot tell the fake from the real link. `\r\n` collapses; non-printable bytes
(NUL padding, high-bit noise) are dropped; **a prompt with no content in front of it is a stray
prompt and is discarded, not delivered as an empty response** — otherwise a leftover prompt
would satisfy the next command's wait and shift every response by one, which is the project's
worst failure mode (plausible but wrong). Command echo is preserved: stripping it is protocol
knowledge.

## Permissions

Declared in this module's manifest: `BLUETOOTH_SCAN` (with `neverForLocation`),
`BLUETOOTH_CONNECT`; legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`/`ACCESS_FINE_LOCATION` capped at
`maxSdkVersion=30`. `bluetooth_le` is `required="false"` — a library module should not decide
Play Store filtering for `:app`, and BLE support is checked at runtime anyway.

This module **never requests** permissions: it has no UI and no Activity. It reports what is
missing (`BleObdLink.missingPermissions`) and parks a connect attempt in
`LinkState.Error(PermissionDenied)`. Prompting is `:app`'s job.

## Tests

194 JVM tests, no device, no Robolectric — the `GattTransport`/`BleScanner`/`BleEnvironment`
seams mean nothing under test needs an Android runtime. 188 run in both build variants; the six
`TrafficFormatTest` cases live in `src/testDebug/` because their subject does.

- `GattBridgeTest` (38) — handshake, probe, CCCD/MTU, single-flight, timeout and the response
  debt, cancellation, refused requests, drops mid-handshake and mid-response.
- `ReconnectTest` (25, OBD-23) — every transition of the reconnect machine on virtual time: the
  drop→retry loop, the doubling schedule and its ceiling, the reset on success, key-off/key-on
  with no user action, resume-on-found after the remembered address stops answering, each way of
  stopping (user disconnect, forget, terminal cause, spent budget) and the re-arm after one, the
  jitter band through the real link, three generation-invariant cases, and the round-2 set: a
  sweep that throws (reported and retried, never wedged in `Scanning`), one that throws with the
  budget spent (disarms out loud), a recovery that itself throws (the handler net), forget
  landing mid-attempt, a superseded attempt not burning a scan, `retrying` tracking the machine,
  and an adapter that comes back within the budget.
- `BleObdLinkTest` (15), `ResponseAssemblerTest` (15) — preconditions/discovery/remembered-device
  fast path; fragmentation, including a property test asserting that *any* chunking of a
  transcript reassembles identically, and an exhaustive every-split-point test.
- `ReconnectPolicyTest` (10, OBD-23) — the classification rule case by case (including a
  tripwire on `ConnectPlanner`'s own abort messages, which the policy string-matches, and
  `BluetoothOff` pinned on its own because its reclassification is not self-evident), the backoff
  schedule, the ceiling, overflow at `Int.MAX_VALUE`, and the jitter band over 200 samples.
- `TrafficFormatTest` (6) + `TrafficTapTest` (5), OBD-48 — line format and escaping; tap order,
  captured discards, and a tapped-vs-untapped run asserting the tap changes nothing.
- `ReconnectSoakTest` (2, OBD-23) — fifty scripted drop/recover cycles (see its KDoc), plus the
  never-recovers case that must stop at its budget rather than run forever.
- `SerialProfileProbeTest` (13) — every candidate family, the fallback, write-type selection.
- `ConsoleSessionTest` (11, OBD-19) — against `FakeObdLink`: command/response round trips in
  order, the ambient link state logged at construction, a timeout logged as an error entry
  (session usable after), state transitions (including a mid-response disconnect), the
  historyLimit bound, the refuse-don't-queue in-flight policy, `forgetRememberedDevice` with and
  without a forgetter wired, `recordError`.
- `ScanStateMachineTest` (11), `ScanPassPolicyTest` (11), `ConnectPlannerTest` (10),
  `RememberedDeviceStoreTest` (10), `BlePermissionPolicyTest` (6), `DongleFilterTest` (6).

## Known limitations

- **Unverified against real hardware.** The candidate UUID list, the name-prefix list and the
  MTU behaviour are all informed guesses until OBD-22 puts this in front of the actual Veepeak
  OBDCheck BLE+. The fallback probe exists precisely because that list will be wrong for
  someone.
- **Auto-reconnect keeps the link alive, not the process.** Retries run on the link dispatcher
  and stop with it: if Android kills the process, or the app is backgrounded long enough for the
  dispatcher to be starved, nothing here brings the link back. That is OBD-24's foreground
  service, and until it lands the reconnect budget is bounded by process lifetime in practice
  rather than by its 12-hour arithmetic.
- **Auto-reconnect notices the adapter coming back only on its next tick.** There is no
  `BluetoothAdapter` state receiver, so a re-enabled radio is discovered by the next scheduled
  attempt rather than immediately — up to the 60 s ceiling of latency once the backoff has run
  out. A receiver would make it instant and belongs at the `:app` edge where the lifecycle is.
- **`disconnect()` cancels a *pending* retry, not one already running.** A sweep already in
  flight finishes and burns its scan-budget slot; the result is discarded by the generation gate,
  so state is unaffected. Left as-is deliberately: cancelling an attempt mid-`connectGatt` risks
  leaking the GATT client that `openSession`'s structure exists to always close, and Android
  gives a process about 32 of them. Round-1 NIT, declined with this reasoning.
- **A blocking `TrafficLog` sink is not covered.** `TrafficLog.record` is non-suspending, so a
  sink structurally cannot suspend the link — but one that blocks (a file write, an upload) would
  stall the link dispatcher, and no test catches that today. The debug logcat sink does not
  block. **Anything adding an on-device file sink must add that coverage first.**
- **No foreground service** — OBD-24.
- `AndroidGattTransport`, `AndroidBleScanner` and `AndroidBleEnvironment` have no unit tests by
  design: they contain no decisions, only translation. Bugs there are translation bugs and are
  the target of the OBD-22 hardware session.
- A remembered device is not forgotten after a failed direct connect (it falls back to scanning
  instead). One bad connect out of range should not throw away a good optimisation.
- **The scan throttle and location-services aborts ride on `LinkError.Unknown`** with stable
  message prefixes (`ScanStateMachine.THROTTLED`, `ConnectPlanner.LOCATION_OFF`) rather than
  dedicated variants. `LinkError` is a frozen `:core:model` contract — giving these their own
  cases needs an orchestrator decision logged in `DECISIONS.md`, which is out of this module's
  ownership. The predicates (`ScanStateMachine.isThrottled`) are the supported way to match them.
- The response/write-ack debt assumes the dongle answers abandoned commands **in order**, which
  is true of a half-duplex ELM327 and is the only assumption available without a request tag on
  the wire. A dongle that silently drops a command it received would leave the debt unpaid until
  the next reconnect; OBD-22 is where that gets observed on real hardware.
- **Test gotcha for anything that collects a `StateFlow` on `TestScope.backgroundScope`**
  (`ConsoleSessionTest` is the first case of this in the module): in this project's
  `kotlinx-coroutines-test` version, a `backgroundScope` job's *first* dispatch — and, it
  appears, any dispatch not preceded by an actual suspension in the calling coroutine — is not
  reached by a bare `advanceUntilIdle()`/`runCurrent()` called synchronously from the test body;
  an explicit `yield()` immediately before the drain is what actually lets it run (confirmed by
  isolated repro, not just observed in this suite). `ConsoleSessionTest`'s `settle()` helper is
  that `yield()` + `advanceUntilIdle()` pair — reuse the pattern rather than plain
  `advanceUntilIdle()` for any future `backgroundScope`-based collector test.
