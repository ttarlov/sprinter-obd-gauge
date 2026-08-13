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
 * MAP being absent is not a cosmetic loss: it is one of the two inputs to [computedBoost], the
 * channel this whole app was built around. The altitude-true `MAP − baro` arithmetic is still
 * correct and still SAE-verified — it simply has nothing to chew on until a Mercedes mode-22 MAP
 * DID is discovered (an OBD-41-style parked discovery session). Baro, the other half, works.
 *
 * So [availabilityOf] reports MAP and IAT as [ChannelAvailability.UnsupportedByVehicle] and
 * boost as [ChannelAvailability.MissingInputs]`(["map"])`, [RealVehicleDataSource] emits that as
 * a [PollEvent.ChannelAvailabilityChanged], and nothing anywhere substitutes a zero. See
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
     * The trans-temp channel is [TcuRecordRegistry.transTempRecord] (OBD-55): the on-vehicle
     * identification of record byte 1 retired the falsified X-Gauge byte-0 decode, so
     * [MercedesPidRegistry.all] no longer contributes a manufacturer channel and `PidIds.TRANS_TEMP`
     * resolves here to the record arm.
     */
    val polled: List<PolledPid> =
        PidRegistry.all.map(PolledPid::Standard) +
            MercedesPidRegistry.all.map(PolledPid::Manufacturer) +
            listOf(PolledPid.Record(TcuRecordRegistry.transTempRecord))

    /**
     * The computed boost channel, `MAP − baro` in kPa. See [ComputedChannels.boost].
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
            parse = { throw UnsupportedOperationException("boost is computed from map − baro, never parsed") },
            pollPriority = PollPriority.FAST,
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
     * Computed boost answers `true`: both of its inputs are SAE-standard PIDs and the subtraction
     * introduces no hypothesis.
     *
     * **This is not the same as "will produce a value here"** — boost is `verified` and, on this
     * van, [ChannelAvailability.MissingInputs]. Ask [availabilityOf] for that. A UI that badges
     * only on this flag will render an unavailable channel as trustworthy-but-silent.
     */
    fun isVerified(id: String): Boolean =
        when (id) {
            PidIds.BOOST -> true
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
            id in UNSUPPORTED_BY_THIS_VEHICLE -> ChannelAvailability.UnsupportedByVehicle(NO_DATA_EVIDENCE)
            id in FALSIFIED_DECODES -> ChannelAvailability.DecodeFalsified(FALSIFIED_EVIDENCE)
            else -> ChannelAvailability.Available
        }
    }

    /**
     * The channels [id] needs polled in order to be produced. Empty for anything directly polled;
     * `map` + `baro` for computed boost.
     *
     * [RealVehicleDataSource] uses this to expand a requested set: a screen asks for `boost` and
     * gets it, without having to know which raw PIDs feed it.
     */
    fun dependenciesOf(id: String): List<String> =
        if (id == PidIds.BOOST) listOf(ProtocolPidIds.MAP, PidIds.BARO) else emptyList()

    /**
     * The channels the 2026-08-12 survey found this vehicle does **not** implement.
     *
     * A falsification list, not an allow-list: absence from it means "no evidence against",
     * never "confirmed present". Adding to it requires a captured `NO DATA`, and the capture
     * belongs in `docs/hardware/` before the id belongs here.
     */
    private val UNSUPPORTED_BY_THIS_VEHICLE: Set<String> = setOf(ProtocolPidIds.MAP, ProtocolPidIds.IAT)

    private const val NO_DATA_EVIDENCE =
        "captured NO DATA from the OM642, engine running, 2026-08-12 " +
            "(docs/hardware/session-2026-08-12.md); the 0100 bitmap does not advertise it"

    /**
     * Channels whose currently-wired decode was falsified against real hardware. Same
     * falsification-list discipline as [UNSUPPORTED_BY_THIS_VEHICLE]: adding an id requires a
     * capture in `docs/hardware/`, and the [applyPoll][RealVehicleDataSource] gate keeps a listed
     * channel off the wire entirely so a decode known to be wrong cannot render a plausible number.
     *
     * **Empty since OBD-55.** `TRANS_TEMP` was the sole entry: the X-Gauge spec read record byte 0
     * (`0x00` → −50 °C on this van). The 2026-08-13 drive test identified the true field at record
     * byte 1 (`63 − raw`, [TcuRecordRegistry]), so the id was reassigned to that live channel and
     * the falsified entry removed. The gate stays — it is the mechanism the next falsified decode
     * (if one is ever found) plugs into — but nothing populates it today.
     */
    private val FALSIFIED_DECODES: Set<String> = emptySet()

    private const val FALSIFIED_EVIDENCE =
        "decode falsified against a docs/hardware/ capture; the true field was not identified"

    private const val BOOST_PLACEHOLDER_MODE = 0x01
    private const val BOOST_PLACEHOLDER_PID = 0x0B
}
