package dev.brl.glyph;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class PowerReceiver extends BroadcastReceiver {

    private static final String TAG = "GlyphPower";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean connected = Intent.ACTION_POWER_CONNECTED.equals(action);
        if (!connected) {
            Log.d(TAG, "power disconnected, clearing");
            // Clear here as well as in the service: if the process was killed
            // while the battery bar was latched, the frame would otherwise stay
            // on until the next frame write.
            GlyphEngine.get().stopCharging();
            GlyphEngine.get().clearStatic(Prefs.B_CHARGE_FULL);
            GlyphService.start(context, GlyphService.ACTION_CHARGING_FULL_STOP);
            return;
        }
        Log.d(TAG, "power connected allowed=" + Prefs.allowed(context));
        if (!Prefs.allowed(context)) {
            return;
        }
        if (Prefs.anyZoneHas(context, Prefs.B_CHARGE_BRIEF)) {
            GlyphService.start(context, GlyphService.ACTION_CHARGING);
        }
        if (Prefs.anyZoneHas(context, Prefs.B_CHARGE_FULL)) {
            GlyphService.start(context, GlyphService.ACTION_CHARGING_FULL_START);
        }
    }
}
