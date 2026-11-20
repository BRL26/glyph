package dev.brl.glyph;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

public final class PhoneStateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.allowed(context) || !Prefs.audioPulseCalls(context)) {
            return;
        }
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            GlyphService.start(context, GlyphService.ACTION_CALL_START);
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)
                || TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            GlyphService.start(context, GlyphService.ACTION_CALL_STOP);
        }
    }
}
