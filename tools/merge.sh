#!/usr/bin/env bash
# merge.sh <OBD-N> — scripted merge procedure, docs/05-local-workflow.md §6.
#
# Only the merge-agent role runs this. Aborts loudly at the first failed step — no partial
# merges, no silent skips. Steps map 1:1 onto §6.1-8.
#
# Bash 3.2 compatible (macOS ships no newer bash on PATH by default).
set -euo pipefail

# MERGE_REPO_ROOT override lets the orchestrator run a COPY of this script — merge.sh
# checks out branches mid-run, and bash reading the executing file while a checkout swaps
# it is undefined behavior. Copy to a temp path, set the env var, run the copy.
repo_root="${MERGE_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$repo_root"

die() {
    echo "" >&2
    echo "merge.sh: ABORT — $*" >&2
    exit 1
}

step() {
    echo ""
    echo "=== $* ==="
}

if [[ $# -lt 1 ]]; then
    die "usage: merge.sh <OBD-N>"
fi

issue_id="$1"
issue_file="$repo_root/issues/${issue_id}.md"

if [[ ! -f "$issue_file" ]]; then
    die "issue file not found: $issue_file"
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
    die "working tree has uncommitted changes — commit or stash before running merge.sh"
fi

# Reads one scalar frontmatter value (between the first two '---' lines) from a markdown file.
frontmatter_value() {
    local file="$1" key="$2"
    awk -v key="$key" '
        /^---[[:space:]]*$/ { fm++; if (fm == 2) { exit }; next }
        fm == 1 {
            if (index($0, key ":") == 1) {
                sub("^" key ":[[:space:]]*", "");
                print;
                exit
            }
        }
    ' "$file"
}

commit_type_for() {
    case "$1" in
        fixture) echo "test" ;;
        process) echo "chore" ;;
        *) echo "feat" ;; # feature, contract-change, and anything unrecognized
    esac
}

current_branch="$(git rev-parse --abbrev-ref HEAD)"

# --- Step 1: issue status/branch + latest review approval, not stale ---
step "1/8  Validate issue status, branch, and review approval"

issue_status="$(frontmatter_value "$issue_file" "status")"
issue_branch="$(frontmatter_value "$issue_file" "branch")"
issue_type="$(frontmatter_value "$issue_file" "type")"
issue_hw_verify="$(frontmatter_value "$issue_file" "hardware-verify")"
issue_title="$(frontmatter_value "$issue_file" "title")"
issue_module="$(frontmatter_value "$issue_file" "module")"

if [[ "$issue_status" != "in-review" ]]; then
    die "$issue_id status is '$issue_status', expected 'in-review'"
fi
if [[ -z "$issue_branch" ]]; then
    die "$issue_id has no 'branch:' set in frontmatter"
fi

# Review records live on the FEATURE BRANCH (doc 05 §5.2) and only reach main via the
# squash merge — so read them from the branch's tree, not the current working tree.
issue_branch_early="$(frontmatter_value "$issue_file" "branch")"
latest_review_path="$(git ls-tree -r --name-only "$issue_branch_early" -- reviews/ 2>/dev/null \
    | grep "^reviews/${issue_id}-round.*\.md$" | sort -V | tail -n1 || true)"
if [[ -z "$latest_review_path" ]]; then
    die "no review file found for $issue_id on branch '$issue_branch_early' (expected reviews/${issue_id}-round*.md)"
fi
latest_review="$(mktemp "${TMPDIR:-/tmp}/merge-review.XXXXXX")"
git show "${issue_branch_early}:${latest_review_path}" > "$latest_review"
trap 'rm -f "$latest_review"' EXIT

review_verdict="$(frontmatter_value "$latest_review" "verdict")"
review_commit="$(frontmatter_value "$latest_review" "reviewed-commit")"

if [[ "$review_verdict" != "approved" ]]; then
    die "$latest_review verdict is '$review_verdict', expected 'approved'"
fi
if [[ -z "$review_commit" ]]; then
    die "$latest_review has no 'reviewed-commit' set"
fi
if ! grep -q '^## Fix list' "$latest_review"; then
    die "$latest_review has no '## Fix list' — approvals without a verified-item list are invalid"
fi
if ! grep -q '✅' "$latest_review"; then
    die "$latest_review's fix list has no verified (✅) items — approvals without a verified-item list are invalid"
fi

if ! git rev-parse --verify "$issue_branch" >/dev/null 2>&1; then
    die "branch '$issue_branch' does not exist"
fi

