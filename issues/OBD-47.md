---
id: OBD-47
title: Swap-in grow animation — picked gauge grows into the tile like the shrink in reverse
module: app
owner: ui-agent
sprint: adhoc
track: small
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: ui/47-swap-grow-animation
---

## Feature (Taras, 2026-08-12 — verbatim intent)
"I want the picked gauge to pop in the same way the scaling animation works when you press
and hold on the gauge. This needs to be more cohesive. Right now the animation is smooth
and perfect only when you press and hold on the gauge but when you pick a new one the new
gauge pops into place too fast."

On-device confirmed (orchestrator frame captures 2026-08-12): tapping a candidate is a
1-frame hard cut — the new gauge appears full-size instantly.

## Root cause
`GaugeTileGrid` keys tiles by `key(id)`: selecting a candidate changes the slot's id, which
tears down the old tile and mounts the new one fresh at progress 0 (full size). The
OBD-42/44 KDoc records this as "a true cross-fade into the swapped tile isn't possible
from inside this composable" — it needs slot-level state that survives the remount.

## Fix requirements
1. **Grow-in entry:** when a candidate is tapped, the NEW gauge's tile enters at shrink
   progress 1 — positioned/sized at the tapped candidate card's own on-screen rect — and
   animates to progress 0 (full tile), using the SAME animation primitives as the
   long-press shrink: same `PICKER_SHRINK_MS` (300), same easing, same
   `pickerShrinkLayer`/counter-scale/corner+border compensation stack (the machinery is
   already progress-symmetric; drive it in reverse).
2. **Grow origin = the tapped card's rect** (OS-switcher metaphor: the card you tap is the
   thing that grows). Capture its bounds at tap time (before the chrome tears down) and
   hand them across the remount via slot-level state (e.g. a swap-entry record keyed by the
   incoming id). Fallback to the current-card slot rect if the tapped bounds are somehow
   unavailable — never fall back to a pop.
3. **Liveness + single surface:** the growing tile is the real live gauge from first frame
   (value updates mid-grow render), same standard the shrink meets.
4. **Reduced motion:** ANIMATOR_DURATION_SCALE=0 → snap to full, consistent with the
   existing spec resolution (reuse `rememberPickerShrinkAnimationSpec`).
5. **Interaction during grow-in:** long-press on the growing tile may be ignored or queued
   until settled (builder's choice, state it); a second rapid swap on ANOTHER tile must not
   corrupt the first tile's grow (each slot's entry state is its own).
6. **Existing semantics untouched:** dismiss paths (back / tap current / tap outside) keep
   their reverse-shrink behavior; persistence unchanged; the 12 OBD-42 tests stay
   byte-unmodified and green; OBD-44/46 tests stay green unweakened.

## Acceptance criteria
- [ ] Tap candidate → new gauge grows from the tapped card's rect to full tile over
      PICKER_SHRINK_MS with the shared easing; no 1-frame pop
- [ ] New gauge is live during grow (test: push a reading mid-grow, assert it renders —
      mirror the OBD-44 mid-flight test construction, constant-driven timing)
- [ ] Snap path under animator-scale 0 (constant-driven, same construction as OBD-44's)
- [ ] Mount-probe test still green for long-press+dismiss; swap remount count unchanged
      from today (exactly one new mount per swap — no double-mount from the entry state)
- [ ] Full gate.sh PASS; Roborazzi refs re-recorded only if a settled frame changed
      (settled frames should NOT change — this is transition-only)

## Self-test plan
GaugeSwapPickerTest additions using the existing manual-clock constructions; no new
screenshot expected.

## Out of scope
Carousel physics, dismiss-path changes, easing redesign.

## Process (D6 small track)
Builder agent in worktree, branch from develop @ 3288e67; orchestrator reviews; merge
target develop (MERGE_TARGET_BRANCH=develop); dev APK + device verify after merge.
