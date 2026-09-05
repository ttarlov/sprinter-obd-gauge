package com.revel.obdgauge.app.maintenance.seed

import com.revel.obdgauge.app.maintenance.MaintenanceCategory
import com.revel.obdgauge.app.maintenance.MaintenanceCategory.CONSUMABLE
import com.revel.obdgauge.app.maintenance.MaintenanceCategory.DRIVELINE
import com.revel.obdgauge.app.maintenance.MaintenanceCategory.KNOWN_ISSUE
import com.revel.obdgauge.app.maintenance.MaintenanceCategory.ROUTINE
import com.revel.obdgauge.app.maintenance.MaintenanceCategory.SCHEDULED
import com.revel.obdgauge.app.maintenance.ServiceItem

/**
 * OBD-79's code-defined default catalog for this van (2018-ish Mercedes Sprinter NCV3, OM642
 * diesel, factory 4x4) — [com.revel.obdgauge.app.maintenance.MaintenanceRepository.seedIfNeeded]
 * inserts this exactly once, on first DB creation. Every field is editable afterward; nothing here
 * is re-applied or re-synced once seeded.
 *
 * **722.9 vs 722.6 transmission** (`issues/OBD-79.md` "Open items" #1): the repo's own captured
 * hardware data says this van runs the 722.9 automatic (→ ATF spec MB 236.15/236.17), which is
 * what's seeded below — Taras separately said "NCV3" in conversation, which points at 722.6/236.14
 * on some NCV3 configurations, so this is flagged low-confidence in [SPEC_TRANS] rather than
 * silently picked. One tap on the detail screen corrects it either way.
 *
 * **4x4 driveline items** (Open item #2) are seeded `enabled = true` with a "4x4 only — disable if
 * RWD" note — there's no vehicle-config detection in this issue, so a RWD owner disables them by
 * hand.
 *
 * **Fuel filter part number** (Open item #3) is seeded low-confidence per the issue table.
 */
object Ncv3Om642Seed {
    private const val MI_7_5 = 7_500
    private const val MI_10 = 10_000
    private const val MI_20 = 20_000
    private const val MI_40 = 40_000
    private const val MI_60 = 60_000
    private const val MI_100 = 100_000

    private const val MO_12 = 12
    private const val MO_24 = 24
    private const val MO_60 = 60

    private const val SPEC_TRANS =
        "⚠️ seed assumes 722.9 (per this van's captured hardware data) — MB 236.15/236.17, " +
            "722.6 would be 236.14; confirm which gearbox is fitted and correct if needed."
    private const val SPEC_4X4_ONLY = "4x4 only — disable if RWD."

    val items: List<ServiceItem> =
        listOf(
            item(
                name = "Engine oil + filter",
                category = ROUTINE,
                intervalMiles = MI_10,
                intervalMonths = MO_12,
                partNumbers = listOf("A6421800009"),
                specNotes = "MB 229.51/.52, 5W-30, ~13 L; filter cap torque 25 Nm.",
            ),
            item(
                name = "Engine air filter",
                category = ROUTINE,
                intervalMiles = MI_20,
                intervalMonths = MO_24,
                partNumbers = listOf("A0000903751"),
            ),
            item(
                name = "Cabin filter",
                category = ROUTINE,
                intervalMiles = MI_20,
                intervalMonths = MO_24,
                partNumbers = listOf("A0008300418", "A0018358747"),
                specNotes = "A0008300418 = dash filter; A0018358747 = roof A/C airbox — verify which this van has.",
            ),
            item(
                name = "Fuel filter",
                category = SCHEDULED,
                intervalMiles = MI_20,
                partNumbers = listOf("A6420920301"),
                specNotes = "⚠️ LOW-CONFIDENCE — verify connector (3 vs 5-pin, heated?) before ordering.",
            ),
            item(
                name = "Transmission fluid + filter",
                category = SCHEDULED,
                intervalMiles = MI_40,
                specNotes = SPEC_TRANS,
            ),
            item(
                name = "Coolant",
                category = SCHEDULED,
                intervalMiles = MI_60,
                intervalMonths = MO_60,
                specNotes = "MB 325.0 (blue, pre-2014-era spec), ~12 L, 50/50 mix.",
            ),
            item(
                name = "Brake fluid",
                category = SCHEDULED,
                intervalMonths = MO_24,
                specNotes = "DOT4, MB 331.0 LV.",
            ),
            item(
                name = "Serpentine belt + tensioner",
                category = SCHEDULED,
                intervalMiles = MI_100,
                partNumbers = listOf("A0029934296", "A6422001370"),
                specNotes = "A0029934296 = belt, A6422001370 = tensioner — do idlers at the same time as the belt.",
            ),
            item(
                name = "Rear differential fluid",
                category = DRIVELINE,
                intervalMiles = MI_40,
                partNumbers = listOf("A0019898303"),
                specNotes = "75W-85, MB 235.7, ~2.2–2.8 L.",
            ),
            item(
                name = "Front differential fluid",
                category = DRIVELINE,
                intervalMiles = MI_40,
                specNotes = "75W-90, MB 235.8/.9, ~0.85 L. $SPEC_4X4_ONLY",
            ),
            item(
                name = "Transfer case fluid",
                category = DRIVELINE,
                intervalMiles = MI_40,
                specNotes = "ATF, MB 236.12/.14, ~1.0 L. $SPEC_4X4_ONLY",
            ),
            item(
                name = "DEF / AdBlue",
                category = CONSUMABLE,
                intervalMiles = MI_10,
                specNotes = "ISO 22241, ~22 L tank — refill interval, not a wear part.",
            ),
            item(
                name = "Glow plugs",
                category = SCHEDULED,
                intervalMiles = MI_60,
                partNumbers = listOf("A0011596601"),
                specNotes = "7V ceramic (cross-refs NGK CZ303) — post-6/2012 builds are ceramic; do NOT fit steel.",
            ),
            item(
                name = "Oil-cooler seals",
                category = KNOWN_ISSUE,
                partNumbers = listOf("A6421880580"),
                specNotes = "Viton valley reseal — the signature OM642 leak. Watch item, no fixed interval.",
            ),
            item(
                name = "Tire rotation",
                category = ROUTINE,
                intervalMiles = MI_7_5,
                specNotes = "Seeded at the upper end of the 5,000–7,500 mi range — tighten up if wear is uneven.",
            ),
        )

    @Suppress("LongParameterList") // one param per ServiceItem field this seed table actually varies.
    private fun item(
        name: String,
        category: MaintenanceCategory,
        intervalMiles: Int? = null,
        intervalMonths: Int? = null,
        partNumbers: List<String> = emptyList(),
        specNotes: String = "",
    ): ServiceItem =
        ServiceItem(
            name = name,
            category = category,
            intervalMiles = intervalMiles,
            intervalMonths = intervalMonths,
            partNumbers = partNumbers,
            specNotes = specNotes,
            isSeed = true,
        )
}
