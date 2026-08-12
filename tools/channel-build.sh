#!/usr/bin/env bash
# channel-build.sh <dev|main> [--any-branch] — OBD-45 dual-channel builds,
# docs/05-local-workflow.md §D5.
#
# Assembles the demo-debug APK for the given channel and copies it to builds/<channel>/
# with a metadata sidecar (commit, branch, timestamp, versionName, applicationId). `dev`
# applies `-Pchannel=dev` (app/build.gradle.kts: applicationIdSuffix ".dev" + "OBD Gauge Dev"
# label) so it installs alongside `main`'s build instead of overwriting it. `builds/` is
# gitignored — these are local sideload artifacts, not repo content.
#
# Bash 3.2 compatible (macOS ships no newer bash on PATH by default) — mirrors gate.sh/merge.sh.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# Mirrors gate.sh's JAVA_HOME handling: system `java` is JDK 25, Gradle/AGP need 17.
default_jdk17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
if [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$default_jdk17"
fi

die() {
    echo "" >&2
    echo "channel-build.sh: ABORT — $*" >&2
    exit 1
}

step() {
    echo ""
    echo "=== $* ==="
}

if [[ $# -lt 1 ]]; then
    die "usage: channel-build.sh <dev|main> [--any-branch]"
fi

channel="$1"
shift

any_branch=false
for arg in "$@"; do
    case "$arg" in
        --any-branch) any_branch=true ;;
        *) die "unknown argument: $arg (only --any-branch is recognized)" ;;
    esac
done

case "$channel" in
    dev) expected_branch="develop" ;;
    main) expected_branch="main" ;;
    *) die "channel must be 'dev' or 'main' (got '$channel')" ;;
esac

# --- refuse a dirty tree (same check as merge.sh) ---
step "1/4  Working tree must be clean"

if ! git diff --quiet || ! git diff --cached --quiet; then
    die "working tree has uncommitted changes — commit or stash before running channel-build.sh"
fi

echo "  OK — working tree clean"

# --- branch must match the channel unless --any-branch ---
step "2/4  Branch matches channel"

current_branch="$(git rev-parse --abbrev-ref HEAD)"

if [[ "$any_branch" == true ]]; then
    echo "  SKIPPED (--any-branch) — on '$current_branch', channel '$channel' expects '$expected_branch'"
elif [[ "$current_branch" != "$expected_branch" ]]; then
    die "channel '$channel' expects branch '$expected_branch', currently on '$current_branch' (pass --any-branch to override)"
else
    echo "  OK — on '$expected_branch'"
fi

# --- assemble ---
step "3/4  Assemble demo-debug (channel: $channel)"


# bash 3.2 (macOS default) treats `"${arr[@]}"` on an EMPTY array as an unbound-variable
# error under `set -u`, even though bash 4+ doesn't — so branch instead of relying on array
# expansion for the (common) case with no extra property.
if [[ "$channel" == "dev" ]]; then
    ./gradlew -q -Pchannel=dev :app:assembleDemoDebug
else
    ./gradlew -q :app:assembleDemoDebug
fi

apk_src="$repo_root/app/build/outputs/apk/demo/debug/app-demo-debug.apk"
if [[ ! -f "$apk_src" ]]; then
    die "expected APK not found at $apk_src — did :app:assembleDemoDebug produce the usual output path?"
fi

echo "  OK — built $apk_src"

# --- copy + metadata sidecar ---
step "4/4  Copy to builds/$channel/ + write metadata"

out_dir="$repo_root/builds/$channel"
mkdir -p "$out_dir"

commit_sha="$(git rev-parse HEAD)"
commit_short="$(git rev-parse --short HEAD)"
branch_name="$(git rev-parse --abbrev-ref HEAD)"
timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
version_name="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$repo_root/app/build.gradle.kts" | head -n1)"

application_id="com.revel.obdgauge.app"
if [[ "$channel" == "dev" ]]; then
    application_id="${application_id}.dev"
fi

# aapt2 badging is the source of truth when the SDK is available (belt-and-suspenders on top
# of the applicationIdSuffix computed above — catches Gradle-config drift, not just documents
# intent). Falls back to the computed value if aapt2/ANDROID_HOME isn't set up.
aapt2_bin=""
if [[ -n "${ANDROID_HOME:-}" ]]; then
    aapt2_bin="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name aapt2 -type f 2>/dev/null | sort -V | tail -n1 || true)"
fi
if [[ -n "$aapt2_bin" && -x "$aapt2_bin" ]]; then
    badging_appid="$("$aapt2_bin" dump badging "$apk_src" 2>/dev/null | sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p")"
    if [[ -n "$badging_appid" ]]; then
        application_id="$badging_appid"
    fi
fi

fixed_name="app-${channel}-debug.apk"
dest_apk="$out_dir/$fixed_name"
cp "$apk_src" "$dest_apk"

meta_file="$out_dir/${fixed_name%.apk}.json"
cat > "$meta_file" <<EOF
{
  "channel": "$channel",
  "commit": "$commit_sha",
  "commitShort": "$commit_short",
  "branch": "$branch_name",
  "timestamp": "$timestamp",
  "versionName": "$version_name",
  "applicationId": "$application_id"
}
EOF

# Timestamped copy alongside the fixed-name "latest" — keeps history without breaking the
# stable "latest APK" path callers/scripts can rely on.
archive_apk="$out_dir/app-${channel}-${commit_short}.apk"
archive_meta="$out_dir/app-${channel}-${commit_short}.json"
cp "$apk_src" "$archive_apk"
cp "$meta_file" "$archive_meta"

echo "  OK — $dest_apk (+ $meta_file)"
echo "  OK — archived as $archive_apk (+ $archive_meta)"

echo ""
echo "channel-build.sh: $channel channel built — applicationId=$application_id versionName=$version_name commit=$commit_short"
