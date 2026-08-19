---
id: OBD-72
title: Selectable gauge render styles — analog needle + LED bar-arc, in addition to the current digital tile
module: app
owner: ui-agent
sprint: backlog
status: open
type: feature
hardware-verify: true
blocked-by: []
branch: feat/72-gauge-styles
---

## What

Let the user change the **render style** of a gauge. Today every tile renders one way (digital numeric
value + sparkline + threshold coloring). Add at least two more styles and a way to pick per gauge:

1. **Analog needle** — round dial, swept needle, tick scale, gauge label, colored threshold zones.
   Reference: `docs/ui-refs/OBD-72-analog-needle-style.png` (TunerStudio-style dials: Boost, Engine Speed,
   Coolant Temp, etc.).
2. **LED bar-arc** — segmented illuminated bars sweeping ~270° around the perimeter with a large digital
   number + label in the center (retro Intellitronix look). Reference:
   `docs/ui-refs/OBD-72-led-bar-arc-style.png` (RPM 3600, MPH 58, VOLT/FUEL/TEMP/OIL).
3. **Digital** — the current tile stays as the default third option.

Filed by Taras 2026-08-19 as a **backlog** feature ("for later") — do NOT build until scheduled. This is
the spec seed; flesh out the risk/phasing analysis at scheduling time.

## Why

The van dash is a legibility-first, arm's-length, moving-vehicle environment, and different readouts suit
different channels — a needle reads *rate of change* at a glance (boost coming on, RPM climbing), a bar-arc
reads a *level* fast (fuel, temp), digital reads an *exact number*. Giving each gauge the right visual
language is a real ergonomics win, and the retro LED/analog looks fit the rally-instrument aesthetic
([[design_aesthetic]]) far better than a generic digital tile.

## Fits the existing architecture (reuse, don't rebuild)

- Renders inside the existing resizable-grid tiles (`GaugeSlot` / the gauge tile composable, `:app`,
  ui-agent). A render style is a swap of the tile's *content*, not the grid/placement/swap/threshold
  machinery — all of which stay.
- **Threshold coloring is sacred and must carry across ALL styles** (green/amber/red per the config
  object, user-editable — OBD-66): a needle's zone arc, a bar-arc's segment color, and the digital value
  color all read from the same `thresholdOverrides`.
- Persist the choice like the other per-gauge settings — a `gaugeRenderStyle: Map<gaugeId, Style>` (or
  per-tile) added to `AppSettings` alongside `thresholdOverrides` / `gaugeOrder` / `gridLayoutsByColumns`,
  through `SettingsRepository.update` + `SettingsCodec`.
- Selector surfaced through the existing edit flow — most likely an extension of the gear → 3D-flip
  editor (OBD-66) with a style picker, or a face on the swap carousel. Decide at build time.

## The one genuinely new piece: per-gauge full-scale range (min/max)

The current digital tile needs no fixed scale. **Needle and bar-arc both do** — they map a value onto a
fixed sweep, so each gauge needs a defined `scaleMin`/`scaleMax` (e.g. RPM 0–4500, coolant 140–260 °F,
boost −15–25 psi). Today only *thresholds* (yellow/red points) exist, not a full-scale range. This feature
must add per-gauge scale bounds (seeded with researched defaults per channel, like the OBD-66 threshold
seeds; ideally user-adjustable in the same editor). Flag this as the main new data model + the thing to get
right — a wrong scale makes an analog gauge useless.

## Open questions to resolve at scheduling (do not answer now)

- Per-gauge style vs a global dashboard style vs both.
- Where the picker lives (extend the OBD-66 gear/flip editor with a style + scale face? swap carousel?).
- **Legibility at small tile sizes** — needle ticks / bar segments may be unreadable in a 1×1 tile; maybe
  gate certain styles to a minimum span, or auto-simplify (drop ticks/labels) below a size.
- Animation: needle-sweep smoothing + damping (avoid jitter/overshoot on noisy signals like boost);
  bar-arc segment lighting cadence; respect the existing stale-data dimming.
- Does the sparkline persist under needle/bar styles, or is it digital-only.
- Rendering approach: Compose `Canvas`/`drawArc`/`drawIntoCanvas` for both — no external gauge lib.

## Likely shape / phasing (rough, for budgeting later)

Comparable in size to the OBD-62..66 dashboard work (two new render components + a scale model + a picker +
persistence + threshold/scale wiring + screenshot tests + device feel-verify). When scheduled, probably
phase: (1) render-style infra + per-gauge scale model + **needle** style + picker; (2) **LED bar-arc**
style; (3) polish (small-size simplification, animation damping). Or one wave — decide at scheduling.

## Testing (when built)

- Pure: value→sweep-angle / value→segment-count mapping across scale bounds + clamping at min/max.
- Roborazzi: each style rendered for a representative gauge in both orientations + at a couple of tile
  spans, incl. threshold-zone coloring and a stale state.
- Device (🖐 Taras — the real gate): legibility of needle + bar-arc on the dash at arm's length while
  driving; threshold zones read correctly; the picker + scale editing feel right. `hardware-verify: true`.

## Out of scope (unless raised later)

- Fully custom user-drawn gauge faces / themes.
- Importing gauge skins.
- Per-style independent color palettes beyond the shared threshold config.
