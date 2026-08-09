package com.revel.obdgauge.testing.link

/**
 * A fault [FakeObdLink] can inject in place of (or delaying) a scripted response, to exercise
 * the protocol layer's error handling without real hardware.
 */
sealed interface Fault {
    /** Return a malformed/garbage frame instead of the scripted response. */
    data object Garbage : Fault

    /** Return the ELM327 `NO DATA` response (ECU didn't answer). */
    data object NoData : Fault

    /** Return the ELM327 `STOPPED` response (command interrupted by another). */
    data object Stopped : Fault

    /** Never respond; [FakeObdLink.sendRaw] runs past its `timeout` and throws
     *  [kotlinx.coroutines.TimeoutCancellationException]. */
    data object Timeout : Fault

    /**
     * Simulate the link dropping mid-response: `sendRaw` throws [ObdLinkDisconnectedException]
     * and [FakeObdLink.state] moves to [com.revel.obdgauge.model.LinkState.Error].
     */
    data object MidResponseDisconnect : Fault
}

/** Thrown by [FakeObdLink.sendRaw] when [Fault.MidResponseDisconnect] is injected. */
class ObdLinkDisconnectedException(
    message: String,
) : Exception(message)
