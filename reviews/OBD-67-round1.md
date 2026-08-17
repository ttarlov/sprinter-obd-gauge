---
id: OBD-67-round1
issue: OBD-67
reviewer: rev-combined
reviewed-commit: 1770db4
status: approved
---

## Round-2 delta (commit 1770db4) — APPROVED

Scoped to `git diff 363cc8c..1770db4`. Both round-1 findings are properly fixed; the two addressed
minors are correct; the deferred minor is acceptable.

**Blocker (gesture dies on pickup) — RESOLVED, structural-stability claim holds.**
`GaugeSlot`'s rearrange branch now calls `GaugeSlotTileBody` at exactly ONE structural position
(DashboardScreen.kt:703); `isDragged` only appends `Modifier.dragGhostTreatment()` to the same chain
and toggles the `UnverifiedBadgeOverlay`/`RearrangeBadges` SIBLINGS after it. Verified under Compose
semantics, not just taken on faith:
- Single call site ⇒ the `GaugeTile`/`BoostTile` `Box` layout node keeps a positionally-stable
  identity across the `isDragged` flip — no branch swap disposes it.
- Within its modifier chain, `dragModifier`'s `pointerInput(id)` element is equal-by-key across the
  flip (`id` unchanged, `dragEnabled` stays true — `pickerTileId` is null throughout a drag). When
  `isDragged` flips true the `rearrangeJiggle`/`animatePlacement` elements drop out (their
  `if (!enabled) return this`), but `NodeChain`'s diff keeps the pointer element in the common
  subsequence and REUSES that `SuspendingPointerInputModifierNode` rather than detaching it. The
  `detectDragGestures` coroutine is scoped to that node, so it survives drag→drop. The dropped
  jiggle/offset elements are node removals elsewhere in the chain and never touch the pointer node.
- The `isDragged` flip happens *within* the `else if (rearrangeMode)` branch (mode is already on when
  a drag starts), so there is no GaugeSlot-level branch change mid-gesture either.

**Major (neighbors snap) — RESOLVED.** `targetRect` now reads `previewLayout.placementFor(id)`
(DashboardScreen.kt:498), the same layout `GaugeGrid` hard-places against (`layout = previewLayout`,
line ~482). `previewLayout` is recomputed only on `targetIndex` change, so each neighbor's target
tracks its reflowed rect and `animatePlacement`'s `LaunchedEffect(target)` fires the glide. The
dragged tile is still excluded (`enabled = !isDragged`), and the floating overlay's own rect (base
`layout`, size-only) is unaffected since a reorder never changes span. Correct.

**Minor (autoscroll origin) — RESOLVED.** `innerViewportHeightPx = viewportHeightPx - 2 * spacingPx`
(DashboardScreen.kt:573) correctly insets by `GaugeGrid`'s `padding(TILE_SPACING_DP)` top+bottom, so
the edge bands now align to the same inner content-space origin `fingerContentPos` lives in.

**Minor (autoscrollDelta untested) — RESOLVED.** New `RearrangeModeTest.kt` (6 tests) exercises all
three branches (top/bottom/neither) and both bound guards (at-top, at-end, fully-unscrollable). Not
papering over anything — it hits the real pure function with band-independent viewport sizing.

**Minor (tile-body-tap-exits-mode) — deferred, acceptable.** The author's stated reason (a stacked
tap `pointerInput` on the drag node risks a blocker-variant node-stability regression) is coherent,
and the behavior is a benign UX quirk (tap-anything-to-exit), not a correctness bug — fine to settle
on the device pass.

**One residual feel note for the device pass (not blocking):** on drop, `dragController.end()` clears
`draggedId`/`targetIndex` so `previewLayout` falls back to base `layout` for the frame(s) before
`moveGauge`'s persist round-trips and updates `gridLayout` to the committed order. In that window tiles
render at the pre-commit base order, so a reflowed neighbor may briefly glide toward its old slot then
to the committed one. The COMMIT itself is correct (verified round-1); this is purely a settle-animation
nicety gated behind persist latency — worth a glance on the Pixel, nothing to fix in code now.

Verdict: **approved** at `1770db4`.

---

## Round 1 (commit 363cc8c) — changes-requested (historical)

# OBD-67 review — rearrange mode (drag-to-move)

