package dev.brl.glyph;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ScheduleReceiver extends BroadcastReceiver {

    public static final String ACTION_START = "dev.brl.glyph.SCHEDULE_START";
    public static final String ACTION_STOP = "dev.brl.glyph.SCHEDULE_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            GlyphEngine.get().clear();
        } else if (ACTION_STOP.equals(action)) {
            GlyphEngine.get().apply(context);
            GlyphService.refresh(context);
        }
        Schedule.apply(context);
    }
}
