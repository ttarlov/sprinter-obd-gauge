package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.PollPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PollScheduleTest {
    private val fastThenSlow =
        listOf(
            PolledPid.Standard(PidRegistry.rpm), // FAST
            PolledPid.Standard(PidRegistry.map), // FAST
            PolledPid.Standard(PidRegistry.coolant), // SLOW
            PolledPid.Standard(PidRegistry.baro), // SLOW
            PolledPid.Manufacturer(MercedesPidRegistry.transTemp), // SLOW
        )

    @Test
    fun `cycle zero polls everything so boost is available immediately`() {
        assertEquals(ids(fastThenSlow), ids(members(cycle = 0)))
    }

    @Test
    fun `intermediate cycles poll only FAST channels`() {
        for (cycle in 1..4) {
            assertEquals("cycle $cycle", listOf("rpm", "map"), ids(members(cycle)))
        }
    }

    @Test
    fun `SLOW channels return on every Nth cycle`() {
        assertEquals(ids(fastThenSlow), ids(members(cycle = 5)))
        assertEquals(listOf("rpm", "map"), ids(members(cycle = 6)))
        assertEquals(ids(fastThenSlow), ids(members(cycle = 10)))
    }

    @Test
    fun `the full cadence over twelve cycles`() {
        val cadence = (0..11).map { cycle -> members(cycle).size }

        assertEquals(listOf(5, 2, 2, 2, 2, 5, 2, 2, 2, 2, 5, 2), cadence)
    }

    @Test
    fun `input order is preserved, so the command sequence is predictable`() {
        val reordered = fastThenSlow.reversed()

        assertEquals(ids(reordered), ids(PollSchedule.cycleMembers(reordered, cycle = 0, slowEveryNCycles = 5)))
    }

    @Test
    fun `a divisor of one polls everything every cycle`() {
        for (cycle in 0..3) {
            assertEquals(ids(fastThenSlow), ids(PollSchedule.cycleMembers(fastThenSlow, cycle, slowEveryNCycles = 1)))
        }
    }

    @Test
    fun `the cadence is configurable`() {
        assertTrue(PollSchedule.includesSlow(cycle = 3, slowEveryNCycles = 3))
        assertFalse(PollSchedule.includesSlow(cycle = 3, slowEveryNCycles = 5))
        assertTrue(PollSchedule.includesSlow(cycle = 0, slowEveryNCycles = 99))
    }

    @Test
    fun `a zero or negative divisor is a configuration error, not a silent coercion`() {
        assertThrows { PollSchedule.includesSlow(cycle = 0, slowEveryNCycles = 0) }
        assertThrows { PollSchedule.includesSlow(cycle = 0, slowEveryNCycles = -1) }
        assertThrows { PollSchedule.includesSlow(cycle = -1, slowEveryNCycles = 5) }
        assertThrows { PollConfig(slowEveryNCycles = 0) }
        assertThrows { PollConfig(cycleInterval = (-1).milliseconds) }
    }

    @Test
    fun `an empty PID set schedules nothing`() {
        assertEquals(emptyList<PolledPid>(), PollSchedule.cycleMembers(emptyList(), cycle = 0, slowEveryNCycles = 5))
    }

    @Test
    fun `staleness windows differ per priority, since SLOW channels refresh N times less often`() {
        val config = PollConfig(fastStaleAfter = 2.seconds, slowStaleAfter = 15.seconds)

        assertEquals(2.seconds, config.staleAfter(PollPriority.FAST))
        assertEquals(15.seconds, config.staleAfter(PollPriority.SLOW))
    }

    private fun members(cycle: Int): List<PolledPid> =
        PollSchedule.cycleMembers(fastThenSlow, cycle, slowEveryNCycles = 5)

    private fun ids(pids: List<PolledPid>): List<String> = pids.map { it.definition.id }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }
}
