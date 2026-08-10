package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState

private const val BANNER_HORIZONTAL_PADDING_DP = 16
private const val BANNER_VERTICAL_PADDING_DP = 8
private const val BANNER_SPACING_DP = 8
private const val SPINNER_SIZE_DP = 16
private const val SPINNER_STROKE_DP = 2

/**
 * Connection-state banner (OBD-11), driven directly by [LinkState] — presentation only, no
 * reconnect logic of its own (that's OBD-23's job; this renders whatever state the
 * [com.revel.obdgauge.model.VehicleDataSource] happens to be in).
 *
 * [LinkState.Ready] renders nothing at all (no node, not even a zero-height one) — the "quiet
 * dashboard" is the whole point once the link is healthy, and tests assert this via
 * `assertDoesNotExist()` on `testTag("connection-banner")`. Every other state renders a full-
 * width bar: [LinkState.Scanning]/[LinkState.Connecting] get a spinner + status text on a
 * neutral surface; [LinkState.Disconnected] is a plain neutral status line; [LinkState.Error]
 * uses the theme's error container plus a "Reconnecting…" affordance — since whether a retry
 * actually happens is out of this composable's scope, the affordance communicates intent, not
 * a guarantee.
 */
@Composable
fun ConnectionBanner(
    connection: LinkState,
    modifier: Modifier = Modifier,
) {
    val message = connectionBannerMessage(connection) ?: return
    val isError = connection is LinkState.Error
    val isBusy = connection == LinkState.Scanning || connection == LinkState.Connecting || isError
    val containerColor =
        if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("connection-banner")
                .semantics { stateDescription = connectionBannerStateName(connection) }
                .background(containerColor)
                .padding(horizontal = BANNER_HORIZONTAL_PADDING_DP.dp, vertical = BANNER_VERTICAL_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BANNER_SPACING_DP.dp),
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(SPINNER_SIZE_DP.dp),
                color = contentColor,
                strokeWidth = SPINNER_STROKE_DP.dp,
            )
        }
        Text(
            text = message,
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("connection-banner-message"),
        )
        if (isError) {
            Text(
                text = "Reconnecting…",
                color = contentColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("connection-banner-reconnecting"),
            )
        }
    }
}

/** Lowercase [LinkState] state name, exposed as `stateDescription` for test assertions. */
internal fun connectionBannerStateName(connection: LinkState): String =
    when (connection) {
        LinkState.Disconnected -> "disconnected"
        LinkState.Scanning -> "scanning"
        LinkState.Connecting -> "connecting"
        LinkState.Ready -> "ready"
        is LinkState.Error -> "error"
    }

/** Banner copy per [LinkState]; `null` means "don't render a banner" ([LinkState.Ready] only). */
internal fun connectionBannerMessage(connection: LinkState): String? =
    when (connection) {
        LinkState.Ready -> null
        LinkState.Disconnected -> "Not connected"
        LinkState.Scanning -> "Scanning for dongle…"
        LinkState.Connecting -> "Connecting…"
        is LinkState.Error -> linkErrorMessage(connection.cause)
    }

private fun linkErrorMessage(cause: LinkError): String =
    when (cause) {
        LinkError.PermissionDenied -> "Bluetooth permission denied"
        LinkError.BluetoothOff -> "Bluetooth is off"
        LinkError.DeviceNotFound -> "Dongle not found"
        is LinkError.Gatt -> "Connection error (code ${cause.code})"
        LinkError.Timeout -> "Connection timed out"
        is LinkError.Unknown -> cause.message
    }
