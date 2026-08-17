---
issue: OBD-67
round: 2
reviewer: rev-combined-final
verdict: approved
gate: green
reviewed-commit: 72bc627
covers: [OBD-67]
---

# OBD-67 final review — freeform drag/resize rearrange, per-orientation layouts

Fresh independent review of the whole `feat/67-drag-move` branch (code at `72fe715`, issue
frontmatter/hardware-checklist at `72bc627` — no code change between them) against
`issues/OBD-67.md`'s shipped contract (the pivot notes, not the original spec). I read the full
final state of the engine (`GridEngine`, `GridLayout`, `GridMetrics`, `GridLayoutSet`,
`GridLayoutCodec`, `GridMigration`), the persistence layer (`AppSettings`, `SettingsCodec`), the
Compose layer (`DashboardScreen.kt`, `RearrangeMode.kt`, `GaugeGrid.kt`), the ViewModel, and the
JUnit/ViewModel test suites. Verdict: **approved** — I could not find a defect that produces an
invalid layout, a desync, a crash, or a stale-state regression on any reachable path. Everything
below is nit-level robustness/observation; none block merge.

## Engine correctness (the highest-value lens — pure, device can't see it)

I traced every mutator for the three failure classes that matter (overlap / out-of-bounds /
duplicate-id or infinite loop) and they hold:

- **`resizeWithPush` (`GridEngine.kt:215`)** — the shift-left clamp `newCol = min(current.col,
  columns - clampedColSpan)` is provably `≥ 0` because the only-remaining reject
  (`clampedColSpan > layout.columns`) already guarantees `columns - clampedColSpan ≥ 0`, and
  `current.col ≥ 0`. The shifted `target` is therefore always in horizontal bounds
  (`newCol + colSpan ≤ columns`). `displaced` is *exactly* the set overlapping `target`, so the
  non-displaced remainder provably can't collide with it; displaced tiles relocate through the same
  `firstFreeSlot` scan `repack` uses, into an `occupied` set that already contains `target` + all
  survivors, so no relocation can overlap. Order preservation via
  `layout.placements.map { byId.getValue(it.id) }` is total (every id is in `byId`). This is correct.
- **`dropAt` / `swapPositions` / `singleMatchingOccupant` (`GridEngine.kt:296`, `:315`, `:367`)** —
  move-then-swap-then-no-op priority is right; the swap is gated on `singleOrNull` + an *exact*
  origin+span match, so a partial overlap, multi-occupant, or same-position-different-size target
  all correctly fall through to snap-back. `swapPositions` exchanging origins can't introduce a
  collision the exact-footprint precondition didn't already rule out.
- **`cellAt` (`GridMetrics.kt:106`)** — correct inverse of `placementRect`; col clamped
  `[0, columns-1]`, row floored at 0. A finger dragged past any edge still resolves to an in-bounds
  cell the engine will accept (or snap-back on), never an out-of-range one.
- **`GridLayoutSet` invariant** — `add*`/`remove`/`replaceId` all `mapValues` across *every* stored
  layout (id-set stays identical), while `moveGauge`/`resizeGauge` route through
  `mutateColumnsLayout` and touch exactly one. `mutateColumnsLayout`'s `layouts.getValue(columns)`
  is safe: `ensuredLayouts` guarantees `REQUIRED_COLUMN_COUNTS = {4, 2}` are present and the screen
  only ever passes `GRID_CANONICAL_COLUMNS`/`GRID_PORTRAIT_COLUMNS`.

## Persistence & migration

- Round-trip and the free single-layout→map migration are correct and *tested*
  (`SettingsCodecTest`): a pre-per-orientation string has no `|`, so `decodeMap` returns a
  one-entry map keyed by its own `columns`, and `ensureColumns` seeds the missing orientation. The
  `|` LAYOUT_SEPARATOR can't collide with a single layout's `#`/`;`/`:` alphabet.
- Malformed-blob discipline holds end to end: `decode`/`decodeMap` return `null` on any bad entry,
  `SettingsCodec` falls back to the empty-map default, the ViewModel re-seeds. No path throws.
- `encodeAppSettings` correctly skips writing an empty map (avoids clobbering with a blank), and the
  key name is unchanged (`"grid_layout"`), so no storage migration is needed.

## Compose / state

