---
id: OBD-44
title: Picker-entry shrink animation — multitasking-style scale-down on long-press
module: app
owner: ui-agent
sprint: adhoc
status: in-progress
type: feature
hardware-verify: false
blocked-by: []
branch: ui/44-picker-shrink-animation
---

## Feature
Refine the OBD-42 picker-entry transition: on long-press, the current live gauge should
SHRINK into the frame of its own card — the way an app window scales down into a card in
the Android/iOS multitasking switcher — rather than the current sink treatment.

## Requested UX (Taras, 2026-08-11 — verbatim intent)
"I want the original gauge to sort of shrink into the frame of the card sort of like when
you do multi tasking on android or iOS."

Key qualities of the OS-switcher feel to replicate:
- The FULL live gauge content scales down as one piece (value, label, coloring keep
  rendering live while shrinking — not a crossfade to a different mini composable).
- It shrinks toward/into its card frame, ending as the current-gauge mini-card sitting
  inside the tile — the card-in-card end state OBD-42 already established.
- Corner radius and elevation/shadow interpolate with the scale (switcher-style), so it
  reads as the same surface changing depth, not a swap.
- Motion spec: use spring/emphasized easing consistent with the existing dashboard motion;
  reverse plays on dismiss (mini-card grows back to full gauge). Selection of a DIFFERENT
  candidate keeps the existing OBD-42 rise treatment.

## Contract surface
None. Pure :app animation work inside the OBD-42 picker components
(GaugePickerTile and friends). No persistence or catalog changes.

## Acceptance criteria
- [ ] Long-press: full gauge scales down live into the mini-card (single surface, no
      content pop/crossfade); corner radius + elevation interpolate with scale
- [ ] Dismiss (tap current card / back / tap outside): exact reverse — grows back to full
- [ ] Gauge stays LIVE during the animation (value updates mid-shrink render correctly)
- [ ] Reduced-motion / animation-scale-0 devices: end states still correct (no stuck
      mid-scale composables)
- [ ] Existing OBD-42 behavior tests still green (enter/dismiss/swap/persist untouched)
- [ ] Screenshot test updated if picker-mode end frame changed; animation intermediate
      pinned only if cheap (end-state correctness is what's test-pinned)

## Self-test plan
Existing OBD-42 Robolectric compose tests as the regression net; add a mid-animation
liveness test if feasible (advance clock partway, assert value recomposition). Roborazzi
re-approve only if the settled picker frame differs.

## Out of scope
Carousel physics, candidate ordering, catalog changes, drag-to-reorder.

## Process note
First issue through the D5 ad-hoc channel: merges to `develop`, dev-channel APK built for
on-device feel verdict; promotion to main only on Taras's acceptance.
