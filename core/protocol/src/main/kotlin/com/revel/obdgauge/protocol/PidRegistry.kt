package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority

/**
 * One standard mode-01 PID: its [PidDefinition] plus the wire facts [ResponseParser] needs to
 * find and validate the PID's frame in a raw ELM327 response.
 *
 * [PidDefinition] deliberately carries no byte-count field (it is the frozen UI-facing
 * contract), so the response length lives here alongside the request bytes.
 *
 * @param definition the contract-level definition handed to the UI and the scheduler.
 * @param mode the OBD-II service, `0x01` for every PID in this registry.
 * @param pid the parameter id within that mode, e.g. `0x05` for coolant.
 * @param dataByteCount how many data bytes follow the `41 XX` header in a positive response.
 *   Frames carrying *more* than this are accepted (some ECUs pad); frames carrying fewer are a
 *   [ParseFailure.UnexpectedDataLength].
 */
data class StandardPidSpec(
    val definition: PidDefinition,
    val mode: Int,
    val pid: Int,
    val dataByteCount: Int,
) {
    /** The command text sent over the link, e.g. `"0105"`. */
    val command: String get() = hexByte(mode) + hexByte(pid)

    /**
     * The mode byte a positive response echoes: request mode `+ 0x40`, so `01` → `41`. The
     * parser looks for `responseMode` followed by [pid] to locate this PID's frame.
     */
    val responseMode: Int get() = mode + POSITIVE_RESPONSE_OFFSET

    /** The 2-byte header a positive response opens with, e.g. `"4105"`. */
    val responseHeader: String get() = hexByte(responseMode) + hexByte(pid)
}

