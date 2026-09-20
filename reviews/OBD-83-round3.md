---
issue: OBD-83
round: 3
reviewer: rev-correctness
verdict: approved
gate: green
reviewed-commit: e67b2df
covers: [OBD-83]
hardware-verify: true
---

# OBD-83 — Immersive mode — round 3 (delta: rebase onto post-OBD-87 main + detekt refactor)

**Verdict: approved.** Fresh independent round-3 delta review after two rebases carried
`feat/83-immersive-mode` from its round-1/round-2-approved state (`aebee9a`, pre-OBD-71/78/82/84/86/87)
onto current `main` (`48414ce`). The only unreviewed code delta introduced by the rebases is a
detekt-driven refactor in the first rebase commit; the second rebase carried no further app changes
(`e67b2df` is the tip). Gate **GREEN** — I re-ran `tools/gate.sh` myself: PASS on all 7
(`assembleDebug`, `test`, `ktlintCheck`, `detekt`, `verifyRoborazzi`, `assembleDemoDebug`,
`module-isolation`). Taras has device-verified immersive mode works on the Garmin.

## What I verified

### 1. The detekt refactor — the main unreviewed delta

**`SettingsScreen.kt`** (`app/src/main/kotlin/com/revel/obdgauge/app/settings/SettingsScreen.kt:339-385`):
the previously-separate `KeepScreenOnSection` private fun plus the OBD-84 inlined
"Show connection status" row were regrouped into one `DisplaySection(keepScreenOn, onSetKeepScreenOn,
showConnectionStatus, onSetShowConnectionStatus, immersiveMode, onSetImmersiveMode)`. Confirmed all
three rows render with correct testTags, labels, and callbacks, each still separated by its own
`HorizontalDivider()`:
- `keep-screen-on-switch` — "Keep screen on" — `onSetKeepScreenOn` (line 359-363)
- `show-connection-status-switch` — "Show connection status" — `onSetShowConnectionStatus` (line
  365-371)
- `immersive-mode-switch` — "Immersive mode" + the "Hides the navigation bar; swipe up to show it"
  caption — `onSetImmersiveMode` (line 373-385)

Nothing dropped or mis-wired; the rendered layout is identical to the three formerly-separate rows,
confirmed against `SettingsScreenTest.kt`'s existing per-toggle click tests (all three still pass
under the gate). `safeDrawingPadding()` is still present, still outside the scroll, on both
`SettingsScreen.kt:80` (before `.verticalScroll(...)`) and `RecordingsScreen.kt:52` (outer
non-scrolling Column) — unchanged from round 2.

**`MainActivity.kt`** (`app/src/main/kotlin/com/revel/obdgauge/app/MainActivity.kt:172-192`): the two
window `LaunchedEffect`s (keepScreenOn's `FLAG_KEEP_SCREEN_ON`, immersive's
`WindowInsetsControllerCompat` nav-bar hide) were merged into one `LaunchedEffect(keepScreenOn,
immersiveMode)`. I diffed this against the round-2-approved shape (`git show 5331531:...MainActivity.kt`)
to isolate exactly what changed:

- **Before:** `LaunchedEffect(keepScreenOn) { addFlags/clearFlags }` and a separate
  `LaunchedEffect(immersiveMode) { controller.hide/show }`, each recomposing only on its own key.
- **After:** one `LaunchedEffect(keepScreenOn, immersiveMode)` that runs both blocks every time
  *either* key changes.

This is behavior-preserving:
- `window.addFlags`/`clearFlags(FLAG_KEEP_SCREEN_ON)` are idempotent — re-issuing the same call
  when only `immersiveMode` changed (keepScreenOn unchanged) is a no-op against window state.
- `controller.hide(navigationBars())` / `controller.show(navigationBars())` (plus
  `systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` when hiding) are likewise idempotent —
  re-issuing when only `keepScreenOn` changed produces no visible change.
- `statusBars()` is still never touched — only `navigationBars()` appears in the merged block,
  preserving the "status bar always visible" invariant from round 1.
- Both effects still fire correctly on independent changes: toggling keepScreenOn alone re-applies
  the (unchanged) immersive state harmlessly; toggling immersive alone re-applies the (unchanged)
  keepScreenOn flag harmlessly. Neither toggle can suppress or clobber the other's effect — both
  branches run unconditionally on every key change, there's no early return.
- No suspend calls inside the effect, so LaunchedEffect's cancel-and-restart-the-coroutine semantics
  on key change carry no unflushed-work risk.

I don't see a way this regresses either flag under real toggling: the change is a strict merge of
two independently-idempotent effect bodies under a wider recomposition trigger, not a change to
either body's logic. Confirmed against the code — not just inferred from the comment — since a
subtle bug here (e.g. accidentally gating the keepScreenOn block behind `if (immersiveMode)`) is
exactly the kind of thing device-testing immersive alone wouldn't catch. Fix list item below is a ✅.

### 2. Nothing dropped in the rebase

