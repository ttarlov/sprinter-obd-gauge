package com.revel.obdgauge.app.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.revel.obdgauge.app.gauge.GaugeThresholds
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Drives [SettingsScreen] directly (no `ViewModel`/Hilt/Activity) — same split
 * `DashboardScreenTest`/`ConsoleScreenTest` use. Persistence itself is `SettingsRepositoryTest`'s
 * job; the settings-edit-recolors-the-dashboard AC is `LiveRecolorTest`'s.
 *
 * The screen's outer `Column` is a `verticalScroll` taller than the default Robolectric
 * viewport — controls below the fold (reset-thresholds, units, keep-screen-on, poll rate) need
 * [performScrollTo] before [performClick]/[performTextReplacement], exactly like a real user
 * scrolling down. (Review round-1 M2: an earlier version of this file worked around a wrong
 * diagnosis — off-screen nodes silently no-op'd without `performScrollTo`, misread as a
 * Robolectric text-field limitation — by testing each section composable in isolation instead
 * of the assembled screen. That workaround is gone; everything below drives the real,
 * assembled [SettingsScreen].)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `back button invokes onBack`() {
        var backCount = 0
        setFullScreenContent(onBack = { backCount++ })

        composeTestRule.onNodeWithTag("settings-back-button").performScrollTo().performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun `toggling a gauge's visibility switch reports the new value`() {
        var reportedId: String? = null
        var reportedVisible: Boolean? = null
        setFullScreenContent(onSetGaugeVisible = { id, visible ->
            reportedId = id
            reportedVisible = visible
        })

        composeTestRule.onNodeWithTag("gauge-visible-${PidIds.COOLANT}").performScrollTo().performClick()

        assertEquals(PidIds.COOLANT, reportedId)
        assertEquals(false, reportedVisible)
    }

    @Test
    fun `moving a gauge up reports a negative delta`() {
        var reportedDelta: Int? = null
        setFullScreenContent(onMoveGauge = { _, delta -> reportedDelta = delta })

        composeTestRule.onNodeWithTag("gauge-up-${PidIds.TRANS_TEMP}").performScrollTo().performClick()

        assertEquals(-1, reportedDelta)
    }

    @Test
    fun `editing the coolant green-max field commits a converted wire-unit value`() {
        var committedId: String? = null
        var committedThresholds: GaugeThresholds? = null
        setFullScreenContent(onSetThresholdOverride = { id, thresholds ->
            committedId = id
            committedThresholds = thresholds
        })

        composeTestRule
            .onNodeWithTag(
                "threshold-${PidIds.COOLANT}-greenMax",
            ).performScrollTo()
            .performTextReplacement("210.0")

        assertEquals(PidIds.COOLANT, committedId)
        assertEquals(210.0, committedThresholds?.greenMax)
    }

    @Test
    fun `editing a threshold field while displaying Celsius commits the wire-unit (Fahrenheit) equivalent`() {
        var committedThresholds: GaugeThresholds? = null
        setFullScreenContent(
            settings = AppSettings(units = UnitPreferences(temperatureUnit = MeasurementUnit.CELSIUS)),
            onSetThresholdOverride = { _, thresholds -> committedThresholds = thresholds },
        )

        // 100 C, typed in the field because the display unit is now Celsius.
        composeTestRule
            .onNodeWithTag(
                "threshold-${PidIds.COOLANT}-greenMax",
            ).performScrollTo()
            .performTextReplacement("100")

        // Stored value must be in the wire unit (Fahrenheit) — 100 C = 212 F.
        assertEquals(212.0, committedThresholds?.greenMax!!, 1e-9)
    }

    @Test
    fun `clearing a threshold field commits nothing (review round-1 M6)`() {
        var commitCount = 0
        setFullScreenContent(onSetThresholdOverride = { _, _ -> commitCount++ })

        composeTestRule.onNodeWithTag("threshold-${PidIds.COOLANT}-greenMax").performScrollTo().performTextClearance()

        assertEquals(0, commitCount)
    }

    @Test
    fun `clear then retype stores exactly the typed value (round-2 regression)`() {
        // Round-2 regression: derived text snapped the old value back after clearance with the
        // cursor at 0, so retyping PREPENDED — clear + "225" stored 225220.0 (silently GREEN
        // forever). The field must stay genuinely blank after clearance, and the retyped value
        // must be stored verbatim.
        var lastCommitted: GaugeThresholds? = null
        setFullScreenContent(onSetThresholdOverride = { _, value -> lastCommitted = value })
        val field = composeTestRule.onNodeWithTag("threshold-${PidIds.COOLANT}-greenMax")

        field.performScrollTo().performTextClearance()
        composeTestRule.waitForIdle()
        val cleared =
            field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertEquals("the field must stay blank after clearance", "", cleared)

        field.performTextInput("225")
        composeTestRule.waitForIdle()
        assertEquals(225.0, requireNotNull(lastCommitted?.greenMax), 0.0)
    }

    @Test
    fun `a unit toggle mid-edit re-derives the field instead of reinterpreting typed digits`() {
        // Round-3 finding: clickable doesn't take focus, so toggling to Celsius while the field
        // showed Fahrenheit digits made the next commit parse them as Celsius (3821 F stored).
        // The displayed text must re-derive in the new unit the moment units change.
        var committed: GaugeThresholds? = null
        val settings = androidx.compose.runtime.mutableStateOf(AppSettings())
        composeTestRule.setContent {
            ObdGaugeTheme {
                SettingsScreen(
                    settings = settings.value,
                    onSetGaugeVisible = { _, _ -> },
                    onMoveGauge = { _, _ -> },
                    onSetThresholdOverride = { id, value ->
                        committed = value
                        if (value != null) {
                            settings.value =
                                settings.value.copy(
                                    thresholdOverrides = settings.value.thresholdOverrides + (id to value),
                                )
                        }
                    },
                    onResetThresholds = {},
                    onSetUnits = { settings.value = settings.value.copy(units = it) },
                    onSetKeepScreenOn = {},
                    onSetShowConnectionStatus = {},
                    onSetPollRate = {},
                    onOpenRecordings = {},
                    onBack = {},
                )
            }
        }
        val field = composeTestRule.onNodeWithTag("threshold-${PidIds.COOLANT}-greenMax")

        field.performScrollTo().performTextReplacement("210")
        composeTestRule.onNodeWithTag("unit-temp-celsius").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val shown =
            field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        org.junit.Assert.assertNotEquals("the field must not keep Fahrenheit digits in Celsius mode", "210", shown)
        org.junit.Assert.assertTrue(
            "expected the Celsius re-derivation of 210F, got '$shown'",
            shown.startsWith("98.8"),
        )

        field.performTextReplacement("100")
        composeTestRule.waitForIdle()
        assertEquals(212.0, requireNotNull(committed?.greenMax), 0.0)
    }

    @Test
    fun `reset thresholds button invokes the callback`() {
        var resetCount = 0
        setFullScreenContent(onResetThresholds = { resetCount++ })

        composeTestRule.onNodeWithTag("reset-thresholds-button").performScrollTo().performClick()

        assertEquals(1, resetCount)
    }

    @Test
    fun `selecting Celsius reports the updated unit preferences`() {
        var reported: UnitPreferences? = null
        setFullScreenContent(onSetUnits = { units -> reported = units })

        composeTestRule.onNodeWithTag("unit-temp-celsius").performScrollTo().performClick()

        assertEquals(MeasurementUnit.CELSIUS, reported?.temperatureUnit)
    }

    @Test
    fun `selecting kPa reports the updated unit preferences`() {
        var reported: UnitPreferences? = null
        setFullScreenContent(onSetUnits = { units -> reported = units })

        composeTestRule.onNodeWithTag("unit-pressure-kpa").performScrollTo().performClick()

        assertEquals(MeasurementUnit.KPA, reported?.pressureUnit)
    }

    @Test
    fun `keep-screen-on switch reports the toggled value`() {
        var reported: Boolean? = null
        setFullScreenContent(onSetKeepScreenOn = { enabled -> reported = enabled })

        composeTestRule.onNodeWithTag("keep-screen-on-switch").performScrollTo().performClick()

        assertTrue(reported!!)
    }

    @Test
    fun `show-connection-status switch reports the toggled value`() {
        var reported: Boolean? = null
        // Unlike keepScreenOn, AppSettings' default is true here ("it's meant to be
        // permanent") — a tap on the default-content switch reports false.
        setFullScreenContent(onSetShowConnectionStatus = { enabled -> reported = enabled })

        composeTestRule.onNodeWithTag("show-connection-status-switch").performScrollTo().performClick()

        assertFalse(reported!!)
    }

    @Test
    fun `selecting a poll rate reports it`() {
        var reported: PollRate? = null
        setFullScreenContent(onSetPollRate = { rate -> reported = rate })

        composeTestRule.onNodeWithTag("poll-rate-${PollRate.HZ_1.name}").performScrollTo().performClick()

        assertEquals(PollRate.HZ_1, reported)
    }

    @Suppress("LongParameterList")
    private fun setFullScreenContent(
        settings: AppSettings = AppSettings(),
        onSetGaugeVisible: (String, Boolean) -> Unit = { _, _ -> },
        onMoveGauge: (String, Int) -> Unit = { _, _ -> },
        onSetThresholdOverride: (String, GaugeThresholds?) -> Unit = { _, _ -> },
        onResetThresholds: () -> Unit = {},
        onSetUnits: (UnitPreferences) -> Unit = {},
        onSetKeepScreenOn: (Boolean) -> Unit = {},
        onSetShowConnectionStatus: (Boolean) -> Unit = {},
        onSetPollRate: (PollRate) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            ObdGaugeTheme {
                SettingsScreen(
                    settings = settings,
                    onSetGaugeVisible = onSetGaugeVisible,
                    onMoveGauge = onMoveGauge,
                    onSetThresholdOverride = onSetThresholdOverride,
                    onResetThresholds = onResetThresholds,
                    onSetUnits = onSetUnits,
                    onSetKeepScreenOn = onSetKeepScreenOn,
                    onSetShowConnectionStatus = onSetShowConnectionStatus,
                    onSetPollRate = onSetPollRate,
                    onOpenRecordings = {},
                    onBack = onBack,
                )
            }
        }
    }
}
