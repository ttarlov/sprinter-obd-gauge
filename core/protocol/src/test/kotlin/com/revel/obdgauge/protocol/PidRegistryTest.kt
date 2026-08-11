package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registry completeness, wire addresses, response lengths, units, and per-PID scaling. */
class PidRegistryTest {
    @Test
    fun `registry defines exactly the six standard PIDs OBD-14 requires`() {
        assertEquals(6, PidRegistry.all.size)
        assertEquals(
            listOf("0105", "010C", "010B", "0133", "010F", "010D"),
            PidRegistry.all.map { it.command },
        )
    }

    @Test
    fun `ids use the frozen PidIds constants where they exist and module-local ids otherwise`() {
        assertEquals(PidIds.COOLANT, PidRegistry.coolant.definition.id)
        assertEquals(PidIds.RPM, PidRegistry.rpm.definition.id)
        assertEquals(PidIds.BARO, PidRegistry.baro.definition.id)
        assertEquals(ProtocolPidIds.MAP, PidRegistry.map.definition.id)
        assertEquals(ProtocolPidIds.IAT, PidRegistry.intakeAirTemp.definition.id)
        assertEquals(ProtocolPidIds.SPEED, PidRegistry.speed.definition.id)
    }

    @Test
    fun `ids are unique across the registry`() {
        val ids = PidRegistry.all.map { it.definition.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every request is a mode-01 StandardPid at the SAE-defined pid number`() {
        val expected =
            mapOf(
                PidIds.COOLANT to 0x05,
                PidIds.RPM to 0x0C,
                ProtocolPidIds.MAP to 0x0B,
                PidIds.BARO to 0x33,
                ProtocolPidIds.IAT to 0x0F,
                ProtocolPidIds.SPEED to 0x0D,
            )
        for (spec in PidRegistry.all) {
            assertEquals(0x01, spec.mode)
            assertEquals(expected.getValue(spec.definition.id), spec.pid)
            assertEquals(ObdRequest.StandardPid(mode = 0x01, pid = spec.pid), spec.definition.request)
        }
    }

    @Test
    fun `response byte lengths match the SAE definitions`() {
        assertEquals(1, PidRegistry.coolant.dataByteCount)
        assertEquals(2, PidRegistry.rpm.dataByteCount)
        assertEquals(1, PidRegistry.map.dataByteCount)
        assertEquals(1, PidRegistry.baro.dataByteCount)
        assertEquals(1, PidRegistry.intakeAirTemp.dataByteCount)
        assertEquals(1, PidRegistry.speed.dataByteCount)
    }

    @Test
    fun `response headers are the request mode plus 0x40`() {
        assertEquals(0x41, PidRegistry.coolant.responseMode)
        assertEquals("4105", PidRegistry.coolant.responseHeader)
        assertEquals("410C", PidRegistry.rpm.responseHeader)
        assertEquals("410B", PidRegistry.map.responseHeader)
        assertEquals("4133", PidRegistry.baro.responseHeader)
        assertEquals("410F", PidRegistry.intakeAirTemp.responseHeader)
        assertEquals("410D", PidRegistry.speed.responseHeader)
    }

    @Test
    fun `units are the PID's natural SI-ish unit, leaving display conversion to the UI`() {
        assertEquals(MeasurementUnit.CELSIUS, PidRegistry.coolant.definition.unit)
        assertEquals(MeasurementUnit.CELSIUS, PidRegistry.intakeAirTemp.definition.unit)
        assertEquals(MeasurementUnit.KPA, PidRegistry.map.definition.unit)
        assertEquals(MeasurementUnit.KPA, PidRegistry.baro.definition.unit)
        assertEquals(MeasurementUnit.RPM, PidRegistry.rpm.definition.unit)
        assertEquals(MeasurementUnit.KMH, PidRegistry.speed.definition.unit)
    }

    @Test
    fun `fast-changing channels poll every cycle and slow ones do not`() {
        assertEquals(PollPriority.FAST, PidRegistry.rpm.definition.pollPriority)
        assertEquals(PollPriority.FAST, PidRegistry.map.definition.pollPriority)
        assertEquals(PollPriority.FAST, PidRegistry.speed.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.coolant.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.baro.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.intakeAirTemp.definition.pollPriority)
    }

    @Test
    fun `standard PIDs are verified by the SAE standard, unlike the mode-22 hypotheses`() {
        assertTrue(PidRegistry.all.all { it.definition.verified })
    }

    @Test
    fun `lookup by id finds every registered PID and nothing else`() {
        for (spec in PidRegistry.all) {
            assertEquals(spec, PidRegistry.byId(spec.definition.id))
        }
        assertNull(PidRegistry.byId(PidIds.TRANS_TEMP))
        assertNull(PidRegistry.byId(""))
    }

    @Test
    fun `lookup by wire address finds every registered PID and nothing else`() {
        for (spec in PidRegistry.all) {
            assertEquals(spec, PidRegistry.byPid(spec.mode, spec.pid))
        }
        assertNull(PidRegistry.byPid(0x01, 0x00))
        assertNull(PidRegistry.byPid(0x22, 0x05))
    }

    @Test
    fun `definitions expose the registry one-to-one for a VehicleDataSource`() {
        assertEquals(PidRegistry.all.map { it.definition }, PidRegistry.definitions)
    }

    @Test
    fun `each parse lambda applies its PID's SAE scaling`() {
        assertEquals(50.0, PidRegistry.coolant.definition.parse(byteArrayOf(0x5A)), 0.0)
        assertEquals(0.0, PidRegistry.intakeAirTemp.definition.parse(byteArrayOf(0x28)), 0.0)
        assertEquals(2000.0, PidRegistry.rpm.definition.parse(byteArrayOf(0x1F, 0x40)), 0.0)
        assertEquals(100.0, PidRegistry.map.definition.parse(byteArrayOf(0x64)), 0.0)
        assertEquals(98.0, PidRegistry.baro.definition.parse(byteArrayOf(0x62)), 0.0)
        assertEquals(80.0, PidRegistry.speed.definition.parse(byteArrayOf(0x50)), 0.0)
    }

    @Test
    fun `parse lambdas read high bytes as unsigned`() {
        assertEquals(215.0, PidRegistry.coolant.definition.parse(byteArrayOf(0xFF.toByte())), 0.0)
        assertEquals(255.0, PidRegistry.baro.definition.parse(byteArrayOf(0xFF.toByte())), 0.0)
        assertEquals(
            16383.75,
            PidRegistry.rpm.definition.parse(byteArrayOf(0xFF.toByte(), 0xFF.toByte())),
            0.0,
        )
    }
}
