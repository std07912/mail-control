#!/usr/bin/env sh
# Transparent source-only bootstrap; a standard Gradle wrapper JAR could not be downloaded in the authoring environment.
set -eu
GRADLE_VERSION=8.13
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
DIST_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/settlement-bootstrap"
DIST_DIR="$DIST_ROOT/gradle-$GRADLE_VERSION"
if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  mkdir -p "$DIST_ROOT"
  command -v curl >/dev/null 2>&1 || { echo 'curl is required, or install Gradle 8.13 manually.' >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo 'unzip is required.' >&2; exit 1; }
  DIST_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  curl --fail --location --connect-timeout 15 --max-time 600 "$DIST_URL" -o "$DIST_ROOT/distribution.zip"
  curl --fail --location --connect-timeout 15 --max-time 30 "$DIST_URL.sha256" -o "$DIST_ROOT/distribution.sha256"
  EXPECTED_SHA=$(tr -d '[:space:]' < "$DIST_ROOT/distribution.sha256")
  ACTUAL_SHA=$(sha256sum "$DIST_ROOT/distribution.zip" | cut -d ' ' -f 1)
  [ "$EXPECTED_SHA" = "$ACTUAL_SHA" ] || { echo 'Gradle SHA-256 mismatch' >&2; exit 1; }
  unzip -q -o "$DIST_ROOT/distribution.zip" -d "$DIST_ROOT"
  rm "$DIST_ROOT/distribution.zip"
fi
exec "$DIST_DIR/bin/gradle" "$@"
