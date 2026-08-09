#!/usr/bin/env bash
# module-isolation.sh — CODEOWNERS-style enforcement, machine-reads OWNERSHIP.
#
# Usage:
#   tools/module-isolation.sh                       # sanity mode: OWNERSHIP parses, exit 0
#   tools/module-isolation.sh <role> [<base-ref>]    # fail if <role> touched paths it
#                                                     # doesn't own, diffed vs <base-ref>
#                                                     # (default: merge-base with main)
#
# Reviewer roles (any role name starting with "rev-") are additionally allowed to touch
# reviews/, per docs/05-local-workflow.md §2, even though OWNERSHIP lists that path's role
# as the generic "reviewer".
#
# Bash 3.2 compatible (macOS ships no newer bash on PATH by default): no mapfile, no
# associative arrays.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ownership_file="$repo_root/OWNERSHIP"

die() {
    echo "module-isolation: $*" >&2
    exit 1
}

[[ -f "$ownership_file" ]] || die "OWNERSHIP file not found at $ownership_file"

# --- Parse OWNERSHIP into two parallel arrays: own_path[i] <-> own_roles[i] (space-joined) ---
own_path=()
own_roles=()

while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    line="${raw_line%%#*}"
    line="$(printf '%s' "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    [[ -z "$line" ]] && continue

    path_token=""
    roles_str=""
    read -r path_token roles_str <<<"$line"

    if [[ -z "$path_token" || -z "$roles_str" ]]; then
        die "malformed OWNERSHIP line (need 'path role [role...]'): $raw_line"
    fi

    own_path+=("$path_token")
    own_roles+=("$roles_str")
done <"$ownership_file"

if [[ ${#own_path[@]} -eq 0 ]]; then
    die "OWNERSHIP has no parseable entries"
fi

# --- Sanity-only mode: no arguments ---
if [[ $# -eq 0 ]]; then
    echo "module-isolation: OWNERSHIP parses OK — ${#own_path[@]} entries."
    exit 0
fi

role="$1"
base_ref="${2:-}"

if [[ -z "$base_ref" ]]; then
    base_ref="$(git -C "$repo_root" merge-base main HEAD 2>/dev/null || true)"
fi
if [[ -z "$base_ref" ]]; then
    die "could not determine merge-base with main; pass a base ref explicitly"
fi

changed=()
while IFS= read -r changed_path; do
    [[ -n "$changed_path" ]] && changed+=("$changed_path")
done < <(git -C "$repo_root" diff --name-only "$base_ref" HEAD)

if [[ ${#changed[@]} -eq 0 ]]; then
    echo "module-isolation: OK for role '$role' — no changed paths vs $base_ref."
    exit 0
fi

violations=()

for changed_path in "${changed[@]}"; do
    matched_roles=""
    matched_len=-1

    for i in "${!own_path[@]}"; do
        token="${own_path[$i]}"
        token_norm="${token#/}"

        is_match=false
        if [[ "$token_norm" == */ ]]; then
            if [[ "$changed_path" == "$token_norm"* ]]; then
                is_match=true
            fi
        else
            if [[ "$changed_path" == "$token_norm" ]]; then
                is_match=true
            fi
        fi

        if [[ "$is_match" == true ]]; then
            token_len=${#token_norm}
            if (( token_len > matched_len )); then
                matched_len=$token_len
                matched_roles="${own_roles[$i]}"
            fi
        fi
    done

    allowed=false
    if [[ -n "$matched_roles" ]]; then
        for r in $matched_roles; do
            if [[ "$r" == "$role" ]]; then
                allowed=true
                break
            fi
        done
    fi

    if [[ "$allowed" == false ]]; then
        if [[ "$changed_path" == reviews/* && "$role" == rev-* ]]; then
            allowed=true
        fi
    fi

    if [[ "$allowed" == false ]]; then
        if [[ -n "$matched_roles" ]]; then
            violations+=("$changed_path (owned by: $matched_roles)")
        else
            violations+=("$changed_path (no OWNERSHIP entry — unowned)")
        fi
    fi
done

if [[ ${#violations[@]} -gt 0 ]]; then
    echo "module-isolation: FAILED for role '$role' (base $base_ref):" >&2
    for v in "${violations[@]}"; do
        echo "  - $v" >&2
    done
    exit 1
fi

echo "module-isolation: OK for role '$role' — ${#changed[@]} changed path(s), all owned."
