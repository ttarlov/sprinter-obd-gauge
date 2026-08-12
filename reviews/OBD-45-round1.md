---
issue: OBD-45
round: 1
reviewers: [orchestrator (infra/tooling — orchestrator-owned surface, no displayed-value path)]
verdict: approved
gate: green
reviewed-commit: 5bd15c7
---

Orchestrator review of `infra/45-dual-channel-builds`. Tiering rationale: tools/ + gradle
config are orchestrator-owned per OWNERSHIP; nothing here computes or transforms a displayed
value, so no Tier-A matrix. Taras's explicit spawn-a-reviewer requirement in this wave
applied to the OBD-44 animation PR; this track gets the orchestrator lens with the builder's
own verification transcript audited line-by-line.

## Fix list

Verified items (from builder transcript + independent diff read):
- [x] ✅ Gradle switch is minimal and inert-by-default: `providers.gradleProperty("channel")`,
      two guarded assignments in defaultConfig. Placeholder default `@string/app_name`
      substitutes the exact pre-OBD-45 manifest text — merged manifest byte-identical
      without the property. Builder proved both directions with aapt2 badging AND proved
      no cross-invocation stickiness (dev build → clean build → original appId).
- [x] ✅ No new flavorDimension; demo/prod × debug/release untouched; full gate.sh PASS on
      the branch (assembleDebug, test, ktlint, detekt, roborazzi, assembleDemoDebug,
      module-isolation).
- [x] ✅ channel-build.sh: dirty-tree refusal, branch↔channel assertion with --any-branch
      escape, JAVA_HOME fallback mirrors gate.sh, stable latest path + timestamped archive
      + JSON sidecar, appId in sidecar taken from actual aapt2 badging when SDK present
      (falls back to computed). bash-3.2 empty-array `set -u` trap caught by the builder's
      own dry-run and fixed (5bd15c7). No branch checkouts → no script-swap-under-bash
      hazard; MERGE_REPO_ROOT copy-execution model in merge.sh untouched and re-proven by
      builder against the worktree.
- [x] ✅ merge.sh: MERGE_TARGET_BRANCH default `main` — zero behavior change for every
      existing call site; develop targeting is opt-in. Post-merge channel-build printed as
      mandatory instruction, not auto-run — rationale (failure-surface separation, gate
      already runs twice, APK is a sideload convenience not a merge precondition) is
      documented in docs/05 §6b and endorsed.
- [x] ✅ docs/05 §6b + DECISIONS D5: process complete — same issue/review discipline,
      merge target develop for ad-hoc, promotion develop→main ONLY on Taras's explicit
      acceptance, no automatic path. /builds/ gitignored.

**Open (not blocking):**
- 🖐 Side-by-side install on the actual Pixel — pinned by appId assertions on both archived
  APKs; physical confirm lands with the next sideload session.
- NIT (recorded, no change requested): versionName sidecar field is sed-parsed from
  build.gradle.kts — acceptable while versionName is a literal; revisit if versioning ever
  becomes computed.

Verdict: **approved** at 5bd15c7.
