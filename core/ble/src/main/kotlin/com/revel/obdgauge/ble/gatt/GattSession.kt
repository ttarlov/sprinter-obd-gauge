package com.revel.obdgauge.ble.gatt

import com.revel.obdgauge.ble.BleConfig
import com.revel.obdgauge.ble.BleLinkException
import com.revel.obdgauge.ble.BleLogger
import com.revel.obdgauge.model.LinkError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** Result of bringing one GATT connection up to a usable serial link. */
internal sealed interface SessionOutcome {
    data class Ready(
        val profile: SerialProfile,
        val mtu: Int,
    ) : SessionOutcome

    data class Failed(
        val error: LinkError,
    ) : SessionOutcome
}

/**
 * One GATT connection, driven as a serial port.
 *
 * Owns the connect handshake (connect → discover → probe → CCCD → MTU), the request/response
 * exchange, and the teardown. Everything it decides is derived from [GattEvent]s, never from
 * framework state, so the whole class runs against a scripted [GattTransport] in a unit test.
 *
 * ### Threading
 * The BLE stack delivers callbacks on binder threads. Those threads do exactly one thing here:
 * `trySend` the event onto an unbounded [Channel]. A single consumer coroutine, running on the
 * caller-supplied link dispatcher, drains that channel and is the only place mutable session
 * state is ever touched. Suspending callers ([open], [send]) must run on the same dispatcher —
 * `BleObdLink` guarantees that with `withContext`. The result is that "callback state" and
 * "coroutine state" are the same state, on one thread, with no locks and no visibility games.
 *
 * ### Waiting
 * Every framework request has a timeout, because the BLE stack's characteristic failure is not
 * an error code but silence. A pending wait is resolved exactly one of four ways: the matching
 * event arrives, the timeout expires, the link drops (all waiters fail with the drop's typed
 * error), or the caller is cancelled.
 *
 * No reconnect logic lives here — a dropped link stays dropped until someone calls connect
 * again. Auto-reconnect is OBD-23.
 */
