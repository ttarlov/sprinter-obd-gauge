package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority

/**
 * The [PidDefinition]s this dashboard requests from its
 * [VehicleDataSource][com.revel.obdgauge.model.VehicleDataSource].
 * [PidDefinition.request]/[PidDefinition.parse] are unused by `:core:testing`'s
 * `FakeVehicleDataSource` (it replays scripted values keyed by [PidDefinition.id], not by
 * asking the dongle) — the real registry lives in `:core:protocol` and replaces this list at
 * Phase-4 integration. `PollPriority` values here are advisory for the same reason.
 */
val DASHBOARD_PIDS: List<PidDefinition> =
    listOf(
        PidDefinition(
            id = PidIds.COOLANT,
            label = "Coolant",
            unit = MeasurementUnit.FAHRENHEIT,
            request = ObdRequest.StandardPid(mode = STANDARD_MODE, pid = COOLANT_PID),
            parse = { UNUSED_PARSE_RESULT },
            pollPriority = PollPriority.SLOW,
        ),
        PidDefinition(
            id = PidIds.OIL_TEMP,
            label = "Oil",
            unit = MeasurementUnit.FAHRENHEIT,
            request = ObdRequest.Mode22(header = OIL_HEADER, rxFilter = OIL_RX_FILTER, request = OIL_REQUEST),
            parse = { UNUSED_PARSE_RESULT },
            pollPriority = PollPriority.SLOW,
            verified = false,
        ),
        PidDefinition(
            id = PidIds.TRANS_TEMP,
            label = "Trans",
            unit = MeasurementUnit.FAHRENHEIT,
            request = ObdRequest.Mode22(header = TRANS_HEADER, rxFilter = TRANS_RX_FILTER, request = TRANS_REQUEST),
            parse = { UNUSED_PARSE_RESULT },
            pollPriority = PollPriority.SLOW,
            verified = false,
        ),
        PidDefinition(
            id = PidIds.BOOST,
            label = "Boost",
            unit = MeasurementUnit.PSI,
            request = ObdRequest.StandardPid(mode = STANDARD_MODE, pid = MAP_PID),
            parse = { UNUSED_PARSE_RESULT },
            pollPriority = PollPriority.FAST,
        ),
    )

/**
 * [DASHBOARD_PIDS] keyed by [PidDefinition.id] — the lookup `DashboardUiState.kt`'s unit
 * conversion (OBD-21) and `settings/SettingsScreen.kt`'s threshold editors use to find each
 * gauge's current wire unit, rather than hardcoding it a second time at each call site.
 */
val DASHBOARD_PIDS_BY_ID: Map<String, PidDefinition> = DASHBOARD_PIDS.associateBy { it.id }

private const val UNUSED_PARSE_RESULT = 0.0
private const val STANDARD_MODE = 1
private const val COOLANT_PID = 0x05
private const val MAP_PID = 0x0B
private const val TRANS_HEADER = "07E12130"
private const val TRANS_RX_FILTER = "032200000000"
private const val TRANS_REQUEST = "220543"
private const val OIL_HEADER = "07E12130"
private const val OIL_RX_FILTER = "032200000000"
private const val OIL_REQUEST = "220534"
