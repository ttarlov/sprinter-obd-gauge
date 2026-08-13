package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.Immutable
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.Reading
import java.time.Instant

/**
 * OBD-27's raw-response viewer content for one gauge: what was actually asked of the dongle,
 * plus the reading that came back from it. Every field here is real — [requestSummary] is
 * formatted straight from the [PidDefinition]'s own [ObdRequest] (the literal mode/PID or
 * header/rxFilter/request bytes this app would send), never invented.
 *
 * What this deliberately does NOT show: literal ELM327 wire response text. [Reading] (the
 * frozen `:core:model` contract) carries only the already-parsed [Reading.value] — no raw
 * bytes — and `:app` has no access to them regardless: the fake source used by every build this
 * issue can test against (`demo`) never receives real wire bytes either (it replays scripted
 * numeric values by id — see `FakeVehicleDataSource`), and reconstructing plausible-looking hex
 * from a parsed value here would mean re-implementing SAE/mode-22 scaling math inside `:app`,
 * which `VehicleDataSource`'s own KDoc rules out ("the UI never does protocol math"). A future
 * per-channel raw-capture flow is a `:core:ble`/`:core:protocol` concern (arriving with real
 * hardware wiring, not this issue) — this viewer's shape (request sent + value received +
 * verified flag) is what's honestly available at this layer today, and is exactly what OBD-27's
 * AC asks for: telling the driver whether a number is proven, not decoding bytes for them.
 *
 * @param verified mirrors [PidDefinition.verified] — the same source [GaugeTileUiState.verified]
 *   (the badge) reads, so the badge and the viewer it opens never disagree.
 */
@Immutable
data class RawFrameUiState(
    val id: String,
    val label: String,
    val verified: Boolean,
    val requestSummary: String,
    val valueText: String,
    val capturedText: String?,
    val hasReading: Boolean,
)

/** Human-readable rendering of what this app would send the dongle for [request]. */
internal fun describeRequest(request: ObdRequest): String =
    when (request) {
        is ObdRequest.StandardPid ->
            "Mode %02d PID %02X".format(request.mode, request.pid)
        is ObdRequest.Mode22 ->
            "ATSH${request.header} ATCRA${request.rxFilter} → ${request.request}"
    }

/** Builds [RawFrameUiState] for [pid], reusing [tileValueText] rather than reformatting it. */
internal fun rawFrameState(
    pid: PidDefinition,
    reading: Reading?,
    tileValueText: String,
    now: Instant,
): RawFrameUiState =
    RawFrameUiState(
        id = pid.id,
        label = pid.label,
        verified = pid.verified,
        requestSummary = describeRequest(pid.request),
        valueText = tileValueText,
        capturedText = reading?.let { formatCapturedText(it, now) },
        hasReading = reading != null,
    )
