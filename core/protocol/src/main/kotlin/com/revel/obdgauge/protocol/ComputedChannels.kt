package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading

/**
 * Channels derived from other readings rather than read off the bus.
 *
 * Pure functions over [Reading]s, deliberately: the derivation is the part most worth testing in
 * isolation, and keeping it out of the poll loop means the altitude cases can be asserted without
 * a dongle, a clock, or a coroutine anywhere in sight.
 */
object ComputedChannels {
    /**
     * Turbo boost: **`MAP − baro`, in kPa gauge**, from the two absolute pressures.
     *
     * This is the final subtraction the whole app is shaped around, and it is deliberately kept as
     * its own function so that whether MAP was *measured* (`010B`, absent on this van) or *computed*
     * ([manifoldPressureFromAirflow], the speed-density estimate this van actually uses) makes no
     * difference here: boost is always the difference of two absolute pressures referenced to the
     * air the engine is breathing — correct in Death Valley and on Loveland Pass alike. Every
     * fixed-offset shortcut ("subtract 101.3") is wrong by roughly 20 kPa (3 psi) at 6 000 ft and
     * 32 kPa (4.6 psi) at 10 000 ft, which on a loaded Sprinter is the difference between "normal
     * pull" and "something is wrong".
     *
     * Naturally signed: at idle, manifold pressure sits *below* ambient, so boost is negative —
     * that is vacuum, and it is a real reading, not an error to clamp away. A gauge that floors at
     * zero hides a leaking intake. (The *physical* bound lives one level up, on MAP: see
     * [manifoldPressureFromAirflow], which clamps MAP to a sane range so a sensor glitch cannot
     * drive boost to a 40-psi garbage spike.)
     *
     * **Baro is source-agnostic (OBD-57).** This function neither knows nor cares whether [baro]
     * came from the ECU's own `0133` sensor or, when that is absent/stale, from the phone's
     * barometer — it is just an input [Reading]. The fallback selection is an `:app`/platform
     * concern (OBD-57b), kept out of `:core:protocol` on purpose so this module stays a pure
     * function of its inputs.
     *
     * **Freshness.** The result is only as fresh as its **oldest** input, so [Reading.timestamp]
     * is the earlier of the two and [Reading.stale] is `true` if *either* input is stale. A fresh
     * MAP paired with a baro from ten minutes and 3 000 ft ago would otherwise present as a
     * current number. When either input is missing entirely, there is no boost reading at all.
     *
     * @return the boost [Reading], or `null` if either input is absent.
     */
    fun boost(
        map: Reading?,
        baro: Reading?,
    ): Reading? {
        if (map == null || baro == null) {
            return null
        }
        return Reading(
            id = PidIds.BOOST,
            value = map.value - baro.value,
            timestamp = minOf(map.timestamp, baro.timestamp),
            stale = map.stale || baro.stale,
        )
    }

    /**
     * Estimated turbo boost from **speed density** (OBD-57): compute manifold absolute pressure
     * from airflow, then `boost = MAP − baro`.
     *
     * The one call the dashboard's boost tile is wired to on this van, because direct MAP (`010B`)
     * answers `NO DATA` and no Mercedes charge-pressure DID was ever found. It is a *model*, not a
     * measurement — badged "Est." and `verified = false` until a 🖐 VE-calibration drive — but a
     * rigorously bounded one: every degenerate input collapses to `null` (a typed unavailability
     * upstream) rather than a plausible wrong number, which on the boost gauge is the single
     * failure this whole module exists to prevent.
     *
     * @param maf mass airflow, g/s ([ProtocolPidIds.MAF], PID `0166` sensor A).
     * @param iat intake/charge air temperature, °C ([ProtocolPidIds.IAT_SENSOR], PID `0168`).
     * @param rpm engine speed, rpm ([PidIds.RPM], PID `010C`).
     * @param baro barometric pressure, kPa absolute — from either the ECU (`0133`) or the phone.
     * @return the boost [Reading], or `null` if any input is missing or the airflow/rpm inputs are
     *   degenerate (see [manifoldPressureFromAirflow]).
     */
    fun speedDensityBoost(
        maf: Reading?,
        iat: Reading?,
        rpm: Reading?,
        baro: Reading?,
    ): Reading? = boost(map = manifoldPressureFromAirflow(maf, iat, rpm), baro = baro)