- **OBD-83's own settings plumbing** — `AppSettings.immersiveMode` (default `false`,
  `AppSettings.kt:92-98`), `SettingsCodec`'s `KEY_IMMERSIVE_MODE` encode/decode
  (`SettingsCodec.kt:37,83-85,138`), `SettingsViewModel.setImmersiveMode`
  (`SettingsViewModel.kt:85`), and `DashboardViewModel.immersiveMode` StateFlow
  (`DashboardViewModel.kt:222-230`) all present and coexist cleanly with OBD-84's
  `showConnectionStatus` fields/keys/setter/flow — same pattern, no collision, verified field-by-field
  in the diff.
- **OBD-71/78/82 reconnect wiring** — `LinkController`, `ConnectionServiceController`,
  `EngineOffPromptHost` (wired at `MainActivity.kt:180`) all present and untouched by this branch's
  diff (`git diff main...HEAD` touches only immersive-mode-related files plus the two review docs).
- **OBD-84 connection-status pill** — `ConnectionBanner`'s `showConnectionStatus` gate, threaded
  through `DashboardScreen`/`DashboardViewModel`, intact and untouched.
- **OBD-87 instant MPG** — `InstantMpgDataSource` (`app/src/prod/kotlin/.../datasource/InstantMpgDataSource.kt`)
  and its `DataSourceModule` wiring (`app/src/prod/kotlin/.../di/DataSourceModule.kt:86`) are present
  and untouched; `INSTANT_MPG_PID_ID`/`INSTANT_MPG_PID_DEFINITION` in `GaugeCatalog.kt` intact. (Not
  clobbered by this branch, which never touches the `prod` source set or `GaugeCatalog.kt`.)
- **Full delta scope check**: `git diff main...HEAD --stat` touches exactly `MainActivity.kt`,
  `DashboardViewModel.kt` (adds `immersiveMode` flow only), `RecordingsScreen.kt` (inset fix, round
  2), `AppSettings.kt`, `SettingsCodec.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`, three test
  files, and the two prior review docs — a tight, expected surface for "immersive setting +
  round-2's inset fix + round-3's detekt refactor." No accidental reverts of post-`aebee9a` main
  commits show up in the diff.

### 3. Gate

Re-ran `tools/gate.sh` myself (not reused from a prior round): **PASS** on `assembleDebug`, `test`,
`ktlintCheck`, `detekt` (confirms the LongMethod/function-count refactor actually satisfies the
rules it was written for), `verifyRoborazzi`, `assembleDemoDebug`, `module-isolation`.

### 4. Scope / module isolation / dependencies

No new dependency (the `androidx.core.view.WindowInsetsControllerCompat`/`WindowInsetsCompat` imports
in `MainActivity.kt` are pre-existing round-1 code, unchanged by this rebase — no `build.gradle`/
version-catalog diff). `module-isolation` sanity check passes (17 OWNERSHIP entries parse clean).
Scope stays immersive-mode + its round-2 padding fix + this round's pure refactor — no unrelated
feature code.

## Fix list

- ✅ `DisplaySection` refactor in `SettingsScreen.kt` preserves all three toggles' testTags, labels,
  and callbacks (`keep-screen-on-switch`, `show-connection-status-switch`, `immersive-mode-switch`) —
  verified against source and against `SettingsScreenTest.kt`'s passing per-toggle tests.
- ✅ Merged `LaunchedEffect(keepScreenOn, immersiveMode)` in `MainActivity.kt` is behavior-preserving:
  both branches run unconditionally on every key change (no cross-gating), `addFlags`/`clearFlags`
  and `controller.hide`/`show` are idempotent, `statusBars()` is still never touched.
- ✅ `safeDrawingPadding()` placement (round 2's fix) unchanged on both `SettingsScreen.kt` and
  `RecordingsScreen.kt`.
- ✅ Rebase dropped nothing: OBD-83 settings plumbing, OBD-71/78/82 reconnect, OBD-84 connection
  pill, and OBD-87 instant MPG all present and untouched on the branch tip; diff scope is tight.
- ✅ Gate GREEN on my own re-run, all 7 checks; `module-isolation` clean; no new dependency.

## Tests

No new test *logic* this round beyond what round 1 already added (immersive switch click test in
`SettingsScreenTest.kt`, codec round-trip in `SettingsCodecTest.kt`, wiring in `LiveRecolorTest.kt`)
— all still pass unchanged under the refactor, which is itself good evidence the refactor didn't
alter observable behavior. No test covers the merged-`LaunchedEffect` idempotency claim directly
(Robolectric doesn't exercise `WindowInsetsController`/`FLAG_KEEP_SCREEN_ON` against a real window),
so that correctness call rests on the code-level idempotency argument above, not a new automated
check — consistent with how round 1 already treated this window-behavior code as device-verified
rather than unit-tested.

## Hardware checklist

`hardware-verify: true` — carried forward from rounds 1-2. Taras has device-verified immersive mode
on the Garmin Overlander post-rebase. This round's refactor is Compose/Kotlin-structural only (no
behavior change per the analysis above), so it doesn't reopen the device checklist on its own, but
if Taras hasn't specifically re-checked **keepScreenOn** since this rebase (he may only have been
watching immersive while testing), that's the one thing I'd flag worth a quick confirm — not because
I found a bug, but because the merged effect is the one place a regression *could* have hidden.
