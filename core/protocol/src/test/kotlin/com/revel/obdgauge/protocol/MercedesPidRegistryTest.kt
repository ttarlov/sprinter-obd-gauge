package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MercedesPidRegistryTest {
    private val transTemp = MercedesPidRegistry.transTemp

    @Test
    fun `trans temp is wired from the X-Gauge code, not hand-transcribed constants`() {
        assertEquals(MercedesPidRegistry.TRANS_TEMP_CODE, transTemp.source)
        assertEquals("7E1", transTemp.canId)
        assertEquals("7E9", transTemp.rxFilter)
        assertEquals("2130", transTemp.requestBytes)
        assertEquals("6130", transTemp.responseHeader)
        assertEquals(0x21, transTemp.requestMode)
        assertEquals(0, transTemp.dataByteIndex)
        assertEquals(1, transTemp.dataByteCount)
        assertEquals(1, transTemp.requiredDataBytes)
    }

    @Test
    fun `trans temp is published in Celsius and flagged unverified`() {
        assertEquals(PidIds.TRANS_TEMP, transTemp.definition.id)
        assertEquals(MeasurementUnit.CELSIUS, transTemp.definition.unit)
        assertEquals(PollPriority.SLOW, transTemp.definition.pollPriority)
        assertFalse("mode-22 PIDs are hypotheses until hardware-verified", transTemp.definition.verified)
    }

    @Test
    fun `every mode-22 definition is unverified by default, not just trans temp`() {
        // The "hypothesis until hardware" rule must survive the second registry entry.
        org.junit.Assert.assertTrue(MercedesPidRegistry.all.none { it.definition.verified })
    }

    @Test
    fun `the contract request carries the ATSH and ATCRA values the requester will send`() {
        val request = transTemp.definition.request

        assertTrue(request is ObdRequest.Mode22)
        request as ObdRequest.Mode22
        assertEquals("7E1", request.header)
        assertEquals("7E9", request.rxFilter)
        assertEquals("2130", request.request)
    }

    @Test
    fun `scaling is raw minus 50 Celsius across the range`() {
        assertEquals(-50.0, parse(0x00), TOLERANCE)
        assertEquals(0.0, parse(0x32), TOLERANCE)
        assertEquals(95.0, parse(0x91), TOLERANCE)
        assertEquals(205.0, parse(0xFF), TOLERANCE)
    }

    @Test
    fun `every raw byte's published Celsius converts back to the ScanGauge's Fahrenheit`() {
        val code = MercedesPidRegistry.TRANS_TEMP_CODE.decode()

        for (raw in 0..MAX_BYTE) {
            val displayed = parse(raw) * F_PER_C + F_FREEZING
            assertEquals("raw $raw", code.displayValue(raw), displayed, TOLERANCE)
        }
    }

    @Test
    fun `a warm transmission reads plausibly`() {
        // 203 F is a normal loaded-tow trans temp; the raw byte that produces it is 0x91.
        assertEquals(203.0, parse(0x91) * F_PER_C + F_FREEZING, TOLERANCE)
        assertTrue("a cold-soak morning must not read below ambient", parse(0x3C) in -1.0..11.0)
    }

    @Test
    fun `the parse lambda cannot read past its own payload`() {
        try {
            transTemp.definition.parse(ByteArray(0))
            throw AssertionError("expected an out-of-bounds refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("out of bounds"))
        }
    }

    @Test
    fun `lookup by id works and unknown ids are null`() {
        assertNotNull(MercedesPidRegistry.byId(PidIds.TRANS_TEMP))
        assertNull(MercedesPidRegistry.byId(PidIds.COOLANT))
        assertNull(MercedesPidRegistry.byId("nope"))
        assertEquals(listOf(transTemp.definition), MercedesPidRegistry.definitions)
    }

    @Test
    fun `ATCRA is derived from the ISO 15765-4 response convention, never from RXF`() {
        assertEquals("7E9", MercedesPidRegistry.responseIdFor("7E1"))
        assertEquals("7E8", MercedesPidRegistry.responseIdFor("7E0"))
        assertEquals("7EF", MercedesPidRegistry.responseIdFor("7E7"))
        // The RXF field is kept verbatim for the record but is not the filter.
        assertEquals("032200000000", transTemp.source.rxf)
    }

    @Test
    fun `ids with no single physical responder get no filter rather than an invented one`() {
        assertNull("7DF is the functional broadcast; it has no one responder", MercedesPidRegistry.responseIdFor("7DF"))
        assertNull(MercedesPidRegistry.responseIdFor("18DAF110"))
        assertNull(MercedesPidRegistry.responseIdFor("zzz"))
    }

    private fun parse(raw: Int): Double = transTemp.definition.parse(byteArrayOf(raw.toByte()))

    private companion object {
        const val TOLERANCE = 1e-9
        const val F_PER_C = 9.0 / 5.0
        const val F_FREEZING = 32.0
        const val MAX_BYTE = 255
    }
}