    /**
     * Manifold absolute pressure (kPa) inferred from airflow by inverting the four-stroke
     * air-ingestion identity — the physics core of the speed-density boost estimate.
     *
     * ```
     * MAF = VE · (Vdisp/2) · (RPM/60) · ρ_charge,   ρ_charge = MAP / (R · T)
     *  ⇒  MAP(kPa) = MAF(g/s) · R · T(K) · 120 / (VE · Vdisp(L) · RPM)
     * ```
     *
     * The folded constant is **[SPEED_DENSITY_CONSTANT] ≈ 34.44** — NOT a magic number: it is
     * `R · strokesPerCycle · secondsPerMinute · (gramsPerKg · m³PerLiter)`, derived and
     * unit-checked in `ComputedChannelsTest`. The `gramsPerKg · m³PerLiter` pair is `1000 · 0.001 =
     * 1`, the two unit conversions that let MAF stay in g/s and displacement in litres; what
     * survives is `R · 2 · 60`.
     *
     * **VE is a curve, not a constant** — [VolumetricEfficiency.at], a boosted-diesel table that a
     * calibration drive refines. A flat VE is the tell of a lazy calc-boost gauge and is wrong by
     * 20 %+ across the rev range.
     *
     * **Charge-temp caveat.** [iat] (`0168`) may be *pre*-turbo, not post-intercooler manifold
     * temperature. The VE calibration absorbs the offset — one more reason VE is fitted, not
     * assumed.
     *
     * **Bounds & graceful degradation (the safety contract).** Any of these yields `null`, never a
     * number:
     * - a missing input (`maf`/`iat`/`rpm` absent);
     * - `rpm ≤ 0` — the divide-by-zero guard: key-on-engine-off and stall report *no boost*, not
     *   infinity;
     * - `maf ≤ 0` — zero/negative airflow is a sensor dropout, not a real vacuum reading.
     *
     * A finite result is then clamped to [MAP_MIN_KPA]..[MAP_MAX_KPA]: a near-stall RPM or a MAF
     * spike can drive the raw quotient absurdly high, and the clamp is what stops that from
     * surfacing as a 40-psi needle slam. The clamp is on MAP (the physical quantity), so the
     * signed vacuum/boost that [boost] reports downstream stays honest.
     *
     * @return the MAP [Reading] (id [ProtocolPidIds.MAP]), or `null` per the degradation rules.
     *
     * The multiple early returns ARE the safety contract: each degenerate input is its own named,
     * auditable guard that collapses to null before any arithmetic runs. Folding them into one exit
     * would obscure exactly the failure modes this function exists to make explicit — hence the
     * `ReturnCount` suppression.
     */
    @Suppress("ReturnCount")
    fun manifoldPressureFromAirflow(
        maf: Reading?,
        iat: Reading?,
        rpm: Reading?,
    ): Reading? {
        if (maf == null || iat == null || rpm == null) {
            return null
        }
        // Divide-by-zero guard and dropout guard, before any arithmetic can produce Infinity/NaN.
        if (rpm.value <= 0.0 || maf.value <= 0.0) {
            return null
        }
        val chargeTempKelvin = iat.value + KELVIN_OFFSET
        val ve = VolumetricEfficiency.at(rpm.value)
        val rawMap =
            maf.value * chargeTempKelvin * SPEED_DENSITY_CONSTANT /
                (ve * ENGINE_DISPLACEMENT_LITERS * rpm.value)
        if (!rawMap.isFinite()) {
            return null
        }
        return Reading(
            id = ProtocolPidIds.MAP,
            value = rawMap.coerceIn(MAP_MIN_KPA, MAP_MAX_KPA),
            timestamp = minOf(maf.timestamp, iat.timestamp, rpm.timestamp),
            stale = maf.stale || iat.stale || rpm.stale,
        )
    }

    /**
     * Specific gas constant for dry air, `0.287 kJ/(kg·K)` = `0.287 kPa·m³/(kg·K)` (1 kJ = 1 kPa·m³).
     * The one physical constant the speed-density model rests on.
     */
    private const val AIR_GAS_CONSTANT = 0.287

    /** Four-stroke: one intake charge per **two** crank revolutions. */
    private const val STROKES_PER_INTAKE_CYCLE = 2.0

    /** RPM is per minute; the ingestion identity works in seconds. */
    private const val SECONDS_PER_MINUTE = 60.0

    /** MAF is captured in grams; density math is per kilogram. */
    private const val GRAMS_PER_KILOGRAM = 1000.0

    /** Displacement is quoted in litres; density math is per cubic metre. */
    private const val CUBIC_METERS_PER_LITER = 0.001

