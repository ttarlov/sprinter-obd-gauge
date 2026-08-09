package com.revel.obdgauge.testing.link

/**
 * One request/response exchange from a captured or synthetic ELM327 session.
 *
 * @param command the AT/OBD command exactly as sent (no trailing `\r`), matched
 *   case-insensitively by [FakeObdLink].
 * @param response the raw response text, `\r`-joined line by line, `>` prompt already
 *   stripped — the same shape [com.revel.obdgauge.model.ObdLink.sendRaw] returns.
 */
data class TranscriptEntry(
    val command: String,
    val response: String,
)

/**
 * Parses the plain-text transcript format [FakeObdLink] fixtures use:
 *
 * ```
 * REQUEST: ATZ
 * RESPONSE:
 * ATZ
 * ELM327 v2.1
 * >
 * ```
 *
 * Each block starts with a `REQUEST:` line, followed by a `RESPONSE:` marker and one or more
 * response lines, terminated by a line containing only `>` — mirroring the prompt a raw
 * serial/BLE capture would show. Blank lines and `#`-prefixed comments outside a response body
 * are ignored, so fixtures can be grouped and annotated for readability.
 */
object TranscriptParser {
    private const val REQUEST_PREFIX = "REQUEST:"
    private const val RESPONSE_MARKER = "RESPONSE:"
    private const val PROMPT = ">"
    private const val COMMENT_PREFIX = "#"

    fun parse(text: String): List<TranscriptEntry> {
        val entries = mutableListOf<TranscriptEntry>()
        var pendingCommand: String? = null
        var inResponse = false
        val responseLines = mutableListOf<String>()

        fun flush() {
            val command = pendingCommand
            if (command != null && responseLines.isNotEmpty()) {
                entries += TranscriptEntry(command, responseLines.joinToString("\r"))
            }
            pendingCommand = null
            inResponse = false
            responseLines.clear()
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith(REQUEST_PREFIX) -> {
                    flush()
                    pendingCommand = line.removePrefix(REQUEST_PREFIX).trim()
                }
                line == RESPONSE_MARKER -> inResponse = true
                line == PROMPT && inResponse -> flush()
                !inResponse || line.isBlank() || line.startsWith(COMMENT_PREFIX) -> Unit
                else -> responseLines += line
            }
        }
        flush()
        return entries
    }

    /**
     * Loads and parses a transcript from a classpath resource, e.g.
     * `"transcripts/elm327-init-and-pids.txt"`.
     */
    fun parseResource(resourcePath: String): List<TranscriptEntry> {
        val stream =
            checkNotNull(TranscriptParser::class.java.classLoader.getResourceAsStream(resourcePath)) {
                "transcript resource not found: $resourcePath"
            }
        return stream.bufferedReader().use { parse(it.readText()) }
    }
}