/**
 * The standard mode-01 PIDs this app polls, with SAE scaling wired in from
 * [VendoredSaeScaling].
 *
 * **Unit strategy: every value parses to the PID's natural SI-ish unit** — °C for temperatures,
 * kPa *absolute* for pressures, rpm, km/h — recorded in each [PidDefinition.unit]. Display
 * conversion to °F / PSI / mph is the UI's job, so protocol math stays in one unit system and
 * boost (`MAP − baro`, OBD-16) subtracts two quantities that are already commensurate. See
 * `MODULE.md` for the mismatch this creates with `:app`'s current `DASHBOARD_PIDS` placeholder.
 *
 * Mode-22 (Mercedes) PIDs are **not** here: they arrive with OBD-15 and carry
 * [PidDefinition.verified] `= false` until hardware-verified.
 *
 * ## Hardware status after session 1 (2026-08-12, OM642, engine running — OBD-43)
 *
 * Everything here is `verified = true`, and for six of the eight that is now backed by a live
 * capture rather than only by the SAE standard:
 *
 * | PID | Channel | 2026-08-12 result |
 * |---|---|---|
 * | `0105` | [coolant] | ✓ `86` = 94 °C, all three ECUs agreed |
 * | `010C` | [rpm] | ✓ `0B54`–`0B64` = 725–729 rpm at idle |
 * | `0104` | [engineLoad] | ✓ `8E`/`90` ≈ 56 % |
 * | `0111` | [throttlePosition] | ✓ `D3` ≈ 83 % — diesel intake flap, see its KDoc |
 * | `010D` | [speed] | ✓ advertised in the `0100` bitmap (not individually captured) |
 * | `0133` | [baro] | ✓ `52` = 82 kPa, consistent with ~5 800 ft |
 * | `010B` | [map] | ✗ **`NO DATA` — not supported on this vehicle** |
 * | `010F` | [intakeAirTemp] | ✗ **`NO DATA` — not supported** (the bitmap agrees) |
 *
 * **[map] and [intakeAirTemp] keep `verified = true` on purpose, and that is not a contradiction
 * — it is the whole point of keeping two axes apart.** [PidDefinition.verified] answers "is this
 * app's request and scaling for this PID correct?", which for two SAE-standard PIDs it is; it
 * does not answer "will this van reply?". That second question is
 * [PidCatalog.availabilityOf], which reports both of these as
 * [ChannelAvailability.UnsupportedByVehicle] from the capture above — and which is what makes
 * the computed boost channel degrade to a *typed unavailable state* instead of a silent zero.
 * Conflating the two would have meant flipping a correct SAE decode to "unverified" and leaving
 * the boost gauge with no way to say why it has nothing to show.
 *
 * ## Hardware status after session 2 (2026-08-13, OM642, cold-start + commercial packet capture — OBD-50)
 *
 * Six more standard PIDs, live-verified via a full Bluetooth HCI capture of a commercial scan
 * tool (`docs/hardware/session-2026-08-13.md` §5) polling this same van's dongle:
 *
 * | PID | Channel | 2026-08-13 result |
 * |---|---|---|
 * | `015C` | [oilTemp] | ✓ `81` = 89 °C — **closes OBD-35**, the dashboard's oil tile now reads a real value |
 * | `012F` | [fuelLevel] | ✓ `6D` ≈ 42.7 % |
 * | `0146` | [ambientTemp] | ✓ `3C` = 20 °C, matched the ATRV's 65 °F |
 * | `0149` | [accelPedal] | ✓ `0D` ≈ 5.1 % |
 * | `0161` | [demandTorque] | ✓ `82` = 5 % |
 * | `0162` | [actualTorque] | ✓ `88` = 11 % |
 *
 * **Two PIDs from the same capture are *not* here.** `015E` (engine fuel rate, `(256A+B)/20`
 * L/h, anchored `00 17` → 1.15 L/h) and `0142` (module voltage, `(256A+B)/1000` V, anchored
 * `36 E2` → 14.05 V) are both live-verified and both scaled — [VendoredSaeScaling.fuelRateLitersPerHour]
 * and [VendoredSaeScaling.moduleVoltageVolts] carry the formula and the anchor test — but neither
 * L/h nor V exists as a [com.revel.obdgauge.model.MeasurementUnit] in the frozen `:core:model`
 * contract, and this module does not add to that contract on its own authority (see
 * `MODULE.md`). They wait for a reviewed contract change before they can become
 * [StandardPidSpec] entries.
 *
 * ## Boost-wave (OBD-56, 2026-08-13): the extended sensor PIDs the survey missed
 *
 * Standard MAF (`0110`) and IAT (`010F`) are unsupported on this van, but the extended dual-bank
 * forms are answered (`docs/hardware/research-2026-08-13-boost-inference.md`). [intakeAirTempSensor]
 * (`0168` sensor 1, `54` → 44 °C) lands here as a real CELSIUS channel, `verified = false`. MAF
 * (`0166` sensor A, `01 C7` → 14.21875 g/s) **does not get a channel**: g/s joins L/h and V as a
 * quantity the frozen enum cannot name, so its decode lives in
 * [VendoredSaeScaling.massAirFlowGramsPerSecond] and it is referenced by id ([ProtocolPidIds.MAF])
 * as a boost dependency that [PidCatalog.availabilityOf] reports
 * [ChannelAvailability.PendingUnitContract]. Both feed OBD-57's speed-density boost.
 */
