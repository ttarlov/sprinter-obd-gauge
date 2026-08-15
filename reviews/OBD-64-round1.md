---
issue: OBD-64
round: 1
reviewers: [orchestrator small-track (D6) + real-device smoke]
verdict: approved
gate: green
reviewed-commit: 95af29b
covers: [OBD-64]
---

Phase 3 of the resizable grid — add/remove/resize from the UI, `gridLayout` as the live source of
truth. Orchestrator review + a full on-device interaction test on the Pixel. Taras chose the "clean
default" direction (edit affordances behind long-press, no always-visible [+]); the build was adjusted
to that at 95af29b.

## Fix list
- [x] ✅ **Isolated & additive.** `:app`/`issues` only. Reuses `GridEngine`/`GaugeGrid`/the picker
      surface; no new edit-mode framework.
- [x] ✅ **gridLayout is the live source.** ViewModel eager-seeds a canonical 4-col migration once when
      null and persists it (guarded against overwrite); add/remove/resize/swap each persist via
      `GridEngine` ops on the stored canonical layout. `GridEngine.replaceId` (swap) unit-tested.
      candidate/add lists key off `gridLayout.ids`.
- [x] ✅ **Picker animations intact.** Edit controls (＋Add / size chips / Remove) live in a bottom
      edit-bar, NOT the in-slot swap chrome, so OBD-44/47 shrink/grow are untouched; `GaugeSwapPickerTest`
      passes unchanged. Real slots are never displaced by a synthetic cell (the [+] cell was removed).
- [x] ✅ **Clean default (Taras's call).** Normal view renders exactly the placed gauges — 4 full-height
      tiles, boost arc at full size. Add moved into the long-press edit bar (`＋ Add`), hidden when all
      catalog gauges are placed.
- [x] ✅ **On-device smoke (Pixel, demo build):** long-press → edit bar; resize Coolant → 2×1 with live
      reflow; ＋Add → palette (Boost/RPM); added Boost → 5 gauges, persisted. All worked on real hardware.
      Screenshots captured.
- [x] ✅ **Tests:** replaceId; ViewModel eager-seed + add/remove/resize/swap persistence; `GridEditTest`
      (compose: add via long-press→＋Add places a 5th; resize chip changes span; remove drops+re-addable).
      Roborazzi re-recorded to the clean-default look; new `gauge_add_palette`.
- [ ] 🖐 Deferred (not this phase, as scoped): resize handles + drag-to-move (the gesture phases).

Gate: PASS (all seven) at 95af29b.
