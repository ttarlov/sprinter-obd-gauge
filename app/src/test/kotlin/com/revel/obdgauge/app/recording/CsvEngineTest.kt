package com.revel.obdgauge.app.recording

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority
import com.revel.obdgauge.model.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Plain-JUnit tests for `CsvEngine.kt` — no Robolectric, no Android types anywhere in scope. */
class CsvEngineTest {
    private val zone = ZoneOffset.of("-06:00")
    private val startedAt = Instant.parse("2026-08-19T04:07:58Z") // 2026-08-18T22:07:58-06:00
    private val coolant =
        PidDefinition(
            id = "coolant",
            label = "Coolant",
            unit = MeasurementUnit.FAHRENHEIT,
            request = ObdRequest.StandardPid(mode = 1, pid = 5),
            parse = { 0.0 },
            pollPriority = PollPriority.SLOW,
        )
    private val rpm =
        PidDefinition(
            id = "rpm",
            label = "RPM",
            unit = MeasurementUnit.RPM,
            request = ObdRequest.StandardPid(mode = 1, pid = 0x0C),
            parse = { 0.0 },
            pollPriority = PollPriority.FAST,
        )
    private val columns = listOf("coolant", "rpm", "missingPid")

    @Test
    fun `csvRow formats timestamp, elapsed_ms, and one cell per column in order`() {
        val now = startedAt.plusSeconds(3)
        val readings =
            mapOf(
                "coolant" to Reading("coolant", 190.0, now, stale = false),
                "rpm" to Reading("rpm", 812.0, now, stale = false),
            )

        val row = csvRow(readings, columns, now, startedAt, zone)

        assertEquals("2026-08-18T22:08:01-06:00,3000,190.00,812.00,", row)
    }

    @Test
    fun `a column absent from readings blanks its cell rather than defaulting to zero`() {
        val readings = mapOf("coolant" to Reading("coolant", 190.0, startedAt, stale = false))

        val row = csvRow(readings, columns, startedAt, startedAt, zone)

        // rpm and missingPid both blank — only coolant answered this tick.
        assertEquals("2026-08-18T22:07:58-06:00,0,190.00,,", row)
    }

    @Test
    fun `a stale reading still contributes its last known value, not a blank`() {
        val readings = mapOf("coolant" to Reading("coolant", 190.0, startedAt, stale = true))

        val row = csvRow(readings, listOf("coolant"), startedAt, startedAt, zone)

        assertEquals("2026-08-18T22:07:58-06:00,0,190.00", row)
    }

    // Round-4 review: on-device values had ugly full-precision tails (e.g. 93.33333333333333);
    // rounded to a fixed 2 decimals for a clean analysis-ready CSV. Locale.US pinned (a
    // comma-decimal locale would silently corrupt every cell's delimiter).
    @Test
    fun `values are formatted to a fixed two decimal places, not full float precision`() {
        val readings = mapOf("coolant" to Reading("coolant", 93.33333333333333, startedAt, stale = false))

        val row = csvRow(readings, listOf("coolant"), startedAt, startedAt, zone)

        assertEquals("2026-08-18T22:07:58-06:00,0,93.33", row)
    }

    @Test
    fun `a whole-number value still gets two decimal places, for consistent column width`() {
        val readings = mapOf("coolant" to Reading("coolant", 225.0, startedAt, stale = false))

        val row = csvRow(readings, listOf("coolant"), startedAt, startedAt, zone)

        assertEquals("2026-08-18T22:07:58-06:00,0,225.00", row)
    }

    @Test
    fun `elapsed_ms is measured from startedAt, not zero every tick`() {
        val now = startedAt.plusSeconds(4)

        val row = csvRow(emptyMap(), emptyList(), now, startedAt, zone)

        assertEquals("2026-08-18T22:08:02-06:00,4000", row)
    }

