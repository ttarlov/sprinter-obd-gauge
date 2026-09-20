---
issue: OBD-83
round: 2
reviewer: rev-correctness
verdict: approved
gate: green
reviewed-commit: aebee9a
covers: [OBD-83]
hardware-verify: true
---

# OBD-83 — Immersive mode — round 2 (delta: status-bar insets on Settings + Recordings)

**Verdict: approved.** Delta re-review of the folded-in, device-caught fix that landed after the
round-1 approval — the Settings and Recordings screens had the same status-bar safe-area bug the
Maintenance screens had (headers drawing under the status bar). Round-1's approval otherwise stands;
this round covers only `6aa0510..aebee9a`. Gate **GREEN** — I re-ran `tools/gate.sh` myself: PASS on
all 7 (`assembleDebug`, `test`, `ktlintCheck`, `detekt`, `verifyRoborazzi`, `assembleDemoDebug`,
`module-isolation`).

## What I verified (delta only)

1. **`safeDrawingPadding()` is placed OUTSIDE the scroll on both screens.**
   - `SettingsScreen.kt`: the outer Column's chain is now
     `fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(...)` — the
     inset sits *before* `verticalScroll`, so it's a fixed outer margin the scroll happens within:
     the header starts below the status bar and never scrolls under it, and the scroll region's
     bottom clears the nav bar. Correct order (the reverse would let the inset scroll away).
   - `RecordingsScreen.kt`: `safeDrawingPadding()` is added to the **outer, non-scrolling** Column
     (`Modifier.fillMaxSize().safeDrawingPadding().padding(...)`, line 52) that holds the header
     `Row` and the `LazyColumn` (line 68). The scroller here is the inner `LazyColumn`, so the
     padding is trivially outside the scroll — it insets the whole screen, pushing the header below
     the status bar and keeping the list within the inset bounds. Same idiom as `DashboardScreen.kt:487`
     and the `MaintenanceScreen` fix from OBD-79 round 2.
2. **Nothing else changed.** The delta is exactly those two screen files (one import + the modifier
   chain in each) plus `reviews/OBD-83-round1.md` — which is my own round-1 review commit
   (`63ed7fd`), expected in this range because `6aa0510` predates it. No logic, no VM, no codec, no
   tests, no new dependency.
3. **Round-1 immersive-mode code is untouched.** Grepping the source delta for
   `immersive`/`navigationBars`/`WindowInsets` returns hits **only** inside the round-1 review
   markdown in the range — zero in `.kt` source. The `MainActivity` immersive `LaunchedEffect`,
   `AppSettings.immersiveMode`, the codec key, the VM StateFlow/setter, and the Settings toggle are
   all byte-identical to the round-1-approved state.
4. **No screenshot regression.** `verifyRoborazzi` is GREEN with no re-recorded references — correct,
   as WindowInsets are zero in the Robolectric/Roborazzi environment, so `safeDrawingPadding`
   contributes zero padding under test and the layout is unchanged there. The fix is a device-only
   visual correction, and the gate still pins the on-screen layout.

## Fix list

- ✅ `safeDrawingPadding()` outside the scroll on `SettingsScreen` (before `verticalScroll`) and on
  `RecordingsScreen`'s outer non-scrolling Column (the `LazyColumn` is the scroller) — headers clear
  the status bar; matches `DashboardScreen.kt:487` / `MaintenanceScreen`.
- ✅ Change is minimal and scoped — only the two screen files (plus round-1's review file in range);
  no logic/VM/codec/test/dependency change.
- ✅ Round-1 immersive-mode implementation untouched (grep-verified: no `.kt` immersive/insets-
  controller edits in the delta).
- ✅ No Roborazzi regression — refs unchanged, `verifyRoborazzi` green (zero insets under test).
- ✅ Round-1 approval carries forward — the immersive feature is unaffected by this delta.

## Tests

No test changes this round (a pure inset fix with no testable logic branch). The full suite still
passes under the gate; the existing Settings/Recordings tests remain valid.

## Hardware checklist

`hardware-verify: true` — unchanged from round 1. The device gate (🖐 Taras on the Garmin Overlander
API 23 **and** the Pixel: Immersive ON → nav bar hides/reveals-transiently/auto-hides, status bar
stays, OFF restores, no insets jump) remains the merge precondition; this inset fix is itself the
result of Taras's device pass on the earlier build, and its own on-device confirmation (headers now
clear the status bar on Settings/Recordings) folds into that same checklist.
