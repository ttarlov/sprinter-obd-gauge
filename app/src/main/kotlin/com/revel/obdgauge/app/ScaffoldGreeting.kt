package com.revel.obdgauge.app

import javax.inject.Inject

/**
 * Trivial constructor-injected type with no dependencies of its own.
 *
 * Exists solely so [MainActivity] has something to `@Inject` — proving the Hilt graph
 * assembles and compiles end to end for the Sprint-0 scaffold. Deleted once a real
 * injected dependency (a `VehicleDataSource` ViewModel) takes its place in Sprint 1.
 */
class ScaffoldGreeting
    @Inject
    constructor() {
        val message: String = "Sprinter OBD Gauge — scaffold online"
    }
