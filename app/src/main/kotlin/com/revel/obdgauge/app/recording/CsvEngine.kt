package com.revel.obdgauge.app.recording

import com.revel.obdgauge.app.gauge.GAUGE_CATALOG_BY_ID
import com.revel.obdgauge.app.gauge.unitSuffix
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.Reading
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// OBD-70's pure CSV-serialization engine: every formatting decision a recording session makes,
// as plain functions over plain values — no coroutines, no file IO, no Android types. Recorder
// is the only caller; keeping the formatting here (not inline in its coroutine) is what makes it
// unit-testable in `app/src/test` with plain JUnit, per this issue's brief.
//
// Every function here takes an explicit ZoneId (defaulting to ZoneId.systemDefault) rather than
// hardcoding UTC or reading the platform zone internally — so a test can pin a fixed zone and
// assert an exact string, while production leaves the default alone and gets the tablet's local
// time in the header/filename, matching the issue's `2026-08-18T22:07:58-06:00` example.

/** `sprinter-obd-gauge log v1` — bumped only if the on-disk shape (columns/header) ever changes. */
private const val LOG_FORMAT_VERSION = "sprinter-obd-gauge log v1"
private const val SAMPLE_HZ = 1
private val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
private val FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
private const val CSV_SEPARATOR = ","

/**
 * One data row: `timestamp,elapsed_ms,<value per column or blank>`. [columns] fixes the id order
 * (decided once at session start, from the injected `loggablePids` — see [Recorder]); a [Reading]
 * present in [readings] contributes its raw [Reading.value] (already display-unit-converted
 * upstream — see `VehicleDataSource`), a [Reading.stale] one still contributes its last known
 * value (the ticker snapshots "latest known," not "fresh only" — see this issue's spec), and a
 * column with NO entry in [readings] at all (a PID this van never answers, e.g. MAP) blanks the
 * cell rather than guessing a zero. Values are fixed to [VALUE_DECIMAL_PLACES] decimals — see
 * [formatCsvValue].
 */
fun csvRow(
    readings: Map<String, Reading>,
    columns: List<String>,
    now: Instant,
    startedAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val timestamp = OffsetDateTime.ofInstant(now, zone).format(TIMESTAMP_FORMATTER)
    val elapsedMs = Duration.between(startedAt, now).toMillis().coerceAtLeast(0)
    val cells = columns.map { id -> readings[id]?.value?.let(::formatCsvValue).orEmpty() }
    return (listOf(timestamp, elapsedMs.toString()) + cells).joinToString(CSV_SEPARATOR)
}

// Round-4 review (device evidence): raw sensor/computed math produces float tails like
// 93.33333333333333 — fine for the app's own display formatting (which already rounds per gauge,
// see GaugeFormatting.kt) but noisy in a CSV meant for spreadsheet/pandas analysis. A plain fixed
// 2-decimal format, always Locale.US (never a comma-decimal locale — same discipline the rest of
// this file already follows for timestamps), is what the review asked for: simple, not clever.
private const val VALUE_DECIMAL_PLACES = 2

private fun formatCsvValue(value: Double): String = String.format(Locale.US, "%.${VALUE_DECIMAL_PLACES}f", value)

/**
 * The `#`-commented self-describing header block plus the plain CSV column-header row, in
 * write-once-at-session-start order. [DateTimeFormatter.ISO_OFFSET_DATE_TIME] (via [csvRow]'s own
 * `startedAt`) is deliberately the same formatter [csvRow] uses for `# started:`, so a reader
 * cross-referencing the comment against the first data row's own timestamp column sees the exact
 * same shape.
 */
