package dev.brl.glyph;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Only modes that need a live process live here: ringing loops, volume
 * observation and the full-time battery display. Everything else is
 * event-driven and costs nothing while idle.
 */
public final class GlyphService extends Service {

    private static final String TAG = "GlyphService";
    private static final String CHANNEL = "glyph_effects";
    private static final int NOTIFICATION_ID = 41;

    public static final String ACTION_CALL_START = "dev.brl.glyph.CALL_START";
    public static final String ACTION_CALL_STOP = "dev.brl.glyph.CALL_STOP";
    public static final String ACTION_CHARGING = "dev.brl.glyph.CHARGING";
    public static final String ACTION_CHARGING_FULL_START = "dev.brl.glyph.CHARGING_FULL_START";
    public static final String ACTION_CHARGING_FULL_STOP = "dev.brl.glyph.CHARGING_FULL_STOP";
    public static final String ACTION_REFRESH = "dev.brl.glyph.REFRESH";
    public static final String ACTION_AUDIO_PULSE = "dev.brl.glyph.AUDIO_PULSE";
    public static final String ACTION_AUDIO_TEST = "dev.brl.glyph.AUDIO_TEST";
    public static final String ACTION_STOP = "dev.brl.glyph.STOP";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private GlyphEngine engine;
    private AudioManager audio;
    private AudioPulse audioPulse;
    private Ringtone testTone;
    private PowerManager.WakeLock audioWakeLock;
    private long audioGeneration;
    private long soundUntil;
    private boolean callActive;
    private boolean volumeRegistered;
    private boolean volumeBroadcastRegistered;
    private boolean chargingFull;
    private boolean batteryRegistered;
    private boolean powerRegistered;
    private ContentObserver volumeObserver;
    private int lastVolumePercent = -1;

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int stream = AudioManager.STREAM_MUSIC;
            if (intent != null) {
                stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE",
                        AudioManager.STREAM_MUSIC);
            }
            showVolume(stream, true);
        }
    };

    public static void start(Context context, String action) {
        Intent intent = new Intent(context, GlyphService.class).setAction(action);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start service", e);
        }
    }

    public static void refresh(Context context) {
        start(context, ACTION_REFRESH);
    }

    public static void stop(Context context) {
        start(context, ACTION_STOP);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = GlyphEngine.get();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        // Evaluate the current charger state immediately. This is what makes
        // "toggle battery while already charging" show the bar without a
        // plug cycle, and it re-registers the power receiver after a restart.
        refreshModes();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_REFRESH : intent.getAction();
        if (ACTION_CALL_START.equals(action)) {
            callActive = true;
            if (Prefs.audioPulseCalls(this)) {
                startAudioPulse(120000L, 6000L, GlyphEffects.STYLE_CALL);
            }
            handler.removeCallbacks(callTimeout);
            handler.postDelayed(callTimeout, 120000L);
        } else if (ACTION_CALL_STOP.equals(action)) {
            stopCall();
        } else if (ACTION_AUDIO_PULSE.equals(action)) {
            if (!callActive && Prefs.audioPulseNotifications(this)) {
                startAudioPulse(8000L, 3000L, GlyphEffects.STYLE_PULSE);
            }
        } else if (ACTION_AUDIO_TEST.equals(action)) {
            startAudioPulse(10000L, 3000L, GlyphEffects.STYLE_CALL);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    playTestTone();
                }
            }, 350L);
        } else if (ACTION_CHARGING.equals(action)) {
            if (Prefs.allowed(this) && Prefs.anyZoneHas(this, Prefs.B_CHARGE_BRIEF)) {
                engine.playChargingBrief(this);
                Log.d(TAG, "charging brief started");
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    maybeStop();
                }
            }, 3400L);
        } else if (ACTION_CHARGING_FULL_START.equals(action)) {
            if (Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL)) {
                chargingFull = true;
                registerBattery();
            }
        } else if (ACTION_CHARGING_FULL_STOP.equals(action)) {
            stopChargingFull();
        } else if (ACTION_REFRESH.equals(action)) {
            refreshModes();
        } else if (ACTION_STOP.equals(action)) {
            soundUntil = 0;
            stopAudioPulse();
            engine.clear();
            stopSelf();
            return START_NOT_STICKY;
        }
        maybeStop();
        return START_NOT_STICKY;
    }

    private void refreshModes() {
        if (!Prefs.allowed(this)) {
            stopAudioPulse();
        }
        if (Prefs.anyZoneHas(this, Prefs.B_VOLUME) && Prefs.allowed(this)) {
            // The system broadcast carries the new stream value, so the frame
            // is written within a few milliseconds. The removed helper polled
            // `settings get` and could lag the key press by ~240 ms.
            registerVolume();
        } else {
            unregisterVolume();
        }
        boolean batteryEnabled = Prefs.allowed(this)
                && (Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL)
                || Prefs.anyZoneHas(this, Prefs.B_CHARGE_BRIEF));
        if (batteryEnabled) {
            registerPower();
        } else {
            unregisterPower();
        }
        if (Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL) && isCharging() && Prefs.allowed(this)) {
            chargingFull = true;
            registerBattery();
        } else {
            stopChargingFull();
        }
    }

    private final Runnable callTimeout = new Runnable() {
        @Override
        public void run() {
            stopCall();
        }
    };

    private void stopCall() {
        handler.removeCallbacks(callTimeout);
        callActive = false;
        soundUntil = 0;
        stopAudioPulse();
        engine.stopCall();
        maybeStop();
    }

    private boolean startAudioPulse(long maxMs, long silenceMs, final int style) {
        if (!Prefs.allowed(this)) {
            return false;
        }
        stopAudioPulse();
        // A pulse that is still shutting down must not keep the old override
        // (or clear the new one). Force the latch off before the fallback.
        GlyphEngine.get().endAudioOverride(null);
        // The fallback starts immediately and needs no RECORD_AUDIO. It covers
        // silent and vibrate notifications/calls; live spectrum frames replace
        // it once sound is actually detected.
        GlyphEngine.get().playSound(style, maxMs, Prefs.soundFrame(this));
        soundUntil = System.currentTimeMillis() + maxMs + 1000L;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                maybeStop();
            }
        }, maxMs + 1200L);
        if (!AudioPulse.hasPermission(this)) {
            return true;
        }
        final long generation = ++audioGeneration;
        acquireAudioWakeLock(maxMs + 2000L);
        int level = style == GlyphEffects.STYLE_CALL
                ? GlyphEngine.LEVEL_CALL : GlyphEngine.LEVEL_NOTIFICATION;
        AudioPulse pulse = new AudioPulse(this, maxMs, silenceMs, level, new Runnable() {
            @Override
            public void run() {
                if (generation != audioGeneration) {
                    return;
                }
                audioPulse = null;
                releaseAudioWakeLock();
                if (callActive) {
                    GlyphEngine.get().playSound(GlyphEffects.STYLE_CALL, 120000L,
                            Prefs.soundFrame(GlyphService.this));
                    soundUntil = System.currentTimeMillis() + 121000L;
                }
                // Leave soundUntil alone otherwise: the fallback notification
                // animation may still be running even though live capture has
                // already timed out. Cutting the service here truncated it.
                maybeStop();
            }
        });
        audioPulse = pulse;
        if (!pulse.start()) {
            audioPulse = null;
            releaseAudioWakeLock();
            // The fallback animation stays active without audio capture.
        }
        return true;
    }

    private void stopAudioPulse() {
        audioGeneration++;
        soundUntil = 0;
        AudioPulse pulse = audioPulse;
        audioPulse = null;
        if (pulse != null) {
            pulse.stop();
        }
        releaseAudioWakeLock();
    }

    private void acquireAudioWakeLock(long timeoutMs) {
        releaseAudioWakeLock();
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager == null) {
            return;
        }
        audioWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glyph:audio");
        audioWakeLock.setReferenceCounted(false);
        audioWakeLock.acquire(timeoutMs);
    }

    private void releaseAudioWakeLock() {
        if (audioWakeLock != null) {
            if (audioWakeLock.isHeld()) {
                audioWakeLock.release();
            }
            audioWakeLock = null;
        }
    }

    private void playTestTone() {
        stopTestTone();
        try {
            android.net.Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            testTone = RingtoneManager.getRingtone(this, uri);
            if (testTone != null) {
                Log.i(TAG, "test ringtone playing uri=" + uri);
                testTone.play();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        stopTestTone();
                    }
                }, 5000L);
            } else {
                Log.w(TAG, "test ringtone is null for uri=" + uri);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "test tone unavailable", error);
        }
    }

    private void stopTestTone() {
        if (testTone != null) {
            try {
                testTone.stop();
            } catch (RuntimeException ignored) {
                // Already stopped.
            }
            testTone = null;
        }
    }

    private void registerVolume() {
        if (volumeRegistered || audio == null) {
            return;
        }
        boolean registered = false;
        try {
            volumeObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    showVolume(AudioManager.STREAM_MUSIC, false);
                }
            };
            // Watch only the music-volume key. The old broad System URI fired
            // on unrelated setting changes and could repaint over another
            // behavior.
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("volume_music"), false, volumeObserver);
            registered = true;
        } catch (RuntimeException error) {
            Log.w(TAG, "volume observer unavailable", error);
        }
        try {
            IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(volumeReceiver, filter);
            }
            volumeBroadcastRegistered = true;
            registered = true;
        } catch (RuntimeException error) {
            Log.w(TAG, "volume broadcast unavailable", error);
        }
        volumeRegistered = registered;
        if (registered) {
            lastVolumePercent = streamPercent(AudioManager.STREAM_MUSIC);
        }
        Log.d(TAG, "volume listener registered=" + registered
                + " broadcast=" + volumeBroadcastRegistered
                + " observer=" + (volumeObserver != null));
    }

    private void unregisterVolume() {
        if (volumeBroadcastRegistered) {
            try {
                unregisterReceiver(volumeReceiver);
            } catch (RuntimeException ignored) {
                // Already unregistered.
            }
        }
        volumeBroadcastRegistered = false;
        if (volumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(volumeObserver);
            } catch (RuntimeException ignored) {
                // Already unregistered.
            }
        }
        volumeObserver = null;
        volumeRegistered = false;
        lastVolumePercent = -1;
    }

    /** Shows the live volume as a bottom-up bar on the assigned strip. */
    private void showVolume(int streamType) {
        showVolume(streamType, false);
    }

    private void showVolume(int streamType, boolean force) {
        if (audio == null || !Prefs.allowed(this)
                || !Prefs.anyZoneHas(this, Prefs.B_VOLUME)) {
            return;
        }
        int stream = streamType;
        if (stream < 0 || stream > AudioManager.STREAM_NOTIFICATION) {
            stream = AudioManager.STREAM_MUSIC;
        }
        int percent = streamPercent(stream);
        // A key-press broadcast repaints even at the same percent. After the
        // bar timed out, the old equality check swallowed the next press and
        // it looked like the first click did nothing.
        if (!force && percent == lastVolumePercent) {
            return;
        }
        lastVolumePercent = percent;
        Log.d(TAG, "volume stream=" + stream + " -> " + percent + "%");
        GlyphEngine.get().playVolume(this, percent);
    }

    private int streamPercent(int stream) {
        if (audio == null) {
            return 0;
        }
        int max = Math.max(1, audio.getStreamMaxVolume(stream));
        int current = audio.getStreamVolume(stream);
        return Math.round(current * 100f / max);
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (scale <= 0) {
                scale = 100;
            }
            int percent = Math.round(level * 100f / scale);
            // The charger can be removed between the service start and this
            // broadcast. Re-adding the static after unplug is what left the
            // battery bar lit until the next reboot.
            if (chargingFull && isCharging()
                    && Prefs.allowed(GlyphService.this)
                    && Prefs.anyZoneHas(GlyphService.this, Prefs.B_CHARGE_FULL)) {
                GlyphEngine.get().setProgress(GlyphService.this, Prefs.B_CHARGE_FULL, percent);
            } else {
                GlyphEngine.get().clearStatic(Prefs.B_CHARGE_FULL);
            }
        }
    };

    private void registerBattery() {
        if (batteryRegistered) {
            return;
        }
        batteryRegistered = true;
        Intent sticky = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            batteryReceiver.onReceive(this, sticky);
        }
    }

    private void unregisterBattery() {
        if (batteryRegistered) {
            unregisterReceiver(batteryReceiver);
        }
        batteryRegistered = false;
    }

    private void stopChargingFull() {
        chargingFull = false;
        unregisterBattery();
        GlyphEngine.get().clearStatic(Prefs.B_CHARGE_FULL);
    }

    /**
     * Power changes need a listener for the whole time the service is alive.
     * The manifest receiver only wakes the process; when the service stopped
     * itself in the unplugged state, the later plug-in had nowhere to go.
     */
    private final BroadcastReceiver powerStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                Log.d(TAG, "power connected");
                if (!Prefs.allowed(GlyphService.this)) {
                    return;
                }
                if (Prefs.anyZoneHas(GlyphService.this, Prefs.B_CHARGE_BRIEF)) {
                    engine.playChargingBrief(GlyphService.this);
                    Log.d(TAG, "charging brief started");
                }
                if (Prefs.anyZoneHas(GlyphService.this, Prefs.B_CHARGE_FULL)) {
                    chargingFull = true;
                    registerBattery();
                }
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                Log.d(TAG, "power disconnected");
                engine.stopCharging();
                stopChargingFull();
            }
            maybeStop();
        }
    };

    private void registerPower() {
        if (powerRegistered) {
            return;
        }
        powerRegistered = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(powerStateReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(powerStateReceiver, filter);
            }
        } catch (RuntimeException error) {
            powerRegistered = false;
            Log.w(TAG, "power receiver unavailable", error);
        }
    }

    private void unregisterPower() {
        if (powerRegistered) {
            try {
                unregisterReceiver(powerStateReceiver);
            } catch (RuntimeException ignored) {
                // Already unregistered.
            }
        }
        powerRegistered = false;
    }

    private boolean isCharging() {
        BatteryManager manager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        return manager != null && manager.isCharging();
    }

    private void maybeStop() {
        boolean audioActive = audioPulse != null && audioPulse.isRunning();
        boolean soundActive = soundUntil > System.currentTimeMillis();
        // Battery behaviors must keep the service (and its dynamic power
        // receiver) alive even while unplugged. Otherwise the plug-in broadcast
        // had no listener and charging stayed dark until the app was opened.
        boolean batteryEnabled = Prefs.allowed(this)
                && (Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL)
                || Prefs.anyZoneHas(this, Prefs.B_CHARGE_BRIEF));
        if (!callActive && !volumeRegistered && !batteryEnabled
                && !audioActive && !soundActive) {
            stopSelf();
        }
    }

    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Glyph effects", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Runs while a glyph behavior is active");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, GlyphService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Glyph effects")
                .setContentText("Call, sound or charging display is active")
                .setContentIntent(openIntent)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent).build())
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    @Override
    public void onDestroy() {
        soundUntil = 0;
        stopAudioPulse();
        stopTestTone();
        handler.removeCallbacksAndMessages(null);
        unregisterVolume();
        unregisterBattery();
        unregisterPower();
        if (callActive) {
            engine.stopCall();
        }
        engine.clearStatic(Prefs.B_CHARGE_FULL);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
