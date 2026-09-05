package com.revel.obdgauge.app.maintenance

import java.time.LocalDate

/** Which trigger ([ServiceItem.intervalMiles] or [ServiceItem.intervalMonths]) is driving [MaintenanceStatus.level]. */
enum class MaintenanceTrigger {
    MILES,
    TIME,

    /** Neither interval is set (e.g. a [MaintenanceCategory.KNOWN_ISSUE] watch item) — nothing counts down. */
    NONE,
}

enum class MaintenanceStatusLevel {
    OK,
    DUE_SOON,
    OVERDUE,

    /** [ServiceItem] has no [MaintenanceRecord] yet — nothing to count down from. */
    NEVER_SERVICED,

    /** [ServiceItem.enabled] is false. */
    DISABLED,
}

/**
 * The Maintenance list/detail screens' one status computation, pure and Compose-free (OBD-79) —
 * given a catalog item, its most recent logged service (if any), the current odometer reading,
 * and today's date, decides the chip color/countdown text. No I/O, no Android types: [forItem] is
 * the only entry point, exercised directly by `MaintenanceStatusTest`.
 *
 * @param milesUntil remaining miles to the next mileage-triggered service, or `null` if
 *   [ServiceItem.intervalMiles] is unset. Negative means overdue by that many miles.
 * @param daysUntil remaining days to the next time-triggered service, or `null` if
 *   [ServiceItem.intervalMonths] is unset. Negative means overdue by that many days.
 * @param drivingTrigger which of [milesUntil]/[daysUntil] decided [level] — see [forItem]'s KDoc
 *   for the tie-break rule when both intervals are set.
 */
data class MaintenanceStatus(
    val level: MaintenanceStatusLevel,
    val milesUntil: Int?,
    val daysUntil: Long?,
    val drivingTrigger: MaintenanceTrigger,
) {
    companion object {
        /**
         * Miles/days windows for [MaintenanceStatusLevel.DUE_SOON] — inside this many
         * miles/days of the next service (but not yet past it) reads amber rather than green.
         * Round numbers, not derived from any single item's interval, matching the seed
         * catalog's own coarse-grained intervals (thousands of miles / whole months).
         */
        private const val DUE_SOON_MILES_WINDOW = 500
        private const val DUE_SOON_DAYS_WINDOW = 30L

        /**
         * The one status computation both Maintenance screens read.
         *
         * Boundary handling (see `MaintenanceStatusTest`'s naming for each case):
         * - `!item.enabled` → [MaintenanceStatusLevel.DISABLED], regardless of everything else.
         * - `latestRecord == null` → [MaintenanceStatusLevel.NEVER_SERVICED] ("log one" in the UI)
         *   — even for a [MaintenanceCategory.KNOWN_ISSUE] watch item with no interval at all,
         *   since "never logged" is itself the fact worth surfacing.
         * - Neither [ServiceItem.intervalMiles] nor [ServiceItem.intervalMonths] is set (a pure
         *   watch item that HAS been logged at least once) → [MaintenanceStatusLevel.OK] with
         *   [MaintenanceTrigger.NONE] and both countdowns `null` — there is nothing to count down.
         * - Otherwise each set interval independently classifies OK/DUE_SOON/OVERDUE against its
         *   own [milesUntil]/[daysUntil]; the OVERALL level is the more urgent (severity-ordered
         *   OVERDUE > DUE_SOON > OK) of the two, and [drivingTrigger] names whichever one produced
         *   that level. A tie in severity (both intervals equally urgent, or only one interval is
         *   set at all) prefers [MaintenanceTrigger.MILES] — an arbitrary but deterministic
         *   tie-break, since miles and days aren't directly comparable magnitudes.
         */
        fun forItem(
            item: ServiceItem,
            latestRecord: MaintenanceRecord?,
            currentOdometerMiles: Int,
            today: LocalDate,
        ): MaintenanceStatus =
            when {
                !item.enabled ->
                    MaintenanceStatus(MaintenanceStatusLevel.DISABLED, null, null, MaintenanceTrigger.NONE)
                latestRecord == null ->
                    MaintenanceStatus(MaintenanceStatusLevel.NEVER_SERVICED, null, null, MaintenanceTrigger.NONE)
                else -> fromIntervals(item, latestRecord, currentOdometerMiles, today)
            }

        /** [forItem]'s "enabled and has a latest record" branch — the actual interval math. */
        private fun fromIntervals(
            item: ServiceItem,
            latestRecord: MaintenanceRecord,
            currentOdometerMiles: Int,
            today: LocalDate,
        ): MaintenanceStatus {
            val milesUntil =
                item.intervalMiles?.let { interval -> latestRecord.odometerMiles + interval - currentOdometerMiles }
            val daysUntil =
                item.intervalMonths?.let { months ->
                    val dueDate = LocalDate.ofEpochDay(latestRecord.performedDateEpochDay).plusMonths(months.toLong())
                    dueDate.toEpochDay() - today.toEpochDay()
                }

            val milesLevel = milesUntil?.let { levelFor(it, DUE_SOON_MILES_WINDOW) }
            val daysLevel = daysUntil?.let { levelFor(it, DUE_SOON_DAYS_WINDOW) }

            val level: MaintenanceStatusLevel
            val trigger: MaintenanceTrigger
            when {
                milesLevel == null && daysLevel == null -> {
                    level = MaintenanceStatusLevel.OK
                    trigger = MaintenanceTrigger.NONE
                }
                milesLevel == null -> {
                    // Excluded above: milesLevel == null && daysLevel == null — so daysLevel is set here.
                    level = requireNotNull(daysLevel)
                    trigger = MaintenanceTrigger.TIME
                }
                daysLevel == null -> {
                    level = milesLevel
                    trigger = MaintenanceTrigger.MILES
                }
                severity(daysLevel) > severity(milesLevel) -> {
                    level = daysLevel
                    trigger = MaintenanceTrigger.TIME
                }
                else -> {
                    level = milesLevel
                    trigger = MaintenanceTrigger.MILES
                }
            }

            return MaintenanceStatus(level, milesUntil, daysUntil, trigger)
        }

        private fun levelFor(
            remaining: Long,
            dueSoonWindow: Long,
        ): MaintenanceStatusLevel =
            when {
                remaining <= 0 -> MaintenanceStatusLevel.OVERDUE
                remaining <= dueSoonWindow -> MaintenanceStatusLevel.DUE_SOON
                else -> MaintenanceStatusLevel.OK
            }

        private fun levelFor(
            remaining: Int,
            dueSoonWindow: Int,
        ): MaintenanceStatusLevel = levelFor(remaining.toLong(), dueSoonWindow.toLong())

        /** OVERDUE > DUE_SOON > OK, for picking the more urgent of two independently-classified levels. */
        private fun severity(level: MaintenanceStatusLevel): Int =
            when (level) {
                MaintenanceStatusLevel.OVERDUE -> 2
                MaintenanceStatusLevel.DUE_SOON -> 1
                else -> 0
            }
    }
}
