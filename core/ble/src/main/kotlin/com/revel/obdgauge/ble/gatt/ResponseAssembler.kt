package com.revel.obdgauge.ble.gatt

/**
 * Reassembles BLE notification chunks into complete, `>`-terminated ELM327 responses.
 *
 * This is the single piece of logic that makes `sendRaw` honest: a BLE dongle splits one
 * response across however many notifications its MTU allows, with no guarantee that a chunk
 * boundary falls on a line boundary. Everything here is pure — no Android types, no coroutines,
 * no timing — so it is exhaustively testable on the JVM. The GATT callback is a thin adapter
 * that does nothing but feed [append] and hand completed responses to the waiting caller.
 *
 * Normalization rules (deliberate, and each one earns its keep against real dongle behaviour):
 * - `\r` and `\n` both act as line separators; consecutive separators collapse because blank
 *   lines are dropped. ELM327 uses `\r`, but clones have been observed emitting `\r\n`.
 * - Bytes outside printable ASCII (`0x20..0x7E`) are dropped. BLE stacks pad short frames with
 *   `NUL`, and a half-powered clone emits high-bit noise; neither is response content.
 * - Each line is end-trimmed, then non-empty lines are joined with `\r` — the exact shape
 *   `FakeObdLink` replays, so `:core:protocol` cannot tell the fake from the real link.
 * - A prompt with nothing but whitespace in front of it is a **stray prompt**, counted in
 *   [strayPrompts] and discarded rather than emitted as an empty response. Without this, a
 *   leftover prompt from a previous command (or from the dongle's power-on banner) would
 *   satisfy the *next* command's wait and shift every subsequent response by one — the worst
 *   possible failure mode for a gauge, because it stays plausible while being wrong.
 * - Content accumulating past [maxPendingChars] without a prompt is discarded and counted in
 *   [overflows]; a wedged link streaming garbage must not grow the heap without bound.
 *
 * Echo remnants (the command echoed back before `ATE0` takes effect) are **preserved** as
 * response lines. Stripping them would be protocol knowledge, which does not live in
 * `:core:ble` — `:core:protocol` owns that, and the shipped transcript fixture keeps them too.
 *
 * Not thread-safe: instances are confined to the link dispatcher (see `GattSession`).
 */
class ResponseAssembler(
    private val maxPendingChars: Int = DEFAULT_MAX_PENDING_CHARS,
) {
    private val pendingBuffer = StringBuilder()
    private var strayPromptCount = 0
    private var overflowCount = 0

    /** Characters buffered so far for the response still being assembled. */
    val pending: String get() = pendingBuffer.toString()

    /** Number of prompts seen with no content in front of them. Diagnostics only. */
    val strayPrompts: Int get() = strayPromptCount

    /** Number of times the pending buffer was discarded for exceeding [maxPendingChars]. */
    val overflows: Int get() = overflowCount

    /** Drops any partially-assembled response. Called before each command write. */
    fun reset() {
        pendingBuffer.setLength(0)
    }

    /** Decodes [bytes] as latin-1 (byte-transparent) and feeds them to [append]. */
    fun append(bytes: ByteArray): List<String> = append(String(bytes, Charsets.ISO_8859_1))

    /**
     * Feeds one notification's worth of text, returning every response completed by it — zero
     * for a mid-response chunk, one normally, more than one if the dongle got ahead of us.
     */
    fun append(text: String): List<String> {
        val completed = mutableListOf<String>()
        for (character in text) {
            when {
                character == PROMPT -> completeResponse()?.let(completed::add)
                character == CR || character == LF -> pendingBuffer.append(CR)
                character.code in PRINTABLE_RANGE -> pendingBuffer.append(character)
                else -> Unit // NUL padding / high-bit noise: not response content.
            }
            if (pendingBuffer.length > maxPendingChars) {
                overflowCount++
                pendingBuffer.setLength(0)
            }
        }
        return completed
    }

    private fun completeResponse(): String? {
        val response =
            pendingBuffer
                .split(CR)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString(CR.toString())
        pendingBuffer.setLength(0)
        return if (response.isEmpty()) {
            strayPromptCount++
            null
        } else {
            response
        }
    }

    companion object {
        /** The ELM327 "ready for the next command" prompt. */
        const val PROMPT = '>'

        private const val CR = '\r'
        private const val LF = '\n'
        private const val DEFAULT_MAX_PENDING_CHARS = 4096
        private val PRINTABLE_RANGE = 0x20..0x7E
    }
}
