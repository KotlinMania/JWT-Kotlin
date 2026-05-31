#!/usr/bin/env bash
#
# Project-local Android SDK installer. No system installs, no shared SDKs
# from sibling projects, no Android Studio required.
#
# Procedure follows Google's documented headless install (see
# https://developer.android.com/tools/sdkmanager):
#   1. Download Google's commandlinetools-<os>-<rev>_latest.zip.
#   2. Unzip into .android-sdk/cmdline-tools/latest/ (the layout sdkmanager expects).
#   3. Accept all SDK licenses non-interactively (`yes | sdkmanager --licenses`).
#   4. Install platform-tools, platforms;android-<COMPILE_SDK>, build-tools;<BUILD_TOOLS>.
#   5. Write local.properties to point at the project-local SDK.
#
# The .android-sdk/ tree is gitignored. Re-run this script after a clean clone
# or when the SDK layout drifts. It is idempotent - completed steps are skipped.
#
# Override defaults via env vars:
#   CMDLINETOOLS_REV - Google's commandlinetools build number (default below).
#   COMPILE_SDK       - Android API level to install (default: read from build.gradle.kts).
#   BUILD_TOOLS       - Build-tools version to install (default below).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$REPO_ROOT/.android-sdk"

# Pin a known-good cmdline-tools revision. Bump when Google rotates.
# Source: https://developer.android.com/studio (look for "Command line tools only").
CMDLINETOOLS_REV="${CMDLINETOOLS_REV:-14742923}"

# compileSdk + minSdk live in build.gradle.kts. Default to compileSdk = 34
# (matches the existing kotlinmania convention); operator can override.
COMPILE_SDK="${COMPILE_SDK:-34}"
BUILD_TOOLS="${BUILD_TOOLS:-34.0.0}"

case "$(uname -s)" in
    Darwin*) OS="mac" ;;
    Linux*)  OS="linux" ;;
    MINGW*|MSYS*|CYGWIN*) OS="win" ;;
    *) echo "setup-android-sdk: unsupported OS $(uname -s)" >&2; exit 1 ;;
esac

ZIP="commandlinetools-${OS}-${CMDLINETOOLS_REV}_latest.zip"
URL="https://dl.google.com/android/repository/${ZIP}"

# ---------------------------------------------------------------------------
# Step 1: command-line tools
# ---------------------------------------------------------------------------
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "setup-android-sdk: downloading $URL"
    mkdir -p "$SDK_DIR/cmdline-tools"
    TMPDIR="$(mktemp -d)"
    trap 'rm -rf "$TMPDIR"' EXIT
    curl -fL --progress-bar "$URL" -o "$TMPDIR/$ZIP"
    unzip -q "$TMPDIR/$ZIP" -d "$TMPDIR"
    # Google's zip extracts to a top-level "cmdline-tools/" directory whose
    # contents need to live at <sdk>/cmdline-tools/latest/ for sdkmanager to
    # locate itself. Move the unpacked tree into place atomically.
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$TMPDIR/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$TMPDIR"
    trap - EXIT
fi

SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"

# ---------------------------------------------------------------------------
# Step 2: license acceptance (non-interactive)
# ---------------------------------------------------------------------------
# The first run prompts y/n for each unaccepted license. `yes |` answers them
# all. Subsequent runs are no-ops. License acceptance is recorded as hash
# files under <sdk>/licenses/ - committing those would let CI skip this step,
# but we keep them out of git so each developer accepts their own.
echo "setup-android-sdk: accepting licenses"
# `yes |` would work but exits with SIGPIPE (status 141) once sdkmanager
# closes its stdin, and `set -euo pipefail` then kills the script before the
# install step runs. printf produces a finite stream of "y" answers and
# exits cleanly, so the pipeline returns 0 when sdkmanager succeeds.
# 200 answers is far more than the ~10 licenses sdkmanager actually prompts
# for (header + per-package), so the stream never runs short.
printf 'y\n%.0s' {1..200} | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses > /dev/null

# ---------------------------------------------------------------------------
# Step 3: install platform + build-tools
# ---------------------------------------------------------------------------
echo "setup-android-sdk: installing platform-tools, android-${COMPILE_SDK}, build-tools;${BUILD_TOOLS}"
# sdkmanager streams progress with carriage-return overwrites. Capture full
# output to a log file so callers redirecting through pagers / tail are not
# confused by the live progress, and so success/failure is auditable later.
LOGFILE="$REPO_ROOT/.android-sdk/sdkmanager-install.log"
"$SDKMANAGER" --sdk_root="$SDK_DIR" \
    "platform-tools" \
    "platforms;android-${COMPILE_SDK}" \
    "build-tools;${BUILD_TOOLS}" > "$LOGFILE" 2>&1
echo "setup-android-sdk: install log at $LOGFILE"

# ---------------------------------------------------------------------------
# Step 4: point local.properties at the project-local SDK
# ---------------------------------------------------------------------------
echo "sdk.dir=$SDK_DIR" > "$REPO_ROOT/local.properties"

echo
echo "setup-android-sdk: done"
echo "  SDK at:     $SDK_DIR"
echo "  configured: local.properties -> $SDK_DIR"
