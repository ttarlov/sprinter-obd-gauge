// Round-1 review, finding 2 added updateSessionIndex (plus its private indexLock), pushing this
// file's small encode/decode/IO helpers just past detekt's per-file function count — they're all
// one cohesive concern (the index.json codec and its one safe mutation path), so suppressing here
// keeps it in one place rather than splitting it for the counter's sake, same as DashboardScreen.kt.
@file:Suppress("TooManyFunctions")

package com.revel.obdgauge.app.recording

import java.io.File
import java.time.Instant

/** `logs/index.json` — see [readSessionIndex]/[writeSessionIndex]. */
const val SESSION_INDEX_FILENAME = "index.json"

/**
 * One row of `logs/index.json`: everything the Recordings screen needs to list a session
 * without re-reading its (potentially large) CSV — [file] is a bare filename (relative to the
 * logs directory both this and [file] share), never an absolute path, so the index stays valid
 * if the app's external-files root ever moves (a fresh install, a new device). File **size** is
 * deliberately NOT stored here — it's read live off the file at display time (see
 * `RecordingsViewModel`), so a file removed out-of-band (e.g. over USB) can't leave a stale
 * number in the index.
 *
 * @param endedAt `null` while the session that wrote this entry is still recording (written once
 *   at start, then updated in place on stop — see [addOrUpdateEntry]).
 */
data class SessionIndexEntry(
    val file: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val rows: Int,
    val pidCount: Int,
)

/**
 * Inserts [updated], or replaces the existing entry with the same [SessionIndexEntry.file] —
 * [Recorder] calls this once on start (a fresh entry, `endedAt = null`) and once on stop (the
 * same file, now with `endedAt` and the final [SessionIndexEntry.rows]), so a session always
 * occupies exactly one row.
 */
fun addOrUpdateEntry(
    existing: List<SessionIndexEntry>,
    updated: SessionIndexEntry,
): List<SessionIndexEntry> {
    val index = existing.indexOfFirst { it.file == updated.file }
    if (index < 0) return existing + updated
    return existing.toMutableList().also { it[index] = updated }
}

/**
 * Hand-rolled JSON, matching this codebase's `SettingsCodec` discipline of "no serialization
 * library for a handful of small fields" (see its KDoc) — except this format needs to be actual
 * parseable JSON (the file is named `index.json` and is meant to be USB-pullable and human/
 * pandas-readable), so this is a real, if deliberately narrow, encoder/decoder for exactly
 * [SessionIndexEntry]'s flat shape — not a general-purpose JSON library replacement.
 */
fun encodeSessionIndex(entries: List<SessionIndexEntry>): String {
    if (entries.isEmpty()) return "[]"
    val items = entries.joinToString(",\n") { entry -> "  ${encodeEntry(entry)}" }
    return "[\n$items\n]"
}

private fun encodeEntry(entry: SessionIndexEntry): String {
    val endedAt = entry.endedAt?.let { "\"$it\"" } ?: "null"
    return "{\"file\":\"${escape(entry.file)}\",\"startedAt\":\"${entry.startedAt}\"," +
        "\"endedAt\":$endedAt,\"rows\":${entry.rows},\"pidCount\":${entry.pidCount}}"
}

private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

// NB: the closing brace MUST be escaped (`\\}`). A bare `}` is a literal on the OpenJDK regex
// engine (so every JVM unit test + Robolectric compiled it fine) but Android's ICU-based engine
// rejects it as a stray quantifier close → PatternSyntaxException at class-load →
// ExceptionInInitializerError on the first index.json touch. Device-only crash (OBD-70). Same for
// the opening `\\{`. Braces inside the `[^{}]` class are literal on both engines and need no escape.
private val OBJECT_PATTERN = Regex("\\{[^{}]*\\}")
private val FIELD_PATTERN = Regex("\"(\\w+)\":(null|\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+)")

/** The inverse of [encodeSessionIndex]. Malformed/unrecognized objects are skipped, not thrown. */
fun decodeSessionIndex(json: String): List<SessionIndexEntry> =
    OBJECT_PATTERN.findAll(json).mapNotNull { match -> decodeEntry(match.value) }.toList()

private fun decodeEntry(objectText: String): SessionIndexEntry? {
    val fields = FIELD_PATTERN.findAll(objectText).associate { it.groupValues[1] to it.groupValues[2] }
    val file = fields["file"]?.let(::unquote)
    val startedAt = fields["startedAt"]?.let(::unquote)?.let(::parseInstantOrNull)
    if (file == null || startedAt == null) return null

    val endedAtRaw = fields["endedAt"]
    val endedAt = if (endedAtRaw == null || endedAtRaw == "null") null else parseInstantOrNull(unquote(endedAtRaw))
    val rows = fields["rows"]?.toIntOrNull() ?: 0
    val pidCount = fields["pidCount"]?.toIntOrNull() ?: 0
    return SessionIndexEntry(file, startedAt, endedAt, rows, pidCount)
}

private fun parseInstantOrNull(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

private fun unquote(text: String): String = text.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")

/** Reads `logsDir/index.json`, or an empty list if it doesn't exist yet (a fresh install). */
fun readSessionIndex(logsDir: File): List<SessionIndexEntry> {
    val file = File(logsDir, SESSION_INDEX_FILENAME)
    if (!file.exists()) return emptyList()
    return decodeSessionIndex(file.readText())
}

/** Writes [entries] to `logsDir/index.json`, creating [logsDir] if needed. */
fun writeSessionIndex(
    logsDir: File,
    entries: List<SessionIndexEntry>,
) {
    logsDir.mkdirs()
    File(logsDir, SESSION_INDEX_FILENAME).writeText(encodeSessionIndex(entries))
}

// Round-1 review (finding 2): Recorder (service/IO thread, start/stop/error-finalize) and
// RecordingsViewModel.delete (main thread) both do a read-modify-write on the SAME index.json —
// two independent `readSessionIndex → transform → writeSessionIndex` sequences race and can lose
// each other's write. A single process-wide lock around the whole read-transform-write makes the
// two writers mutually exclusive without either needing to know the other exists.
private val indexLock = Any()

/**
 * The one choke point for mutating `logsDir/index.json` — reads the current entries, applies
 * [transform], and writes the result back, all under [indexLock]. Every index mutation in this
 * app (Recorder's start/stop/error-finalize, RecordingsViewModel's delete) goes through this
 * instead of hand-rolling its own read-modify-write, so the two can never interleave and lose an
 * update. Returns the entries actually written, for callers that want them without a second read.
 */
fun updateSessionIndex(
    logsDir: File,
    transform: (List<SessionIndexEntry>) -> List<SessionIndexEntry>,
): List<SessionIndexEntry> =
    synchronized(indexLock) {
        val updated = transform(readSessionIndex(logsDir))
        writeSessionIndex(logsDir, updated)
        updated
    }
