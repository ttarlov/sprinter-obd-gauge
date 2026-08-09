package com.revel.obdgauge.testing.link

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptParserTest {
    @Test
    fun `parses request-response blocks terminated by the prompt`() {
        val text =
            """
            REQUEST: ATZ
            RESPONSE:
            ATZ
            ELM327 v2.1
            >

            REQUEST: ATE0
            RESPONSE:
            ATE0
            OK
            >
            """.trimIndent()

        val entries = TranscriptParser.parse(text)

        assertEquals(2, entries.size)
        assertEquals(TranscriptEntry("ATZ", "ATZ\rELM327 v2.1"), entries[0])
        assertEquals(TranscriptEntry("ATE0", "ATE0\rOK"), entries[1])
    }

    @Test
    fun `ignores comments and blank lines outside a response body`() {
        val text =
            """
            # a comment
            REQUEST: 0105

            RESPONSE:
            41 05 5A
            >
            """.trimIndent()

        val entries = TranscriptParser.parse(text)

        assertEquals(listOf(TranscriptEntry("0105", "41 05 5A")), entries)
    }

    @Test
    fun `parses multi-line responses in order`() {
        val text =
            """
            REQUEST: 0100
            RESPONSE:
            SEARCHING...
            41 00 BE 3E B8 11
            >
            """.trimIndent()

        val entries = TranscriptParser.parse(text)

        assertEquals("SEARCHING...\r41 00 BE 3E B8 11", entries.single().response)
    }

    @Test
    fun `parses the shipped fixture without error`() {
        val entries = TranscriptParser.parseResource("transcripts/elm327-init-and-pids.txt")

        assertEquals(13, entries.size)
        assertEquals("ATZ", entries.first().command)
        assertEquals("220543", entries.last().command)
    }
}
