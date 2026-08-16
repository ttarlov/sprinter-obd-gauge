---
id: OBD-67
title: Rearrange mode — long-press jiggle, grid backdrop, drag-to-move with ordered reflow, re-homed badges
module: app
owner: ui-agent
sprint: grid-feature
status: open
type: feature
hardware-verify: true
blocked-by: []
branch: feat/67-drag-move
---

## What

Direct-manipulation rearrange for the gauge grid, home-screen style. **Long-press anywhere** on the
dashboard enters a whole-board **rearrange mode** (iOS/Android "jiggle"), signaled by a **grid backdrop**
drawn behind the gauges. In that mode you **drag a tile's body to move it** and the others **reflow**
(ordered) to make room; drop commits and persists. Each tile shows corner **badges** — × remove,
⇄ swap, ⚙ threshold — which **re-home** the existing swap carousel and threshold editor off the current
long-press default onto explicit buttons. Exit via Done / back / tap-outside.

Drag-to-**resize** is intentionally out of scope here — it is the OBD-68 follow-on.

> **UI agent:** invoke the `frontend-design:frontend-design` skill for this issue's visual design — the
> grid backdrop (line weight/color, empty-cell drop-target treatment), the badge styling, the picked-up
> tile treatment (scale/elevation), and any jiggle affordance. Don't ship templated defaults.

## Why this shape

