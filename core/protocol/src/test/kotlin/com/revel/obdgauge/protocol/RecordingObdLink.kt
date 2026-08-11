package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.ObdLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * An [ObdLink] decorator that records every command sent, and can stall one of them forever.
 *
 * The recording makes step *ordering* assertable (the init sequence's whole point) and proves
 * the retry policy: exactly one extra `ATZ` on a first-command timeout, and no retry anywhere
 * else. The stall makes cancellation testable without a real timeout — [stallCommand] suspends
 * indefinitely *before* delegating, so the wrapped `FakeObdLink` never sees a command in flight
 * and its half-duplex guard stays meaningful.
 */
class RecordingObdLink(
    private val delegate: ObdLink,
) : ObdLink {
    private val recorded = mutableListOf<String>()

    /** Every command passed to [sendRaw], in order, across all attempts. */
    val commands: List<String> get() = recorded.toList()

    /** When set, [sendRaw] of this command suspends forever instead of answering. */
    var stallCommand: String? = null

    override val state: StateFlow<LinkState> get() = delegate.state

    override suspend fun connect() = delegate.connect()

    override suspend fun disconnect() = delegate.disconnect()

    override suspend fun sendRaw(
        command: String,
        timeout: Duration,
    ): String {
        recorded += command
        if (command.equals(stallCommand, ignoreCase = true)) {
            delay(Duration.INFINITE)
        }
        return delegate.sendRaw(command, timeout)
    }
}
