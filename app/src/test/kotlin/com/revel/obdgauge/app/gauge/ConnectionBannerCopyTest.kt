package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OBD-84's permanent pill: [connectionBannerMessage]/[connectionBannerStateName] as pure
 * functions of ([LinkState], `dataFlowing`) — no Compose needed, mirrors
 * [ConnectActionLabelTest]'s own "presentation logic, tested without a composable" style.
 * [LinkState.Ready] is the only state [dataFlowing] affects; every other state ignores it
 * entirely (asserted below by passing both `true` and `false` and expecting the same copy).
 */
class ConnectionBannerCopyTest {
    @Test
    fun `Ready with data flowing reads Live`() {
        assertEquals("Live · reading ECU", connectionBannerMessage(LinkState.Ready, dataFlowing = true))
        assertEquals("live", connectionBannerStateName(LinkState.Ready, dataFlowing = true))
    }

    @Test
    fun `Ready with no fresh data reads Connected, waiting for ECU`() {
        assertEquals(
            "Connected · waiting for ECU",
            connectionBannerMessage(LinkState.Ready, dataFlowing = false),
        )
        assertEquals("waiting", connectionBannerStateName(LinkState.Ready, dataFlowing = false))
    }

    @Test
    fun `dataFlowing is ignored by every state other than Ready`() {
        val nonReadyStates =
            listOf(
                LinkState.Disconnected,
                LinkState.Scanning,
                LinkState.Connecting,
                LinkState.Error(LinkError.Timeout),
            )
        for (state in nonReadyStates) {
            assertEquals(
                connectionBannerMessage(state, dataFlowing = false),
                connectionBannerMessage(state, dataFlowing = true),
            )
            assertEquals(
                connectionBannerStateName(state, dataFlowing = false),
                connectionBannerStateName(state, dataFlowing = true),
            )
        }
    }

    @Test
    fun `non-Ready copy is unchanged from pre-OBD-84 wording`() {
        assertEquals("Not connected", connectionBannerMessage(LinkState.Disconnected))
        assertEquals("Scanning for dongle…", connectionBannerMessage(LinkState.Scanning))
        assertEquals("Connecting…", connectionBannerMessage(LinkState.Connecting))
        assertEquals("Bluetooth is off", connectionBannerMessage(LinkState.Error(LinkError.BluetoothOff)))
    }
}
