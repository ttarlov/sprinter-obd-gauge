---
issue: OBD-17
round: 1
issues-covered: [OBD-17, OBD-18]
reviewers: [rev-correctness, rev-platform, rev-arch]
verdict: changes-requested
gate: green
reviewed-commit: 57fa2a3
---

Tier-A round 1 on `ble/17-18-scanner-gatt-bridge`. Three separate reviewer contexts; both
Opus reviewers ran source mutations (11 total, 4 survived — all in the GATT-double/scan-config
layers). One rev-arch finding rejected by orchestrator arbitration (see end).

### [BLOCKER] B1 — sendRaw timeout permanently desyncs the link (rev-correctness, PROVEN by probe)
**Where:** GattSession.kt:113-133 (send/finally); under-asserted at BleObdLinkTest.kt:333-344
**What:** timeout clears the awaiter but the command is still outstanding on the wire; the late
response is delivered to the NEXT command's awaiter. Probe: `0105` times out → `010B` returns
`41 05 5A`. Not self-correcting; every subsequent reading is the previous command's. A
`SEARCHING...` >2 s is normal van behavior.
**Fix:** track outstanding-response debt; deliverData discards that many responses (existing
"discarded unsolicited response" log is the sink) before honoring a new awaiter — or
drain-until-quiet before next write.
**Verify:** test — timeout `0105`, issue `010B`, emit both responses late, assert `010B`
returns `41 0B 64`. Fails today.

### [BLOCKER] B2 — in-flight GATT session untracked; cancelled/overlapping connect leaks the client permanently (rev-platform)
**Where:** BleObdLink.kt:173-204 (openSession), :196, :217-222 (closeSession)
**What:** session/scope assigned only after Ready. (1) CancellationException mid-handshake
escapes with no finally; scope is a ROOT SupervisorJob so caller cancellation doesn't reach
it — BluetoothGatt never closed, event loop parked forever. (2) disconnect() mid-handshake
is a no-op that the completing handshake later overwrites with Ready. (3) overlapping
connect() orphans the first client. Android grants ~32 GATT clients per process; each leak
is permanent. Trigger is mundane: viewModelScope death or withTimeout around connect().
**Fix:** assign session/scope BEFORE awaiting handshake; wrap in try/catch(Throwable) →
closeSession() + rethrow; Failed branch routes through closeSession() too.
**Verify:** cancel connect() mid-silent-handshake → transport.closeCount == 1; disconnect()
mid-handshake then release → terminal Disconnected, closeCount == 1.

### [MAJOR] M1 — isBluetoothEnabled() evaluated before the permission gate (rev-platform)
ConnectPreconditions constructor args evaluate eagerly → adapter read before permission
check; @RequiresPermission(BLUETOOTH_CONNECT) on 31+ → SecurityException escapes connect()
on the exact first-run path. Fix: short-circuit permissions first (or lazy bluetoothEnabled)
+ catch SecurityException in AndroidBleEnvironment returning false.
Verify: FakeBleEnvironment throwing SecurityException + missing perms → Error(PermissionDenied), no throw.

### [MAJOR] M2 — DataStore has no corruption handler (rev-platform)
One power-loss mid-write (normal in a vehicle) → CorruptionException on every read →
connect() throws forever. Fix: ReplaceFileCorruptionHandler { emptyPreferences() } +
.catch on the data flow in lastAddress(). Verify: garbage bytes in store file → null.

### [MAJOR] M3 — chunked-write ack path: unsafe timeout tolerance on API 33+ AND zero test bite (both reviewers)
(a) On ack timeout the code continues, but API 33+ returns ERROR_GATT_WRITE_REQUEST_BUSY for
overlapped writes → decoded as hard failure; tolerance doesn't exist on modern devices.
(b) Step.WRITE is one map key — late ack for chunk N completes chunk N+1's awaiter (off-by-one).
(c) rev-correctness mutation: removing the ack wait entirely passes all 96 tests.
Fix: never issue a write while one is unacked — ack timeout fails the command with
LinkError.Timeout (link stays Ready); decode API-33 return codes as BluetoothStatusCodes.
Verify: writeStatus=null on first chunk of >20-byte cmd at mtu 23 → exactly one write reaches
transport, sendRaw throws Timeout; plus failure-status test; re-run mutation 4 — must fail.

