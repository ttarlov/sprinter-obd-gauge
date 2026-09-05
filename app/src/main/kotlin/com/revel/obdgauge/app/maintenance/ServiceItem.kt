package com.revel.obdgauge.app.maintenance

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * OBD-79: one row in the maintenance catalog — a kind of service (oil change, tire rotation, …),
 * not a logged event (that's [MaintenanceRecord]). Every field is user-editable in the Maintenance
 * detail screen; [isSeed] just distinguishes "Taras added this" from "the NCV3/OM642 seed catalog
 * put this here", it does not gate editability.
 *
 * @param intervalMiles null means "no mileage interval" (e.g. brake fluid, time-only) — see
 *   [MaintenanceStatus.forItem].
 * @param intervalMonths null means "no time interval" (e.g. tire rotation, miles-only).
 * @param partNumbers editable list of part numbers, pre-filled by the seed catalog where known.
 * @param specNotes free text: oil type/capacity/torque/fluid spec, plus low-confidence flags the
 *   seed catalog leaves for the user to verify (`issues/OBD-79.md`'s "Open items").
 * @param enabled OBD-79's seed marks 4x4-only driveline items enabled but notes "disable if RWD"
 *   in [specNotes] — there is no vehicle-config detection in this issue, so this is the only lever.
 */
@Entity(tableName = "service_items")
@TypeConverters(ServiceItemConverters::class)
data class ServiceItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val category: MaintenanceCategory,
    val intervalMiles: Int? = null,
    val intervalMonths: Int? = null,
    val partNumbers: List<String> = emptyList(),
    val specNotes: String = "",
    val enabled: Boolean = true,
    val isSeed: Boolean = false,
)

/**
 * Room has no native `List<String>` column type — [ServiceItem.partNumbers] is small (never more
 * than a handful of part numbers) and never searched/filtered at the SQL level, so a delimited
 * string column is simpler than a join table for what this issue needs. `;` can't appear inside a
 * real Mercedes part number (alphanumeric + hyphens only), so no escaping is needed.
 */
internal object ServiceItemConverters {
    private const val SEPARATOR = ";"

    @TypeConverter
    @JvmStatic
    fun fromPartNumbers(value: List<String>): String = value.joinToString(SEPARATOR)

    @TypeConverter
    @JvmStatic
    fun toPartNumbers(value: String): List<String> = value.split(SEPARATOR).filter { it.isNotBlank() }
}
