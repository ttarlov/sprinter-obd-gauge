package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.app.ui.theme.GaugeAmber
import com.revel.obdgauge.app.ui.theme.GaugeGreen
import com.revel.obdgauge.app.ui.theme.GaugeNeutral
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState

// Tight on purpose: this pill now shares its header Row with five fixed-min-width buttons
// (Rec/wrench/gear + the always-composed but often-invisible ＋Add/Done — see this file's KDoc
// on the outer-Row-vs-inner-pill split), so on the narrowest portrait widths its own flex slot
// can shrink to only a few dp. Every dp shaved off this pill's own chrome is a dp more that can
// go to its message text before ellipsis eats it entirely.
private const val PILL_HORIZONTAL_PADDING_DP = 8
private const val PILL_VERTICAL_PADDING_DP = 6
private const val PILL_SPACING_DP = 6
private const val PILL_CORNER_RADIUS_DP = 14
private const val PILL_TINT_ALPHA = 0.14f
private const val SPINNER_SIZE_DP = 14
private const val SPINNER_STROKE_DP = 2
private const val STATUS_DOT_SIZE_DP = 6

/**
 * The connection-status pill's color+motion treatment, derived from [LinkState] and (for
 * [LinkState.Ready] only) [dataFlowing] — see [bannerTone]. Kept as its own enum (rather than
 * inlining a chain of `if`s in [ConnectionBanner]) so tone assignment is a single, exhaustive
 * `when` next to [connectionBannerMessage]/[connectionBannerStateName]'s own tone-shaped `when`s.
 */
private enum class BannerTone { NEUTRAL, BUSY, LIVE, ERROR }

private fun bannerTone(
    connection: LinkState,
    dataFlowing: Boolean,
): BannerTone =
    when (connection) {
        LinkState.Disconnected -> BannerTone.NEUTRAL
        LinkState.Scanning, LinkState.Connecting -> BannerTone.BUSY
        LinkState.Ready -> if (dataFlowing) BannerTone.LIVE else BannerTone.BUSY
        is LinkState.Error -> BannerTone.ERROR
    }

/**
 * Permanent connection-status pill (OBD-11, made permanent + fused with data-liveness by
 * OBD-84) — presentation only, no reconnect logic of its own (that's OBD-23's job; this renders
 * whatever state the [com.revel.obdgauge.model.VehicleDataSource] happens to be in, plus
 * [dataFlowing] — see [DashboardUiState.dataFlowing]'s KDoc for how that's derived).
 *
 * Every [LinkState] renders a single-line, fixed-height, ellipsized pill — the dashboard header
 * `Row`'s height (and thus the grid viewport / tile sizes, see `DashboardScreen.kt`'s round-6/7
 * fixes) must never depend on which state this is showing. [LinkState.Disconnected]/
 * [LinkState.Ready] (steady states, nothing in flight) show a small color-coded status dot;
 * [LinkState.Scanning]/[LinkState.Connecting]/[LinkState.Error] (an attempt in flight, or one
 * that just failed and may retry) show a spinner instead. [LinkState.Ready] is where OBD-84
 * splits by [dataFlowing]: "Live · reading ECU" (green) once fresh data is actually arriving,
 * "Connected · waiting for ECU" (amber) when the dongle is paired but nothing fresh has come in
 * (ignition off, `SEARCHING…`, a protocol wedge). [LinkState.Error] keeps the stronger, saturated
 * `errorContainer` treatment (unlike the other states' quiet dot-on-tint look) plus a
 * "Reconnecting…" affordance — since whether a retry actually happens is out of this
 * composable's scope, the affordance communicates intent, not a guarantee.
 *
 * ### OBD-84: permanent + the [showConnectionStatus] toggle
 * Prior to OBD-84, [LinkState.Ready] rendered nothing at all (the "quiet dashboard once healthy"
 * behavior some `ConnectionBannerTest` cases still cover for the OFF path). [showConnectionStatus]
 * `= false` (Settings' "Show connection status" toggle) restores exactly that: `Ready` renders
 * nothing, every other state is unchanged. The toggle only ever gates the permanent `Ready`
 * pill — reconnect feedback (Disconnected/Scanning/Connecting/Error) always shows regardless, so
 * turning this off never hides a genuine outage.
 *
 * ### OBD-25: the connect entry point
 * [onConnect] is the app's one user-visible way to ask for a link. `null` (the default, and what
 * the `demo` flavor always passes, since it has no link) renders exactly the pre-OBD-25 banner.
 * When non-null, a trailing text button appears on the states where asking is meaningful:
 * `Disconnected` ("Connect") and `Error` ("Retry"). `Scanning`/`Connecting`/`Ready` deliberately
 * show no button — see [connectActionLabel]'s own KDoc.
 *
 * Requesting runtime permissions is **not** done here: this composable has no Activity. The
 * caller (`MainActivity`) checks `LinkController.missingPermissions` and prompts at the moment
 * of the tap, exactly as `ConsoleActivity` has done since OBD-19.
 */
