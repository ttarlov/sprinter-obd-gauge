package com.revel.obdgauge.app.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * OBD-79's boundary coverage for [MaintenanceStatus.forItem] — pure, no Robolectric/Compose
 * needed. Naming mirrors `issues/OBD-79.md`'s Testing section: "miles-only / time-only /
 * whichever-first / never-serviced / overdue / disabled."
 */
class MaintenanceStatusTest {
    private val today = LocalDate.of(2026, 9, 4)

    private fun item(
        intervalMiles: Int? = null,
        intervalMonths: Int? = null,
        enabled: Boolean = true,
    ) = ServiceItem(
        name = "Test item",
        category = MaintenanceCategory.ROUTINE,
        intervalMiles = intervalMiles,
        intervalMonths = intervalMonths,
        enabled = enabled,
    )

    private fun record(
        performedDate: LocalDate,
        odometerMiles: Int,
    ) = MaintenanceRecord(
        serviceItemId = 1,
        performedDateEpochDay = performedDate.toEpochDay(),
        odometerMiles = odometerMiles,
    )

    @Test
    fun `disabled item is DISABLED regardless of everything else`() {
        val status =
            MaintenanceStatus.forItem(
                item = item(intervalMiles = 5_000, enabled = false),
                latestRecord = record(today, odometerMiles = 0),
                currentOdometerMiles = 100_000,
                today = today,
            )

        assertEquals(MaintenanceStatusLevel.DISABLED, status.level)
        assertEquals(MaintenanceTrigger.NONE, status.drivingTrigger)
    }

    @Test
    fun `never-serviced item is NEVER_SERVICED even with intervals set`() {
        val status =
            MaintenanceStatus.forItem(
                item = item(intervalMiles = 5_000, intervalMonths = 6),
                latestRecord = null,
                currentOdometerMiles = 12_000,
                today = today,
            )

        assertEquals(MaintenanceStatusLevel.NEVER_SERVICED, status.level)
        assertEquals(MaintenanceTrigger.NONE, status.drivingTrigger)
    }

    @Test
    fun `never-serviced watch item with no interval is still NEVER_SERVICED, not OK`() {
        val status =
            MaintenanceStatus.forItem(
                item = item(),
                latestRecord = null,
                currentOdometerMiles = 12_000,
                today = today,
            )

        assertEquals(MaintenanceStatusLevel.NEVER_SERVICED, status.level)
    }

    @Test
    fun `logged watch item with no interval at all is OK with no driving trigger`() {
        val status =
            MaintenanceStatus.forItem(
                item = item(),
                latestRecord = record(today.minusYears(1), odometerMiles = 50_000),
                currentOdometerMiles = 90_000,
                today = today,
            )

        assertEquals(MaintenanceStatusLevel.OK, status.level)
        assertEquals(MaintenanceTrigger.NONE, status.drivingTrigger)
        assertEquals(null, status.milesUntil)
        assertEquals(null, status.daysUntil)
    }

    @Test
    fun `miles-only item classifies OK, DUE_SOON, and OVERDUE purely off the mileage trigger`() {
        val serviced = record(today.minusMonths(1), odometerMiles = 90_000)

        val ok = MaintenanceStatus.forItem(item(intervalMiles = 5_000), serviced, currentOdometerMiles = 90_000, today)
        assertEquals(MaintenanceStatusLevel.OK, ok.level)
        assertEquals(MaintenanceTrigger.MILES, ok.drivingTrigger)
        assertEquals(5_000, ok.milesUntil)
        assertEquals(null, ok.daysUntil)

        val dueSoon =
            MaintenanceStatus.forItem(item(intervalMiles = 5_000), serviced, currentOdometerMiles = 94_800, today)
        assertEquals(MaintenanceStatusLevel.DUE_SOON, dueSoon.level)
        assertEquals(200, dueSoon.milesUntil)

        val overdue =
            MaintenanceStatus.forItem(item(intervalMiles = 5_000), serviced, currentOdometerMiles = 95_500, today)
        assertEquals(MaintenanceStatusLevel.OVERDUE, overdue.level)
        assertEquals(-500, overdue.milesUntil)
    }

    @Test
    fun `time-only item classifies OK, DUE_SOON, and OVERDUE purely off the time trigger`() {
        val servicedRecently = record(today.minusMonths(11).minusDays(20), odometerMiles = 80_000)

        val ok =
            MaintenanceStatus.forItem(
                item(intervalMonths = 24),
                record(today.minusMonths(1), odometerMiles = 80_000),
                currentOdometerMiles = 80_500,
                today,
            )
        assertEquals(MaintenanceStatusLevel.OK, ok.level)
        assertEquals(MaintenanceTrigger.TIME, ok.drivingTrigger)
        assertEquals(null, ok.milesUntil)

        val dueSoon =
            MaintenanceStatus.forItem(item(intervalMonths = 12), servicedRecently, currentOdometerMiles = 80_500, today)
        assertEquals(MaintenanceStatusLevel.DUE_SOON, dueSoon.level)

        val overdue =
            MaintenanceStatus.forItem(
                item(intervalMonths = 12),
                record(today.minusYears(2), odometerMiles = 70_000),
                currentOdometerMiles = 80_500,
                today,
            )
        assertEquals(MaintenanceStatusLevel.OVERDUE, overdue.level)
        assertEquals(MaintenanceTrigger.TIME, overdue.drivingTrigger)
    }

    @Test
    fun `whichever-first - the more urgent of miles and time drives the overall level and trigger`() {
        // Miles say comfortably OK (9,500 of 10,000 remaining); time says already OVERDUE.
        val item = item(intervalMiles = 10_000, intervalMonths = 12)
        val serviced = record(today.minusYears(2), odometerMiles = 80_000)

        val status = MaintenanceStatus.forItem(item, serviced, currentOdometerMiles = 80_500, today)

        assertEquals(MaintenanceStatusLevel.OVERDUE, status.level)
        assertEquals(MaintenanceTrigger.TIME, status.drivingTrigger)
        assertEquals(9_500, status.milesUntil)
    }

    @Test
    fun `whichever-first - the reverse case, miles overdue while time is fine, is driven by miles`() {
        val item = item(intervalMiles = 5_000, intervalMonths = 24)
        val serviced = record(today.minusMonths(1), odometerMiles = 90_000)

        val status = MaintenanceStatus.forItem(item, serviced, currentOdometerMiles = 96_000, today)

        assertEquals(MaintenanceStatusLevel.OVERDUE, status.level)
        assertEquals(MaintenanceTrigger.MILES, status.drivingTrigger)
    }

    @Test
    fun `a tie in severity between miles and time prefers MILES as the driving trigger`() {
        // Both land DUE_SOON at the same severity, no clear winner by urgency alone — the due
        // date is exactly 20 days out (within the 30-day window) and 300 mi remain (within the
        // 500 mi window), so the deterministic MILES tie-break decides it.
        val item = item(intervalMiles = 5_000, intervalMonths = 12)
        val dueDate = today.plusDays(20)
        val serviced = record(dueDate.minusMonths(12), odometerMiles = 90_000)

        val status = MaintenanceStatus.forItem(item, serviced, currentOdometerMiles = 94_700, today)

        assertEquals(20L, status.daysUntil)
        assertEquals(300, status.milesUntil)
        assertEquals(MaintenanceStatusLevel.DUE_SOON, status.level)
        assertEquals(MaintenanceTrigger.MILES, status.drivingTrigger)
    }
}
