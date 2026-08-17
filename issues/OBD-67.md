---
id: OBD-67
title: Rearrange mode — freeform drag-to-move, per-orientation layouts, push/shift-resize, badges
module: app
owner: ui-agent
sprint: grid-feature
status: merged
type: feature
hardware-verify: true
blocked-by: []
branch: feat/67-drag-move
---

## Device-approved in full

Every mechanism this issue built — drag-to-move (freeform placement), per-orientation layouts,
add/remove, and resize (including push-on-collision and shift-left-at-the-right-edge) — is
**device-approved in full** by Taras on the Pixel, across thirteen rounds of device-found fixes.
The pivot sections below (newest first) summarize what changed and why at each stage; the branch's
own commit history (`git log feat/67-drag-move`) has the full round-by-round detail — every commit
from round 5 onward is a self-contained device-found-bug writeup (repro, root cause, fix, tests).

## Resize pivot (rounds 10–13, device-verified) — READ THIS FIRST TOO

The original spec (and round 3's model pivot below) deferred resize to a follow-on that never got
filed as its own issue — it shipped inside THIS issue instead, as `PickerEditBar`'s size chips
(1×1/2×1/1×2/2×2) calling `DashboardViewModel.resizeGauge`. It went through three device-verified
revisions before landing:

- **Round 10 — a genuine regression, two stacked bugs.** `resizeGauge` was still calling
  `GridEngine.resize` (the pre-freeform, repack-based primitive — see round 3's own pivot below):
  repack doesn't just resize the target tile, it re-derives EVERY placement's `(col, row)` from
  scratch, silently reshuffling unrelated tiles on every resize. Fixed with `GridEngine
  .resizeInPlace`: changes only the target's span, validated via `withinBounds`/`overlaps`,
  rejecting (keeping the current span) on collision or column overflow — same snap-back contract
  `dropAt` already had. A SECOND, independent bug surfaced in the same round: round 9's own
  drag-commit `optimisticLayout` (see below) cleared only on an EXACT match against the confirmed
  persisted layout — dragging a tile then resizing a different one before the drop's own async
  round-trip landed left the stale drag snapshot permanently masking the resize. Fixed by clearing
  the override unconditionally on the very next persisted-layout change, whatever it turns out to
  be, so it can never outlive more than one legitimate update.
- **Round 11 — investigated a reported row-bound bug, found none.** Device report: resize worked
  for 1×1/2×1 but not 1×2/2×2. Read `resizeInPlace` line by line (no row upper-bound exists
  anywhere in it) and reproduced the exact scenario against a fresh-install layout in both
  orientations — row growth into genuinely empty space succeeded every time. Added temporary
  `OBD67DRAG`-channel diagnostic logging to `resizeGauge` rather than guess further.
- **Round 12 — user decision: push instead of reject.** The logging proved `resizeInPlace` itself
  was correct all along — "1×1/2×1 work, 1×2/2×2 don't" was a genuine, silent, CORRECT rejection
  in a packed layout (round-10's default fixture happens to pack all four core gauges edge-to-edge,
  so any wider request collides with a neighbor). Technically right, invisible to the user, reads
  as broken. User's call: `GridEngine.resizeWithPush` replaces `resizeInPlace` in `resizeGauge` —
  growing a tile now displaces whatever's in the way to the nearest free slot (the same
  first-free-slot scan `repack`/`addInFirstFreeSlot` use, extending into new rows as needed — rows
  are unbounded, the grid scrolls) instead of refusing. `resizeInPlace` stays in the codebase,
  unused, alongside `resize`/`reorder`/`targetIndexAt` as a pure, tested, pre-pivot primitive. Also
  generalized round 9's drag-only optimistic-apply (below) into one shared `GaugeDashboard`-level
  `optimisticGrid` slot used by BOTH drag and resize commits, closing the exact class of bug round
  10's second stacked bug was.
- **Round 13 — user decision: shift left instead of refusing at the right edge.** One rejection
  remained deliberately: a colSpan that would grow a tile past the column count at its own column
  (a right-column tile "can't become 2-wide"). User's own words: "I can resize any way I want, but
  only when the gauge is on the LEFT side. If a gauge is in the RIGHT column it only resizes up/
  down, not side to side" — meaning that specific refusal itself needed to go. `resizeWithPush` now
  shifts the tile's own `col` left just far enough to fit (`newCol = min(col, columns - colSpan)`)
  before pushing whatever's now in the way — a right-column tile widening extends into the space
  actually available (its left) instead of doing nothing. The only resize still refused: a span
  wider than the grid itself (unreachable via the real chips, max 2×2, but still a real
  mathematical edge `resizeWithPush` has to honor).

`GridEngine.resizeWithPush` (not `resizeInPlace`) is what ships. `resizeGauge` stays per-orientation
(`mutateColumnsLayout`, one stored layout edited at a time) and optimistic (`GaugeDashboard`'s
shared `optimisticGrid`, same mechanism the drag commit uses) throughout every round above.

## Rendering & interaction fixes (rounds 5–9, device-verified)

Smaller device-found fixes between the model/persistence pivots (below) and the resize pivot
(above) — each is a real, separately-committed bug, summarized here so a reviewer doesn't have to
reconstruct them from the diff alone:

- **Round 5 — tile-shrink on entering rearrange mode.** `GaugeGrid`'s row count fed BOTH the
  cell-sizing calculation and the visible/scrollable extent through one shared value; rearrange
  mode's spare "+" row inflated that divisor, shrinking every tile ~30% the instant the mode was
  entered. Split into `sizingRows` (occupied extent only, drives cell size — invariant to
  rearrange mode) and `visibleRows` (drives scroll/backdrop extent only).
- **Round 6 — the residual ~5% shrink.** The top chrome row's Done button was conditionally
  composed, narrowing the connection banner's available width only in rearrange mode; the banner's
  unbounded text then wrapped, growing the whole header and shrinking the grid's viewport. Fixed
  by always composing the Done button (toggling visibility, not composition) and capping the
  banner's own text to one line.
- **Round 7 — the actual text still wrapping.** Round 6 capped the banner's message text but
  missed the connect button's own label ("Retry"/"Connect"), which still wrapped one letter per
  line in the disconnected/error state. Capped that too; added Roborazzi coverage for the
  disconnected banner specifically (round 5/6 only ever exercised the connected state, which
  renders no banner at all).
- **Round 8 — below-fold "+" cells became unreachable.** Once rearrange-mode tiles render
  full-viewport-height (round 5's fix), a vertical swipe on a tile starts a drag instead of
  scrolling, and there's no gesture left to reach the spare row's add cells. Added a "＋ Add"
  button to the top chrome row as the always-reachable primary add path (below-fold cells remain
  the secondary "add at this exact spot" path).
- **Round 9 — three fixes in one round, all device-feel:** (a) the drop commit's async persist
  produced a visible "flick back to origin, then snap" on release — fixed with the optimistic-apply
  pattern later generalized in round 12 (above). (b) the drop target was resolved from the dragged
  tile's top-left corner, a small target for anything wider than 1×1 — switched to the tile's own
  visual center, a no-op change for 1×1 but far more forgiving for larger spans. (c) empty cells'
  `detectTapGestures` consumed the down/up it claimed, blocking `GaugeGrid`'s own `verticalScroll`
  ancestor from ever seeing a swipe that started on an empty cell — replaced with a hand-rolled
  detector that never consumes anything until it's already clear the gesture was a tap.

## Persistence pivot (round 4, device-verified) — READ THIS FIRST TOO

Round 3 (below) made placement freeform but still persisted ONE canonical (4-column) `GridLayout`,
repacked into whichever orientation was on screen. Device testing found that insufficient: **a
freeform arrangement is inherently tied to a column count** — landscape (4 cols) and portrait (2
cols) are different canvases, and one position set can't serve both without a lossy translation
(exactly round 3's own flagged "portrait limitation," which round 3 explicitly left unresolved).
User decision: **freeform must work correctly in both orientations**, arranged independently.

- **`AppSettings.gridLayoutsByColumns: Map<Int, GridLayout>`** replaces the single
  `gridLayout: GridLayout?` field — one persisted layout PER column count (landscape=4,
  portrait=2), keyed by `GridLayout.columns`. Same on-disk DataStore key as before
  (`"grid_layout"`) — `GridLayoutCodec.encodeMap`/`decodeMap` join/split multiple `encode`d
  layouts on a new `|` separator; a pre-round-4 single-layout string has no `|` in it, so it
  decodes as a one-entry map keyed by whatever columns it was stored at — **the migration is free,
  no separate code path**, it falls out of `decodeMap`'s own fallback plus the eager-seed's
  `GridLayoutSet.ensureColumns` filling whichever count is still missing.
- **New pure layer `grid/GridLayoutSet.kt`** (tested in `GridLayoutSetTest`) is what keeps the
  cross-orientation invariant: **positions/spans are edited per-orientation** (`GridEngine.dropAt`/
  `resize`/`addAt` each still apply to exactly ONE stored layout — arranging landscape never
  touches portrait and vice versa), but **the SET of gauge ids must stay identical across every
  stored layout** — you never want a gauge on the dash-mount landscape grid but silently missing
  in portrait. `addEverywhere`/`addAnywhereEverywhere`/`removeEverywhere`/`replaceIdEverywhere`
  are the sync primitives; `ensureColumns` is the seed/migration primitive.
- **`DashboardViewModel`**: `moveGauge`/`resizeGauge`/`addGaugeAt` all gained a `columns` parameter
  (which stored layout to edit); `addGauge`/`removeGauge`/`swapGauge` sync across every stored
  layout via `GridLayoutSet`, no `columns` param needed (add-wherever/remove/rename all apply
  uniformly). `gridLayout: StateFlow<GridLayout?>` became
  `gridLayoutsByColumns: StateFlow<Map<Int, GridLayout>>`.
- **UI**: `GaugeDashboard`/`GaugeTileGrid` take `gridLayoutsByColumns` instead of a single
  `gridLayout`, and `GaugeTileGrid`'s own layout lookup is now a direct `map[columns]` read — no
  repack needed for EITHER orientation's own persisted data (round 3's "repack destroys freeform
  positions" fix only covered landscape via a conditional; round 4 removes the conditional
  entirely, since portrait now has its own real stored entry too). `PickerEditBar`'s resize/
  placement wiring (a sibling overlay OUTSIDE the orientation-aware `BoxWithConstraints`) reads a
  `currentColumns` state hoisted via `SideEffect` from inside that scope.
- **Portrait limitation from round 3 is now resolved** — no longer a known gap, since portrait
  gets its own persisted, freely-editable layout instead of being derived from landscape's.

## Model pivot (round 3, device-verified) — READ THIS FIRST

The original spec below shipped as written (ordered reflow) and was **replaced before this issue
closed**, per Taras's own device testing plus a decision he approved mid-build. The `## What`/
`## Why this shape`/`## Implementation` sections underneath are the **original design record** —
left intact for history, not because they describe current behavior. What actually shipped:

- **Ordered reflow → freeform placement.** Device testing found ordered reflow (drag changes a
  tile's packing index, `GridEngine.reorder`, everyone else repacks around it) structurally cannot
  do two things a widget-style dashboard needs: place a tile side-to-side into open space (a repack
  always left-packs, closing any gap you drag into), or hold a persistent empty cell to add into.
  Both are exactly what a "home-screen style" rearrange implies, so the model itself changed —
  **not** a bug fix on top of ordered reflow.
- **New engine primitives** (`grid/GridEngine.kt`, pure, tested — see `GridEngineTest`'s
  `dropAt`/`canDrop`/`swapPositions`/`addAt` suite): `dropAt(layout, id, col, row)` resolves a
  drag-drop in priority order — move if it fits (`moveTo`/`canPlace`, already existed, unused until
  now), else swap if the target is occupied by exactly one other placement whose own origin+span
  exactly matches (new `swapPositions`), else snap back (no-op; deliberately no multi-tile
  reshuffle). `canDrop` mirrors the same decision for the live highlight. `addAt(layout, id, col,
  row, colSpan, rowSpan)` places a not-yet-placed id at an explicit cell, no repack — the "＋"
  empty-cell add flow's mutator. `reorder`/`targetIndexAt` (the ordered-model primitives) stay in
  the engine, still correct, just no longer what the drag UI calls.
- **No more live neighbor reflow during a drag.** Nothing else moves while dragging — only the
  dragged tile (floating overlay + dimmed ghost, unchanged from the original build). A live
  **drop-target highlight** (filled/bordered rect at the dragged tile's own footprint, valid/invalid
  styled via `canDrop`) replaces the old reflow preview. `animatePlacement` (the per-id decaying-
  offset glide the custom `Layout` needs since it has no `animateItemPlacement`) is kept, but now
  only fires for the ONE remaining case a tile's position can change without being dragged itself:
  the displaced tile in a committed swap.
- **Empty cells are first-class.** Every cell not covered by a placement, across the occupied extent
  **plus one spare row** (`GaugeGrid`'s new `minRows` param), renders a "＋" in rearrange mode
  (`RearrangeMode.kt`'s `EmptyCellAddButton`, reusing the backdrop's own dashed drop-target square).
  Tapping opens the existing add-palette targeted at that cell (`GaugeDashboard`'s new `addAtCell`
  state, `DashboardViewModel.addGaugeAt`) — the OLD "tap a gap to exit rearrange mode" affordance is
  gone (every gap is now a "＋", not a plain tap target); Done and back remain the reliable exits.
- **`moveGauge(id, toIndex: Int)` → `moveGauge(id, col: Int, row: Int)`.** Same canonical-4-col
  persist path (`mutateGrid`), now calling `dropAt` instead of `reorder`. **Known limitation, not
  attempted:** `col`/`row` are read directly off the on-screen grid, correct in landscape (where the
  rendered grid IS the canonical layout unchanged — `GaugeTileGrid` only repacks when the rendered
  column count differs from canonical, and landscape's column count equals canonical's), but
  portrait's narrower repack means a portrait-dragged `(col, row)` does not translate back to the
  same canonical cell. Freeform's dash-mount primary target is landscape; portrait dragging still
  produces a valid (non-corrupting) layout, just not necessarily the visually-intended one.
- **A genuinely critical, easy-to-miss fix alongside the pivot:** `GridEngine.withColumns` always
  **repacks** (left-to-right, ignoring stored `col`/`row`) — correct for the old ordered model
  (position was never meaningful on its own), but it would have silently destroyed every freeform
  placement on **every recomposition** if left unconditional. `GaugeTileGrid`'s layout computation
  now only repacks when the rendered column count actually differs from the persisted layout's own
  column count (i.e., portrait only) — landscape renders the canonical layout untouched.

Everything NOT called out above shipped as the original spec describes and is unchanged: the grid
backdrop, the jiggle affordance, the ×/⇄/⚙ badges and their re-homing (initially built with an
outward-overlap look, corrected to an inward inset after device testing — see the round-1/round-2
review history), the drag gesture wiring itself (including two device-verified structural fixes —
the full-screen scrim that was starving drag pointer events, and the gesture host needing its own
`isDragged`-invariant modifier chain — both documented in the commit history, not repeated here).

## What (original spec — see the pivot section above for what shipped)

Direct-manipulation rearrange for the gauge grid, home-screen style. **Long-press anywhere** on the
dashboard enters a whole-board **rearrange mode** (iOS/Android "jiggle"), signaled by a **grid backdrop**
drawn behind the gauges. In that mode you **drag a tile's body to move it** and the others **reflow**
(ordered) to make room; drop commits and persists. Each tile shows corner **badges** — × remove,
⇄ swap, ⚙ threshold — which **re-home** the existing swap carousel and threshold editor off the current
long-press default onto explicit buttons. Exit via Done / back / tap-outside.

Drag-to-**resize** is intentionally out of scope here — it is a follow-on issue (its exact number TBD;
the round-3 pivot's code comments informally say "OBD-68" for the freeform-placement work itself, which
is **not** a filed issue — that label refers only to this same OBD-67, just the revised model).

> **UI agent:** invoke the `frontend-design:frontend-design` skill for this issue's visual design — the
> grid backdrop (line weight/color, empty-cell drop-target treatment), the badge styling, the picked-up
> tile treatment (scale/elevation), and any jiggle affordance. Don't ship templated defaults.

## Why this shape (original spec — ordered reflow was replaced, see pivot section above)

The grid engine was built for this and is already unit-tested but **unwired to any UI**:
`GridEngine.reorder(layout, id, toIndex)` is the ordered-reflow primitive; the model already stores
explicit `(col,row)`. This issue is the interaction layer over that engine — no model/persistence
redesign. Ordered reflow (drag changes a tile's packing index) is deliberate: it is how real home
screens behave, and freeform-2D reflow with mixed spans is both harder and not wanted.

## Reuse (original spec's list — see "what actually ships" below for the CURRENT primitives)

- `grid/GridEngine.kt` — `reorder(layout, id, toIndex)` (commit + live preview). Pure, tested.
- `grid/GridMetrics.kt` — `cellSize`, `placementRect` (backdrop + hit-test geometry). Pure, tested.
- `grid/GaugeGrid.kt` — the custom `Layout` (children keyed `key(id)`, measurable index i ↔
  `placements[i]`). Host the drag overlay/animation here.
- `GaugePicker.kt` — `SwapPager`/`SwapPagerCard` (⇄ target) and the threshold flip editor (⚙ target);
  `EditChip` styling for badges.
- `DashboardViewModel.mutateGrid { }` — the single persist path (canonical 4-col → DataStore).

**What actually ships** (per the pivot sections above): `GridEngine.dropAt`/`canDrop` (freeform
move/swap) and `resizeWithPush` (resize with push-and-shift) replace `reorder` as what the UI
calls; `GridLayoutSet` replaces the single canonical-layout persist path with per-orientation sync;
`GaugeGrid.kt`'s empty-cell tap detector and background-tap passthrough (rounds 8/9) and its
`sizingRows`/`visibleRows` split (round 5) are new since this list was written. `reorder`/
`targetIndexAt`/`resize`/`resizeInPlace` remain in the codebase, pure and tested, unused by any
current UI path — kept as historical/potentially-reusable primitives rather than deleted.

## Implementation (original spec)

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

## Watch (original spec's list — riskiest parts, per the plan BEFORE any pivot; several of these
turned out to be exactly where device rounds found real bugs, confirming they were the right
things to worry about)

- **Single-source cell geometry** — backdrop, hit-test, Layout must use one identical `CellSize`.
- **Scroll conversion** — overlay is a non-scrolling sibling; convert `viewport = content - scrollState.value`; autoscroll changes it mid-drag.
- **Pointer routing** — don't stack drag + tap on one node; badges are separate top-drawn children.
  *(Round 9 found a related case this list didn't anticipate: a plain tap detector on an empty
  cell can block a scrollable ANCESTOR from ever seeing a swipe — see that round's fix above.)*
- **`animatePlacement`** — confirm Compose version supports stable lookahead; exclude the dragged tile.
- **Preview churn** — recompute `previewLayout` only on `targetIndex` change, not per frame.
  *(Moot post-pivot: freeform has no live preview layout, only a drop-target highlight.)*
- **Index-stability across orientation repack** — *(moot post-pivot: freeform positions are
  absolute per orientation, no shared packing index to keep stable.)*
- **Behavior change** — long-press no longer opens swap directly. Update the `longClick()`-to-swap tests
  (`GaugePickerScreenshotTest`, `DashboardScreenTest`) to route through rearrange→⇄, and change the
  `GaugeTileInteraction` a11y label ("Swap gauge" → "Rearrange dashboard"). *(Shipped as spec'd.)*

## Testing (current state — what's actually in the suite)

**Pure JUnit** (`app/src/test/.../gauge/grid/`, `GridEngineTest`, `assertValid` helper): `cellAt`/
`canPlace`/`moveTo`/`dropAt` (move, swap, snap-back, no-op-for-absent-id); `resizeWithPush`
(displaces a colliding occupant to a free slot, leaves everyone untouched growing into empty
space, shifts left instead of rejecting a right-edge overflow — plain and combined with a push —
still rejects a span wider than the grid itself, never produces an overlap, no-op for an absent
id); `resizeInPlace`/`resize`/`reorder`/`targetIndexAt` (pre-pivot primitives, own tests kept,
unaffected). `GridLayoutSetTest`: the cross-orientation gauge-set-sync invariant.

**ViewModel** (`DashboardViewModelTest`): `moveGauge`/`resizeGauge`/`addGauge`/`addGaugeAt`/
`removeGauge` — each per-orientation (only the acted-on layout changes, the other orientation's is
untouched), persisted through the fake repo, no-op on an absent id.

**Roborazzi** (`app/src/testDemo/screenshots/`): `dashboard_rearrange_mode.png`/
`dashboard_rearrange_threshold.png` (backdrop + badges, tiles pixel-identical to normal mode — a
device-found regression rounds 5/6/7 fixed); `dashboard_disconnected_banner*.png` (the top chrome
row's height-invariance fix, rounds 6/7); `gauge_add_palette.png`/`gauge_picker_mode.png`.

**GaugeSwapPickerTest/GridEditTest** (Robolectric, real `DashboardViewModel` + fake data source):
the full render path for swap/resize/add — persisted state AND rendered bounds, both mid-pick and
after dismissing the picker, so a regression that renders right but persists wrong (or vice versa)
still fails. This is deliberately the level several device-found bugs (rounds 5, 6, 9, 10, 12, 13)
slipped past pure-engine tests at.

**Device (Taras, 🖐 — gestures and feel are device-only, Roborazzi cannot verify a drag or a
flick/settle timing):** long-press → mode + backdrop; drag a tile → drops exactly where released,
no flick-back; resize any gauge any way, including widening a right-column tile (shifts left,
doesn't refuse); add via the top-bar button or an empty cell; remove; scroll anywhere that isn't
an occupied tile; Done/back/tap-out exit; both orientations independently.

## Done when

Gate green (`assembleDebug`, `test`, `ktlintCheck`, `detekt`, `verifyRoborazziDemoDebug`,
`module-isolation`) — confirmed on every round's commit, including the final logging-strip pass.
Pure engine + ViewModel + Roborazzi + render-path tests pass. **Taras signs off on the drag/resize
feel on the Pixel** (this issue is `hardware-verify: true` — never auto-closed) — **done, device-
approved in full** across thirteen rounds (see "Device-approved in full" at the top). Ready for a
fresh reviewer to review the whole branch before merge.

## Hardware checklist

Device-verified by Taras on the Pixel 5 across the full device-driven fix cycle (this issue is
`hardware-verify: true`; values below are observed on-device behavior, not automated results):

- ✅ **Long-press → rearrange mode** — grid backdrop + per-tile badges appear; tile size is identical
  between normal and rearrange mode (no shrink).
- ✅ **Drag-to-move** — tile picks up, tracks the finger, drops where released (no flick-back), neighbors
  do not spuriously reflow; commit persists across app restart.
- ✅ **Freeform placement** — a tile drops into an empty cell and stays; dropping onto a same-size tile
  swaps them; a bad drop snaps back.
- ✅ **Add gauge** — top-bar "＋ Add" opens the palette; selecting a gauge lands it in an open cell;
  empty-cell "＋" targets work; swiping over empty space scrolls (doesn't drag).
- ✅ **Resize** (size chips) — grows into empty space; pushes neighbors aside when blocked; a
  right-column widen shifts the tile left to fit; vertical grows freely. Verified in portrait and
  landscape (`columns=4`).
- ✅ **Per-orientation independence** — landscape (4-col) and portrait (2-col) hold their own
  arrangements; add/remove of a gauge syncs the gauge set across both.
- ✅ **Connection banner** — single-line/short in both modes, no wrapped "Retry".

Taras sign-off (2026-08-16): "I think we got it!! Lets call it done." Final code review approved at
`72fe715` (`reviews/OBD-67-round2.md`), gate green.
