package com.revel.obdgauge.protocol

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property tests for the parser's two hard guarantees, over thousands of seeded pseudo-random
 * inputs:
 *
 * 1. **It never throws.** Any input, any PID — the outcome is a value or a typed failure.
 * 2. **It never poisons.** When it does return a value, that value is finite and inside the
 *    range the PID's SAE formula can physically produce. Honest scope note (review round-1):
 *    because the parser only ever converts whole bytes, any framed value lands in range by
 *    construction — so this property guards the never-throws/finiteness boundary, NOT
 *    mis-framing. Mis-framing is pinned by the dedicated alignment, short-frame, and
 *    truncated-then-complete tests in ResponseParserTest.
 *
 * The seed is fixed so a failure is reproducible; the failure message prints the offending input
 * escaped, because the interesting cases are full of carriage returns.
 */
class ResponseParserPropertyTest {
    @Test
    fun `never throws and never poisons on arbitrary garbage`() {
        val random = Random(GARBAGE_SEED)
        repeat(GARBAGE_CASES) {
            check(randomGarbage(random))
        }
    }

    @Test
    fun `never throws and never poisons on mutated valid frames`() {
        val random = Random(MUTATION_SEED)
        repeat(MUTATION_CASES) {
            check(mutate(VALID_FRAMES.random(random), random))
        }
    }

    @Test
    fun `dataBytes never throws for arbitrary modes, pids and expected lengths`() {
        val random = Random(DATA_BYTES_SEED)
        repeat(DATA_BYTES_CASES) {
            val raw = if (random.nextBoolean()) randomGarbage(random) else mutate(VALID_FRAMES.random(random), random)
            val outcome =
                ResponseParser.dataBytes(
                    raw = raw,
                    responseMode = random.nextInt(0, 0x100),
                    pid = random.nextInt(0, 0x100),
                    expectedCount = random.nextInt(0, 9),
                )
            val bytes = outcome.valueOrNull()
            if (bytes != null) {
                assertTrue(
                    "byte outside 0..255 for \"${escape(raw)}\": $bytes",
                    bytes.all { it in 0..0xFF },
                )
            }
        }
    }

    /** Parses [raw] with every registered PID, asserting both guarantees. */
    private fun check(raw: String) {
        for (spec in PidRegistry.all) {
            val outcome = ResponseParser.parse(spec, raw)
            val value = outcome.valueOrNull()
            if (value != null) {
                val range = PLAUSIBLE_RANGES.getValue(spec.definition.id)
                assertTrue(
                    "${spec.definition.id} produced $value (outside $range) from \"${escape(raw)}\"",
                    value.isFinite() && value in range,
                )
            }
        }
    }

    private fun randomGarbage(random: Random): String {
        val length = random.nextInt(0, MAX_GARBAGE_LENGTH)
        val body = buildString { repeat(length) { append(GARBAGE_ALPHABET.random(random)) } }
        return if (random.nextInt(SPLICE_ODDS) == 0) {
            val token = SPLICE_TOKENS.random(random)
            val at = random.nextInt(0, body.length + 1)
            body.take(at) + token + body.drop(at)
        } else {
            body
        }
    }

    private fun mutate(
        frame: String,
        random: Random,
    ): String {
        var current = frame
        repeat(random.nextInt(1, MAX_MUTATIONS + 1)) {
            current = mutateOnce(current, random)
        }
        return current
    }

    @Suppress("MagicNumber")
    private fun mutateOnce(
        frame: String,
        random: Random,
    ): String {
        if (frame.isEmpty()) {
            return GARBAGE_ALPHABET.random(random).toString()
        }
        val at = random.nextInt(frame.length)
        return when (random.nextInt(6)) {
            0 -> frame.removeRange(at, at + 1)
            1 -> frame.take(at) + GARBAGE_ALPHABET.random(random) + frame.drop(at)
            2 -> frame.take(at) + GARBAGE_ALPHABET.random(random) + frame.drop(at + 1)
            3 -> frame.take(at)
            4 -> frame + frame.take(at)
            else -> frame.reversed()
        }
    }

    private fun escape(raw: String): String = raw.replace("\r", "\\r").replace("\n", "\\n")

    private companion object {
        const val GARBAGE_SEED = 0x0BD13L
        const val MUTATION_SEED = 0x5AE79L
        const val DATA_BYTES_SEED = 0x41055L

        const val GARBAGE_CASES = 1500
        const val MUTATION_CASES = 1500
        const val DATA_BYTES_CASES = 1500

        const val MAX_GARBAGE_LENGTH = 48
        const val MAX_MUTATIONS = 3
        const val SPLICE_ODDS = 3

        /** Everything an ELM327 (or a corrupted BLE stream) can plausibly put on the wire. */
        val GARBAGE_ALPHABET: List<Char> =
            ("0123456789ABCDEFabcdef" + " \t\r\n" + ">?:.,;-/*#" + "GHIJKLMNOPQRSTUVWXYZ").toList()

        /** Fragments that make garbage look dangerously close to a real response. */
        val SPLICE_TOKENS: List<String> =
            listOf(
                "NO DATA",
                "SEARCHING...",
                "STOPPED",
                "UNABLE TO CONNECT",
                "CAN ERROR",
                "BUFFER FULL",
                "BUS INIT: OK",
                "?",
                "41",
                "4105",
                "410C",
                "7F 01 12",
                "0:",
                "1:",
            )

        val VALID_FRAMES: List<String> =
            listOf(
                "41 05 5A",
                "41055A",
                "SEARCHING...\r41 05 5A",
                "41 0C 1F 40",
                "410C1F40",
                "41 0B 64",
                "41 33 62",
                "41 0F 28",
                "41 0D 50",
                "0105\r41 05 5A\r",
                "014\r0: 41 00 BE 3E\r1: B8 11 00 00",
                "41 05 5A 00 00 00",
                // OBD-50 (session 2, 2026-08-13) additions.
                "41 5C 81",
                "41 2F 6D",
                "41 46 3C",
                "41 49 0D",
                "41 61 82",
                "41 62 88",
            )

        /** The full range each PID's SAE formula can produce, from raw `0x00` to raw `0xFF(FF)`. */
        val PLAUSIBLE_RANGES: Map<String, ClosedFloatingPointRange<Double>> =
            mapOf(
                PidRegistry.coolant.definition.id to -40.0..215.0,
                PidRegistry.intakeAirTemp.definition.id to -40.0..215.0,
                PidRegistry.map.definition.id to 0.0..255.0,
                PidRegistry.baro.definition.id to 0.0..255.0,
                PidRegistry.speed.definition.id to 0.0..255.0,
                PidRegistry.rpm.definition.id to 0.0..16383.75,
                PidRegistry.engineLoad.definition.id to 0.0..100.0,
                PidRegistry.throttlePosition.definition.id to 0.0..100.0,
                // OBD-50 (session 2, 2026-08-13) additions.
                PidRegistry.oilTemp.definition.id to -40.0..215.0,
                PidRegistry.fuelLevel.definition.id to 0.0..100.0,
                PidRegistry.ambientTemp.definition.id to -40.0..215.0,
                PidRegistry.accelPedal.definition.id to 0.0..100.0,
                PidRegistry.demandTorque.definition.id to -125.0..130.0,
                PidRegistry.actualTorque.definition.id to -125.0..130.0,
            )
    }
}
