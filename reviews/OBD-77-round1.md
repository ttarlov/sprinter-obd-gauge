---
issue: OBD-77
round: 1
reviewer: rev-platform
verdict: approved
gate: green
reviewed-commit: aa56851
covers: [OBD-77]
hardware-verify: true
---

# OBD-77 — expand-in-place gauge editor (floating card) — round 1

**Verdict: approved.** Zero blockers, zero majors. Two minor non-blocking notes below. This is a
clean, well-fenced re-host: the editor content is unchanged (the same `EditorControls` the flip
card feeds), only its *host* moved, and the tricky integration points — recompose-safe bounds
capture, animation lifecycle, layered back handling, and drag gating — are each handled correctly
and covered by tests. Gate is green (I re-ran `tools/gate.sh` myself → PASS on all 7 checks) and
the device look/feel is Taras-approved.

## What I verified (correctness-first)

1. **Grow math is pure + total** (`editorGrowTransform`, `GaugeEditorGrowTest`). Traced by hand:
   - `progress` is `coerceIn(0f,1f)` before the `lerp`s, so an overshooting spring can't invert the
     card (test pins this). `EDITOR_GROW_MS` uses `tween`, not a spring, so it never overshoots
     anyway — belt and suspenders.
   - Degenerate `rest` (not yet measured, `Rect.Zero`) OR degenerate `start` (a tile whose bounds
     were never captured) returns `IDENTITY_GROW` → the card fades in at rest instead of collapsing
     to a point at the screen origin. Both paths tested. This is the important safety case: on the
     very first frame `restBounds == Rect.Zero`, and the identity fallback means no one-frame
     flash-from-origin before `onGloballyPositioned` lands.
   - Endpoints exact: `p=0` maps the card precisely onto `start`, `p=1` sits untransformed at
     `rest`. Assumes `TransformOrigin.Center`, which is exactly what `GaugeEditorOverlay` sets.
2. **Bounds capture cannot loop** (`SlotBounds`). The per-slot `onGloballyPositioned` writes a
   **plain, non-snapshot** holder, so it never schedules a recomposition/relayout — critical,
   because it now runs on *every* tile every layout pass (including under the jiggle/drag transforms
   of rearrange mode). Nothing reads `slotBounds.value` except the ⚙ badge's `onClick`, by which
   point the latest layout has already written it. Correct call; an observable `mutableStateOf`
   here would have been a relayout loop.
3. **Root-space rects both ends.** Start (`slotBounds.value = boundsInRoot()`) and rest
   (`onGloballyPositioned { restBounds = boundsInRoot() }` on the **untransformed** parent Box, with
   the `graphicsLayer` on the *child*) are in the same coordinate space, so header height, grid
   padding, and scroll offset cancel — the overlay never re-derives geometry from `GridMetrics`, and
   the rest-bounds node can't feed its own transform back into its own measurement. Verified the
   `graphicsLayer`/measure split is on the right nodes.
4. **Animation lifecycle is leak/■double-fire-free.** `LaunchedEffect(expanded)` animates 0↔1;
   `onCollapsed` fires only after `animateTo(0f)` *completes*. If `expanded` flips back to true mid
   collapse, the effect's coroutine is cancelled at the suspending `animateTo`, so the trailing
   `if (!expanded) onCollapsed()` never runs on a stale value — `editorTarget` is not cleared out
   from under a re-open. `GaugeDashboard` keeps the overlay composed via `editorClosing` precisely so
   the exit tween can play; `onCollapsed` is the sole clear. Coherent.
5. **State split is genuinely independent.** ⚙ drives `editorTarget`/`editorClosing`; ⇄ drives
   `pickerTileId`. They no longer share `pickerOpensThreshold` (removed), so opening the editor can't
   disturb the in-tile swap carousel or vice-versa. `GaugeEditorCardTest` pins both directions (⚙
   opens the card and *not* the pager; ⇄ opens the pager and *not* the card).
