---
id: OBD-45
title: Dual-channel builds — develop branch + dev/test APK alongside master build
module: tools
owner: infra-agent
sprint: adhoc
status: in-review
type: infra
hardware-verify: false
blocked-by: []
branch: infra/45-dual-channel-builds
---

## Feature
Introduce a two-channel build process (Taras, 2026-08-11):
- **dev channel** — tied to a new long-lived `develop` branch. Ad-hoc feature branches go
  through the SAME issue/review process as everything else, but merge to `develop` first.
  Every merge to `develop` compiles a dev/test APK, saved on disk, ready to sideload.
- **main channel** — tied to `main`. The build currently on Taras's phone is the master
  build. Every merge to `main` compiles the master APK, saved on disk, ready to install.

## Requirements
- Dev APK must install ALONGSIDE the master build (decision 2026-08-11): dev-channel
  builds get `applicationIdSuffix ".dev"` and a visibly distinct label (e.g. "OBD Gauge
  Dev"), so sideloading a test build never replaces the master build.
- Implement the channel as a lightweight switch (gradle property like `-Pchannel=dev`),
  NOT a new flavor dimension — keep the demo/prod × debug/release matrix intact.
- `tools/channel-build.sh <dev|main>`: assembles the demo-debug APK for that channel and
  copies it to `builds/dev/` or `builds/main/` with a metadata sidecar (commit, branch,
  timestamp, versionName). `builds/` is gitignored.
- Merge integration: after a merge lands, the channel build for the TARGET branch runs
  automatically (hook into tools/merge.sh or a documented post-merge step the
  orchestrator runs — pick the least-fragile option given merge.sh's copy-execution
  constraint and document it).
- `develop` branch created from current main; document the promotion flow
  (develop → main on Taras's explicit acceptance; main merges compile the master APK).
- docs/05-local-workflow.md gains a §"Ad-hoc feature channel (D5)": same issue frontmatter
  + review tiers, merge target `develop`, feel-verdict gate before promotion.

## Acceptance criteria
- [ ] `tools/channel-build.sh dev` from develop produces builds/dev/*.apk with `.dev`
      appId + Dev label; `aapt`/apkanalyzer-verified distinct applicationId
- [ ] `tools/channel-build.sh main` from main produces builds/main/*.apk with the
      unchanged applicationId (byte-identical appId to the current phone install)
- [ ] Both APKs install side-by-side on one device (verify via adb if a device is
      attached; otherwise pin with appId assertions and note 🖐)
- [ ] Normal (non-channel) builds are byte-for-byte unaffected — gate.sh stays green with
      zero gradle-config drift for existing variants
- [ ] merge.sh integration or documented post-merge step works when merge.sh runs as a
      copy with MERGE_REPO_ROOT
- [ ] docs/05 §D5 written; DECISIONS.md gains D5 entry; .gitignore covers builds/

## Self-test plan
Assemble both channels, diff applicationIds from the APKs (aapt dump badging), run
gate.sh to prove no drift, dry-run merge.sh copy-mode with the hook.

## Out of scope
Release signing, CI servers, prod-flavor channel builds, versioning scheme changes.
