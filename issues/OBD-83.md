---
id: OBD-83
title: Immersive mode — toggleable setting that hides the system nav bar (swipe to reveal)
module: app
owner: ui-agent
sprint: ux
status: open
type: feature
hardware-verify: true
blocked-by: [OBD-79]
branch: feat/83-immersive-mode
---

## What (Taras, 2026-09-04)

> "On the Garmin the 3-button navigation interferes with the app's buttons… some apps hide the nav bar
> and it shows up if you pull up from the bottom. Let's do just the nav-bar hiding, but also make it a
> toggleable feature in Settings — call it 'Immersive mode'."

Add a **Settings toggle, "Immersive mode."** When ON, the app hides the **system navigation bar** in
**sticky-immersive** mode: it disappears to reclaim the space, and a swipe up from the bottom edge
reveals it **transiently** (it auto-hides again). The **top status bar stays visible** (clock/battery —
Taras wants that kept). When OFF, the nav bar behaves normally. This is app-commanded window behavior
(not system-level); it works on the Garmin Overlander (API 23) via the compat controller.

Motivation: on the dash-mounted Garmin the 3-button nav row competes with the app's own on-screen
controls. Hiding it removes the collision and gives the dashboard the full height. Making it a toggle
(not forced) keeps the phone usable normally when Taras wants the nav bar.

## Blocked by OBD-79
OBD-79 also adds an `AppSettings` field + `SettingsCodec` key + edits `MainActivity`. Build this **after
OBD-79 merges**, off the updated `main`, to avoid a three-file merge conflict.

## Design — mirror the existing `keepScreenOn` setting exactly
`keepScreenOn` (OBD-21) is the precedent: a persisted boolean, a Settings toggle, applied reactively in
`MainActivity` via a `LaunchedEffect`. Copy that shape.

- **Persisted flag:** additive `AppSettings.immersiveMode: Boolean = false` (default OFF — no surprise
  behavior change until opted in) + a `SettingsCodec` boolean key with per-field default-on-missing
  (match the existing codec discipline; `keepScreenOn` is the template).
- **Settings UI:** a new toggle section "Immersive mode" in `settings/SettingsScreen.kt`, mirroring
  `KeepScreenOnSection` (label + `Switch`), with a one-line hint ("Hides the navigation bar; swipe up
  to show it"). Setter through `DashboardViewModel`/`SettingsViewModel` → `settingsRepository.update`.
- **Apply in `MainActivity`:** a `LaunchedEffect(immersiveMode)` (alongside the existing
  `keepScreenOn` one) using `WindowInsetsControllerCompat(window, window.decorView)`:
  - ON: `controller.systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; `controller.hide(
    WindowInsetsCompat.Type.navigationBars())`. Do **not** hide `statusBars()`.
  - OFF: `controller.show(WindowInsetsCompat.Type.navigationBars())`.
  Collect the flag the same way `keepScreenOn` is collected in `DashboardOrSettings()`.
- **Insets interaction:** edge-to-edge is already on and screens use `safeDrawingPadding()`. Transient
  (swipe-revealed) bars do not change the reported insets, so content won't jump when the bar peeks —
  verify no layout thrash. When the nav bar is hidden, `safeDrawingPadding` bottom inset goes to ~0 and
  content fills the reclaimed space (the desired effect). Confirm the OBD-79 Maintenance screens and the
  dashboard still look right in both states.

## Testing
- **Pure JUnit:** `SettingsCodec` round-trip + default-on-missing for `immersiveMode` (mirrors the
  `keepScreenOn` codec test).
- **Device (🖐 Taras — the gate):** on the **Garmin (API 23)** AND the Pixel — toggle Immersive mode ON
  → nav bar hides, app content fills the space, swipe up from the bottom reveals it transiently then it
  auto-hides; status bar stays visible throughout. Toggle OFF → nav bar returns and stays.
  `hardware-verify: true` (the whole point is device chrome behavior, esp. the Garmin).

## Out of scope
- Hiding the **status bar** (Taras wants it kept).
- Per-screen or automatic immersive (single global toggle only).
- Any gesture-nav vs 3-button detection — sticky-immersive covers both.
</content>
