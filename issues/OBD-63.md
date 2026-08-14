---
id: OBD-63
title: Resizable gauge grid — Phase 2, spanning grid renderer
module: app
owner: orchestrator
sprint: grid-feature
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: feat/63-grid-render
---

## What (Phase 2 of the resizable-grid feature — render the grid; no gestures, no add/remove)
The dashboard now RENDERS as a spanning, widget-style grid driven by the persisted/migrated
`GridLayout`. Tiles are positioned by their `GridPlacement(col, row, colSpan, rowSpan)`, so gauges
can sit side-by-side, run wide (2×1) or tall/big (2×2), and exceed four in count. No gestures and no
add/remove in this phase — purely the static spanning layout.

- `gauge/grid/GridMetrics.kt` — pure, Compose-free pixel math: `cellWidth`, `cellSize` (fills the
  viewport but floors at a min height so many rows scroll instead of clipping), `placementRect`
  (span-expanded rect absorbing interior gutters), `contentHeight`. Fully unit-tested.
- `gauge/grid/GaugeGrid.kt` — a custom `androidx.compose.ui.layout.Layout` that measures each slot
  to its span box and places it at its cell origin, wrapped in `verticalScroll` so >4 rows never
  clip. Cell height is captured from the bounded outer `BoxWithConstraints` (the scroll gives the
  inner Layout an unbounded height). Each slot is `key(id)`ed here.
- `gauge/DashboardScreen.kt` — `GaugeTileGrid` now resolves `gridLayout ?: migration(gaugeOrder)`,
  repacks it into the orientation's column count (`GRID_COLUMNS_LANDSCAPE = 4` /
  `GRID_COLUMNS_PORTRAIT = 2`), and renders it via `GaugeGrid`. Per-tile content is the unchanged
  `GaugeSlot(...)` (picker/sparkline/grow-origin untouched), now with `Modifier.fillMaxSize()`.
- `gauge/DashboardViewModel.kt` + `app/MainActivity.kt` — additive `gridLayout: StateFlow<GridLayout?>`
  plumbed to the screen alongside the existing `gaugeOrder`.

## Why the picker/sparklines are unaffected
Every slot stays a normally-measured, normally-placed child of one custom `Layout` with no wrapper
node, so each `GaugeSlot`'s `onGloballyPositioned` bounds (root-space, used by the OBD-42/44/47
swap picker + grow animation) are exactly as before. With no persisted grid (the Phase-2 reality —
nothing writes one yet), the resolved layout is always the migration of `gaugeOrder`, so a swap that
rewrites `gaugeOrder` re-derives a fresh layout with the new id in the same cell — the picker path is
unchanged.

## Testing
`GridMetricsTest` (pure spanning math, incl. >4-row scroll height). Roborazzi: `dashboard_landscape`
re-recorded byte-identical (default look preserved); `dashboard_portrait` re-recorded (now a 2-col
grid); NEW `dashboard_grid_wide` (2×1), `dashboard_grid_six` (six tiles, speed beside oil), and
`dashboard_grid_big` (2×2). Full gate green.

## Done when
Gate green; dashboard renders as a spanning grid from `GridLayout`; default migrated layout looks
like today's dashboard; picker/sparklines/thresholds/orientation all still work; screenshots
re-recorded + new spanning scenarios added.
