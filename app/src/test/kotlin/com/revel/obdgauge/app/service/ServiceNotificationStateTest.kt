package com.revel.obdgauge.app.service

import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * B4 MAJOR (round-1 review, reviews/OBD-24-round1.md): every [LinkState.Error] used to render as
 * "Connection error — reconnecting…" even though nothing in this app currently retries — pure,
 * Robolectric-free tests pinning the corrected, honest copy per cause.
 */
class ServiceNotificationStateTest {
    @Test
    fun `Ready state includes the coolant reading`() {
        val state = serviceNotificationState(LinkState.Ready, coolant = null)

        assertEquals("Connected — Coolant —", state.text)
    }

    @Test
    fun `Disconnected never claims to be reconnecting`() {
        val state = serviceNotificationState(LinkState.Disconnected, coolant = null)

        assertEquals("Not connected", state.text)
        assertFalse(state.text.contains("reconnecting", ignoreCase = true))
    }

    @Test
    fun `PermissionDenied gets terminal-until-user-acts copy, not a reconnecting promise`() {
        val state = serviceNotificationState(LinkState.Error(LinkError.PermissionDenied), coolant = null)

        assertEquals("Bluetooth permission denied — open the app to grant it", state.text)
        assertFalse(state.text.contains("reconnecting", ignoreCase = true))
    }

    @Test
    fun `BluetoothOff gets terminal-until-user-acts copy, not a reconnecting promise`() {
        val state = serviceNotificationState(LinkState.Error(LinkError.BluetoothOff), coolant = null)

        assertEquals("Bluetooth is off — turn it on to reconnect", state.text)
        assertFalse(state.text.contains("reconnecting…", ignoreCase = true))
    }

    @Test
    fun `every other Error cause gets an honest lost-connection copy, never a reconnecting promise`() {
        val causes =
            listOf(
                LinkError.DeviceNotFound,
                LinkError.Timeout,
                LinkError.Gatt(code = ARBITRARY_GATT_CODE),
                LinkError.Unknown(message = "simulated"),
            )

        causes.forEach { cause ->
            val state = serviceNotificationState(LinkState.Error(cause), coolant = null)
            assertEquals("Connection lost — open the app", state.text)
            assertFalse(state.text.contains("reconnecting", ignoreCase = true))
        }
    }

    private companion object {
        const val ARBITRARY_GATT_CODE = 133
    }
}
