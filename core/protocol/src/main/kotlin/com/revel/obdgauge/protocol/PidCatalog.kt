package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority

/**
 * A channel the scheduler can actually put on the wire, with the wire facts needed to do it.
 *
 * The two arms exist because the request/parse paths genuinely differ — a standard PID is one
 * command, a manufacturer PID is a five-command header-scoped sequence — and modelling that as a
 * sealed type means [RealVehicleDataSource] dispatches on an exhaustive `when` rather than on a
 * nullable cast.
 */
sealed interface PolledPid {
    /** The contract-level definition, shared by both arms. */
    val definition: PidDefinition

    /** A standard mode-01 PID from [PidRegistry]. */
    data class Standard(
        val spec: StandardPidSpec,
    ) : PolledPid {
        override val definition: PidDefinition get() = spec.definition
    }

    /** A manufacturer PID from [MercedesPidRegistry], requiring `ATSH`/`ATCRA` framing. */
    data class Manufacturer(
        val spec: Mode22PidSpec,
    ) : PolledPid {
        override val definition: PidDefinition get() = spec.definition
    }

    /**
     * A KWP `21 xx` **record** channel from [TcuRecordRegistry] — the same `ATSH`/`ATCRA` framing
     * as [Manufacturer], but its answer is a multi-frame block a field is read out of by offset,
     * not a single-frame value. The trans-temp channel (OBD-55) rides this arm.
     */
    data class Record(
        val spec: KwpRecordSpec,
    ) : PolledPid {
        override val definition: PidDefinition get() = spec.definition
    }
}

/**
 * The single lookup surface over every channel this app knows: the standard PIDs
 * ([PidRegistry]), the Mercedes hypotheses ([MercedesPidRegistry]), and the computed boost
 * channel.
 *
 * Two jobs, both of which exist because the frozen contracts in `:core:model` deliberately do not
 * carry them:
 *
 * 1. **Wire facts by id.** `PidDefinition` has no data-byte count and no response header — it is
 *    the UI-facing contract — so [RealVehicleDataSource] resolves an id to a [PolledPid] here.
 *    `start(pids)` is therefore a *selection by id*: the framing and scaling always come from this
 *    catalog, never from a caller-supplied lambda, so a placeholder definition passed in by a
 *    screen cannot change how a value is computed.
 * 2. **Verification status by id** ([isVerified]) — the hook OBD-27 badges unverified gauges
 *    from. `Reading` carries no `verified` flag and is frozen; extending the registry instead of
 *    the contract is the sanctioned move, and it is the right one anyway: whether a PID is a
 *    hypothesis is a property of the *definition*, constant across every reading it ever
 *    produces, so putting it on each `Reading` would be repeating a static fact thousands of
 *    times a minute.
 * 3. **Vehicle availability by id** ([availabilityOf]) — a *different* question from (2), added
 *    by OBD-43 once real hardware answered. See below.
 *
 * ## What the 2026-08-12 capture did to this catalog
 *
 * `docs/hardware/session-2026-08-12.md`, engine running, OM642 at ~5 800 ft:
 *
 * - **`010B` MAP → `NO DATA`. Not supported on this vehicle.** The `0100` bitmap does not
 *   advertise it and the ECU does not answer it.
 * - **`010F` IAT → `NO DATA`. Not supported either**, and the bitmap agrees.
 * - Coolant, rpm, engine load, throttle and baro all answered; speed is bitmap-advertised.
 *
 * MAP being absent was the whole reason boost had nothing to chew on. **OBD-57 changed the model:**
 * MAP is no longer read from `010B`, it is *computed* from speed density
 * ([ComputedChannels.speedDensityBoost]) out of mass airflow (`0166`), charge temp (`0168`), rpm
 * and baro — the extended sensor PIDs the survey missed but this van answers. Boost's inputs are
 * therefore [ProtocolPidIds.MAF], [ProtocolPidIds.IAT_SENSOR], [PidIds.RPM] and [PidIds.BARO], and
 * boost is now an estimate ([isVerified] `= false`, "Est."), not a verified subtraction.
 *
 * So [availabilityOf] reports the old `010B`/`010F` MAP and IAT as
 * [ChannelAvailability.UnsupportedByVehicle] (kept for the survey record). Mass airflow *was*
 * [ChannelAvailability.PendingUnitContract] (`0166` answered, but g/s had no frozen unit) and boost
 * *was* [ChannelAvailability.MissingInputs]`(["maf"])`. **OBD-58 landed the g/s unit** (DECISIONS.md
 * D9): MAF is now a live [PidRegistry] channel, so [availabilityOf] reports it — and, all four
 * inputs being available, boost — as [ChannelAvailability.Available]. Nothing anywhere substitutes a
 * zero; when an input simply does not answer at runtime, boost is absent, not zeroed. See
 * [ChannelAvailability] for why "show 0 PSI" is the outcome being engineered against.
 *
 * These verdicts are about **this van**. They are recorded here, next to the registry, rather
 * than in a UI-side allow-list, because "which PIDs does the vehicle implement" is protocol
 * knowledge, and because a second vehicle would want a second table — not a scattering of `if`s.
 */
