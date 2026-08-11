package com.revel.obdgauge.app.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.revel.obdgauge.app.gauge.DASHBOARD_PIDS_BY_ID
import com.revel.obdgauge.app.gauge.GaugeThresholds
import com.revel.obdgauge.model.MeasurementUnit

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
    return reconcileGaugeOrder(decoded.ifEmpty { AppSettings().gaugeOrder })
}

/**
 * Review round-1 M5: a persisted gauge order can go stale relative to the current
 * `DASHBOARD_PIDS` catalog — an id it named might no longer exist (dropped from a future
 * catalog revision), or the catalog might have grown a gauge this order predates. Reconciling
 * on every decode keeps `AppSettings.gaugeOrder` self-healing without any DataStore
 * schema-version machinery: unknown ids are dropped, and any known id missing from the
 * persisted order is appended, visible — never silently hidden by an order that simply
 * predates it. (Threshold overrides have no equivalent reconciliation — see `AppSettings`'
 * KDoc's OBD-25 migration caveat, which stays a real gap unlike this one.)
 */
private fun reconcileGaugeOrder(order: List<GaugeOrderEntry>): List<GaugeOrderEntry> {
    val knownIds = DASHBOARD_PIDS_BY_ID.keys
    val known = order.filter { it.id in knownIds }
    val presentIds = known.map { it.id }.toSet()
    val missing = knownIds.filterNot { it in presentIds }.map { id -> GaugeOrderEntry(id) }
    return known + missing
}

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
