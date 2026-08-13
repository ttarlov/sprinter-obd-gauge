---
issue: OBD-24
round: 1
reviewers: [combined correctness+platform (Tier B, Opus)]
verdict: changes-requested
gate: green
reviewed-commit: 5e22275
covers: [OBD-24, OBD-27]
---

Tier-B review of `ui/24-27-service-badge`. Baseline 169/169 green. OBD-27 badge work:
clean and well-pinned (mutation e — catalog filtered to verified-only — caught by 30
tests; Roborazzi diffs pixel-verified as exactly two 22×22 badge regions per frame,
picker chrome byte-identical). OBD-24 service: one likely launch-crash blocker + a
restart-storm major + a wrong Doze rationale; every device-required AC still unchecked.
**BRANCH PARKED at wave wind-down (budget ceiling); this record is the round-2 fix brief.**

## Findings

**B1 BLOCKER — FGS `connectedDevice` type check is a GRANTED-permission check → crash on
fresh install, API 34+ (demo flavor included).** Manifest comment (:14-19) has the
mechanism inverted: ForegroundServiceTypePolicy's CONNECTED_DEVICE set resolves via
checkPermission (granted, not declared); BLUETOOTH_CONNECT is runtime and nothing in main
requests it; no normal-perm alternative declared. Path: fresh install → MainActivity:57
startForegroundService → ObdConnectionService:70 startForeground → SecurityException on
main thread in onCreate → dead before first paint. Robolectric can't see it (ShadowService
doesn't enforce the policy) — @Config(sdk=[34]) passes green, which is why the suite lies.
FIX: declare a normal auto-granted member of the allOf=false set (CHANGE_NETWORK_STATE)
alongside BLUETOOTH_CONNECT; wrap startForeground to degrade not crash; 🖐 on-device
fresh-install check is the arbiter.

**B2 MAJOR — self-heal is an unbounded zero-backoff restart storm; `Scanning` counts as
"connected".** ConnectionServiceController.kt:62-67: latch is `!= Disconnected`. MEASURED:
30 start() calls in 60s against a 2s failing scan = 15 scans/30s vs Android's 5/30s
throttle → OS silently blanks scan results → the storm PREVENTS reconnection. Demo dodges
it only because no scenario emits Disconnected mid-script. FIX (choose + justify):
narrow the trigger to a positive VM-stopped signal (matches stated intent, smallest), or
delete self-heal deferring to OBD-23's machine, or gate behind a backoff budget. Either
way `Scanning` must stop counting as was-connected. Full hazard statement for OBD-25 is
reproduced at the end of this record — it is part of the OBD-25 brief.

**B3 MAJOR — Doze rationale is wrong: an FGS does not keep the CPU awake.** Service KDoc
:43-57 claims a wake lock buys nothing; FGS exempts from bucketing, not SoC suspend —
screen off, delay()-scheduled polling stops within seconds. The 10-min screen-off AC is
exactly what this predicts passes and reviewer predicts fails. FIX: PARTIAL_WAKE_LOCK held
while polling (acquired with the service, released on stop), KDoc corrected; 🖐 the AC's
on-device 10-min screen-off test (keepScreenOn OFF — it masks the bug).

**B4 MAJOR — notification says "reconnecting…" while nothing reconnects.**
ServiceNotificationState.kt:45 renders every Error as "Connection error — reconnecting…";
controller explicitly defers reconnect to OBD-23; PermissionDenied/BluetoothOff are
terminal-until-user-acts. FIX: honest copy ("Connection lost — open the app") until
OBD-23/25 makes the promise true; per-error phrasing for the terminal two.

**B5 MAJOR — no way into the app or off switch from the notification; START_STICKY
orphan.** buildNotification (:120-129): no setContentIntent, no stop action. After LMK
kills the process, sticky-restart recreates the service with no task in recents →
onTaskRemoved unreachable, no user-reachable stop short of force-stop; holds a live GATT
link on an always-hot OBD port overnight. FIX: content intent → MainActivity; explicit
Stop action; reconsider START_STICKY vs NOT_STICKY with the orphan scenario written down.

