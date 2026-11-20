package dev.brl.glyph;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * Material You role colors. Android exposes the wallpaper-derived tonal
 * palettes as system colors from API 31, so the app can follow the phone theme
 * without pulling in Material Components or AppCompat.
 */
public final class Palette {

    public final boolean light;
    public final int background;
    public final int surface;
    public final int surface2;
    public final int surface3;
    public final int primary;
    public final int onPrimary;
    public final int primaryContainer;
    public final int onPrimaryContainer;
    public final int secondary;
    public final int secondaryContainer;
    public final int onSecondaryContainer;
    public final int onSurface;
    public final int onSurfaceVariant;
    public final int outline;

    private Palette(Context context) {
        Resources resources = context.getResources();
        light = (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;

        if (light) {
            background = color(context, android.R.color.system_neutral1_50);
            surface = color(context, android.R.color.system_neutral1_10);
            surface2 = color(context, android.R.color.system_neutral2_10);
            surface3 = color(context, android.R.color.system_neutral2_50);
            primary = color(context, android.R.color.system_accent1_600);
            onPrimary = color(context, android.R.color.system_accent1_0);
            primaryContainer = color(context, android.R.color.system_accent1_100);
            onPrimaryContainer = color(context, android.R.color.system_accent1_900);
            secondary = color(context, android.R.color.system_accent2_600);
            secondaryContainer = color(context, android.R.color.system_accent2_100);
            onSecondaryContainer = color(context, android.R.color.system_accent2_900);
            onSurface = color(context, android.R.color.system_neutral1_900);
            onSurfaceVariant = color(context, android.R.color.system_neutral2_700);
            outline = color(context, android.R.color.system_neutral2_500);
        } else {
            background = color(context, android.R.color.system_neutral1_900);
            surface = color(context, android.R.color.system_neutral1_800);
            surface2 = color(context, android.R.color.system_neutral2_800);
            surface3 = color(context, android.R.color.system_neutral2_700);
            primary = color(context, android.R.color.system_accent1_200);
            onPrimary = color(context, android.R.color.system_accent1_800);
            primaryContainer = color(context, android.R.color.system_accent1_700);
            onPrimaryContainer = color(context, android.R.color.system_accent1_100);
            secondary = color(context, android.R.color.system_accent2_200);
            secondaryContainer = color(context, android.R.color.system_accent2_700);
            onSecondaryContainer = color(context, android.R.color.system_accent2_100);
            onSurface = color(context, android.R.color.system_neutral1_100);
            onSurfaceVariant = color(context, android.R.color.system_neutral2_200);
            outline = color(context, android.R.color.system_neutral2_500);
        }
    }

    public static Palette of(Context context) {
        return new Palette(context);
    }

    private static int color(Context context, int resource) {
        try {
            return context.getColor(resource);
        } catch (Resources.NotFoundException error) {
            // A stripped system image without the dynamic palette: keep the
            // old dark baseline rather than drawing transparent surfaces.
            return 0xFF1D1B20;
        }
    }
}
