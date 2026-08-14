package com.revel.obdgauge.app.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.revel.obdgauge.app.gauge.DASHBOARD_PIDS_BY_ID
import com.revel.obdgauge.app.gauge.GAUGE_CATALOG_BY_ID
import com.revel.obdgauge.app.gauge.GaugeThresholds
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidDefinition

// Manual (no serialization-library dependency) Preferences DataStore encoding for
// [AppSettings] — the data model is a handful of small fields, stable enough that a
// JSON/protobuf schema would be more machinery than the payload warrants. Pure functions,
// unit-tested directly against `mutablePreferencesOf()`/an edited `MutablePreferences`
// (`SettingsCodecTest`) with no DataStore/file IO involved.
//
// Anything malformed or missing decodes to that field's `AppSettings` default rather than
// throwing — the same "worst case is a silent reset, never a crash" discipline `:core:ble`'s
// DataStore usage follows (see `DataStoreSettingsRepository`'s KDoc).

private val KEY_GAUGE_ORDER = stringPreferencesKey("gauge_order")
private val KEY_THRESHOLD_OVERRIDES = stringPreferencesKey("threshold_overrides")
private val KEY_TEMPERATURE_UNIT = stringPreferencesKey("temperature_unit")
private val KEY_PRESSURE_UNIT = stringPreferencesKey("pressure_unit")
private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
private val KEY_POLL_RATE = stringPreferencesKey("poll_rate")
private val KEY_SPEED_CORRECTION_FACTOR = doublePreferencesKey("speed_correction_factor")

private const val ENTRY_SEPARATOR = ";"
private const val FIELD_SEPARATOR = ":"
private const val THRESHOLD_SEPARATOR = "|"
private const val NULL_SENTINEL = "~"
private const val GAUGE_ORDER_FIELD_COUNT = 2
private const val THRESHOLD_FIELD_COUNT = 5

/** Reads [preferences] into an [AppSettings], falling back to per-field defaults on the way. */
fun decodeAppSettings(preferences: Preferences): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        gaugeOrder = preferences[KEY_GAUGE_ORDER]?.let(::decodeGaugeOrder) ?: defaults.gaugeOrder,
        thresholdOverrides =
            preferences[KEY_THRESHOLD_OVERRIDES]?.let(::decodeThresholdOverrides) ?: defaults.thresholdOverrides,
        units =
            UnitPreferences(
                temperatureUnit =
                    preferences[KEY_TEMPERATURE_UNIT]?.let(
                        ::decodeUnit,
                    ) ?: defaults.units.temperatureUnit,
                pressureUnit = preferences[KEY_PRESSURE_UNIT]?.let(::decodeUnit) ?: defaults.units.pressureUnit,
            ),
        keepScreenOn = preferences[KEY_KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
        pollRate = preferences[KEY_POLL_RATE]?.let(::decodePollRate) ?: defaults.pollRate,
        // Missing/malformed (NaN/infinite) → the 1.0 default, matching the file's "worst case is
        // a silent reset, never a crash" discipline.
        speedCorrectionFactor =
            preferences[KEY_SPEED_CORRECTION_FACTOR]?.takeIf { it.isFinite() }
                ?: defaults.speedCorrectionFactor,
    )
}

/** Writes [settings] into [preferences] (an in-progress `dataStore.edit {}` transaction). */
fun encodeAppSettings(
    settings: AppSettings,
    preferences: MutablePreferences,
) {
    preferences[KEY_GAUGE_ORDER] = encodeGaugeOrder(settings.gaugeOrder)
    preferences[KEY_THRESHOLD_OVERRIDES] = encodeThresholdOverrides(settings.thresholdOverrides)
    preferences[KEY_TEMPERATURE_UNIT] = settings.units.temperatureUnit.name
    preferences[KEY_PRESSURE_UNIT] = settings.units.pressureUnit.name
    preferences[KEY_KEEP_SCREEN_ON] = settings.keepScreenOn
    preferences[KEY_POLL_RATE] = settings.pollRate.name
    preferences[KEY_SPEED_CORRECTION_FACTOR] = settings.speedCorrectionFactor
}

private fun encodeGaugeOrder(order: List<GaugeOrderEntry>): String =
    order.joinToString(ENTRY_SEPARATOR) { "${it.id}$FIELD_SEPARATOR${if (it.visible) "1" else "0"}" }

