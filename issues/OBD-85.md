---
id: OBD-85
title: Fix EmptyCellAddButton zero-bounds under Robolectric so the empty-cell add flow is testable
module: app
owner: ui-agent
sprint: grid-feature
status: open
type: bug
hardware-verify: false
blocked-by: []
branch: feat/85-empty-cell-add-bounds
---

## What (from OBD-84 review, 2026-09-12)

During OBD-84 review, a `GridEditTest` case ("an empty cell's add button still opens the palette
targeted at that cell") was `@Ignore`d because it never actually tested what it claimed — it was
passing by accident and OBD-84's header change removed the accident.

**Root cause (verified independently on `main`):** `EmptyCellAddButton` (the rearrange-mode empty-cell
"+" target, `gauge/grid/RearrangeMode.kt`) reports `getBoundsInRoot() == (0,0,0,0)` under Robolectric —
a permanent measurement quirk, not a timing flake (confirmed identical after 50 forced frame advances,
and identical on unmodified `main`). So the test's `performTouchInput { click() }` dispatched at the
root origin `(0,0)`. On `main`, `LinkState.Ready` hid the `ConnectionBanner`, leaving the header's own
generic "＋ Add" button pinned at `left=0` — so the (0,0) click accidentally hit THAT button, which also
opens the palette, masking that the empty-cell button was never exercised. OBD-84's always-visible pill
correctly pushes the header ＋Add off `(0,0)`, exposing the bogus test.

**Not a regression:** on a real device `EmptyCellAddButton` has real bounds and its tap fires correctly
(device-verified in OBD-67 r8/9); the real add-at-cell flow still works with the pill present (OBD-84
reviewer confirmed). Only the Robolectric-only test measurement is broken.

## Scope

Make `EmptyCellAddButton`'s bounds report correctly under Robolectric (or make the test target it
deterministically) so the "add at empty cell" flow becomes genuinely testable, then un-`@Ignore` the
`GridEditTest` case and assert it exercises the REAL empty-cell button (not a screen-corner coincidence).

Investigate why the composable measures to zero under Robolectric — likely a layout/measurement
interaction in the `GaugeGrid` custom `Layout` or the empty-cell overlay's modifier chain
(`grid/GaugeGrid.kt`, `grid/RearrangeMode.kt`, `grid/GridMetrics.kt`). Consider a test-only hook (a
stable testTag + `onNodeWithTag(...).performClick()` that doesn't depend on bounds) if the true
measurement fix is out of proportion. Whatever the approach, the un-ignored test must FAIL if the
empty-cell add wiring breaks (i.e. actually target that button).

## Testing
- Un-`@Ignore` the `GridEditTest` case; it must pass by genuinely clicking `EmptyCellAddButton` and
  asserting the palette opens targeted at that cell — and fail if that wiring regresses.
- `tools/gate.sh` green.

## Priority
Low / non-blocking. Test-infra hardening; no user-facing behavior change. Filed so the coverage gap
OBD-84 surfaced doesn't get lost.
</content>
