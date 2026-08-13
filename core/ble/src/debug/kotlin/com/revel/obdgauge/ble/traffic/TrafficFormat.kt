package com.revel.obdgauge.ble.traffic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders a [TrafficEntry] as exactly one logcat line (OBD-48). Debug source set only — see
 * `LogcatTrafficLog`.
 *
 * ### One entry, one line, always
 * A capture is read back with `grep`/`awk` after the drive, so an ELM327 response — which is
 * full of `\r` and may carry several ECU lines — is escaped rather than emitted raw. A response
 * that broke itself across four logcat records would be four records that no longer say which
 * command they answered.
 *
 * ### Timestamps
 * Logcat stamps its own, but only at the resolution and clock its buffer chose, and a capture
 * that gets copied out of `adb logcat` into a fixture keeps nothing. The millisecond stamp here
 * travels with the line.
 */
internal object TrafficFormat {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun format(
        entry: TrafficEntry,
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = "${stamp(epochMillis, zone)} ${body(entry)}"

    private fun stamp(
        epochMillis: Long,
        zone: ZoneId,
    ): String = TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    private fun body(entry: TrafficEntry): String =
        when (entry) {
            is TrafficEntry.Tx -> "TX  ${escape(entry.command)}"
            is TrafficEntry.Rx -> "RX  ${escape(entry.text)}${suffix(entry.fate)}"
            is TrafficEntry.Link -> "==  ${entry.state}"
            is TrafficEntry.Note -> "--  ${escape(entry.text)}"
        }

    private fun suffix(fate: RxFate): String =
        when (fate) {
            RxFate.DELIVERED -> ""
            RxFate.DISCARDED_AS_DEBT -> "   [dropped: paid response debt]"
            RxFate.DISCARDED_UNSOLICITED -> "   [dropped: unsolicited]"
        }

    /** Control characters become visible escapes so the line survives the logcat buffer intact. */
    private fun escape(text: String): String =
        buildString(text.length) {
            for (character in text) {
                when {
                    character == '\r' -> append("\\r")
                    character == '\n' -> append("\\n")
                    character.code < FIRST_PRINTABLE -> append("\\x%02X".format(character.code))
                    else -> append(character)
                }
            }
        }

    private const val FIRST_PRINTABLE = 0x20
}
