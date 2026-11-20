package dev.brl.glyph;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

public final class Prefs {

    public static final int[] ZONE_LEDS = GlyphLayout.ZONE_LEDS;
    public static final int FRAME_LENGTH = GlyphLayout.LEDS;
    public static final int MAX_VALUE = GlyphLayout.MAX_VALUE;

    public static final int STYLE_PULSE = GlyphEffects.STYLE_PULSE;
    public static final int STYLE_BREATHE = GlyphEffects.STYLE_BREATHE;
    public static final int STYLE_WAVE = GlyphEffects.STYLE_WAVE;

    public static final int B_ESSENTIAL = Behavior.ESSENTIAL;
    public static final int B_CALLS = Behavior.CALLS;
    public static final int B_VOLUME = Behavior.VOLUME;
    public static final int B_NOTIFICATION_PROGRESS = Behavior.NOTIFICATION_PROGRESS;
    public static final int B_MEDIA = Behavior.MEDIA;
    public static final int B_CHARGE_BRIEF = Behavior.CHARGE_BRIEF;
    public static final int B_CHARGE_FULL = Behavior.CHARGE_FULL;

    public static final String[] BEHAVIOR_NAMES = Behavior.NAMES;
    public static final String[] BEHAVIOR_DESCRIPTIONS = Behavior.DESCRIPTIONS;

    public static final int BEHAVIOR_COUNT = Behavior.COUNT;

    public static final int AUTOGEN_NOTE_MAP = 0;
    public static final int AUTOGEN_BEATS = 1;
    public static final int AUTOGEN_SMOOTH = 2;
    public static final int AUTOGEN_FREQUENCY = 3;
    public static final int AUTOGEN_PATTERN = 4;
    public static final int AUTOGEN_COUNT = 5;
    public static final String[] AUTOGEN_NAMES = {
            "Note map", "Beats", "Smooth", "Frequency", "Pattern"
    };
    public static final String[] AUTOGEN_DESCRIPTIONS = {
            "Each detected pitch gets its own LED",
            "Beats mode from the website auto-generator",
            "The same generator with anti-flicker smoothing",
            "Frequency bands mapped onto the three strips",
            "Website Pattern wave driven by detected beats"
    };

