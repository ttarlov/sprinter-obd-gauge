---
issue: OBD-79
round: 2
reviewer: rev-correctness
verdict: approved
gate: green
reviewed-commit: 2ccdef1
covers: [OBD-79]
hardware-verify: false
---

# OBD-79 — maintenance tracker — round 2 (delta: system-bar insets)

**Verdict: approved.** Delta re-review of the single device-caught fix that landed after the round-1
approval — the Maintenance list/detail screens weren't reserving system-bar insets (header drew
under the status bar; list bottom under the gesture pill). Round-1's approval otherwise stands; this
round covers only `08038b8..2ccdef1`. Gate **GREEN** — I re-ran `tools/gate.sh` myself: PASS on all
7 (`assembleDebug`, `test`, `ktlintCheck`, `detekt`, `verifyRoborazzi`, `assembleDemoDebug`,
`module-isolation`).

## What I verified (delta only)

1. **`safeDrawingPadding()` is placed OUTSIDE `verticalScroll` on both screens.** In each Column's
   modifier chain the order is `fillMaxSize().safeDrawingPadding().verticalScroll(...).padding(...)`
   — `MaintenanceScreen.kt:88-93`, `MaintenanceDetailScreen.kt:63-68`. Because `safeDrawingPadding`
   sits *before* `verticalScroll`, the inset is a fixed outer margin the scroll happens *within*:
   the header begins below the status bar and never scrolls up under it, and the scroll region's
   bottom edge is inset above the nav/gesture pill. The reverse order (`verticalScroll` then
   `safeDrawingPadding`) would have let the inset scroll away with the content — that's the bug, and
   it isn't what landed. Correct fix.
2. **Matches the established idiom.** Identical to `DashboardScreen.kt:487`
   (`Column(modifier = Modifier.fillMaxSize().safeDrawingPadding())`) — same import, same placement
   on the top-level fill-the-screen Column. No new API, no bespoke inset math.
3. **Nothing else changed.** The delta is exactly two source files (one import + the modifier chain
   in each) plus `reviews/OBD-79-round1.md` — which is my own round-1 review commit, expected in
   this range because `08038b8` predates it. No logic, no tests, no seed, no DI, no DataStore, no
   PNGs touched. `git diff --stat`: 3 files, +156/−2.
4. **`SettingsScreen` untouched.** Not in the changed-files list (`git diff --name-only` for
   `settings` → none). The fix is scoped to the two Maintenance screens only.
5. **No screenshot regression.** `verifyRoborazzi` is GREEN with **zero** re-recorded references —
   `maintenance_list.png` / `maintenance_detail.png` are byte-identical to round 1. This is the
   correct outcome: WindowInsets are zero in the Robolectric/Roborazzi environment (no system bars
   simulated), so `safeDrawingPadding` contributes zero padding under test and the layout is
   unchanged there. The fix is a device-only visual correction, exactly as intended, and the gate
   still pins the on-screen layout.

## Fix list

- ✅ `safeDrawingPadding()` placed outside `verticalScroll` on both Maintenance screens — header
  clears the status bar, scroll region clears the gesture pill; matches `DashboardScreen.kt:487`.
- ✅ Change is minimal and scoped — only the two Maintenance screen files (plus round-1's review
  file); `SettingsScreen` and all logic/tests/seed/DI untouched.
- ✅ No Roborazzi regression — refs unchanged, `verifyRoborazzi` green (zero insets under test).
- ✅ Round-1 approval carries forward — the maintenance feature itself is unaffected by this delta.

## Tests

No test changes this round (a pure inset fix with no testable logic branch). The full suite still
passes under the gate; the two Maintenance Roborazzi references remain valid and unchanged.

## Hardware checklist

`hardware-verify: false` — no OBD/BLE path. The inset fix was itself device-caught by Taras; the
on-device confirmation that the header/list now clear the system bars is a non-gating 🖐 check.
