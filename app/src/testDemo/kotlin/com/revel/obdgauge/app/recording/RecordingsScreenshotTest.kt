package com.revel.obdgauge.app.recording

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** OBD-70 Roborazzi refs: the Recordings screen, empty and populated. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingsScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `recordings screen, empty`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                RecordingsScreen(sessions = emptyList(), onShare = {}, onDelete = {}, onBack = {})
            }
        }

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "recordings_empty.png")
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `recordings screen, populated`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                RecordingsScreen(sessions = sampleSessions(), onShare = {}, onDelete = {}, onBack = {})
            }
        }

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "recordings_populated.png")
    }

    private fun sampleSessions(): List<RecordingSummary> =
        listOf(
            RecordingSummary(
                SessionIndexEntry(
                    file = "obdlog_2026-08-18_2207.csv",
                    startedAt = Instant.parse("2026-08-19T04:07:58Z"),
                    endedAt = Instant.parse("2026-08-19T04:23:12Z"),
                    rows = 914,
                    pidCount = 5,
                ),
                sizeBytes = 84_200,
            ),
            RecordingSummary(
                SessionIndexEntry(
                    file = "obdlog_2026-08-17_1830.csv",
                    startedAt = Instant.parse("2026-08-18T00:30:00Z"),
                    endedAt = Instant.parse("2026-08-18T00:41:20Z"),
                    rows = 680,
                    pidCount = 5,
                ),
                sizeBytes = 61_500,
            ),
        )

    private companion object {
        const val SCREENSHOT_DIR = "src/testDemo/screenshots/"
    }
}
