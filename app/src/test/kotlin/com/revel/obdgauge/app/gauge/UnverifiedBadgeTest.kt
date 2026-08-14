package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * OBD-27 self-test plan: "Compose UI test asserting badge presence/absence by the unverified
 * flag and raw-viewer content on tap." Hand-built [DashboardUiState] via [toDashboardUiState]
 * (no `FakeVehicleDataSource`), so — like `GaugeSwapPickerTest` — this lives in the
 * flavor-agnostic `app/src/test`, not `testDemo`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land")
class UnverifiedBadgeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `unverified gauges show the badge, verified gauges do not`() {
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(readyState()) } }

        // OBD-50: oilTemp verified via standard 015C. OBD-60: transTemp joined the verified side too —
        // byte 11 of the `21 30` record was identified on-vehicle, so its badge is gone. Boost is now
        // the sole unverified core gauge (the speed-density "Est."), so only its badge shows.
        composeTestRule.onNodeWithTag("gauge-boost-unverified-badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("gauge-transTemp-unverified-badge").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-coolant-unverified-badge").assertDoesNotExist()
        composeTestRule.onNodeWithTag("gauge-oilTemp-unverified-badge").assertDoesNotExist()
    }

    @Test
    fun `tapping the badge opens the raw-response viewer with request, value, and unverified note`() {
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(readyState()) } }

        composeTestRule.onNodeWithTag("gauge-boost-unverified-badge").performClick()

        composeTestRule.onNodeWithTag("raw-viewer-boost").assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("raw-viewer-boost-request")
            .assertTextEquals("Request: Mode 01 PID 0B")
        composeTestRule.onNodeWithTag("raw-viewer-boost-value").assertTextEquals("Value: 5.0 PSI")
        composeTestRule.onNodeWithTag("raw-viewer-boost-unverified-note").assertIsDisplayed()
    }

    @Test
    fun `closing the raw-response viewer dismisses it`() {
        // boost (OBD-60: transTemp is verified now, so boost is the unverified exemplar).
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(readyState()) } }
        composeTestRule.onNodeWithTag("gauge-boost-unverified-badge").performClick()
        composeTestRule.onNodeWithTag("raw-viewer-boost").assertIsDisplayed()

        composeTestRule.onNodeWithTag("raw-viewer-boost-close").performClick()

        composeTestRule.onNodeWithTag("raw-viewer-boost").assertDoesNotExist()
    }

    // Mutation (c) killer (round-1 review, reviews/OBD-24-round1.md): "drop the settled gate"
    // SURVIVED against the existing Roborazzi references, because a mid-shrink/grow frame was
    // never captured in a reference image — a screenshot can only ever pin the settled states it
    // was recorded from. Testing `UnverifiedBadgeOverlay` directly at its own `settled` seam
    // (rather than trying to reproduce live shrink/grow animation timing) hits exactly the gate
    // the mutant would remove, deterministically.
    @Test
    fun `badge does not render while the tile is mid-shrink or mid-grow (settled = false)`() {
        // boost (OBD-60: transTemp is verified now, so boost is the unverified exemplar).
        val unverifiedTile = readyState().boost

        composeTestRule.setContent {
            ObdGaugeTheme {
                Box(modifier = Modifier.size(BOX_SIZE_DP.dp)) {
                    UnverifiedBadgeOverlay(
                        tile = unverifiedTile,
                        settled = false,
                        rawFrame = null,
                        modifier = Modifier.size(BOX_SIZE_DP.dp),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("gauge-boost-unverified-badge").assertDoesNotExist()
    }

    @Test
    fun `badge renders once settled becomes true for the same unverified tile`() {
        val unverifiedTile = readyState().boost

        composeTestRule.setContent {
            ObdGaugeTheme {
                Box(modifier = Modifier.size(BOX_SIZE_DP.dp)) {
                    UnverifiedBadgeOverlay(
                        tile = unverifiedTile,
                        settled = true,
                        rawFrame = null,
                        modifier = Modifier.size(BOX_SIZE_DP.dp),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("gauge-boost-unverified-badge").assertIsDisplayed()
    }

    // B9 MINOR: the visible glyph stays small, but the tap target must meet Android's 48dp
    // minimum recommended touch target regardless.
    @Test
    fun `badge touch target is at least 48dp in both dimensions`() {
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(readyState()) } }

        composeTestRule
            .onNodeWithTag("gauge-boost-unverified-badge")
            .assertWidthIsAtLeast(MIN_TOUCH_TARGET_DP.dp)
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET_DP.dp)
    }

    // B10 MINOR: TalkBack must announce the badge as one Button node with the real label, not a
    // bare "?" from an unmerged Text child.
    @Test
    fun `badge announces as a single Button node with the unverified label, not a bare glyph`() {
        composeTestRule.setContent { ObdGaugeTheme { GaugeDashboard(readyState()) } }

        composeTestRule
            .onNodeWithTag("gauge-boost-unverified-badge")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Unverified reading — tap for raw response"),
                ),
            )
        // If mergeDescendants were dropped, this same tag would resolve to a node whose own text
        // is "?" rather than carrying the ContentDescription assertion above at all — the
        // presence of ContentDescription on THIS node (not a merged-away child) is the pin.
    }

    // B8 MINOR (round-1 review, reviews/OBD-24-round1.md): unknown-id regression, the
    // DashboardScreen.kt:347 half of the fix — an id that's swapped into gaugeOrder but ISN'T in
    // GAUGE_CATALOG at all (e.g. a stale entry from a since-removed PID) used to fall back to
    // `verified = true` via `GAUGE_CATALOG_BY_ID[id]?.verified ?: true`; an id this app can't
    // vouch for at all must never render as silently trustworthy — it should show the badge, not
    // hide it.
    @Test
    fun `an id absent from GAUGE_CATALOG entirely still shows the badge, not a silent verified default`() {
        val orderWithUnknownId = listOf(GaugeOrderEntry(UNKNOWN_ID)) + DEFAULT_GAUGE_ORDER.drop(1)

        composeTestRule.setContent {
            ObdGaugeTheme {
                GaugeDashboard(uiState = DashboardUiState.Loading, gaugeOrder = orderWithUnknownId)
            }
        }

        composeTestRule.onNodeWithTag("gauge-$UNKNOWN_ID-unverified-badge").assertIsDisplayed()
    }

    @Test
    fun `a verified gauge's raw viewer omits the unverified note`() {
        // Coolant has no badge to tap on the dashboard (it's verified — first test above), but
        // RawResponseDialog itself is reusable directly: this pins the "verified gauges show no
        // unverified-note" half of it without needing a badge tap to reach it.
        val coolantFrame = readyState().rawFrames.getValue(PidIds.COOLANT)
        composeTestRule.setContent { ObdGaugeTheme { RawResponseDialog(state = coolantFrame, onDismiss = {}) } }

        composeTestRule.onNodeWithTag("raw-viewer-coolant-value").assertTextEquals("Value: 190°F")
        composeTestRule.onNodeWithTag("raw-viewer-coolant-unverified-note").assertDoesNotExist()
    }

    private fun readyState(): DashboardUiState {
        val now = Instant.EPOCH
        val readings =
            mapOf(
                PidIds.COOLANT to Reading(PidIds.COOLANT, COOLANT_VALUE, now, stale = false),
                PidIds.OIL_TEMP to Reading(PidIds.OIL_TEMP, OIL_VALUE, now, stale = false),
                PidIds.TRANS_TEMP to Reading(PidIds.TRANS_TEMP, TRANS_VALUE, now, stale = false),
                PidIds.BOOST to Reading(PidIds.BOOST, BOOST_VALUE, now, stale = false),
            )
        return toDashboardUiState(readings, LinkState.Ready, now)
    }

    private companion object {
        const val COOLANT_VALUE = 190.0
        const val OIL_VALUE = 200.0
        const val TRANS_VALUE = 215.0
        const val BOOST_VALUE = 5.0
        const val BOX_SIZE_DP = 200
        const val MIN_TOUCH_TARGET_DP = 48
        const val UNKNOWN_ID = "notAGauge"
    }
}
