---
id: OBD-82
title: Connect button must restart the foreground service, not just the link (in-session reconnect wedge)
module: app
owner: ui-agent
sprint: reliability
status: open
type: bug
hardware-verify: true
blocked-by: []
branch: feat/82-connect-restarts-service
---

## What (Taras, 2026-09-04)

> "Sometimes I can't get the app to reconnect. What helps is clearing cache AND data from the app — but
> that kills all my gauge layout and I have to reconfigure. Also if I shut the engine off for a longer
> period I have to quit and restart the app before it reconnects; the Connect button does nothing."

Two symptoms, root-caused to two distinct wedges (diagnosis 2026-09-04, code-grounded, read-only).

## Root causes

### RC1 — no in-session service resume (PRIMARY; symptom 2; genuine gap, NOT covered by OBD-71/78)
After the foreground service stops — **OBD-69 idle-stop** (20 min no data) or **OBD-71 engine-off-stop**
— the Connect button re-arms the **link** but never restarts the **service**. The parked poll loop
(`RealVehicleDataSource.runSession` returns on `PollOutcome.LinkDown`) only resumes when something calls
`dataSource.start()` on a live link, and the **only** restart-on-`Ready`-edge trigger is
`ConnectionServiceController` (`app/.../service/ConnectionServiceController.kt:99-105`,
`becameReady && intendsToRun`) — which is dead once the service is stopped.

- `MainActivity.connectNow()` (`app/.../MainActivity.kt:215-217`) calls **only** `link.connect()`.
- `ContextCompat.startForegroundService(...)` lives **only** in `onCreate` (`MainActivity.kt:125`).
- `DashboardViewModel` calls `start()` only in `onStart` (first UI subscription,
  `DashboardViewModel.kt:114`); it never re-issues `start()` on a later `Ready` edge.

So after an idle/engine-off stop: tap Connect → link returns to `Ready`, but nothing restarts the
service, nothing re-issues `start()` → gauges stay frozen → "Connect does nothing." A **fresh launch**
recovers only because `onCreate` restarts the service (+ `connectIfRemembered`, which runs **only** at
launch, `MainActivity.kt:132`).

Confirmed **not a link-layer no-op**: `BleObdLink.connect()` always `release()`es and re-runs the full
attempt (`core/ble/.../BleObdLink.kt:220-232`, `:285`) — the wedge is purely the dead service.

### RC2 — "clear-data fixes it" is the FORCE-STOP, not the wipe (symptom 1)
Recovery is process death: the `@Singleton BleObdLink` (`BleObdLink.kt:91`) resets to `Disconnected` and
`onCreate` rebuilds the service. **No persisted state traps the app** — a stale remembered address
self-corrects (`connectDirect` fails → scan fallback, `BleObdLink.kt:315-328`). The data wipe (and the
layout loss) is pure collateral.

### RC3 — zombie-Ready (contributing to symptom 1; ALREADY covered by OBD-78)
A command timeout keeps the link `Ready` by design (`BleObdLink.kt:427`; `GattSession.send:168-170`,
`writeCommand:349-351` — "one bad command is not a dead link"). If the dongle keeps its BLE radio up but
stops answering (ELM327-clone wedge), no `STATE_DISCONNECTED` fires, so the reconnect machine
(`onSessionTerminated`, `BleObdLink.kt:560`, only from `GattSession.terminate:291-293`) never books an
attempt → link sits `Ready` with dead gauges. **OBD-78's data-liveness watchdog fixes this** (verified:
with the service alive, its forced `connect()` reaches `Ready` and `becameReady` restarts polling). Out
of scope here.

## Immediate workaround (verified — surface to Taras)
**Use Force stop (App info → Force stop), NOT Clear data.** Gauge layout lives in the `obd_app_settings`
DataStore file (`AppSettings.gridLayoutsByColumns`, `SettingsModule.kt:49`); the remembered dongle in
`obd_ble_link` (`DataStoreRememberedDeviceStore.kt:57`). Both survive a force-stop and are wiped only by
Clear data. The BLE bond is OS-level and survives both (clearing app data does **not** unpair). So a
force-stop gives the identical fresh-process recovery with **zero layout loss**.

## Overlap with in-flight work (no duplication)
- **OBD-78** (in-review) fixes RC3 (service alive, dongle silent). Does **not** fix RC1 — its watchdog
  runs on `serviceScope` and is dead once the service is idle/engine-off stopped.
- **OBD-71** (engine-off) makes teardown deterministic (RPM==0) + fixes drain, but adds **no** resume and
  leaves `connectNow` unchanged (verified on `feat/71-overnight-reconnect`: `MainActivity.kt:224` still a
  bare `link.connect()`, `startForegroundService` still `onCreate`-only).
- Systemic root shared by RC1: OBD-69 and OBD-71 use one stop path (`disconnectLinkOnUserStop → stopSelf`)
  and the app has exactly one service-start site (`onCreate`) with no in-session resume.

## The fix
Make the Connect/Retry affordance **also (re)start the foreground service**, alongside `link.connect()`:
`ContextCompat.startForegroundService(this, Intent(this, ObdConnectionService::class.java))` in the
connect path (`requestConnect()`/`connectNow()`). Idempotent — `ObdConnectionService.onStartCommand`
only acts on `ACTION_STOP`, and `ConnectionServiceController.start()` no-ops while running — so a repeat
start on an already-running service is harmless; the new service instance's `becameReady` restarts the
parked poll loop once the link reaches `Ready`. **Owner file:** `app/src/main/kotlin/com/revel/obdgauge/app/MainActivity.kt`.

Complementary (lower priority, optional): have `DashboardViewModel` re-issue `dataSource.start()` on a
`Ready` edge while foreground, as a second safety net. The service restart on Connect is the clean fix.

## Testing
- The connect path is thin Activity glue; primary verification is on-device. Add whatever unit coverage
  is practical around any extracted helper (e.g. a testable "ensure service + connect" function).
- **Device (🖐 Taras — the gate):** reproduce symptom 2 (engine off long enough for the idle/engine-off
  stop, or wait out the 20-min idle-stop) → tap **Connect** → gauges resume **without** a quit+relaunch.
  Fold into the same van trip as OBD-71/OBD-78 verification. `hardware-verify: true`.

## Out of scope
- The zombie-Ready wedge itself (OBD-78).
- Engine-off drain / deterministic teardown (OBD-71).
- Any change to the reconnect backoff/curve (OBD-23).
</content>
