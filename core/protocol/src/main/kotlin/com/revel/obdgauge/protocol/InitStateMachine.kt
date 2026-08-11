package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.ObdLink
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One step of the ELM327 init sequence, and the command that performs it.
 *
 * The order below is the order they are issued. Echo, linefeeds, and spaces are all turned
 * **off** before anything is parsed, so the parser's job shrinks to hex frames; `ATSP0` lets
 * the dongle auto-detect the vehicle's protocol; `0100` proves the detection actually worked
 * by making the ECU answer.
 */
enum class InitStep(
    val command: String,
) {
    /** `ATZ` — full reset. Slow (the dongle reboots) and answers with a version banner. */
    RESET("ATZ"),

    /** `ATE0` — echo off. The dongle may still echo this one command before it takes effect. */
    ECHO_OFF("ATE0"),

    /** `ATL0` — linefeeds off, so responses are `\r`-separated only. */
    LINEFEEDS_OFF("ATL0"),

    /** `ATS0` — spaces off, so `41 05 5A` arrives as `41055A` (fewer bytes over BLE). */
    SPACES_OFF("ATS0"),

    /** `ATSP0` — automatic protocol search. */
    PROTOCOL_AUTO("ATSP0"),

    /** `0100` — supported-PID request; the first command the *vehicle* has to answer. */
    VERIFY("0100"),
}

/**
 * Why an init attempt did not reach a ready link. All are expected conditions in a van
 * (dongle asleep, key off, ECU bus quiet), hence a typed result rather than an exception.
 */
sealed interface InitFailure {
    /**
     * A step got no answer before its timeout.
     *
     * @param step the step that timed out. For [InitStep.RESET] this is reported only after the
     *   single documented retry also timed out.
     */
    data class NoResponse(
        val step: InitStep,
    ) : InitFailure

    /**
     * A step answered, but not acceptably — `?`, a missing `OK`, or an unrecognizable banner.
     *
     * @param step the step that was rejected.
     * @param raw what the dongle actually said, for the debug console and logs.
     */
    data class Rejected(
        val step: InitStep,
        val raw: String,
    ) : InitFailure

    /**
     * The dongle initialized fine but could not talk to the vehicle: `0100` came back
     * `UNABLE TO CONNECT`, `NO DATA`, or a bus error. Typically ignition off, or a dongle in the
     * wrong port.
     *
     * @param raw the `0100` response, for the debug console and logs.
     */
    data class NoProtocol(
        val raw: String,
    ) : InitFailure

    /**
     * The link itself failed mid-sequence — the transport threw (BLE disconnect, GATT error).
     *
     * @param step the step in flight when the link dropped.
     * @param message the transport's diagnostic message.
     */
    data class LinkDown(
        val step: InitStep,
        val message: String,
    ) : InitFailure
}

/** The outcome of one [Elm327InitStateMachine.run] attempt. */
sealed interface InitResult {
    /**
     * Init completed: the dongle is configured and the vehicle answered `0100`.
     *
     * @param banner the `ATZ` version banner, e.g. `"ELM327 v2.1"` — worth logging, since clone
     *   dongles differ in ways that matter during hardware bring-up.
     * @param supportedPidBitmap the four data bytes of the `0100` response, a bitmap of which
     *   PIDs in `0x01..0x20` the ECU implements. See [supports].
     * @param rawVerifyResponse the raw `0100` response text, for the debug console.
     */
    data class Success(
        val banner: String,
        val supportedPidBitmap: List<Int>,
        val rawVerifyResponse: String,
    ) : InitResult {
        /**
         * Whether the ECU advertised support for [pid], per the `0100` bitmap.
         *
         * The bitmap covers `0x01..0x20` only, most-significant bit first: bit 7 of the first
         * byte is PID `0x01`. Anything outside that range (notably baro, `0x33`) returns `false`
         * here — it lives in the `0120`/`0140` bitmaps this machine does not request. A `false`
         * is therefore "not advertised in this bitmap", not "not supported".
         */
        fun supports(pid: Int): Boolean {
            val bitIndex = pid - 1
            val byteIndex = bitIndex / BITS_PER_BYTE
            return if (pid < 1 || byteIndex !in supportedPidBitmap.indices) {
                false
            } else {
                val bit = BITS_PER_BYTE - 1 - (bitIndex % BITS_PER_BYTE)
                (supportedPidBitmap[byteIndex] shr bit) and 1 == 1
            }
        }

        private companion object {
            const val BITS_PER_BYTE = 8
        }
    }

    /** Init did not complete; [failure] says why. */
    data class Failure(
        val failure: InitFailure,
    ) : InitResult
}

/**
 * Per-step timeouts. `ATZ` reboots the dongle and `0100` may sit through a protocol search, so
 * neither fits the default 2 s that suits a plain AT command.
 *
 * @param reset timeout for `ATZ`, applied to each of its two attempts.
 * @param atCommand timeout for the plain configuration commands (`ATE0`…`ATSP0`).
 * @param verify timeout for `0100`, which can spend seconds in `SEARCHING...`.
 */
