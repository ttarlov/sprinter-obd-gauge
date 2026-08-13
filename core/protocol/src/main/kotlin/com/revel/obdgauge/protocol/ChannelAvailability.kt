package com.revel.obdgauge.protocol

/**
 * Whether a channel can produce a value **on this vehicle** — the axis
 * [com.revel.obdgauge.model.PidDefinition.verified] deliberately does not cover.
 *
 * The two questions are independent and this module keeps them that way:
 *
 * | | `verified` | [ChannelAvailability] |
 * |---|---|---|
 * | asks | is our request + scaling right? | will this van answer at all? |
 * | settled by | SAE J1979 / an X-Gauge decode / a capture | a capture, and only a capture |
 * | e.g. MAP `010B` | `true` — textbook SAE | [UnsupportedByVehicle] — `NO DATA`, 2026-08-12 |
 * | e.g. trans temp | `false` — hypothesis | [DecodeFalsified] — the TCU answers `2130`, but the |
 * | | | X-Gauge byte-0 decode was falsified 2026-08-12 |
 *
 * They were conflated in exactly one place before OBD-43 — the boost channel — and the
 * consequence is the reason this type exists. Boost is `MAP − baro`; MAP answers `NO DATA` on
 * this van; so boost has no inputs and no value. Without a typed way to say that, a boost gauge
 * has only two options, and both are wrong: show nothing and look like a UI bug, or show the
 * subtraction's identity element and read **0 PSI on a mountain grade** — a number that is
 * indistinguishable from "no boost, engine not pulling" and is the single failure mode this
 * module exists to prevent. So the answer is neither: the channel reports, in types, that it is
 * unavailable and which input is missing.
 *
 * Produced by [PidCatalog.availabilityOf] and reported by
 * [PollEvent.ChannelAvailabilityChanged]; consumers (OBD-27's badge, the debug console) render it.
 */
sealed interface ChannelAvailability {
    /** The channel can produce values: the vehicle answers it, or its inputs do. */
    data object Available : ChannelAvailability

    /**
     * The vehicle answered `NO DATA` when this PID was surveyed against real hardware, so the
     * channel will never produce a value here however correct its decode is.
     *
     * @param evidence what was observed, and when — this is a claim about one specific van, and
     *   it must stay auditable rather than becoming folklore in a `when` branch.
     */
    data class UnsupportedByVehicle(
        val evidence: String,
    ) : ChannelAvailability

    /**
     * A computed channel whose inputs are not all available, so the derivation cannot run.
     *
     * @param missing the ids of the inputs that are unavailable, in [PidCatalog.dependenciesOf]
     *   order. Never empty — an empty list would be [Available].
     */
    data class MissingInputs(
        val missing: List<String>,
    ) : ChannelAvailability

    /**
     * The vehicle answers the channel's request, but the DECODE currently wired to this id was
     * falsified against real hardware — the extracted byte provably does not hold the claimed
     * quantity. Publishing it would render a plausible-looking wrong number, the charter failure
     * mode, so [RealVehicleDataSource] refuses to store its values (the request itself is not
     * sent for these ids either — unlike [UnsupportedByVehicle], there is no discoverability
     * value in re-observing an answer whose meaning is already known to be misread).
     *
     * Distinct from [UnsupportedByVehicle] on purpose: that verdict says "this van will not
     * answer"; this one says "it answers, and we know we are reading it wrong". A UI can render
     * the difference ("unavailable" vs "pending verification").
     *
     * @param evidence the capture that falsified the decode, and where the correct decode lives.
     */
    data class DecodeFalsified(
        val evidence: String,
    ) : ChannelAvailability

    /**
     * The vehicle answers the channel and its decode is known, but the value **cannot be
     * published yet** because the frozen `:core:model`
     * [com.revel.obdgauge.model.MeasurementUnit] enum has no member for its quantity — so no
     * [com.revel.obdgauge.model.PidDefinition] can carry it and no [StandardPidSpec] can poll it.
     *
     * Distinct from every other verdict, and the distinction matters: this is not "the van won't
     * answer" ([UnsupportedByVehicle]) nor "we read it wrong" ([DecodeFalsified]) nor "an input is
     * absent" ([MissingInputs]) — the van answers and the arithmetic is proven (its anchor test is
     * green in [VendoredSaeScaling]). The only thing missing is a *unit contract*, an additive
     * `:core:model` change that this module will not make on its own authority.
     *
     * The mass-airflow channel ([ProtocolPidIds.MAF], g/s) is the live case: it is a dependency of
     * the speed-density boost estimate, so boost degrades to [MissingInputs]`(["maf"])` until the
     * unit lands. When it does (a scoped `:core:model` + `:app` change), MAF becomes an ordinary
     * polled channel and boost auto-flips to [Available] with no change here — the clean seam this
     * verdict exists to hold open. `015E` fuel-rate (L/h) and `0142` module-voltage (V) sit in the
     * same waiting room.
     *
     * @param quantity the unit the frozen enum is missing, e.g. `"g/s"` — auditable, so the seam
     *   names exactly what it is waiting on.
     */
    data class PendingUnitContract(
        val quantity: String,
    ) : ChannelAvailability
}
