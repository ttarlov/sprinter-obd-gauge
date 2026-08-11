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
     * This is the calculation the whole app is shaped around. Both `010B` (manifold absolute) and
     * `0133` (barometric) are *absolute* kPa, so their difference is boost referenced to the air
     * the engine is actually breathing — correct in Death Valley and on Loveland Pass alike. Every
     * fixed-offset shortcut ("subtract 101.3") is wrong by roughly 20 kPa (3 psi) at 6 000 ft and
     * 32 kPa (4.6 psi) at 10 000 ft, which on a loaded Sprinter is the difference between "normal
     * pull" and "something is wrong".
     *
     * Naturally signed: at idle, manifold pressure sits far *below* ambient, so boost is negative
     * — that is vacuum, and it is a real reading, not an error to clamp away. A gauge that floors
     * at zero hides a leaking intake.
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
}
