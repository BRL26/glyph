package dev.brl.glyph;

import java.util.List;

/**
 * Composes static displays in claim order, oldest first. Each claim owns a set
 * of LEDs (normally a whole strip); a later claim replaces the earlier value on
 * those LEDs instead of being blended with max(). Kept Android-free for tests.
 */
public final class FrameClaims {

    private FrameClaims() {
    }

    public static int[] compose(List<int[]> frames, List<boolean[]> masks) {
        int[] frame = new int[GlyphLayout.LEDS];
        if (frames == null) {
            return frame;
        }
        for (int index = 0; index < frames.size(); index++) {
            int[] claim = frames.get(index);
            if (claim == null || claim.length != GlyphLayout.LEDS) {
                continue;
            }
            boolean[] mask = masks != null && index < masks.size() ? masks.get(index) : null;
            if (mask != null && mask.length == GlyphLayout.LEDS) {
                for (int i = 0; i < GlyphLayout.LEDS; i++) {
                    if (mask[i]) {
                        frame[i] = claim[i];
                    }
                }
            } else {
                // Legacy/global claims have no strip mask; keep the brighter
                // value so we never dim an unrelated display by accident.
                for (int i = 0; i < GlyphLayout.LEDS; i++) {
                    if (claim[i] > frame[i]) {
                        frame[i] = claim[i];
                    }
                }
            }
        }
        return frame;
    }
}
