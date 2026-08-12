/*
 * Portions of this file (the scaling constants and formulas below) are derived from:
 *
 *   Project:  kotlin-obd-api — https://github.com/eltonvs/kotlin-obd-api
 *   Version:  v1.4.1 (master @ 30014eb6e8cd35334ba8f7ea627500f6b1942ff5, fetched 2026-08-10)
 *   Files:    src/main/kotlin/com/github/eltonvs/obd/command/temperature/Temperature.kt
 *             src/main/kotlin/com/github/eltonvs/obd/command/pressure/Pressure.kt
 *             src/main/kotlin/com/github/eltonvs/obd/command/engine/Engine.kt
 *             src/main/kotlin/com/github/eltonvs/obd/command/ParserFunctions.kt
 *   Copyright (c) Elton Viana and the kotlin-obd-api contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * Vendored (transcribed, not linked) per DECISIONS.md D1. See NOTICE at the repository root.
 */

package com.revel.obdgauge.protocol

/**
 * SAE J1979 mode-01 scaling for the six standard PIDs this app polls.
 *
 * Every formula here was **vendored** from kotlin-obd-api (see the license header above and
 * `DECISIONS.md` D1) and then **independently cross-checked** against the public SAE J1979
 * definitions. The cross-check result, PID by PID:
 *
 * | PID    | Channel      | SAE J1979          | kotlin-obd-api                                | Agrees? |
 * |--------|--------------|--------------------|-----------------------------------------------|---------|
 * | `0105` | Coolant temp | `A − 40` °C        | `bytesToInt(1 byte) - 40f`                     | yes     |
 * | `010F` | Intake temp  | `A − 40` °C        | `bytesToInt(1 byte) - 40f`                     | yes     |
 * | `010B` | MAP          | `A` kPa (absolute) | `bytesToInt(1 byte)`                           | yes     |
 * | `0133` | Barometric   | `A` kPa (absolute) | `bytesToInt(1 byte)`                           | yes     |
 * | `010D` | Speed        | `A` km/h           | `bytesToInt(1 byte)`                           | yes     |
 * | `010C` | Engine speed | `(256·A + B) / 4`  | `bytesToInt(2 bytes) / 4` — see the note below | see note |
 * | `0104` | Engine load  | `A × 100 / 255` %  | **not vendored** — see the percentage note        | n/a     |
 * | `0111` | Throttle pos | `A × 100 / 255` %  | **not vendored** — see the percentage note        | n/a     |
 *
 * **Percentage note (OBD-43).** [percent] is **not** vendored from kotlin-obd-api; it is taken
 * directly from the SAE J1979 definitions of PID `04` (calculated engine load) and PID `11`
 * (throttle position), which are the same `A × 100 / 255` full-scale-byte mapping. It lives in
 * this file so that all mode-01 scaling has one home, but the Apache-2.0 header above covers the
 * vendored formulas only — nothing in [percent] is derived from that project, and the licence
 * obligation is unchanged either way.
 *
 * **RPM note (the one divergence, deliberate and documented rather than silent).** The
 * *formula and the constant* agree with SAE — `(256·A + B)` scaled by ¼. The *arithmetic* does
 * not: kotlin-obd-api computes `Long / Int`, which is **integer division in Kotlin**, so its
 * result is truncated to a whole number and the ¼-rpm resolution SAE specifies is discarded
 * (e.g. raw `00 01` → SAE `0.25` rpm, kotlin-obd-api `0`). That is a lossy implementation of a
 * correct formula, not a different formula. This module implements the **SAE-exact** version
 * (`/ 4.0`, floating point). At gauge resolution the two agree everywhere it matters; the
 * exact form is used because a truncating divide is a silent-precision trap and this module's
 * whole job is to not produce plausible-but-wrong numbers.
 *
 * **Units.** Everything here returns the PID's natural SI-ish unit — °C, kPa **absolute**,
 * rpm, km/h. Display conversion (°F, PSI, mph) is the UI's job; see `MODULE.md`.
 *
 * **Inputs.** Every parameter is one *unsigned* OBD data byte (`0..255`). Callers converting
 * from a Kotlin `ByteArray` must mask with `and 0xFF` first — [dataByte] does this. Out-of-range
 * input throws [IllegalArgumentException]: this is a programming error, not a wire condition,
 * and [ResponseParser] never lets wire data reach here unvalidated (it also traps any throw and
 * converts it into a typed [ParseFailure.ScalingError], so the parser's never-throws guarantee
 * holds regardless).
 */