    private Prefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences("glyph", Context.MODE_PRIVATE);
    }

    /** Version 2 uses the switch as a permission gate, so it starts on. */
    public static void migrate(Context context) {
        SharedPreferences preferences = sp(context);
        // Calls are global now; remove the old per-strip Calls bit once.
        if (!preferences.getBoolean("v8_init", false)) {
            SharedPreferences.Editor editor = preferences.edit();
            for (int zone = 0; zone < 3; zone++) {
                int bits = preferences.getInt("zone_bits_" + zone,
                        Behavior.DEFAULT_ZONE_BITS[zone]);
                editor.putInt("zone_bits_" + zone, bits & ~Behavior.CALLS);
            }
            editor.putBoolean("v8_init", true);
            editor.apply();
        }
        // Essential notifications are the persistent bar. Make sure a strip
        // is assigned once; without this an old layout left the feature with
        // no destination and it looked broken.
        if (!preferences.getBoolean("v9_init", false)) {
            boolean assigned = false;
            for (int zone = 0; zone < 3; zone++) {
                if ((preferences.getInt("zone_bits_" + zone,
                        Behavior.DEFAULT_ZONE_BITS[zone]) & Behavior.ESSENTIAL) != 0) {
                    assigned = true;
                    break;
                }
            }
            SharedPreferences.Editor editor = preferences.edit();
            if (!assigned) {
                editor.putInt("zone_bits_0",
                        preferences.getInt("zone_bits_0", Behavior.DEFAULT_ZONE_BITS[0])
                                | Behavior.ESSENTIAL);
                editor.putBoolean("zone_on_0", true);
            }
            editor.putBoolean("v9_init", true);
            editor.apply();
        }
        if (!preferences.getBoolean("v2_init", false)) {
            preferences.edit()
                    .putBoolean("master", true)
                    .putBoolean("v2_init", true)
                    .apply();
        }
        // Earlier builds could leave every strip saved as off, which made all
        // behavior switches appear broken. Restore the intended default once.
        if (!preferences.getBoolean("v4_init", false)) {
            SharedPreferences.Editor editor = preferences.edit();
            for (int zone = 0; zone < 3; zone++) {
                editor.putBoolean("zone_on_" + zone, true);
            }
            editor.putBoolean("v4_init", true);
            editor.apply();
        }
        // Older builds treated B as the 20-LED strip and C as the 5-LED strip.
        // Reset the assignments to the SDK's A/B/C layout once so every
        // behavior has a valid destination on the hardware.
        if (!preferences.getBoolean("v5_init", false)) {
            preferences.edit()
                    .putBoolean("zone_on_0", true)
                    .putBoolean("zone_on_1", true)
                    .putBoolean("zone_on_2", true)
                    .putInt("zone_bits_0", B_ESSENTIAL | B_CALLS | B_CHARGE_FULL)
                    .putInt("zone_bits_1", B_CHARGE_BRIEF)
                    .putInt("zone_bits_2", B_VOLUME | B_MEDIA | B_NOTIFICATION_PROGRESS)
                    .putBoolean("v5_init", true)
                    .apply();
        }
        // The UI previously paired the 20-LED C strip and 11-LED A strip with
        // the wrong physical positions. Reset assignments once so volume and
        // progress start on the correct bottom edge and use the stock strip
        // lengths (20 for volume/progress, 11 for battery).
        if (!preferences.getBoolean("v6_init", false)) {
            SharedPreferences.Editor editor = preferences.edit();
            for (int zone = 0; zone < 3; zone++) {
                editor.putBoolean("zone_on_" + zone, true);
                editor.putInt("zone_bits_" + zone, Behavior.DEFAULT_ZONE_BITS[zone]);
            }
            editor.putBoolean("v6_init", true);
            editor.apply();
        }
        // A previous settings write could leave Volume assigned to no strip,
        // which made both the UI and the root key hook silently do nothing.
        // Repair that once, without overwriting a deliberate later choice.
        if (!preferences.getBoolean("v7_init", false)) {
            boolean volumeAssigned = false;
            for (int zone = 0; zone < 3; zone++) {
                if ((preferences.getInt("zone_bits_" + zone,
                        Behavior.DEFAULT_ZONE_BITS[zone]) & Behavior.VOLUME) != 0) {
                    volumeAssigned = true;
                    break;
                }
            }
            SharedPreferences.Editor editor = preferences.edit();
            if (!volumeAssigned) {
                editor.putInt("zone_bits_2",
                        preferences.getInt("zone_bits_2",
                                Behavior.DEFAULT_ZONE_BITS[2]) | Behavior.VOLUME);
                editor.putBoolean("zone_on_2", true);
            }
            editor.putBoolean("v7_init", true);
            editor.apply();
        }
    }

    public static int behaviorBit(int index) {
        return 1 << index;
    }

    // ------------------------------------------------------------- gate

    public static boolean master(Context context) {
        return sp(context).getBoolean("master", true);
    }

    public static void setMaster(Context context, boolean value) {
        sp(context).edit().putBoolean("master", value).apply();
    }

    // ---------------------------------------------------------- output gate

    /** Category/monitor gates. They never change zone assignments. */
    public static boolean gate(Context context, String key) {
        return sp(context).getBoolean(key, true);
    }

    public static void setGate(Context context, String key, boolean value) {
        sp(context).edit().putBoolean(key, value).apply();
    }

    public static boolean allOff(Context context) {
        return sp(context).getBoolean("all_off", false);
    }

    public static void setAllOff(Context context, boolean value) {
        sp(context).edit().putBoolean("all_off", value).apply();
    }

    public static boolean glyphLights(Context context) {
        return sp(context).getBoolean("glyph_lights", false);
    }

    public static void setGlyphLights(Context context, boolean value) {
        sp(context).edit().putBoolean("glyph_lights", value).apply();
    }

    /** Persistent, manual music visualizer. It is armed until explicitly off. */
    public static boolean visualizer(Context context) {
        return sp(context).getBoolean("visualizer", false);
    }

    public static void setVisualizer(Context context, boolean value) {
        sp(context).edit().putBoolean("visualizer", value).apply();
    }

    /** Keep the stock glyph package disabled after a reboot. */
    public static boolean replaceStock(Context context) {
        return sp(context).getBoolean("replace_stock", false);
    }

    public static void setReplaceStock(Context context, boolean value) {
        sp(context).edit().putBoolean("replace_stock", value).apply();
    }

    public static boolean scheduleEnabled(Context context) {
        return sp(context).getBoolean("schedule_on", false);
    }

    public static void setScheduleEnabled(Context context, boolean value) {
        sp(context).edit().putBoolean("schedule_on", value).apply();
    }

    public static int scheduleStart(Context context) {
        return sp(context).getInt("schedule_start", 22 * 60);
    }

    public static int scheduleEnd(Context context) {
        return sp(context).getInt("schedule_end", 7 * 60);
    }

    public static void setSchedule(Context context, int start, int end) {
        sp(context).edit().putInt("schedule_start", start).putInt("schedule_end", end).apply();
    }

    public static boolean quiet(Context context) {
        if (!scheduleEnabled(context)) {
            return false;
        }
        int start = scheduleStart(context);
        int end = scheduleEnd(context);
        if (start == end) {
            return false;
        }
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }

    /** True when the manual permission is on and quiet hours are not active. */
    public static boolean allowed(Context context) {
        return master(context) && !quiet(context);
    }

    // ------------------------------------------------------------- zones

    public static int brightness(Context context) {
        return sp(context).getInt("brightness", 100);
    }

    public static void setBrightness(Context context, int value) {
        sp(context).edit().putInt("brightness", clamp(value, 0, 100)).apply();
    }

    public static boolean audioPulseNotifications(Context context) {
        return sp(context).getBoolean("audio_pulse_notifications", true);
    }

    public static void setAudioPulseNotifications(Context context, boolean value) {
        sp(context).edit().putBoolean("audio_pulse_notifications", value).apply();
    }

    public static boolean audioPulseCalls(Context context) {
        return sp(context).getBoolean("audio_pulse_calls", true);
    }

    public static void setAudioPulseCalls(Context context, boolean value) {
        sp(context).edit().putBoolean("audio_pulse_calls", value).apply();
    }

    public static boolean zoneOn(Context context, int zone) {
        return sp(context).getBoolean("zone_on_" + zone, true);
    }

    public static void setZoneOn(Context context, int zone, boolean value) {
        sp(context).edit().putBoolean("zone_on_" + zone, value).apply();
    }

    public static int zoneLevel(Context context, int zone) {
        return sp(context).getInt("zone_level_" + zone, 100);
    }

    public static void setZoneLevel(Context context, int zone, int value) {
        sp(context).edit().putInt("zone_level_" + zone, clamp(value, 0, 100)).apply();
    }

    public static int zoneBehaviors(Context context, int zone) {
        return sp(context).getInt("zone_bits_" + zone, defaultBehaviors(zone));
    }

    public static void setZoneBehaviors(Context context, int zone, int bits) {
        sp(context).edit().putInt("zone_bits_" + zone, bits).apply();
    }

    public static boolean hasBehavior(Context context, int zone, int bit) {
        return (zoneBehaviors(context, zone) & bit) != 0;
    }

    public static void setBehavior(Context context, int zone, int bit, boolean on) {
        int bits = zoneBehaviors(context, zone);
        bits = on ? (bits | bit) : (bits & ~bit);
        setZoneBehaviors(context, zone, bits);
    }

    public static boolean anyZoneHas(Context context, int bit) {
        for (int zone = 0; zone < 3; zone++) {
            if (hasBehavior(context, zone, bit)) {
                return true;
            }
        }
        return false;
    }

    public static boolean allZonesHave(Context context, int bit) {
        for (int zone = 0; zone < 3; zone++) {
            if (!hasBehavior(context, zone, bit)) {
                return false;
            }
        }
        return true;
    }

    public static void setBehaviorAll(Context context, int bit, boolean on) {
        for (int zone = 0; zone < 3; zone++) {
            setBehavior(context, zone, bit, on);
        }
    }

    private static int defaultBehaviors(int zone) {
        return Behavior.DEFAULT_ZONE_BITS[zone];
    }

    // ------------------------------------------------------------- frames

    public static int zoneValue(Context context, int zone) {
        if (!zoneOn(context, zone)) {
            return 0;
        }
        return GlyphLayout.zoneValue(zoneLevel(context, zone), brightness(context));
    }

    /** Full strip frame for a behavior, respecting the master/quiet gate. */
    public static int[] frameForBehavior(Context context, int bit) {
        if (!allowed(context)) {
            return new int[FRAME_LENGTH];
        }
        return GlyphLayout.behaviorFrame(zoneOnArray(context), zoneBitsArray(context),
                zoneLevelArray(context), brightness(context), bit);
    }

    /** All enabled LEDs at their configured level, for sound-reactive effects. */
    public static int[] soundFrame(Context context) {
        if (!allowed(context)) {
            return new int[FRAME_LENGTH];
        }
        return GlyphLayout.soundFrame(zoneOnArray(context), zoneLevelArray(context),
                brightness(context));
    }

    /** Progress frame: each assigned strip fills from its bottom. */
    public static int[] progressFrame(Context context, int bit, int percent) {
        if (!allowed(context)) {
            return new int[FRAME_LENGTH];
        }
        return GlyphLayout.progressFrame(zoneOnArray(context), zoneBitsArray(context),
                zoneLevelArray(context), brightness(context), bit, percent);
    }

    /** LEDs belonging to a behavior, including the zero end of a progress bar. */
    /** LEDs on strips that are currently enabled, for global effects like sound. */
    public static boolean[] enabledLeds(Context context) {
        boolean[] mask = new boolean[FRAME_LENGTH];
        for (int zone = 0; zone < 3; zone++) {
            if (!zoneOn(context, zone)) {
                continue;
            }
            for (int pos = 0; pos < ZONE_LEDS[zone]; pos++) {
                mask[GlyphLayout.hwIndex(zone, pos)] = true;
            }
        }
        return mask;
    }

    public static boolean[] behaviorMask(Context context, int bit) {
        return GlyphLayout.behaviorMask(zoneOnArray(context), zoneBitsArray(context), bit);
    }

    /** Preview/test frame, independent of the permission gate. */
    public static int[] frameForZone(Context context, int zone) {
        return GlyphLayout.zoneFrame(zoneOnArray(context), zoneLevelArray(context),
                brightness(context), zone);
    }

    public static int[] frameForAll(Context context) {
        return GlyphLayout.allFrame(zoneOnArray(context), zoneLevelArray(context),
                brightness(context));
    }

    public static int[] fullZone(int zone) {
        return GlyphLayout.fullZone(zone);
    }

    public static int[] fullFrame() {
        return GlyphLayout.fullFrame();
    }

    private static boolean[] zoneOnArray(Context context) {
        boolean[] values = new boolean[3];
        for (int zone = 0; zone < 3; zone++) {
            values[zone] = zoneOn(context, zone);
        }
        return values;
    }

    private static int[] zoneLevelArray(Context context) {
        int[] values = new int[3];
        for (int zone = 0; zone < 3; zone++) {
            values[zone] = zoneLevel(context, zone);
        }
        return values;
    }

    private static int[] zoneBitsArray(Context context) {
        int[] values = new int[3];
        for (int zone = 0; zone < 3; zone++) {
            values[zone] = zoneBehaviors(context, zone);
        }
        return values;
    }

    // ------------------------------------------------------------- style

    public static int autogenMode(Context context) {
        return clamp(sp(context).getInt("autogen_mode", AUTOGEN_NOTE_MAP),
                0, AUTOGEN_COUNT - 1);
    }

    public static void setAutogenMode(Context context, int value) {
        sp(context).edit().putInt("autogen_mode",
                clamp(value, 0, AUTOGEN_COUNT - 1)).apply();
    }

    public static int style(Context context) {
        return sp(context).getInt("style", STYLE_PULSE);
    }

    public static void setStyle(Context context, int value) {
        sp(context).edit().putInt("style", value).apply();
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