**B6 MAJOR — POST_NOTIFICATIONS never requested → notification AC unreachable on fresh
13+ install.** Degradation itself is correct (no crash, polling unaffected). FIX: request
at first launch (or explicitly re-scope the AC in the issue with Taras's sign-off);
document as shipped limitation either way.

**MINORs**: B7 notification posted per reading, zero dedupe (measured 22 posts / 3
distinct texts; ~14,400 binder round-trips/hour at 2Hz) — one-line last-state guard.
B8 unknown-id verified defaults are `true` in :app (DashboardScreen.kt:347,
DashboardUiState.kt:35,42) — OPPOSITE of PidCatalog.isVerified's deliberate false;
flip both. B9 badge tap target 22dp (Box.clickable has no minimum-size) + glyph contrast
3.56:1 < 4.5:1 AA — widen touch target, darken fill. B10 a11y: no mergeDescendants, no
Role.Button — TalkBack reads bare "?". B11 picker candidate mini-cards carry no badge
(hypothesis learned too late). B12 badge intercepts a 22dp corner of the picker dismiss
surface (opens dialog atop an open picker). OBSERVATIONS: B13 RPM verified flag unpinned;
B14 stop-vs-self-heal resurrection race not reproduced in 400 trials (window narrower
than expected; noted for the record).

## Mutation ledger (reviewer-run)
(a) remove self-heal → CAUGHT (controller test); (b) invert badge condition → CAUGHT (3
unit + 3 screenshot); (c) drop settled gate (badge mid-animation) → **SURVIVED** —
coverage gap, round 2 must add a mid-animation badge-absence test; (d) skip
serviceScope.cancel in onDestroy → **SURVIVED** — teardown test only asserts no-throw,
round 2 must assert scope cancelled + dataSource.stop() reached; (e) filter unverified
from catalog → CAUGHT ×30.

## Fix list (round 2)
- [ ] B1 manifest perms + degrade-not-crash (+ 🖐 fresh-install device check)
- [ ] B2 self-heal trigger narrowed/bounded; Scanning excluded (+ storm regression test:
      failing-scan source, assert bounded start() count)
- [ ] B3 wake lock + corrected KDoc (+ 🖐 10-min screen-off device check)
- [ ] B4 honest notification copy (+ test per terminal error)
- [ ] B5 content intent + stop action + sticky decision written down (+ test)
- [ ] B6 POST_NOTIFICATIONS request or AC re-scope (Taras sign-off)
- [ ] B7 dedupe; B8 default flips (+ unknown-id test); B9/B10 badge target/contrast/a11y;
      B11/B12 at builder discretion with recorded rationale
- [ ] Mutations c and d must be KILLED by new tests; re-run a/b/e
- [ ] B13: pin RPM's verified flag

## OBD-25 hazard statement (verbatim, for the integration brief)
ConnectionServiceController re-issues start() unconditionally and immediately on every
observed transition into Disconnected from any non-Disconnected state — including
Scanning — with no delay, counter, or ceiling, on the assumption that the only producer
of a clean Disconnected is the VM's WhileSubscribed(5s) teardown. The moment OBD-23's
auto-reconnect is wired behind a real VehicleDataSource, any policy that (a) reports
give-up as Disconnected or (b) parks at Disconnected between attempts has its backoff
defeated every cycle: start() replaces (frozen contract), cancelling the scheduled retry
and forcing a fresh attempt at zero delay — measured 30 start()/60s = 3× the OS scan
throttle, which then silently blanks results and prevents the reconnect it forces.
OBD-25 must resolve ownership explicitly: (1) delete self-heal, reconnect machine owns
start(); (2) narrow trigger to a positive VM-stopped signal; or (3) gate self-heal behind
the reconnect policy's own budget. Whichever: Scanning must not count as was-connected.