### [MAJOR] M4 — scan throttle (5 starts/30 s) unhandled and undocumented (rev-platform)
Code 6 → distinct LinkError (not Unknown) so :app can say "wait 30 s"; enforce min inter-scan
interval or cap in BleObdLink; document the budget in MODULE.md. Verify: ScanStateMachineTest
maps code 6 to the typed error.

### [MAJOR] M5 — pre-Android-12 scans need Location SERVICES on, not just the permission (rev-platform)
minSdk 26: with Location off, scan silently finds nothing → misreported DeviceNotFound.
Fix: BleEnvironment.isLocationServicesEnabled() (guarded <S) → ConnectPlanner fourth abort
with distinct error; or at minimum a MODULE.md Known limitation. Verify: ConnectPlannerTest
case sdk<31 + perm granted + location off.

### [MAJOR] M6 — scan configuration has no test bite (rev-correctness)
Mutations 5 & 6 survived: collapsing to one unfiltered pass, and killing the fallback sweep,
both pass all 96. FakeBleScanner discards `passes`. Fix: record passes + assert
[filtered, unfiltered] ordering; extract runPasses loop to a pure helper; tests for
"pass1 DeviceNotFound → pass2 runs" and "pass1 other failure → pass2 doesn't".
Verify: re-run mutations 5+6 — must fail.

### [MAJOR] M7 — awaitStep rejection branch untested (rev-correctness)
Mutation 7 survived: ignoring action() rejection passes all 96. openAccepted knob is dead.
Fix: per-call accepted knobs in FakeGattTransport + one test per step asserting typed error.
Verify: re-run mutation 7.

### [MAJOR] M8 — GATT double models only happy, synchronous, in-order callbacks (rev-correctness)
Production code handles disconnect-during-discovery and data-before-ack correctly (probed) but
nothing pins it. Fix: dropDuringDiscovery hook + notification-before-ack knob, one test each
(assert Error(Gatt(19)) + closeCount==1 for the former).

### [MAJOR] M9 — write/descriptor compat helpers compare BluetoothStatusCodes against GATT_SUCCESS (rev-platform, upgraded from NIT because it interacts with M3)
Use BluetoothStatusCodes.SUCCESS; log the non-success code (BUSY vs MISSING_PERMISSION vs
NOT_BOUND are diagnostically different).

### NITs (do unless one fights back)
- N1: post-timeout test should issue a second successful sendRaw (proves mutex released)
- N2: SerialProfileProbe.matchCandidate: firstOrNull → filter (duplicate-service clones)
- N3: @Volatile on AndroidGattTransport.listener/gatt (make MODULE.md's threading claim literally true)
- N4: onSessionTerminated cancels its own coroutine — set state before closeSession() or comment why safe
- N5: lastAddress() read twice per scanning connect — reuse the first read
- N6: one-line comment on testImplementation(:core:testing) referencing its charter (see rejected finding)

### Rejected finding (orchestrator arbitration)
rev-arch flagged `testImplementation(project(":core:testing"))` as a dependency-direction
violation. REJECTED: the ":core:model only" rule governs the production graph; :core:testing's
charter is "shared fakes consumed by every other module's tests." Test-scope use is the
intended design. N6 adds the clarifying comment so this isn't re-litigated.

## Praise where due (rev-correctness)
ResponseAssembler's exhaustive-split property test (every boundary + 250 random chunkings +
hostile 7-byte chunking) is the strongest test on the branch; three prompt-boundary mutations
all killed.