data class InitTimeouts(
    val reset: Duration = 5.seconds,
    val atCommand: Duration = 2.seconds,
    val verify: Duration = 10.seconds,
)

/**
 * Drives an already-connected [ObdLink] through the ELM327 init sequence:
 * `ATZ` → `ATE0` → `ATL0` → `ATS0` → `ATSP0` → `0100`.
 *
 * **Result, not exceptions.** Every expected failure — no answer, a rejected command, a vehicle
 * that will not talk — comes back as [InitResult.Failure] with a typed [InitFailure]. The only
 * thing that propagates is [CancellationException], because swallowing that would break
 * structured concurrency.
 *
 * **Cancellable and restartable.** [run] keeps no state between calls: every invocation starts
 * at [InitStep.RESET] and re-issues the whole sequence, so a caller can cancel a hung attempt
 * (key-off mid-init, user walks away) and simply call [run] again on a clean machine. This
 * class holds no mutable fields — the sequence state lives entirely on the coroutine's stack.
 *
 * **No retry policy, with one documented exception.** Retrying init is the caller's decision
 * (it owns the backoff and the UI), so this machine does not loop. The exception is a *single*
 * `ATZ` retry after the first command times out: a cold or just-woken ELM327 very commonly
 * swallows the first byte it is sent, and treating that as a hard failure would make the normal
 * first connection of the day fail once every time. The retry is bounded to one attempt, only
 * on [InitStep.RESET], and only for a timeout — a dongle that *answers* wrongly is not retried.
 *
 * Connection setup (scan/connect/GATT) belongs to the [ObdLink] implementation; this machine
 * only sends commands. See `MODULE.md`.
 *
 * @param link the connected link to initialize.
 * @param timeouts per-step timeouts; see [InitTimeouts].
 */