@Composable
fun ConnectionBanner(
    connection: LinkState,
    dataFlowing: Boolean = false,
    showConnectionStatus: Boolean = true,
    modifier: Modifier = Modifier,
    onConnect: (() -> Unit)? = null,
) {
    if (connection == LinkState.Ready && !showConnectionStatus) return
    val message = connectionBannerMessage(connection, dataFlowing)
    val tone = bannerTone(connection, dataFlowing)
    val isError = connection is LinkState.Error
    // Only a genuine in-flight attempt (or a just-failed one that may retry) spins; the two
    // steady states (Disconnected, and Ready either way) show a static dot instead — see this
    // file's KDoc.
    val isSpinning = connection == LinkState.Scanning || connection == LinkState.Connecting || isError
    val containerColor =
        if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            toneColor(tone).copy(alpha = PILL_TINT_ALPHA)
        }
    val contentColor =
        if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

    // Outer Row keeps the full width `modifier` (from DashboardScreen: `Modifier.weight(1f)`)
    // reserves in the header — the header-height/width-allocation contract every other header
    // control depends on (see DashboardScreen.kt's round-6/7 fixes) is completely unchanged.
    // The pill itself (background/clip/padding) lives on an INNER Row that hugs its own content
    // and left-aligns within that reserved space, rather than painting a full-bleed bar — see
    // this file's KDoc on the OBD-84 pill-vs-bar redesign.
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("connection-banner")
                .semantics { stateDescription = connectionBannerStateName(connection, dataFlowing) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillContent(connection, message, tone, isSpinning, isError, containerColor, contentColor, onConnect)
    }
}

/**
 * The pill's actual visual content (dot/spinner, message, error affordances) — split out of
 * [ConnectionBanner] purely to keep that composable under detekt's `LongMethod` budget now that
 * OBD-84 added the tone/dot machinery on top of the pre-existing spinner/message/button; the
 * split has no behavioral significance of its own.
 */
@Composable
@Suppress("LongParameterList") // one param per thing this pill's single Row actually renders.
private fun PillContent(
    connection: LinkState,
    message: String,
    tone: BannerTone,
    isSpinning: Boolean,
    isError: Boolean,
    containerColor: Color,
    contentColor: Color,
    onConnect: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(PILL_CORNER_RADIUS_DP.dp))
                .background(containerColor)
                .padding(horizontal = PILL_HORIZONTAL_PADDING_DP.dp, vertical = PILL_VERTICAL_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PILL_SPACING_DP.dp),
    ) {
        if (isSpinning) {
            CircularProgressIndicator(
                modifier = Modifier.size(SPINNER_SIZE_DP.dp),
                color = contentColor,
                strokeWidth = SPINNER_STROKE_DP.dp,
            )
        } else {
            StatusDot(toneColor(tone))
        }
        // OBD-67 round-6 device-verified fix (still load-bearing under OBD-84's pill): this
        // Text must never grow taller from its own content, regardless of what squeezes it —
        // single line + ellipsize is defense-in-depth on top of the header-width fix.
        Text(
            text = message,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("connection-banner-message"),
        )
        if (isError) {
            Text(
                text = "Reconnecting…",
                color = contentColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("connection-banner-reconnecting"),
            )
        }
        val connectLabel = connectActionLabel(connection)
        if (onConnect != null && connectLabel != null) {
            ConnectActionButton(connectLabel, contentColor, onConnect)
        }
    }
}

