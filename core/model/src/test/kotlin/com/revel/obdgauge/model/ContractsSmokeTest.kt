package com.revel.obdgauge.model

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Not a spec test — just proves the module compiles standalone and its test toolchain
 * (JUnit, kotlinx-coroutines-test, Turbine) is wired correctly. Real contract behavior
 * has nothing to assert yet: these are interfaces/data classes with no logic.
 */
class ContractsSmokeTest {
    @Test
    fun `PidDefinition holds its fields and parse is callable`() {
        val rawByte = 40
        val expectedValue = rawByte.toDouble()
        val pid =
            PidDefinition(
                id = "coolant",
                label = "Coolant",
                unit = MeasurementUnit.FAHRENHEIT,
                request = ObdRequest.StandardPid(mode = 1, pid = 0x05),
                parse = { bytes -> bytes.first().toDouble() },
                pollPriority = PollPriority.SLOW,
            )

        assertEquals("coolant", pid.id)
        assertTrue(pid.verified)
        assertEquals(expectedValue, pid.parse(byteArrayOf(rawByte.toByte())), 0.0)
    }

    @Test
    fun `Reading is a plain value holder`() {
        val idleRpm = 780.0
        val now = Instant.now()
        val reading = Reading(id = "rpm", value = idleRpm, timestamp = now, stale = false)

        assertEquals(idleRpm, reading.value, 0.0)
        assertEquals(now, reading.timestamp)
    }

    @Test
    fun `LinkState Error carries a typed LinkError`() {
        val gattFailureCode = 133
        val state: LinkState = LinkState.Error(LinkError.Gatt(code = gattFailureCode))

        assertTrue(state is LinkState.Error)
        val cause = (state as LinkState.Error).cause as LinkError.Gatt
        assertEquals(gattFailureCode, cause.code)
    }

    @Test
    fun `StateFlow of LinkState is observable via Turbine`() =
        runTest {
            val state = MutableStateFlow<LinkState>(LinkState.Disconnected)

            state.test {
                assertEquals(LinkState.Disconnected, awaitItem())
                state.value = LinkState.Scanning
                assertEquals(LinkState.Scanning, awaitItem())
            }
        }
}
