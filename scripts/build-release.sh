#!/usr/bin/env bash
# Builds the Play release bundle and refuses to pretend an unsigned one is usable.
#
#   ./scripts/build-release.sh            # build with the current versionCode
#   ./scripts/build-release.sh 7          # set versionCode to 7 first
#
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

# System Java is too new for Gradle 8.14; Android Studio ships a JDK 21 that works.
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "✗ No JDK at JAVA_HOME=$JAVA_HOME" >&2
    exit 1
fi

if [ $# -ge 1 ]; then
    NEW_CODE="$1"
    [[ "$NEW_CODE" =~ ^[0-9]+$ ]] || { echo "✗ versionCode must be a number, got '$NEW_CODE'" >&2; exit 1; }
    /usr/bin/sed -i '' -E "s/^([[:space:]]*versionCode )[0-9]+/\1$NEW_CODE/" android/app/build.gradle
    echo "→ versionCode set to $NEW_CODE"
fi

VERSION_CODE=$(grep -E "^\s*versionCode " android/app/build.gradle | grep -oE '[0-9]+')
VERSION_NAME=$(grep -E "^\s*versionName " android/app/build.gradle | sed -E 's/.*"(.*)".*/\1/')
echo "→ building versionCode=$VERSION_CODE versionName=$VERSION_NAME"

if [ ! -f android/keystore.properties ]; then
    echo
    echo "⚠  android/keystore.properties is missing — this build will be UNSIGNED and Play"
    echo "   will reject it. Copy android/keystore.properties.example and fill it in with the"
    echo "   SAME upload key the live listing was signed with."
    echo
fi

npx cap sync android
( cd android && ./gradlew clean :app:bundleRelease )

AAB="$ROOT/android/app/build/outputs/bundle/release/app-release.aab"
[ -f "$AAB" ] || { echo "✗ No bundle produced at $AAB" >&2; exit 1; }

echo
if unzip -l "$AAB" | grep -qE "META-INF/.*\.(RSA|SF|DSA)"; then
    echo "✓ SIGNED — ready to upload"
    echo "  $AAB  ($(du -h "$AAB" | cut -f1))"
    echo
    echo "  Play Console → Release → Testing → Closed testing → Create new release"
    echo "  versionCode $VERSION_CODE must exceed the highest already uploaded on ANY track."
else
    echo "✗ UNSIGNED — Play will reject this. Add android/keystore.properties and re-run."
    echo "  $AAB"
    exit 1
fi
