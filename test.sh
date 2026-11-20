#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
OUT="build/tests"
rm -rf "$OUT"
mkdir -p "$OUT"

javac -source 8 -target 8 -encoding UTF-8 \
    -d "$OUT" \
    src/dev/brl/glyph/Behavior.java \
    src/dev/brl/glyph/GlyphLayout.java \
    src/dev/brl/glyph/GlyphEffects.java \
    src/dev/brl/glyph/FrameClaims.java \
    src/dev/brl/glyph/MediaPosition.java \
    src/dev/brl/glyph/AudioSpectrum.java \
    src/dev/brl/glyph/NoteMap.java \
    src/dev/brl/glyph/MeydaFeatures.java \
    src/dev/brl/glyph/AutoGen.java \
    tests/dev/brl/glyph/GlyphTests.java \
    tests/dev/brl/glyph/AutoGenTests.java \
    tests/dev/brl/glyph/NoteMapTests.java

java -cp "$OUT" dev.brl.glyph.GlyphTests
java -cp "$OUT" dev.brl.glyph.AutoGenTests
java -cp "$OUT" dev.brl.glyph.NoteMapTests
