package dev.brl.glyph;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        applyStockReplacement(context);
        Root.get().init(context.getApplicationContext());
        Schedule.apply(context);
        if (!Prefs.allowed(context)) {
            return;
        }
        if (Prefs.anyZoneHas(context, Prefs.B_VOLUME)) {
            GlyphService.start(context, GlyphService.ACTION_REFRESH);
        }
        if (Prefs.anyZoneHas(context, Prefs.B_CHARGE_FULL) && isCharging(context)) {
            GlyphService.start(context, GlyphService.ACTION_CHARGING_FULL_START);
        }
    }

    private void applyStockReplacement(Context context) {
        if (!Prefs.replaceStock(context)) {
            return;
        }
        Root.get().init(context.getApplicationContext());
        // co.aospa.glyph is a persistent system app: force-stop lets the
        // system restart it. Freeze its process so it cannot keep painting a
        // full-white frame over the replacement's volume bar.
        Root.get().exec("pm disable-user --user 0 co.aospa.glyph; "
                + "am force-stop co.aospa.glyph; "
                + "for p in $(ps -A -o PID,ARGS | grep '[c]o.aospa.glyph' "
                + "| awk '{print $1}'); do kill -STOP $p; done");
    }

    private boolean isCharging(Context context) {
        Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            return false;
        }
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }
}