private fun decodeGaugeOrder(raw: String): List<GaugeOrderEntry> {
    val decoded =
        raw.split(ENTRY_SEPARATOR).filter { it.isNotBlank() }.mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != GAUGE_ORDER_FIELD_COUNT) return@mapNotNull null
            GaugeOrderEntry(id = parts[0], visible = parts[1] == "1")
        }
    // GAUGE_CATALOG_BY_ID (not DASHBOARD_PIDS_BY_ID): a persisted swap to a non-core id (e.g.
    // "rpm") must survive reconciliation, not be dropped as "unknown" — see reconcileGaugeOrder's
    // KDoc for the full story.
    // ifEmpty runs AFTER reconciliation (round-2 MINOR): a persisted order whose ids are ALL
    // unknown must fall back to defaults, not decode to a zero-tile dashboard with nothing to
    // long-press — that state is unrecoverable short of clearing app data.
    return reconcileGaugeOrder(decoded, GAUGE_CATALOG_BY_ID).ifEmpty { AppSettings().gaugeOrder }
}

/**
 * Review round-1 M5 (OBD-21): a persisted gauge order can go stale relative to the current
 * catalog — an id it named might no longer exist (dropped from a future catalog revision).
 * Dropping unknown ids on every decode keeps `AppSettings.gaugeOrder` self-healing without any
 * DataStore schema-version machinery. (Threshold overrides have no equivalent reconciliation —
 * see `AppSettings`' KDoc's OBD-25 migration caveat, which stays a real gap unlike this one.)
 *
 * [catalog] defaults to [DASHBOARD_PIDS_BY_ID] but the real call site ([decodeGaugeOrder]) always
 * passes [GAUGE_CATALOG_BY_ID] explicitly — the default exists so catalog *drift* is directly
 * testable (a synthetic bigger-than-today catalog, without touching the real `DASHBOARD_PIDS`)
 * rather than so production can rely on it.
 *
 * OBD-42 review round-1 M2: this used to *also* auto-backfill any core id missing from the kept
 * list, gated on the kept list being short a slot relative to the core catalog's size — an
 * attempt to tell "genuine catalog drift" apart from "a swap deliberately removed a core id from
 * its slot" by slot count. That still got it wrong: probing with a **larger** catalog (e.g. a
 * hypothetical 5th `DASHBOARD_PIDS` entry, OBD-43's shape) makes a persisted 4-slot swapped order
 * decode to 6 entries, resurrecting the swapped-away gauge as a duplicate — slot count alone
 * can't distinguish "this order predates a catalog that had fewer gauges" from "a swap happened
 * and the catalog *also* grew" (this file's own `SettingsCodecTest` regression case pins it).
 *
 * There is no shape-of-the-list signal that reliably tells those two cases apart, so this no
 * longer tries: **auto-backfill is gone entirely**. `DEFAULT_GAUGE_ORDER` already covers fresh
 * installs (built straight from the current `DASHBOARD_PIDS` at file-load time, never reconciled
 * against a stale persisted order), and a catalog gauge that's new to an *existing* install is
 * discoverable through the swap picker instead (`candidateGaugesFor` in `GaugeCatalog.kt` reads
 * the live catalog directly) — a user opts a new gauge in deliberately, rather than it being
 * silently force-injected as a new visible tile. This is the coherent story for OBD-42 going
 * forward: gauge visibility only ever changes via an explicit user action (Settings' visibility
 * toggle, or the picker), never via decode-time inference.
 */
internal fun reconcileGaugeOrder(
    order: List<GaugeOrderEntry>,
    // No default: the production catalog is GAUGE_CATALOG_BY_ID, and a caller silently taking
    // DASHBOARD_PIDS_BY_ID would drop every persisted rpm swap (round-2 NIT).
    catalog: Map<String, PidDefinition>,
): List<GaugeOrderEntry> = order.filter { it.id in catalog.keys }

private fun encodeThresholdOverrides(overrides: Map<String, GaugeThresholds>): String =
    overrides.entries.joinToString(THRESHOLD_SEPARATOR) { (id, thresholds) ->
        listOf(
            id,
            thresholds.greenMax?.toString() ?: NULL_SENTINEL,
            if (thresholds.greenInclusive) "1" else "0",
            thresholds.redMin?.toString() ?: NULL_SENTINEL,
            if (thresholds.redInclusive) "1" else "0",
        ).joinToString(FIELD_SEPARATOR)
    }

private fun decodeThresholdOverrides(raw: String): Map<String, GaugeThresholds> =
    raw
        .split(THRESHOLD_SEPARATOR)
        .filter { it.isNotBlank() }
        .mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != THRESHOLD_FIELD_COUNT) return@mapNotNull null
            val id = parts[0]
            val thresholds =
                GaugeThresholds(
                    greenMax = parts[1].takeIf { it != NULL_SENTINEL }?.toDoubleOrNull(),
                    greenInclusive = parts[2] == "1",
                    redMin = parts[3].takeIf { it != NULL_SENTINEL }?.toDoubleOrNull(),
                    redInclusive = parts[4] == "1",
                )
            id to thresholds
        }.toMap()

private fun decodeUnit(name: String): MeasurementUnit? = runCatching { MeasurementUnit.valueOf(name) }.getOrNull()

private fun decodePollRate(name: String): PollRate? = runCatching { PollRate.valueOf(name) }.getOrNull()