Fresh read against the spec, not the author's notes. The pure math (`cellAt`, `targetIndexAt`,
`reorder`/`moveGauge`, index-stability) is correct and well-tested — I verified the finger→index→commit
chain by hand and it has no off-by-one. The defects are both in the Compose interaction layer, exactly
the device-only surface Robolectric can't reach, so the green gate does not clear them.

---

**[blocker]** `gauge/DashboardScreen.kt:675-695` (`GaugeSlot`) — the drag gesture's host node is swapped
across an `if (isDragged) { … } else { … }` branch at the instant the drag starts, which cancels the
running gesture.

The `detectDragGestures` pointerInput lives in `bodyModifier` (via `dragModifier`, line 653-668) and is
passed as the `modifier` param to `GaugeSlotTileBody`. But that call appears at **two different source
positions**: the `if (isDragged)` ghost branch (line 676) and the `else` branch (line 678). `onDragStart`
(GaugeTileGrid:509) calls `dragController.start(id)`, which sets `draggedId` → GaugeTileGrid recomposes →
`isDragged = id == draggedId` flips true → GaugeSlot swaps from the `else` group to the `if` group. Compose
has no cross-branch node reuse without `movableContentOf`, so the `else`'s Box (and its
`SuspendingPointerInputModifierNode`) is **detached**, cancelling the coroutine that is mid-`detectDragGestures`.
The `else`-branch KDoc (line 599-601) states the ghost keeps "receiving the pointer events the still-attached
gesture here continues to report" — that assumption is precisely what the branch split breaks. `onDrag`/
`onDragEnd` never fire after the first frame, so the finger stops moving the tile and the drop never commits
via the gesture (only the `dragEnabled` LaunchedEffect eventually clears the stranded overlay).

Failure scenario: pick up any tile → tile lifts, overlay appears → tile freezes and cannot be moved or
dropped; on the Pixel this reads as "drag does nothing."

