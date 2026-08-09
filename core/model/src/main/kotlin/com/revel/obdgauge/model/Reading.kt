package com.revel.obdgauge.model

import java.time.Instant

/**
 * One parsed value for a [PidDefinition], as consumed by the UI. This is a **frozen
 * Phase-0 contract** (see `docs/01-build-plan.md` §0.2 and `DECISIONS.md`).
 *
 * @param id the [PidDefinition.id] this reading is for.
 * @param value the parsed, unit-converted value (see [PidDefinition.unit] for its unit).
 * @param timestamp when this value was captured.
 * @param stale `true` when this reading is older than the freshness window the UI expects
 *   for its poll priority (e.g. a lost connection or a skipped poll cycle). The UI dims the
 *   value and shows "last seen Xs ago" rather than showing a silently outdated number.
 */
data class Reading(
    val id: String,
    val value: Double,
    val timestamp: Instant,
    val stale: Boolean,
)
