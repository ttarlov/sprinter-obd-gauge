---
id: OBD-84
title: Permanent connection-status pill (dongle link + ECU-feeding), toggleable in Settings
module: app
owner: ui-agent
sprint: ux
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: feat/84-connection-status-pill
---

## What (Taras, 2026-09-12)

> "There's a connection status bar that pops up when the app is trying to reconnect. I want to see that
> bar a bit more often. Create a permanent status bar in the upper-left corner that tells me if the
> dongle is connected to the device AND if the dongle is reading from the ECU and feeding in — so when
> the ignition is on and the app is doing its connection procedure I can see what it's doing (connecting,
> connection established). I want to be able to turn that status bar off in the settings too."

Make the existing upper-left connection banner **permanent and more informative**: fuse the **BLE link
state** ("dongle connected to phone") with **data-liveness** ("dongle actually reading the ECU and
feeding data in") into one always-visible pill, and add a Settings toggle to hide it.

### Chosen behavior (Taras, 2026-09-12)
- **Fused two-state pill** (single line): `Disconnected` → `Scanning…` → `Connecting…` →
  **`Connected · waiting for ECU`** → **`Live · reading ECU`** (+ `Error: <reason>` with Retry).
  Color-coded: neutral/grey (disconnected), amber (busy / connected-but-no-data), green (live), red (error).
- **Default ON** (it's meant to be permanent). Settings toggle **`Show connection status`** to hide it.

## Design — reuse, don't rebuild (map from the OBD-84 exploration)

Almost all plumbing exists on `main`. The feature is: (1) render the **Ready** state the current banner
hides, split by data-liveness; (2) a `dataFlowing` derivation; (3) a `showConnectionStatus` setting.

### 1. The pill = the existing banner, made permanent
`app/src/main/kotlin/com/revel/obdgauge/app/gauge/ConnectionBanner.kt` already maps `LinkState` →
text/color/spinner via pure `internal` mappers (`connectionBannerMessage`, `connectionBannerStateName`,
`connectActionLabel`, `linkErrorMessage`) and already sits in the upper-left. It **returns nothing on
`LinkState.Ready`** ("quiet dashboard once healthy"). This feature makes it render the Ready state:
- `LinkState.Ready` + `dataFlowing` → **"Live · reading ECU"** (green).
- `LinkState.Ready` + `!dataFlowing` → **"Connected · waiting for ECU"** (amber) — dongle paired but no
  fresh data (ignition off / `SEARCHING…` / a wedge).
- All existing non-Ready states (Disconnected/Scanning/Connecting/Error + Connect/Retry action) unchanged.

Keep the pure mappers pure and add the Ready-state copy alongside them (unit-testable). **Do NOT** modify
the frozen `LinkState` contract (`core/model/.../LinkState.kt`, 5 variants) — the Ready split is a
UI-side fusion with `dataFlowing`, not a new state.

### 2. `dataFlowing` derivation (new, pure, tested)
Add `dataFlowing: Boolean` to `DashboardUiState`
(`app/src/main/kotlin/com/revel/obdgauge/app/gauge/DashboardUiState.kt`), computed inside the existing
pure `toDashboardUiState(...)` as **`readings.values.any { !it.stale }`** (mirrors OBD-69's
`applyReadingsToIdleSignal` "non-empty = data" rule, tightened with `Reading.stale`). No new connection
plumbing — `DashboardUiState.connection: LinkState` is already carried through `DashboardViewModel.uiState`.

### 3. Layout constraint — THE gotcha
The dashboard header `Row` (`DashboardScreen.kt` ~L487–559) must keep a **constant height** — the
＋Add/Done buttons are always-composed + alpha-gated precisely so `ConnectionBanner`'s `weight(1f)` width
and the Row height (→ grid viewport → tile sizes) never shift. The now-always-visible pill MUST stay
**fixed-height, single-line, ellipsized**. Rendering the Ready state at the same height as the existing
states satisfies this; verify the header height is identical across Disconnected/Connecting/Ready-live/
Ready-waiting/Error.

### 4. `showConnectionStatus` setting — mirror `keepScreenOn` exactly
`keepScreenOn` is the merged Boolean-toggle precedent (NOT `immersiveMode` — that's on unmerged
feat/83). Clone all touchpoints, **default `true`**:
- `settings/AppSettings.kt`: `val showConnectionStatus: Boolean = true`.
- `settings/SettingsCodec.kt`: `KEY_SHOW_CONNECTION_STATUS = booleanPreferencesKey("show_connection_status")`
  + decode-with-default + encode (default-on-missing discipline; `decodeAppSettings` is near detekt's
  complexity bound but one more `?:` is fine).
- `settings/SettingsViewModel.kt`: `fun setShowConnectionStatus(enabled: Boolean)`.
- `settings/SettingsScreen.kt`: a "Show connection status" `Switch` row (mirror `KeepScreenOnSection`,
  testTag `show-connection-status-switch`) + wire through `SettingsRoute`. (File is near detekt's
  function-count bar — inline the section like OBD-70's Recordings if detekt complains.)
- `gauge/DashboardViewModel.kt`: `val showConnectionStatus: StateFlow<Boolean>` (same shape as
  `keepScreenOn`).
- `MainActivity.DashboardOrSettings()`: collect it and pass into `GaugeDashboard(showConnectionStatus = …)`
  (add the param, default `true`). No `LaunchedEffect`/window flag — this only gates the pill.

**OFF behavior:** when `showConnectionStatus == false`, revert to today's behavior — the banner shows on
non-Ready states (scanning/connecting/error) and hides on Ready. I.e. the toggle only controls whether
the **Ready** ("Live/waiting") state is shown permanently. This keeps reconnect feedback when off. (If
Taras later wants OFF = fully hidden even during reconnect, that's a one-line follow-up.)

## Testing
- **Pure JUnit (`app/src/test/`):** the Ready-state copy mapper (Ready+flowing → "Live…", Ready+!flowing
  → "waiting…", other states unchanged); `toDashboardUiState` sets `dataFlowing` (true when a non-stale
  reading exists, false on empty/all-stale); `SettingsCodec` round-trip + default-**true**-on-missing for
  `showConnectionStatus`.
- **Compose/Roborazzi (`app/src/testDemo/`):** extend `ConnectionBannerTest`/`DashboardScreenTest` — pill
  visible on Ready when `showConnectionStatus` (Live vs waiting-for-ECU), hidden on Ready when off; header
  height constant across states (the layout gotcha). Re-record any shifted dashboard screenshot refs.
- **Device (🖐 Taras, non-gating smoke):** on the van/Garmin — key on, watch the pill walk Disconnected →
  Connecting → Connected·waiting → Live as the ECU comes up; toggle it off in Settings → pill gone on a
  healthy link. `hardware-verify: false` (pure UI state-fusion; no OBD-protocol correctness to gate on).

## Out of scope
- Changing `LinkState` (frozen contract) or adding a service-side liveness signal (OBD-78 territory).
- A verbose per-step connect log, or two separate dongle/ECU indicators (Taras chose the fused pill).
- The reconnect logic itself (OBD-71/78/82) — this only *surfaces* connection state.

## Base note
Builds on `main` (pre-OBD-83). `AppSettings` has `keepScreenOn` only. Trivial conflict expected with
feat/83 (immersive) at merge since both add a Boolean toggle in the same spots — resolve then. A testable
device build must be cut from an integration that also includes the reconnect fixes (71/78/82), else it
regresses the reconnect behavior already verified on the Garmin.
</content>
