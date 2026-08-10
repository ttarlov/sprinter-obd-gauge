package com.revel.obdgauge.ble

import com.revel.obdgauge.model.LinkError
import java.io.IOException

/**
 * What `BleObdLink.sendRaw` throws when a command cannot be completed.
 *
 * The `ObdLink` contract leaves the exception type implementation-defined and expects
 * `:core:protocol` to catch and translate it, so the payload that matters is [error] — the same
 * typed [LinkError] vocabulary `LinkState.Error` uses. An `IOException` subclass because that is
 * what this is: a transport failure, not a programming error.
 *
 * Note that a [LinkError.Timeout] from `sendRaw` does **not** imply the link is dead. A dongle
 * answering "no response from the ECU" by saying nothing at all is routine; the link stays
 * `Ready` and the next command goes out normally. Only a genuine disconnect moves the state.
 */
class BleLinkException(
    val error: LinkError,
    message: String = error.toString(),
    cause: Throwable? = null,
) : IOException(message, cause)
