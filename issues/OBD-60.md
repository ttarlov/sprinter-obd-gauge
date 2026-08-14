---
id: OBD-60
title: Trans temp IDENTIFIED — record 21 30 byte 11, °C = raw − 50 (re-arm the gauge)
module: core/protocol
owner: protocol-agent
sprint: boost-wave
status: merged
type: feature
hardware-verify: true
blocked-by: []
branch: protocol/60-transtemp-byte11
---

## What (on-vehicle 2026-08-13, docs/hardware/session-5-2026-08-13-transtemp-IDENTIFIED.md)
The real transmission-fluid-temperature byte in the 722.9 `21 30` record is **byte 11**, decode
**°C = raw − 50**. Identified with a diagnostic build that logged the entire record every poll
cycle — **2495 samples** across a 29-minute warm-restart-through-drive — against coolant (`0105`),
RPM and oil (`015C`) ground truth. This re-arms the gauge that OBD-59 blanked when the byte-1
`63 − raw` candidate was falsified (OBD-55/59).

## Why byte 11 (the evidence)
- **Only byte of 24 that behaves like a warming fluid:** frame-to-frame jitter 0.1 counts (byte 1
  was 25), correlation +0.94 with oil / +0.83 with coolant, bounded 66→99 °C band.
- **The decisive discriminator vs the old "coolant echo" label:** under drive load byte 11 sat
  **25 °C below coolant** and converged as the transmission warmed (2310/2495 samples below coolant,
  zero above). A software mirror of `0105` cannot decouple 25 °C — it is a distinct thermal mass.
- **Offset anchored, not guessed:** the session-2 cold-soak record reads byte 11 − 50 = 27 °C =
  ambient. Nothing reads above cold-soaked ambient, which rules out −40 and pins −50.

The idle-only observations that earlier called byte 11 a coolant echo were aliased the same way the
byte-1 candidate was — ATF ≈ coolant at idle because the trans makes no heat. The drive separates them.

## Fix
- `TcuRecordRegistry.transTempRecord`: `dataByteIndex = 11`, parse = `raw − 50` (via
  `VendoredSaeScaling.dataByte`, unsigned + bounds-checked), `verified = true`. `transTempCelsius`
  is the single arithmetic source. Removed the mislabeled byte-11 `tcuCoolantCelsius` "coolant echo"
  probe and the `TRANS_TEMP_BYTE_UNIDENTIFIED` sentinel.
- `PidCatalog.FALSIFIED_DECODES` → empty (TRANS_TEMP left it); channel is now `Available`/verified.
- `:app` DashboardPids trans tile `verified = true` (mirrors PidCatalog, pinned by the E2E parity test).
- Tests flipped from "gated/refuses/unverified" to "polls/decodes byte 11/verified" across
  KwpRecordParserTest, KwpRecordRequesterTest, PidCatalogTest, RealVehicleDataSourceTest,
  ProdChainEndToEndTest, DashboardUiStateTest, UnverifiedBadgeTest. Added `TcuRecordCaptures.DRIVEN_UNDER_LOAD`
  — a real driven record where byte 11 = 68 °C while coolant was 93 °C — as the regression that a
  future coolant-echo relabel or wrong-offset decode would break. Roborazzi refs re-recorded (Trans
  tile drops its unverified badge).

## Hardware checklist
- [x] Full `21 30` record logged every cycle over a real drive (2495 samples), byte-by-byte analysis
      against coolant/RPM/oil ground truth — `docs/hardware/session-5-2026-08-13-transtemp-IDENTIFIED.md`
      + `docs/hardware/evidence/session-5-transtemp.csv`.
- [x] Byte 11 passes frame-to-frame stability, thermal correlation, and the load-decoupling test that
      falsified the byte-1 candidate; offset anchored to cold-soak = ambient.
- [ ] 🖐 Optional emphatic confirmation: a true cold-start (overnight) drive, where byte 11 lags coolant
      40–60 °C during warm-up. Not required to ship — the warm-restart drive already breaks the echo
      hypothesis — but it would be the final nail. Same drive also gives boost a MAP calibration point.

## Done when
Gate green; trans tile shows a live verified ATF temperature on the van; van APK rebuilt at merge head.
