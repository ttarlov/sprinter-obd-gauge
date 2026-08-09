---
id: OBD-7
title: Research position — custom ELM327 layer
module: research
owner: researcher
sprint: 1
status: in-progress
type: process
hardware-verify: false
blocked-by: []
branch: research/7-custom-elm327-layer
---

## Feature
A one-page position paper argues for a custom ELM327 layer scoped to this app, with an honest LOC and test-burden estimate.

## Contract surface
None expected — a research document only.

## Acceptance criteria
- [ ] `research/custom-position.md` created, one page
- [ ] Honest LOC estimate for the custom layer (init state machine, standard PID parsing, mode-22 framing)
- [ ] Honest test-burden estimate (roughly how many test cases/fixtures needed for confidence)
- [ ] Enumerates exactly what must be built (init handshake, PID registry, mode-22 header control, response parsing, scaling)
- [ ] States a clear position: build custom, with scope boundaries

## Self-test plan
N/A — research artifact.

## Out of scope
The library-adoption position (OBD-6); the decision itself (OBD-9).
