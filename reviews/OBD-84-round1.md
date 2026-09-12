---
issue: OBD-84
round: 1
reviewer: rev-correctness
verdict: approved
gate: green
reviewed-commit: 1edd1cf
covers: [OBD-84]
hardware-verify: false
---

# OBD-84 review — round 1 (rev-correctness, combined lens)

Bottom line: **approved.** The permanent connection-status pill is a clean, well-tested UI-side
fusion of `LinkState` + `dataFlowing`. The pure mappers stay pure and are exhaustively tested, the
`showConnectionStatus` setting is a faithful `keepScreenOn` clone (default-**true**, round-trips,
default-on-missing), the header-height gotcha is genuinely guarded by a real assertion, and the
frozen `LinkState` contract is untouched. Module isolation is clean, no new dependency, scope tight.
Gate is green — I re-ran `tools/gate.sh` myself (see below), not trusting the paste.

The one item that needed real adversarial work — the `@Ignore`d `GridEditTest` case — I verified
independently by running probe tests on **both** `main` and the branch. **The `@Ignore` is
legitimate**: the test never exercised the real empty-cell add button on `main` either, and OBD-84
introduces **no regression** to the actual add-at-cell flow. Details in the crux section. One
recommended follow-up (fix `EmptyCellAddButton`'s zero-bounds so it becomes testable) and two
non-blocking notes.

`issues/OBD-84.md` is `hardware-verify: false` (pure UI state-fusion). This approval covers
correctness; Taras's van/Garmin smoke walk (Disconnected → Connecting → Connected·waiting → Live,
toggle-off on a healthy link) remains the real acceptance but does not gate the merge.

---

## Gate (re-run, not trusted)

`tools/gate.sh` on `f9dd179` with the prescribed `JAVA_HOME`/`ANDROID_HOME`:

```
PASS  assembleDebug
PASS  test
PASS  ktlintCheck
PASS  detekt
PASS  verifyRoborazzi
PASS  assembleDemoDebug
PASS  module-isolation (sanity)
GATE: PASS
```

---

## THE CRUX — the `@Ignore`d `GridEditTest` (independently verified)

**Question posed:** with the pill present, does the empty-cell "+" add-button still open the palette
targeted at that cell? Is the `@Ignore` legitimate, or is it masking a real regression?

**Answer: the `@Ignore` is legitimate. There is no regression to the real add-at-cell flow.** The
test was already bogus on `main` — it never touched `EmptyCellAddButton`; its pass depended on an
accidental (0,0) coincidence that OBD-84 correctly removes.

I did not take the builder's word for it. I built a detached worktree at `main` (`7c453f6`) and a
probe in the branch worktree, and measured the actual Robolectric bounds:

**On `main`** (`ObD84ProbeTest` — Ready fixture, rearrange mode, `w800dp-h360dp-land`):
```
emptyCell(gauge-rearrange-add).boundsInRoot = DpRect(left=0.0, top=0.0, right=0.0, bottom=0.0)
header(rearrange-add-button).boundsInRoot    = DpRect(left=0.0, top=4.0, right=75.0, bottom=44.0)
```
`EmptyCellAddButton` reports **zero bounds** under Robolectric — so `performTouchInput { click() }`
dispatches at root **(0,0)**, which is nowhere near the real empty cells (they live in the spare
row far below). Because `LinkState.Ready` hides `ConnectionBanner` entirely on `main`, the header
`＋ Add` button is pinned to the **left edge (left=0)** and its expanded (48dp min) touch target
covers the origin — so the (0,0) tap lands on the header button, which also opens `gauge-add-palette`.
The assertion passed **for the wrong reason**. I also confirmed the test is present and NOT
`@Ignore`d on `main`, so it does run and pass there.

**On the branch** (`f9dd179`, same fixture, pill present because Ready + all-fresh readings →
`dataFlowing = true` → "Live" pill fills the `weight(1f)` slot):
```
emptyCell.boundsInRoot = DpRect(left=0.0, top=0.0, right=0.0, bottom=0.0)   <- STILL zero
header.boundsInRoot    = DpRect(left=469.0, top=4.0, right=544.0, bottom=44.0)  <- pushed right
palette opened after (0,0) click (pill present)? = false
```
The empty-cell button's bounds are **unchanged** (OBD-84 doesn't touch it), and the header button is
now pushed to `left=469`, so the (0,0) tap hits nothing and the palette does not open — exactly the
failure the `@Ignore` describes.

