#!/usr/bin/env bash
# gate.sh — the merge gate: build/test/lint/isolation, docs/05-local-workflow.md §1/§6 step 5.
#
# Usage:
#   tools/gate.sh              # full gate: Gradle build+test+lint, module-isolation sanity
#   tools/gate.sh --process    # process-only: skip Gradle, check module-isolation +
#                               # issues/ + reviews/ frontmatter sanity (dirs may not exist yet)
#
# Bash 3.2 compatible (macOS ships no newer bash on PATH by default).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

default_jdk17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
if [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$default_jdk17"
fi

process_only=false
for arg in "$@"; do
    if [[ "$arg" == "--process" ]]; then
        process_only=true
    fi
done

pass_steps=()
fail_steps=()

run_step() {
    local step_name="$1"
    shift
    echo ""
    echo "==> $step_name"
    if "$@"; then
        pass_steps+=("$step_name")
    else
        fail_steps+=("$step_name")
    fi
}

# Sanity-checks that every *.md in $1 opens with '---' and closes it within 40 lines.
# Tolerates $1 not existing (Sprint 0: issues/ and reviews/ aren't seeded yet).
frontmatter_sanity() {
    local dir="$1"
    if [[ ! -d "$dir" ]]; then
        echo "  (no $dir/ yet — skipping)"
        return 0
    fi

    local all_ok=true
    local f
    for f in "$dir"/*.md; do
        if [[ ! -e "$f" ]]; then
            continue
        fi
        local first_line
        first_line="$(sed -n '1p' "$f")"
        if [[ "$first_line" != "---" ]]; then
            echo "  missing frontmatter opener ('---' on line 1): $f" >&2
            all_ok=false
            continue
        fi
        if ! sed -n '2,40p' "$f" | grep -q '^---$'; then
            echo "  missing frontmatter closer (no second '---' within 40 lines): $f" >&2
            all_ok=false
        fi
    done

    if [[ "$all_ok" == true ]]; then
        return 0
    fi
    return 1
}

process_check() {
    local all_ok=true
    echo "  -- issues/ --"
    if ! frontmatter_sanity "$repo_root/issues"; then
        all_ok=false
    fi
    echo "  -- reviews/ --"
    if ! frontmatter_sanity "$repo_root/reviews"; then
        all_ok=false
    fi
    if [[ "$all_ok" == true ]]; then
        return 0
    fi
    return 1
}

if [[ "$process_only" == true ]]; then
    run_step "module-isolation (sanity)" "$repo_root/tools/module-isolation.sh"
    run_step "process-file sanity (issues/, reviews/)" process_check
else
    run_step "assembleDebug" ./gradlew assembleDebug
    run_step "test" ./gradlew test
    run_step "ktlintCheck" ./gradlew ktlintCheck
    run_step "detekt" ./gradlew detekt

    if ./gradlew help --task :app:assembleDemoDebug >/dev/null 2>&1; then
        run_step "assembleDemoDebug" ./gradlew assembleDemoDebug
    else
        echo ""
        echo "==> assembleDemoDebug: task not present yet (demo flavor arrives Sprint 1) — skipping"
    fi

    run_step "module-isolation (sanity)" "$repo_root/tools/module-isolation.sh"
fi

echo ""
echo "================ GATE SUMMARY ================"
for s in "${pass_steps[@]-}"; do
    if [[ -n "$s" ]]; then
        echo "  PASS  $s"
    fi
done
for s in "${fail_steps[@]-}"; do
    if [[ -n "$s" ]]; then
        echo "  FAIL  $s"
    fi
done
echo "================================================"

if [[ ${#fail_steps[@]} -gt 0 ]]; then
    echo "GATE: FAIL"
    exit 1
fi

echo "GATE: PASS"
exit 0