- The single shared `optimisticGrid` slot (`DashboardScreen.kt:287`) is the round-10/12 fix done
  right: `LaunchedEffect(gridLayoutsByColumns) { optimisticGrid = null }` clears it *unconditionally*
  on the next persisted change, and both the drag commit (`onOptimisticGrid`) and the resize commit
  (`PickerEditBar.onResize`) build on `effectiveGridLayoutsByColumns`, so a rapid drag-then-resize
  always reads the prior optimistic value, never a stale pre-confirmation one.
- The drag gesture node structure keeps the `pointerInput(id)` on its own OUTER `Box` whose chain
  depends only on `dragEnabled` (stable for a drag's lifetime), with jiggle/animatePlacement/ghost on
  a separate INNER child — this is the round-3 fix and does *not* reintroduce the cancel-on-pickup bug.
  Closures stay fresh via `rememberUpdatedState` (round-4 stale-layout fix).
- Coroutine lifecycle: `RearrangeAutoscroll`'s loop is keyed on `draggedId`/`viewportHeightPx` and
  guarded, so it starts/stops with the drag and can't leak. `LaunchedEffect(dragEnabled)` ends the
  drag on any exit, so leaving the mode mid-drag can't strand a floating tile.
- `detectNonConsumingTap` genuinely never consumes until a confirmed in-slop up, so a swipe starting
  on an empty "＋" cell falls through to the ancestor `verticalScroll` — the round-9 scroll-passthrough
  fix is correctly implemented.

## Merge-readiness

- `grep -rn OBD67DRAG app core` → nothing. No `println`/stray `Log` in the feature. The diagnostic
  logging was stripped as claimed.
- The kept-unused primitives (`resize`, `resizeInPlace`, `reorder`, `targetIndexAt`) are genuinely
  dead-but-intentional: no `main` call site wires them; the live paths call `resizeWithPush`/`dropAt`.
- `issues/OBD-67.md`'s pivot notes accurately describe what ships.

## Test adequacy

Coverage is strong and hits exactly the device-found regression points: `GridEngineTest` covers
`resizeWithPush` push, grow-into-empty, shift-left (plain + combined-with-push in 4-col), the
wider-than-grid reject, multi-tile displacement no-overlap, and absent-id; `dropAt` move/swap/
snap-back/no-op; `cellAt`; `GridLayoutSetTest` pins the cross-orientation id-set sync;
`DashboardViewModelTest` proves per-orientation isolation for `resize`/`move` and sync for
`add`/`remove`/`swap`; `SettingsCodecTest` pins round-trip + the single-layout migration.

## Findings

**[nit]** `GridEngine.kt:234` — `resizeWithPush`'s displaced-tile relocation calls `firstFreeSlot`
without clamping `d.colSpan` to `layout.columns`, unlike `repack` which coerces spans first.
Unreachable in production (real spans ≤ 2, columns ≥ 2, syncs seed at colSpan 1). Pure robustness.

**[nit]** `GridLayoutCodec.kt:72` — `decodeMap`'s `associateBy { it.columns }` silently keeps the last
of two entries sharing a column count. Only reachable via a hand-corrupted DataStore blob; worst case
is a re-seed, never a crash. Fine as-is.

**[observation]** `optimisticGrid` clears on a `gridLayoutsByColumns` change, not on orientation
change. A rotation mid-commit leaves a stale override keyed to the previous column count — no
user-visible or persistence effect (the persist is authoritative). Noted for completeness.

## Fix list

✅ **Approved — nothing blocking, clean.** Verified: engine mutators cannot produce overlap /
out-of-bounds / duplicate-id / infinite loop on any reachable input; the shift-left clamp is `≥ 0`
and in-bounds by construction; `dropAt`/swap gating is exact; the single shared `optimisticGrid`
closes the round-10 stale-mask class; the drag gesture node is structurally invariant across
`isDragged` (no cancel-on-pickup) and closures stay fresh; persistence round-trips and the
single-layout migration are correct and tested; malformed blobs reset rather than crash; no
debug/logging leftovers; the kept-unused primitives are dead-but-intentional and unwired; tests cover
the shipped engine, per-orientation isolation, the sync invariant, and the codec/migration. The two
nits are unreachable-in-production robustness items; the observation has no effect. Merge-ready.
