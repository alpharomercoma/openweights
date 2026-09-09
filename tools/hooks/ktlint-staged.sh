#!/bin/sh
# Runs ktlint on the staged Kotlin files, and only those, before a commit exists.
#
# Why not `./gradlew ktlintCheck`: Gradle takes ten to forty seconds to start and lints
# every module, which is the wrong price for a commit that touched one file, and it
# would collide with any build already running (AGENTS.md: one Gradle at a time). The
# ktlint release binary lints a file in about a second.
#
# Why this exact version: the Gradle plugin pins ktlint 1.5.0 (build.gradle.kts), and a
# newer CLI carries rules the plugin does not, so CI and the hook would disagree. The
# binary is fetched once into ~/.cache/ktlint from the pinned release and reused.
#
# Wired by lefthook.yml; run by hand as tools/hooks/ktlint-staged.sh [files...].
set -eu

VERSION=1.5.0
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/ktlint"
BIN="$CACHE/ktlint-$VERSION"
URL="https://github.com/pinterest/ktlint/releases/download/$VERSION/ktlint"

if [ ! -x "$BIN" ]; then
    mkdir -p "$CACHE"
    echo "ktlint-staged: fetching ktlint $VERSION into $CACHE" >&2
    curl -sSL -o "$BIN" "$URL" && chmod +x "$BIN"
fi

# The release binary is a self-executing jar and needs a JVM; the same one Gradle uses.
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi
if [ -n "${JAVA_HOME:-}" ]; then
    PATH="$JAVA_HOME/bin:$PATH"
fi

if [ "$#" -eq 0 ]; then
    set -- $(git diff --cached --name-only --diff-filter=ACMR -- '*.kt' '*.kts')
fi
[ "$#" -eq 0 ] && exit 0

# The repository's .editorconfig carries the ktlint rules the build uses; the binary
# reads it from the working directory, so run from the root.
cd "$(git rev-parse --show-toplevel)"
exec "$BIN" --relative "$@"
