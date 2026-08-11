package com.revel.obdgauge.app.sparkline

import com.revel.obdgauge.model.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `onReadings` is fed one entry per `DashboardViewModel.uiState`'s three-way `combine()` tick —
 * which fires on a settings edit or connection-state change just as much as on an actual new
 * reading (review round-1 M4). These tests pin the resulting dedup/trim behavior directly on
 * [SparklineHistoryHolder], one level above [SparklineBuffer]'s own dedup unit test.
 */
class SparklineHistoryHolderTest {
    @Test
    fun `a fresh reading is appended to its id's flow`() {
        val holder = SparklineHistoryHolder(listOf(ID))
        val now = Instant.EPOCH

        holder.onReadings(mapOf(ID to reading(190.0, now)), now)

        assertEquals(listOf(190.0), holder.flowFor(ID).value.map { it.value })
    }

    @Test
    fun `a stale reading only trims, it is never appended`() {
        val holder = SparklineHistoryHolder(listOf(ID))
        val t0 = Instant.EPOCH
        holder.onReadings(mapOf(ID to reading(190.0, t0)), t0)

        val t1 = t0.plusSeconds(1)
        holder.onReadings(mapOf(ID to reading(999.0, t1, stale = true)), t1)

        assertEquals(listOf(190.0), holder.flowFor(ID).value.map { it.value })
    }

    @Test
    fun `re-feeding the identical reading (e_g_ a settings-only combine tick) adds no new point`() {
        val holder = SparklineHistoryHolder(listOf(ID))
        val now = Instant.EPOCH
        val sameReading = reading(190.0, now)

        holder.onReadings(mapOf(ID to sameReading), now)
        // Simulates the 3-way combine firing again with unchanged readings (a settings edit or
        // connection transition), the exact scenario review round-1 M4 flagged.
        holder.onReadings(mapOf(ID to sameReading), now)
        holder.onReadings(mapOf(ID to sameReading), now)

        assertEquals(1, holder.flowFor(ID).value.size)
    }

    @Test
    fun `a missing id is still trimmed against the emission's now`() {
        val holder = SparklineHistoryHolder(listOf(ID))
        val t0 = Instant.EPOCH
        holder.onReadings(mapOf(ID to reading(190.0, t0)), t0)

        // Past the 5-minute window, with no reading for ID at all in this emission.
        val muchLater = t0.plusSeconds(301)
        holder.onReadings(emptyMap(), muchLater)

        assertTrue(holder.flowFor(ID).value.isEmpty())
    }

    @Test
    fun `distinct new timestamps still accumulate normally`() {
        val holder = SparklineHistoryHolder(listOf(ID))
        val t0 = Instant.EPOCH

        holder.onReadings(mapOf(ID to reading(190.0, t0)), t0)
        val t1 = t0.plusMillis(250)
        holder.onReadings(mapOf(ID to reading(191.0, t1)), t1)

        assertEquals(listOf(190.0, 191.0), holder.flowFor(ID).value.map { it.value })
    }

    private fun reading(
        value: Double,
        timestamp: Instant,
        stale: Boolean = false,
    ) = Reading(id = ID, value = value, timestamp = timestamp, stale = stale)

    private companion object {
        const val ID = "coolant"
    }
}
