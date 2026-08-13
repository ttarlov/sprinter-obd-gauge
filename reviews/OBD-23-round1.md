---
issue: OBD-23
round: 1
reviewers: [rev-correctness+rev-platform combined (Tier A, single-Opus economy — documented deviation from D4 two-agent matrix)]
verdict: changes-requested
gate: green
reviewed-commit: 7b57af1
covers: [OBD-23, OBD-48]
---

Tier-A review of `ble/23-48-reconnect-traffic`. Baseline 186/186 green. OBD-48's traffic
tap: clean (structurally observe-only, debug fence independently re-verified at the AAR
byte level — release contains zero ObdTraffic traces; note the app-level dex check is
currently vacuous because :core:ble is debugImplementation-only pre-OBD-25; the module AAR
check is the real one). OBD-23 reconnect: strong core (all 9 lenses examined; generation
gating, client accounting, debt isolation, backoff math all verified with probes) but two
findings block merge. **BRANCH PARKED at wave wind-down (budget ceiling); this record is
the round-2 fix brief.**

## Findings

**BLOCKER B1 — unattended retry has no exception boundary; link wedges in Scanning forever.**
BleObdLink.kt:281-290 (retry job) + :143 (linkScope has no CoroutineExceptionHandler).
runConnectAttempt() can throw non-BleLinkException throwables nothing wraps:
scanner.scan() (AndroidBleScanner.kt:99-105 catches only SecurityException; OEM stacks
throw IllegalStateException when the adapter dies), environment.isBluetoothEnabled()
(:507, SecurityException API 31+), device.connectGatt (AndroidGattTransport.kt:104-106,
unguarded). Pre-OBD-23 the only caller was a ViewModel scope (has a boundary); the
unattended retry does not. PROVEN by probe: throwing scanner on the retry sweep →
exception escapes linkScope; after 10 virtual minutes state=Scanning, autoReconnect=true,
reconnectJob=null, nothing scheduled, no log. Van scenario: overnight BT-stack restart at
a trailhead → process crash or permanent silent "Scanning…", every later key-on ignored.
FIX: runCatching around the job body → typed LinkState.Error + reschedule-or-disarm with
stated reason; CoroutineExceptionHandler on linkScope; any abnormal exit moves state off
Scanning. Add the missing third limitation to MODULE.md if any residue remains.

**MAJOR M1 — forgetRememberedDevice doesn't supersede an in-flight attempt.**
BleObdLink.kt:161-167 disarms + forgets but never bumps generation → an in-flight attempt
completes and openSession re-remembers the forgotten address at :419. PROVEN: state=Ready
remembered=[old address] forgets=1. Van scenario: dongle swap at a rest stop — Forget
mid-retry, the old dongle gets re-remembered, auto-reconnect now disarmed so the new unit
is never found. FIX: generation++ inside the same dispatcher block, matching disconnect().

**MINOR m1** — onSessionTerminated comment (:436-439) claims release-before-generation for
both callers; disconnect() does generation++ THEN release (:187-188). Invariant holds only
via no-suspension atomicity; fix the comment so a future suspending insertion isn't
falsely reassured.
**MINOR m2** — superseded attempt still burns a scan: gate fires after scanner.scan(),
never before (:334-339; window = the rememberedDevices.lastAddress() suspend at :205).
PROVEN: 2 scans where 1 owed. Costs Android's 5-per-30s scan budget. One-line pre-gate.
**MINOR m3** — give-up is observationally identical to still-retrying (:274-277 logs
only). LinkState is frozen; expose a module-level `retrying: StateFlow<Boolean>` (like
missingPermissions) so :app can offer Retry. "Never silent" bar currently unmet.
**MINOR m4 — BluetoothOff-terminal is a landmine, reclassify recoverable.** planConnect
reads isBluetoothEnabled() every retry (:507) and a BT-stack restart reports off for
seconds; first backoff is 1s so the module samples exactly that window → one bad read
permanently + silently disarms. PROVEN: adapter back after 10 min, link never returns.
Cost of recoverable is one isEnabled() read per backoff cycle (no radio). MODULE.md's
"user re-enabled BT" framing hides that the dominant trigger is not a user.

**NITs**: :269 `?: return` guard silent (same shape as B1's outcome — add narrative/log);
:138-142 "at most one job" comment true-for-wrong-reason (field nulled at :288 pre-body);
disconnect() cancels pending but not running attempts (radio sweeps on after user stop —
harmless to state, burns scan budget; note or fix).

## Mutation ledger (reviewer-run)
(a) skip retired-client release → KILLED (soak); (b) stale callback writes state → KILLED
×3; (c) drop settleDebts → KILLED (GattBridgeTest quiet-window); (c2 bonus) drop debt
booking → KILLED ×7 (note: the soak SURVIVED c2 — GattBridgeTest is the load-bearing debt
coverage, the soak's late-answer script is decorative for that claim); (d) zero jitter+cap
→ KILLED ×5; (e) tap suspends on hot path → SURVIVED (LOW today: TrafficLog.record is
non-suspend so a sink structurally cannot suspend; a BLOCKING sink is uncaught — future
file-sink work must add coverage); (f bonus) remove builder's self-caught stale-gate →
KILLED by exactly its regression test.

## Fix list (round 2)
- [ ] B1 exception boundary (+ test: throwing scanner mid-retry → typed Error, machine
      lives or disarms loudly; never silent Scanning)
- [ ] M1 forget bumps generation (+ test: forget mid-attempt → address NOT re-remembered)
- [ ] m2 pre-scan stale gate (+ scan-count assertion)
- [ ] m3 retrying StateFlow (+ test)
- [ ] m4 BluetoothOff recoverable (+ test: off-then-on within budget recovers)
- [ ] m1 comment fix; NITs at builder discretion (record declines)
- [ ] Re-run reviewer mutations a-f; all must stay killed; pristine tree verified
