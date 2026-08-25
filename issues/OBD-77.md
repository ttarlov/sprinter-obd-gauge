---
id: OBD-77
title: Expand-in-place gauge editor — lift the ⚙ editor out of the tile into a floating card
module: app
owner: ui-agent
sprint: grid-feature
status: in-review
type: feature
hardware-verify: true
blocked-by: []
branch: feat/72-gauge-styles
---

## What (Taras, on-Pixel, 2026-08-25)

Today the gauge editor (OBD-66 threshold menu + OBD-72 style picker) is the **back face of the
in-tile flip card** (`SwapPagerCard` → `GaugeEditorFace`), so it is permanently constrained to the
tile's size. On a small gauge (1×1, and even 1×2) the style chips + threshold stepper + color
squares can't fit — we chased it with tile-relative scaling + a scroll backstop (commit 93e3aaf) and
it's *usable* but never cohesive at the smallest size. Taras picked the real fix:

> **Expand-in-place card.** Tap the ⚙ gear → the gauge's editor animates up out of the tile into a
> larger floating card over the grid (dimmed scrim behind), roomy and fully tappable regardless of
> the gauge's tile size; **Done / back / tap-outside** collapses it back into the tile. Keeps the
> spatial "this is *that* gauge" connection (it grows from the tile's own rect) while decoupling the
> editor's size from the tile's.

## Why

The editor's content (3-way style picker + two-boundary threshold stepper + color squares) has a
real minimum comfortable size that a 1×1 cell simply can't provide. Scaling controls down to fit a
cell trades away tappability and legibility — the wrong axis to optimize. Lifting the editor to a
fixed comfortable floating card removes the constraint entirely and is the standard, UX-friendly
pattern (home-screen widget config, iOS context menus).

## Design

- **Split the two rearrange-mode badges' behavior** (both currently route through the same in-tile
  `SwapPager` flip via `pickerTileId` + `pickerOpensThreshold` in `DashboardScreen`):
  - **⇄ (swap):** unchanged — keep the in-tile `SwapPager` swap carousel exactly as-is.
  - **⚙ (edit):** no longer flips the in-tile card. Instead drives a new **dashboard-level floating
    editor overlay** (sibling of the existing `gauge-picker-scrim`, above the grid). Consider a
    dedicated `editorTileId` state distinct from `pickerTileId` so swap-vs-edit stay independent.
- **The floating card:**
  - Hosts the existing `GaugeEditorFace` (reuse it — style picker + threshold editor, both already
    built) at a **comfortable fixed size**, e.g. width `min(maxWidth - 2·margin, ~340dp)`, height
    wrap-content. Because the card is now a known comfortable size, `GaugeEditorFace` no longer needs
    the aggressive tile-relative `EditorScale` shrink — render controls at their natural sizes
    (simplify or neutralize `EditorScale` for this host; keep the code, just feed it the card's own
    generous dimension). The flip-back gear / `GEAR_CLEARANCE` indent is moot inside the card (the
    card has its own header) — give it a proper header row instead: **gauge name + a ✕/Done**.
  - **Animate from the tile's rect** to the expanded size: grab the tile's `GridMetrics.placementRect`
    (cell geometry already hoisted in `GaugeDashboard`) as the start bounds and animate
    position+scale (or size) out to the resting card, reverse on dismiss. A `Transition` /
    `animateFloatAsState` on a 0→1 "expanded" progress driving offset+scale is enough; it does not
    need to be a true shared-element. Respect `prefers-reduced-motion`? (no such flag on Android —
    just keep it a short tween, ~200–250ms, matching `SWAP_POP_MS`).
  - **Scrim + dismiss:** reuse/extend the existing full-size picker scrim (dim the grid, tap-outside
    dismisses). Wire `BackHandler` (close editor first, then rearrange mode) consistent with the
    existing layered handlers. `Done`/✕ in the card header also dismisses.
- **Persistence unchanged:** `onSetThreshold` / `onSetRenderStyle` still fold through
  `DashboardViewModel` → DataStore exactly as today; only the editor's *host* changes.
