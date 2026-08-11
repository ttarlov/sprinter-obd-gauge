package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.ObdLink
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** What one raw command produced: the dongle's text, or the reason there was none. */
internal sealed interface RawResult {
    data class Text(
        val value: String,
    ) : RawResult

    data class Failed(
        val message: String,
    ) : RawResult
}

/**
 * Sends one command, turning every transport failure into a [RawResult.Failed] value.
 *
 * The catch order is load-bearing and is the same rule [Elm327InitStateMachine] follows:
 * [TimeoutCancellationException] *is* a [CancellationException], so it has to be caught first —
 * it is this layer's own timeout firing, not the caller giving up — while a real cancellation
 * from the caller must propagate untouched or structured concurrency breaks. `Exception` is then
 * caught broadly on purpose: [ObdLink.sendRaw]'s contract says implementations throw
 * implementation-defined exceptions on disconnect, and translating those into typed values is
 * exactly this module's job.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal suspend fun ObdLink.sendCatching(
    command: String,
    timeout: Duration,
): RawResult =
    try {
        RawResult.Text(sendRaw(command, timeout))
    } catch (timedOut: TimeoutCancellationException) {
        RawResult.Failed("timeout after $timeout on \"$command\"")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        RawResult.Failed(e.message ?: e::class.simpleName.orEmpty())
    }
