#!/usr/bin/env bash
# Builds Glyph.apk with the Android SDK tools directly (no Gradle, no AndroidX).
set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
PLATFORM="$SDK/platforms/android-37/android.jar"
OUT="build"
APK="Glyph.apk"
KS="$HOME/.android/debug.keystore"

[ -x "$BT/aapt2" ] || { echo "aapt2 not found in $BT" >&2; exit 1; }
[ -f "$PLATFORM" ] || { echo "android.jar not found at $PLATFORM" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res"

echo "==> compiling resources"
# Keep the root helper single-sourced; package it as a raw resource so the
# app can install/update it without a second copy in the source tree.
mkdir -p res/raw
cp helper/glyph-helper.sh res/raw/glyph_helper.sh
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "==> linking resources"
"$BT/aapt2" link \
    -o "$OUT/unsigned.apk" \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version 31 \
    --target-sdk-version 37 \
    "$OUT/res.zip"

echo "==> compiling java"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# Java 8 bytecode: javac refuses -bootclasspath above target 8; d8 handles it.
javac -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> dexing"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --lib "$PLATFORM" --min-api 31 --output "$OUT/dex" "$OUT/classes.jar"

python3 - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import os, shutil, sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
shutil.copyfile(apk, tmp)
with zipfile.ZipFile(tmp, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
os.replace(tmp, apk)
PY

echo "==> aligning and signing"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000
fi

"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify "$APK"
echo "==> built $(pwd)/$APK"
