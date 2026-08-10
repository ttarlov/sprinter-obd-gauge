package com.revel.obdgauge.ble.gatt

import com.revel.obdgauge.testing.link.TranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The reassembly contract, exercised the way BLE actually delivers bytes: in arbitrary pieces.
 *
 * The load-bearing test here is [anyChunkingOfATranscriptReassemblesIdentically] — a property
 * test asserting that the split points are irrelevant, which is the only claim that matters when
 * the MTU, the dongle's buffering and the radio all get a vote on where the boundaries land.
 */
class ResponseAssemblerTest {
    @Test
    fun `single chunk response is assembled and the prompt stripped`() {
        val assembler = ResponseAssembler()

        val responses = assembler.append("41 05 5A\r\r>")

        assertEquals(listOf("41 05 5A"), responses)
    }

    @Test
    fun `response split across chunks emits nothing until the prompt arrives`() {
        val assembler = ResponseAssembler()

        assertEquals(emptyList<String>(), assembler.append("41 0"))
        assertEquals(emptyList<String>(), assembler.append("5 5A\r"))
        assertEquals(listOf("41 05 5A"), assembler.append("\r>"))
    }

    @Test
    fun `multi-line responses join with carriage returns`() {
        val assembler = ResponseAssembler()

        val responses = assembler.append("SEARCHING...\r41 00 BE 3E B8 11\r\r>")

        assertEquals(listOf("SEARCHING...\r41 00 BE 3E B8 11"), responses)
    }

    @Test
    fun `crlf line endings collapse to single carriage returns`() {
        val assembler = ResponseAssembler()

        val responses = assembler.append("ATZ\r\nELM327 v2.1\r\n\r\n>")

        assertEquals(listOf("ATZ\rELM327 v2.1"), responses)
    }

    @Test
    fun `command echo is preserved because stripping it is protocol knowledge`() {
        val assembler = ResponseAssembler()

        val responses = assembler.append("0105\r41 05 5A\r\r>")

        assertEquals(listOf("0105\r41 05 5A"), responses)
    }

    @Test
    fun `nul padding and high-bit noise are discarded`() {
        val assembler = ResponseAssembler()

        val noisy = "41\u0000 05\u0000 5A\u00ff\u0001\r\r>"
        val responses = assembler.append(noisy)

        assertEquals(listOf("41 05 5A"), responses)
    }

    @Test
    fun `a garbage frame is delivered as a response rather than dropped`() {
        val assembler = ResponseAssembler()

        val responses = assembler.append("G@RB13D//FRAME??\r\r>")

        assertEquals(listOf("G@RB13D//FRAME??"), responses)
    }

    @Test
    fun `a stray prompt is counted and never delivered as an empty response`() {
        val assembler = ResponseAssembler()

        val fromStrayPrompts = assembler.append("\r\r>>")
        val fromRealResponse = assembler.append("41 05 5A\r\r>")

        assertEquals(emptyList<String>(), fromStrayPrompts)
        assertEquals(2, assembler.strayPrompts)
        assertEquals(listOf("41 05 5A"), fromRealResponse)
    }

    @Test
    fun `several responses arriving in one chunk are all returned in order`() {
        val assembler = ResponseAssembler()

        val responses = assembler.append("OK\r\r>41 05 5A\r\r>NO DATA\r\r>")

        assertEquals(listOf("OK", "41 05 5A", "NO DATA"), responses)
    }

    @Test
    fun `reset discards a partial response so the next command starts clean`() {
        val assembler = ResponseAssembler()
        assembler.append("41 05 5")

        assembler.reset()
        val responses = assembler.append("41 0B 64\r\r>")

        assertEquals(listOf("41 0B 64"), responses)
        assertEquals("", assembler.pending)
    }

    @Test
    fun `a runaway stream without a prompt is discarded instead of growing the heap`() {
        val assembler = ResponseAssembler(maxPendingChars = 32)

        assembler.append("A".repeat(200))

        assertTrue("expected at least one overflow", assembler.overflows > 0)
        assertTrue("pending buffer should be bounded", assembler.pending.length <= 32)
    }

    @Test
    fun `bytes are decoded transparently`() {
        val assembler = ResponseAssembler()

        val responses = assembler.append("41 05 5A\r\r>".toByteArray(Charsets.ISO_8859_1))

        assertEquals(listOf("41 05 5A"), responses)
    }

    @Test
    fun `every single split point of a transcript reassembles identically`() {
        val expected = TRANSCRIPT_RESPONSES
        val wire = wireStream(expected)

        for (split in 0..wire.length) {
            val assembler = ResponseAssembler()
            val actual = assembler.append(wire.substring(0, split)) + assembler.append(wire.substring(split))
            assertEquals("split at $split", expected, actual)
        }
    }

    @Test
    fun `any chunking of a transcript reassembles identically`() {
        val expected = TRANSCRIPT_RESPONSES
        val wire = wireStream(expected)
        val random = Random(SEED)

        repeat(PROPERTY_ITERATIONS) { iteration ->
            val assembler = ResponseAssembler()
            val actual = mutableListOf<String>()
            var offset = 0
            while (offset < wire.length) {
                val size = random.nextInt(1, MAX_CHUNK).coerceAtMost(wire.length - offset)
                actual += assembler.append(wire.substring(offset, offset + size))
                offset += size
            }
            assertEquals("iteration $iteration", expected, actual)
        }
    }

    @Test
    fun `the shipped fragmented fixture reassembles into the same responses the fake replays`() {
        val entries = TranscriptParser.parseResource("transcripts/elm327-init-and-pids.txt")
        val expected = entries.map { it.response }
        val assembler = ResponseAssembler()

        // Deliberately hostile fragmentation: 7 bytes at a time, which never lines up with the
        // line structure of the fixture.
        val wire = wireStream(expected)
        val actual = mutableListOf<String>()
        wire.chunked(HOSTILE_CHUNK).forEach { chunk -> actual += assembler.append(chunk) }

        assertTrue("fixture should have several exchanges", expected.size > 5)
        assertEquals(expected, actual)
    }

    private companion object {
        const val SEED = 20260810
        const val PROPERTY_ITERATIONS = 250
        const val MAX_CHUNK = 24
        const val HOSTILE_CHUNK = 7

        /** Responses in the shape `sendRaw` returns them: lines joined by `\r`, no prompt. */
        val TRANSCRIPT_RESPONSES =
            listOf(
                "ATZ\rELM327 v2.1",
                "OK",
                "SEARCHING...\r41 00 BE 3E B8 11",
                "41 05 5A",
                "NO DATA",
                "?",
                "62 05 43 5C",
            )

        /** Rebuilds what the dongle would actually put on the wire for those responses. */
        fun wireStream(responses: List<String>): String =
            responses.joinToString(separator = "") { response -> "$response\r\r>" }
    }
}
