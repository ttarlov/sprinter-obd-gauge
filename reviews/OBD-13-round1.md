---
issue: OBD-13
round: 1
issues-covered: [OBD-13, OBD-14]
reviewers: [rev-correctness, rev-arch]
verdict: approved
gate: green
reviewed-commit: 405bd33
---

Tier-A round 1 on `protocol/13-14-init-registry` (rev-platform not required: pure-JVM module
per doc 04 §1 matrix). rev-arch: approved, all 7 checks clean. rev-correctness: APPROVE with
findings at faf7f4c — all ACs map to biting tests, six SAE scalings independently re-derived
and confirmed, vendoring provenance verified byte-for-byte against upstream @30014eb, the
pre-approved rpm deviation (SAE-exact float divide where kotlin-obd-api truncates quarter-rpm)
verified real/correct/documented in all three places, 7 mutations run (6 caught + 1 incidental).

## Fix list
Reviewer findings, fixed pre-merge in 405bd33 (orchestrator-authored, reviewer-specified —
the MAJOR is the module's charter failure mode and does not merge unfixed at Tier A):
- [x] M1 ✅ (MAJOR) truncated-frame-then-complete-frame yielded a plausible wrong value
  (probe: "41 05\r41 05 5A" → 25°C instead of 50°C). Fixed with per-line-first framing +
  joined fallback exactly as the reviewer specified (they walked all existing tolerance
  cases); two regression tests added (coolant + rpm twins) — failed before, pass now;
  joined-only mutation re-run: FAILS the suite.
- [x] N1 ✅ property-test KDoc overclaim scoped honestly (range check is by-construction;
  mis-framing pinned by dedicated tests instead)
- [x] N2 ✅ retry policy directly asserted: garbage-ATZ test now asserts commands == [ATZ]
- [x] N3 ✅ arrival-order wording fixed in KDoc
- [x] N4 ✅ ?-suffix caveat documented in MODULE.md
- [x] N5 ✅ headers-on non-guarantee documented in MODULE.md (explicit note for OBD-15)

## Reviewer-verified (at faf7f4c; fixes verified by orchestrator rerun at 405bd33)
88→90 tests, 0 failures; 31-case tolerance/refusal probe matrix all fail-safe; byte-alignment
claim proven by hostile probes; init retry policy both-sided; PidIds keying consistent;
detekt+ktlint clean. Mutation table in the reviewer transcript: coolant offset (18 fails),
rpm divisor (3), noise-strip (2, property tests the sole catcher), zero-padding (2),
alignment (1), step-order swap (18), retry-on-rejection (1 incidental → now direct).
