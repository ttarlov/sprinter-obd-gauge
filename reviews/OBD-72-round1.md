---
issue: OBD-72
round: 1
reviewer: rev-platform
verdict: approved
gate: green
reviewed-commit: aa56851
covers: [OBD-72]
hardware-verify: true
---

# OBD-72 — selectable gauge render styles (needle + LED bar-arc) — round 1

**Verdict: approved.** Zero blockers, zero majors. Two minor non-blocking observations
(one theoretical, one micro-perf) plus the consolidated taste notes for Taras below. This is a
clean, well-fenced feature: the new render math is pure/tested, persistence is backward-compatible,
and the "threshold coloring is sacred across every style" contract genuinely holds — it is enforced
by the outer tile shell being unconditional, not re-implemented per style.

## What I verified (correctness-first)

1. **Pure render math** (`GaugeRenderMath.kt`, `GaugeScale.kt`) — traced by hand, not just trusted
   the tests:
   - `sweepFraction` clamps to `0f..1f`; `max <= min` degenerate scale returns `0f` (no div-by-zero),
     inverted (`min > max`) returns `0f`. Verified against `GaugeScaleTest`.
   - `needleAngleDegrees` pins the needle at the sweep ends for out-of-range values (no off-dial
     needle). Boundary values at min/max/threshold land exactly (tests pin 0/50/100).
   - `litSegmentCount` rounds + clamps to `[0, totalSegments]`; `totalSegments <= 0` → 0 (no negative
     loop).
   - `thresholdZoneSpans` green/amber/red span math matches `GaugeThresholds.classify` for every
     boundary combination (both set / green-only / red-only / both-null→single NEUTRAL span). The
     bar-arc's `segmentZone` classifies each segment's sweep-midpoint value through the **same**
     `classify`, so a lit bar reads the same colors the needle's zone arc paints.
   - `tickValues` truncates the last step to land on `max`; non-positive tick / degenerate scale
     returns just the two ends (no infinite loop).
2. **Threshold coloring carries across ALL styles.** The needle zone arcs, bar-arc segment colors,
   and digital value all derive from the one `effectiveThresholds()` map (seed + OBD-66 overrides),
   wired `viewModel.thresholds` → `GaugeDashboard(thresholds=)` → each body. The danger-pulse
   red-fill/border is on the **outer** `GaugeTile`/`BoostTile` Box, unconditional above the
   `when(style)` dispatch (`DashboardScreen.kt:1311-1331`), so a red-zone gauge pulses red in every
   style for free. OBD-66 pulsing behavior is untouched. Confirmed sacred, including user overrides.
3. **Persistence / backward-compat** (`SettingsCodec` + `GaugeRenderCodec`) — the upgrade path is
   safe. `decodeAppSettings` reads a blob missing `render_styles`/`scale_overrides` to the empty-map
   defaults → every gauge DIGITAL, every scale `GaugeScaleDefaults.seed` — `SettingsCodecTest`'s
   "default to empty when missing" pins it. Round-trips both new maps. No change to the existing
   gauge-order / threshold / grid / speed-factor encoding (same file, same keys). A corrupted
   persisted scale (`min >= max`) can't crash — the render math defends it directly.
4. **No regression to the existing dashboard.** The four re-recorded screenshots
   (`gauge_threshold_editor`, `dashboard_rearrange_mode`, `dashboard_rearrange_threshold`,
   `dashboard_disconnected_banner_rearrange`) are all **rearrange-mode / editor** frames — exactly
   where the widened gear badge (now on boost/rpm/speed) and the new style-chip row show. The normal,
   non-rearrange **digital dashboard screenshots were NOT re-recorded** → pixel-identical for a user
   who never opens an editor or switches styles. DIGITAL is the default and its body was moved to
   `DigitalGaugeBody` verbatim (same testTags, same layout).
5. **Widened editable gate.** `editable = GAUGE_CATALOG_BY_ID[id] != null` (was TEMPERATURE-only).
   The style-only path is real: `hasEditor` (any catalog id) gates the gear/flip + style picker;
   `hasThresholds` (TEMPERATURE) separately gates the threshold squares/stepper section
   (`GaugePicker.kt` `SwapPage`, `GaugeEditorFace.kt`). A non-threshold gauge (rpm/boost/speed) gets
   a style-only face and cannot NPE — every threshold lookup falls back to `?: GaugeThresholds()`
   (both boundaries null → NEUTRAL, no crash, no zone arc).
6. **Module boundary.** No `com.revel.obdgauge.protocol.*` import anywhere in `app/src/main/`
   (grepped). All new code in `:app`.
