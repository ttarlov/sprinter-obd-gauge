package com.revel.obdgauge.app.speed

import kotlin.math.abs

/**
 * OBD-61's correctness core: the pure, Android-free engine that learns the speedometer
 * correction factor from paired ECU-and-GPS speed samples.
 *
 * The van reads low at speed because of larger-than-stock tires (indicated 60 ≈ true 66). The
 * ECU reports its own idea of vehicle speed (standard PID `010D`); the phone's GPS measures the
 * ground truth. Their ratio — `gps / ecu` — is the multiplier that turns the ECU's number into
 * true speed. This object collects that ratio over many samples and reports the **median** of
 * the accepted ones, so a single glitchy GPS fix cannot move the gauge.
 *
 * ## Everything here is a pure function of immutable state
 * No coroutines, no `Location`, no clock, no `StateFlow` — all of that lives in
 * [SpeedCalibrator] and [GpsSpeedProvider]. This file is exhaustively unit-testable in plain
 * JVM (`SpeedCalibrationTest`), which is the whole point of splitting it out: the arithmetic and
 * the gates are the part that has to be *right*, and they are the part with zero platform
 * dependency.
 *
 * ## Degrade-to-1.0, never crash
 * [SpeedCalibrationState.factor] takes the fallback to return until enough samples land, and the
 * caller passes the persisted/last factor (or `1.0` on a fresh install). No GPS, no permission,
 * or fewer than [MIN_SAMPLES] accepted ratios all resolve to "use the default" — the tile then
 * simply shows the raw ECU speed (× 1.0), which is the correct graceful-degradation behavior.
 */
object SpeedCalibration {
    /**
     * Below this, neither speed is trustworthy for calibration: GPS speed is noisy at walking
     * pace, and a low-speed ratio is dominated by quantization. 40 km/h ≈ 25 mph — comfortably
     * into the range where the tire-size error is what dominates the difference. Both the ECU
     * and the GPS speed must clear this bar.
     */
    const val MIN_SPEED_KMH: Double = 40.0

    /**
     * Reject a GPS sample whose reported horizontal-speed accuracy is worse than this (metres
     * per second). A `null` accuracy (older fixes, or a provider that does not report one) is
     * *allowed* — we cannot judge it, and rejecting every such fix would make the feature
     * dormant on a large fraction of devices.
     */
    const val MAX_ACCURACY_MPS: Double = 2.0

    /**
     * Steadiness gate: if the ECU speed moved more than this between consecutive observations,
     * the vehicle is accelerating or braking and the ECU/GPS latencies are no longer comparable
     * — the instantaneous ratio would be measuring lag, not tire size. Only near-constant-speed
     * cruising contributes to the calibration.
     */
    const val STEADINESS_MAX_DELTA_KMH: Double = 5.0

    /** Discard any ratio below this — a GPS glitch reading far slower than the ECU. */
    const val RATIO_MIN: Double = 0.80

    /** Discard any ratio above this — a GPS glitch reading far faster than the ECU. */
    const val RATIO_MAX: Double = 1.25

    /** Accepted ratios kept in the ring buffer; the median is taken over these. */
    const val CAPACITY: Int = 20

    /**
     * Until this many ratios have been accepted, [SpeedCalibrationState.factor] returns the
     * caller's default rather than a median of too-few samples. A handful of steady-cruise
     * fixes is enough to be robust to one outlier while still converging within a minute or two
     * of highway driving.
     */
    const val MIN_SAMPLES: Int = 5

    /**
     * Fold one paired observation into [state], returning the new state.
     *
     * The sample is *accepted* (its ratio appended to the ring buffer) only if every quality
     * gate passes; otherwise the buffer is unchanged. Either way the returned state records
     * [ecuSpeedKmh] as the latest observed ECU speed, so the next call's steadiness gate has a
     * neighbour to compare against — including after a rejected sample, so a stretch of
     * acceleration cannot permanently wedge the gate (it recovers on the first steady sample
     * once cruising resumes).
     *
     * @param ecuSpeedKmh the raw ECU vehicle speed, km/h (PID `010D`, before any display-unit
     *   conversion or correction).
     * @param gpsSpeedKmh the phone's GPS ground speed, km/h.
     * @param gpsAccuracyMps reported horizontal-speed accuracy in m/s, or `null` if unknown.
     */
    fun accept(
        state: SpeedCalibrationState,
        ecuSpeedKmh: Double,
        gpsSpeedKmh: Double,
        gpsAccuracyMps: Double?,
    ): SpeedCalibrationState {
        val prevEcuSpeedKmh = state.prevEcuSpeedKmh
        // Always remember the latest observed ECU speed, accepted or not — see the KDoc on why
        // the steadiness gate must not deadlock on a rejected run.
        val observed = state.copy(prevEcuSpeedKmh = ecuSpeedKmh)

        // Double division never throws — a sub-floor ecu speed yields a ratio that simply fails
        // the range check below, so the speed gate and the ratio gate can be evaluated together.
        val ratio = gpsSpeedKmh / ecuSpeedKmh
        val accepted =
            // Gate 1: both speeds above the noise floor.
            ecuSpeedKmh >= MIN_SPEED_KMH &&
                gpsSpeedKmh >= MIN_SPEED_KMH &&
                // Gate 2: GPS accuracy known-and-good, or unknown (null is allowed).
                (gpsAccuracyMps == null || gpsAccuracyMps <= MAX_ACCURACY_MPS) &&
                // Gate 3: steadiness — skipped on the first sample, which has no neighbour.
                (prevEcuSpeedKmh == null || abs(ecuSpeedKmh - prevEcuSpeedKmh) <= STEADINESS_MAX_DELTA_KMH) &&
                // Gate 4: ratio within the plausible tire-error band.
                ratio in RATIO_MIN..RATIO_MAX

        return if (accepted) observed.copy(ratios = (observed.ratios + ratio).takeLast(CAPACITY)) else observed
    }
}

/**
 * Immutable calibration state: the bounded ring buffer of recently accepted ratios (oldest
 * first, capped at [SpeedCalibration.CAPACITY]) and the last observed ECU speed used by the
 * steadiness gate. Starts empty, so a fresh install with no history reports the default factor.
 */
data class SpeedCalibrationState(
    val ratios: List<Double> = emptyList(),
    val prevEcuSpeedKmh: Double? = null,
) {
    /**
     * The learned correction factor: the median of the accepted ratios once at least
     * [SpeedCalibration.MIN_SAMPLES] have landed, otherwise [default] (the persisted/last factor,
     * or `1.0`). Median — not mean — so one surviving outlier inside the clamp band cannot drag
     * the factor; an even-sized buffer averages the two middle values.
     */
    fun factor(default: Double): Double {
        if (ratios.size < SpeedCalibration.MIN_SAMPLES) return default
        val sorted = ratios.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
