#!/bin/sh
set -eu

GRADLE_VERSION=8.10.2
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/kodari-wrapper/gradle-$GRADLE_VERSION"
EXEC="$BASE/gradle-$GRADLE_VERSION/bin/gradle"

if [ ! -x "$EXEC" ]; then
    mkdir -p "$BASE"
    ARCHIVE="$BASE/gradle-$GRADLE_VERSION-bin.zip"
    if command -v curl >/dev/null 2>&1; then
        curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ARCHIVE"
    elif command -v wget >/dev/null 2>&1; then
        wget "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -O "$ARCHIVE"
    else
        echo 'curl or wget is required to download Gradle.' >&2
        exit 1
    fi
    unzip -q -o "$ARCHIVE" -d "$BASE"
    rm -f "$ARCHIVE"
fi

exec "$EXEC" "$@"