6. **Back handling is order-independent + layered.** Four `BackHandler`s, each `enabled`-gated so
   the most specific wins regardless of registration order: editor → picker → add-palette → (last)
   exit-rearrange, whose predicate now also requires `editorTarget == null`. Test: back closes the
   editor first and only leaves rearrange mode on a second press. Matches the existing OBD-67
   layered-handler discipline.
7. **Self-heal.** A gauge removed/swapped out from under an open editor clears `editorTarget`
   (+ `editorClosing`) in the same `LaunchedEffect(placedIds)` that already heals `pickerTileId`, so
   no card is ever left editing an id that's no longer on the board with an armed scrim/BackHandler.
8. **Drag gating.** `dragEnabled` now also requires `!editorOpen`, so a raw drag through the scrim
   can't start moving a tile behind the card. The `LaunchedEffect(dragEnabled)` still cancels any
   in-flight drag when the flag drops.
9. **Scrim vs card hit-testing.** Card is the later sibling in the scrim Box → hit-tested first; its
   own empty `detectTapGestures {}` swallows chrome taps so they don't fall through to the
   dismissing scrim. Standard, correct.
10. **`hasThresholds` parity.** The card offers the threshold section iff `unit.kind() ==
    TEMPERATURE` — the same rule the in-tile face uses — so rpm/boost/speed get a style-only card.
11. **`roomyEditorScale` vs `rememberEditorScale`.** The floating host feeds fixed
    touch-comfortable constants (44dp stepper/40dp square targets); the flip host keeps its
    tile-relative fractions. Both feed the one `EditorControls` via `EditorLayout`. No duplication of
    the actual controls.

## Fix list

_None._ ✅ No blocking or major findings. Approving. (`reviewed-commit` set to branch head
`aa56851`, which adds only the needle-value rescale + sparkline dead-code sweep + device sign-off on
top of the OBD-77 feature commit `3f84b48` — all gate-green and Taras-verified.)

## Minor (non-blocking)

- **M1 — stale start-bounds on collapse (theoretical).** `startBounds` is a snapshot at ⚙-tap; if
  the grid scrolled or reflowed while the editor was open, the collapse would animate back to the
  old rect. In practice the scrim blocks all grid interaction for the editor's whole lifetime, so
  the board can't move underneath it — no live defect. Leaving as-is is correct; noting so it's a
  known assumption, not an accident.
- **M2 — `twoColumn` keys off screen orientation, not card fit.** `maxWidth > maxHeight` on the
  full-screen scrim is "is the device landscape," which is the right proxy for the dash-mount, but a
  very short landscape window with a tall threshold section still relies on the kept `verticalScroll`
  backstop. Fine as shipped; flagged only so the two-column threshold on an unusually short screen is
  understood to scroll rather than reflow.

## Tests

- Pure: `GaugeEditorGrowTest` — endpoints, halfway, clamp, both degenerate-bounds fallbacks.
- Robolectric interaction: `GaugeEditorCardTest` — ⚙→card-not-pager, ⇄→pager-not-card, Done/
  tap-outside dismiss, back layering, style persist, threshold-step persist.
- Roborazzi: `dashboard_editor_expanded{,_portrait}.png`, `dashboard_editor_style_only.png` added;
  `dashboard_rearrange_threshold.png` retired (its in-tile-threshold-flip route is gone);
  `gauge_threshold_editor.png` now reaches the flip face via the swap card's gear (its only
  remaining route). All re-recorded, `verifyRoborazzi` green.

## Hardware checklist

Device gate is Taras's (`hardware-verify: true`). On the Pixel (demo-dev), 2026-08-25:

- [x] ⚙ on a gauge grows the editor out of that tile into a comfortable floating card; everything
  visible + tappable regardless of the tile being 1×1. **Taras: "excellent. I like that look."**
- [x] Done / back / tap-outside collapses the card back into the tile.
- [x] ⇄ still opens the in-tile swap carousel (unchanged).
- [ ] (Follow-up, not OBD-77) needle value rescale verified separately (commit b0e8d86) — Taras:
  "looks great."

Nothing here blocks; recording the ✅ device items so the merge gate's hardware-verify is satisfied.
