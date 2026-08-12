package com.revel.obdgauge.app.gauge

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.DataStoreSettingsRepository
import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.sign

/**
 * OBD-42's long-press swap carousel, end to end: [GaugeDashboard] wired to a real
 * [DashboardViewModel] (temp-file [DataStoreSettingsRepository] where a test needs real
 * persistence, an in-memory double otherwise — same split `LiveRecolorTest`/
 * `SettingsRepositoryTest` already use) and a small hand-rolled [VehicleDataSource] double
 * emitting coolant/trans/oil/boost **and rpm** readings, so the picker's rpm mini-card (and a
 * tile it gets swapped into) both show a real, non-placeholder value — the AC's "feels alive,
 * not like a menu."
 *
 * [createAndroidComposeRule] (not the plain `createComposeRule()` most other tests in this
 * module use) specifically so the back-gesture dismiss test can reach the hosting Activity's
 * `OnBackPressedDispatcher` via `activityRule` — `createComposeRule()`'s declared return type
 * doesn't expose that, even though it's backed by the same kind of rule under the hood.
 *
 * `DEFAULT_GAUGE_ORDER` (all four core gauges visible) is used throughout, so every picker in
 * this file has exactly two candidates — the current gauge plus rpm — a deliberate choice to
 * keep each carousel's mini-cards within `LazyRow`'s initial composition window; `.performScrollTo()`
 * is still used before interacting with the rpm card since it's the second/peeking item, per
 * `app/MODULE.md`'s "off-screen node" pitfall.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land")
class GaugeSwapPickerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
    private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `long-press enters picker mode on that tile only`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-picker-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertDoesNotExist()
        // The rest of the dashboard stays live and untouched.
        composeTestRule.onNodeWithTag("gauge-transTemp-value").assertTextEquals(TRANS_TEXT)
        composeTestRule.onNodeWithTag("gauge-oilTemp-value").assertTextEquals(OIL_TEXT)
        composeTestRule.onNodeWithTag("gauge-boost-value").assertTextEquals(BOOST_TEXT)
        composeTestRule.onNodeWithTag("gauge-picker-transTemp").assertDoesNotExist()
    }

    @Test
    fun `carousel shows the current gauge first, then candidates excluding gauges visible on other tiles`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-picker-card-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-picker-card-rpm").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("gauge-picker-card-transTemp").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-picker-card-oilTemp").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-picker-card-boost").assertDoesNotExist()
        // Mini-card shows a live value, not just a label — "feels alive, not like a menu" (AC).
        composeTestRule.onNodeWithTag("gauge-picker-card-rpm-value").assertTextEquals(RPM_TEXT)
    }

    @Test
    fun `tap a candidate swaps the tile and persists, surviving a recreated repository`() {
        val file = temporaryFolder.newFile("gauge-swap.preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        val firstRepository =
            DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        setDashboard(newViewModel(firstRepository))

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-picker-card-rpm").performScrollTo().performTouchInput { click() }
        settlePickerAnimation()

        composeTestRule.onNodeWithTag("gauge-rpm").assertExists()
        composeTestRule.onNodeWithTag("gauge-rpm-value").assertTextEquals(RPM_TEXT)
        composeTestRule.onNodeWithTag("gauge-coolant").assertDoesNotExist()

        // Simulated restart (mirrors SettingsRepositoryTest): the first DataStore instance must
        // be gone, not just idle, before a second one opens the same file.
        firstScope.cancel()
        val secondScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        val secondRepository =
            DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        val restartedOrder = runBlocking { secondRepository.settings.first() }.gaugeOrder
        secondScope.cancel()

        assertEquals(PidIds.RPM, restartedOrder.first().id)
        assertTrue(restartedOrder.first().visible)
    }

    @Test
    fun `M1 - the picker scrim is gone after a completed swap (no stale pickerTileId)`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-picker-card-rpm").performScrollTo().performTouchInput { click() }
        settlePickerAnimation()

        // Sanity: the swap actually landed.
        composeTestRule.onNodeWithTag("gauge-rpm").assertExists()
        // Review round-1 M1: key(id) tears "coolant"'s subtree down (GaugePickerTile's own
        // LaunchedEffect included) before its delayed onDismiss() can run, so without
        // GaugeDashboard's own visibleIds self-heal, pickerTileId stays pinned to "coolant"
        // forever — this invisible scrim would still exist, silently eating the next back
        // press/outside tap for no visible reason.
        composeTestRule.onNodeWithTag("gauge-picker-scrim").assertDoesNotExist()
    }

    @Test
    fun `M1 - the old id returning to gaugeOrder via a non-picker path does not reopen its picker`() {
        // Restores "coolant" by calling swapGauge directly rather than via a second long-press:
        // ANY tile's long-press unconditionally overwrites pickerTileId to that tile's own id
        // (GaugeDashboard's onLongPress), which would silently "fix" the stale value as a side
        // effect of the interaction needed to trigger the second swap in the first place — that
        // would mask the bug instead of exercising it. Driving the second swap straight through
        // the ViewModel isolates review round-1 M1's actual defect: pickerTileId staying pinned
        // to an id no longer on screen, independent of what brings that id back into gaugeOrder.
        val viewModel = newViewModel()
        setDashboard(viewModel)

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-picker-card-rpm").performScrollTo().performTouchInput { click() }
        settlePickerAnimation()
        composeTestRule.onNodeWithTag("gauge-rpm").assertExists()

        viewModel.swapGauge(PidIds.RPM, PidIds.COOLANT)
        composeTestRule.waitForIdle()

        // Review round-1 M1: without the self-heal, pickerTileId is still pinned to "coolant"
        // from the FIRST swap's abandoned dismiss — the instant coolant's id reappears in
        // gaugeOrder, its tile would mount already in picker mode instead of showing live data.
        composeTestRule.onNodeWithTag("gauge-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
        composeTestRule.onNodeWithTag("gauge-picker-coolant").assertDoesNotExist()
    }

    @Test
    fun `M3 - a swapped-in gauge renders a placeholder, not a missing tile, during Loading`() {
        // DashboardUiState.Loading only carries the four core placeholders (extraTiles is empty
        // by default) — a tile freshly swapped to a non-core id must still render something for
        // every frame before the first real reading arrives, not a gap in the layout.
        val loadingOrder = listOf(GaugeOrderEntry(PidIds.RPM)) + DEFAULT_GAUGE_ORDER.drop(1)
        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(uiState = DashboardUiState.Loading, gaugeOrder = loadingOrder)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-rpm").assertExists()
        composeTestRule.onNodeWithTag("gauge-rpm-value").assertTextEquals(NO_READING_TEXT)
    }

    @Test
    fun `dismiss - tapping the current gauge's own mini-card changes nothing`() {
        setDashboard(newViewModel())
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-picker-card-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
        composeTestRule.onNodeWithTag("gauge-picker-coolant").assertDoesNotExist()
    }

    @Test
    fun `dismiss - the back gesture changes nothing`() {
        setDashboard(newViewModel())
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
        composeTestRule.onNodeWithTag("gauge-picker-coolant").assertDoesNotExist()
    }

    @Test
    fun `dismiss - tapping outside the tile changes nothing`() {
        setDashboard(newViewModel())
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-picker-scrim").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
        composeTestRule.onNodeWithTag("gauge-picker-coolant").assertDoesNotExist()
    }

    @Test
    fun `dismiss - tapping a different live tile also changes nothing`() {
        setDashboard(newViewModel())
        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-transTemp").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-picker-coolant").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-coolant-value").assertTextEquals(COOLANT_TEXT)
    }

    @Test
    fun `swap correctness - value, label, unit suffix, and threshold coloring all come from the new pid`() {
        setDashboard(newViewModel())
        // Confirm the pre-swap state really is coolant's RED zone first, so the post-swap
        // assertions below are a meaningful contrast — a "still red, still coolant" bug
        // wouldn't be caught by asserting the new state in isolation.
        composeTestRule.onNodeWithTag("gauge-coolant").assert(hasZone(ThresholdZone.RED))

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gauge-picker-card-rpm").performScrollTo().performTouchInput { click() }
        settlePickerAnimation()

        // VALUE: the fed rpm reading, never the stale coolant one.
        composeTestRule.onNodeWithTag("gauge-rpm-value").assertTextEquals(RPM_TEXT)
        // LABEL: "RPM", not "Coolant".
        composeTestRule.onNodeWithTag("gauge-rpm-label").assertTextEquals("RPM")
        // UNIT SUFFIX: RPM_TEXT ("3200 RPM") already pins this — " RPM", never "°F".
        // THRESHOLD COLORING: rpm has no seed threshold entry, so NEUTRAL — not RED, which is
        // what coolant's own value would still produce if coloring were stuck on the old pid.
        composeTestRule.onNodeWithTag("gauge-rpm").assert(hasZone(ThresholdZone.NEUTRAL))
    }

    @Config(qualifiers = "w360dp-h640dp-port")
    @Test
    fun `picker is reachable in portrait too`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("gauge-picker-coolant").assertExists()
        composeTestRule.onNodeWithTag("gauge-picker-card-rpm").performScrollTo().assertExists()
    }

    // --- OBD-44: picker-entry shrink animation --------------------------------------------

    /**
     * OBD-44 AC: "Gauge stays LIVE during the animation (value updates mid-shrink render
     * correctly)." Review round-1 MAJOR M2: the original version of this test advanced a FIXED
     * delta from an assumed `t == 0`, but `performTouchInput { longClick() }`'s own synthetic
     * gesture already advances `mainClock` by roughly its long-press timeout (~630 ms, comfortably
     * past `PICKER_SHRINK_MS`'s 220 ms) as part of recognizing the gesture at all — the fixed
     * delta landed a few ms BEFORE the animation settled, making the test pass even against a
     * broken (already-settled) implementation. Fixed by advancing a small delta from
     * `mainClock.currentTime` captured immediately AFTER the gesture, and — this is the "so drift
     * fails loudly" half of the fix — asserting mid-flight-ness explicitly (bounds strictly
     * between the full tile's own and the fully-settled mini-card's) so a future timing drift
     * fails the test instead of silently testing the settled frame again.
     */
    @Test
    fun `gauge shrinks continuously through mid-animation and stays live while it does`() {
        val dataSource = FixedReadingsVehicleDataSource(fixedReadings())
        setDashboard(DashboardViewModel(dataSource, clock, PickerInMemorySettingsRepository()))
        val fullBounds = composeTestRule.onNodeWithTag("gauge-coolant").getBoundsInRoot()

        lateinit var midBounds: DpRect
        composeTestRule.mainClock.autoAdvance = false
        try {
            composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
            // Small delta from wherever the gesture itself left the clock — NOT a fixed absolute
            // time (see KDoc above).
            composeTestRule.mainClock.advanceTimeBy(MID_FLIGHT_DELTA_MS)
            midBounds = composeTestRule.onNodeWithTag("gauge-picker-card-coolant").getBoundsInRoot()

            // The picker-card identity switches the instant picker mode is entered (isPicking
            // flips synchronously in GaugeSlot; only the VISUAL shrink is still animating), so
            // the live value already lives under the picker-card tag before the shrink settles.
            dataSource.readings.value =
                dataSource.readings.value +
                (PidIds.COOLANT to Reading(PidIds.COOLANT, MID_SHRINK_COOLANT_VALUE, Instant.EPOCH, stale = false))
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule
                .onNodeWithTag("gauge-picker-card-coolant-value")
                .assertTextEquals(MID_SHRINK_COOLANT_TEXT)

            composeTestRule.mainClock.advanceTimeBy(SWAP_SETTLE_MS)
        } finally {
            composeTestRule.mainClock.autoAdvance = true
        }
        composeTestRule.waitForIdle()

        val settledBounds = composeTestRule.onNodeWithTag("gauge-picker-card-coolant").getBoundsInRoot()
        assertMidFlight(fullBounds, midBounds, settledBounds)
    }

    /**
     * OBD-44 AC: "Reduced-motion / animation-scale-0 devices: end states still correct (no stuck
     * mid-scale composables)." Review round-1 MAJOR M1: the original version of this test called
     * `waitForIdle()`, which auto-advances the clock through however long a (non-`snap`) `tween`
     * branch would take too — it passed identically whether or not `rememberPickerShrinkAnimationSpec`
     * actually branched on the duration scale at all (mutation-confirmed below). The FIRST fix
     * attempt (pause `mainClock`, trigger the long-press via `longClick()`, advance exactly one
     * frame) turned out to be vacuous too: `longClick()`'s own synthetic gesture already advances
     * `mainClock` by roughly its long-press timeout as part of recognizing the gesture at all
     * (~630 ms, comfortably longer than `PICKER_SHRINK_MS`'s 220 ms) — a REAL tween would have
     * fully settled by the time `longClick()` even returns, one frame or not.
     *
     * Fixed by driving the press manually — `down()` then `advanceEventTime()` in small polled
     * steps against [composeTestRule]'s ROOT node (not the tile's own testTag, which switches to
     * `gauge-picker-card-coolant` the instant `isPicking` flips — continuing the SAME partial
     * gesture against a node whose own tag just changed out from under it doesn't reliably
     * resolve) — stopping the INSTANT the picker-card identity appears, i.e. as close to the real
     * long-press threshold as this polling granularity allows. At that exact instant, a real
     * `snap()` must have the shrink ALREADY at (or very near) its settled size; a `tween` — even a
     * 220 ms one — would still be almost full-size, since barely any *animation* time has elapsed
     * beyond the threshold crossing itself.
     */
    @Test
    fun `animator duration scale 0 snaps to the picker-card end state the instant the long-press registers`() {
        val context = composeTestRule.activity
        val originalScale =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            setDashboard(newViewModel())
            val fullBounds = composeTestRule.onNodeWithTag("gauge-coolant").getBoundsInRoot()
            val pressPoint =
                composeTestRule
                    .onNodeWithTag("gauge-coolant")
                    .fetchSemanticsNode()
                    .boundsInRoot.center

            composeTestRule.mainClock.autoAdvance = false
            try {
                composeTestRule.onRoot().performTouchInput { down(pressPoint) }
                var waited = 0L
                while (composeTestRule.onAllNodesWithTag("gauge-picker-card-coolant").fetchSemanticsNodes().isEmpty()) {
                    check(waited < LONG_PRESS_POLL_TIMEOUT_MS) { "long-press never registered" }
                    // mainClock, not the touch injection's own advanceEventTime: the long-press
                    // gesture detector's timeout is coroutine/frame-clock-based (mainClock),
                    // not driven by the timestamp embedded in synthetic MotionEvents.
                    composeTestRule.mainClock.advanceTimeBy(LONG_PRESS_POLL_STEP_MS)
                    waited += LONG_PRESS_POLL_STEP_MS
                }

                // Height only (not a hard-coded target size) so this doesn't depend on the exact
                // mini-card dimensions — just "clearly already shrunk," which a real snap()
                // guarantees at this instant and a tween cannot.
                val justOpenedBounds = composeTestRule.onNodeWithTag("gauge-picker-card-coolant").getBoundsInRoot()
                assertTrue(
                    "expected the shrink to already be (near) settled under animator-scale 0, but height " +
                        "${justOpenedBounds.height} is still close to the full tile's ${fullBounds.height}",
                    justOpenedBounds.height < fullBounds.height / 2,
                )

                composeTestRule.onRoot().performTouchInput { up() }
            } finally {
                composeTestRule.mainClock.autoAdvance = true
            }
            composeTestRule.waitForIdle()
        } finally {
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalScale)
        }
    }

    /**
     * Review round-1 BLOCKER B1: an anisotropic (independent scaleX/scaleY) transform on the
     * shrinking tile's CONTENT read as a vertically-crushed smear once the full (portrait-tall)
     * tile landed in the (landscape-wide) mini-card slot. `pickerShrinkContentCounterScale`
     * (`GaugePicker.kt`) fixes the distortion; this pins the OUTER geometry regression guard the
     * fix round asked for — the settled current-card's own bounds must have the SAME aspect ratio
     * as a real [GaugeMiniCard] (here, the `rpm` candidate sitting right next to it in the same
     * carousel — a real, unscaled reference rather than a hand-computed pixel value, robust to
     * density/font-scale).
     *
     * `getBoundsInRoot()`, not `getUnclippedBoundsInRoot()`, on BOTH nodes: the latter reports
     * pure LAYOUT size, blind to `pickerShrinkLayer`'s graphicsLayer scale entirely (it's a
     * paint-time transform, not a layout one) — it would report the *current* card's own
     * un-shrunk full-tile size. `getBoundsInRoot()` correctly reflects the transformed/visible
     * bounds for the current card, but clips the *reference* candidate to however much of it
     * happens to be scrolled into the carousel's `LazyRow` viewport — this test's wider-than-
     * default `@Config` exists specifically so a 96dp candidate card fully fits that viewport
     * unclipped, making the two `getBoundsInRoot()` reads comparable.
     */
    @Config(qualifiers = "w1400dp-h500dp-land")
    @Test
    fun `settled current-card bounds match a real GaugeMiniCard's aspect ratio`() {
        setDashboard(newViewModel())

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        val currentBounds = composeTestRule.onNodeWithTag("gauge-picker-card-coolant").getBoundsInRoot()
        val referenceBounds =
            composeTestRule.onNodeWithTag("gauge-picker-card-rpm").performScrollTo().getBoundsInRoot()

        val currentAspect = currentBounds.width / currentBounds.height
        val referenceAspect = referenceBounds.width / referenceBounds.height
        assertEquals(
            "the settled current-card's aspect ratio should match a real GaugeMiniCard's",
            referenceAspect,
            currentAspect,
            ASPECT_TOLERANCE,
        )
    }

    /**
     * Review round-1 MAJOR M3: pins OBD-44's core architectural claim directly — `GaugeTile`
     * itself is never torn down and remounted across the picker-mode boundary — rather than only
     * inferring it from value/tag assertions that a remount would also (eventually) satisfy.
     * [LocalGaugeTileMountProbe] (`DashboardScreen.kt`) fires once per composition MOUNT via
     * `remember { }`, which only re-runs if the underlying composable is recreated — a mutation as
     * narrow as wrapping a tile's call site in `key(isPicking) { }` inside `GaugeSlot` would bump
     * this count on every picker-mode toggle; this test would catch it where a `waitForIdle()` +
     * value-equality assertion would not (the value re-reads correctly either way).
     */
    @Test
    fun `GaugeTile is never torn down and remounted across a long-press + dismiss cycle`() {
        var mountCount = 0
        val viewModel = newViewModel()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalGaugeTileMountProbe provides { mountCount++ }) {
                ObdGaugeTheme {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val gaugeOrder by viewModel.gaugeOrder.collectAsStateWithLifecycle()
                    GaugeDashboard(uiState = uiState, gaugeOrder = gaugeOrder, onSwapGauge = viewModel::swapGauge)
                }
            }
        }
        composeTestRule.waitForIdle()
        val initialMounts = mountCount
        assertTrue("expected every visible tile to have mounted once", initialMounts > 0)

        composeTestRule.onNodeWithTag("gauge-coolant").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertEquals("entering picker mode must not remount any tile", initialMounts, mountCount)

        composeTestRule.onNodeWithTag("gauge-picker-card-coolant").performTouchInput { click() }
        composeTestRule.waitForIdle()
        assertEquals("dismissing must not remount any tile", initialMounts, mountCount)
    }

    /**
     * [full]/[mid]/[settled] are the same node's bounds captured before the long-press, partway
     * through the shrink, and after it settles. Asserts [mid] is strictly BETWEEN the other two on
     * both height (catches a broken/incomplete scale) and vertical center position (catches a
     * broken/zeroed translation — height alone wouldn't: scale and translation are independent
     * `graphicsLayer` properties, see `pickerShrinkLayer`'s KDoc).
     */
    private fun assertMidFlight(
        full: DpRect,
        mid: DpRect,
        settled: DpRect,
    ) {
        assertTrue(
            "mid-flight height (${mid.height}) should be smaller than the full tile's (${full.height})",
            mid.height < full.height,
        )
        assertTrue(
            "mid-flight height (${mid.height}) should still be larger than the settled card's (${settled.height})",
            mid.height > settled.height,
        )
        val fullCenterY = (full.top + full.bottom).value / 2f
        val settledCenterY = (settled.top + settled.bottom).value / 2f
        val midCenterY = (mid.top + mid.bottom).value / 2f
        val settledDelta = settledCenterY - fullCenterY
        val midDelta = midCenterY - fullCenterY
        assertEquals(
            "mid-flight center must have moved toward the settled position, not away from it",
            sign(settledDelta),
            sign(midDelta),
            0.0f,
        )
        assertTrue(
            "mid-flight center (delta $midDelta) must not have already reached the settled position (delta $settledDelta)",
            abs(midDelta) < abs(settledDelta),
        )
    }

    private fun setDashboard(viewModel: DashboardViewModel) {
        composeTestRule.setContent {
            ObdGaugeTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val gaugeOrder by viewModel.gaugeOrder.collectAsStateWithLifecycle()
                GaugeDashboard(uiState = uiState, gaugeOrder = gaugeOrder, onSwapGauge = viewModel::swapGauge)
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun newViewModel(repository: SettingsRepository = PickerInMemorySettingsRepository()): DashboardViewModel =
        DashboardViewModel(FixedReadingsVehicleDataSource(fixedReadings()), clock, repository)

    /** Drains the picker's local "confirm, rise, dismiss" beat (see `GaugePicker.kt`). */
    private fun settlePickerAnimation() {
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(SWAP_SETTLE_MS)
        composeTestRule.waitForIdle()
    }

    private fun hasZone(zone: ThresholdZone) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, zone.name.lowercase())

    private companion object {
        const val SWAP_SETTLE_MS = 500L
        const val LONG_PRESS_POLL_STEP_MS = 50L
        const val LONG_PRESS_POLL_TIMEOUT_MS = 3000L

        // Review round-1 M2: a SMALL delta from wherever performTouchInput { longClick() }'s own
        // gesture leaves mainClock — not a fixed absolute time (see the test's KDoc for why the
        // original version of this constant put the assertion a few ms past settle instead).
        const val MID_FLIGHT_DELTA_MS = 30L
        const val MID_SHRINK_COOLANT_VALUE = 245.0
        const val MID_SHRINK_COOLANT_TEXT = "245°F"
        const val ASPECT_TOLERANCE = 0.05f

        const val COOLANT_VALUE = 235.0
        const val TRANS_VALUE = 150.0
        const val OIL_VALUE = 200.0
        const val BOOST_VALUE = 5.0
        const val RPM_VALUE = 3200.0

        const val COOLANT_TEXT = "235°F"
        const val TRANS_TEXT = "150°F"
        const val OIL_TEXT = "200°F"
        const val BOOST_TEXT = "5.0 PSI"
        const val RPM_TEXT = "3200 RPM"

        fun fixedReadings(now: Instant = Instant.EPOCH): Map<String, Reading> =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, COOLANT_VALUE, now, stale = false),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, TRANS_VALUE, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, OIL_VALUE, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, BOOST_VALUE, now, stale = false),
                PidIds.RPM to Reading(PidIds.RPM, RPM_VALUE, now, stale = false),
            )
    }
}

/** Emits a fixed readings map, [LinkState.Ready] — start/stop are no-ops, mirroring `LiveRecolorTest`'s double. */
private class FixedReadingsVehicleDataSource(
    initial: Map<String, Reading>,
) : VehicleDataSource {
    override val readings = MutableStateFlow(initial)
    override val connection = MutableStateFlow<LinkState>(LinkState.Ready)

    override fun start(pids: List<PidDefinition>) = Unit

    override fun stop() = Unit
}

/** No-DataStore double for tests that don't need real persistence — mirrors `DashboardViewModelTest`'s own. */
private class PickerInMemorySettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
