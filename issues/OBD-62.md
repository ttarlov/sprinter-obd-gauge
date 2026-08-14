---
id: OBD-62
title: Resizable gauge grid — Phase 1, grid model + pure repack engine + persistence (no UI)
module: app
owner: orchestrator
sprint: grid-feature
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: feat/62-grid-engine
---

## What (Phase 1 of the resizable-grid feature — foundation, additive, no rendering change)
The dashboard becomes a spanning widget-style grid (>4 gauges, resizable, side-by-side). This phase
lands the **pure, fully-tested foundation** and touches no rendering, so it carries zero visual risk.

- `gauge/grid/GridLayout.kt` — model: `GridPlacement(id, col, row, colSpan, rowSpan)` + `GridLayout(columns, placements)`; occupancy/overlap helpers.
- `gauge/grid/GridEngine.kt` — pure ops built on a deterministic **repack** primitive: `addInFirstFreeSlot`, `remove`, `resize`, `reorder`, plus `canPlace`/`moveTo` positional primitives for the later drag phases (P4/P5). Repack computes col/row from order+spans top-left → bottom-right, so a bigger tile bumps others down predictably.
- `gauge/grid/GridMigration.kt` — `fromGaugeOrder(order, columns)`: today's visible gauges → 1×1 placements in reading order.
- `settings/AppSettings.kt` + `SettingsCodec.kt` — additive `gridLayout: GridLayout?` field (null ⇒ derive from `gaugeOrder`), encode/decode with default-on-malformed.

## Why positions are stored but repack-computed
Model stores explicit `(col,row)` so Phase 4/5 drag can set them directly with no migration; Phases 1–3 (menu sizing + add/remove + reorder) compute them via repack. Forward-compatible — no redo.

## Testing
Exhaustive unit tests on the engine (repack determinism, first-free-slot, resize bump/reflow, remove, overlap/fit rejection, columns bound), migration, and codec round-trip. No UI in this phase.

## Done when
Gate green; grid model + engine + migration + persistence landed and unit-tested; existing behavior unchanged (gaugeOrder untouched, dashboard still renders as before).