object PidCatalog {
    /**
     * Every channel that is actually polled: standard PIDs, then manufacturer PIDs, then KWP
     * record channels.
     *
     * The trans-temp channel is [TcuRecordRegistry.transTempRecord] — the `21 30` record — but it is
     * in [FALSIFIED_DECODES] (OBD-59): its byte-1 `63 − raw` decode was falsified on-vehicle, so the
     * channel is resolvable and announced but [RealVehicleDataSource]'s gate keeps it off the wire
     * and stores no value. It stays in this list, rather than being dropped, so the record machinery
     * is wired for OBD-51's re-identification and so removing it from the gate would visibly resurrect
     * a wire poll — the gate is load-bearing, not decorative.
     */
    val polled: List<PolledPid> =
        PidRegistry.all.map(PolledPid::Standard) +
            MercedesPidRegistry.all.map(PolledPid::Manufacturer) +
            listOf(PolledPid.Record(TcuRecordRegistry.transTempRecord))

    /**
     * The computed boost channel, kPa. Since OBD-57 its MAP half is the speed-density estimate
     * ([ComputedChannels.speedDensityBoost]), so `verified = false` ("Est.") — see [isVerified].
     *
     * `PidDefinition.request` and `parse` are structurally required by the frozen contract but
     * meaningless here — boost is never requested and never parsed. Rather than point them at
     * MAP's wire address (which would let a careless consumer poll MAP and publish it *as* boost,
     * exactly the plausible-wrong-number failure this module exists to prevent), `parse` refuses.
     * [RealVehicleDataSource] routes by id and never calls it; anything that does call it gets a
     * typed [ParseFailure.ScalingError] from [scaleReading], never a number.
     */
    val computedBoost: PidDefinition =
        PidDefinition(
            id = PidIds.BOOST,
            label = "Boost",
            unit = MeasurementUnit.KPA,
            request = ObdRequest.StandardPid(mode = BOOST_PLACEHOLDER_MODE, pid = BOOST_PLACEHOLDER_PID),
            parse = { throw UnsupportedOperationException("boost is computed from speed density, never parsed") },
            pollPriority = PollPriority.FAST,
            verified = false,
        )

    /** Every definition this catalog knows, polled and computed, for handing to a data source. */
    val definitions: List<PidDefinition> get() = polled.map(PolledPid::definition) + computedBoost

    /** Resolves a polled channel by [PidDefinition.id], or `null` for computed/unknown ids. */
    fun byId(id: String): PolledPid? = polled.firstOrNull { it.definition.id == id }

    /**
     * Whether the channel [id]'s **request and scaling** are confirmed rather than hypothesised.
     *
     * Unknown ids answer `false` — an id this module cannot vouch for is not verified, and the
     * conservative answer is the one that makes the UI show a caveat rather than hide one.
     *
     * **Computed boost answers `false` since OBD-57.** It used to answer `true`: `010B` MAP − baro
     * is a subtraction of two SAE-standard PIDs that introduces no hypothesis. But `010B` is
     * absent on this van, so boost is now the *speed-density estimate* — a thermodynamic model
     * whose volumetric-efficiency curve ([VolumetricEfficiency]) is uncalibrated and whose charge
     * temperature may be pre-turbo. That is a hypothesis by construction, so it is badged "Est."
     * and stays `verified = false` until a 🖐 VE-calibration drive fits the curve. This is the
     * honest half of shipping a computed boost gauge at all.
     *
     * **This is not the same as "will produce a value here"** — boost is unverified *and*, on this
     * van, [ChannelAvailability.MissingInputs]`(["maf"])` pending the g/s unit. Ask [availabilityOf]
     * for that. A UI that badges only on this flag will render an unavailable channel as
     * caveated-but-silent.
     */
    fun isVerified(id: String): Boolean =
        when (id) {
            PidIds.BOOST -> false
            else -> byId(id)?.definition?.verified ?: false
        }

    /**
     * Whether the channel [id] can produce a value on **this** vehicle, per the hardware survey.
     *
     * Computed channels are answered from their [dependenciesOf], so boost degrades explicitly
     * the moment one of its inputs is known-unsupported instead of quietly never appearing.
     *
     * Unknown ids answer [ChannelAvailability.Available] — the optimistic direction on purpose:
     * this table records *falsified* channels only (a captured `NO DATA`), so "not in the table"
     * means "nothing is known against it", and the honest failure for such a channel is a skipped
     * reading at poll time, not a pre-emptive refusal to try. [isVerified] is the pessimistic
     * side of the pair; the two defaults differ because the two questions do.
     */
    fun availabilityOf(id: String): ChannelAvailability {
        val unsupportedInputs = dependenciesOf(id).filter { availabilityOf(it) != ChannelAvailability.Available }
        return when {
            unsupportedInputs.isNotEmpty() -> ChannelAvailability.MissingInputs(unsupportedInputs)
            id in PENDING_UNIT_CONTRACT -> ChannelAvailability.PendingUnitContract(PENDING_UNITS.getValue(id))
            id in UNSUPPORTED_BY_THIS_VEHICLE -> ChannelAvailability.UnsupportedByVehicle(NO_DATA_EVIDENCE)
            id in FALSIFIED_DECODES -> ChannelAvailability.DecodeFalsified(FALSIFIED_EVIDENCE)
            else -> ChannelAvailability.Available
        }
    }