/** Small color-coded steady-state indicator — the pill's calm alternative to the busy spinner. */
@Composable
private fun StatusDot(color: Color) {
    Row(
        modifier =
            Modifier
                .size(STATUS_DOT_SIZE_DP.dp)
                .clip(CircleShape)
                .background(color)
                .testTag("connection-banner-dot"),
    ) {}
}

/**
 * [tone]'s saturated color — the dot's own color, and (at [PILL_TINT_ALPHA]) the pill's
 * background tint. Deliberately reuses the app's existing threshold-zone vocabulary
 * ([GaugeGreen]/[GaugeAmber]/[GaugeNeutral] — see `ui/theme/Color.kt`) rather than inventing a
 * second palette: green/amber already mean exactly "healthy"/"needs attention" everywhere else
 * in this app. [BannerTone.ERROR] is unreachable here (handled by the stronger `errorContainer`
 * treatment above instead) but included for an exhaustive `when`.
 */
private fun toneColor(tone: BannerTone): Color =
    when (tone) {
        BannerTone.LIVE -> GaugeGreen
        BannerTone.BUSY -> GaugeAmber
        BannerTone.NEUTRAL -> GaugeNeutral
        BannerTone.ERROR -> GaugeNeutral
    }

// OBD-67 round-7 device-verified fix: this Text was the ACTUAL text still wrapping after round
// 6 — round 6 only capped the message and "Reconnecting…" Texts in ConnectionBanner itself,
// missing this one. On the real disconnected/error device state (message + "Reconnecting…" +
// this button all sharing one now-narrower row — see DashboardScreen.kt's round-6 fix reserving
// the Done button's width in every mode), "Retry"/"Connect" wrapped one letter per line,
// ballooning the banner to ~180px tall in BOTH modes (consistent, per round 6, but consistently
// TALL, not the short single row it should be). A button label must never wrap. Split into its
// own composable (rather than inlined in ConnectionBanner) partly to keep that fix's own KDoc
// next to the exact Text it documents, and partly to keep ConnectionBanner itself under
// detekt's LongMethod budget now that every Text in this file carries a line-limit comment.
@Composable
private fun ConnectActionButton(
    label: String,
    contentColor: Color,
    onConnect: () -> Unit,
) {
    TextButton(
        onClick = onConnect,
        modifier = Modifier.testTag("connection-banner-connect"),
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The connect button's label for [connection], or `null` where no button should show. Pure, so
 * the "no second tap while an attempt is in flight" rule is assertable without Compose.
 */
internal fun connectActionLabel(connection: LinkState): String? =
    when (connection) {
        LinkState.Disconnected -> "Connect"
        is LinkState.Error -> "Retry"
        LinkState.Scanning, LinkState.Connecting, LinkState.Ready -> null
    }

/**
 * Lowercase [LinkState] state name, exposed as `stateDescription` for test assertions.
 * [LinkState.Ready] splits by [dataFlowing] (OBD-84) since it's no longer one silent state.
 */
internal fun connectionBannerStateName(
    connection: LinkState,
    dataFlowing: Boolean = false,
): String =
    when (connection) {
        LinkState.Disconnected -> "disconnected"
        LinkState.Scanning -> "scanning"
        LinkState.Connecting -> "connecting"
        LinkState.Ready -> if (dataFlowing) "live" else "waiting"
        is LinkState.Error -> "error"
    }

/**
 * Pill copy per [LinkState]. [LinkState.Ready] (OBD-84) splits by [dataFlowing]: "Live · reading
 * ECU" once fresh data is actually arriving, "Connected · waiting for ECU" when the dongle is
 * paired but nothing fresh has come in yet. Every other state is unchanged from pre-OBD-84.
 */
internal fun connectionBannerMessage(
    connection: LinkState,
    dataFlowing: Boolean = false,
): String =
    when (connection) {
        LinkState.Ready -> if (dataFlowing) "Live · reading ECU" else "Connected · waiting for ECU"
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
