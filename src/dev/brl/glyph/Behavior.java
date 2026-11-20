package dev.brl.glyph;

/** The seven glyph behaviors and their stock-matching default strips. */
public final class Behavior {

    public static final int ESSENTIAL = 1;
    public static final int CALLS = 1 << 1;
    public static final int VOLUME = 1 << 2;
    public static final int NOTIFICATION_PROGRESS = 1 << 3;
    public static final int MEDIA = 1 << 4;
    public static final int CHARGE_BRIEF = 1 << 5;
    public static final int CHARGE_FULL = 1 << 6;

    public static final int COUNT = 7;

    /**
     * Calls and notification sounds are global alerts, not per-strip
     * assignments. Essential notifications stay per-strip because they are a
     * persistent bar for a matched notification.
     */
    public static final boolean[] PER_ZONE = {
            true, false, true, true, true, true, true
    };

    public static final String[] NAMES = {
            "Essential notifications",
            "Calls",
            "Volume",
            "Notification progress",
            "Media progress",
            "Charging brief",
            "Charging full"
    };

    public static final String[] DESCRIPTIONS = {
            "Keep the assigned strip lit while a matching notification is active",
            "Global call animation; configure it on the Alerts tab",
            "Bottom-up volume bar on the 20-LED top-left strip",
            "Bottom-up progress from matching app notifications",
            "Bottom-up progress from media notifications only",
            "One bottom-up fill when power is connected",
            "Battery level bar while the phone is charging"
    };

    /**
     * Public zone order matches the SDK: A is the 11-LED right strip, B the
     * 5-LED bottom-left strip, and C the 20-LED top-left strip. The defaults
     * mirror the stock ROM: volume/progress use C, charging uses A, and the
     * short plug-in fill uses B.
     */
    public static final int[] DEFAULT_ZONE_BITS = {
            ESSENTIAL | CHARGE_FULL,
            CHARGE_BRIEF,
            VOLUME | MEDIA | NOTIFICATION_PROGRESS
    };

    private Behavior() {
    }

    public static int bit(int index) {
        return 1 << index;
    }
}
