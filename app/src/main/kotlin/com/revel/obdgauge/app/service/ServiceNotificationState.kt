package com.revel.obdgauge.app.service

import com.revel.obdgauge.app.gauge.DASHBOARD_PIDS_BY_ID
import com.revel.obdgauge.app.gauge.NO_READING_TEXT
import com.revel.obdgauge.app.gauge.formatGaugeValue
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading

/**
 * The persistent notification's content — pure data, no `Notification.Builder` in sight, so
 * [serviceNotificationState] is unit-testable without Robolectric (same split this codebase
 * uses everywhere else: pure formatting in `gauge/GaugeFormatting.kt`, Android glue in
 * `DashboardScreen.kt`/`MainActivity.kt`).
 */
data class ServiceNotificationState(
    val title: String,
    val text: String,
)

/**
 * OBD-24 AC: "Persistent notification shows a live headline reading (e.g. coolant temp) and
 * updates as data changes." Coolant is the headline channel — it's the one gauge every scenario
 * and every real cold-start reliably has a fresh value for (unlike boost, which needs a load
 * event, or the still-unverified trans channel — oil is verified as of OBD-50 — this
 * notification shouldn't be the one place a driver learns to trust a hypothesis number from).
 *
 * @param connection current [LinkState] — drives which message shows; reuses this codebase's
 *   established per-state copy style (`gauge/ConnectionBanner.kt`'s `connectionBannerMessage`),
 *   but can't reuse that function directly: it returns `null` for [LinkState.Ready] (banner
 *   renders nothing once healthy) whereas the notification needs positive content — "quiet
 *   dashboard" doesn't apply to a status bar icon the driver glances at with the screen off.
 * @param coolant the latest coolant [Reading], or `null` before one has arrived.
 *
 * ### Round-1 review fix (B4 MAJOR): no promise this app doesn't keep
 * Every [LinkState.Error] used to render as "Connection error — reconnecting…", but nothing
 * reconnects — [ConnectionServiceController] only re-asserts against `DashboardViewModel`'s own
 * UI-gated `stop()` (a clean [LinkState.Disconnected], never an `Error`); auto-retry on a real
 * error is OBD-23's job and isn't wired yet. Text a driver reads off a dash mount has to be
 * literally true: [LinkError.PermissionDenied] and [LinkError.BluetoothOff] are
 * terminal-until-the-user-acts (no amount of waiting fixes either), so they get their own
 * specific copy telling the driver what to actually do; every other cause gets an honest "lost,
 * open the app" rather than a promise this build can't keep.
 */
fun serviceNotificationState(
    connection: LinkState,
    coolant: Reading?,
): ServiceNotificationState {
    val text =
        when (connection) {
            LinkState.Ready -> "Connected — Coolant ${coolantValueText(coolant)}"
            LinkState.Disconnected -> "Not connected"
            LinkState.Scanning -> "Scanning for dongle…"
            LinkState.Connecting -> "Connecting…"
            is LinkState.Error -> errorText(connection.cause)
        }
    return ServiceNotificationState(title = NOTIFICATION_TITLE, text = text)
}

private fun errorText(cause: LinkError): String =
    when (cause) {
        LinkError.PermissionDenied -> "Bluetooth permission denied — open the app to grant it"
        LinkError.BluetoothOff -> "Bluetooth is off — turn it on to reconnect"
        LinkError.DeviceNotFound, LinkError.Timeout, is LinkError.Gatt, is LinkError.Unknown ->
            "Connection lost — open the app"
    }

private fun coolantValueText(coolant: Reading?): String {
    val unit = DASHBOARD_PIDS_BY_ID.getValue(PidIds.COOLANT).unit
    return coolant?.let { formatGaugeValue(it.value, unit) } ?: NO_READING_TEXT
}

private const val NOTIFICATION_TITLE = "OBD Gauge"
