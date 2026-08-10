---
id: OBD-9
title: Library-vs-custom decision and DECISIONS.md entry
module: process
owner: orchestrator
sprint: 1
status: merged
type: process
hardware-verify: false
blocked-by: [OBD-6, OBD-7, OBD-8]
branch: orchestrator/9-decision-record
---

## Feature
The library-vs-custom decision is debated, rebutted, and logged as a signed-off entry in `DECISIONS.md`.

## Contract surface
None expected on frozen `:core:model` contracts — but this decision determines the shape of Sprint 2 protocol work (OBD-15 specifically), so it gates downstream scope even though it doesn't itself touch a contract.

## Acceptance criteria
- [ ] Rebuttals to the OBD-6/7/8 positions recorded (comments/notes appended to the respective research files or a debate log)
- [ ] Decision entry merged into `DECISIONS.md` with rationale referencing the rubric score from OBD-8
- [ ] Taras (human) sign-off recorded on the decision entry
- [ ] Decision is unambiguous enough that OBD-15 (mode-22 work) can proceed without re-litigating it

## Self-test plan
N/A — process/decision record; validity is the presence of the sign-off, not a test.

## Out of scope
Implementing the chosen approach — that's Sprint 2, OBD-13 onward.

## Sign-off
Taras approved D1 (custom ELM327 layer + vendored kotlin-obd-api scaling tables) 2026-08-09; recorded in DECISIONS.md.