class Elm327InitStateMachine(
    private val link: ObdLink,
    private val timeouts: InitTimeouts = InitTimeouts(),
) {
    /** Runs the full sequence once, from a clean state. Safe to call again after cancellation. */
    suspend fun run(): InitResult =
        when (val reset = performReset()) {
            is ResetOutcome.Failed -> InitResult.Failure(reset.failure)
            is ResetOutcome.Banner -> runAfterReset(reset.text)
        }

    private suspend fun runAfterReset(banner: String): InitResult {
        val failure = CONFIG_STEPS.firstNotNullOfOrNull { step -> performConfigStep(step) }
        return if (failure != null) InitResult.Failure(failure) else verifyProtocol(banner)
    }

    /** `ATZ`, with the single documented retry on a first-command timeout. */
    private suspend fun performReset(): ResetOutcome {
        val first = send(InitStep.RESET, timeouts.reset)
        val outcome = if (first is StepOutcome.TimedOut) send(InitStep.RESET, timeouts.reset) else first
        return when (outcome) {
            is StepOutcome.TimedOut -> ResetOutcome.Failed(InitFailure.NoResponse(InitStep.RESET))
            is StepOutcome.Dropped -> ResetOutcome.Failed(InitFailure.LinkDown(InitStep.RESET, outcome.message))
            is StepOutcome.Answered -> classifyBanner(outcome.raw)
        }
    }

    /** `ATE0`/`ATL0`/`ATS0`/`ATSP0`: each must answer `OK`. Returns `null` when the step passed. */
    private suspend fun performConfigStep(step: InitStep): InitFailure? =
        when (val outcome = send(step, timeouts.atCommand)) {
            is StepOutcome.TimedOut -> InitFailure.NoResponse(step)
            is StepOutcome.Dropped -> InitFailure.LinkDown(step, outcome.message)
            is StepOutcome.Answered ->
                if (acknowledges(outcome.raw)) null else InitFailure.Rejected(step, outcome.raw.trim())
        }

    /** `0100`: the vehicle itself has to answer, with a 4-byte supported-PID bitmap. */
    private suspend fun verifyProtocol(banner: String): InitResult =
        when (val outcome = send(InitStep.VERIFY, timeouts.verify)) {
            is StepOutcome.TimedOut -> InitResult.Failure(InitFailure.NoResponse(InitStep.VERIFY))
            is StepOutcome.Dropped -> InitResult.Failure(InitFailure.LinkDown(InitStep.VERIFY, outcome.message))
            is StepOutcome.Answered -> classifyVerify(banner, outcome.raw)
        }

    private fun classifyVerify(
        banner: String,
        raw: String,
    ): InitResult {
        val parsed =
            ResponseParser.dataBytes(
                raw = raw,
                responseMode = SUPPORTED_PIDS_RESPONSE_MODE,
                pid = SUPPORTED_PIDS_PID,
                expectedCount = SUPPORTED_PIDS_DATA_BYTES,
            )
        return when (parsed) {
            is ParseOutcome.Success -> InitResult.Success(banner, parsed.value, raw.trim())
            is ParseOutcome.Failure ->
                if (parsed.reason.meansNoProtocol()) {
                    InitResult.Failure(InitFailure.NoProtocol(raw.trim()))
                } else {
                    InitResult.Failure(InitFailure.Rejected(InitStep.VERIFY, raw.trim()))
                }
        }
    }

    /**
     * Sends one command, translating transport behaviour into a [StepOutcome].
     *
     * The catch order is load-bearing. [TimeoutCancellationException] is itself a
     * [CancellationException], so it is caught first: an inner `withTimeout` firing is this
     * machine's own signal (the exception carries nothing beyond that fact, hence the swallow),
     * whereas a cancellation from the caller must propagate untouched or structured concurrency
     * breaks. `Exception` is then caught broadly on purpose — [ObdLink.sendRaw]'s contract says
     * implementations throw implementation-defined exceptions on disconnect, and translating
     * those into [InitFailure.LinkDown] is precisely this layer's job.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun send(
        step: InitStep,
        timeout: Duration,
    ): StepOutcome =
        try {
            StepOutcome.Answered(link.sendRaw(step.command, timeout))
        } catch (timedOut: TimeoutCancellationException) {
            StepOutcome.TimedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            StepOutcome.Dropped(e.message ?: e::class.simpleName.orEmpty())
        }

    private companion object {
        val CONFIG_STEPS =
            listOf(
                InitStep.ECHO_OFF,
                InitStep.LINEFEEDS_OFF,
                InitStep.SPACES_OFF,
                InitStep.PROTOCOL_AUTO,
            )
    }
}

/**
 * Validates the `ATZ` banner: the last meaningful line that is not the command's own echo.
 *
 * A banner must identify itself as an ELM327-compatible dongle — it has to contain `ELM` or
 * `OBD` (`"ELM327 v2.1"` on a genuine chip, `"ELM327 v1.5"` or an `OBDII ...` string on the
 * clones this app will actually meet, since emulating an ELM327 is the entire point of a clone).
 * Anything else — silence, `?`, an error line, or garbage — is [InitFailure.Rejected] with the
 * raw text attached, so the debug console shows exactly what the dongle said.
 *
 * This is the one place the machine is deliberately strict about a *cosmetic* response, and it
 * is a two-token constant away from being relaxed: if hardware bring-up (OBD-22) meets a working
 * dongle with an unfamiliar banner, widen [BANNER_TOKENS] rather than dropping the check.
 */
private fun classifyBanner(raw: String): ResetOutcome {
    val banner = meaningfulLines(raw).lastOrNull { !it.equals(InitStep.RESET.command, ignoreCase = true) }
    val normalized = banner?.uppercase()
    val accepted =
        normalized != null &&
            banner != UNKNOWN_COMMAND &&
            !normalized.contains(ERROR_TOKEN) &&
            BANNER_TOKENS.any { normalized.contains(it) }
    return if (accepted) {
        ResetOutcome.Banner(banner)
    } else {
        ResetOutcome.Failed(InitFailure.Rejected(InitStep.RESET, raw.trim()))
    }
}

/**
 * `ATE0` may echo itself once before echo actually goes off, so the check is "contains OK" on the
 * whole response rather than "equals OK" — but an error token anywhere disqualifies it, so an
 * `OK` buried in a failing response cannot pass.
 */
private fun acknowledges(raw: String): Boolean {
    val compact = meaningfulLines(raw).joinToString(separator = "") { it.uppercase().filterNot(Char::isWhitespace) }
    return compact.contains(OK_TOKEN) && !compact.contains(ERROR_TOKEN) && !compact.contains(UNKNOWN_COMMAND)
}

/**
 * `NO DATA`, `UNABLE TO CONNECT`, and bus errors on `0100` all mean the same thing to a driver:
 * the dongle is fine, the vehicle is not answering. Garbage or a wrong frame is a dongle problem
 * instead, and stays [InitFailure.Rejected].
 */
private fun ParseFailure.meansNoProtocol(): Boolean =
    this == ParseFailure.NoData || this == ParseFailure.UnableToConnect || this is ParseFailure.BusError

private fun meaningfulLines(raw: String): List<String> =
    raw
        .split('\r', '\n')
        .map { it.replace(PROMPT, "").trim() }
        .filter { it.isNotEmpty() }

/** How one [Elm327InitStateMachine] step ended, before it is interpreted per step. */
private sealed interface StepOutcome {
    data class Answered(
        val raw: String,
    ) : StepOutcome

    data object TimedOut : StepOutcome

    data class Dropped(
        val message: String,
    ) : StepOutcome
}

/** The `ATZ` step's outcome: a usable banner, or a typed failure. */
private sealed interface ResetOutcome {
    data class Banner(
        val text: String,
    ) : ResetOutcome

    data class Failed(
        val failure: InitFailure,
    ) : ResetOutcome
}

private const val SUPPORTED_PIDS_RESPONSE_MODE = 0x41
private const val SUPPORTED_PIDS_PID = 0x00
private const val SUPPORTED_PIDS_DATA_BYTES = 4

/** Substrings that mark an `ATZ` response as a usable dongle banner. See `classifyBanner`. */
private val BANNER_TOKENS = listOf("ELM", "OBD")

private const val OK_TOKEN = "OK"
private const val ERROR_TOKEN = "ERROR"
private const val UNKNOWN_COMMAND = "?"
private const val PROMPT = ">"