**Why this is not a regression to the real flow:**
- `RearrangeMode.kt` (which owns `EmptyCellAddButton` and its non-consuming tap handler),
  `GridMetrics`, and `GaugeGrid` are **byte-identical to `main`** (`git diff main...HEAD` touches
  none of them — verified). The empty-cell button's `onTap → showAddPalette(target cell)` path is
  unchanged.
- OBD-84's only `DashboardScreen.kt` change is inside the **header Row** (add a testTag + thread
  `dataFlowing`/`showConnectionStatus` into `ConnectionBanner`). Header **height** is asserted
  constant across states (see below), so the grid viewport — and thus every empty cell's real
  position — is unchanged. The pill only changes **horizontal** allocation *within the header*, not
  the grid geometry below it.
- The zero-bounds is a Robolectric measurement artifact of the offset-positioned
  (`Modifier.offset { IntOffset(...) }`) empty cells; Robolectric **structurally cannot** exercise
  this flow on `main` or on the branch. On a real device the button has real bounds and its tap
  fires unchanged — and this exact button is device-verified (OBD-67 rounds 8/9).

So the real "tap an empty cell's + to add a gauge there" flow still works with the pill present;
only a test that never actually tested it has stopped passing-by-accident. `@Ignore` (rather than
silently deleting or leaving a red gate) is the honest call. **Recommended follow-up (non-blocking):
file an issue to fix `EmptyCellAddButton`'s zero-bounds reporting so this flow becomes genuinely
testable** — the KDoc in `GridEditTest.kt:147-168` and the `@Ignore` reason already point at it.

---

## What I verified (file:line)

- **Header-height gotcha — real, not hollow.** `DashboardScreen.kt:499-509` keeps the outer header
  `Row`'s `fillMaxWidth()` and the `ConnectionBanner`'s `Modifier.weight(1f)` intact; the pill chrome
  (clip/background/padding) moved to an **inner** Row inside `PillContent` (`ConnectionBanner.kt:209-219`),
  so the width allocation for ＋Add/Done/Rec/wrench/gear is untouched. The new
  `header height is identical across every connection-status pill state` test
  (`DashboardScreenTest.kt:127-161`) drives disconnected/connecting/ready-live/ready-waiting/error
  through one composition via `mutableStateOf` and asserts `distinctHeights.size == 1` — a genuine
  cross-state equality assertion over the five states the brief named, not a tautology.
- **Pill Ready split — correct.** `connectionBannerMessage` (`ConnectionBanner.kt:322-330`):
  Ready+flowing → "Live · reading ECU", Ready+!flowing → "Connected · waiting for ECU"; `bannerTone`
  (`:80-89`): Ready+flowing → LIVE/green, else BUSY/amber; Error keeps the saturated `errorContainer`
  treatment (`:164-168`). Pure-function tests pin both copy and `stateDescription` for both halves
  and prove `dataFlowing` is ignored by every non-Ready state (`ConnectionBannerCopyTest.kt:16-58`).
- **`dataFlowing` derivation — pure + tested.** `toDashboardUiState` sets
  `dataFlowing = readings.values.any { !it.stale }` (`DashboardUiState.kt:161`); tested false on
  empty and on all-stale, true when any one reading is fresh (`DashboardUiStateTest.kt:118-152`).
  Field defaults `false` so no call site regresses.
- **`showConnectionStatus` — all six `keepScreenOn` touchpoints cloned, default true.**
  `AppSettings.kt:80` (`= true`), `SettingsCodec.kt:81` decode-with-default + `:133` encode,
  `SettingsViewModel.kt:83`, `SettingsScreen.kt:92-100` switch (testTag `show-connection-status-switch`)
  + `:400` route wiring, `DashboardViewModel.kt:201-206` `StateFlow`, `MainActivity.kt:158` collect +
  `:183` pass into `GaugeDashboard`. Codec round-trips an explicit `false` and defaults to `true` on
  a missing key / a pre-OBD-84 install (`SettingsCodecTest.kt:75-84`); the switch reports its toggle
  (`SettingsScreenTest.kt:238-247`). Backward-compatible: unknown key → default true.