@Suppress("TooManyFunctions") // 12 vs threshold 11: the debt-settlement pair (settleDebts +
// settleGateIfClear, round-2 R2-1) tipped it. Each function is small and single-purpose;
// splitting the session would separate state from the only code allowed to touch it.
internal class GattSession(
    private val transport: GattTransport,
    private val scope: CoroutineScope,
    private val config: BleConfig,
    private val logger: BleLogger,
) {
    private val events = Channel<GattEvent>(Channel.UNLIMITED)
    private val assembler = ResponseAssembler()
    private val stepAwaiters = mutableMapOf<Step, CompletableDeferred<Int>>()
    private val listener = GattEventListener { event -> events.trySend(event) }

    private var responseAwaiter: CompletableDeferred<String>? = null
    private var profile: SerialProfile? = null
    private var negotiatedMtu = BleConfig.DEFAULT_MTU
    private var ready = false
    private var terminated = false
    private var eventLoop: Job? = null

    /**
     * Responses the dongle still owes us for commands we gave up on.
     *
     * A `sendRaw` timeout does not un-send the command — a `SEARCHING...` that runs past two
     * seconds is ordinary van behaviour, and the answer arrives afterwards regardless. Without
     * this counter that late answer satisfies the *next* command's wait, and every reading from
     * then on is the previous command's: a permanent off-by-one that never self-corrects and
     * stays plausible while being wrong. So the debt is tracked and [deliverData] pays it off by
     * discarding that many responses before honouring a new awaiter.
     *
     * Incremented only when the command's terminating `\r` was actually accepted by the
     * transport. Over-counting eats the next good response; under-counting resurrects the
     * off-by-one. Either way the damage is bounded now: [settleDebts] gives an owed event one
     * quiet window to arrive before presuming it lost (round-2 review R2-1).
     */
    private var responseDebt = 0

    /**
     * The same accounting for write acknowledgements. A chunk whose ack times out is still on
     * the stack's queue, and its late `onCharacteristicWrite` must not be mistaken for the ack
     * of the next command's first chunk.
     */
    private var writeAckDebt = 0

    /** Whether the command being sent had its terminator accepted by the transport. */
    private var commandOnTheWire = false

    /**
     * Invoked once, on the link dispatcher, if the link drops *after* reaching Ready. A failure
     * during the handshake is reported through [open]'s return value instead.
     */
    var onTerminated: ((LinkError) -> Unit)? = null

    /** Largest command chunk that fits one ATT write at the negotiated MTU. */
    val maxPayloadBytes: Int
        get() = (negotiatedMtu - BleConfig.ATT_HEADER_BYTES).coerceAtLeast(1)

    /** Runs the full handshake. Never throws for an expected failure — see [SessionOutcome]. */
    suspend fun open(): SessionOutcome =
        try {
            eventLoop = scope.launch { for (event in events) handleEvent(event) }
            awaitStep(Step.CONNECTION, config.connectTimeout) { transport.open(listener) }
            awaitStep(Step.SERVICES, config.discoverTimeout) { transport.discoverServices() }

            val discovered = transport.services()
            logger.log("discovered ${discovered.size} service(s) on ${transport.address}")
            val selected =
                SerialProfileProbe.probe(discovered) ?: throw BleLinkException(
                    LinkError.Unknown("no writable + notifiable characteristic pair on ${transport.address}"),
                )
            logger.log("serial profile chosen: ${selected.describe()}")

            awaitStep(Step.NOTIFICATIONS, config.descriptorTimeout) { transport.enableNotifications(selected) }
            negotiateMtu()

            profile = selected
            ready = true
            SessionOutcome.Ready(selected, negotiatedMtu)
        } catch (failure: BleLinkException) {
            logger.log("connect handshake failed: ${failure.error}")
            SessionOutcome.Failed(failure.error)
        }

    /**
     * Writes `command + \r` and suspends until the assembled `>`-terminated response arrives.
     *
     * Assumes single-flight — `BleObdLink` holds the mutex. Cancellation-safe: a cancelled call
     * drops its awaiter and leaves the link usable for the next command.
     */
    suspend fun send(
        command: String,
        timeout: Duration,
    ): String {
        val target =
            profile?.takeIf { ready }
                ?: throw BleLinkException(LinkError.Unknown("link is not ready for \"$command\""))
        settleDebts()
        val deferred = CompletableDeferred<String>()
        responseAwaiter = deferred
        commandOnTheWire = false
        assembler.reset()
        return try {
            writeCommand(target, command)
            withTimeout(timeout) { deferred.await() }
        } catch (timedOut: TimeoutCancellationException) {
            throw BleLinkException(LinkError.Timeout, "no response to \"$command\" within $timeout", timedOut)
        } finally {
            if (responseAwaiter === deferred) {
                responseAwaiter = null
                if (!deferred.isCompleted && commandOnTheWire) {
                    responseDebt++
                    logger.log("\"$command\" abandoned with its answer still owed; debt=$responseDebt")
                }
            }
        }
    }

    /**
     * Bounds the debt bookkeeping (round-2 review R2-1): a debt is a *prediction* that a late
     * event will arrive, and when it never does — a truncated response with no `>`, a dongle
     * reset — an unexpiring debt eats every subsequent good response forever. So before a new
     * command is written, any outstanding debt gets one quiet window to settle: a late arrival
     * during the wait is absorbed by the normal handlers, and whatever is still owed afterwards
     * is presumed lost and cleared. Worst case cost of a lost event is one quiet window, never
     * the link.
     */
    private suspend fun settleDebts() {
        if (responseDebt == 0 && writeAckDebt == 0) {
            return
        }
        logger.log("waiting ${config.debtQuietWindow} for owed events (responses=$responseDebt acks=$writeAckDebt)")
        val gate = CompletableDeferred<Unit>()
        debtsSettled = gate
        withTimeoutOrNull(config.debtQuietWindow) { gate.await() }
        debtsSettled = null
        if (responseDebt > 0 || writeAckDebt > 0) {
            logger.log("presumed lost after quiet window: responses=$responseDebt acks=$writeAckDebt — debts cleared")
            responseDebt = 0
            writeAckDebt = 0
        }
    }

    /** Completed by the event handlers the moment the last owed event arrives; see [settleDebts]. */
    private var debtsSettled: CompletableDeferred<Unit>? = null

    private fun settleGateIfClear() {
        if (responseDebt == 0 && writeAckDebt == 0) {
            debtsSettled?.complete(Unit)
        }
    }

    /** Tears the connection down and fails anything still waiting. Idempotent. */
    fun close() {
        ready = false
        terminated = true
        failPending(BleLinkException(LinkError.Unknown("link closed")))
        eventLoop?.cancel()
        events.close()
        transport.close()
    }

    private fun handleEvent(event: GattEvent) {
        when (event) {
            is GattEvent.Connected -> stepAwaiters.completeStep(Step.CONNECTION, event.status)
            is GattEvent.ServicesDiscovered -> stepAwaiters.completeStep(Step.SERVICES, event.status)
            is GattEvent.NotificationsEnabled -> stepAwaiters.completeStep(Step.NOTIFICATIONS, event.status)
            // A late ack owed by an abandoned command is absorbed here, so it cannot be mistaken
            // for the acknowledgement of the chunk currently in flight.
            is GattEvent.WriteCompleted ->
                if (writeAckDebt > 0) {
                    writeAckDebt--
                    logger.log("paid write-ack debt (status=${event.status})")
                    settleGateIfClear()
                } else {
                    stepAwaiters.completeStep(Step.WRITE, event.status)
                }
            is GattEvent.MtuChanged -> {
                if (event.status == GATT_SUCCESS) {
                    negotiatedMtu = event.mtu
                }
                stepAwaiters.completeStep(Step.MTU, event.status)
            }
            is GattEvent.DataReceived -> deliverData(event.bytes)
            is GattEvent.Disconnected -> terminate(event.status)
        }
    }

    private fun deliverData(bytes: ByteArray) {
        for (response in assembler.append(bytes)) {
            val awaiter = responseAwaiter
            when {
                responseDebt > 0 -> {
                    responseDebt--
                    logger.log("paid response debt, discarded: ${response.take(LOG_SNIPPET_CHARS)}")
                    settleGateIfClear()
                }
                awaiter == null ->
                    logger.log("discarded unsolicited response: ${response.take(LOG_SNIPPET_CHARS)}")
                else -> {
                    responseAwaiter = null
                    awaiter.complete(response)
                }
            }
        }
    }

    private fun terminate(status: Int) {
        if (terminated) {
            return
        }
        terminated = true
        val error =
            if (status == GATT_SUCCESS) {
                LinkError.Unknown("dongle disconnected")
            } else {
                LinkError.Gatt(status)
            }
        val wasReady = ready
        ready = false
        logger.log("GATT link to ${transport.address} dropped: $error")
        failPending(BleLinkException(error, "link dropped: $error"))
        if (wasReady) {
            onTerminated?.invoke(error)
        }
    }

    private fun failPending(failure: BleLinkException) {
        val pending = stepAwaiters.values.toList()
        stepAwaiters.clear()
        pending.forEach { it.completeExceptionally(failure) }
        responseAwaiter?.completeExceptionally(failure)
        responseAwaiter = null
    }

    private suspend fun awaitStep(
        step: Step,
        timeout: Duration,
        action: () -> Boolean,
    ) {
        val deferred = CompletableDeferred<Int>()
        stepAwaiters[step] = deferred
        val error =
            try {
                if (action()) {
                    statusError(withTimeoutOrNull(timeout) { deferred.await() })
                } else {
                    LinkError.Unknown("$step was rejected by the BLE stack")
                }
            } finally {
                stepAwaiters.remove(step)
            }
        if (error != null) {
            throw BleLinkException(error, "$step failed: $error")
        }
    }

    /**
     * MTU negotiation is best-effort by design: a dongle that refuses (or ignores) the request
     * still works at the 23-byte default, just with more notifications per response — which the
     * assembler handles anyway. A drop during the request is not tolerated, only a rejection.
     */
    private suspend fun negotiateMtu() {
        try {
            awaitStep(Step.MTU, config.mtuTimeout) { transport.requestMtu(BleConfig.REQUESTED_MTU) }
            logger.log("MTU negotiated: $negotiatedMtu (payload ${maxPayloadBytes}B)")
        } catch (rejected: BleLinkException) {
            if (terminated) {
                throw rejected
            }
            logger.log("MTU request not honoured (${rejected.error}); staying at $negotiatedMtu")
        }
    }

    /**
     * Splits the command across ATT writes, **never issuing a chunk while another is unacked**.
     * The ack is the only flow control BLE offers, and on API 33+ an overlapped write is
     * rejected outright with `ERROR_GATT_WRITE_REQUEST_BUSY` — so proceeding without it is not
     * tolerance, it is data loss on every modern device.
     *
     * An ack that never comes therefore fails the whole command with [LinkError.Timeout] (the
     * link stays Ready — one bad command is not a dead link) and books a [writeAckDebt] so the
     * late ack cannot be mistaken for the next command's.
     */
    private suspend fun writeCommand(
        target: SerialProfile,
        command: String,
    ) {
        val payload = (command + COMMAND_TERMINATOR).toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxPayloadBytes, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            val isFinalChunk = end == payload.size
            try {
                awaitStep(Step.WRITE, config.writeTimeout) {
                    transport.write(target, chunk).also { accepted ->
                        // Accepted means the stack owns the bytes; the terminator being out of
                        // our hands is what makes a response expected, ack or no ack.
                        if (accepted && isFinalChunk) {
                            commandOnTheWire = true
                        }
                    }
                }
            } catch (failure: BleLinkException) {
                if (failure.error == LinkError.Timeout) {
                    writeAckDebt++
                    logger.log("write ack timed out at offset $offset; failing the command")
                }
                throw failure
            }
            offset = end
        }
    }

    private companion object {
        /** ELM327 commands are terminated by a carriage return, never a newline. */
        const val COMMAND_TERMINATOR = "\r"
        const val LOG_SNIPPET_CHARS = 40
    }
}

/** The framework requests [GattSession] waits on, one at a time each. */
private enum class Step { CONNECTION, SERVICES, NOTIFICATIONS, MTU, WRITE }

private fun MutableMap<Step, CompletableDeferred<Int>>.completeStep(
    step: Step,
    status: Int,
) {
    remove(step)?.complete(status)
}

private fun statusError(status: Int?): LinkError? =
    when (status) {
        null -> LinkError.Timeout
        GATT_SUCCESS -> null
        else -> LinkError.Gatt(status)
    }
