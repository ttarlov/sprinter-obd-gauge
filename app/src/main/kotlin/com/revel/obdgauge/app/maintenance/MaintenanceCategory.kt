package com.revel.obdgauge.app.maintenance

/**
 * OBD-79: how a [ServiceItem] groups on the Maintenance list — matches the issue's seed table
 * exactly. Stored on the entity as its [name] (see [ServiceItem]'s Room column), so adding a
 * category here is additive; renaming one is a silent-migration hazard the same way any Room
 * enum-as-string column is (out of scope for this issue — no persisted install predates this
 * table).
 */
enum class MaintenanceCategory {
    ROUTINE,
    SCHEDULED,
    DRIVELINE,
    CONSUMABLE,
    KNOWN_ISSUE,
}
