package com.revel.obdgauge.app.speed

import com.revel.obdgauge.app.gauge.SPEED_PID_ID
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * OBD-61's app-scoped glue: feeds paired ECU-and-GPS speed observations into the pure
 * [SpeedCalibration] engine, publishes the learned correction [factor], and persists it.
 *
 * ## What flows where
 * - The **raw ECU speed** (km/h, PID `010D`) comes from [source]`.readings[`[SPEED_PID_ID]`]` —
 *   [source] is the RealVehicleDataSource *before* the display-unit seam, so speed is still km/h
 *   here, commensurate with the GPS speed the calibration compares it against.
 * - The **GPS speed** comes from [speedSource]; its m/s is scaled to km/h before comparison.
 * - Acceptance is driven off each **GPS fix** (not the faster ECU poll): one GPS sample →
 *   one [SpeedCalibration.accept] call, paired with the freshest ECU reading. Driving off the
 *   GPS cadence avoids re-weighting a single fix against every intervening ECU poll.
 *
 * ## Persistence discipline
 * The factor is seeded from [SettingsRepository] (the last learned value, or 1.0 on a fresh
 * install) and written back only when it moves beyond [PERSIST_EPSILON], so a steady factor does
 * not churn DataStore on every fix. The settings flow is the single source of the "current
 * persisted" value: our own writes come back through it, which is what closes the loop and stops
 * a just-written factor from being re-persisted.
 *
 * ## Degrade to 1.0
 * Below [SpeedCalibration.MIN_SAMPLES] accepted ratios, [factor] reports the persisted default.
 * No GPS, no permission, or a source with no speed channel all leave the factor at that default,
 * and [com.revel.obdgauge.app.datasource.SpeedCorrectionDataSource] then multiplies by 1.0 — a
 * true no-op.
 */
class SpeedCalibrator(
    private val source: VehicleDataSource,
    private val speedSource: SpeedSource,
    private val settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) {
    private val mutableFactor = MutableStateFlow(DEFAULT_FACTOR)

    /** The learned correction multiplier (true speed = ecu × factor); starts at the default. */
    val factor: StateFlow<Double> = mutableFactor.asStateFlow()

    // @Volatile for cross-thread visibility: the scope runs on Dispatchers.Default (multi-
    // threaded), so the GPS collector's writes to `state` must be visible to the settings
    // collector that reads `state.ratios.size`. Single writer (the GPS collector), so no CAS
    // is needed — visibility is the only requirement. Mirrors [persistedFactor].
    @Volatile
    private var state = SpeedCalibrationState()

    /** The most recent persisted factor — the default fed to [SpeedCalibrationState.factor]. */
    @Volatile
    private var persistedFactor = DEFAULT_FACTOR

    init {
        // Seed and track the persisted factor. While the calibration is still in warm-up (too
        // few samples), the published factor tracks the persisted value directly.
        scope.launch {
            settingsRepository.settings
                .map { it.speedCorrectionFactor }
                .collect { persisted ->
                    persistedFactor = persisted
                    if (state.ratios.size < SpeedCalibration.MIN_SAMPLES) {
                        mutableFactor.value = persisted
                    }
                }
        }

        scope.launch {
            speedSource.samples.collect { sample ->
                if (sample == null) return@collect
                val ecuReading = source.readings.value[SPEED_PID_ID] ?: return@collect
                // A stale reading is a frozen last-known value from a dropped link — never
                // calibrate against it.
                if (ecuReading.stale) return@collect

                state =
                    SpeedCalibration.accept(
                        state = state,
                        ecuSpeedKmh = ecuReading.value,
                        gpsSpeedKmh = sample.speedMps * KMH_PER_MPS,
                        gpsAccuracyMps = sample.accuracyMps,
                    )

                val learned = state.factor(persistedFactor)
                mutableFactor.value = learned
                if (abs(learned - persistedFactor) > PERSIST_EPSILON) {
                    settingsRepository.update { it.copy(speedCorrectionFactor = learned) }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_FACTOR = 1.0
        const val KMH_PER_MPS = 3.6

        /** Below this delta the factor is treated as unchanged — avoids DataStore write churn. */
        const val PERSIST_EPSILON = 0.005
    }
}
