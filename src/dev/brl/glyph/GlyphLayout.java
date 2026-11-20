package dev.brl.glyph;

/**
 * Phone (3a) hardware layout from Nothing's Glyph Developer Kit.
 *
 * The SDK/driver frame order is:
 *
 *   A: 11 LEDs, indices 20..30, A1 top -> A11 bottom (right strip).
 *   B:  5 LEDs, indices 31..35, B1 bottom-right -> B5 top-left (bottom-left).
 *   C: 20 LEDs, indices  0..19, C1 bottom-left -> C20 top-right (top-left).
 *
 * Public zone indexes are A, B, C in that same order. All progress frames
 * fill from position 0 (the bottom of the physical strip) upward.
 */
public final class GlyphLayout {

    public static final int LEDS = 36;
    public static final int ZONES = 3;
    // aw20036_frame_brightness_store copies each value into an
    // unsigned char[36]. Anything above 255 wraps (4054 becomes 214,
    // 3391 becomes 63), which makes a fade jump up and down.
    public static final int MAX_VALUE = 255;

    public static final int[] ZONE_LEDS = {11, 5, 20};
    public static final int[] HW_START = {20, 31, 0};

    private GlyphLayout() {
    }

    /**
     * Maps a position from the bottom of a zone (0 = bottom, n-1 = top) to
     * the LED index used by frame_brightness.
     */
    public static int hwIndex(int zone, int posFromBottom) {
        if (zone < 0 || zone >= ZONES) {
            return -1;
        }
        int n = ZONE_LEDS[zone];
        int pos = Math.max(0, Math.min(n - 1, posFromBottom));
        if (zone == 0) {
            // A is stored top-to-bottom, so reverse it for a bottom-up bar.
            return HW_START[0] + (n - 1 - pos);
        }
        // B and C are stored bottom-to-top already.
        return HW_START[zone] + pos;
    }

    public static int zoneValue(int levelPercent, int brightnessPercent) {
        int level = clamp(levelPercent, 0, 100);
        int brightness = clamp(brightnessPercent, 0, 100);
        return Math.round(MAX_VALUE * (level / 100f) * (brightness / 100f));
    }

    /** Number of LEDs lit in a strip at a percent, with 1% showing one LED. */
    public static int litCount(int zoneLeds, int percent) {
        int p = clamp(percent, 0, 100);
        if (p == 0) {
            return 0;
        }
        return (int) Math.ceil(zoneLeds * (p / 100f));
    }

    /** Full-strip frame for one behavior. */
    public static int[] behaviorFrame(boolean[] zoneOn, int[] zoneBits,
                                      int[] zoneLevels, int brightness, int behavior) {
        int[] frame = new int[LEDS];
        for (int zone = 0; zone < ZONES; zone++) {
            if (!zoneOn[zone] || (zoneBits[zone] & behavior) == 0) {
                continue;
            }
            int value = zoneValue(zoneLevels[zone], brightness);
            int count = ZONE_LEDS[zone];
            for (int pos = 0; pos < count; pos++) {
                frame[hwIndex(zone, pos)] = value;
            }
        }
        return frame;
    }

    /** Progress frame: each participating strip fills from its bottom. */
    public static int[] progressFrame(boolean[] zoneOn, int[] zoneBits,
                                      int[] zoneLevels, int brightness, int behavior,
                                      int percent) {
        int[] frame = new int[LEDS];
        int p = clamp(percent, 0, 100);
        for (int zone = 0; zone < ZONES; zone++) {
            if (!zoneOn[zone] || (zoneBits[zone] & behavior) == 0) {
                continue;
            }
            int value = zoneValue(zoneLevels[zone], brightness);
            int count = litCount(ZONE_LEDS[zone], p);
            for (int pos = 0; pos < count; pos++) {
                frame[hwIndex(zone, pos)] = value;
            }
        }
        return frame;
    }

    /** Which hardware LEDs belong to a behavior, even when its value is zero. */
    public static boolean[] behaviorMask(boolean[] zoneOn, int[] zoneBits, int behavior) {
        boolean[] mask = new boolean[LEDS];
        for (int zone = 0; zone < ZONES; zone++) {
            if (!zoneOn[zone] || (zoneBits[zone] & behavior) == 0) {
                continue;
            }
            for (int pos = 0; pos < ZONE_LEDS[zone]; pos++) {
                mask[hwIndex(zone, pos)] = true;
            }
        }
        return mask;
    }

    /** Preview frame that still shows a strip even when it is disabled. */
    public static int[] zoneFrame(boolean[] zoneOn, int[] zoneLevels, int brightness, int zone) {
        int[] frame = new int[LEDS];
        if (zone < 0 || zone >= ZONES) {
            return frame;
        }
        int value = zoneOn[zone] ? zoneValue(zoneLevels[zone], brightness) : MAX_VALUE;
        for (int pos = 0; pos < ZONE_LEDS[zone]; pos++) {
            frame[hwIndex(zone, pos)] = value;
        }
        return frame;
    }

    /** Every enabled strip, used by the global notification/call animations. */
    public static int[] soundFrame(boolean[] zoneOn, int[] zoneLevels, int brightness) {
        int[] frame = new int[LEDS];
        for (int zone = 0; zone < ZONES; zone++) {
            if (!zoneOn[zone]) {
                continue;
            }
            int value = zoneValue(zoneLevels[zone], brightness);
            for (int pos = 0; pos < ZONE_LEDS[zone]; pos++) {
                frame[hwIndex(zone, pos)] = value;
            }
        }
        return frame;
    }

    /** Preview frame that still shows disabled strips. */
    public static int[] allFrame(boolean[] zoneOn, int[] zoneLevels, int brightness) {
        int[] frame = new int[LEDS];
        for (int zone = 0; zone < ZONES; zone++) {
            int value = zoneOn[zone] ? zoneValue(zoneLevels[zone], brightness) : MAX_VALUE;
            for (int pos = 0; pos < ZONE_LEDS[zone]; pos++) {
                frame[hwIndex(zone, pos)] = value;
            }
        }
        return frame;
    }

    public static int[] fullZone(int zone) {
        int[] frame = new int[LEDS];
        if (zone < 0 || zone >= ZONES) {
            return frame;
        }
        for (int pos = 0; pos < ZONE_LEDS[zone]; pos++) {
            frame[hwIndex(zone, pos)] = MAX_VALUE;
        }
        return frame;
    }

    public static int[] fullFrame() {
        int[] frame = new int[LEDS];
        for (int i = 0; i < LEDS; i++) {
            frame[i] = MAX_VALUE;
        }
        return frame;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
