# Hardware Session 3 — 2026-08-13, TRANS TEMP IDENTIFIED (drive test)

Engine running throughout. Live-gauges app milestone hit (coolant 185°F, oil 156°F,
RPM 723 + sparkline — first real-engine read of the prod app). Then: boost DID probes
(all dead, below), TCU record sweep, and the decisive trans-temp drive test.

## ★ TRANS TEMP = record 21 30, DATA BYTE 1 (0-indexed after 61 30). IDENTIFIED.
Byte 1 tracked thermal state inversely + monotonically across four states, and stayed
DECOUPLED from coolant (byte 11) — the discriminator that kills the coolant-echo confusion
every commercial tool fell for:

| State | byte1 raw | byte11 (coolant) | notes |
|---|---|---|---|
| Cold engine-off (AM) | 0x2D (45) | — | 63−45 = 18°C ≈ ambient (65°F day) — the ONE independent anchor |
| Cold idle warming | 0x28→0x27→0x21 | rising | raw falls as temp rises |
| Warm idle (13 min) | 0x23 (35) | 0x8C (90°C) | 63−35 = 28°C |
| **Post 10-min drive** | **0x12 (18)** | **0x91 (95°C)** | **63−18 = 45°C** |
| +90s heat-soak | 0x12 (18) | 0x8F (93°C) | byte1 STEADY while coolant DROPS → decoupled, proves not-coolant |

**Model: ATF °C = 63 − raw** (inverse; standard Mercedes 1°C/count assumed for slope).
- Offset SOLID: anchored on cold-soak = ambient (raw 45 → 18°C).
- Slope PROVISIONAL: only one independent ground-truth point exists; 1°C/count assumed
  (Mercedes convention, matches coolant's raw−50 magnitude). High-temp behavior (wrap /
  saturation above ~63°C) UNTESTED — the gentle drive only reached ~45°C.
- 🖐 TO FULLY NAIL SCALING: one reading with ATF >60°C (a sustained grade / tow / spirited
  pull), ideally cross-checked against a STAR/Xentry ATF reading for a 2nd anchor.

Byte 18 = engine-run status bitfield (confirmed again, 0x86). Byte 11 = coolant echo
(confirmed, tracks 0105). Bytes 12-15 = converter pair (FFFF parked, small signed driving).

## TCU service-21 record map (7E1) — swept this session
- 21 30 → telemetry (coolant echo, trans temp byte1, status) — THE record
- 21 31 → shaft speeds: bytes 4-7 = 0x02B1/0x02D1 = 689/721 ≈ idle rpm (trans input/output
  speed — matches research; future gauge candidate)
- 21 33 → multi-value (0x08C6, 0x03E8=1000 ref?, speeds) — uncataloged
- 21 01, 21 20 → rejected (7F 21 12); 21 90 → rejected (prior session)

## Boost — DEAD, definitively (D8 targeted test executed on-vehicle)
- `10 03` at 7E0 → `50 03` POSITIVE (extended session entered, confirmed).
- `22 20 C4` inside a confirmed-live extended session (3.4s after 50 03) → still `7F 22 31`
  requestOutOfRange. ScanGauge's NCV3 boost DID does NOT exist. Dead in default AND
  extended session.
- Service 21 at 7E0 → `7F 21 11` service-not-supported (engine ECU has no service 21).
- Verdict: no native boost over generic OBD on this van, on-vehicle confirmed. Matches the
  research strong-negative. Remaining paths (full 22-sweep likely security-gated, Xentry
  sniff, aftermarket sensor) are out of scope / new decisions. Boost tile stays honestly —.

## App-quit bug (noted, no trace)
Taras reopened the app and it quit; no crash trace in logcat crash/main buffers. Filed for
repro — likely the connect-on-launch path. OBD-54.
