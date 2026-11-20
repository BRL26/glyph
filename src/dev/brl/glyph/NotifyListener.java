package dev.brl.glyph;

import android.app.Notification;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

public final class NotifyListener extends NotificationListenerService {

    private static final String TAG = "GlyphNotify";
    private static final long MEDIA_POLL_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String activeMediaKey;
    private String activeNotificationKey;
    private int lastMediaPercent = -1;
    private int lastNotificationPercent = -1;
    private final Set<String> essentialKeys = new HashSet<>();
    private final Set<String> alertedKeys = new HashSet<>();

    private MediaController mediaController;
    private MediaSession.Token mediaToken;
    private String mediaControllerKey;
    private boolean loggedMissingMediaDuration;

    private final Runnable mediaTick = new Runnable() {
        @Override
        public void run() {
            if (mediaController == null) {
                return;
            }
            updateMediaProgress();
            if (mediaController != null) {
                handler.postDelayed(this, MEDIA_POLL_MS);
            }
        }
    };

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateMediaProgress();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMediaProgress();
        }

        @Override
        public void onSessionDestroyed() {
            releaseMedia(true);
        }
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) {
            return;
        }
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras == null ? Bundle.EMPTY : notification.extras;
        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = body(extras);
        String key = sbn.getKey();
        boolean newKey = alertedKeys.add(key);

        MediaSession.Token token = mediaToken(extras);
        boolean media = token != null
                || Notification.CATEGORY_TRANSPORT.equals(notification.category);
        boolean ongoing = sbn.isOngoing();
        boolean summary = (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        boolean call = Notification.CATEGORY_CALL.equals(notification.category);

        if (media) {
            // Media notifications drive the progress bar, never the alert
            // animation. Some players (including Metrolist) publish
            // progressMax=0, so the actual position comes from the session.
            clearNotificationProgress(sbn);
            boolean sessionProgress = handleMedia(sbn, token, pkg, title, text);
            if (!sessionProgress) {
                handleProgress(sbn, notification, extras, pkg, title, text, true);
            }
        } else {
            if (key != null && key.equals(activeMediaKey)) {
                releaseMedia(true);
            }
            handleProgress(sbn, notification, extras, pkg, title, text, false);
            // A notification update reuses its key. Alert once per post, not
            // on every progress or text refresh.
            if (newKey && Prefs.allowed(this) && Prefs.audioPulseNotifications(this)
                    && OutputGate.allows(this, OutputGate.GLYPHS)
                    && !ongoing && !summary && !call) {
                GlyphService.start(this, GlyphService.ACTION_NOTIFICATION_ALERT);
            }
        }

        if (isEssential(sbn)) {
            essentialKeys.add(key);
            Log.d(TAG, "essential " + pkg + " active=" + essentialKeys.size()
                    + " media=" + media);
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
        alertedKeys.remove(key);
        if (key.equals(activeMediaKey)) {
            releaseMedia(true);
        }
        if (key.equals(activeNotificationKey)) {
            clearNotificationProgress(sbn);
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
        // The listener is alive before a notification, so this is the safe
        // place to have the default note tracks ready for a live alert.
        SoundCompiler.preloadDefaults(this);
        // This listener is the app's permanent system binding. Re-arm the
        // normal service here after a process restart without asking Android
        // for a foreground-service notification (and Active apps entry).
        boolean liveModes = Prefs.visualizer(this)
                || (Prefs.allowed(this)
                && (Prefs.anyZoneHas(this, Prefs.B_VOLUME)
                || Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL)
                || Prefs.anyZoneHas(this, Prefs.B_CHARGE_BRIEF)));
        if (liveModes) {
            GlyphService.start(this, GlyphService.ACTION_REFRESH);
        }
        essentialKeys.clear();
        alertedKeys.clear();
        releaseMedia(false);
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                if (sbn == null || sbn.getNotification() == null) {
                    continue;
                }
                String key = sbn.getKey();
                if (key != null) {
                    alertedKeys.add(key);
                }
                if (isEssential(sbn)) {
                    essentialKeys.add(key);
                }
                Notification notification = sbn.getNotification();
                Bundle extras = notification.extras == null ? Bundle.EMPTY : notification.extras;
                MediaSession.Token token = mediaToken(extras);
                if (token != null) {
                    String pkg = sbn.getPackageName();
                    handleMedia(sbn, token, pkg,
                            text(extras.getCharSequence(Notification.EXTRA_TITLE)), body(extras));
                }
            }
        }
        if (!essentialKeys.isEmpty() && Prefs.allowed(this)
                && OutputGate.allowsBehavior(this, Prefs.B_ESSENTIAL)
                && Prefs.anyZoneHas(this, Prefs.B_ESSENTIAL)) {
            GlyphEngine.get().showEssential(this);
        } else {
            GlyphEngine.get().clearStatic(Prefs.B_ESSENTIAL);
        }
        Log.d(TAG, "listener connected essential=" + essentialKeys.size()
                + " media=" + (mediaController != null)
                + " access=" + Prefs.allowed(this));
    }

    @Override
    public void onListenerDisconnected() {
        releaseMedia(true);
        essentialKeys.clear();
        alertedKeys.clear();
        GlyphEngine.get().clearStatic(Prefs.B_ESSENTIAL);
        super.onListenerDisconnected();
    }

    private boolean handleMedia(StatusBarNotification sbn, MediaSession.Token token,
                                String pkg, String title, String text) {
        if (!Prefs.allowed(this) || !OutputGate.allows(this, OutputGate.MUSIC)
                || !Prefs.anyZoneHas(this, Prefs.B_MEDIA) || token == null) {
            return false;
        }
        if (Rules.hasEnabled(this, Rules.MEDIA)
                && !Rules.matches(this, Rules.MEDIA, pkg, title, text)) {
            return false;
        }
        String key = sbn.getKey();
        if (mediaController == null || !token.equals(mediaToken)
                || key == null || !key.equals(mediaControllerKey)) {
            connectMedia(key, token);
        }
        activeMediaKey = key;
        updateMediaProgress();
        return lastMediaPercent >= 0;
    }

    private void connectMedia(String key, MediaSession.Token token) {
        releaseMedia(false);
        try {
            mediaController = new MediaController(this, token);
            mediaToken = token;
            mediaControllerKey = key;
            lastMediaPercent = -1;
            loggedMissingMediaDuration = false;
            mediaController.registerCallback(mediaCallback, handler);
            handler.post(mediaTick);
            Log.d(TAG, "media controller connected key=" + key);
        } catch (Throwable error) {
            Log.w(TAG, "could not attach to media session", error);
            releaseMedia(false);
        }
    }

    private void updateMediaProgress() {
        MediaController controller = mediaController;
        if (controller == null) {
            return;
        }
        PlaybackState state;
        MediaMetadata metadata;
        try {
            state = controller.getPlaybackState();
            metadata = controller.getMetadata();
        } catch (Throwable error) {
            Log.w(TAG, "media state unavailable", error);
            releaseMedia(true);
            return;
        }
        long duration = metadata == null ? 0L
                : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration <= 0L && state != null && state.getExtras() != null) {
            duration = state.getExtras().getLong(MediaMetadata.METADATA_KEY_DURATION, 0L);
        }
        if (state == null || duration <= 0L) {
            if (!loggedMissingMediaDuration) {
                loggedMissingMediaDuration = true;
                Log.d(TAG, "media session has no duration yet duration=" + duration);
            }
            return;
        }
        int playback = state.getState();
        if (playback == PlaybackState.STATE_STOPPED
                || playback == PlaybackState.STATE_ERROR
                || playback == PlaybackState.STATE_NONE) {
            releaseMedia(true);
            return;
        }
        boolean playing = playback == PlaybackState.STATE_PLAYING
                || playback == PlaybackState.STATE_FAST_FORWARDING
                || playback == PlaybackState.STATE_REWINDING;
        long position = MediaPosition.current(state.getPosition(),
                state.getLastPositionUpdateTime(), SystemClock.elapsedRealtime(),
                state.getPlaybackSpeed(), playing);
        int percent = MediaPosition.percent(position, duration);
        if (percent < 0) {
            return;
        }
        if (percent != lastMediaPercent) {
            lastMediaPercent = percent;
            GlyphEngine.get().setProgress(this, Prefs.B_MEDIA, percent);
            Log.d(TAG, "media progress " + percent + "% pos=" + position
                    + " duration=" + duration + " state=" + playback);
        }
    }

    private void releaseMedia(boolean clearProgress) {
        handler.removeCallbacks(mediaTick);
        if (mediaController != null) {
            try {
                mediaController.unregisterCallback(mediaCallback);
            } catch (Throwable ignored) {
                // Already detached.
            }
        }
        mediaController = null;
        mediaToken = null;
        mediaControllerKey = null;
        lastMediaPercent = -1;
        loggedMissingMediaDuration = false;
        if (clearProgress) {
            activeMediaKey = null;
            GlyphEngine.get().clearStatic(Prefs.B_MEDIA);
        }
    }

    private boolean handleProgress(StatusBarNotification sbn, Notification notification,
                                   Bundle extras, String pkg, String title, String text,
                                   boolean media) {
        if (!Prefs.allowed(this)) {
            return false;
        }
        String key = sbn.getKey();
        if (!extras.containsKey(Notification.EXTRA_PROGRESS)) {
            if (media) {
                if (key != null && key.equals(activeMediaKey)) {
                    releaseMedia(true);
                }
            } else {
                clearNotificationProgress(sbn);
            }
            return false;
        }
        boolean indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        int progress = extras.getInt(Notification.EXTRA_PROGRESS, -1);
        int max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100);
        if (indeterminate || progress < 0 || max <= 0) {
            return false;
        }
        int percent = Math.round(progress * 100f / max);

        String scope = media ? Rules.MEDIA : Rules.NOTIFICATION;
        int behavior = media ? Prefs.B_MEDIA : Prefs.B_NOTIFICATION_PROGRESS;
        if (!Prefs.anyZoneHas(this, behavior)
                || !OutputGate.allowsBehavior(this, behavior)) {
            return false;
        }
        if (Rules.hasEnabled(this, scope)
                && !Rules.matches(this, scope, pkg, title, text)) {
            return false;
        }

        if (media) {
            activeMediaKey = key;
            lastMediaPercent = percent;
        } else {
            activeNotificationKey = key;
            lastNotificationPercent = percent;
        }
        GlyphEngine.get().setProgress(this, behavior, percent);
        Log.d(TAG, "progress " + pkg + " " + percent + "% media=" + media);
        return true;
    }

    private void clearNotificationProgress(StatusBarNotification sbn) {
        String key = sbn == null ? null : sbn.getKey();
        if (key != null && key.equals(activeNotificationKey)) {
            activeNotificationKey = null;
            lastNotificationPercent = -1;
            GlyphEngine.get().clearStatic(Prefs.B_NOTIFICATION_PROGRESS);
        }
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
        if (!Prefs.allowed(this)
                || !OutputGate.allowsBehavior(this, Prefs.B_ESSENTIAL)
                || !Prefs.anyZoneHas(this, Prefs.B_ESSENTIAL)) {
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

    private static MediaSession.Token mediaToken(Bundle extras) {
        if (extras == null) {
            return null;
        }
        try {
            Object value = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
            return value instanceof MediaSession.Token ? (MediaSession.Token) value : null;
        } catch (RuntimeException error) {
            return null;
        }
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
