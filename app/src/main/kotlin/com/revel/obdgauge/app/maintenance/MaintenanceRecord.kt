package com.revel.obdgauge.app.maintenance

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * OBD-79: one logged service event against a [ServiceItem] — "I did the oil change on this date,
 * at this mileage." [MaintenanceStatus.forItem] reads the latest of these (by
 * [performedDateEpochDay]) to compute a countdown to the next one.
 *
 * @param performedDateEpochDay [java.time.LocalDate.toEpochDay] — a plain `Long` so this entity
 *   (and its DAO queries) needs no Room date `TypeConverter`.
 * @param odometerMiles the manual odometer reading at the time of service (prefilled to
 *   [AppSettings.currentOdometerMiles][com.revel.obdgauge.app.settings.currentOdometerMiles] in
 *   the "Log service" form, editable).
 * @param costCents null means "not recorded" — optional field, per the issue spec.
 */
@Entity(
    tableName = "maintenance_records",
    foreignKeys = [
        ForeignKey(
            entity = ServiceItem::class,
            parentColumns = ["id"],
            childColumns = ["serviceItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serviceItemId")],
)
data class MaintenanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serviceItemId: Long,
    val performedDateEpochDay: Long,
    val odometerMiles: Int,
    val notes: String? = null,
    val costCents: Int? = null,
    val partsUsed: String? = null,
)
