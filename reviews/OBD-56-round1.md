---
issue: OBD-56
round: 1
reviewers: [rev-correctness Tier A (Opus)]
verdict: approved
gate: green
reviewed-commit: e06eaf3
covers: [OBD-56, OBD-57]
---

Tier-A review of the speed-density boost engine (ships dormant; OBD-58 flips it live).

## Fix list
- [x] ✅ MAF 0166 (256B+C)/32 = 14.21875 g/s and IAT 0168 sensor-1 A2−40 = 44°C decoded
      exactly against captures (full precision, guards a /16 or /64 slip); 0168 padding
      tolerance real (requests 2 bytes, trailing van padding can't shift sensor-1).
- [x] ✅ Speed-density physics structurally correct; SPEED_DENSITY_CONSTANT=34.44 DERIVED
      from named factors (R·120, the 1000·0.001 g/kg·m³/L pair cancels) with a real
      unit-tracking test. Reviewer independently reproduced MAP=84.29 kPa → boost 0.33 psi
      at the idle anchor, round-trip exact.
- [x] ✅ Spike defense sound: guard order null→(rpm≤0||maf≤0)→divide→isFinite→clamp[20,260].
      Every degenerate input (RPM 0/neg, MAF 0/neg, NaN/Inf, baro null) → typed
      unavailable; extreme-finite → bounded clamp. All 4 builder mutations + the
      combined-guard probe + 4 reviewer mutations (invert RPM, VE→0, swap clamp, drop
      baro-guard) KILLED.
- [x] ✅ VE(RPM) table: positive breakpoints 0.85–1.05, linear, flat-extrapolated, no
      discontinuity/≤0; >1.0 peak correct for boosted-NA-referenced VE.
- [x] ✅ OBD-58 flip-preview test exercises the REAL start→publish→speedDensityBoost→Reading
      path via a genuine synthetic MAF channel (not a mock); coupling swept — flip needs
      only the g/s unit + MAF channel + removal from PENDING sets, compute path unchanged.
- [x] ✅ Honesty: isVerified(BOOST)=false, "Est.", verified/available asserted as
      independent axes; KDoc flags VE re-fit + pre-turbo IAT caveat; nothing claims
      calibrated truth.

## Accepted LOW findings (no blockers)
- LOW-1: near-stall RPM (50–200) + stale-high MAF → one-frame ~25 psi clamp flash on hard
  decel. Bounded <40psi, badged, stale-flagged, clamp-vs-reject defensible. → note for
  OBD-57b: a display-side rate-limit/smoothing would suppress the transient flash better
  than a protocol-side reject (which would flicker unavailable during normal transients).
- LOW-2: baro lacks an independent sanity clamp — explicitly OBD-57b (:app) scope; flagged
  for that reviewer.

283 protocol tests green; gate PASS. :app verified-flag mirror (8 files) accepted under
arbitration (OBD-53 tracks the duplication).

Verdict: **approved** at e06eaf3. Merge target: main. Live-flip: OBD-58.
