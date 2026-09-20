package com.revel.obdgauge.app.datasource

import com.revel.obdgauge.app.gauge.INSTANT_MPG_PID_ID
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import com.revel.obdgauge.protocol.ProtocolPidIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * OBD-87's instant-fuel-economy seam: the **outermost** prod decorator, computing
 * `mpg = corrected_speed_mph / (fuelRate_Lph / GALLONS_TO_LITERS)` from two channels that only
 * coexist at this point in the chain.
 *
 * ## Why here, and not `RealVehicleDataSource` (where boost is computed)
 * Boost is computed at the innermost layer because both its inputs (MAP, baro) are absolute
 * pressures in the protocol's own kPa — no unit conversion or GPS correction ever touches them.
 * Speed is different: `:core:protocol` parses it in raw ECU km/h, [DisplayUnitDataSource]
 * re-expresses it in the mph [com.revel.obdgauge.app.gauge.GAUGE_CATALOG] declares, and
 * [SpeedCorrectionDataSource] then multiplies it by the GPS-learned correction factor. The
 * **GPS-corrected mph** value — the only speed worth dividing fuel rate into for a dash-mounted
 * MPG readout — exists nowhere before [SpeedCorrectionDataSource]'s own output. So this class
 * wraps *that*, becoming the new outermost link the `prod` DI chain returns
 * (`DataSourceModule.provideVehicleDataSource`).
 *
 * ## Guards — emit ABSENCE, never a fabricated number (mirrors `ComputedChannels`' discipline)
 * - [speed] or [fuelRate] absent → no raw sample this tick.
 * - `fuelRate.value <= 0` (decel fuel-cut, or engine off) → no raw sample. A fuel-cut instant is
 *   momentary — the smoothing window below is what keeps the tile from blanking every time the
 *   van coasts, by carrying the last valid samples until they age out.
 * - `speed.value == 0` (idle/stopped, fuel still flowing) → MPG **is** 0, a well-defined answer,
 *   not an absence.
 * - A non-finite result (backstop; the guards above should make this unreachable) → no sample.
 *
 * ## Smoothing
 * Raw instant MPG is a mathematically correct but visually useless number — it pins high the
 * instant fuel-cut zeroes the denominator's numerator effect and craters to 0 at every stop —
 * so [smoother] folds each valid raw sample into a short rolling average
 * ([InstantMpgSmoother.WINDOW], ~2-3 s) before it is published. [clock] is the same
 * `Clock.systemDefaultZone()` singleton `DataSourceModule` already threads through
 * `RealVehicleDataSource`/`DashboardViewModel`, sampled once per upstream emission as the
 * window's "now" — deterministic in tests via `Clock.fixed`/an adjustable fake, exactly the
 * seam `WedgeDecision.kt`'s `nowMillis` parameter uses for the same reason.
 *
 * ## Degrades to absence
 * No speed, no fuel rate, or a fuel-cut/absence long enough to empty the window: no `instantMpg`
 * key is injected at all — the tile shows [com.revel.obdgauge.app.gauge.NO_READING_TEXT], the
 * same as boost with no MAF. Every other channel passes through completely untouched.
 * [start]/[stop]/[connection] are pass-throughs, so the frozen `VehicleDataSource` lifecycle
 * contract stays the delegate's.
 */
class InstantMpgDataSource(
    private val delegate: VehicleDataSource,
    private val clock: Clock,
    scope: CoroutineScope,
) : VehicleDataSource {
    private val smoother = InstantMpgSmoother()

    override val readings: StateFlow<Map<String, Reading>> =
        delegate.readings
            .map(::withInstantMpg)
            .stateIn(scope, SharingStarted.Eagerly, withInstantMpg(delegate.readings.value))

    override val connection: StateFlow<LinkState> get() = delegate.connection

    override fun start(pids: List<PidDefinition>) = delegate.start(pids)

    override fun stop() = delegate.stop()

    private fun withInstantMpg(readings: Map<String, Reading>): Map<String, Reading> {
        val raw = InstantMpgCompute.compute(readings[SPEED_ID], readings[FUEL_RATE_ID])
        val smoothed = smoother.accept(raw, clock.instant())
        return if (smoothed == null) readings else readings + (INSTANT_MPG_PID_ID to smoothed)
    }

    private companion object {
        val SPEED_ID: String = ProtocolPidIds.SPEED
        val FUEL_RATE_ID: String = ProtocolPidIds.FUEL_RATE
    }
}

