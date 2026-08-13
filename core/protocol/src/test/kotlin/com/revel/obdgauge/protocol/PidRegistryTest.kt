package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registry completeness, wire addresses, response lengths, units, and per-PID scaling. */
class PidRegistryTest {
    @Test
    fun `registry defines exactly the fifteen standard PIDs OBD-14, OBD-43, OBD-50 and OBD-56 require`() {
        // OBD-50 grew this from 8 to 14; OBD-56 adds the fifteenth, intakeAirTempSensor (0168 — the
        // IAT this van actually answers, standard 010F being NO DATA). fuelRate, moduleVoltage and
        // MAF (0166) stay blocked on a missing MeasurementUnit; see PidRegistry's KDoc.
        assertEquals(15, PidRegistry.all.size)
        assertEquals(
            // OBD-14's six, then OBD-43's two, then OBD-50's six, then OBD-56's one appended. New
            // entries go on the end (0168 slots after 010F, its unsupported standard sibling), so
            // an existing caller's poll order never shifts under it.
            listOf(
                "0105",
                "010C",
                "010B",
                "0133",
                "010F",
                "0168",
                "010D",
                "0104",
                "0111",
                "015C",
                "012F",
                "0146",
                "0149",
                "0161",
                "0162",
            ),
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
        // OBD-50: oilTemp is the one session-2 addition wired to a frozen PidIds constant — it's
        // the id :app's dashboard oil tile already requests (DashboardPids.kt). The rest are
        // module-local, same as the OBD-43 pair above.
        assertEquals(PidIds.OIL_TEMP, PidRegistry.oilTemp.definition.id)
        assertEquals(ProtocolPidIds.FUEL_LEVEL, PidRegistry.fuelLevel.definition.id)
        assertEquals(ProtocolPidIds.AMBIENT_TEMP, PidRegistry.ambientTemp.definition.id)
        assertEquals(ProtocolPidIds.ACCEL_PEDAL, PidRegistry.accelPedal.definition.id)
        assertEquals(ProtocolPidIds.DEMAND_TORQUE, PidRegistry.demandTorque.definition.id)
        assertEquals(ProtocolPidIds.ACTUAL_TORQUE, PidRegistry.actualTorque.definition.id)
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
                ProtocolPidIds.IAT_SENSOR to 0x68,
                ProtocolPidIds.SPEED to 0x0D,
                ProtocolPidIds.ENGINE_LOAD to 0x04,
                ProtocolPidIds.THROTTLE to 0x11,
                PidIds.OIL_TEMP to 0x5C,
                ProtocolPidIds.FUEL_LEVEL to 0x2F,
                ProtocolPidIds.AMBIENT_TEMP to 0x46,
                ProtocolPidIds.ACCEL_PEDAL to 0x49,
                ProtocolPidIds.DEMAND_TORQUE to 0x61,
                ProtocolPidIds.ACTUAL_TORQUE to 0x62,
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
        assertEquals(1, PidRegistry.oilTemp.dataByteCount)
        assertEquals(1, PidRegistry.fuelLevel.dataByteCount)
        assertEquals(1, PidRegistry.ambientTemp.dataByteCount)
        assertEquals(1, PidRegistry.accelPedal.dataByteCount)
        assertEquals(1, PidRegistry.demandTorque.dataByteCount)
        assertEquals(1, PidRegistry.actualTorque.dataByteCount)
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
        assertEquals("415C", PidRegistry.oilTemp.responseHeader)
        assertEquals("412F", PidRegistry.fuelLevel.responseHeader)
        assertEquals("4146", PidRegistry.ambientTemp.responseHeader)
        assertEquals("4149", PidRegistry.accelPedal.responseHeader)
        assertEquals("4161", PidRegistry.demandTorque.responseHeader)
        assertEquals("4162", PidRegistry.actualTorque.responseHeader)
    }

    @Test
    fun `units are the PID's natural SI-ish unit, leaving display conversion to the UI`() {
        assertEquals(MeasurementUnit.CELSIUS, PidRegistry.coolant.definition.unit)
        assertEquals(MeasurementUnit.CELSIUS, PidRegistry.intakeAirTemp.definition.unit)
        assertEquals(MeasurementUnit.KPA, PidRegistry.map.definition.unit)
        assertEquals(MeasurementUnit.KPA, PidRegistry.baro.definition.unit)
        assertEquals(MeasurementUnit.RPM, PidRegistry.rpm.definition.unit)
        assertEquals(MeasurementUnit.KMH, PidRegistry.speed.definition.unit)
        assertEquals(MeasurementUnit.CELSIUS, PidRegistry.oilTemp.definition.unit)
        assertEquals(MeasurementUnit.CELSIUS, PidRegistry.ambientTemp.definition.unit)
        assertEquals(MeasurementUnit.PERCENT, PidRegistry.fuelLevel.definition.unit)
        assertEquals(MeasurementUnit.PERCENT, PidRegistry.accelPedal.definition.unit)
        assertEquals(MeasurementUnit.PERCENT, PidRegistry.demandTorque.definition.unit)
        assertEquals(MeasurementUnit.PERCENT, PidRegistry.actualTorque.definition.unit)
    }

    @Test
    fun `fast-changing channels poll every cycle and slow ones do not`() {
        assertEquals(PollPriority.FAST, PidRegistry.rpm.definition.pollPriority)
        assertEquals(PollPriority.FAST, PidRegistry.map.definition.pollPriority)
        assertEquals(PollPriority.FAST, PidRegistry.speed.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.coolant.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.baro.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.intakeAirTemp.definition.pollPriority)
        // OBD-50: none of session 2's additions are boost-rate signals — all SLOW.
        assertEquals(PollPriority.SLOW, PidRegistry.oilTemp.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.fuelLevel.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.ambientTemp.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.accelPedal.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.demandTorque.definition.pollPriority)
        assertEquals(PollPriority.SLOW, PidRegistry.actualTorque.definition.pollPriority)
    }

    @Test
    fun `standard PIDs are SAE-verified, except the extended sensor whose value is unconfirmed`() {
        // Every SAE-standard decode is verified — except intakeAirTempSensor (0168, OBD-56): its
        // decode FORMAT is high-confidence but its VALUE is unconfirmed against ground truth until
        // a 🖐 throttle sweep, so it ships verified = false like a mode-22 hypothesis.
        assertFalse(PidRegistry.intakeAirTempSensor.definition.verified)
        assertTrue(PidRegistry.all.filterNot { it == PidRegistry.intakeAirTempSensor }.all { it.definition.verified })
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

    // --- OBD-43: engine load 0104 and throttle position 0111 -------------------------------

    @Test
    fun `the OBD-43 PIDs carry their SAE wire facts`() {
        assertEquals(ProtocolPidIds.ENGINE_LOAD, PidRegistry.engineLoad.definition.id)
        assertEquals("0104", PidRegistry.engineLoad.command)
        assertEquals("4104", PidRegistry.engineLoad.responseHeader)
        assertEquals(1, PidRegistry.engineLoad.dataByteCount)

        assertEquals(ProtocolPidIds.THROTTLE, PidRegistry.throttlePosition.definition.id)
        assertEquals("0111", PidRegistry.throttlePosition.command)
        assertEquals("4111", PidRegistry.throttlePosition.responseHeader)
        assertEquals(1, PidRegistry.throttlePosition.dataByteCount)
    }

    @Test
    fun `the OBD-43 PIDs publish percent on the FAST cadence`() {
        assertEquals(MeasurementUnit.PERCENT, PidRegistry.engineLoad.definition.unit)
        assertEquals(MeasurementUnit.PERCENT, PidRegistry.throttlePosition.definition.unit)
        assertEquals(PollPriority.FAST, PidRegistry.engineLoad.definition.pollPriority)
        assertEquals(PollPriority.FAST, PidRegistry.throttlePosition.definition.pollPriority)
    }

    @Test
    fun `the OBD-43 parse lambdas apply the full-scale-byte percentage exactly`() {
        assertEquals(0.0, PidRegistry.engineLoad.definition.parse(byteArrayOf(0x00)), 0.0)
        assertEquals(100.0, PidRegistry.engineLoad.definition.parse(byteArrayOf(0xFF.toByte())), 0.0)
        assertEquals(20.0, PidRegistry.engineLoad.definition.parse(byteArrayOf(51)), 0.0)

        assertEquals(0.0, PidRegistry.throttlePosition.definition.parse(byteArrayOf(0x00)), 0.0)
        assertEquals(100.0, PidRegistry.throttlePosition.definition.parse(byteArrayOf(0xFF.toByte())), 0.0)
    }

    @Test
    fun `an idling OM642 reads a high throttle number, and that is the intake flap not the pedal`() {
        // The captured 0xD3 at warm idle, pedal untouched. Pinned as a REGRESSION GUARD ON THE
        // KDOC, not as an aspiration: anyone who "fixes" this channel by rescaling it so idle
        // lands near 0 % — the gasoline intuition — breaks this test, which is the point. The
        // scaling is right; the actuator is simply not a throttle butterfly.
        val idle = PidRegistry.throttlePosition.definition.parse(byteArrayOf(0xD3.toByte()))

        assertTrue("a diesel intake flap idles wide open, near 83 %: was $idle", idle in 82.5..83.5)
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

    // --- OBD-50 (session 2, 2026-08-13): oilTemp, fuelLevel, ambientTemp, accelPedal, ---------
    // --- demandTorque, actualTorque -------------------------------------------------------

    @Test
    fun `the OBD-50 PIDs carry their SAE wire facts and all poll SLOW`() {
        assertEquals("015C", PidRegistry.oilTemp.command)
        assertEquals("012F", PidRegistry.fuelLevel.command)
        assertEquals("0146", PidRegistry.ambientTemp.command)
        assertEquals("0149", PidRegistry.accelPedal.command)
        assertEquals("0161", PidRegistry.demandTorque.command)
        assertEquals("0162", PidRegistry.actualTorque.command)

        for (spec in listOf(
            PidRegistry.oilTemp,
            PidRegistry.fuelLevel,
            PidRegistry.ambientTemp,
            PidRegistry.accelPedal,
            PidRegistry.demandTorque,
            PidRegistry.actualTorque,
        )) {
            assertEquals(PollPriority.SLOW, spec.definition.pollPriority)
            assertTrue("OBD-50 additions are live-verified: ${spec.definition.id}", spec.definition.verified)
        }
    }

    @Test
    fun `the OBD-50 parse lambdas reproduce the session's van anchors exactly`() {
        // docs/hardware/session-2026-08-13.md §5, each written-down value re-derived from the
        // raw byte the definition's own parse lambda receives — not recomputed by hand.
        assertEquals(89.0, PidRegistry.oilTemp.definition.parse(byteArrayOf(0x81.toByte())), 0.0)
        assertEquals(20.0, PidRegistry.ambientTemp.definition.parse(byteArrayOf(0x3C)), 0.0)
        assertEquals(5.0, PidRegistry.demandTorque.definition.parse(byteArrayOf(0x82.toByte())), 0.0)
        assertEquals(11.0, PidRegistry.actualTorque.definition.parse(byteArrayOf(0x88.toByte())), 0.0)

        // Fuel level and accel pedal were written down to one decimal (42.7 %, 5.1 %); assert
        // the full-precision quotient so the rounding cannot hide a wrong divisor.
        assertEquals(10900.0 / 255.0, PidRegistry.fuelLevel.definition.parse(byteArrayOf(0x6D)), 0.0)
        assertEquals(1300.0 / 255.0, PidRegistry.accelPedal.definition.parse(byteArrayOf(0x0D)), 0.0)
    }

    // --- OBD-56 (2026-08-13): the extended IAT the survey missed, 0168 sensor 1 -----------------

    @Test
    fun `the IAT sensor channel carries the 0168 wire facts and ships unverified`() {
        assertEquals(ProtocolPidIds.IAT_SENSOR, PidRegistry.intakeAirTempSensor.definition.id)
        assertEquals("0168", PidRegistry.intakeAirTempSensor.command)
        assertEquals("4168", PidRegistry.intakeAirTempSensor.responseHeader)
        assertEquals(MeasurementUnit.CELSIUS, PidRegistry.intakeAirTempSensor.definition.unit)
        assertEquals(PollPriority.SLOW, PidRegistry.intakeAirTempSensor.definition.pollPriority)
        // Two data bytes: the support/bank byte then sensor 1. Asking for exactly two lets the
        // parser ignore the van's non-standard trailing padding without it breaking the read.
        assertEquals(2, PidRegistry.intakeAirTempSensor.dataByteCount)
        assertFalse(PidRegistry.intakeAirTempSensor.definition.verified)
    }

    @Test
    fun `the IAT sensor decode reads sensor 1 from the second data byte and ignores padding`() {
        // Capture `41 68 01 54 …`: byte 0 = 0x01 (support), byte 1 = 0x54 → 84 − 40 = 44 °C. The
        // parse lambda receives the two requested bytes; the padding beyond them never reaches it.
        assertEquals(44.0, PidRegistry.intakeAirTempSensor.definition.parse(byteArrayOf(0x01, 0x54)), 0.0)
        // Sensor 1 is byte 1, NOT byte 0 — reading the support byte would give 0x01 → −39 °C, an
        // alarming wrong number. Pin that the right field is read.
        assertEquals(-39.0, VendoredSaeScaling.temperatureCelsius(0x01), 0.0)
    }

    @Test
    fun `demand and actual torque are signed, unlike the OBD-43 full-scale percentages`() {
        // The /255 trap doesn't apply here — this is A - 125, not A * 100 / 255 — but the sign
        // is the equivalent footgun: a raw byte below 125 must not be clamped to zero.
        assertEquals(-125.0, PidRegistry.demandTorque.definition.parse(byteArrayOf(0x00)), 0.0)
        assertEquals(-125.0, PidRegistry.actualTorque.definition.parse(byteArrayOf(0x00)), 0.0)
        assertEquals(130.0, PidRegistry.demandTorque.definition.parse(byteArrayOf(0xFF.toByte())), 0.0)
    }
}
