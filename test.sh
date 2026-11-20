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
    src/dev/brl/glyph/AudioSpectrum.java \
    tests/dev/brl/glyph/GlyphTests.java

java -cp "$OUT" dev.brl.glyph.GlyphTests
