package dev.brl.glyph;

import java.util.Arrays;

/** Pure frame effects used by GlyphEngine. Kept Android-free for host tests. */
public final class GlyphEffects {

    public static final int STYLE_PULSE = 0;
    public static final int STYLE_BREATHE = 1;
    public static final int STYLE_WAVE = 2;
    public static final int STYLE_CALL = 4;
    public static final int STYLE_SEQUENCE = 5;

    private GlyphEffects() {
    }

    public static int[] compose(int style, long elapsed, int[] source) {
        int[] frame = new int[GlyphLayout.LEDS];
        if (source == null || source.length != GlyphLayout.LEDS) {
            return frame;
        }
        switch (style) {
            case STYLE_PULSE: {
                float factor = 0.35f + 0.65f * wave(elapsed, 620.0);
                scale(source, frame, factor);
                break;
            }
            case STYLE_BREATHE: {
                float factor = 0.06f + 0.94f * wave(elapsed, 2600.0);
                scale(source, frame, factor);
                break;
            }
            case STYLE_WAVE:
            case STYLE_CALL: {
                int[] order = order(source);
                if (order.length == 0) {
                    break;
                }
                // The wavelength is relative to the strip this behavior owns,
                // so 5, 11 and 20-LED strips all show a complete wave.
                float period = style == STYLE_CALL ? 300.0f : 240.0f;
                float step = (float) (Math.PI * 2.0 * 1.5 / order.length);
                float low = style == STYLE_CALL ? 0.15f : 0.08f;
                for (int index = 0; index < order.length; index++) {
                    int hw = order[index];
                    float phase = (elapsed / period) - index * step;
                    float factor = low + (1f - low)
                            * ((float) Math.sin(phase) * 0.5f + 0.5f);
                    frame[hw] = Math.round(source[hw] * factor);
                }
                break;
            }
            case STYLE_SEQUENCE: {
                int[] order = order(source);
                if (order.length == 0) {
                    break;
                }
                // Count only the LEDs this behavior owns. A B-only sequence
                // used to stay dark for 31/36 of its runtime because it was
                // walking the fixed hardware indexes.
                int lit = (int) Math.min(order.length, (elapsed * order.length) / 2400L);
                for (int index = 0; index < lit; index++) {
                    frame[order[index]] = source[order[index]];
                }
                break;
            }
            default:
                break;
        }
        return frame;
    }

    /**
     * Hardware indexes that belong to this frame, ordered by strip and then
     * from the bottom of each strip. Zero LEDs are skipped, so an animation
     * assigned to one strip never counts empty space on the other two.
     */
    public static int[] order(int[] source) {
        if (source == null || source.length != GlyphLayout.LEDS) {
            return new int[0];
        }
        int[] result = new int[GlyphLayout.LEDS];
        int count = 0;
        for (int zone = 0; zone < GlyphLayout.ZONES; zone++) {
            for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
                int hw = GlyphLayout.hwIndex(zone, pos);
                if (source[hw] > 0) {
                    result[count++] = hw;
                }
            }
        }
        return Arrays.copyOf(result, count);
    }

    /** Linear blend between two frames, clamped to 0..1. Used by the fades. */
    public static int[] blend(int[] from, int[] target, float t) {
        int[] frame = new int[GlyphLayout.LEDS];
        if (from == null || target == null
                || from.length != GlyphLayout.LEDS || target.length != GlyphLayout.LEDS) {
            return frame;
        }
        float amount = Math.max(0f, Math.min(1f, t));
        for (int i = 0; i < frame.length; i++) {
            frame[i] = Math.round(from[i] + (target[i] - from[i]) * amount);
        }
        return frame;
    }

    /**
     * Which frame of a precompiled track to show. Looping tracks wrap around
     * so a ringtone keeps replaying for as long as the call rings; one-shot
     * tracks clamp to their last frame.
     */
    public static int frameIndex(long elapsedMs, int frameCount, int fps, boolean loop) {
        if (frameCount <= 0 || fps <= 0) {
            return 0;
        }
        long totalMs = Math.round(frameCount * (1000.0 / fps));
        if (totalMs <= 0) {
            return frameCount - 1;
        }
        long position = elapsedMs < 0 ? 0 : elapsedMs;
        position = loop ? position % totalMs : Math.min(position, totalMs - 1);
        // The epsilon keeps exact frame boundaries (500 ms at 60 fps) from
        // flooring to the previous frame through floating-point error.
        long index = (long) Math.floor(position * (double) fps / 1000.0 + 1.0e-9);
        return (int) Math.max(0, Math.min(frameCount - 1, index));
    }

    public static float wave(long elapsed, double periodMs) {
        return (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * elapsed / periodMs));
    }

    public static int sum(int[] frame) {
        if (frame == null) {
            return 0;
        }
        int total = 0;
        for (int value : frame) {
            total += value;
        }
        return total;
    }

    private static void scale(int[] source, int[] out, float factor) {
        for (int i = 0; i < GlyphLayout.LEDS; i++) {
            out[i] = Math.round(source[i] * factor);
        }
    }
}