The grid engine was built for this and is already unit-tested but **unwired to any UI**:
`GridEngine.reorder(layout, id, toIndex)` is the ordered-reflow primitive; the model already stores
explicit `(col,row)`. This issue is the interaction layer over that engine — no model/persistence
redesign. Ordered reflow (drag changes a tile's packing index) is deliberate: it is how real home
screens behave, and freeform-2D reflow with mixed spans is both harder and not wanted.

## Reuse (do NOT rebuild)

- `grid/GridEngine.kt` — `reorder(layout, id, toIndex)` (commit + live preview). Pure, tested.
- `grid/GridMetrics.kt` — `cellSize`, `placementRect` (backdrop + hit-test geometry). Pure, tested.
- `grid/GaugeGrid.kt` — the custom `Layout` (children keyed `key(id)`, measurable index i ↔
  `placements[i]`). Host the drag overlay/animation here.
- `GaugePicker.kt` — `SwapPager`/`SwapPagerCard` (⇄ target) and the threshold flip editor (⚙ target);
  `EditChip` styling for badges.
- `DashboardViewModel.mutateGrid { }` — the single persist path (canonical 4-col → DataStore).

## Implementation

### New pure helpers (unit-test in `app/src/test/.../gauge/grid/`)
- `GridMetrics.cellAt(x, y, cell, spacing, columns): Cell` — inverse of `placementRect`; content-space
  point → `(col,row)`, col clamped `[0,columns-1]`, row ≥ 0. Add `data class Cell(col, row)`.
- `GridEngine.targetIndexAt(layout, draggedId, col, row): Int` — packing index of the placement under
  the finger (`placementAt`, span-aware), or `placements.size` for an empty/out-of-range cell.
- `DashboardViewModel.moveGauge(id, toIndex) = mutateGrid { GridEngine.reorder(it, id, toIndex) }`.
  Runs on the canonical 4-col layout; the index is order-stable across orientation repack.

### State (in `GaugeDashboard`, alongside `pickerTileId`/`showAddPalette`)
- `rearrangeMode: Boolean` — entered by repurposing per-tile `onLongPress` **and** a container-level
  long-press on the grid `BoxWithConstraints` (gaps enter too). Exit: a **Done** `TextButton` in the top
  chrome `Row` (shown only in mode), a tap-outside scrim (reuse the existing picker-scrim pattern), and
  layered `BackHandler`s (close an open badge editor first, then exit mode). Extend the existing
  `LaunchedEffect(placedIds)` self-heal to exit when the board empties.
- `pickerOpensThreshold: Boolean` — ⚙ seeds the reused editor to its threshold face, ⇄ to the gauge
  face. The `pickerTileId` flow is **kept** but now set only by the badges, not by long-press.
- New callback `onMoveGauge: (id, toIndex) -> Unit`, wired in `MainActivity` to `viewModel::moveGauge`.

### Grid backdrop
`Modifier.drawBehind` on the **inner scrolled `Layout` node**, gated on `rearrangeMode`, using the
**same** `CellSize` the Layout places with. **Hoist `cell` up into `BoxWithConstraints`** (width is
bounded under vertical scroll) so backdrop, hit-test, and placement share one geometry — drawing behind
the scrolled node makes lines scroll in lockstep, no manual offset math.

### Drag interaction
- In rearrange mode a tile's modifier switches from `gaugeTileInteraction` to plain
  `detectDragGestures` (entry already cost the long-press); `change.consume()` in `onDrag`.
- A remembered drag controller holds `draggedId`, accumulated delta, grab offset, derived `targetIndex`.
  **Hoist `scrollState`** out of the inline `rememberScrollState()` so drag math and scroll share it.
- **Floating tile:** render the dragged tile again as an overlay sibling on top of `GaugeGrid`, sized via
  `placementRect`, positioned `Modifier.offset { finger - grab - scrollOffset }` + `graphicsLayer`
  (scale ~1.05, shadowElevation). Its home slot renders a dimmed ghost.
- **Finger→index:** `cellAt(fingerContentPos)` → `targetIndexAt` → `targetIndex`.
- **Live preview vs commit:** `previewLayout = GridEngine.reorder(base, draggedId, targetIndex)`,
  recomputed **only when `targetIndex` changes**; render `GaugeGrid(previewLayout)`. Drop →
  `onMoveGauge(draggedId, targetIndex)`; cancel → drop preview (tiles animate home).

### Animated neighbor reflow
Custom `Layout` has no `animateItemPlacement`. Wrap the inner `Layout` in a `LookaheadScope` and apply an
`animatePlacement()` modifier (remembered `Animatable<IntOffset>`) to each child **inside the existing
`key(id)` wrapper** so state survives reorder. **Exclude the dragged tile** (finger-driven — must not
fight the drag). Fallback if the Compose version lacks stable `approachLayout`: per-id `Animatable` map
keyed by id, animated on `placementRect` change via `Modifier.offset`.

### Re-homed badges
`slotContent` overlays ×/⇄/⚙ as separate top-drawn `Box` children with their own
`pointerInput{detectTapGestures}` (topmost hit-test wins → a badge tap never starts a body drag). ×→
`onRemoveGauge`; ⇄→ `pickerTileId=id; pickerOpensThreshold=false`; ⚙→ `pickerTileId=id;
pickerOpensThreshold=true`. Thread `initiallyShowThreshold: Boolean = false` through
`SwapPager`→`SwapPagerCard` (trigger-only; no editor logic change). While `pickerTileId != null`,
disable drag and hide other tiles' badges.

### Autoscroll
When the grid is scrolling (rows overflow): `onDrag` sets an edge signal when the finger enters a
top/bottom band; a `LaunchedEffect(draggedId)` loop calls `scrollState.scrollBy` per `withFrameNanos`
while active and `canScroll*`, recomputing `targetIndex` each tick.

## Watch (riskiest parts)

- **Single-source cell geometry** — backdrop, hit-test, Layout must use one identical `CellSize`.
- **Scroll conversion** — overlay is a non-scrolling sibling; convert `viewport = content - scrollState.value`; autoscroll changes it mid-drag.
- **Pointer routing** — don't stack drag + tap on one node; badges are separate top-drawn children.
- **`animatePlacement`** — confirm Compose version supports stable lookahead; exclude the dragged tile.
- **Preview churn** — recompute `previewLayout` only on `targetIndex` change, not per frame.
- **Index-stability across orientation repack** — the single-index commit depends on `placements` order
  being preserved by `withColumns`; **guard with a test** (below).
- **Behavior change** — long-press no longer opens swap directly. Update the `longClick()`-to-swap tests
  (`GaugePickerScreenshotTest`, `DashboardScreenTest`) to route through rearrange→⇄, and change the
  `GaugeTileInteraction` a11y label ("Swap gauge" → "Rearrange dashboard").

## Testing

**Pure JUnit** (`app/src/test/.../gauge/grid/`, existing style + `assertValid` helper):
- `cellAt`: point-in-cell, gutter snapping, col clamp, span interiors, round-trip vs `placementRect`.
- `targetIndexAt`: over each tile → its index; empty/past-end → `size`; over dragged tile → own index;
  spanning tiles via `placementAt`.
- **Index-stability invariant:** `reorder(canonical, id, i)` then `withColumns(2)` equals `withColumns`
  then `reorder` — locks single-index commit correctness.
- `DashboardViewModelTest`: `moveGauge` reorders + persists on canonical 4-col via the fake repo; no-op
  on absent id; clamps out-of-range index.

**Roborazzi** (`app/src/testDemo/screenshots/`): new `dashboard_rearrange_mode.png` (backdrop + badges);
optionally `dashboard_rearrange_threshold.png` (⚙ → flipped editor). Record via
`recordRoborazziDemoDebug`; the gate verifies. Reroute the `longClick()`-to-swap screenshot/UI tests.

**Device (🖐 Taras — gestures are device-only, Roborazzi cannot verify a drag):** long-press → mode +
backdrop; drag a tile → neighbors reflow smoothly; drop → persists across app restart; ⇄/⚙/× badges
work; autoscroll on a tall (6+) layout; Done/back/tap-out exit.

## Done when

Gate green (`assembleDebug`, `test`, `ktlintCheck`, `detekt`, `verifyRoborazziDemoDebug`,
`module-isolation`); the pure helpers + `moveGauge` + index-stability tests pass; rearrange-mode
screenshots recorded; long-press-behavior-change tests updated; and **Taras signs off on the drag feel
on the Pixel** (this issue is `hardware-verify: true` — never auto-closed).
