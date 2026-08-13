# Hardware Session 4 — 2026-08-13, TRANS-TEMP BYTE-1 DECODE FALSIFIED IN THE FIELD

Taras observed the live trans gauge jumping and reading implausibly low vs coolant/oil.
Investigated on-vehicle (engine running, warming, ~1800 rpm fast idle). CONCLUSION: the
OBD-55 decode (record 21 30 data byte 1, °C = 63 − raw) is WRONG. Byte 1 is a fast-changing
dynamic signal, not a temperature.

## Evidence — byte 1 jumps frame-to-frame at operating RPM
Four `2130` reads seconds apart (16:11), data byte 1 raw: `39, 08, 04, 1C` → under 63−raw
that's 6°C, 55°C, 59°C, 35°C. A temperature does not jump 50°C in seconds. Byte 1 is
dynamic (pressure / slip / duty / current), and the whole record's dynamic fields
(bytes ~3-6, 12-17) swing at operating RPM while sitting near-zero at parked idle — which
is why the SPARSE session-1..3 samples (all at quiet idle/parked states) aliased into a
false "inverse-monotonic temperature."

## Why the original identification failed (my error)
Identification rested on ~6 discrete samples over 2 days, ALL at comparable low-idle/parked
/engine-off conditions. Byte 1 happened to sit at decreasing values as the vehicle warmed
across those quiet snapshots → looked like 63−raw. It was aliasing: too few points, too
similar conditions, no frame-to-frame stability check. A live look at operating RPM
falsified it in seconds. Lesson: NEVER identify a temperature from sparse snapshots without
a frame-to-frame stability check + a full warmup curve.

## Byte 11 — a candidate, but NOT trusted
Controlled read (16:15): coolant (0105) = 93°C; record byte 11 = 0x79 = 71°C (stable across
reads, was 0x70=62°C at 16:11 → rising). Rising-toward-coolant-from-below is trans-like.
BUT this CONTRADICTS session-3's claim that byte 11 tracked coolant 1:1 during warmup. The
contradiction means I do not understand this record; byte 11 will NOT be shipped on this
evidence. Same mistake I just made if I did.

## The RIGHT method (OBD-51 reframed): logged full-record identification
Next van session (engine cold-start → warmup → drive): log the ENTIRE 21 30 record every
few seconds WITH simultaneous coolant (0105) ground truth, screen-on/WiFi. Then offline:
find the byte that (a) is stable frame-to-frame, (b) rises slowly + monotonically, (c) sits
below coolant during warmup and converges when hot, (d) plausible magnitude. If no byte
qualifies, trans temp is not cleanly in record 0x30 and we sweep other TCU records (21 31,
21 33 already seen; more) or conclude it's not available over OBD. NO gauge ships until a
byte passes ALL four on a full curve, not snapshots.

## Immediate correction (OBD-59)
Re-gate PidIds.TRANS_TEMP to unavailable (tile shows "—") — honest beats wrong-but-badged.
The byte-1 decode is retired. OBD-55 is effectively reverted at the decode level.
