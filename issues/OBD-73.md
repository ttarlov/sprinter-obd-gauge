---
id: OBD-73
title: "Dumb mode" — emoji-face temp readouts (settings toggle) mapped to threshold bands
module: app
owner: ui-agent
sprint: backlog
status: open
type: feature
hardware-verify: true
blocked-by: []
branch: feat/73-dumb-mode
---

## What

A **global settings toggle** — Taras's working name is **"Dumb mode"** (final user-facing label his call;
"Glance mode" / "Simple mode" are alternatives) — that replaces the **numeric temperature readout** on temp
gauges with an **emoji face** that reflects how the reading sits against that gauge's threshold bands:

- **Good / normal** → 😊 (happy)
- **Warming** (climbing toward the warning band) → 😐 (concerned)
- **Warning** (at the red threshold — e.g. coolant **225 °F**) → 😢 (sad)
- **Critical** (well past the warning) → 😭 (crying)

The point is arm's-length glanceability on a moving dash: a face reads "van happy vs. van dying" faster than
a number. Filed by Taras 2026-08-24 as a **backlog** feature — do NOT build until scheduled.

## Why

Same dash-mount, don't-make-me-read-while-driving ethos the whole app is built on. For the health gauges
(coolant / oil / trans), the *exact* number matters less than the *state* — a passenger or a quick glance
gets "everything's fine" or "pull over" instantly. It's a legibility win, not a gimmick.

## Fits the existing architecture (reuse, don't rebuild)

- **The threshold bands already exist** (OBD-66): each gauge has user-editable green/yellow/red thresholds
  (`thresholdOverrides`, seeds — coolant 215/225, trans 215/240, oil 245/260 °F, as greenMax / redMin). Dumb
  mode maps the live value onto a face **using those same bands**, so it auto-tracks whatever thresholds the
  user has set — no new config for the breakpoints.
- Persist the toggle in `AppSettings` (like `keepScreenOn`) via `SettingsRepository.update` + `SettingsCodec`;
  surface the switch in `SettingsScreen`.
- It's a **render swap of the tile's value area**, not a change to the grid/threshold/swap machinery — those
  stay. Threshold *coloring* still applies (the tile can still pulse red in the danger zone per OBD-66); the
  face and the color reinforce each other.

## Emoji breakpoints (derive from thresholds — tunable at build time)

Map the live value to a face using the gauge's own bands (`greenMax` = yellow point, `redMin` = red point):

| Band | Condition (temps) | Face |
|---|---|---|
| Good | value < greenMax | 😊 |
| Warming | greenMax ≤ value < redMin | 😐 |
| Warning | redMin ≤ value < redMin + margin | 😢 |
| Critical | value ≥ redMin + margin | 😭 |

Matches Taras's mapping (good → concerned as it warms → sad at ~225 red → crying hotter). `margin` (e.g.
+10–15 °F, or a fraction of the band) is a design knob. Keep the mapping a **pure function**
`faceFor(value, thresholds): Face` so it's unit-tested exactly. Stale/no-data → a neutral/absent face
(reuse the existing stale-dimming).

## The Android-6 / Garmin gotcha (call out — this ships to minSdk 23)

**Do NOT rely on the platform emoji font.** Android 6.0.1 (the Garmin Overlander) ships an old, incomplete
emoji set — several of these faces render as tofu boxes, wrong, or ugly, and vary across devices. Options,
decide at build time: (a) **draw simple faces with Compose `Canvas`** (a few circles + arcs — happy/flat/frown/
tears) for pixel-identical rendering on every device and full control of size/contrast at arm's length — the
recommended path; or (b) bundle an emoji font / vector assets. Native Unicode emoji is the one thing to avoid.
This is exactly the kind of platform-variance bug that only shows on the real Garmin (see the OBD-70
JVM-vs-Android regex crash — device reality ≠ dev environment).

## Open questions to resolve at scheduling (don't answer now)

- **Which gauges?** Temps (coolant/oil/trans) clearly. Non-temp gauges (RPM/speed/boost/voltage) have no
  "hot health" semantics — in dumb mode do they stay numeric, hide, or get their own state faces? Default
  guess: dumb mode is temps-only; everything else stays numeric.
- Global toggle vs. per-gauge (a gauge could individually opt into faces). Taras framed it global; per-gauge
  is a possible superset.
- Relationship to **OBD-72** (selectable gauge styles): dumb mode is essentially an "emoji face" render style.
  If OBD-72 lands first, this could be "emoji style, flipped on globally by a settings switch." Buildable
  independently too — decide sequencing at scheduling.
- Number-only vs. face + small number (belt-and-suspenders) — Taras said replace the number; confirm.

## Testing (when built)

- Pure: `faceFor(value, thresholds)` across all bands + boundary values (exactly at greenMax / redMin /
  critical margin) + stale.
- Roborazzi: each face state rendered on a temp tile, both orientations; dumb-mode-on vs -off.
- Device (🖐 Taras — the real gate): faces legible at arm's length on the **Garmin (API 23)**, render
  identically (no tofu), and track reality on a drive as the engine warms. `hardware-verify: true`.

## Out of scope (unless raised later)

- Custom user-chosen emoji per band / face themes.
- Sound/haptic alerts on state change.
- Faces for non-health channels (RPM/speed) — revisit only if wanted.
