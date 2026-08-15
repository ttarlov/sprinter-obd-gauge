---
id: OBD-66
title: Per-gauge threshold editor — gear + 3D flip + pulsing danger zone
module: app
owner: orchestrator
sprint: grid-feature
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: feat/66-threshold-editor
---

## Feature (Taras, sketch)
Reach a per-gauge threshold editor from swap/pick mode: a small gear in the upper-LEFT of the
centered pager card flips the card over its Y axis (3D flip, ~220ms to match the select-pop) to a
threshold menu. The back face has two stacked squares on the right — YELLOW (amber threshold) over
RED (danger threshold) — a blue frame marks the selected one, and the selected boundary's value shows
as a large number with a `+`/`−` vertical stepper. Every step persists to `thresholdOverrides` for
that gauge id. The gauge tiles recolor live off the edited thresholds, and a tile at/above its RED
(danger) threshold **pulses red**.

## Researched seed defaults (this vehicle, °F)
`ThresholdConfig.seed` re-baselined to a loaded Sprinter/Revel on grades:

| Gauge     | YELLOW (amber) | RED (danger) |
|-----------|----------------|--------------|
| coolant   | 215            | 225          |
| transTemp | 215            | 240          |
| oilTemp   | 245            | 260          |

The RED boundary is **inclusive** so a reading exactly on the danger line reads — and pulses — RED.
boost/rpm/speed stay neutral (no threshold entry).

## Implementation (`:app`-only, additive)
- **Seed / model**: `gauge/ThresholdConfig.kt` updated to the table above (redInclusive = true). The
  threshold model (`GaugeThresholds`, `classify`, `effectiveThresholds`) and the persist path
  (`thresholdOverrides` via `SettingsRepository.update`) are REUSED — no parallel store. The
  settings-screen threshold editor and this one write the same map.
- **Pure logic**: `gauge/ThresholdEditing.kt` — `ThresholdColor` (YELLOW→greenMax, RED→redMin),
  value stepping/clamping, pre-fill default (fallback 200), display↔wire conversion. Unit-tested.
- **Editor UI**: `gauge/GaugePicker.kt` — the focused (`pagerState.currentPage`) pager card of a
  temperature gauge shows a gear (`gauge-threshold-gear-<id>`); tapping it flips the card
  (`graphicsLayer { rotationY; cameraDistance }`) to `ThresholdEditorFace` (squares
  `gauge-threshold-square-yellow`/`-red`, value `gauge-threshold-value`, stepper
  `gauge-threshold-plus`/`-minus`). Tapping the gear again flips back; the card-body select tap and
  the pager swipe are untouched.
- **Live pulse**: `gauge/DashboardScreen.kt` — a RED tile pulses its red fill+border via an
  `infiniteTransition` (created only on a live RED tile, gated by `LocalDangerPulseEnabled` so tests
  can render a static red tile).
- **Wiring**: `DashboardViewModel.thresholds` (effective map) + `setThreshold(id, band)`, plumbed
  through `GaugeDashboard`/`MainActivity`.

## Tests
- `ThresholdEditingTest` (pure), `ThresholdConfigTest` (re-baselined seeds), `GaugeThresholdEditorTest`
  (gear→flip→menu, square→field mapping persists, live recolor). Seed-dependent scenario tests
  updated. Roborazzi: `gauge_threshold_editor.png` + `gauge_danger_zone.png` added; the dashboard/grid
  refs re-recorded (coolant now RED, oil now GREEN under the new seeds).

## Status
Implemented; gate green. Flip + pulse are device-only visuals — Taras to verify on the Pixel before
merge.
