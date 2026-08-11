package com.revel.obdgauge.ble.console

import com.revel.obdgauge.model.LinkState
import java.time.Instant

/**
 * One line of OBD-19 debug-console scrollback. [ConsoleSession] appends these in chronological
 * order and never mutates one after appending it.
 */
sealed interface ConsoleEntry {
    /** When this entry was recorded, per [ConsoleSession]'s injected clock. */
    val timestamp: Instant

    /** A raw command the user (directly, or via a quick-command chip) sent. */
    data class CommandSent(
        override val timestamp: Instant,
        val command: String,
    ) : ConsoleEntry

    /** The full response to [command], already reassembled by the link (`>` stripped). */
    data class ResponseReceived(
        override val timestamp: Instant,
        val command: String,
        val response: String,
    ) : ConsoleEntry

    /** The driven [com.revel.obdgauge.model.ObdLink]'s [LinkState] changed to [state]. */
    data class LinkStateChanged(
        override val timestamp: Instant,
        val state: LinkState,
    ) : ConsoleEntry

    /**
     * Something did not complete normally: a timeout, a link exception, a refused command (see
     * [ConsoleSession]'s in-flight policy), or an unsupported action. [command] is `null` when
     * the error isn't tied to a specific command — e.g. "forget remembered device" on a link
     * with no such concept, or a denied runtime permission.
     */
    data class ErrorOccurred(
        override val timestamp: Instant,
        val command: String?,
        val message: String,
    ) : ConsoleEntry
}
