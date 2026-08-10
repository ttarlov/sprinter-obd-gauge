---
issue: OBD-11
round: 1
issues-covered: [OBD-11, OBD-12]
reviewers: [rev-correctness+rev-platform combined (Tier B, doc 05 §5.5)]
verdict: changes-requested
gate: green
reviewed-commit: ccdfc82
---

Tier-B combined-lens review of the batched `ui/11-12-banner-demo-flavor` branch. Arch-lens
checks (isolation, commit hygiene, MODULE.md currency) script-verified by the orchestrator.
Reviewer ran three source mutations; two exposed gaps (findings 1-2).

### [MAJOR] M1 — Demo clock's "residual limitation" triggers on every background round-trip
**Where:** src/demo di/DataSourceModule.kt KDoc; MODULE.md
**What:** `WhileSubscribed(5_000)` + `onStart/onCompletion` means backgrounding >5 s restarts
the fake (virtual clock → EPOCH) while the one-shot anchored Clock keeps advancing — stale age
then reads time-since-launch. Latent only because GRADE_CLIMB never goes stale.
**Required fix:** demo-local decorator recording wall-clock at each `start()`; Clock reads
`EPOCH + (now − lastStart)`. Or document plainly. (Decorator chosen.)

### [MAJOR] M2 — Clock fix has zero regression coverage
**What:** mutation: reverting provideClock to `Clock.systemDefaultZone()` (the original bug
verbatim) leaves all 45 demo tests green. The DI-provided clock is never exercised.
**Required fix:** testDemo test asserting the provided clock reads ≈ Instant.EPOCH.

### [NIT] N1 — "Distinct visual treatment" only semantics-verified (colors/spinner mutation
survives all tests + Roborazzi, since references render at Ready where the banner is absent).
Honestly disclosed in MODULE.md — accepted as a nit; screenshot-at-error-state welcome later.
### [NIT] N2 — Three source files cite "HARD CONSTRAINT in issues/OBD-12.md" — it lives in
app/MODULE.md.
### [NIT] N3 — DashboardScreenshotTest class KDoc still says `src/test/screenshots/`.

## Fix list
- [ ] M1: re-anchoring decorator + KDoc truth
- [ ] M2: provideClock regression test (must fail under systemDefaultZone mutation)
- [ ] N2/N3: reference fixes (N1 accepted-as-disclosed, no action this round)

## Reviewer-verified (round 1, at ccdfc82)
All ACs map to artifacts; banner walk test state-discriminating (mutation-proved); flavor
relocation assertion-neutral (100% rename similarity); prod classpath clean (dependencies +
dex strings); StubVehicleDataSource honors the pinned restart contract; no banner flicker on
non-Ready transitions; 45 demo + 11 prod tests green; both APKs build.

## Author responses (round 1)
- M1 ✅ fixed — `RestartAnchoredDataSource` (src/demo) records wall-clock at each `start()`;
  `epochSinceStartClock()` reads `EPOCH + (now − lastStart)`. DI binds source + clock from it.
  KDoc rewritten to state the WhileSubscribed restart reality instead of calling it residual.
- M2 ✅ fixed — `DataSourceModuleClockTest` (2 tests): provided clock ≈ EPOCH at provisioning
  AND re-anchors ≈ EPOCH after a delayed restart. Fails under the systemDefaultZone mutation
  by construction (~56-year delta).
- N2 ✅ fixed — three cross-references now point at app/MODULE.md.
- N3 ✅ fixed — screenshot-dir KDoc corrected.
- N1 — accepted-as-disclosed per review, no action this round.
(Fixes orchestrator-authored under budget wind-down, same precedent as OBD-10 round 3;
fresh-context reviewer verifies the delta.)

## Author responses (round 2)
- N3 ✅ actually fixed in this commit (round-1 sed pattern missed the line — reviewer caught it).
- R2-1 (MODULE.md misdescribing the superseded clock design) ✅ fixed — Known-limitations
  entry rewritten for the decorator, `RestartAnchoredDataSource` added to the demo flavor
  description, `DataSourceModuleClockTest` added to the test inventory.
