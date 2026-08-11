package com.revel.obdgauge.app.console

import com.revel.obdgauge.ble.console.ConsoleEntry
import com.revel.obdgauge.model.LinkState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** `HH:mm:ss.SSS` in the device's local zone — precise enough to see command/response pairing. */
internal fun formatConsoleTimestamp(timestamp: Instant): String =
    TIMESTAMP_FORMAT.format(timestamp.atZone(ZoneId.systemDefault()))

/**
 * One scrollback line's body text, `\r`-joined multi-line responses rendered as real newlines.
 * Pure and Compose-free so it's trivial to assert against directly.
 */
internal fun formatConsoleEntryBody(entry: ConsoleEntry): String =
    when (entry) {
        is ConsoleEntry.CommandSent -> "> ${entry.command}"
        is ConsoleEntry.ResponseReceived -> entry.response.replace("\r", "\n")
        is ConsoleEntry.LinkStateChanged -> "-- link: ${formatLinkStateName(entry.state)} --"
        is ConsoleEntry.ErrorOccurred ->
            if (entry.command != null) {
                "! [${entry.command}] ${entry.message}"
            } else {
                "! ${entry.message}"
            }
    }

internal fun formatLinkStateName(state: LinkState): String =
    when (state) {
        LinkState.Disconnected -> "disconnected"
        LinkState.Scanning -> "scanning"
        LinkState.Connecting -> "connecting"
        LinkState.Ready -> "ready"
        is LinkState.Error -> "error (${state.cause})"
    }
