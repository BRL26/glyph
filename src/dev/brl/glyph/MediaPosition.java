package dev.brl.glyph;

/**
 * Pure math for a media session's reported position. Media apps publish a
 * position and the elapsed-realtime timestamp it was captured at; the live
 * position has to be extrapolated at the playback speed between updates.
 */
public final class MediaPosition {

    private MediaPosition() {
    }

    public static long current(long positionMs, long updatedAtMs, long nowMs,
                               float speed, boolean playing) {
        long base = Math.max(0L, positionMs);
        if (!playing || speed <= 0f) {
            return base;
        }
        long elapsed = Math.max(0L, nowMs - updatedAtMs);
        double advanced = elapsed * (double) speed;
        if (advanced <= 0.0) {
            return base;
        }
        long room = Long.MAX_VALUE / 4L - base;
        if (room <= 0L || advanced >= room) {
            return base;
        }
        return base + (long) advanced;
    }

    public static int percent(long positionMs, long durationMs) {
        if (durationMs <= 0L) {
            return -1;
        }
        long position = Math.max(0L, Math.min(positionMs, durationMs));
        return (int) Math.round(position * 100.0 / durationMs);
    }
}