object VendoredSaeScaling {
    /**
     * Temperature PIDs (`0105` coolant, `010F` intake air): `A − 40`, degrees Celsius.
     *
     * The `−40` offset means raw `0x00` is −40 °C and raw `0xFF` is 215 °C.
     */
    fun temperatureCelsius(a: Int): Double {
        requireDataByte(a, "A")
        return a - CELSIUS_OFFSET
    }

    /**
     * Engine speed (`010C`): `(256·A + B) / 4`, rpm.
     *
     * Divided in floating point, so the ¼-rpm resolution SAE specifies survives — see the
     * RPM note on [VendoredSaeScaling].
     */
    fun engineRpm(
        a: Int,
        b: Int,
    ): Double {
        requireDataByte(a, "A")
        requireDataByte(b, "B")
        return (HIGH_BYTE_WEIGHT * a + b) / RPM_DIVISOR
    }

    /**
     * Pressure PIDs (`010B` manifold absolute, `0133` barometric): `A`, kPa **absolute**.
     *
     * Both are absolute, which is exactly why boost can be computed as `MAP − baro` at any
     * elevation instead of assuming a fixed sea-level offset (OBD-16 does that arithmetic).
     */
    fun pressureKpa(a: Int): Double {
        requireDataByte(a, "A")
        return a.toDouble()
    }

    /** Vehicle speed (`010D`): `A`, km/h. */
    fun speedKmh(a: Int): Double {
        requireDataByte(a, "A")
        return a.toDouble()
    }

    /**
     * Full-scale-byte percentage PIDs (`0104` calculated engine load, `0111` throttle position):
     * `A × 100 / 255`, percent.
     *
     * The divisor is **255, not 256**: SAE defines the byte as a full-scale fraction, so raw
     * `0xFF` is exactly 100 % and raw `0x00` is exactly 0 %. Dividing by 256 — an easy and
     * invisible slip — would top the gauge out at 99.6 % and shift every reading below it, which
     * is precisely the plausible-but-wrong number this module refuses to produce. Multiplying
     * *before* dividing keeps the two endpoints and the exact quotients (e.g. raw `51` → 20 %,
     * raw `204` → 80 %) bit-exact in IEEE-754 rather than merely close.
     *
     * Not vendored — see the percentage note on [VendoredSaeScaling].
     */
    fun percent(a: Int): Double {
        requireDataByte(a, "A")
        return a * PERCENT_FULL_SCALE / MAX_BYTE
    }

    /**
     * Reads [index] of [data] as an unsigned OBD data byte (`0..255`).
     *
     * Kotlin's `Byte` is signed, so a raw `0xBE` arrives as `-66`; every scaling call must mask
     * before it scales. Throws [IllegalArgumentException] if [index] is out of bounds —
     * [ResponseParser] validates data length before invoking any `parse` lambda, so this fires
     * only on direct misuse of a [com.revel.obdgauge.model.PidDefinition.parse] lambda.
     */
    fun dataByte(
        data: ByteArray,
        index: Int,
    ): Int {
        require(index in data.indices) {
            "data byte index $index out of bounds for a ${data.size}-byte payload"
        }
        return data[index].toInt() and BYTE_MASK
    }

    private fun requireDataByte(
        value: Int,
        name: String,
    ) {
        require(value in 0..MAX_BYTE) { "data byte $name must be in 0..$MAX_BYTE, was $value" }
    }

    /** Vendored from kotlin-obd-api `Temperature.kt`: `private const val CELSIUS_OFFSET = 40f`. */
    private const val CELSIUS_OFFSET = 40.0

    /** Vendored from kotlin-obd-api `Engine.kt`: `private const val RPM_DIVISOR = 4`. */
    private const val RPM_DIVISOR = 4.0

    /** Big-endian byte weight, from kotlin-obd-api `ParserFunctions.kt`'s `bytesToInt` fold. */
    private const val HIGH_BYTE_WEIGHT = 256

    /** SAE J1979 full-scale percentage numerator: raw `0xFF` maps to exactly 100 %. */
    private const val PERCENT_FULL_SCALE = 100.0

    private const val MAX_BYTE = 255
    private const val BYTE_MASK = 0xFF
}
