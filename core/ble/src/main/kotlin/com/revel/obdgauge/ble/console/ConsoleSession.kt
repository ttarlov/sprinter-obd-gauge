package com.revel.obdgauge.ble.console

import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.ObdLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Forgets whatever device a link fast-path-remembers. [ObdLink] itself has no such concept —
 * it's `BleObdLink.forgetRememberedDevice`, specific to `:core:ble`'s implementation — so this
 * is a narrow seam [ConsoleSession] can be wired to without depending on that concrete type and
 * losing testability against `FakeObdLink`.
 */
fun interface RememberedDeviceForgetter {
    suspend fun forget()
}

/**
 * Pure controller for the OBD-19 debug-console REPL: scrollback plus raw command dispatch over
 * an [ObdLink]. No Android imports anywhere in this file — the whole class is exercised against
 * `FakeObdLink` on the JVM; `app/src/debug/` is the only place that touches an Activity or
 * Compose.
 *
 * ### In-flight policy: refuse, don't queue
 * The dongle is half-duplex and `FakeObdLink` enforces that strictly (it throws if a second
 * `sendRaw` arrives mid-flight — see its KDoc), so queueing here would still have to serialize
 * against the same single-flight link underneath. Rather than hide that ordering from the
 * operator, [sendCommand] **refuses** a second command outright while one is in flight, logging
 * an [ConsoleEntry.ErrorOccurred] immediately: someone typing raw AT commands at a dongle during
 * hardware bring-up benefits far more from instant, honest feedback ("that didn't go out, try
 * again") than from a silent queue whose ordering they can't see on screen. The UI additionally
 * disables its send affordance while [commandInFlight] is true, so this refusal is a safety net
 * for chip-taps and test races, not the primary defense.
 *
 * @param link the connection to drive. Real usage wires `BleObdLink` (via its `ObdLink`
 *   binding); tests wire `FakeObdLink`.
 * @param scope collects [ObdLink.state] into scrollback for the session's lifetime. This
 *   collector never completes on its own ([ObdLink.state] is an unbounded `StateFlow`), so it
 *   must be a scope that tolerates a permanently-running child — a `ViewModel`'s
 *   `viewModelScope` in production, `TestScope.backgroundScope` (never the test scope itself)
 *   under `runTest`.
 * @param forgetter optional "forget remembered device" seam; `null` if the wired link has no
 *   such concept — see [RememberedDeviceForgetter].
 * @param historyLimit the scrollback is trimmed to at most this many entries, oldest dropped
 *   first.
 * @param clock timestamps entries; injected so tests can fix it.
 */
