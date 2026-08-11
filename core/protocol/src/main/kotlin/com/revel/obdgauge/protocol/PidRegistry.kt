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
 * [PidDefinition.verified] `= false` until hardware-verified. Everything in this registry is
 * `verified = true` by the SAE standard, though "verified against the standard" is not the same
 * as "verified against this van's ECU" — see `MODULE.md`'s known limitations.
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

    /** Intake air temperature, `010F`, `A − 40` °C. */
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

    /** Every standard PID in the registry, in a stable declaration order. */
    val all: List<StandardPidSpec> = listOf(coolant, rpm, map, baro, intakeAirTemp, speed)

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
    private const val SPEED_PID = 0x0D

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
