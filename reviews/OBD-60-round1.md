---
issue: OBD-60
round: 1
reviewers: [rev-correctness Tier A (Opus, adversarial + mutation)]
verdict: approved
gate: green
reviewed-commit: 358c4c2
covers: [OBD-60]
---

Tier-A adversarial review of re-arming the transmission-temperature gauge (record `21 30`
byte 11, °C = raw − 50), the same gauge whose byte-1 candidate was falsified on-vehicle (OBD-59).
Reviewed with extra skepticism for a repeat of the aliasing failure. Verdict SHIP WITH NITS; both
nits fixed at 358c4c2 and re-gated green.

## Fix list

### Axis 1 — identification soundness (skeptical read of the evidence): SOUND
- [x] ✅ Load-decoupling argument holds and the CSV backs the doc: byte11−50 is negative vs coolant
      for all 41 downsampled rows, never positive (t=0: 68 vs 93 = −25; t=1707: 99 vs 99 = ±0). A
      software mirror of `0105` cannot sit 25 °C low and converge — requires a distinct thermal mass.
- [x] ✅ Not a repeat of the byte-1 mistake: jitter 0.1 vs 25, 2495 samples, a load transient, two
      independent thermal ground truths. The prior aliasing failure mode is addressed head-on.
- [x] ✅ Offset `raw − 50` anchored by the session-2 five-point lockstep match over a 63 °C span
      (27/30/50/65/90), corroborated by cold-soak = ambient. (Nit fixed: doc now foregrounds the
      five-point match, the robust anchor, rather than the mildly-circular cold-soak argument.)
- [x] ✅ Oil-echo ruled out (sits ~12–15 °C below oil throughout; TCU is the reporter).
- [ ] 🖐 Cold-start confirmation outstanding — acknowledged non-blocking in the doc and OBD-60.md.

### Axis 2 — implementation correctness: CORRECT
- [x] ✅ Byte index 11 lands on the `22`-frame first byte (WARM_IDLE 0x8E=142→92 °C, matches fixture).
- [x] ✅ Single arithmetic source: `TcuRecordRegistry.transTempCelsius(Int)`; both the record overload
      and the parse lambda route through it — no duplicated math to drift.
- [x] ✅ Unsigned masking real and load-bearing (`VendoredSaeScaling.dataByte` `and 0xFF`); the test
      uses 0x8E (>127) so a dropped mask reads −114.
- [x] ✅ Wiring flipped in both modules and cross-module pinned: FALSIFIED_DECODES → empty, verified
      = true in protocol + app, E2E parity test pins app == PidCatalog. No dangling removed-symbol refs.

### Axis 3 — mutation kill table: 6/6 KILLED
- [x] ✅ (a) byte 11 → 10/12 — KwpRecordParserTest byte-11 anchor + RealVehicleDataSourceTest 95.0
- [x] ✅ (b) offset 50 → 40 — parser 92, requester 95, driven 68 all break
- [x] ✅ (c) verified true → false — KwpRecordRequesterTest + PidCatalogTest
- [x] ✅ (d) re-gate (TRANS_TEMP back in FALSIFIED_DECODES) — PidCatalogTest + RealVehicleDataSourceTest + E2E
- [x] ✅ (e) drop unsigned mask — parser test uses 0x8E, signed read ≠ 92
- [x] ✅ (f) app verified true → false — E2E parity + DashboardUiStateTest + UnverifiedBadgeTest (badge returns)
- [x] ✅ DRIVEN_UNDER_LOAD provenance honestly documented (reconstructed framing, decode-only use).
      Nit fixed: test renamed to what it actually pins (decode value + idle-vs-load delta), not the
      physical coolant decoupling (coolant is a separate PID, can't be in one record).

Gate: PASS (assembleDebug, test, ktlint, detekt, verifyRoborazzi, assembleDemoDebug, module-isolation)
at 358c4c2.
