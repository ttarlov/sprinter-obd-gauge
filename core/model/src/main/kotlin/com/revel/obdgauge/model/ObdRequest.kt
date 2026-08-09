package com.revel.obdgauge.model

/**
 * How to ask the dongle for one [PidDefinition]'s value. This is a **frozen Phase-0
 * contract** (see `docs/01-build-plan.md` §0.2 and `DECISIONS.md`).
 */
sealed interface ObdRequest {
    /**
     * A standard OBD-II mode/PID request, e.g. mode `01` PID `05` (coolant temp) sent as `0105`.
     *
     * @param mode the OBD-II service/mode, e.g. `1` for "show current data".
     * @param pid the parameter ID within that mode.
     */
    data class StandardPid(
        val mode: Int,
        val pid: Int,
    ) : ObdRequest

    /**
     * A manufacturer-specific mode-22 request, framed with an `ATSH` header and `ATCRA`
     * receive filter before the request itself is sent. Used for the Mercedes X-Gauge-derived
     * PIDs (trans temp, etc.) that standard mode-01 doesn't expose.
     *
     * @param header the `ATSH` value: the CAN transmit header (TXD) to set before requesting.
     * @param rxFilter the `ATCRA` value: the CAN receive filter (RXF) for the expected reply.
     * @param request the raw mode-22 request bytes, e.g. `2213 08` framed as one hex string.
     */
    data class Mode22(
        val header: String,
        val rxFilter: String,
        val request: String,
    ) : ObdRequest
}
