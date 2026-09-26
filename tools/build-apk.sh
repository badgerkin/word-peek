#!/usr/bin/env bash
# Builds a signed Word Peek APK without Gradle or the Android SDK manager.
#
# Needs: JDK 17+, kotlinc 2.x, ProGuard 7.x (the proguard-base jar and its runtime
# dependencies from Maven Central, in one directory), and the Debian/Ubuntu packages
#   aapt apksigner zipalign dalvik-exchange
# plus an android.jar for the compile SDK (pass its path as ANDROID_JAR).
#
# The aapt2 in Ubuntu 24.04 can't parse the resource table in the API 36+
# android.jar, so resources can be linked against an older platform jar via
# LINK_JAR (API 34 works). Framework resource IDs are stable across versions,
# so the result is the same; code is still compiled against ANDROID_JAR.
#
#   ANDROID_JAR=/path/to/android-37.jar LINK_JAR=/path/to/android-34.jar \
#   KOTLIN_HOME=/path/to/kotlinc PROGUARD_HOME=/path/to/proguard-jars \
#   WORDPEEK_KEYSTORE=/path/to/release.jks WORDPEEK_KEYSTORE_PASSWORD=... \
#   WORDPEEK_KEY_ALIAS=wordpeek tools/build-apk.sh
#
# Output: build/WordPeek.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
PACKAGE=com.wordpeek
MIN_SDK=23
TARGET_SDK=37
VERSION_CODE=1
VERSION_NAME=1.0.0

: "${ANDROID_JAR:?set ANDROID_JAR to the platform android.jar}"
: "${KOTLIN_HOME:?set KOTLIN_HOME to the kotlinc directory}"
: "${PROGUARD_HOME:?set PROGUARD_HOME to a directory holding the ProGuard jars}"
LINK_JAR="${LINK_JAR:-$ANDROID_JAR}"
# Release signing key, never committed. Same variables as the Gradle release build.
: "${WORDPEEK_KEYSTORE:?set WORDPEEK_KEYSTORE to the release keystore path}"
: "${WORDPEEK_KEYSTORE_PASSWORD:?set WORDPEEK_KEYSTORE_PASSWORD}"
: "${WORDPEEK_KEY_ALIAS:?set WORDPEEK_KEY_ALIAS}"
export WORDPEEK_KEYSTORE_PASSWORD
export WORDPEEK_KEY_PASSWORD="${WORDPEEK_KEY_PASSWORD:-$WORDPEEK_KEYSTORE_PASSWORD}"
DX="$(command -v dx || command -v dalvik-exchange)"

rm -rf "$OUT/intermediates"
mkdir -p "$OUT/intermediates"/{res,gen,rclasses,classes,dexin}
cd "$OUT/intermediates"

echo "==> Resources"
aapt2 compile --dir "$APP/res" -o res/compiled.zip
# aapt2 needs the package attribute that Gradle normally injects from `namespace`.
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$APP/AndroidManifest.xml" > AndroidManifest.xml
aapt2 link -o base.apk -I "$LINK_JAR" --manifest AndroidManifest.xml \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java gen --auto-add-overlay res/compiled.zip

echo "==> Compile"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d rclasses $(find gen -name '*.java') 2>&1 \
    | grep -v 'source value 8\|target value 8\|suppress this warning\|^warning: \[options\]\|^[0-9]* warnings\?$' || true
"$KOTLIN_HOME/bin/kotlinc" $(find "$APP/java" -name '*.kt') \
    -classpath "$ANDROID_JAR:rclasses" -no-reflect -no-jdk -jvm-target 1.8 \
    -Xlambdas=class -Xsam-conversions=class -Xno-param-assertions -Xno-call-assertions \
    -d classes

echo "==> Shrink"
cp -r rclasses/. classes/
# Drops unused code, mostly from the Kotlin stdlib. Required below Android 8: parts of the
# stdlib the app never calls use invokedynamic, which dx can't convert for older versions.
java -cp "$(find "$PROGUARD_HOME" -name '*.jar' | tr '\n' ':')" proguard.ProGuard \
    -injars classes \
    -injars "$KOTLIN_HOME/lib/kotlin-stdlib.jar(!META-INF/**)" \
    -outjars dexin/app.jar \
    -libraryjars "$ANDROID_JAR" \
    -include "$ROOT/app/proguard-rules.pro" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
[ -f dexin/app.jar ] || { echo "error: ProGuard failed" >&2; exit 1; }

echo "==> Dex"
"$DX" --dex --min-sdk-version="$MIN_SDK" --output=classes.dex dexin/app.jar

echo "==> Package"
cp base.apk unsigned.apk
zip -q -j unsigned.apk classes.dex
zipalign -f -p 4 unsigned.apk aligned.apk

# Passwords go through the environment so they don't appear in the process list.
apksigner sign --ks "$WORDPEEK_KEYSTORE" --ks-pass env:WORDPEEK_KEYSTORE_PASSWORD \
    --key-pass env:WORDPEEK_KEY_PASSWORD --ks-key-alias "$WORDPEEK_KEY_ALIAS" \
    --min-sdk-version "$MIN_SDK" --out "$OUT/WordPeek.apk" aligned.apk
apksigner verify --verbose "$OUT/WordPeek.apk" | grep -E 'Verified using|^Verifies'
echo "==> $OUT/WordPeek.apk"