    @Test
    fun `header lines include the format marker, started time, app line, sample rate, legend, and column row`() {
        val lines =
            csvHeaderLines(listOf("coolant", "rpm"), listOf(coolant, rpm), startedAt, "0.1.0", "demo", "main", zone)

        assertEquals("# sprinter-obd-gauge log v1", lines[0])
        assertEquals("# started: 2026-08-18T22:07:58-06:00", lines[1])
        assertEquals("# app: 0.1.0 (flavor=demo, channel=main)", lines[2])
        assertEquals("# sample_hz: 1", lines[3])
        assertEquals("# columns: coolant=Coolant(°F), rpm=RPM(RPM)", lines[4])
        assertEquals("timestamp,elapsed_ms,coolant,rpm", lines[5])
    }

    @Test
    fun `a column with no matching PidDefinition legends by its bare id`() {
        val lines = csvHeaderLines(listOf("mystery"), emptyList(), startedAt, "0.1.0", "demo", "main", zone)

        assertTrue(lines[4].endsWith("mystery"))
    }

    // ---- Round-4 review (device evidence): the legend must match the VALUE's actual unit ----

    @Test
    fun `a converted gauge channel legends by GAUGE_CATALOG's display unit, not the raw PidCatalog unit`() {
        // Simulates prod's real mismatch: PidCatalog.definitions declares coolant in CELSIUS (the
        // protocol's natural SI unit), but the readings this recorder actually reads have already
        // been converted to GAUGE_CATALOG's declared FAHRENHEIT by DisplayUnitDataSource — device
        // evidence was coolant logged 186.8 (a °F value) labeled "(°C)".
        val rawCoolant =
            PidDefinition(
                id = PidIds.COOLANT,
                label = "Coolant Temp",
                unit = MeasurementUnit.CELSIUS,
                request = ObdRequest.StandardPid(mode = 1, pid = 5),
                parse = { 0.0 },
                pollPriority = PollPriority.SLOW,
            )

        val lines = csvHeaderLines(listOf(PidIds.COOLANT), listOf(rawCoolant), startedAt, "0.1.0", "prod", "main", zone)

        assertTrue("expected the legend to show the DISPLAY unit (°F): ${lines[4]}", lines[4].contains("(°F)"))
        assertTrue("must not show the raw PidCatalog unit (°C): ${lines[4]}", !lines[4].contains("(°C)"))
    }

    @Test
    fun `a channel outside GAUGE_CATALOG legends by its own declared unit, unconverted`() {
        // baro isn't one of the six gauge-catalog channels DisplayUnitDataSource converts — it
        // passes through unchanged, so the legend should too.
        val baro =
            PidDefinition(
                id = PidIds.BARO,
                label = "Baro",
                unit = MeasurementUnit.KPA,
                request = ObdRequest.StandardPid(mode = 1, pid = 0x33),
                parse = { 0.0 },
                pollPriority = PollPriority.SLOW,
            )

        val lines = csvHeaderLines(listOf(PidIds.BARO), listOf(baro), startedAt, "0.1.0", "prod", "main", zone)

        assertTrue(lines[4].contains("(kPa)"))
    }

    @Test
    fun `buildFilename matches the issue's example shape`() {
        val filename = buildFilename(startedAt, zone)

        assertEquals("obdlog_2026-08-18_220758.csv", filename)
    }

    @Test
    fun `pollUnion keeps base order first and appends only extras not already in base`() {
        val union = pollUnion(base = listOf(coolant, rpm), extra = listOf(rpm, coolant))

        assertEquals(listOf(coolant, rpm), union)
    }

    @Test
    fun `pollUnion appends new extras after the base set, deduplicated`() {
        val boost =
            PidDefinition(
                id = "boost",
                label = "Boost",
                unit = MeasurementUnit.PSI,
                request = ObdRequest.StandardPid(mode = 1, pid = 0x0B),
                parse = { 0.0 },
                pollPriority = PollPriority.FAST,
            )

        val union = pollUnion(base = listOf(coolant), extra = listOf(rpm, boost, boost))

        assertEquals(listOf(coolant, rpm, boost), union)
    }

    @Test
    fun `pollUnion with an empty extra set is exactly the base set`() {
        assertEquals(listOf(coolant, rpm), pollUnion(listOf(coolant, rpm), emptyList()))
    }
}