class ConsoleSession(
    private val link: ObdLink,
    scope: CoroutineScope,
    private val forgetter: RememberedDeviceForgetter? = null,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutableEntries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val entries: StateFlow<List<ConsoleEntry>> = mutableEntries.asStateFlow()

    /** Passthrough of the driven link's own state; nothing about it is console-specific. */
    val linkState: StateFlow<LinkState> = link.state

    private val mutableInFlight = MutableStateFlow(false)
    val commandInFlight: StateFlow<Boolean> = mutableInFlight.asStateFlow()

    /** Non-suspending: guards [sendCommand] against a concurrent call without blocking either. */
    private val commandLock = Mutex()

    init {
        // Logs every value link.state ever holds, including the one it starts at — a debug
        // console opened against an already-connected (or already-erroring) link should show
        // that immediately rather than sitting blank until the next real transition. A
        // "skip the first value" filter was considered and rejected: StateFlow always replays
        // its *current* value to a new collector, so if this collector's coroutine happened to
        // start late (after a real transition already landed), such a filter would silently
        // discard that transition instead of the ambient one — a race, not a rule.
        link.state
            .onEach { state -> appendEntry(ConsoleEntry.LinkStateChanged(now(), state)) }
            .launchIn(scope)
    }

    /** Delegates to [ObdLink.connect]; any resulting state change is logged automatically. */
    suspend fun connect() = link.connect()

    /** Delegates to [ObdLink.disconnect]; any resulting state change is logged automatically. */
    suspend fun disconnect() = link.disconnect()

    /**
     * Forgets the remembered device via [forgetter], or records an [ConsoleEntry.ErrorOccurred]
     * if this session's link has none.
     */
    suspend fun forgetRememberedDevice() {
        val active = forgetter
        if (active == null) {
            recordError("forget remembered device: not supported by this link")
            return
        }
        active.forget()
    }

    /**
     * Sends [command] raw and appends its outcome to [entries]: a [ConsoleEntry.CommandSent]
     * immediately, followed by exactly one of [ConsoleEntry.ResponseReceived] or
     * [ConsoleEntry.ErrorOccurred].
     *
     * Returns `true` if a response was received, `false` for every other outcome (refused,
     * timed out, threw) — a convenience for callers that want a quick success signal without
     * inspecting [entries] themselves. A genuine cancellation of the calling coroutine (not a
     * [ObdLink.sendRaw] timeout — see below) propagates normally rather than being swallowed.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    // ReturnCount: guard clauses (refuse-if-busy, then one branch per catch) ARE the fix here —
    // see BleObdLink.openSession's identical justification for the same rule.
    // TooGenericExceptionCaught: ObdLink.sendRaw's KDoc leaves its failure exception type
    // implementation-defined ("@throws Exception (implementation-defined) on timeout or
    // disconnect"), and this class is typed against that interface, not against any one
    // implementation — FakeObdLink throws IllegalStateException/ObdLinkDisconnectedException,
    // BleObdLink throws BleLinkException. Catching a specific subtype here would silently miss
    // whichever implementation doesn't throw it; `Exception` is the honest catch for an
    // interface whose own contract is this loose.
    suspend fun sendCommand(
        command: String,
        timeout: Duration = DEFAULT_COMMAND_TIMEOUT,
    ): Boolean {
        if (!commandLock.tryLock()) {
            recordError("refused: another command is in flight", command)
            return false
        }
        mutableInFlight.value = true
        appendEntry(ConsoleEntry.CommandSent(now(), command))
        try {
            val response = link.sendRaw(command, timeout)
            appendEntry(ConsoleEntry.ResponseReceived(now(), command, response))
            return true
        } catch (timedOut: TimeoutCancellationException) {
            // ObdLink.sendRaw's timeout behavior is implementation-defined (see its KDoc):
            // FakeObdLink lets `withTimeout`'s own TimeoutCancellationException escape, while
            // BleObdLink translates its timeout into BleLinkException(LinkError.Timeout) before
            // it ever reaches a caller. Both are ordinary command failures, not this
            // coroutine's own cancellation, so neither is rethrown — the session stays usable
            // for the next command either way. The exception's own message rides along rather
            // than being silently dropped, even though it's rarely more specific than the
            // fixed prefix.
            recordError("timeout waiting for response (${timedOut.message})", command)
            return false
        } catch (cancellation: CancellationException) {
            // A genuine cancellation of the caller (e.g. the owning scope was torn down) is
            // NOT a command failure to log — propagate it so structured concurrency still works.
            throw cancellation
        } catch (failure: Exception) {
            recordError(failure.message ?: failure.toString(), command)
            return false
        } finally {
            mutableInFlight.value = false
            commandLock.unlock()
        }
    }

    /** Appends an [ConsoleEntry.ErrorOccurred] with the session's clock; not tied to a command. */
    fun recordError(
        message: String,
        command: String? = null,
    ) {
        appendEntry(ConsoleEntry.ErrorOccurred(now(), command, message))
    }

    private fun now(): Instant = clock.instant()

    private fun appendEntry(entry: ConsoleEntry) {
        mutableEntries.update { (it + entry).takeLast(historyLimit) }
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 500
        val DEFAULT_COMMAND_TIMEOUT: Duration = 5.seconds
    }
}