branch_head="$(git rev-parse "$issue_branch")"
branch_head_short="$(git rev-parse --short "$issue_branch")"

if [[ "$review_commit" != "$branch_head" && "$review_commit" != "$branch_head_short" ]]; then
    # Review-record commits land on the branch AFTER the commit they review (doc 05 §5.2),
    # so exempt commits that touch only reviews/ — any other path after approval voids it.
    non_review_paths="$(git diff --name-only "$review_commit".."$branch_head" -- . ':!reviews/' 2>/dev/null || echo "DIFF_FAILED")"
    if [[ -n "$non_review_paths" ]]; then
        die "stale approval: reviewed-commit '$review_commit' != branch head '$branch_head_short' and non-review paths changed since approval: $non_review_paths"
    fi
    echo "  OK — commits after $review_commit touch reviews/ only (review records)"
fi

echo "  OK — $issue_id in-review on '$issue_branch'; $latest_review approved at $branch_head_short"

# --- Step 2: hardware-verify gate — no automation path around this ---
step "2/8  Hardware-verify checklist"

if [[ "$issue_hw_verify" == "true" ]]; then
    if ! grep -q '^## Hardware checklist' "$issue_file"; then
        die "$issue_id has hardware-verify: true but no '## Hardware checklist' section — Taras must add observed values"
    fi
    echo "  OK — hardware checklist present"
else
    echo "  n/a — hardware-verify: ${issue_hw_verify:-false}"
fi

# --- Step 3: contract-change sign-off ---
step "3/8  Contract-change sign-off"

if [[ "$issue_type" == "contract-change" ]]; then
    if ! grep -q "$issue_id" "$repo_root/DECISIONS.md"; then
        die "$issue_id is type: contract-change but has no DECISIONS.md entry"
    fi
    if ! grep -qi "sign-off" "$issue_file"; then
        die "$issue_id is type: contract-change but has no Taras sign-off note in the issue file"
    fi
    echo "  OK — DECISIONS.md entry + sign-off note present"
else
    echo "  n/a — type: ${issue_type:-feature}"
fi

# --- Step 4: rebase branch on main; conflicts go back to the author ---
step "4/8  Rebase '$issue_branch' on main"

git checkout "$issue_branch"
if ! git rebase main; then
    git rebase --abort || true
    git checkout "$current_branch" || true
    die "rebase of '$issue_branch' onto main hit conflicts — mechanical, conflict-free rebases only; back to the author"
fi

echo "  OK — '$issue_branch' rebased cleanly on main"

# --- Step 5: gate on the rebased branch ---
step "5/8  Gate on rebased branch"

if ! "$repo_root/tools/gate.sh"; then
    git checkout "$current_branch" || true
    die "gate failed on rebased '$issue_branch' — no merge, no exceptions"
fi

echo "  OK — gate green on '$issue_branch'"

# --- Step 6: squash merge; flip issue to status: merged in the same commit ---
step "6/8  Squash merge to main"

git checkout main
if ! git merge --squash "$issue_branch"; then
    git reset --hard HEAD || true
    git checkout "$current_branch" || true
    die "squash merge of '$issue_branch' into main failed"
fi

sed -i.bak "s/^status: .*/status: merged/" "$issue_file"
rm -f "${issue_file}.bak"
git add -A

cc_type="$(commit_type_for "$issue_type")"
commit_scope="${issue_module##*/}"
commit_msg="${cc_type}(${commit_scope:-misc}): ${issue_title} (${issue_id})

Closes ${issue_id}
Role: merge-agent"

git commit -m "$commit_msg"
merge_commit="$(git rev-parse --short HEAD)"

echo "  OK — squash-merged as $merge_commit; $issue_id flipped to status: merged"

# --- Step 7: post-merge verify; revert first, diagnose second ---
step "7/8  Post-merge gate on main"

if ! "$repo_root/tools/gate.sh"; then
    echo "  RED on main — reverting $merge_commit immediately" >&2
    git revert --no-edit "$merge_commit"
    die "post-merge gate failed on main — reverted $merge_commit. Reopen $issue_id (status: open) with the failure pasted in, then diagnose."
fi

echo "  OK — gate green on main"

# --- Step 8: delete branch ---
step "8/8  Delete branch"

# -D (not -d): a squash merge never creates the ancestry link git's safe-delete checks for.
# Everything up to here already validated the branch's content is safely on main.
git branch -D "$issue_branch"

echo "  OK — '$issue_branch' deleted"

echo ""
echo "merge.sh: $issue_id merged successfully as $merge_commit"
