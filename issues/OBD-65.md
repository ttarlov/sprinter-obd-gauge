---
id: OBD-65
title: Swap-gauge carousel can't be scrolled/swiped in a small tile — pick another gauge is near-impossible
module: app
owner: orchestrator
sprint: grid-feature
status: merged
type: bug
hardware-verify: false
blocked-by: []
branch: fix/65-carousel-scroll
---

## Bug (Taras, on-device 2026-08-14)
Long-press a gauge → the "Swap gauge" carousel of other candidates appears, but you **cannot swipe
through the candidates**. In a small (1×1) tile it's almost impossible to select another gauge — the
carousel is cramped and the horizontal drag doesn't scroll it.

## Diagnosis (orchestrator)
The carousel IS a real `LazyRow` (`GaugePicker.kt` `PickerCandidateCarousel`, ~line 721: `rememberLazyListState`
+ snap fling + keyed items), so scrolling was intended. Two compounding causes:

1. **Confined to the cell.** Since the OBD-63 grid rework, `GaugeGrid` measures each slot with
   `Constraints.fixed(cellW, cellH)`. The picker chrome (`GaugePickerChrome`) — and therefore the
   candidate carousel — renders **inside that one fixed cell**. In a 1×1 tile that's ~1 card wide, so
   there's almost nothing to show or drag. (A 2×1 tile shows ~3 candidates — confirms the width is the
   cell width.)
2. **Drag eaten by the tile's tap detector.** `gaugeTileInteraction` (`GaugeTileInteraction.kt`) applies
   `pointerInput { detectTapGestures(onTap, onLongPress) }` over the whole slot, overlapping the carousel.
   A horizontal swipe is consumed/handled as a (cancelled) tap on the tile rather than reaching the
   `LazyRow`, so the carousel never scrolls.

## Fix direction (agent to reproduce, choose the cleanest, verify on device)
Make the swap-candidate carousel **comfortably scrollable and usable regardless of tile size.** Likely the
right move is to stop confining the picker candidate list to the single small cell — e.g. present the
candidate carousel (and the edit-bar it already sits above) as an **overlay/panel with adequate width**
when a tile is being edited, instead of squeezed into the shrunk cell — which fixes BOTH the room and the
gesture overlap (the carousel is no longer under the tile's `detectTapGestures`). Alternatively, if the
confined layout is kept, the `LazyRow` must reliably win the horizontal drag (resolve the pointer-input
competition with the tile's tap detector) AND have enough width to be usable. Must NOT break the OBD-42/44/47
shrink/grow animations, the swap-select "confirm/rise/dismiss" beat, tap-to-dismiss scrim, or the OBD-64
edit bar (add/resize/remove).

## Done when
Long-pressing any tile (incl. a 1×1) lets you swipe through ALL swap candidates and pick one; verified
ON-DEVICE (gesture bug — Robolectric can't fully cover scroll gestures). A Compose/unit test guards the
carousel is present + scrollable where feasible. Gate green.

## Approach (fix/65-carousel-scroll) — in-tile pager
Per Taras's direction, the swap UI now happens **inside the long-pressed tile**, not a bottom bar.
Long-pressing a tile replaces its content with an in-place `HorizontalPager` (`SwapPager` in
`GaugePicker.kt`) that fills the tile's own bounds at whatever size it is:

- **Page 0** = the current gauge (highlighted border), so you start on it.
- **Following pages** = the swap candidates (`candidateGaugesFor`), one full-tile gauge card per page.
- **Swipe** left/right (native pager snap) to flip through; the pager consumes horizontal drags, so it
  wins the swipe the old carousel lost to the tile's `detectTapGestures` (which is suspended while
  picking — `GaugeSlot` renders the pager instead of the interactive tile).
- **Tap a candidate page** → `swapGauge(oldId, newId)` persists it and pick-mode dismisses (self-heal).
- **Tap page 0 / the scrim / back** → dismiss, no change.

### Presentation (Taras's sketch, refined)
The picked tile keeps its **frame** (tinted `surfaceVariant` background + primary border) and the pager
lives inside it. The centered card is **~75%** of the frame width (`SWAP_CARD_FRACTION`), with the
neighbour candidate cards **peeking** at the left/right edges (`contentPadding = frameWidth * 0.125` each
side, via `BoxWithConstraints`) — the "swipe for more" affordance. Holds down to a 1×1 landscape cell: the
centered card stays a readable majority of the (small) frame with a visible peek.

**Pop animation** (a clean scale, not the old exact-bounds anisotropic shrink):
- *Enter*: an `Animatable` runs 0→1 once; the pager content scales from `1/0.75` (centered card ≈ full
  tile, a continuation of the gauge that was there) down to 1 (settled ~75% card) while the frame
  background/border fade in — "zoom out to browse".
- *Select*: the tapped candidate card scales up to `1.35` (fills the frame) over 220 ms, then `swapGauge`
  persists and pick-mode dismisses — "pop in to select". During that pop the frame chrome (bg + border)
  and every non-selected page fade to 0, so the chosen gauge grows to fill the tile on a clean background
  with no frame/neighbour ghost.

The OBD-64 edit bar (＋Add / 1×1 / 2×1 / 1×2 / 2×2 / ✕) stays a floating bottom overlay (buttons, not swiped).

**Removed** (retired, not dead-coded): the whole OBD-42/44/46/47 shrink-into-a-mini-card carousel stack —
`GaugePickerChrome`, `PickerCandidateCarousel`, `pickerShrinkLayer/Visuals/Border`, the anisotropic-corner
math, `pickerAwareInteraction/Tag`, the grow-in origin registry, and the `LocalGaugeTileMountProbe` hook.
The new pop is a simple `graphicsLayer` scale reimplemented cleanly on the pager, not that stack.

`gauge_picker_mode` / `gauge_add_palette` Roborazzi refs re-recorded (picked tile now shows the framed
~75% current-gauge card with a neighbour peeking, edit bar below). Full gate green. On-device swipe + pop
still to be confirmed by the orchestrator (Robolectric can't drive fling; tests reach candidate pages via
the pager's `performScrollToIndex` scroll semantics and assert the card is a fraction of the frame).
