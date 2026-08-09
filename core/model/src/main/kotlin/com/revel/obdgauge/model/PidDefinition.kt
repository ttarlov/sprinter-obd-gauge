package com.revel.obdgauge.model

/**
 * The static definition of one gauge's data channel: what to ask the dongle, and how to
 * turn the raw response into a value. This is a **frozen Phase-0 contract** (see
 * `docs/01-build-plan.md` §0.2 and `DECISIONS.md`); the registry of instances lives in
 * `:core:protocol`.
 *
 * Computed channels (e.g. boost = MAP − baro) are NOT represented as a single
 * `PidDefinition` — they are derived by combining multiple [Reading]s inside the
 * `VehicleDataSource` implementation. The UI never does protocol math; see [VehicleDataSource].
 *
 * @param id stable identifier used to key [Reading]s and UI config, e.g. `"coolant"`,
 *   `"transTemp"`, `"boost"`, `"oilTemp"`, `"baro"`, `"rpm"`.
 * @param label human-readable gauge name for display.
 * @param unit the [MeasurementUnit] the parsed value is expressed in.
 * @param request how to ask the dongle for this value; see [ObdRequest].
 * @param parse converts the raw response bytes into a numeric value (unit conversion and
 *   SAE scaling applied here, not in the UI).
 * @param pollPriority how often the scheduler polls this PID; see [PollPriority].
 * @param verified whether this PID's request/parse has been confirmed against real hardware.
 *   Standard mode-01 PIDs default to `true`; mode-22 hypotheses (see `docs/01-build-plan.md`
 *   §2B) start `false` until hardware-verified in Sprint 3, and are surfaced as such in the UI.
 */
data class PidDefinition(
    val id: String,
    val label: String,
    val unit: MeasurementUnit,
    val request: ObdRequest,
    val parse: (ByteArray) -> Double,
    val pollPriority: PollPriority,
    val verified: Boolean = true,
)
