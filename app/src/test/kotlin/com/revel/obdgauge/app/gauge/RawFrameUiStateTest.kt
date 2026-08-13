package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.ObdRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class RawFrameUiStateTest {
    @Test
    fun `describeRequest formats a standard mode-01 PID as mode and hex pid`() {
        val request = ObdRequest.StandardPid(mode = 1, pid = 0x05)

        assertEquals("Mode 01 PID 05", describeRequest(request))
    }

    @Test
    fun `describeRequest formats a mode-22 request with its header and rx filter`() {
        val request = ObdRequest.Mode22(header = "07E12130", rxFilter = "032200000000", request = "220543")

        assertEquals("ATSH07E12130 ATCRA032200000000 → 220543", describeRequest(request))
    }
}