object PidRegistry {
    /** Engine coolant temperature, `0105`, `A − 40` °C. */
    val coolant: StandardPidSpec =
        spec(
            id = PidIds.COOLANT,
            label = "Coolant",
            unit = MeasurementUnit.CELSIUS,
            pid = COOLANT_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.SLOW,
            parse = { data -> VendoredSaeScaling.temperatureCelsius(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /** Engine speed, `010C`, `(256·A + B) / 4` rpm. */
    val rpm: StandardPidSpec =
        spec(
            id = PidIds.RPM,
            label = "RPM",
            unit = MeasurementUnit.RPM,
            pid = RPM_PID,
            dataByteCount = TWO_DATA_BYTES,
            pollPriority = PollPriority.FAST,
            parse = { data ->
                VendoredSaeScaling.engineRpm(
                    a = VendoredSaeScaling.dataByte(data, 0),
                    b = VendoredSaeScaling.dataByte(data, 1),
                )
            },
        )

    /** Intake manifold absolute pressure, `010B`, `A` kPa absolute. Feeds computed boost. */
    val map: StandardPidSpec =
        spec(
            id = ProtocolPidIds.MAP,
            label = "MAP",
            unit = MeasurementUnit.KPA,
            pid = MAP_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.FAST,
            parse = { data -> VendoredSaeScaling.pressureKpa(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /**
     * Barometric pressure, `0133`, `A` kPa absolute. `SLOW` because it only moves with
     * elevation and weather — the other half of the altitude-correct boost calculation.
     */
    val baro: StandardPidSpec =
        spec(
            id = PidIds.BARO,
            label = "Baro",
            unit = MeasurementUnit.KPA,
            pid = BARO_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.SLOW,
            parse = { data -> VendoredSaeScaling.pressureKpa(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /** Intake air temperature, `010F`, `A − 40` °C. **Unsupported on this van** — see [intakeAirTempSensor]. */
    val intakeAirTemp: StandardPidSpec =
        spec(
            id = ProtocolPidIds.IAT,
            label = "Intake Air",
            unit = MeasurementUnit.CELSIUS,
            pid = IAT_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.SLOW,
            parse = { data -> VendoredSaeScaling.temperatureCelsius(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /**
     * Intake air temperature, extended dual-bank PID `0168` sensor 1, `A2 − 40` °C — the IAT this
     * van actually answers (OBD-56). Standard `010F` ([intakeAirTemp]) returns `NO DATA`; `0168`
     * fills the gap and is the charge-air temperature the speed-density boost model reads.
     *
     * **Framing.** `0168` is the dual-bank form: a leading support/bank byte (data index 0)
     * then one temperature byte per sensor. Sensor 1 is the **second** data byte (index 1), so
     * `A2 − 40`. Capture `41 68 01 54 00 00 21 …`: index 0 `0x01` (support), index 1 `0x54` → 84 −
     * 40 = **44 °C**. The van pads the frame with non-standard trailing bytes (possibly
     * multi-frame); [dataByteCount] asks for exactly the two bytes through sensor 1 and
     * [ResponseParser] ignores the padding — the parse cannot be broken by however many extra
     * bytes follow.
     *
     * `verified = false`: the decode *format* is high-confidence but the *value* is unconfirmed
     * against ground truth until a 🖐 throttle sweep (IAT ≈ ambient + soak, rising under load).
     */
    val intakeAirTempSensor: StandardPidSpec =
        spec(
            id = ProtocolPidIds.IAT_SENSOR,
            label = "Intake Air",
            unit = MeasurementUnit.CELSIUS,
            pid = IAT_SENSOR_PID,
            dataByteCount = TWO_DATA_BYTES,
            pollPriority = PollPriority.SLOW,
            verified = false,
            parse = { data -> VendoredSaeScaling.temperatureCelsius(VendoredSaeScaling.dataByte(data, 1)) },
        )

    /** Vehicle speed, `010D`, `A` km/h. */
    val speed: StandardPidSpec =
        spec(
            id = ProtocolPidIds.SPEED,
            label = "Speed",
            unit = MeasurementUnit.KMH,
            pid = SPEED_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.FAST,
            parse = { data -> VendoredSaeScaling.speedKmh(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /**
     * Calculated engine load, `0104`, `A × 100 / 255` %.
     *
     * `FAST`: load is the engine's instantaneous answer to the pedal and to grade, moving on the
     * same timescale as rpm and boost. Polling it on the SLOW cadence would show a value from up
     * to five cycles ago next to a live boost needle — two numbers describing the same instant
     * that disagree, which is worse than not showing it.
     *
     * Live-confirmed 2026-08-12 on the OM642 (`8E`/`90` ≈ 56 % at warm idle with AC on, at
     * ~5 800 ft); the `0100` bitmap advertises it.
     */
    val engineLoad: StandardPidSpec =
        spec(
            id = ProtocolPidIds.ENGINE_LOAD,
            label = "Load",
            unit = MeasurementUnit.PERCENT,
            pid = ENGINE_LOAD_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.FAST,
            parse = { data -> VendoredSaeScaling.percent(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /**
     * Throttle position, `0111`, `A × 100 / 255` %.
     *
     * `FAST` for the same reason as [engineLoad] — it is a pedal-rate signal.
     *
     * **Diesel caveat, and it is not a small one.** On the OM642 this PID does not report a
     * driver-commanded throttle plate: a diesel has no throttle butterfly metering power, and
     * `0111` reads the *intake flap* (swirl/EGR actuator), which the 2026-08-12 capture found
     * sitting at `D3` ≈ **83 % at warm idle with the pedal untouched**. Interpreted with gasoline
     * intuition ("83 % throttle at idle") that number is alarming and wrong. It is a real,
     * correctly scaled reading of a different actuator, and it does not travel 0→100 % with the
     * pedal. Anything that thresholds, colours, or labels this channel must say "intake flap",
     * not "throttle", and must not assume idle ≈ 0 %.
     */
    val throttlePosition: StandardPidSpec =
        spec(
            id = ProtocolPidIds.THROTTLE,
            label = "Throttle",
            unit = MeasurementUnit.PERCENT,
            pid = THROTTLE_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.FAST,
            parse = { data -> VendoredSaeScaling.percent(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /**
     * Engine oil temperature, `015C`, `A − 40` °C. **Closes OBD-35**: this is the id
     * `:app`'s `DashboardPids.kt` dashboard oil tile already requests, so wiring it here is the
     * whole fix — no `:app` change beyond that catalog's `verified` flag. Live-verified
     * 2026-08-13 (`81` → 89 °C).
     */
    val oilTemp: StandardPidSpec =
        spec(
            id = PidIds.OIL_TEMP,
            label = "Oil",
            unit = MeasurementUnit.CELSIUS,
            pid = OIL_TEMP_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.SLOW,
            parse = { data -> VendoredSaeScaling.temperatureCelsius(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /** Fuel level input, `012F`, `A × 100 / 255` %. Live-verified 2026-08-13 (`6D` ≈ 42.7 %). */
    val fuelLevel: StandardPidSpec =
        spec(
            id = ProtocolPidIds.FUEL_LEVEL,
            label = "Fuel Level",
            unit = MeasurementUnit.PERCENT,
            pid = FUEL_LEVEL_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.SLOW,
            parse = { data -> VendoredSaeScaling.percent(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /** Ambient air temperature, `0146`, `A − 40` °C. Live-verified 2026-08-13 (`3C` = 20 °C). */
    val ambientTemp: StandardPidSpec =
        spec(
            id = ProtocolPidIds.AMBIENT_TEMP,
            label = "Ambient",
            unit = MeasurementUnit.CELSIUS,
            pid = AMBIENT_TEMP_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.SLOW,
            parse = { data -> VendoredSaeScaling.temperatureCelsius(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /**
     * Accelerator pedal position D, `0149`, `A × 100 / 255` %. Live-verified 2026-08-13 (`0D` ≈
     * 5.1 %).
     */
    val accelPedal: StandardPidSpec =
        spec(
            id = ProtocolPidIds.ACCEL_PEDAL,
            label = "Accel Pedal",
            unit = MeasurementUnit.PERCENT,
            pid = ACCEL_PEDAL_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.SLOW,
            parse = { data -> VendoredSaeScaling.percent(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /**
     * Engine's demand (driver's intended) percent torque, `0161`, `A − 125` %, signed. Live-verified
     * 2026-08-13 (`82` = 5 %).
     */
    val demandTorque: StandardPidSpec =
        spec(
            id = ProtocolPidIds.DEMAND_TORQUE,
            label = "Demand Torque",
            unit = MeasurementUnit.PERCENT,
            pid = DEMAND_TORQUE_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.SLOW,
            parse = { data -> VendoredSaeScaling.torquePercent(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /**
     * Engine's actual percent torque, `0162`, `A − 125` %, signed. Live-verified 2026-08-13
     * (`88` = 11 %).
     */
    val actualTorque: StandardPidSpec =
        spec(
            id = ProtocolPidIds.ACTUAL_TORQUE,
            label = "Actual Torque",
            unit = MeasurementUnit.PERCENT,
            pid = ACTUAL_TORQUE_PID,
            dataByteCount = ONE_DATA_BYTE,
            pollPriority = PollPriority.SLOW,
            parse = { data -> VendoredSaeScaling.torquePercent(VendoredSaeScaling.dataByte(data, 0)) },
        )

    /** Every standard PID in the registry, in a stable declaration order. */
    val all: List<StandardPidSpec> =
        listOf(
            coolant,
            rpm,
            map,
            baro,
            intakeAirTemp,
            intakeAirTempSensor,
            speed,
            engineLoad,
            throttlePosition,
            oilTemp,
            fuelLevel,
            ambientTemp,
            accelPedal,
            demandTorque,
            actualTorque,
        )

    /** The [PidDefinition]s of [all], for handing to a `VehicleDataSource`. */
    val definitions: List<PidDefinition> get() = all.map(StandardPidSpec::definition)

    /** Looks a spec up by its [PidDefinition.id], or `null` if this registry does not define it. */
    fun byId(id: String): StandardPidSpec? = all.firstOrNull { it.definition.id == id }

    /** Looks a spec up by its wire address, or `null` if this registry does not define it. */
    fun byPid(
        mode: Int,
        pid: Int,
    ): StandardPidSpec? = all.firstOrNull { it.mode == mode && it.pid == pid }

    @Suppress("LongParameterList")
    private fun spec(
        id: String,
        label: String,
        unit: MeasurementUnit,
        pid: Int,
        dataByteCount: Int,
        pollPriority: PollPriority,
        parse: (ByteArray) -> Double,
        verified: Boolean = true,
    ): StandardPidSpec =
        StandardPidSpec(
            definition =
                PidDefinition(
                    id = id,
                    label = label,
                    unit = unit,
                    request = ObdRequest.StandardPid(mode = STANDARD_MODE, pid = pid),
                    parse = parse,
                    pollPriority = pollPriority,
                    verified = verified,
                ),
            mode = STANDARD_MODE,
            pid = pid,
            dataByteCount = dataByteCount,
        )

    private const val STANDARD_MODE = 0x01
    private const val COOLANT_PID = 0x05
    private const val RPM_PID = 0x0C
    private const val MAP_PID = 0x0B
    private const val BARO_PID = 0x33
    private const val IAT_PID = 0x0F
    private const val IAT_SENSOR_PID = 0x68
    private const val SPEED_PID = 0x0D
    private const val ENGINE_LOAD_PID = 0x04
    private const val THROTTLE_PID = 0x11
    private const val OIL_TEMP_PID = 0x5C
    private const val FUEL_LEVEL_PID = 0x2F
    private const val AMBIENT_TEMP_PID = 0x46
    private const val ACCEL_PEDAL_PID = 0x49
    private const val DEMAND_TORQUE_PID = 0x61
    private const val ACTUAL_TORQUE_PID = 0x62

    private const val ONE_DATA_BYTE = 1
    private const val TWO_DATA_BYTES = 2
}

/** Positive-response offset applied to the request mode by the ECU: mode `01` answers as `41`. */
internal const val POSITIVE_RESPONSE_OFFSET = 0x40

/**
 * Formats [value] as exactly two uppercase hex digits.
 *
 * Deliberately not `"%02X".format(value)`: `String.format` is locale-sensitive and renders
 * digits in the default locale's numbering system, which would silently corrupt a command
 * string on a device set to e.g. Arabic-Indic digits. `Integer.toHexString` and Kotlin's
 * `uppercase()` are both locale-independent.
 */
internal fun hexByte(value: Int): String = Integer.toHexString(value).uppercase().padStart(2, '0')
