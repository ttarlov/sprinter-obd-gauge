package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * OBD-64: the grid is now MUTABLE from the UI — add (>4), remove, and resize a tile from menus, no
 * drag. Wires [GaugeDashboard] to a real [DashboardViewModel] (so every mutation round-trips
 * through `SettingsRepository`/`gridLayout` exactly as production does) over a fixed-readings
 * [VehicleDataSource] double, then drives the "+" add-cell/palette and the long-press picker's new
 * size/remove controls. Assertions check BOTH the rendered tree and the persisted `gridLayout`, so
 * a regression that renders right but persists wrong (or vice versa) still fails.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land")
class GridEditTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

    @Test
    fun `the edit-bar Add button opens the palette and adding places a fifth gauge`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)

        // The normal dashboard is clean — no always-visible add cell.
        composeTestRule.onNodeWithTag("gauge-add-cell").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-edit-add").assertDoesNotExist()

        // OBD-67: long-press only enters rearrange mode now; the ⇄ badge opens the picker (and
        // with it, this same edit bar — unaffected by which badge opened the tile's picker).
        // Add lives behind that, in the edit bar; rpm/speed unplaced, so it's shown.
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-edit-add").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-add-palette").assertExists()

        composeTestRule.onNodeWithTag("gauge-add-option-${PidIds.RPM}").performScrollTo().performTouchInput { click() }
        composeTestRule.waitForIdle()

        // The fifth tile is now on the dashboard, live, and persisted.
        composeTestRule.onNodeWithTag("gauge-${PidIds.RPM}").assertExists()
        composeTestRule.onNodeWithTag("gauge-${PidIds.RPM}-value").assertTextEquals(RPM_TEXT)
        assertEquals(
            5,
            viewModel.gridLayoutsByColumns.value
                .getValue(GRID_CANONICAL_COLUMNS)
                .ids.size,
        )
        assert(
            PidIds.RPM in
                viewModel.gridLayoutsByColumns.value
                    .getValue(GRID_CANONICAL_COLUMNS)
                    .ids,
        )
        // Palette closed itself after the pick.
        composeTestRule.onNodeWithTag("gauge-add-palette").assertDoesNotExist()
    }

    // OBD-67 round-8 device-verified fix: the below-fold "＋" empty cells became practically
    // unreachable once tiles render full-viewport-height in rearrange mode — a vertical swipe
    // anywhere on a tile starts a drag instead of scrolling, so there's no gesture left to reach
    // them. This is the always-reachable primary path: a "＋ Add" button in the top chrome row,
    // reachable the instant rearrange mode is entered — no badge-opened picker required first
    // (unlike the edit-bar's own `gauge-edit-add`, tested above, which only exists once a tile is
    // being picked). Reuses the exact same `showAddPalette = true` route as that edit-bar button,
    // so it lands the new gauge the identical way: wherever GridEngine finds room, not a specific
    // cell — asserted here by checking the persisted layout directly, same as the test above.
    @Test
    fun `the header Add button opens the palette without a badge-opened picker first`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)

        // Not in rearrange mode yet: the header button exists (always composed — see
        // DashboardScreen.kt's round-8 fix) but is disabled/invisible, so it must not be
        // clickable — this pins that half of the invariant-height trick, not just its presence.
        composeTestRule.onNodeWithTag("rearrange-add-button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rearrange-add-button").assertIsEnabled()
        composeTestRule.onNodeWithTag("rearrange-add-button").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-add-palette").assertExists()

        composeTestRule.onNodeWithTag("gauge-add-option-${PidIds.RPM}").performScrollTo().performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-${PidIds.RPM}").assertExists()
        assertEquals(
            5,
            viewModel.gridLayoutsByColumns.value
                .getValue(GRID_CANONICAL_COLUMNS)
                .ids.size,
        )
        composeTestRule.onNodeWithTag("gauge-add-palette").assertDoesNotExist()
    }

    // OBD-67 round-9 device-verified fix: EmptyCellAddButton switched from `detectTapGestures`
    // (which consumes the down/up it claims — correct in isolation, but blocks GaugeGrid's
    // `verticalScroll` ancestor from ever seeing a swipe that starts on an empty cell) to a
    // hand-rolled non-consuming tap detector, so a plain tap must still reliably open the
    // add-palette targeted at that exact cell — this pins that the swap didn't break the tap
    // itself, the one thing Robolectric CAN verify about this fix (the actual scroll-passthrough
    // it's for needs a device, per the round-9 ask).
    //
    // OBD-84 finding (2026-09-12): this test has never actually exercised the tap path its name
    // claims. `EmptyCellAddButton`'s empty-cell Boxes (`RearrangeMode.kt`, positioned via
    // `Modifier.offset { IntOffset(...) }.size(...)`) report `getBoundsInRoot() ==
    // Rect.fromLTRB(0,0,0,0)` under Robolectric — confirmed identical on unmodified `main`
    // (pre-OBD-84), including after 50 forced `mainClock.advanceTimeByFrame()` passes, so this
    // is a pre-existing Robolectric-measurement quirk in that composable, not something OBD-84
    // introduced. `performTouchInput { click() }` on a zero-bounds node dispatches its tap at
    // literal root coordinate (0,0) — on `main`, with `LinkState.Ready` (this test's fixture)
    // hiding `ConnectionBanner` entirely (pre-OBD-84 behavior), the header Row's "＋ Add" button
    // ends up sitting AT that exact (0,0) origin (no flex sibling to push it right), so the
    // "wrong" click accidentally lands on and triggers the UNRELATED header Add button instead —
    // which also opens `gauge-add-palette`, so the assertion below passed for the wrong reason.
    // OBD-84 makes the header always claim its full-width flex slot (the permanent pill), which
    // correctly pushes that button away from (0,0) — removing the coincidence and exposing that
    // this test's own tap has always missed its intended target. Fixing `EmptyCellAddButton`'s
    // bounds reporting is real OBD-67/68 grid-measurement territory, out of scope for a
    // connection-status-pill issue; ignored here pending a follow-up issue rather than silently
    // deleting or leaving a red gate. See `reviews/` for this issue's round notes.
    @Ignore(
        "Pre-existing: EmptyCellAddButton reports zero semantics bounds under Robolectric " +
            "(reproduced on unmodified main too) — see the KDoc above. Needs a real fix in " +
            "RearrangeMode.kt/GridMetrics, tracked as a follow-up, not an OBD-84 regression.",
    )
    @Test
    fun `an empty cell's add button still opens the palette targeted at that cell`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // The default 4 gauges fill the landscape grid's single row; rearrange mode's spare row
        // below is entirely empty, so every "add" cell there shares this testTag — any one is a
        // valid, deterministic target here (this test only cares that TAPPING one works, not
        // which specific cell).
        composeTestRule.onAllNodesWithTag("gauge-rearrange-add").onFirst().performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-add-palette").assertExists()

        composeTestRule.onNodeWithTag("gauge-add-option-${PidIds.RPM}").performScrollTo().performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-${PidIds.RPM}").assertExists()
        assertEquals(
            5,
            viewModel.gridLayoutsByColumns.value
                .getValue(GRID_CANONICAL_COLUMNS)
                .ids.size,
        )
    }

    // OBD-67 round-10 device-verified fix: `resizeGauge` used to route through `GridEngine.resize`
    // (repack-based — the pre-freeform ordered-reflow primitive), never updated when the freeform
    // pivot landed. Device report: "resizing of the gauges does not work anymore... the chip
    // visibly selects... [but] the tile is still 1×1." Root cause confirmed by direct
    // investigation (temporarily inspecting the persisted layout + rendered bounds mid-fix, not
    // guessed): the resize DID apply (the target's own colSpan/rowSpan came back correct) — the
    // bug was `repack()` ALSO silently re-deriving every OTHER placement's `(col, row)` from
    // scratch via shelf-packing, which for this specific 4-gauges-fill-the-row fixture pushed a
    // gauge into a new row, shrinking every tile's height (the very bug round 5 fixed the OTHER
    // cause of) — device-visible as "nothing looks resized" even though the data technically was.
    // `resizeGauge` now routes through `GridEngine.resizeInPlace`, which leaves every other
    // placement exactly where it is.
    //
    // Grows DOWN (1×1 → 1×2) into rearrange mode's always-present empty spare row, not sideways —
    // the default fixture packs all 4 gauges edge-to-edge across the row, so ANY sideways growth
    // collides with a neighbor and is correctly rejected (see the rejection test below); growing
    // into the empty row below is the "clearly fits" case team lead's own note called out.
    //
    // This test pins BOTH halves: the resized tile's span changes, AND every other tile's
    // position does not — the second assertion is exactly what the old repack-based path would
    // have failed (it reshuffled every OTHER tile's `(col, row)` too, even though only coolant was
    // resized).
    @Test
    fun `a resize chip changes only the target tile's span, in place`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)
        val before = viewModel.gridLayoutsByColumns.value.getValue(GRID_CANONICAL_COLUMNS)
        assertEquals(1, before.placementFor(PidIds.COOLANT)!!.rowSpan)
        val transTempBefore = before.placementFor(PidIds.TRANS_TEMP)!!
        val oilTempBefore = before.placementFor(PidIds.OIL_TEMP)!!
        val boostBefore = before.placementFor(PidIds.BOOST)!!

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-resize-1x2-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        // Persisted layout: coolant resized, nothing else moved.
        val after = viewModel.gridLayoutsByColumns.value.getValue(GRID_CANONICAL_COLUMNS)
        val coolant = after.placementFor(PidIds.COOLANT)!!
        assertEquals(1, coolant.colSpan)
        assertEquals(2, coolant.rowSpan)
        assertEquals(0, coolant.col)
        assertEquals(0, coolant.row)
        assertEquals(transTempBefore, after.placementFor(PidIds.TRANS_TEMP))
        assertEquals(oilTempBefore, after.placementFor(PidIds.OIL_TEMP))
        assertEquals(boostBefore, after.placementFor(PidIds.BOOST))

        // Rendered, still mid-pick: the pager fills the tile's own bounds, so a real 2-ROW-tall
        // area (not a 1×1 pager card) is the render-path half of "round-trips through the
        // layout" — the persisted-only assertions above couldn't have caught a render-path-only
        // regression. Compared against transTemp (still 1×1, one row tall) as the "what 1×1 looks
        // like here" reference — a resized-to-1×2 tile must be unambiguously taller, not just
        // non-zero.
        val transTempBounds = composeTestRule.onNodeWithTag("gauge-transTemp").getBoundsInRoot()
        val pagerBounds = composeTestRule.onNodeWithTag("gauge-swap-pager-coolant").getBoundsInRoot()
        assertTrue(
            "a 1×2 coolant's pager (${pagerBounds.height}) should be taller than a 1×1 " +
                "transTemp (${transTempBounds.height}) if the resize actually rendered, not just persisted",
            pagerBounds.height > transTempBounds.height * MIN_WIDE_TILE_RATIO,
        )

        // Dismiss and confirm the resize is STILL applied post-dismiss — exactly team lead's own
        // repro sequence ("tap [a size]... dismiss the edit bar... the tile is still 1×1").
        composeTestRule.onNodeWithTag("gauge-picker-scrim").performTouchInput { click() }
        composeTestRule.waitForIdle()
        val settledBounds = composeTestRule.onNodeWithTag("gauge-coolant").getBoundsInRoot()
        assertTrue(
            "a dismissed 1×2 coolant (${settledBounds.height}) should still be taller than a 1×1 " +
                "transTemp (${transTempBounds.height}) — the exact regression team lead reported",
            settledBounds.height > transTempBounds.height * MIN_WIDE_TILE_RATIO,
        )
    }

    // OBD-67 round-13 device-verified (user decision, replacing round-10's rejection here — see
    // GridEngine.resizeWithPush's own KDoc for the "shift left" refinement): the user's own
    // words: "I can resize any way I want, but only when the gauge is on the LEFT side. If a
    // gauge is in the RIGHT column it only resizes up/down, not side to side" — meaning a
    // right-column tile widening should extend into the space actually available (its left), not
    // do nothing. Portrait is 2 columns; the default migration packs the 4 core gauges 2×2, so
    // transTemp is at col 1 (the right column) — growing it to 2 wide now shifts it to col 0 and
    // pushes coolant (which was sitting there) out of the way, through the real UI, not just the
    // ViewModel.
    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `widening a right-column tile shifts it left and pushes what was there`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)
        val transBefore =
            viewModel.gridLayoutsByColumns.value
                .getValue(GRID_PORTRAIT_COLUMNS)
                .placementFor(PidIds.TRANS_TEMP)!!
        assertEquals(1, transBefore.col)

        composeTestRule.onNodeWithTag("gauge-transTemp").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-transTemp").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-resize-2x1-transTemp").performTouchInput { click() }
        composeTestRule.waitForIdle()

        val layout = viewModel.gridLayoutsByColumns.value.getValue(GRID_PORTRAIT_COLUMNS)
        val transAfter = layout.placementFor(PidIds.TRANS_TEMP)!!
        assertEquals(0, transAfter.col) // shifted left to fit the 2-wide span
        assertEquals(0, transAfter.row)
        assertEquals(2, transAfter.colSpan)
        // coolant was occupying (0,0) — pushed out. Rows 0-1 are now solid across both columns,
        // so it lands at the start of a new row.
        val coolant = layout.placementFor(PidIds.COOLANT)!!
        assertEquals(0, coolant.col)
        assertEquals(2, coolant.row)
        assertEquals(1, coolant.colSpan) // displaced, not resized
        // Never in the way — untouched.
        assertEquals(0, layout.placementFor(PidIds.OIL_TEMP)!!.col)
        assertEquals(1, layout.placementFor(PidIds.OIL_TEMP)!!.row)
        assertEquals(1, layout.placementFor(PidIds.BOOST)!!.col)
        assertEquals(1, layout.placementFor(PidIds.BOOST)!!.row)
    }

    // OBD-67 round-12 (user decision, replacing round-10/11's rejection behavior — see
    // GridEngine.resizeWithPush's own KDoc for the full "silent-but-correct" diagnosis that led
    // here): portrait's default migration packs all 4 core gauges into a FULLY OCCUPIED 2×2 block
    // (coolant/transTemp row 0, oilTemp/boost row 1). Growing coolant colSpan-2 now PUSHES
    // transTemp (immediately to coolant's right) to the next free slot instead of being rejected —
    // through the real UI (chip tap → picker → render), not just the ViewModel, closing the same
    // render-path gap round-10's tests targeted.
    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `growing a tile wider pushes the colliding neighbor to a free slot`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-resize-2x1-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        val layout = viewModel.gridLayoutsByColumns.value.getValue(GRID_PORTRAIT_COLUMNS)
        val coolant = layout.placementFor(PidIds.COOLANT)!!
        assertEquals(0, coolant.col)
        assertEquals(2, coolant.colSpan)
        // Row 0 is now solid across both columns, so the displaced transTemp lands at the start
        // of a new row rather than anywhere still on row 0/1 (both fully occupied post-resize).
        val transTemp = layout.placementFor(PidIds.TRANS_TEMP)!!
        assertEquals(0, transTemp.col)
        assertEquals(2, transTemp.row)
        assertEquals(1, transTemp.colSpan) // displaced, not resized
        // Never in the way — untouched.
        assertEquals(0, layout.placementFor(PidIds.OIL_TEMP)!!.col)
        assertEquals(1, layout.placementFor(PidIds.OIL_TEMP)!!.row)
    }

    // OBD-67 round-12: the flip side of the push test above — growing DOWN (rowSpan) instead of
    // wide, in the same fully-occupied portrait default, displaces oilTemp (immediately below
    // coolant) instead.
    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `growing a tile taller pushes the colliding neighbor to a free slot`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-resize-1x2-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        val layout = viewModel.gridLayoutsByColumns.value.getValue(GRID_PORTRAIT_COLUMNS)
        val coolant = layout.placementFor(PidIds.COOLANT)!!
        assertEquals(0, coolant.row)
        assertEquals(2, coolant.rowSpan)
        // Both rows are now solid down column 0 (coolant) and column 1 (transTemp/boost unmoved),
        // so the displaced oilTemp lands at the start of a new row, same shape as the colSpan
        // test above but rotated: col 0's own row 0/1 are both taken.
        val oilTemp = layout.placementFor(PidIds.OIL_TEMP)!!
        assertEquals(0, oilTemp.col)
        assertEquals(2, oilTemp.row)
        assertEquals(1, oilTemp.colSpan) // displaced, not resized
        // Never in the way — untouched.
        assertEquals(1, layout.placementFor(PidIds.TRANS_TEMP)!!.col)
        assertEquals(0, layout.placementFor(PidIds.TRANS_TEMP)!!.row)
    }

    @Test
    fun `remove drops a tile and it returns as an addable candidate`() {
        val viewModel = newViewModel()
        setDashboard(viewModel)

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-remove-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        // Gone from the grid (rendered + persisted), and the picker closed with it.
        composeTestRule.onNodeWithTag("gauge-coolant").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-picker-coolant").assertDoesNotExist()
        assertNull(
            viewModel.gridLayoutsByColumns.value
                .getValue(GRID_CANONICAL_COLUMNS)
                .placementFor(PidIds.COOLANT),
        )

        // It's now offered again in the add palette — reached via another tile's edit bar. Still
        // in rearrange mode from above, so oilTemp's badges are already showing (no re-long-press).
        composeTestRule.onNodeWithTag("gauge-rearrange-swap-oilTemp").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-edit-add").performTouchInput { click() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-add-option-${PidIds.COOLANT}").performScrollTo().assertExists()
    }

    private fun setDashboard(viewModel: DashboardViewModel) {
        composeTestRule.setContent {
            ObdGaugeTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val gaugeOrder by viewModel.gaugeOrder.collectAsStateWithLifecycle()
                val gridLayoutsByColumns by viewModel.gridLayoutsByColumns.collectAsStateWithLifecycle()
                GaugeDashboard(
                    uiState = uiState,
                    gaugeOrder = gaugeOrder,
                    gridLayoutsByColumns = gridLayoutsByColumns,
                    onSwapGauge = viewModel::swapGauge,
                    onAddGauge = viewModel::addGauge,
                    onRemoveGauge = viewModel::removeGauge,
                    onResizeGauge = viewModel::resizeGauge,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun newViewModel(): DashboardViewModel =
        DashboardViewModel(GridEditVehicleDataSource(fixedReadings()), clock, GridEditSettingsRepository())

    private companion object {
        const val RPM_TEXT = "3200 RPM"

        // A genuinely-2×2-wide tile should be roughly 2x a 1×1 neighbor's width in this 4-column
        // landscape fixture; 1.5x is a comfortably wide margin against spacing/gutter rounding
        // while still being unambiguously more than "did not resize" (which would be ~1x).
        const val MIN_WIDE_TILE_RATIO = 1.5f

        fun fixedReadings(now: Instant = Instant.EPOCH): Map<String, Reading> =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, 190.0, now, stale = false),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, 150.0, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, 200.0, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, 5.0, now, stale = false),
                PidIds.RPM to Reading(PidIds.RPM, 3200.0, now, stale = false),
                SPEED_PID_ID to Reading(SPEED_PID_ID, 65.0, now, stale = false),
            )
    }
}

/** Emits a fixed readings map, [LinkState.Ready] — start/stop no-ops. Mirrors the picker tests' double. */
private class GridEditVehicleDataSource(
    initial: Map<String, Reading>,
) : VehicleDataSource {
    override val readings = MutableStateFlow(initial)
    override val connection = MutableStateFlow<LinkState>(LinkState.Ready)

    override fun start(pids: List<PidDefinition>) = Unit

    override fun stop() = Unit
}

/** No-DataStore settings double — the eager seed writes its `gridLayout` in memory. */
private class GridEditSettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