## Fix list
- [x] B1: timeout resync + test
- [x] B2: session tracked from creation; all exits close; disconnect-mid-handshake wins
- [x] M1: permission gate before adapter read + SecurityException belt-and-braces
- [x] M2: DataStore corruption handler + catch
- [x] M3: write flow-control redesign per above
- [x] M4: throttle: typed error + guard + doc
- [x] M5: location-services precondition (or documented limitation)
- [x] M6: scan-config tests that bite (mutations 5/6 die)
- [x] M7: awaitStep rejection tests (mutation 7 dies)
- [x] M8: GATT double realism hooks + 2 tests
- [x] M9: BluetoothStatusCodes decode
- [x] N1–N6

## Author responses (round 1)

Fix commits (no history rewrite): `684930f` production, `08d2902` tests, `d5c7003` docs.
Branch head `d5c7003`. Gate green; `:core:ble:testDebugUnitTest` 131 tests, 0 failures
(was 96).

| Item | Status | Where | Notes |
|---|---|---|---|
| **B1** | fixed `684930f` | `GattSession.kt` `responseDebt`, `send`/`deliverData` | A command whose terminator the transport accepted books a debt when abandoned (timeout **or** cancellation); `deliverData` discards exactly that many responses before honouring a new awaiter. Debt is booked only when the terminator went out — over-counting would eat the next good answer, time *that* command out and re-arm the debt forever, which is worse than the bug. Tests: the probe you described (`a late response to a timed-out command is never handed to the next command`), plus repeated abandonment and the cancellation case. Reverting `responseDebt++` fails 5 tests. |
| **B2** | fixed `684930f` | `BleObdLink.kt` `openSession`/`release`/`generation` | Session and scope are assigned **before** the handshake is awaited; `runCatching { open() }` releases and rethrows on cancellation; the Failed branch releases too. `release()` clears the tracked refs only if they still point at the session it is closing, so an overlapping connect cannot null out its successor. A `generation` counter (bumped by every connect/disconnect) gates every state publish, so disconnect-mid-handshake is terminal. `FakeGattTransport.close()` is now idempotent like the real one, so `closeCount` means "clients returned". Tests: cancelled handshake, disconnect mid-handshake, overlapping connect, sendRaw mid-handshake. Reverting the early tracking fails 16 tests. |
| **M1** | fixed `684930f` | `BleObdLink.planConnect`, `AndroidBleEnvironment` | Preconditions are gathered in a function, not eager constructor args: `mayQueryRadio = supported && missing.isEmpty()` guards both the adapter and the location read. `AndroidBleEnvironment` also wraps the adapter reads in `runCatching` → "can't tell" reports as "no". Test asserts `environment.adapterReads == 0` against a fake that throws `SecurityException`. |
| **M2** | fixed `684930f` | `BleModule`, `DataStoreRememberedDeviceStore` | `ReplaceFileCorruptionHandler { emptyPreferences() }` on the production store, `.catch { emit(null) }` in `lastAddress()`, and `remember`/`forget` are best-effort (`runCatching`) so a failed write cannot fail a connect. Two tests: a garbage `.preferences_pb` reads as null, and an unwritable store does not throw. |
| **M3** | fixed `684930f` | `GattSession.writeCommand`, `handleEvent` | (a) The ack timeout no longer continues — it fails the command with `LinkError.Timeout`, link stays `Ready`. (b) `writeAckDebt` absorbs the late ack so it cannot complete the next command's chunk awaiter. (c) API-33 return decoding moved to `BluetoothStatusCodes` (M9). Tests: unacked first chunk → exactly one write reaches the transport and `Timeout` is thrown; failure status → `Gatt(3)`; late ack → next command unaffected. **Mutation 4 now fails (4 tests).** |
| **M4** | fixed `684930f` | `ScanBudget`, `ScanStateMachine.THROTTLED`/`isThrottled`, `AndroidBleScanner.budgetedPass` | Budget enforced *before* the sweep (5 starts / 30 s, pure, clock injected), throttled outcomes recognisable via `isThrottled`, budget documented in MODULE.md. **Deviation:** the error remains a `LinkError.Unknown` with a stable prefix rather than its own variant — `LinkError` is a frozen `:core:model` contract and `:core:model` is not mine to change (OWNERSHIP: orchestrator). Flagging for an orchestrator decision if a dedicated variant is wanted; the predicate is the supported match in the meantime. Logged as a Known limitation. |
| **M5** | fixed `684930f` | `BleEnvironment.isLocationUsableForScan`, `ConnectPlanner` | Fourth abort, ordered *after* the remembered-device check because `ConnectPlan.Direct` never scans and must not be blocked by it. `LocationManager.isLocationEnabled` is API 28+, so there is a provider-based fallback for 26–27. Same frozen-contract caveat as M4 (`ConnectPlanner.LOCATION_OFF`). Three planner tests. |
| **M6** | fixed `08d2902` | `ScanPassPolicy.kt`, `ScanPassPolicyTest`, `FakeBleScanner.lastPasses` | `runScanPasses` + `worthAnotherPass` extracted as pure functions; 11 tests covering the plan shape, its timeouts, pass-1-not-found → pass 2, pass-1-found → stop, pass-1-failed-for-a-reason → stop, and the budget. `FakeBleScanner` records the plan and `BleObdLinkTest` asserts `[filtered, unfiltered]`. **Mutations 5 and 6 now fail (4 and 2 tests).** |
| **M7** | fixed `08d2902` | `FakeGattTransport` accept knobs, `GattBridgeTest` | Per-request refusal knobs (`openAccepted`, `discoverAccepted`, `notificationsAccepted`, `mtuAccepted`, `writeAccepted`); one test per step asserting the typed error *and* that the client is still released. **Mutation 7 now fails (2 tests).** |
| **M8** | fixed `08d2902` | `FakeGattTransport.dropDuringDiscovery` / `notifyBeforeAck` | Drop-during-discovery asserts `Error(Gatt(19))` **and** `closeCount == 1`; notification-before-ack asserts the response still lands. |
| **M9** | fixed `684930f` | `AndroidGattTransport.acceptedWrite` | Compares against `BluetoothStatusCodes.SUCCESS` and logs the refusing code, so BUSY / MISSING_BLUETOOTH_CONNECT_PERMISSION / DEVICE_NOT_BONDED stay distinguishable at bring-up. Untestable headlessly by design (thin adapter) — flagged for OBD-22. |
| **N1** | done `08d2902` | The B1 test issues a successful `010B` after the timeout, which is exactly the mutex-released proof. |
| **N2** | done `684930f` | `matchCandidate` now filters all services with the candidate UUID instead of taking the first. |
| **N3** | done `684930f` | `@Volatile` on `AndroidGattTransport.gatt`/`listener`. |
| **N4** | done `684930f` | `onSessionTerminated` publishes the state *before* releasing, with the reason in the KDoc — the release cancels the scope the callback is running in. |
| **N5** | done `684930f` | `connect()` reads `lastAddress()` once and passes it to `scanAndConnect`. |
| **N6** | done `684930f` | Comment on `testImplementation(project(":core:testing"))` in `core/ble/build.gradle.kts` citing the charter and this record. |

### Mutation re-runs (all four previously-surviving mutations now die)

| # | Mutation | Round 1 | Now |
|---|---|---|---|
| 4 | remove the write-ack wait entirely | survived | **FAILS** — 4 tests |
| 5 | collapse the scan plan to one unfiltered pass | survived | **FAILS** — 4 tests |
| 6 | never run the fallback sweep | survived | **FAILS** — 2 tests |
| 7 | ignore an `action()` rejection in `awaitStep` | survived | **FAILS** — 2 tests |

Two extra self-checks on the blocker fixes: removing `responseDebt++` fails 5 tests; tracking
the session only once Ready fails 16.

### Structural note

`BleObdLinkTest` outgrew detekt's `LargeClass` threshold once the regression tests landed, so it
is split along the issue seam: `BleObdLinkTest` keeps OBD-17 (preconditions, discovery, fast
path) and the new `GattBridgeTest` takes OBD-18 (the bridge). Shared fixture helpers moved to
`testing/LinkTestSupport.kt`. No test was dropped in the move.
