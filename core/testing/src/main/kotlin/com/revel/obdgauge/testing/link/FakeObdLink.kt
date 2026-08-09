package com.revel.obdgauge.testing.link

import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.ObdLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Deterministic [ObdLink] fake that replays a fixed [TranscriptEntry] list keyed by command,
 * with configurable per-command latency and injectable [Fault]s — so `:core:protocol` (and
 * `:core:ble`) can be tested without a real dongle. See `MODULE.md` for the shipped fixture.
 *
 * Matching is by command, not position: [transcript] entries are grouped by (uppercased)
 * command, and each `sendRaw` call for that command consumes the next queued response — once a
 * command's queue is down to one entry, that last response repeats for every further call
 * (steady-state polling, e.g. repeated `0105` reads). A command absent from [transcript] gets
 * ELM327's own unknown-command reply, `"?"`.
 *
 * **Half-duplex is enforced strictly, not tolerantly.** A production [ObdLink]'s contract
 * requires it to transparently serialize concurrent [sendRaw] calls; this fake instead THROWS
 * an [IllegalStateException] if a second [sendRaw] arrives while one is in flight. It exists to
 * catch protocol-scheduler bugs, not to paper over them — see the [ObdLink] KDoc.
 *
 * @param transcript the request/response script to replay; see [TranscriptParser].
 * @param defaultLatency delay applied before responding, when [commandLatency] has no override
 *   for the command.
 * @param commandLatency per-command latency overrides, keyed like [transcript]'s commands
 *   (case-insensitive).
 * @param commandFaults per-command fault queues: the Nth call to that command consumes the Nth
 *   fault, then falls back to the scripted response once the queue is empty.
 * @param positionFaults faults keyed by 1-indexed global `sendRaw` call number, checked before
 *   [commandFaults].
 * @param connectLatency delay [connect] waits before moving to [LinkState.Ready].
 */
class FakeObdLink(
    transcript: List<TranscriptEntry>,
    private val defaultLatency: Duration = Duration.ZERO,
    private val commandLatency: Map<String, Duration> = emptyMap(),
    commandFaults: Map<String, List<Fault>> = emptyMap(),
    private val positionFaults: Map<Int, Fault> = emptyMap(),
    private val connectLatency: Duration = Duration.ZERO,
) : ObdLink {
    private val responseQueues: Map<String, ArrayDeque<String>> = buildResponseQueues(transcript)
    private val faultQueues: Map<String, ArrayDeque<Fault>> =
        commandFaults.mapValues { (_, faults) -> ArrayDeque(faults) }

    private val callCount = AtomicInteger(0)
    private val inFlight = AtomicBoolean(false)

    private val mutableState = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = mutableState.asStateFlow()

    override suspend fun connect() {
        mutableState.value = LinkState.Connecting
        delay(connectLatency)
        mutableState.value = LinkState.Ready
    }

    override suspend fun disconnect() {
        mutableState.value = LinkState.Disconnected
    }

    override suspend fun sendRaw(
        command: String,
        timeout: Duration,
    ): String {
        check(inFlight.compareAndSet(false, true)) {
            "FakeObdLink: sendRaw(\"$command\") called while another command is in flight (half-duplex violation)"
        }
        try {
            val position = callCount.incrementAndGet()
            val normalized = command.uppercase()
            val fault = positionFaults[position] ?: faultQueues[normalized]?.removeFirstOrNull()
            val latency = commandLatency[normalized] ?: defaultLatency

            return withTimeout(timeout) {
                delay(latency)
                respond(command, normalized, fault, timeout)
            }
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun respond(
        command: String,
        normalized: String,
        fault: Fault?,
        timeout: Duration,
    ): String =
        when (fault) {
            Fault.Garbage -> GARBAGE_RESPONSE
            Fault.NoData -> "NO DATA"
            Fault.Stopped -> "STOPPED"
            Fault.Timeout -> {
                delay(timeout * TIMEOUT_OVERSHOOT_FACTOR)
                error("unreachable: withTimeout should have cancelled this delay")
            }
            Fault.MidResponseDisconnect -> {
                mutableState.value = LinkState.Error(LinkError.Unknown("mid-response disconnect (fake)"))
                throw ObdLinkDisconnectedException("connection dropped mid-response for \"$command\"")
            }
            null -> nextScriptedResponse(normalized)
        }

    private fun nextScriptedResponse(normalized: String): String {
        val queue = responseQueues[normalized] ?: return UNKNOWN_COMMAND_RESPONSE
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    private companion object {
        const val GARBAGE_RESPONSE = "G@RB13D//FRAME??"
        const val UNKNOWN_COMMAND_RESPONSE = "?"
        const val TIMEOUT_OVERSHOOT_FACTOR = 2

        fun buildResponseQueues(transcript: List<TranscriptEntry>): Map<String, ArrayDeque<String>> {
            val queues = mutableMapOf<String, ArrayDeque<String>>()
            for (entry in transcript) {
                queues.getOrPut(entry.command.uppercase()) { ArrayDeque() }.addLast(entry.response)
            }
            return queues
        }
    }
}
