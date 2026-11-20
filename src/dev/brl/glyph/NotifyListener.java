package dev.brl.glyph;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

public final class NotifyListener extends NotificationListenerService {

    private static final String TAG = "GlyphNotify";

    private String activeMediaKey;
    private String activeNotificationKey;
    private final Set<String> essentialKeys = new HashSet<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) {
            return;
        }
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return;
        }
        Bundle extras = notification.extras;
        if (extras == null) {
            extras = Bundle.EMPTY;
        }

        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = body(extras);

        boolean handledProgress = handleProgress(sbn, notification, extras, pkg, title, text);
        boolean ongoing = sbn.isOngoing();
        boolean summary = (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        boolean call = Notification.CATEGORY_CALL.equals(notification.category);

        // Notifications and calls are global sound animations. They start even
        // when the phone is silent or vibrating; AudioPulse replaces the
        // fallback with live spectrum frames when sound is present.
        if (Prefs.allowed(this) && Prefs.audioPulseNotifications(this)
                && !ongoing && !summary && !call) {
            GlyphService.start(this, GlyphService.ACTION_AUDIO_PULSE);
        }

        // Essential notifications are the persistent bar, independent of the
        // sound animation. It stays lit until the matching notification is
        // removed.
        if (isEssential(sbn)) {
            essentialKeys.add(sbn.getKey());
            Log.d(TAG, "essential " + pkg + " active=" + essentialKeys.size()
                    + " progress=" + handledProgress);
            GlyphEngine.get().showEssential(this);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        String key = sbn.getKey();
        if (key == null) {
            return;
        }
        if (key.equals(activeMediaKey)) {
            activeMediaKey = null;
            GlyphEngine.get().clearStatic(Prefs.B_MEDIA);
        }
        if (key.equals(activeNotificationKey)) {
            activeNotificationKey = null;
            GlyphEngine.get().clearStatic(Prefs.B_NOTIFICATION_PROGRESS);
        }
        if (essentialKeys.remove(key)) {
            if (essentialKeys.isEmpty()) {
                GlyphEngine.get().clearStatic(Prefs.B_ESSENTIAL);
            }
            Log.d(TAG, "essential removed active=" + essentialKeys.size());
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        essentialKeys.clear();
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                if (isEssential(sbn)) {
                    essentialKeys.add(sbn.getKey());
                }
            }
        }
        if (!essentialKeys.isEmpty() && Prefs.allowed(this)
                && Prefs.anyZoneHas(this, Prefs.B_ESSENTIAL)) {
            GlyphEngine.get().showEssential(this);
        } else {
            GlyphEngine.get().clearStatic(Prefs.B_ESSENTIAL);
        }
        Log.d(TAG, "listener connected essential=" + essentialKeys.size()
                + " access=" + Prefs.allowed(this));
    }

    private boolean isEssential(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return false;
        }
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) {
            return false;
        }
        Notification notification = sbn.getNotification();
        if (sbn.isOngoing()
                || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return false;
        }
        if (!Prefs.allowed(this) || !Prefs.anyZoneHas(this, Prefs.B_ESSENTIAL)) {
            return false;
        }
        // No rules means no persistent bar. Treating "no filter" as "every
        // notification" turned dozens of active notifications into one bar
        // that never went out.
        if (!Rules.hasEnabled(this, Rules.ESSENTIAL)) {
            return false;
        }
        Bundle extras = notification.extras == null ? Bundle.EMPTY : notification.extras;
        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        return Rules.matches(this, Rules.ESSENTIAL, pkg, title, body(extras));
    }

    private boolean handleProgress(StatusBarNotification sbn, Notification notification,
                                   Bundle extras, String pkg, String title, String text) {
        if (!Prefs.allowed(this)) {
            return false;
        }
        if (!extras.containsKey(Notification.EXTRA_PROGRESS)) {
            return false;
        }
        boolean indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        int progress = extras.getInt(Notification.EXTRA_PROGRESS, -1);
        int max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100);
        if (indeterminate || progress < 0 || max <= 0) {
            return false;
        }
        int percent = Math.round(progress * 100f / max);

        boolean isMedia = Notification.CATEGORY_TRANSPORT.equals(notification.category)
                || extras.containsKey(Notification.EXTRA_MEDIA_SESSION);

        // A notification belongs to exactly one progress mode. Media progress
        // only consumes media-session notifications; notification progress
        // handles every other progress notification.
        boolean media = isMedia
                && (!Rules.hasEnabled(this, Rules.MEDIA)
                || Rules.matches(this, Rules.MEDIA, pkg, title, text));
        boolean notificationProgress = !isMedia
                && (!Rules.hasEnabled(this, Rules.NOTIFICATION)
                || Rules.matches(this, Rules.NOTIFICATION, pkg, title, text));

        if (media && !Prefs.anyZoneHas(this, Prefs.B_MEDIA)) {
            media = false;
        }
        if (notificationProgress
                && !Prefs.anyZoneHas(this, Prefs.B_NOTIFICATION_PROGRESS)) {
            notificationProgress = false;
        }
        if (!media && !notificationProgress) {
            return false;
        }

        if (media) {
            activeMediaKey = sbn.getKey();
            GlyphEngine.get().setProgress(this, Prefs.B_MEDIA, percent);
        } else {
            activeNotificationKey = sbn.getKey();
            GlyphEngine.get().setProgress(this, Prefs.B_NOTIFICATION_PROGRESS, percent);
        }
        Log.d(TAG, "progress " + pkg + " " + percent + "% media=" + media
                + " notification=" + notificationProgress);
        return true;
    }

    private static String body(Bundle extras) {
        String text = text(extras.getCharSequence(Notification.EXTRA_TEXT));
        String big = text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String sub = text(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (!big.isEmpty()) {
            text = text.isEmpty() ? big : text + " " + big;
        }
        if (!sub.isEmpty()) {
            text = text.isEmpty() ? sub : text + " " + sub;
        }
        return text;
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
