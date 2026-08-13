package com.revel.obdgauge.ble.traffic

import com.revel.obdgauge.model.LinkState

/**
 * One ordered tap of everything crossing the link (OBD-48).
 *
 * ### Why this exists
 * Hardware session 1 (`docs/hardware/session-2026-08-12.md`) lost an entire drive capture: the
 * OBD-19 console renders traffic on screen, the phone locked mid-drive, and fifteen poll cycles
 * went into a dark screen. A capture surface that depends on a screen being awake is not a
 * capture surface. This one is a logcat sink, so `adb logcat -s ObdTraffic` records a whole
 * drive with the phone in a pocket.
 *
 * ### It is a tap, not a hook
 * Implementations may only observe. Nothing in `:core:ble` reads a return value, branches on
 * one, or waits for one — the link behaves identically with [NONE] wired, which is what
 * release builds get and what every pre-existing test still runs against.
 *
 * ### Ordering
 * Every call site is on the link dispatcher (see `BleObdLink`'s threading notes), so entries
 * arrive in the order the events happened and TX/RX/link-state interleave truthfully. A sink
 * must not reorder them.
 */
fun interface TrafficLog {
    fun record(entry: TrafficEntry)

    companion object {
        /** Discards everything. What release builds wire, and the default for tests. */
        val NONE: TrafficLog = TrafficLog { }
    }
}

/** One tapped event. */
sealed interface TrafficEntry {
    /** A command handed to the transport, terminator not included. */
    data class Tx(
        val command: String,
    ) : TrafficEntry

    /** One fully assembled, `>`-terminated response, and what the session did with it. */
    data class Rx(
        val text: String,
        val fate: RxFate,
    ) : TrafficEntry

    /** A link lifecycle transition, interleaved with the traffic that surrounds it. */
    data class Link(
        val state: LinkState,
    ) : TrafficEntry

    /** Anything else worth seeing in the capture: a timeout, a drop, a session close. */
    data class Note(
        val text: String,
    ) : TrafficEntry
}

/**
 * What became of a response. A capture that only showed delivered responses would hide exactly
 * the pathology worth catching — the debt bookkeeping discarding an answer, or the dongle
 * talking unprompted.
 */
enum class RxFate {
    /** Handed to the command that was waiting for it. */
    DELIVERED,

    /** Discarded to pay off a response owed by an abandoned command. */
    DISCARDED_AS_DEBT,

    /** Arrived with nobody waiting. */
    DISCARDED_UNSOLICITED,
}