@Suppress("LongParameterList") // one field per documented header line — see this issue's spec example.
fun csvHeaderLines(
    columns: List<String>,
    loggablePids: List<PidDefinition>,
    startedAt: Instant,
    versionName: String,
    flavor: String,
    channel: String,
    zone: ZoneId = ZoneId.systemDefault(),
): List<String> {
    val byId = loggablePids.associateBy { it.id }
    val legend = columns.joinToString(", ") { id -> legendEntry(id, byId[id]) }
    val startedFormatted = OffsetDateTime.ofInstant(startedAt, zone).format(TIMESTAMP_FORMATTER)
    return listOf(
        "# $LOG_FORMAT_VERSION",
        "# started: $startedFormatted",
        "# app: $versionName (flavor=$flavor, channel=$channel)",
        "# sample_hz: $SAMPLE_HZ",
        "# columns: $legend",
        (listOf("timestamp", "elapsed_ms") + columns).joinToString(CSV_SEPARATOR),
    )
}

/**
 * Round-4 review (device evidence — coolant logged `186.8` labeled `(°C)`, a Fahrenheit value
 * mislabeled as Celsius, along with oilTemp/transTemp/boost/speed): the legend must name the unit
 * the VALUE actually arrives in, not [pid]'s own raw/wire unit. In `prod`, [loggablePids] is
 * `PidCatalog.definitions` (declared in `:core:protocol`'s natural SI-ish units — CELSIUS, KPA,
 * KMH) but the readings this recorder actually reads come from the top-of-chain injected
 * `VehicleDataSource`, which for the six [GAUGE_CATALOG_BY_ID] channels has already been through
 * `DisplayUnitDataSource` (prod) converting to `:app`'s declared display unit (°F/PSI/mph) —
 * every other channel passes through unconverted. This mirrors that EXACT resolution rule
 * (`GAUGE_CATALOG_BY_ID[id]?.unit`, else the channel's own declared unit) so the legend can never
 * disagree with the data. `demo`'s `loggablePids` (`DASHBOARD_PIDS` + rpm) are the SAME
 * [PidDefinition] instances [GAUGE_CATALOG_BY_ID] holds, so this is a no-op there — the fix only
 * changes `prod`'s six converted channels.
 */
private fun legendEntry(
    id: String,
    pid: PidDefinition?,
): String {
    if (pid == null) return id
    val displayUnit = GAUGE_CATALOG_BY_ID[id]?.unit ?: pid.unit
    val unit = unitSuffix(displayUnit).trim()
    return if (unit.isEmpty()) "$id=${pid.label}" else "$id=${pid.label}($unit)"
}

/**
 * `obdlog_2026-08-18_220758.csv` — the issue's example filename shape, at second resolution
 * (round-1 review, nit 5: the original minute-resolution format let two sessions started in the
 * same minute collide — the second Record tap would silently overwrite the first CSV's header and
 * the index would keep only one row for both. Second resolution still isn't collision-proof
 * against a genuinely programmatic caller, but it's what a human tapping Record needs).
 */
fun buildFilename(
    startedAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = "obdlog_${OffsetDateTime.ofInstant(startedAt, zone).format(FILENAME_FORMATTER)}.csv"

/**
 * The widened poll set a recording session polls: [base] (the dashboard's own 6-gauge
 * [com.revel.obdgauge.app.gauge.GAUGE_CATALOG]) plus every [extra] (the recorder's
 * `loggablePids`) not already present in [base], keeping [base]'s own order first so the
 * dashboard's own channels keep whatever poll priority they already had. Order-preserving and
 * de-duplicated by [PidDefinition.id] — never a `Set`, since [com.revel.obdgauge.model.VehicleDataSource.start]
 * takes an ordered `List`.
 */
fun pollUnion(
    base: List<PidDefinition>,
    extra: List<PidDefinition>,
): List<PidDefinition> {
    val baseIds = base.mapTo(mutableSetOf()) { it.id }
    val extraDeduped = LinkedHashMap<String, PidDefinition>()
    extra.forEach { pid -> if (pid.id !in baseIds) extraDeduped.putIfAbsent(pid.id, pid) }
    return base + extraDeduped.values
}
