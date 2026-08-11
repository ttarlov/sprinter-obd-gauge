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
 */
object PidCatalog {
    /** Every channel that is actually polled: standard PIDs first, then manufacturer PIDs. */
    val polled: List<PolledPid> =
        PidRegistry.all.map(PolledPid::Standard) + MercedesPidRegistry.all.map(PolledPid::Manufacturer)

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
     * Whether the channel [id] has been confirmed against real hardware.
     *
     * Unknown ids answer `false` — an id this module cannot vouch for is not verified, and the
     * conservative answer is the one that makes the UI show a caveat rather than hide one.
     * Computed boost answers `true`: both of its inputs are SAE-standard PIDs and the subtraction
     * introduces no hypothesis.
     */
    fun isVerified(id: String): Boolean =
        when (id) {
            PidIds.BOOST -> true
            else -> byId(id)?.definition?.verified ?: false
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

    private const val BOOST_PLACEHOLDER_MODE = 0x01
    private const val BOOST_PLACEHOLDER_PID = 0x0B
}
