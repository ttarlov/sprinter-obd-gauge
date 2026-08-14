---
issue: OBD-62
round: 1
reviewers: [orchestrator small-track (D6)]
verdict: approved
gate: green
reviewed-commit: eeecb4f
covers: [OBD-62]
---

Phase 1 of the resizable grid — pure foundation, no rendering change. Orchestrator review.

## Fix list
- [x] ✅ **Additive, zero visual risk.** No existing rendering touched; `gaugeOrder` untouched; the new
      `gridLayout` settings field defaults null (→ dashboard migrates from `gaugeOrder` later). Gate's
      verifyRoborazzi green confirms no screenshot moved.
- [x] ✅ **Repack engine correct.** First-free-slot is top-then-left; a resize bumps following tiles and
      the layout stays valid (in-bounds, non-overlapping, ids preserved) — asserted by an `assertValid`
      invariant check in every engine test. Over-wide spans clamp to the column count (no wedge).
- [x] ✅ **Forward-compatible model.** Positions stored `(col,row,colSpan,rowSpan)` though repack-computed
      now, so the drag/resize phases set explicit cells via `canPlace`/`moveTo` with no migration.
- [x] ✅ **Persistence safe.** `GridLayoutCodec` encodes `columns#id:col:row:cs:rs;…`; malformed/absent →
      null via `runCatching` (matches the codec's "silent reset, never crash" discipline). Round-trip
      tested; empty-preferences → null default.
- [x] ✅ **Migration renders identically.** `fromGaugeOrder` maps visible gauges to 1×1 in reading order;
      hidden dropped. Tested.
- [x] ✅ Gate PASS (all seven) at eeecb4f. 17 engine tests + migration + codec.

Gate: PASS at eeecb4f.
