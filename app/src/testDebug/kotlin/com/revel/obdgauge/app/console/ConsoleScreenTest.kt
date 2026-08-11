package com.revel.obdgauge.app.console

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.revel.obdgauge.ble.console.ConsoleEntry
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * OBD-19 Robolectric smoke test: [ConsoleScreen] is a stateless composable (no `ViewModel`/
 * Hilt/Activity involved), so it's driven directly, the same way [com.revel.obdgauge.app.gauge
 * .ConnectionBannerTest] drives `ConnectionBanner` — this is `:app:testDebug`, a build-type
 * source set, so it runs for both `testDemoDebugUnitTest` and `testProdDebugUnitTest` without
 * needing `:core:testing` (a `demo`-only dependency) at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsoleScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val entries =
        listOf(
            ConsoleEntry.CommandSent(Instant.EPOCH, "ATZ"),
            ConsoleEntry.ResponseReceived(Instant.EPOCH, "ATZ", "ELM327 v2.1"),
            ConsoleEntry.ErrorOccurred(Instant.EPOCH, "0100", "timeout waiting for response"),
        )

    @Test
    fun `scrollback renders every entry, in order`() {
        composeTestRule.setContent {
            ConsoleScreen(
                entries = entries,
                linkState = LinkState.Ready,
                commandInFlight = false,
                onSend = {},
                onConnect = {},
                onDisconnect = {},
                onForget = {},
            )
        }

        composeTestRule.onNodeWithTag("console-entry-0").assertIsDisplayed().assertTextContains("ATZ", substring = true)
        composeTestRule
            .onNodeWithTag("console-entry-1")
            .assertIsDisplayed()
            .assertTextContains("ELM327 v2.1", substring = true)
        composeTestRule
            .onNodeWithTag("console-entry-2")
            .assertIsDisplayed()
            .assertTextContains("timeout waiting for response", substring = true)
    }

    @Test
    fun `the link-state banner reflects an Error state`() {
        composeTestRule.setContent {
            ConsoleScreen(
                entries = emptyList(),
                linkState = LinkState.Error(LinkError.DeviceNotFound),
                commandInFlight = false,
                onSend = {},
                onConnect = {},
                onDisconnect = {},
                onForget = {},
            )
        }

        composeTestRule.onNodeWithTag("console-link-state-message").assertTextContains("error", substring = true)
    }

    @Test
    fun `send is disabled with blank input, and enabled once text is typed`() {
        composeTestRule.setContent {
            ConsoleScreen(
                entries = emptyList(),
                linkState = LinkState.Ready,
                commandInFlight = false,
                onSend = {},
                onConnect = {},
                onDisconnect = {},
                onForget = {},
            )
        }

        composeTestRule.onNodeWithTag("console-send").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("console-input").performTextInput("ATI")
        composeTestRule.onNodeWithTag("console-send").assertIsEnabled()
    }

    @Test
    fun `send is disabled while a command is in flight, quick-command chips too`() {
        composeTestRule.setContent {
            ConsoleScreen(
                entries = emptyList(),
                linkState = LinkState.Ready,
                commandInFlight = true,
                onSend = {},
                onConnect = {},
                onDisconnect = {},
                onForget = {},
            )
        }

        composeTestRule.onNodeWithTag("console-input").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("console-send").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("console-chip-ATZ").assertIsNotEnabled()
    }

    @Test
    fun `tapping a quick-command chip sends it directly`() {
        var sent: String? = null
        composeTestRule.setContent {
            ConsoleScreen(
                entries = emptyList(),
                linkState = LinkState.Ready,
                commandInFlight = false,
                onSend = { command -> sent = command },
                onConnect = {},
                onDisconnect = {},
                onForget = {},
            )
        }

        composeTestRule.onNodeWithTag("console-chip-ATZ").performClick()

        assertEquals("ATZ", sent)
    }
}
