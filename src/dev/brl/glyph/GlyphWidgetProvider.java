package dev.brl.glyph;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

/**
 * Compact launcher surface for the output gates. It is deliberately
 * a set of switches, not a second settings screen: each control only flips the
 * same non-destructive gate used by Home and the Quick Settings tiles.
 */
public final class GlyphWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_TOGGLE = "dev.brl.glyph.WIDGET_TOGGLE";
    private static final String EXTRA_CONTROL = "control";

    private static final int ALL_OFF = 5;
    private static final int LIGHTS = 6;
    private static final int VISUALIZER = 7;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            manager.updateAppWidget(id, build(context, manager.getAppWidgetOptions(id)));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int id, Bundle options) {
        manager.updateAppWidget(id, build(context, options));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            int control = intent.getIntExtra(EXTRA_CONTROL, -1);
            if (control == ALL_OFF) {
                OutputGate.toggleAllOff(context);
            } else if (control == LIGHTS) {
                OutputGate.toggleFlashlight(context);
            } else if (control == VISUALIZER) {
                OutputGate.toggleVisualizer(context);
            } else if (control >= 0) {
                OutputGate.toggle(context, control);
            }
        }
        super.onReceive(context, intent);
        updateAll(context);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, GlyphWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        for (int id : ids) {
            manager.updateAppWidget(id, build(context, manager.getAppWidgetOptions(id)));
        }
    }

    private static RemoteViews build(Context context, Bundle options) {
        int width = options == null ? 0
                : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int height = options == null ? 0
                : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        if (width < 220 || height < 170) {
            return buildSmall(context, width >= 100);
        }

        Palette palette = Palette.of(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.glyph_widget);
        views.setTextColor(R.id.widget_title, palette.onSurface);

        setChip(views, R.id.widget_music, "Music",
                OutputGate.enabled(context, OutputGate.MUSIC), palette);
        setChip(views, R.id.widget_volume, "Volume",
                OutputGate.enabled(context, OutputGate.VOLUME), palette);
        setChip(views, R.id.widget_battery, "Battery",
                OutputGate.enabled(context, OutputGate.BATTERY), palette);
        setChip(views, R.id.widget_ringtone, "Ringtone",
                OutputGate.enabled(context, OutputGate.RINGTONE), palette);
        setChip(views, R.id.widget_visualizer, "Visualizer",
                OutputGate.visualizer(context), palette);
        setChip(views, R.id.widget_all_off,
                OutputGate.allOff(context) ? "All off: on" : "All off",
                OutputGate.allOff(context), palette);
        setChip(views, R.id.widget_lights,
                OutputGate.flashlight(context) ? "Glyph lights: on" : "Glyph lights",
                OutputGate.flashlight(context), palette);

        views.setOnClickPendingIntent(R.id.widget_music, pending(context, OutputGate.MUSIC));
        views.setOnClickPendingIntent(R.id.widget_volume, pending(context, OutputGate.VOLUME));
        views.setOnClickPendingIntent(R.id.widget_battery, pending(context, OutputGate.BATTERY));
        views.setOnClickPendingIntent(R.id.widget_ringtone, pending(context, OutputGate.RINGTONE));
        views.setOnClickPendingIntent(R.id.widget_visualizer, pending(context, VISUALIZER));
        views.setOnClickPendingIntent(R.id.widget_all_off, pending(context, ALL_OFF));
        views.setOnClickPendingIntent(R.id.widget_lights, pending(context, LIGHTS));
        return views;
    }

    private static RemoteViews buildSmall(Context context, boolean showLabel) {
        Palette palette = Palette.of(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.glyph_widget_small);
        views.setInt(R.id.widget_small_root, "setBackgroundResource", R.drawable.bg_widget);
        views.setInt(R.id.widget_small_icon, "setColorFilter", palette.primary);
        views.setTextColor(R.id.widget_small_label, palette.onSurface);
        views.setViewVisibility(R.id.widget_small_label,
                showLabel ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setOnClickPendingIntent(R.id.widget_small_root, pending(context, LIGHTS));
        return views;
    }

    private static void setChip(RemoteViews views, int id, String label,
                                boolean active, Palette palette) {
        views.setTextViewText(id, label);
        views.setTextColor(id, active ? palette.onPrimaryContainer : palette.onSurface);
        views.setInt(id, "setBackgroundResource",
                active ? R.drawable.bg_widget_chip_active : R.drawable.bg_widget_chip);
    }

    private static PendingIntent pending(Context context, int control) {
        Intent intent = new Intent(context, GlyphWidgetProvider.class);
        intent.setAction(ACTION_TOGGLE);
        intent.putExtra(EXTRA_CONTROL, control);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, control, intent, flags);
    }
}