Fix: keep the pointerInput on a node that is stable across the `isDragged` transition. Render
`GaugeSlotTileBody(tile, sparkline, bodyModifier.then(if (isDragged) Modifier.dragGhostTreatment() else
Modifier))` at **one** call site, and add `UnverifiedBadgeOverlay` + `RearrangeBadges` as conditional
sibling children after it — rather than duplicating the body call in both branches. (Verify on device;
this rests on Compose node-lifecycle semantics, but the structure should not depend on reuse that Compose
doesn't guarantee.)

---

**[major]** `gauge/DashboardScreen.kt:490-492` — `animatePlacement` is fed the **base** layout's rect, not
the **preview** layout's, so neighbor reflow never animates during a drag and jumps back on drop.

`GaugeGrid` renders `previewLayout` (line 482) and hard-places every tile at its *preview* `(col,row)`.
But the `targetRect` handed to each `GaugeSlot` (and on to `Modifier.animatePlacement`, GaugeSlot:673)
is computed from `layout.placementFor(id)` where `layout` is the stable base (line 441-445). Base is
frozen for the entire drag (it only changes on commit via `onMoveGauge`), so `animatePlacement`'s `target`
never changes → `jump == Offset.Zero` every recomposition → the decaying-offset animation never runs.
Neighbors therefore **snap** to their reflowed slots instead of gliding, defeating the issue's "Animated
neighbor reflow" section entirely. Worse, on drop `base` jumps from pre-drag to committed order, so every
reflowed neighbor's `target` changes in one step and `animatePlacement` plays a glide *from its pre-drag
position* — a visible jump-back-then-settle at the exact moment the move commits.

Failure scenario: drag a tile across a full row → the other tiles teleport into place with no motion;
release → they flick back to where they started and slide forward again.

Fix: feed the preview rect — `previewLayout.placementFor(id)?.let { GridMetrics.placementRect(it, cell,
spacingPx) }` — so `targetRect` tracks where `GaugeGrid` actually hard-places the tile. (This is masked by
the blocker above: with the gesture cancelled, `targetIndex` never changes so preview never reflows — but
it's an independent defect and will surface once the blocker is fixed.)

---

**[minor]** `gauge/RearrangeMode.kt:369-374` + `DashboardScreen.kt:316,329` — autoscroll edge band uses a
viewport origin that doesn't match the finger's content-space origin. `viewportHeightPx` is `maxHeight`
of `GaugeDashboard`'s `BoxWithConstraints` (the whole grid area), but `fingerContentPos` is relative to
`GaugeGrid`'s inner `Layout`, which sits inside `GaugeGrid`'s `Modifier.padding(TILE_SPACING_DP)` (line
485). So `fingerScreenY = fingerContentPos.y - scrollState.value` is offset ~12dp from the outer viewport,
and the bottom band check `fingerScreenY > viewportHeightPx - AUTOSCROLL_BAND_PX` is off by the top+bottom
padding (~12–24dp). Only shifts where autoscroll triggers; feel is device-verified, so minor. Fix: pass the
inner `GaugeGrid` viewport height (or subtract the padding) so both share one origin.

**[minor]** `gauge/DashboardScreen.kt:270-285,652-695` — tapping a tile **body** (not a badge) in rearrange
mode exits the mode. In the rearrange branch the body carries only `detectDragGestures`, which does not
consume a plain tap; the always-mounted `gauge-rearrange-scrim` (line 270, drawn beneath the tiles) also
receives the unconsumed down/up and fires `onTap → exitRearrange` (guarded only by `pickerTileId == null`,
which holds). The issue lists exits as Done/back/tap-*outside*; a tap on a jiggling tile is arguably not
"outside," and a user reaching to grab a tile with a slightly-too-short press would drop out of the mode.
Confirm intended on device; if not, consume taps on the rearrange-mode tile body.

**[minor]** `gauge/RearrangeMode.kt:337-348` — `autoscrollDelta` is pure and its KDoc advertises "Pure so
the edge-detection rule is unit-testable without a `LaunchedEffect`/frame clock," but no test exercises it.
The three branch edges (top band + `scrollValue>0`, bottom band + `scrollValue<scrollMax`, and the
no-scroll-when-at-bound cases) are exactly the kind of off-by-one the issue's test plan wants locked down,
and they're testable with plain floats. Add a `RearrangeModeTest` (or fold into an existing pure test).

---

## What I verified (holds up)

- `GridMetrics.cellAt` correctly inverts `placementRect` — gutter-trails-preceding-cell, col clamp
  `[0,columns-1]`, row floor 0, span-interior, and the round-trip test are all sound.
- `GridEngine.targetIndexAt` resolves spans via `placementAt`, returns `size` for empty/past-end/absent-id;
  tests cover each.
- The reorder insertion index is **not** off-by-one: `reorder` removes-then-inserts against the
  dragged-excluded list while `targetIndexAt` returns the index in the full list — the standard
  forward-drag-inserts-after / backward-drag-inserts-before behavior falls out correctly. Verified [a,b,c,d]
  forward and backward by hand.
- `recomputeTargetIndex`/preview both key off the **stable base** `layout`, not the churning preview, so
  `targetIndex` is monotonic and the "preview churn" guard (recompute only on `targetIndex` change) is real.
- Index-stability across `withColumns` holds and is guarded; `moveGauge` commits to canonical 4-col with a
  matching-order index — safe.
- Overlay scroll conversion (`fingerContentPos - grab - scrollValue`) and the autoscroll fold-back
  (`moveBy(consumed)`) are self-consistent — no double-count.
- BackHandler layering is mutually-exclusive by guard (order-independent); scrim z-order routes the
  outside-tap to the OBD-42 picker-scrim while picking, no same-touch race.
- Scope is clean: `app` module only, reuses `GridEngine.reorder`/`SwapPager`/threshold editor/`mutateGrid`;
  drag-to-resize correctly absent (still the PickerEditBar chips).
- Rerouted `longClick()`→⇄-badge tests are meaningful (they assert the badge actually opens the picker),
  not just made to pass.

## Fix list (all cleared at 1770db4)

- [x] **blocker** — Drag pointerInput host is now one stable call site; gesture node survives the
      `isDragged` flip (verified under Compose `NodeChain` semantics). ✅
- [x] **major** — `targetRect` now feeds `previewLayout`, matching what `GaugeGrid` places against;
      neighbor reflow animates during drag. ✅
- [x] minor — autoscroll viewport origin inset by `GaugeGrid`'s padding. ✅
- [x] minor — pure `autoscrollDelta` test added (`RearrangeModeTest`, 6 cases). ✅
- [~] minor — tile-body-tap-exits-mode deferred to device with a coherent reason; acceptable. ⏸
