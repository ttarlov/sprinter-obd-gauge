---
issue: OBD-47
round: 1
reviewers: [orchestrator (D6 small track)]
verdict: approved
gate: green
reviewed-commit: a0eed29
---

D6 small-track review of `ui/47-swap-grow-animation` (branch from develop 1b8e5cf).
Swap-in hard cut (key(id) remount at progress 0) replaced with a grow-in that reuses the
OBD-44/46 shrink stack in reverse.

## Fix list

- [x] ✅ Entry-state handoff: root-space rect of the tapped candidate captured at tap time
      (per-item onGloballyPositioned, remember scoped to the item's own key — cross-item
      clobber impossible), stashed in GaugeTileGrid's plain non-snapshot registry (correct
      choice: the write only feeds a later remember(id) read, never drives recomposition),
      consumed read-and-remove exactly once per mount. Rotation mid-grow degrades to a
      full-size mount (entry already consumed) — acceptable and self-healing.
- [x] ✅ Cohesion: grow drives the IDENTICAL rememberPickerShrinkAnimationSpec (300ms /
      FastOutSlowIn / snap-at-scale-0) through the SAME layer/counter-scale/corner/border
      stack — no parallel animation path. GaugeSlot keeps grow and pick strictly either/or
      (effective progress/bounds/long-press selected by isGrowingIn); chrome never renders
      during grow.
- [x] ✅ Long-press during grow: ignored, not queued — rationale (picker opening ~300ms
      after a forgotten input reads as unprompted UI) endorsed.
- [x] ✅ Tests: 18/18 green. Mid-grow test verified by ORCHESTRATOR MUTATION: forcing the
      grow Animatable to start at 0 (the old hard pop) → killed by exactly
      `swap-in grows continuously from the tapped candidate's rect and stays live while it
      does`; pristine restore re-verified green, tree clean. Snap test mirrors the OBD-44
      polling construction; mount-probe extended to assert exactly +1 mount per swap
      (guards against a key(isGrowingIn) wrapper regression); distinct MID_GROW_RPM_VALUE
      prevents coincidental-match passes. 12 OBD-42 tests byte-unmodified.
- [x] ✅ No Roborazzi changes — verify green, screenshots dir clean, settled frames
      untouched (the standing "transition must not leak into settled state" rule held).
- [x] ✅ Full gate.sh PASS at a0eed29.

NITs (recorded, not blocking):
- Rect.Zero terminal fallback in the onSelectCandidate wrapper (tapped bounds AND ghost
  bounds both null) would grow from a zero-rect at origin — effectively unreachable (a card
  must lay out to be tappable); revisit only if a real report surfaces.
- GaugeTile/BoostTile call sites went fully positional (9 args) to satisfy detekt line
  budgets — readable today, fragile if the signature grows; next signature change should
  restore named args.

Verdict: **approved** at a0eed29. Merge target: develop (D5 channel).
