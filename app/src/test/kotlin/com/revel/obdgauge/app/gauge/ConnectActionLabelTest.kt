package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OBD-25's connect affordance, as a pure function of link state — no Compose needed, and
 * flavor-agnostic (`app/src/test`), like the rest of this codebase's presentation logic.
 */
class ConnectActionLabelTest {
    @Test
    fun `disconnected offers Connect`() {
        assertEquals("Connect", connectActionLabel(LinkState.Disconnected))
    }

    @Test
    fun `an error offers Retry, whatever the cause`() {
        assertEquals("Retry", connectActionLabel(LinkState.Error(LinkError.DeviceNotFound)))
        assertEquals("Retry", connectActionLabel(LinkState.Error(LinkError.BluetoothOff)))
        assertEquals("Retry", connectActionLabel(LinkState.Error(LinkError.PermissionDenied)))
    }

    /**
     * The load-bearing one. `:core:ble` spends its whole backoff inside `Error` and moves through
     * `Scanning`/`Connecting` for each attempt, and `BleObdLink.connect()` deliberately resets the
     * backoff ("the user asking again is not the eleventh failure in a row"). Offering a button
     * mid-attempt therefore hands the user a way to supersede an in-flight connect and restart the
     * backoff from zero, repeatedly — the same shape as the restart storm the OBD-24 review
     * measured, just with a finger instead of a state machine driving it.
     */
    @Test
    fun `an attempt already in flight offers nothing to tap`() {
        assertNull(connectActionLabel(LinkState.Scanning))
        assertNull(connectActionLabel(LinkState.Connecting))
    }

    @Test
    fun `a healthy link offers nothing to tap - OBD-84's permanent pill has no action`() {
        assertNull(connectActionLabel(LinkState.Ready))
    }
}
