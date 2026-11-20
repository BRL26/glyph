package dev.brl.glyph;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

public final class Schedule {

    private static final int REQ_START = 10;
    private static final int REQ_STOP = 11;

    private Schedule() {
    }

    public static void apply(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent start = pending(context, REQ_START, true);
        PendingIntent stop = pending(context, REQ_STOP, false);
        manager.cancel(start);
        manager.cancel(stop);
        if (!Prefs.scheduleEnabled(context)) {
            return;
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                next(Prefs.scheduleStart(context)), start);
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                next(Prefs.scheduleEnd(context)), stop);
    }

    private static PendingIntent pending(Context context, int request, boolean start) {
        Intent intent = new Intent(context, ScheduleReceiver.class)
                .setAction(start ? ScheduleReceiver.ACTION_START : ScheduleReceiver.ACTION_STOP);
        return PendingIntent.getBroadcast(context, request, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static long next(int minutes) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, minutes / 60);
        calendar.set(Calendar.MINUTE, minutes % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }
}
