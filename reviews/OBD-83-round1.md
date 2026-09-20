---
issue: OBD-83
round: 1
reviewer: rev-correctness
verdict: approved
gate: green
reviewed-commit: 6aa0510
covers: [OBD-83]
hardware-verify: true
---

# OBD-83 — Immersive mode (Settings toggle, hide nav bar, keep status bar) — round 1

**Verdict: approved.** Zero blockers, zero majors. A textbook mirror of the OBD-21 `keepScreenOn`
setting: an additive default-OFF boolean, its codec key, a `DashboardViewModel` StateFlow, a
`SettingsViewModel` setter, a Settings toggle, and a `MainActivity` `LaunchedEffect` that applies it
to the window via the `androidx.core` compat controller. The single most important requirement —
**the status bar stays visible** — holds: the effect touches only `navigationBars()`. Gate
**GREEN** — I re-ran `tools/gate.sh` myself: PASS on all 7 (`assembleDebug`, `test`, `ktlintCheck`,
`detekt`, `verifyRoborazzi`, `assembleDemoDebug`, `module-isolation`).

Note on the merge gate: `hardware-verify: true`. This approval clears the *code*; the device gate
(🖐 Taras, on the Garmin API 23 **and** the Pixel: nav bar hides/reveals-transiently/auto-hides,
status bar stays, OFF restores) is a separate, non-automatable precondition for merge and is not
satisfied by this review.

## What I verified (correctness-first)

1. **Status bar stays visible — the load-bearing requirement, VERIFIED.** The immersive
   `LaunchedEffect` (`MainActivity.kt`, new block) calls **only** `controller.hide(
   WindowInsetsCompat.Type.navigationBars())` (ON) and `controller.show(...navigationBars())` (OFF).
   I grepped every added `+` line in the whole diff for `statusBars`/`systemBars`/`navigationBars`:
   the *only* `Type` referenced is `navigationBars()`; `statusBars()` appears solely in a comment
   saying it is deliberately left alone; `systemBars()` never appears at all. `systemBarsBehavior`
   is a controller property (how hidden bars behave), not a bar being hidden — it does not touch the
   status bar. So the clock/battery can never be hidden by this code.
2. **Sticky-immersive ON; `show()` fully restores OFF.** ON sets
   `systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` (swipe-up reveals the nav bar
   transiently, then it auto-hides) then hides `navigationBars()` — correct sticky-immersive. OFF
   calls `show(navigationBars())`. I reasoned about whether OFF needs to also reset
   `systemBarsBehavior` back to `BEHAVIOR_DEFAULT`: **it does not.** `systemBarsBehavior` only
   governs how a *hidden* bar behaves; a shown bar is simply shown. Once OFF calls `show()`, nothing
   in this app hides the nav bar again while the toggle is OFF (the only `hide()` is the ON branch,
   which re-sets the behavior itself), so the residual behavior constant is dormant and produces no
   observable difference — the bar returns and stays. `show()` alone is sufficient and correct.
3. **`keepScreenOn` mirror is exact, and backward-compat holds.** New `immersiveMode: Boolean =
   false` on `AppSettings`; `KEY_IMMERSIVE_MODE = booleanPreferencesKey("immersive_mode")`; decode
   is `preferences[KEY_IMMERSIVE_MODE] ?: defaults.immersiveMode` (per-field default-on-missing,
   identical to `keepScreenOn`); encode writes the key; `DashboardViewModel.immersiveMode` StateFlow
   and `SettingsViewModel.setImmersiveMode` copy the `keepScreenOn` shape line-for-line. **An
   existing persisted settings blob written before OBD-83 has no `immersive_mode` key and decodes to
   `false`** — no decode break, no surprise behavior. The codec test is real: round-trip asserts an
   enabled toggle survives, and the same test asserts `emptyPreferences()` → `false`; the
   comprehensive "full settings object round-trips" test also now carries `immersiveMode = true`.
4. **The two `window` `LaunchedEffect`s are disjoint and cannot race.** The `keepScreenOn` effect
   toggles `window.addFlags/clearFlags(FLAG_KEEP_SCREEN_ON)`; the immersive effect drives
   `WindowInsetsControllerCompat(window, window.decorView)` on `navigationBars()`. Different window
   surfaces (a window flag vs. the insets controller), different keys (`keepScreenOn` vs.
   `immersiveMode`), no shared mutable state — each re-runs independently when its own flag changes.
   Both run on the composition's Main dispatcher, so the insets-controller calls are correctly on
   the UI thread. No interaction, no ordering dependency.
5. **API-23 (Garmin) compat is the right API, no new dependency.** Uses
   `WindowInsetsControllerCompat` + `WindowInsetsCompat.Type.navigationBars()` +
   `WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` — all `androidx.core.view`
   compat surfaces that back-fill to the legacy `systemUiVisibility` path on API 23, **not** the raw
   API-30 `window.insetsController` (which would NoClassDefFound/crash below 30). No
   `libs.versions.toml`/`build.gradle` change in the diff — the symbols come from the already-present
   `androidx.core`, confirmed by the green `assembleDebug`/`assembleDemoDebug`. Device behavior on
   API 23 itself is the hardware gate, correctly deferred to Taras.
6. **Isolation + scope clean.** All touched files are `:app` (settings/gauge/MainActivity + tests);
   no `:core:ble`/`:core:protocol` imports; no icon dependency (the toggle is `Switch` + `Text`, same
   as `KeepScreenOnSection`). Default OFF means zero behavior change until a user opts in. The Settings
   toggle was inlined rather than extracted to a private composable, matching the file's existing
   detekt-function-count discipline (the Recordings section does the same). `module-isolation` PASS.

## Fix list

- ✅ Status bar kept — the effect touches only `navigationBars()`; `statusBars()`/`systemBars()` are
  never hidden (grep-verified across the whole diff).
- ✅ Sticky-immersive ON (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` + hide nav) and OFF (`show` nav)
  correct; `show()` alone fully restores the bar — no behavior-constant reset needed (reasoned).
- ✅ `keepScreenOn` mirror exact; backward-compatible decode (missing key → `false`); real codec
  round-trip + default-on-missing test.
- ✅ The immersive and keepScreenOn `LaunchedEffect`s touch disjoint window surfaces, keyed
  independently, both on the UI thread — no race.
- ✅ API-23 compat via `androidx.core` `WindowInsetsControllerCompat`, not raw API-30; no new
  dependency.
- ✅ Module isolation, no icon dep, scope clean (default OFF, toggle-only), plus a real
  `SettingsScreenTest` interaction test for the switch.

## Tests

- Pure JUnit: `SettingsCodecTest` — "OBD-83 immersive mode round-trips, and defaults to false when
  missing" (present→survives, absent→false), plus `immersiveMode = true` folded into the full-object
  round-trip.
- Compose UI: `SettingsScreenTest` — "immersive-mode switch reports the toggled value" (clicks
  `immersive-mode-switch`, asserts the reported boolean). `LiveRecolorTest` updated for the new
  `SettingsScreen` callback param (mechanical). `test` gate step green.

## Hardware checklist

`hardware-verify: true` — the merge gate requires the issue's `## Hardware checklist` with Taras's
observed values on the **Garmin Overlander (API 23)** and the **Pixel**: Immersive ON → nav bar
hides, content fills the reclaimed space, swipe-up reveals it transiently then it auto-hides, status
bar stays visible throughout; OFF → nav bar returns and stays; no insets jump when the bar peeks.
This code review does not and cannot satisfy that gate.
