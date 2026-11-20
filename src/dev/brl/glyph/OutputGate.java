package dev.brl.glyph;

import android.content.Context;
import android.widget.Toast;

/**
 * Non-destructive output gates. These switches never edit zone assignments,
 * levels or behavior bits; they only decide whether a producer may reach the
 * LED writer. Music, Volume, Battery and Ringtone are category gates. There is
 * deliberately no second "Glyph output" gate: On is the permission switch and
 * All off is the single master kill.
 *
 * Glyph lights (the torch) bypasses all of them. The music visualizer is a
 * manual, persistent mode: it claims the strips until it is turned off, but a
 * call or notification still takes priority and the visualizer resumes after.
 */
public final class OutputGate {

    public static final int MUSIC = 0;
    public static final int VOLUME = 1;
    public static final int BATTERY = 2;
    public static final int RINGTONE = 3;
    /** Internal "normal output" category; blocked only by All off, never gated. */
    public static final int GLYPHS = 4;

    public static final String[] NAMES = {
            "Music", "Volume", "Battery", "Ringtone"
    };
    public static final String[] DESCRIPTIONS = {
            "Block media progress from every strip",
            "Block the hardware volume bar from every strip",
            "Block charging fill and charging level",
            "Block call and ringtone animations"
    };

    private OutputGate() {
    }

    private static String key(int category) {
        switch (category) {
            case MUSIC:
                return "gate_music";
            case VOLUME:
                return "gate_volume";
            case BATTERY:
                return "gate_battery";
            case RINGTONE:
                return "gate_ringtone";
            default:
                return "gate_unknown";
        }
    }

    public static boolean enabled(Context context, int category) {
        if (category == GLYPHS) {
            return !allOff(context);
        }
        return Prefs.gate(context, key(category));
    }

    private static final int ALL_CATEGORIES = -1;

    public static void setEnabled(Context context, int category, boolean enabled) {
        Prefs.setGate(context, key(category), enabled);
        onChanged(context, category);
    }

    public static void toggle(Context context, int category) {
        setEnabled(context, category, !enabled(context, category));
    }

    public static boolean allOff(Context context) {
        return Prefs.allOff(context);
    }

    public static void setAllOff(Context context, boolean value) {
        Prefs.setAllOff(context, value);
        if (value) {
            // All off is the kill switch for every normal output, including
            // the manual visualizer. The torch is the only exception.
            setVisualizer(context, false);
        }
        onChanged(context, ALL_CATEGORIES);
    }

    public static void toggleAllOff(Context context) {
        setAllOff(context, !allOff(context));
    }

    public static boolean flashlight(Context context) {
        return Prefs.glyphLights(context);
    }

    public static void setFlashlight(Context context, boolean value) {
        Prefs.setGlyphLights(context, value);
        GlyphEngine.get().setFlashlight(value);
        GlyphWidgetProvider.updateAll(context);
    }

    public static void toggleFlashlight(Context context) {
        setFlashlight(context, !flashlight(context));
    }

    // ------------------------------------------------------- visualizer

    public static boolean visualizer(Context context) {
        return Prefs.visualizer(context);
    }

    /**
     * Arms or disarms the persistent audio visualizer. It is manual output, so
     * it is not blocked by the category gates; All off still stops it. Returns
     * false when the microphone permission is missing.
     */
    public static boolean setVisualizer(Context context, boolean value) {
        if (value) {
            if (!AudioPulse.hasPermission(context)) {
                Toast.makeText(context,
                        "Microphone permission is required for the visualizer",
                        Toast.LENGTH_LONG).show();
                return false;
            }
            if (allOff(context)) {
                Toast.makeText(context,
                        "Turn 'All glyphs off' off first", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        Prefs.setVisualizer(context, value);
        if (value) {
            GlyphService.start(context, GlyphService.ACTION_VISUALIZER_ON);
        } else if (GlyphService.isRunning()) {
            GlyphService.start(context, GlyphService.ACTION_VISUALIZER_OFF);
        }
        GlyphWidgetProvider.updateAll(context);
        return true;
    }

    public static boolean toggleVisualizer(Context context) {
        return setVisualizer(context, !visualizer(context));
    }

    /**
     * Category gates are evaluated only after All off. The flashlight and the
     * visualizer are checked before this method anywhere they can write.
     */
    public static boolean allows(Context context, int category) {
        if (allOff(context)) {
            return false;
        }
        if (category == GLYPHS) {
            return true;
        }
        return Prefs.gate(context, key(category));
    }

    /** Maps a behavior to its category without touching zone settings. */
    public static boolean allowsBehavior(Context context, int behavior) {
        if (behavior == Prefs.B_MEDIA) {
            return allows(context, MUSIC);
        }
        if (behavior == Prefs.B_VOLUME) {
            return allows(context, VOLUME);
        }
        if (behavior == Prefs.B_CHARGE_BRIEF || behavior == Prefs.B_CHARGE_FULL) {
            return allows(context, BATTERY);
        }
        // Essential/notification progress have no separate category switch;
        // All off still blocks them.
        return allows(context, GLYPHS);
    }

    /** Calls are ringtone-gated; notification sounds use the normal master. */
    public static boolean allowsAlert(Context context, int level) {
        if (!allows(context, GLYPHS)) {
            return false;
        }
        return level != GlyphEngine.LEVEL_CALL || allows(context, RINGTONE);
    }

    /** Refresh the engine and only the service state this gate can affect. */
    private static void onChanged(Context context, int category) {
        GlyphEngine.get().refreshGates(context);
        GlyphWidgetProvider.updateAll(context);
        try {
            GlyphService.refreshIfNeeded(context, category);
        } catch (RuntimeException ignored) {
            // A control surface can change state while the service is
            // unavailable; the persisted gate still applies on the next start.
        }
    }
}