    /**
     * The channels [id] needs polled in order to be produced. Empty for anything directly polled;
     * the four speed-density inputs for computed boost.
     *
     * [RealVehicleDataSource] uses this to expand a requested set: a screen asks for `boost` and
     * gets it, without having to know which raw PIDs feed it.
     *
     * **OBD-57 rewired this.** Boost is no longer `010B` MAP − baro (that PID answers `NO DATA`
     * here); MAP is now *computed* from speed density, so boost's real inputs are mass airflow
     * ([ProtocolPidIds.MAF]), charge-air temperature ([ProtocolPidIds.IAT_SENSOR]), engine speed
     * ([PidIds.RPM]) and barometric pressure ([PidIds.BARO]) — the arguments of
     * [ComputedChannels.speedDensityBoost]. Since OBD-58 landed the g/s unit all four are live
     * channels, so boost is [ChannelAvailability.Available] (see [availabilityOf]).
     */
    fun dependenciesOf(id: String): List<String> =
        if (id == PidIds.BOOST) {
            listOf(ProtocolPidIds.MAF, ProtocolPidIds.IAT_SENSOR, PidIds.RPM, PidIds.BARO)
        } else {
            emptyList()
        }

    /**
     * The channels the 2026-08-12 survey found this vehicle does **not** implement.
     *
     * A falsification list, not an allow-list: absence from it means "no evidence against",
     * never "confirmed present". Adding to it requires a captured `NO DATA`, and the capture
     * belongs in `docs/hardware/` before the id belongs here.
     */
    private val UNSUPPORTED_BY_THIS_VEHICLE: Set<String> = setOf(ProtocolPidIds.MAP, ProtocolPidIds.IAT)

    /**
     * Channels whose decode is proven but which cannot be published because the frozen
     * `:core:model` [com.revel.obdgauge.model.MeasurementUnit] enum names no unit for their
     * quantity — see [ChannelAvailability.PendingUnitContract].
     *
     * **Empty since OBD-58.** Mass airflow ([ProtocolPidIds.MAF], g/s) was the sole entry: `0166`
     * answered but the frozen enum named no g/s unit, so boost degraded to `MissingInputs(["maf"])`.
     * DECISIONS.md D9 approved the additive units and OBD-58 landed `GRAMS_PER_SECOND`, so MAF got a
     * [PidRegistry] channel ([PidRegistry.maf]), left this set, and boost auto-flipped to
     * [ChannelAvailability.Available] — exactly as this KDoc predicted, with no logic change here.
     * The set (and [availabilityOf]'s branch) stays as the mechanism the next scaling-only channel
     * plugs into, the same way [FALSIFIED_DECODES] does.
     */
    private val PENDING_UNIT_CONTRACT: Set<String> = emptySet()

    /** The frozen-enum-missing unit each [PENDING_UNIT_CONTRACT] channel is waiting on. */
    private val PENDING_UNITS: Map<String, String> = emptyMap()

    private const val NO_DATA_EVIDENCE =
        "captured NO DATA from the OM642, engine running, 2026-08-12 " +
            "(docs/hardware/session-2026-08-12.md); the 0100 bitmap does not advertise it"

    /**
     * Channels whose currently-wired decode was falsified against real hardware. Same
     * falsification-list discipline as [UNSUPPORTED_BY_THIS_VEHICLE]: adding an id requires a
     * capture in `docs/hardware/`, and the [applyPoll][RealVehicleDataSource] gate keeps a listed
     * channel off the wire entirely so a decode known to be wrong cannot render a plausible number.
     *
     * **`TRANS_TEMP` (OBD-59).** OBD-55 briefly emptied this set when it thought it had identified
     * transmission temperature at record byte 1 (`63 − raw`). A live look at operating RPM on
     * 2026-08-13 falsified that in seconds — byte 1 jumps frame-to-frame, it is a dynamic signal not
     * a temperature (`docs/hardware/session-4-2026-08-13-transtemp-FALSIFIED.md`). So `TRANS_TEMP` is
     * back on the gate and the tile blanks to "—". The `21 30` record itself is kept
     * ([TcuRecordRegistry.transTempRecord]) for OBD-51 to re-identify the real byte; only the value
     * is withheld.
     */
    private val FALSIFIED_DECODES: Set<String> = setOf(PidIds.TRANS_TEMP)

    private const val FALSIFIED_EVIDENCE =
        "byte-1 63−raw decode FALSIFIED on-vehicle 2026-08-13 (jumps at operating RPM, " +
            "docs/hardware/session-4-2026-08-13-transtemp-FALSIFIED.md); real trans-temp byte " +
            "not yet identified — OBD-51"

    private const val BOOST_PLACEHOLDER_MODE = 0x01
    private const val BOOST_PLACEHOLDER_PID = 0x0B
}