- **Threshold coloring stays sacred / structural** (unchanged): the tile's danger-pulse/tint sits
  above the `when(style)` dispatch; this issue doesn't touch render dispatch.
- **frontend-design:** invoke the `frontend-design:frontend-design` skill for the card's visual
  design — elevation/shadow, scrim opacity, header, the grow-from-tile motion, and the internal
  layout of the (now roomy) style-picker + threshold editor. Match the app's existing dark dash
  aesthetic (`docs/ui-refs/` for the gauge look; the editor should feel of-a-piece with the tiles).

## Reuse / don't rebuild

- `GaugeEditorFace` (style picker + threshold stepper + squares) — reuse wholesale; just re-host it
  and relax its tile-scaling now that the host is comfortably sized.
- `GridMetrics.placementRect` + the hoisted `cell` — for the start-bounds of the grow animation.
- The existing `gauge-picker-scrim`, `dismissPicker`, layered `BackHandler`s — extend, don't
  reinvent.
- `EditChip` (now takes optional scaled font/padding) — fine to call with defaults in the roomy card.

## Testing

- Roborazzi (Robolectric), `app/src/testDemo/`: the expanded editor card open over the grid
  (`dashboard_editor_expanded.png`); threshold face + style face. Record via
  `recordRoborazziDemoDebug`; gate verifies.
- Robolectric interaction: ⚙ opens the floating editor (not the in-tile flip); ⇄ still opens the
  in-tile swap; Done/back/scrim-tap dismiss; editor persists a style change + a threshold step.
- Unit: any new pure helper for the grow-bounds math (start rect → resting rect interpolation) if
  extracted.
- Device (🖐 Taras — the real gate): ⚙ on a **1×1** temp gauge → editor grows to a comfortable card,
  everything visible + tappable, threshold adjust works; Done collapses it back; ⇄ still swaps
  in-tile; nothing regresses in drag/rearrange. `hardware-verify: true`.

## Fix list

Round 1 review (`reviews/OBD-77-round1.md`): **approved, zero blockers, zero majors.**

- ✅ Implemented per spec — ⚙ opens a dashboard-level floating editor card growing from the tile's
  own root-space rect; ⇄ swap carousel untouched; Done/back/tap-outside collapse; gate GREEN.
- ✅ Correctness verified: pure `editorGrowTransform` (clamped + degenerate-safe), non-observable
  `SlotBounds` (no relayout loop), animation lifecycle (no double-fire/leak), layered BackHandler,
  self-heal on removed gauge, drag gating under the scrim.
- ✅ Device-approved by Taras (Pixel, demo-dev): "excellent. I like that look."
- M1 (stale start-bounds on collapse) and M2 (`twoColumn` keys off orientation) noted as
  non-blocking known-assumptions — no code change.

## Hardware checklist

Device-verified by Taras, 2026-08-25. **PASS on both devices.**

- [x] **Garmin Overlander (API 23), prod build**: ⚙ grows the editor out of the tile into the floating
  card; landscape two-column STYLE | THRESHOLD layout; fully usable at 1×1. **Taras: "Looks excellent
  on garmin."**
- [x] **Pixel (demo-dev)**: ⚙ opens the card, ⇄ still opens the in-tile swap, Done/back/tap-outside
  collapse, grow/collapse motion. **Taras: "excellent. I like that look."**

## Out of scope

- Drag-to-resize (OBD-68, still deferred).
- Changing WHAT the editor can edit (scale min/max editing still deferred per OBD-72).
- Any change to the gauge render styles themselves.

## Note on branch

Built on `feat/72-gauge-styles` (alongside the OBD-72 style work + the sparkline-strip removal +
needle-readout relocation already committed there) because it re-hosts OBD-72's own
`GaugeEditorFace` and would conflict heavily with those same files on a separate branch. Record all
three (OBD-72 styles, sparkline removal, OBD-77 editor) in the squash-merge message at merge time.
