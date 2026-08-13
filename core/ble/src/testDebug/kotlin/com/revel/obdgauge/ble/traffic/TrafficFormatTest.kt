package com.revel.obdgauge.ble.traffic

import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * OBD-48 line format. This test lives in `src/testDebug/` because [TrafficFormat] is compiled
 * only into the debug variant — a test of it under `src/test/` would fail to compile the
 * release unit-test variant, which is itself part of the fence's proof.
 */
class TrafficFormatTest {
    private val zone = ZoneId.of("UTC")

    // 2026-08-12T04:04:31.482Z
    private val instant = 1_786_593_871_482L

    @Test
    fun `a tx line carries the millisecond stamp and the command`() {
        assertEquals(
            "04:04:31.482 TX  0105",
            TrafficFormat.format(TrafficEntry.Tx("0105"), instant, zone),
        )
    }

    @Test
    fun `a delivered response is rendered without a disposition suffix`() {
        assertEquals(
            "04:04:31.482 RX  41 05 86",
            TrafficFormat.format(TrafficEntry.Rx("41 05 86", RxFate.DELIVERED), instant, zone),
        )
    }

    @Test
    fun `a discarded response says why it was dropped`() {
        val debt = TrafficFormat.format(TrafficEntry.Rx("41 05 86", RxFate.DISCARDED_AS_DEBT), instant, zone)
        val unsolicited =
            TrafficFormat.format(TrafficEntry.Rx("41 05 86", RxFate.DISCARDED_UNSOLICITED), instant, zone)

        assertTrue(debt, debt.contains("debt"))
        assertTrue(unsolicited, unsolicited.contains("unsolicited"))
        assertFalse("a dropped response must not read like a delivered one", debt == unsolicited)
    }

    @Test
    fun `link transitions render their typed cause`() {
        val line = TrafficFormat.format(TrafficEntry.Link(LinkState.Error(LinkError.Gatt(GATT_ERROR))), instant, zone)

        assertTrue(line, line.contains("Gatt(code=$GATT_ERROR)"))
    }

    @Test
    fun `a multi-ecu response stays one logcat line`() {
        // The real shape from hardware session 1: every functional 7DF request answers three
        // times. Emitted raw this would be four logcat records, three of which no longer say
        // which command they answered.
        val raw = "41 05 86\r41 05 86\r41 05 86\r\r"

        val line = TrafficFormat.format(TrafficEntry.Rx(raw, RxFate.DELIVERED), instant, zone)

        assertFalse("carriage returns must be escaped, not emitted", line.contains('\r'))
        assertFalse(line.contains('\n'))
        assertEquals("41 05 86\\r41 05 86\\r41 05 86\\r\\r", line.substringAfter("RX  "))
    }

    @Test
    fun `non printable bytes survive as visible escapes`() {
        val line = TrafficFormat.format(TrafficEntry.Note("stray \u0007 byte"), instant, zone)

        assertTrue(line, line.contains("\\x07"))
    }

    private companion object {
        const val GATT_ERROR = 8
    }
}
