#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.9"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME/git2app/gradle-$GRADLE_VERSION"
if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  TMP_DIR="$(mktemp -d)"
  ZIP_FILE="$TMP_DIR/gradle.zip"
  curl -fsSL --retry 3 -o "$ZIP_FILE" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip -q "$ZIP_FILE" -d "$TMP_DIR"
  mkdir -p "$(dirname "$DIST_DIR")"
  rm -rf "$DIST_DIR"
  mv "$TMP_DIR/gradle-$GRADLE_VERSION" "$DIST_DIR"
  rm -rf "$TMP_DIR"
fi
exec "$DIST_DIR/bin/gradle" "$@"
