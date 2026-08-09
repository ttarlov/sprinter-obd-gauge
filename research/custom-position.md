# Position: Build a custom ELM327 layer (Agent R2)

## The case

The scope isn't "write an OBD library" — it's the intersection of what `ObdLink`/`ObdRequest`/`PidDefinition`
(already frozen in `:core:model`) and `FakeObdLink` (already built, `:core:testing`) demand: an init
handshake, six mode-01 PIDs with linear SAE formulas, two or three mode-22 PIDs with custom CAN headers, one
tolerant parser, one single-flight scheduler. That's the whole job per OBD-13/14/15/16. Nothing else in the
app touches the wire.

Three things make custom the low-risk choice here, not just the cheap one:

1. **The contract already assumes bespoke framing.** `ObdRequest.Mode22` carries `header`/`rxFilter`/`request`
   as first-class fields — someone already designed for hand-rolled ATSH/ATCRA control, not a library's PID
   abstraction. Mode-22-with-custom-headers is also the entire reason the app exists (Mercedes temps aren't on
   standard mode-01); per R3's brief this is the one constraint most third-party libraries fail. Building it
   custom isn't "instead of" adopting a library — it's building the one part that has to be hand-written
   regardless of Phase-1's outcome. A library only buys back the standard-PID third of the work.
2. **The test harness is a sunk cost, already paid.** `FakeObdLink` replays transcripts, injects
   `Garbage`/`NoData`/`Stopped`/`Timeout`/`MidResponseDisconnect` faults, and enforces strict half-duplex —
   all free to a custom layer building directly against `sendRaw()`. A wrapped library either needs its own
   adapter tested separately or borrows this harness anyway, at which point the library added an indirection
   layer without buying new test coverage.
3. **No stream-adapter tax.** `sendRaw(command, timeout): String` already models exactly what GATT gives you —
   write, then suspend until a terminator or timeout, with fragment reassembly pushed to `:core:ble`. Libraries
   in this space (`obd-java-api` and forks) are written for Classic Bluetooth `InputStream`/`OutputStream`;
   bridging GATT notifications into that shape is real code that a custom layer skips entirely.

Total surface is small enough that "minimal" is not a euphemism: six mode-01 PIDs with linear byte formulas
(no lookup tables, no bit-flag decoding), two to three mode-22 PIDs, one init sequence, one scheduler loop.

## Scope table

| Component | Est. LOC (prod) | Test burden |
|---|---|---|
| Init state machine (ATZ→ATE0→ATL0→ATS0→ATSP0→0100 + typed `InitFailure`) | ~120 | ~8 cases: happy path + one per failure mode (timeout, garbage, unexpected banner, no-protocol) + cancel/restart |
| Standard PID registry (6 defs: coolant, RPM, MAP, baro, IAT, speed) | ~80 | ~12 cases: one reference-value check per PID, plus NO DATA/SEARCHING/STOPPED handling |
| Mode-22 framing + Mercedes PID defs (2-3 PIDs, ATSH/ATCRA, byte extraction) | ~150 | ~8 cases: header-set sequencing, generic byte-extraction, trans-temp round-trip vs. synthetic transcript, `unverified` flag propagation |
| Tolerant response parser (multi-frame, echo strip, whitespace/CR, `?`, garbage) | ~130 | ~15 cases + 1 property test (never-throws on arbitrary bytes); this is the highest-value test investment in the module |
| Poll scheduler (single-flight, FAST/SLOW cycle, boost = MAP − baro) | ~110 | ~10 cases: ordering over simulated ticks, 3 baro fixtures (sea level/6k/10k), cancellation-safety |
| **Total** | **~590 LOC** | **~53 cases**, target >90% line coverage on parser + scheduler (per OBD-14) |

That's a module small enough to read start-to-finish in one sitting — which matters more than any abstraction
a library would provide, for a hobby project with a bus factor of one.

## Conceded weaknesses

- **SAE mode-01 formulas are simple but unverified by anyone but us.** RPM's `((A*256)+B)/4`, temp's `A-40` —
  trivial to write, trivial to get subtly wrong (byte-order swap, dropped `/4`) and have it produce a
  plausible-but-wrong number that isn't caught until it's compared against a dash readout in the van. A
  mature library has these formulas battle-tested across thousands of vehicles; ours has whatever reference
  values we thought to write down.
- **Mode-22 MTH scaling isn't a published spec at all.** It's ported from X-Gauge community formulas by
  inference, not documentation. "Verified" only means "matches a synthetic transcript we authored ourselves"
  until Sprint 3 hardware confirms it — the `unverified` flag on `PidDefinition` exists precisely because this
  risk is real, not hypothetical.
- **The tolerant parser only handles the fault modes we thought to inject.** `FakeObdLink`'s fault list
  (`Garbage`, `NoData`, `Stopped`, `Timeout`, `MidResponseDisconnect`) is our own list, not years of accumulated
  bug reports from a library's issue tracker. A dongle quirk nobody anticipated surfaces at hardware bring-up,
  not before.
- **Zero upstream.** Every fix is ours, forever, with no community to search when a new dongle firmware behaves
  oddly. For a hobby project this is an acceptable trade against control, but it is a real, permanent cost.

**Position: build custom**, scoped exactly to init + 6 mode-01 PIDs + 2-3 mode-22 PIDs + one parser + one
scheduler, against the already-frozen `ObdLink` contract — nothing broader.

## Rebuttal

1. **kotlin-obd-api's parser/mutex work — partial concession.** It's real prior art and worth reading before
   writing our own tolerant parser: `Exceptions.kt` already enumerates the `SEARCHING`/`NO DATA`/`STOPPED`/`?`
   cases our parser must also handle, so treating it as a checklist lowers the risk of missing an edge case.
   But R3 confirms `ObdDeviceConnection` owns `InputStream`/`OutputStream` directly rather than consuming
   anything shaped like `ObdLink.sendRaw()` — so none of that mutex/parser code is reachable without a
   ~100-150 LOC adapter that itself needs its own tests (R3, C2: "close to a wash"). The debugged edge cases
   reduce our *risk*, not our *LOC or test count*.
2. **Vendoring the SAE formula tables — conceded, folded in.** This neutralizes real risk. The six mode-01
   formulas are simple and kotlin-obd-api's `engine/`/`temperature/` packages already encode them correctly
   (R3, C5). Amending the plan: cite and cross-check `PidDefinition.parse` lambdas for the six standard PIDs
   against kotlin-obd-api's formula constants (Apache-2.0, attributed in comments) instead of deriving from
   the SAE spec cold. This doesn't touch mode-22 — no Mercedes formulas exist in the library to borrow, so the
   MTH-derivation risk conceded above is unchanged.
3. **R3's C4 (maintenance, 10%) is too generous to custom.** Scoring library and custom both 7/10 treats them
   as symmetric, but kotlin-obd-api has four contributors and commits through 2026-07-31; our custom layer has
   a bus factor of exactly one hobby project with zero upstream. Steelmanning against my own position: C4
   should score custom closer to 5-6, not 7. At 10% weight this moves the total from 8.95 to ~8.75 — directionally
   honest, doesn't change the recommendation.

**Final position: amended, not reversed.** Build custom for init, mode-22 framing, parser, and scheduler as
scoped above; additionally vendor kotlin-obd-api's standard-PID SAE formula constants (cited, Apache-2.0) to
retire the hand-derived-formula risk on the six mode-01 PIDs.