    /**
     * The folded speed-density constant, `≈ 34.44`, kept as the product of its factors rather than
     * a literal so the derivation is auditable and `ComputedChannelsTest`'s unit-tracking test can
     * pin it. `AIR_GAS_CONSTANT · 2 · 60 · (1000 · 0.001)` = `0.287 · 120 · 1` = `34.44`.
     */
    const val SPEED_DENSITY_CONSTANT: Double =
        AIR_GAS_CONSTANT * STROKES_PER_INTAKE_CYCLE * SECONDS_PER_MINUTE *
            GRAMS_PER_KILOGRAM * CUBIC_METERS_PER_LITER

    /**
     * OM642 3.0 L V6 turbodiesel displacement, litres. The Sprinter NCV3's engine — a fixed
     * physical fact of the vehicle, not a tunable.
     */
    const val ENGINE_DISPLACEMENT_LITERS = 2.987

    /** °C → K. */
    private const val KELVIN_OFFSET = 273.15

    /**
     * Physical clamp on inferred MAP, kPa absolute. `20` is well below any running manifold
     * pressure (deep idle vacuum bottoms near 30–35 kPa); `260` is above the OM642's peak boost
     * (~22 psi ≈ 152 kPa gauge, ~253 kPa absolute at sea level) with a little headroom. A value
     * outside this band is a model/sensor artefact, not a reading, and is pulled back to the edge
     * rather than published as a spike.
     */
    const val MAP_MIN_KPA = 20.0
    const val MAP_MAX_KPA = 260.0
}

/**
 * Volumetric efficiency VE(RPM) for the OM642, as an interpolated table — the one un-measured term
 * in the speed-density boost model, and the reason it is honestly badged "Est." until calibrated.
 *
 * ## Why a curve, not a flat number
 * VE is the ratio of the air the cylinders actually ingest to their swept volume. On a *boosted*
 * engine it is low at idle (little manifold filling), climbs past **1.0** where the turbo is fully
 * spooled and force-feeding the cylinders (peak-torque band), and tapers at high rpm as breathing
 * losses grow. A flat VE — the shortcut every wild-guess calc-boost gauge in the app store ships —
 * is wrong by 20 %+ across the range and turns a physics model into a fudge factor.
 *
 * ## Calibration-ready (the 🖐 drive)
 * These defaults are sensible boosted-diesel values, **not** fitted to this specific van. To
 * refine them, log MAF/IAT/RPM/baro against a known boost reference (a mechanical gauge, or the MB
 * WOT spec ~21–22 psi peak) across idle→cruise→WOT, then solve each row's VE from
 * `VE = MAF · R · T · 120 / (MAP_measured · Vdisp · RPM)` and drop the fitted values straight into
 * [CURVE] — no other code changes, and `verified` flips to `true`. The table is the entire
 * calibration surface on purpose.
 *
 * Endpoints clamp (no extrapolation): below the first rpm or above the last, VE holds flat at the
 * edge value — the model must not invent efficiency past where the table has evidence.
 */
object VolumetricEfficiency {
    /**
     * (rpm, VE) breakpoints, ascending by rpm. Boosted-diesel shape: 0.85 at idle → a >1.0 hump in
     * the spooled peak-torque band → easing back toward redline. Ordered so [at] can walk it.
     *
     * These pairs are calibration DATA, not incidental literals — a table meant to be re-fitted by
     * the 🖐 drive, so each value is intentionally inline where it can be edited (`MagicNumber`
     * suppressed for that reason).
     */
    @Suppress("MagicNumber")
    val CURVE: List<Pair<Double, Double>> =
        listOf(
            700.0 to 0.85,
            1200.0 to 0.92,
            1800.0 to 1.00,
            2200.0 to 1.05,
            2800.0 to 1.04,
            3500.0 to 0.99,
            4600.0 to 0.95,
        )

    /**
     * VE at [rpm] by linear interpolation between [CURVE] breakpoints, holding flat outside the
     * table's range. Assumes [rpm] is already known positive (the caller guards `rpm ≤ 0`).
     *
     * Early returns for the two out-of-table cases keep the interpolation loop simple and the
     * no-extrapolation rule obvious (`ReturnCount` suppressed for that reason).
     */
    @Suppress("ReturnCount")
    fun at(rpm: Double): Double {
        val first = CURVE.first()
        if (rpm <= first.first) {
            return first.second
        }
        val last = CURVE.last()
        if (rpm >= last.first) {
            return last.second
        }
        for (i in 1 until CURVE.size) {
            val (upperRpm, upperVe) = CURVE[i]
            if (rpm <= upperRpm) {
                val (lowerRpm, lowerVe) = CURVE[i - 1]
                val fraction = (rpm - lowerRpm) / (upperRpm - lowerRpm)
                return lowerVe + fraction * (upperVe - lowerVe)
            }
        }
        return last.second
    }
}