7. **Compose correctness.** `zoneSpans`/`ticks`/`lit` are `remember`ed on their inputs; per-frame
   work is cheap float math. `nativeCanvas.drawText` is guarded — `labelPaint` is `null` (and the
   draw skipped) when compact, so no text is drawn on a tile too small for it. Renders safely against
   a placeholder/absent reading (falls back through `resolveTile` + default thresholds/scale).

Trusted the gate as instructed (author: GATE PASS, ~460 green); did not re-run the full Android build.

## Fix list (must-fix before approve)

_None._ ✅ No blocking or major findings. Approving.

**reviewed-commit bumped c3ba824 → aa56851 (round-1 addendum, 2026-08-25).** Since the original
review the branch gained device-verified taste iterations — needle readout relocated into the dial's
bottom gap + its own value scale, per-gauge history sparklines removed (dead code swept clean,
`1816cce`), and the flip-editor controls made tile-relative — plus the whole [[OBD-77]]
expand-in-place editor, which carries its **own** approved review (`reviews/OBD-77-round1.md`,
`18edd45`). All of it is gate-green and Taras-verified on the Garmin + Pixel (see the issue's Hardware
checklist). The bump attests this review holds against the full branch head; OBD-77's own review
covers the editor internals.

## Minor / non-blocking observations

- **[minor, theoretical] NaN reading → NaN needle angle.** `needleAngleDegrees` doesn't special-case
  a NaN `value` (`sweepFraction`'s `coerceIn(0f,1f)` passes NaN through), so a NaN reading would draw
  the needle line to a NaN offset. Compose/Skia no-ops on NaN geometry (no crash), and readings from
  the parsed data source are never NaN today, so this is theoretical — the digital tile would already
  render "NaN" text in that world. `litSegmentCount` is already NaN-safe (`Math.round(NaN)` → 0). If
  you ever want belt-and-suspenders, clamp NaN→0f in `sweepFraction`.
- **[minor, micro-perf] `Paint()` allocated inside the needle Canvas draw block.**
  `NeedleGauge.kt:105-116` news up a `Paint` on each draw pass (up to 4 Hz for a live needle,
  non-compact only). It's cheap and bounded, digital is unaffected, but it's the one draw-scope
  allocation — hoist it into a `remember` if the needle ever feels janky on the real dash.

## Taste notes for Taras (non-blocking — your call, consolidated for the morning)

These are the design decisions the author deliberately made/deferred. All defensible; none gate the
merge. Flagging so they're in one place before your device feel-verify (`hardware-verify: true` stays
your gate).

1. **Scale-editing UI deferred.** The per-gauge min/max/tick data model + persistence + seed are
   wired end-to-end, but the editor face exposes **only** the style picker + (for temps) the existing
   threshold squares — no min/max/tick stepper yet. The spec called scale-editing "ideally
   user-adjustable"; the author shipped the researched seeds and left the editing UI for a follow-up.
2. **Needle color is orange (`GaugeNeedleAccent #E0824A`), not red** — deliberate, so a needle
   sitting in a green zone doesn't itself read as a danger signal; the colored zone **arc** carries
   the threshold vocabulary, the needle is just the pointer. Matches your rally/teal-orange aesthetic.
3. **Danger-pulse shows behind the needle/bar dial.** Because the pulse is the unconditional outer
   shell (that's what makes coloring "free"), a red gauge pulses its whole tile red behind the dial.
   Correct for the contract; worth an eyeball on whether a pulsing red field behind a needle reads
   well or is too busy.
4. **Sweep = 135° start / 270°**, reusing the existing `BoostArc` convention (empty wedge at the
   bottom) so every arc gauge on the dash shares one shape.
5. **Bar-arc segment counts: 20 normal / 10 compact, 3° gap.** Judgment values for legibility.
6. **Compact simplification kicks in below 120 dp** (`GAUGE_COMPACT_SIZE_DP`): needle drops tick
   labels + thins to two end ticks; bar-arc drops to 10 chunkier segments. Check the 1×1 references.
7. **RPM ceiling 5000 is the soft number** — no documented OM642 factory rev-limiter; it's judgment
   (already tracked as your open Q). Override from a real tach reading if you capture one; it only
   needs a `scaleOverride`, no code change.
8. **Boost stays neutral in the new styles** (no zone arc on the needle; blue lit segments on the
   bar-arc, since it has no thresholds) and the speed-density "Est." badge carries over from the
   outer overlay unchanged — consistent with the digital tile's treatment.

## Bottom line

Correct, backward-compatible, and properly fenced. The one genuinely new data model (per-gauge
scale) is defended against every degenerate input, and the "sacred" coloring requirement is
structurally guaranteed rather than duplicated. Nothing here is a correctness defect — the two minor
notes are a theoretical NaN edge and a micro-allocation, and everything else is taste for your device
pass. Approving; `hardware-verify: true` remains your gate.
