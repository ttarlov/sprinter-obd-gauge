---
id: OBD-64
title: Resizable gauge grid — Phase 3, add/remove + size-from-menu
module: app
owner: orchestrator
sprint: grid-feature
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: feat/64-grid-edit
---

## What (Phase 3 of the resizable-grid feature — make the grid MUTABLE from the UI; still NO drag)
The persisted `GridLayout` becomes the single live source of truth for which gauges show, where,
and at what size. Gauges can now be added (>4), removed, and resized from menus — no drag gestures
(deferred to a later phase).

- `gauge/grid/GridEngine.kt` — new pure `replaceId(layout, oldId, newId)`: renames a placement in
  place (same cell + span), no repack. Unit-tested. OBD-42's swap is now a grid op.
- `gauge/DashboardViewModel.kt` — eager-seeds the canonical (4-col) grid ONCE when
  `settings.gridLayout` is null (`GridMigration.fromGaugeOrder(gaugeOrder, GRID_CANONICAL_COLUMNS)`),
  then treats `gridLayout` as the source of truth. New mutators all persist via
  `SettingsRepository.update`, operating on the stored canonical layout:
  `addGauge`/`removeGauge`/`resizeGauge`, and `swapGauge` re-routed through `GridEngine.replaceId`
  (keeping `gaugeOrder` in lockstep for any code still reading it).
- `gauge/GaugeCatalog.kt` — candidate/addable lists now key off the grid's placed ids, not
  `gaugeOrder.visible`: new `candidateGaugesFor(currentId, placedIds)` overload and
  `addableGaugesFor(placedIds)`.
- `gauge/DashboardScreen.kt` — the normal (non-editing) dashboard renders exactly the placed
  gauges (no add cell), keeping the clean Phase-2 look. The whole edit surface lives behind
  long-press: a compact bottom **edit bar** with size chips (1×1 / 2×1 / 1×2 / 2×2), a Remove `✕`,
  and a `＋ Add` button (`gauge-edit-add`, hidden when nothing is addable) that opens the add
  palette. Picker self-heal keys off the placed ids so remove/swap clears a stale picker. `BoostArc`
  yields vertical space so the PSI value survives genuinely short (multi-row) tiles, while staying
  full-size in the default one-row landscape.

## Why the picker animations are unaffected
The edit bar (size/remove/add) is a separate overlay over the dashboard, not inside the in-slot
swap chrome — the OBD-42/44/47 in-slot shrink/carousel and each `GaugeSlot`'s `onGloballyPositioned`
geometry are byte-identical. The bar only adds resize/remove/add; the add palette + `addGauge`
wiring are unchanged, just triggered from the bar's Add button instead of an always-visible cell.

## Testing
- `GridEngineTest` — `replaceId` (keeps span/pos; no-op on absent/self).
- `DashboardViewModelTest` — eager-seed-on-null persists a 4-col migration; add/remove/resize/swap
  each persist the right `GridLayout`.
- `GaugeCatalogTest` — grid-keyed `candidateGaugesFor` + `addableGaugesFor`.
- `GridEditTest` (compose) — long-press → edit-bar `＋ Add` opens the palette and adds a 5th gauge;
  resize chips change a tile's span; remove drops a tile and it returns as an addable candidate; the
  normal view shows no add cell.
- Roborazzi (recorded via `recordRoborazziDemoDebug`): default views (`dashboard_landscape`/
  `dashboard_portrait`/`dashboard_grid_wide`/`dashboard_grid_big`) render exactly the placed gauges
  (Phase-2 look, full-size boost arc in the one-row landscape); `gauge_picker_mode` shows the edit
  bar (Add + size chips + Remove); NEW `gauge_add_palette`.

## Done when
Gate green; grid is mutable from the UI (add >4 / remove / resize-from-menu); swap picker + its
animations still work; screenshots re-recorded + add-palette added. No drag (later phase).