/**
 * The pure `mpg = speed_mph / (fuelRate_Lph / GALLONS_TO_LITERS)` computation plus its guards —
 * kept as a standalone object (not a private method) so it is unit-testable with zero coroutine
 * or `Clock` scaffolding, the same split `ComputedChannels.boost` uses in `:core:protocol`.
 */
object InstantMpgCompute {
    /** 1 US gallon = 3.785411784 litres, exactly (the legal US definition). */
    const val LITERS_PER_US_GALLON: Double = 3.785411784

    /**
     * @return the raw (unsmoothed) instant-MPG [Reading], or `null` per the guards documented on
     *   [InstantMpgDataSource].
     *
     * The two early returns ARE the safety contract — each degenerate case (absent/fuel-cut
     * inputs, then the non-finite backstop) is its own named, auditable guard that collapses to
     * null before continuing, the same shape `ComputedChannels.manifoldPressureFromAirflow` uses
     * in `:core:protocol` (hence the same `ReturnCount` suppression).
     */
    @Suppress("ReturnCount")
    fun compute(
        speed: Reading?,
        fuelRate: Reading?,
    ): Reading? {
        if (speed == null || fuelRate == null || fuelRate.value <= 0.0) {
            return null
        }
        val timestamp = minOf(speed.timestamp, fuelRate.timestamp)
        val stale = speed.stale || fuelRate.stale
        val mpg =
            if (speed.value == 0.0) {
                0.0
            } else {
                speed.value / (fuelRate.value / LITERS_PER_US_GALLON)
            }
        if (!mpg.isFinite()) {
            return null
        }
        return Reading(id = INSTANT_MPG_PID_ID, value = mpg, timestamp = timestamp, stale = stale)
    }
}

/**
 * A ~2-3 s time-windowed rolling average over successive [InstantMpgCompute.compute] outputs.
 *
 * Stateful (a small ring of recent samples) but deterministic: every call takes [now] explicitly
 * rather than reading a wall clock itself, so a test drives the window purely by choosing
 * [Instant]s — no coroutine, no real or virtual-time scheduler, exactly like `WedgeInputs`'
 * `nowMillis` parameter in `WedgeDecision.kt`.
 *
 * @param window how far back from [now] a sample is still averaged in; the default is the
 *   midpoint of the issue's "~2-3 s" spec.
 */
class InstantMpgSmoother(
    private val window: Duration = WINDOW,
) {
    private val samples = ArrayDeque<Sample>()

    /**
     * Folds [raw] into the window (skipped if `null` — a fuel-cut/absence tick does not reset
     * the average, it just contributes nothing new) and evicts anything older than [window]
     * relative to [now], then returns the average of what remains, or `null` if nothing is left
     * in-window — the "no valid samples" absence case.
     *
     * A repeat call with the exact same [raw] timestamp (the `stateIn` seed value replaying the
     * upstream flow's current value into the collector on subscribe — see
     * [InstantMpgDataSource.readings]) replaces rather than double-counts that sample, so the
     * window's sample count reflects distinct upstream ticks, not how many times this function
     * happened to be invoked for the same one.
     */
    fun accept(
        raw: Reading?,
        now: Instant,
    ): Reading? {
        if (raw != null) {
            if (samples.lastOrNull()?.timestamp == raw.timestamp) {
                samples.removeLast()
            }
            samples.addLast(Sample(raw.timestamp, raw.value, raw.stale))
        }
        while (samples.isNotEmpty() && Duration.between(samples.first().timestamp, now) > window) {
            samples.removeFirst()
        }
        if (samples.isEmpty()) {
            return null
        }
        val average = samples.sumOf { it.value } / samples.size
        val stale = samples.any { it.stale }
        return Reading(id = INSTANT_MPG_PID_ID, value = average, timestamp = now, stale = stale)
    }

    private data class Sample(
        val timestamp: Instant,
        val value: Double,
        val stale: Boolean,
    )

    companion object {
        val WINDOW: Duration = Duration.ofMillis(WINDOW_MILLIS)
        private const val WINDOW_MILLIS = 2_500L
    }
}
