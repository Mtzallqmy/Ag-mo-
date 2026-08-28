#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.11.1"
GRADLE_SHA256="f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
BASE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/al-agent-bootstrap"
ZIP="$BASE_DIR/gradle-$GRADLE_VERSION-bin.zip"
DIST="$BASE_DIR/gradle-$GRADLE_VERSION"
mkdir -p "$BASE_DIR"
if [ ! -x "$DIST/bin/gradle" ]; then
  if [ ! -f "$ZIP" ]; then
    echo "Downloading Gradle $GRADLE_VERSION from services.gradle.org..." >&2
    if command -v curl >/dev/null 2>&1; then
      curl --fail --location --proto '=https' --tlsv1.2 -o "$ZIP.tmp" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    elif command -v wget >/dev/null 2>&1; then
      wget --https-only -O "$ZIP.tmp" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    else
      echo "curl or wget is required for the first bootstrap." >&2; exit 2
    fi
    mv "$ZIP.tmp" "$ZIP"
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$ZIP" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 256 "$ZIP" | awk '{print $1}')
  else
    echo "sha256sum or shasum is required to verify Gradle." >&2; exit 2
  fi
  [ "$ACTUAL" = "$GRADLE_SHA256" ] || { echo "Gradle checksum verification failed." >&2; rm -f "$ZIP"; exit 3; }
  rm -rf "$DIST"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP" -d "$BASE_DIR"
  else
    echo "unzip is required for the first bootstrap." >&2; exit 2
  fi
fi
exec "$DIST/bin/gradle" "$@"