- **Toggle gates only the permanent Ready pill.** `ConnectionBanner.kt:153`
  (`if (connection == Ready && !showConnectionStatus) return`) — Scanning/Connecting/Disconnected/Error
  always render, so turning it off never hides a genuine outage. Tested: Ready-off renders nothing,
  Scanning-off still shows (`ConnectionBannerTest.kt:60-79`).
- **`LinkState` contract untouched** — the Ready split is a UI-side fusion via a private
  `BannerTone` enum + `dataFlowing`; no new variant, no `core/model` change (not in the diff).
- **Module isolation / deps / scope.** No `com.revel.obdgauge.protocol.*` or `.ble.*` imports added;
  `ConnectionBanner` pulls only `GaugeGreen/GaugeAmber/GaugeNeutral` from the app's own
  `ui.theme` and `LinkState/LinkError` from the already-depended-on `:core:model`. No
  `build.gradle*` change, no new dependency. `module-isolation` gate check PASS.
- **37 re-recorded Roborazzi refs — explained, not masked.** `verifyRoborazzi` is green, so the
  committed refs match the current render. Every changed screenshot is a **dashboard** shot whose
  header was previously empty on `Ready` (banner hidden) and now shows the permanent pill — reasoned
  from code: header height is proven constant (above), so tiles are not resized; the diffs are the
  pill itself appearing in the header. The gauge_* face/needle/bar shots are full-dashboard captures
  that include that header. I reasoned this from the code + the height-constant proof rather than
  pixel-diffing all 37 PNGs.

---

## Fix list

- ✅ **(acceptable) `@Ignore`d `GridEditTest` case** — legitimately pre-existing (verified on `main`
  by probe: `EmptyCellAddButton` bounds `(0,0,0,0)`, the pass rode a header-button (0,0) coincidence).
  No regression to the real add-at-cell flow (`RearrangeMode.kt`/`GaugeGrid` byte-identical, header
  height constant). **Recommended follow-up issue:** fix `EmptyCellAddButton`'s zero-bounds reporting
  so the flow is testable. Non-blocking.
- ✅ **(acceptable) Non-Ready states got the pill's new look, not just Ready.** Pre-change, all
  non-error states were a full-width `surfaceVariant` bar; now they're a tinted rounded pill
  (`toneColor(tone).copy(alpha = 0.14)`) with a color-coded dot (Disconnected/Ready) or spinner
  (Scanning/Connecting/Error). **Copy and `stateDescription` are byte-identical** for every non-Ready
  state (pinned by `ConnectionBannerCopyTest`), and spinner logic is unchanged (`isSpinning` == the
  old `isBusy` set). The *visual* change is broader than "render Ready" but is exactly the
  color-coding the issue's "Chosen behavior" specifies (neutral/grey disconnected, amber busy, green
  live, red error) and is reflected in the re-recorded refs — intended, not a regression.
- ✅ **(note, non-blocking) Portrait ellipsization at w360dp.** With five fixed-min-width header
  buttons (~320dp) the pill's `weight(1f)` slot can shrink until the message ellipsizes to nearly
  nothing (dot only). This is a **pre-existing structural** property of the header (the fixed buttons
  + `weight(1f)` banner predate OBD-84); the always-visible Ready pill just makes it visible on a
  healthy link too. Single-line + `TextOverflow.Ellipsis` means no clip/crash/height-growth
  (the OBD-67 round-6/7 fix still holds). Landscape is the dash target. Flagging per the brief.
- ✅ **(acceptable) `PillContent` extraction + `@Suppress("LongParameterList")`** — a mechanical
  split to stay under detekt's `LongMethod` budget, no behavioral change; the `SettingsScreen` switch
  is inlined (not a private fun) for the same detekt-function-count reason as OBD-70's Recordings
  section. Consistent with established patterns.

No required changes. Merge when the queue reaches it.
