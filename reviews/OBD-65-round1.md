---
issue: OBD-65
round: 1
reviewers: [orchestrator + Taras on-device acceptance]
verdict: approved
gate: green
reviewed-commit: 81de1e1
covers: [OBD-65]
---

Swap-gauge picker reworked into an **in-tile swipe carousel** (Taras's design), across several
on-device iterations, all verified on the Pixel and accepted by Taras ("looks good").

## Fix list
- [x] ✅ **Root cause fixed.** The old carousel was crammed into the fixed grid cell and its horizontal
      drag was eaten by the tile's tap detector. New design: long-press replaces the tile content with
      an in-place `HorizontalPager` — page 0 = current gauge, following pages = candidates — so the
      pager natively owns horizontal drags and the tap detector is suspended in pick-mode.
- [x] ✅ **Cards ~75% of the frame with peek** (Taras's sketch): the tile keeps its frame; the pager's
      centered card is ~75% (`SWAP_CARD_FRACTION`, 12.5% contentPadding each side) so neighbor cards
      peek — holds down to a 1×1 cell. Readable + swipeable at any size.
- [x] ✅ **Zoom/pop animation restored** (had been stripped in an earlier pass): enter-zoom on long-press
      (current gauge scales from ~full-tile into the 75% card), pop-in on select (chosen card scales to
      1.35 to fill, then `swapGauge` persists + dismiss).
- [x] ✅ **Ghost-frame fix** (Taras caught, I reproduced on-device): during the select-pop the frame
      chrome (`selectFade` 1→0) and the other pager pages (`pageAlpha`→0) now fade out, so only the
      chosen gauge grows in on a clean background — no lingering frame/peek ghost. Settled states +
      Roborazzi unchanged (pure transient).
- [x] ✅ **Removed the dead OBD-42/44/46/47 shrink-carousel-grow stack** (net −1094 lines) that the new
      design obsoletes; `GaugeMiniCard` kept for the add palette.
- [x] ✅ **Preserved:** OBD-64 edit bar (add/resize/remove), add palette, scrim/back dismiss, tap-current
      dismiss. Picker/demo/grid structure tests reworked and green.
- [x] ✅ **On-device verified on the Pixel** across iterations: long-press→enter-zoom, in-tile swipe with
      peek, tap-to-select pop, and the ghost-free select-pop. Taras accepted.

Gate: PASS (all seven) at 81de1e1. Animation/fling is device-only (Robolectric can't render frames);
structure/reachability covered by tests, motion verified on hardware.
