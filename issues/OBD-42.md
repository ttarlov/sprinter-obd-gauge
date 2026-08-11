---
id: OBD-42
title: In-place gauge swap — long-press carousel picker
module: app
owner: ui-agent
sprint: backlog
status: in-progress
type: feature
hardware-verify: false
blocked-by: []
branch: ui/42-gauge-swap-carousel
---

## Feature
Long-pressing a gauge tile swaps what that tile shows, in place: the live gauge sinks into
its own frame (card-inside-card), revealing a horizontal carousel of candidate gauge cards
scrollable INSIDE the tile's frame; tapping a candidate makes it take the slot, persisted.

## Requested UX (Taras, 2026-08-11 — verbatim intent)
- Press-and-hold on an existing gauge → the current gauge "sinks into the frame of itself so
  it looks like a card inside a card" — a depth cue that the tile is now in picker mode.
- In picker mode you can "scroll side to side to pop in other gauges": candidates render as
  sunken mini gauge-cards in a carousel within the tile bounds.
- Tapping a mini-card swaps it into the slot, replacing the old gauge.
- Candidates are prebuilt VERIFIED gauges (coolant, TPS, engine load, RPM, etc.).

## Contract surface
None expected. Swap = replacing the pid id at that position in OBD-21's persisted
`gaugeOrder`. Candidate catalog from `DASHBOARD_PIDS`/settings; verified-only filtering uses
the existing PidCatalog hook once prod wiring lands (demo: all fake channels count).

## Acceptance criteria
- [ ] Long-press enters picker mode on that tile only; visual sink effect (scale/depth) on
      the current gauge; the rest of the dashboard stays live
- [ ] Horizontal carousel of candidate mini-cards inside the tile frame; currently-shown
      gauge indicated; candidates exclude gauges already displayed on other tiles
- [ ] Tap candidate → swap animates in, persists via gaugeOrder (survives restart)
- [ ] Dismiss without choosing: tap the current gauge / back / tap outside — no change
- [ ] Works in both orientations; reachable on every tile
- [ ] Swap correctness is test-pinned: after swapping, the tile's value/label/threshold
      coloring ALL come from the new pid (a mismatched label-vs-value is a lying gauge)
- [ ] Demo flavor demonstrates it: RPM available as a swap-in candidate
- [ ] Compose tests: enter/dismiss/swap/persist paths; screenshot of picker mode

## Self-test plan
Robolectric compose tests against FakeVehicleDataSource (rpm channel exists unused —
becomes the demo candidate); settings persistence via temp-file DataStore as in
LiveRecolorTest; Roborazzi reference for picker mode.

## Out of scope
New PID definitions (TPS 0111, engine load 0104 → OBD-43); reordering via drag (settings
screen already does ordering); multi-select.
