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
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.HashMap;

/**
 * Only modes that need a live process live here: ringing loops, volume
 * observation and the full-time battery display. Everything else is
 * event-driven and costs nothing while idle.
 */
public final class GlyphService extends Service {

    private static final String TAG = "GlyphService";
    private static final int LEGACY_NOTIFICATION_ID = 41;

    public static final String ACTION_CALL_START = "dev.brl.glyph.CALL_START";
    public static final String ACTION_CALL_STOP = "dev.brl.glyph.CALL_STOP";
    public static final String ACTION_CHARGING = "dev.brl.glyph.CHARGING";
    public static final String ACTION_CHARGING_FULL_START = "dev.brl.glyph.CHARGING_FULL_START";
    public static final String ACTION_CHARGING_FULL_STOP = "dev.brl.glyph.CHARGING_FULL_STOP";
    public static final String ACTION_REFRESH = "dev.brl.glyph.REFRESH";
    public static final String ACTION_NOTIFICATION_ALERT = "dev.brl.glyph.NOTIFICATION_ALERT";
    public static final String ACTION_AUDIO_TEST = "dev.brl.glyph.AUDIO_TEST";
    public static final String ACTION_NOTIFICATION_TEST = "dev.brl.glyph.NOTIFICATION_TEST";
    public static final String ACTION_AUDIO_TEST_MUTE = "dev.brl.glyph.AUDIO_TEST_MUTE";
    public static final String ACTION_NOTIFICATION_TEST_MUTE = "dev.brl.glyph.NOTIFICATION_TEST_MUTE";
    public static final String ACTION_VISUALIZER_ON = "dev.brl.glyph.VISUALIZER_ON";
    public static final String ACTION_VISUALIZER_OFF = "dev.brl.glyph.VISUALIZER_OFF";
    public static final String ACTION_STOP = "dev.brl.glyph.STOP";

    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private GlyphEngine engine;
    private AudioManager audio;
    private Ringtone testTone;
    private MediaPlayer testPlayer;
    private final HashMap<String, SoundCompiler.Result> compiledSounds = new HashMap<>();
    private long soundUntil;
    private boolean callActive;
    private boolean volumeRegistered;
    private boolean volumeBroadcastRegistered;
    private boolean chargingFull;
    private boolean batteryRegistered;
    private boolean powerRegistered;
    private AudioPulse visualizer;
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
            // A normal service, not a foreground service: the permanent
            // notification listener owns the process, so there is no "app is
            // running in the background" entry in Active apps.
            context.startService(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start service, asking root", e);
            Root.get().startService(action);
        }
    }

    public static void refresh(Context context) {
        start(context, ACTION_REFRESH);
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * Gate changes should not wake the service unless it is already running
     * or the change affects volume/battery listeners. Starting the service for
     * a music or ringtone toggle added a startup to every tap, which was the
     * main source of the lag.
     */
    public static void refreshIfNeeded(Context context) {
        refreshIfNeeded(context, -1);
    }

    public static void refreshIfNeeded(Context context, int changedCategory) {
        if (running) {
            // Music and ringtone gates only affect the frame engine. They do
            // not change volume/battery receivers, so do not round-trip through
            // the service for those taps.
            if (changedCategory == OutputGate.VOLUME
                    || changedCategory == OutputGate.BATTERY
                    || changedCategory == OutputGate.GLYPHS
                    || changedCategory < 0) {
                refresh(context);
            }
            return;
        }
        boolean volume = Prefs.anyZoneHas(context, Prefs.B_VOLUME)
                && Prefs.allowed(context)
                && OutputGate.allows(context, OutputGate.VOLUME);
        boolean battery = Prefs.allowed(context)
                && OutputGate.allows(context, OutputGate.BATTERY)
                && (Prefs.anyZoneHas(context, Prefs.B_CHARGE_FULL)
                || Prefs.anyZoneHas(context, Prefs.B_CHARGE_BRIEF));
        if (volume || battery) {
            refresh(context);
        }
    }

    public static void stop(Context context) {
        start(context, ACTION_STOP);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        engine = GlyphEngine.get();
        engine.init(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Prefs.glyphLights(this)) {
            engine.setFlashlight(true);
        }
        // Remove the old foreground-service notification left by 2.0.
        NotificationManager notifications =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notifications != null) {
            notifications.cancel(LEGACY_NOTIFICATION_ID);
        }
        Root.get().keepAlive();
        // Do not preload ringtone/notification media here. The notification
        // listener already does that on connect; doing it on every service
        // start added MediaCodec work to volume and gate taps.
        // A sticky restart (or boot) re-arms the manual visualizer.
        if (Prefs.visualizer(this)) {
            startVisualizer();
        }
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
            if (Prefs.audioPulseCalls(this)
                    && OutputGate.allows(this, OutputGate.RINGTONE)) {
                startCallGlyphs();
            }
            handler.removeCallbacks(callTimeout);
            handler.postDelayed(callTimeout, 120000L);
        } else if (ACTION_CALL_STOP.equals(action)) {
            stopCall();
        } else if (ACTION_NOTIFICATION_ALERT.equals(action)) {
            if (!callActive && Prefs.audioPulseNotifications(this)
                    && OutputGate.allows(this, OutputGate.GLYPHS)) {
                // The system already plays the notification sound. Use the
                // compiled note track silently instead of the old fixed pulse.
                startSoundTest(RingtoneManager.TYPE_NOTIFICATION, true);
            }
        } else if (ACTION_AUDIO_TEST.equals(action)) {
            startSoundTest(RingtoneManager.TYPE_RINGTONE, false);
        } else if (ACTION_NOTIFICATION_TEST.equals(action)) {
            startSoundTest(RingtoneManager.TYPE_NOTIFICATION, false);
        } else if (ACTION_AUDIO_TEST_MUTE.equals(action)) {
            startSoundTest(RingtoneManager.TYPE_RINGTONE, true);
        } else if (ACTION_NOTIFICATION_TEST_MUTE.equals(action)) {
            startSoundTest(RingtoneManager.TYPE_NOTIFICATION, true);
        } else if (ACTION_CHARGING.equals(action)) {
            if (Prefs.allowed(this) && OutputGate.allows(this, OutputGate.BATTERY)
                    && Prefs.anyZoneHas(this, Prefs.B_CHARGE_BRIEF)) {
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
            if (OutputGate.allows(this, OutputGate.BATTERY)
                    && Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL)) {
                chargingFull = true;
                registerBattery();
            }
        } else if (ACTION_CHARGING_FULL_STOP.equals(action)) {
            stopChargingFull();
        } else if (ACTION_VISUALIZER_ON.equals(action)) {
            startVisualizer();
        } else if (ACTION_VISUALIZER_OFF.equals(action)) {
            stopVisualizer();
        } else if (ACTION_REFRESH.equals(action)) {
            refreshModes();
        } else if (ACTION_STOP.equals(action)) {
            Prefs.setVisualizer(this, false);
            stopVisualizer();
            releaseTestPlayer();
            soundUntil = 0;
            engine.clear();
            stopSelf();
            return START_NOT_STICKY;
        }
        maybeStop();
        return START_STICKY;
    }

    private void refreshModes() {
        if (Prefs.anyZoneHas(this, Prefs.B_VOLUME) && Prefs.allowed(this)
                && OutputGate.allows(this, OutputGate.VOLUME)) {
            // The system broadcast carries the new stream value, so the frame
            // is written within a few milliseconds. The removed helper polled
            // `settings get` and could lag the key press by ~240 ms.
            registerVolume();
        } else {
            unregisterVolume();
        }
        boolean batteryEnabled = Prefs.allowed(this)
                && OutputGate.allows(this, OutputGate.BATTERY)
                && (Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL)
                || Prefs.anyZoneHas(this, Prefs.B_CHARGE_BRIEF));
        if (batteryEnabled) {
            registerPower();
        } else {
            unregisterPower();
        }
        if (Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL) && isCharging()
                && Prefs.allowed(this) && OutputGate.allows(this, OutputGate.BATTERY)) {
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
        releaseTestPlayer();
        engine.stopCall();
        maybeStop();
    }

    private boolean startAlert(long durationMs, int style) {
        int level = style == GlyphEffects.STYLE_CALL
                ? GlyphEngine.LEVEL_CALL : GlyphEngine.LEVEL_NOTIFICATION;
        if (!Prefs.allowed(this) || !OutputGate.allowsAlert(this, level)) {
            return false;
        }
        releaseTestPlayer();
        // Fixed scripts, not live FFT: calls are a repeating wave and
        // notifications are a short pulse. The engine enforces call >
        // notification > every other display.
        GlyphEngine.get().playSound(this, style, durationMs, Prefs.soundFrame(this));
        soundUntil = System.currentTimeMillis() + durationMs + 1000L;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                maybeStop();
            }
        }, durationMs + 1200L);
        Log.d(TAG, "alert style=" + style + " duration=" + durationMs);
        return true;
    }

    /**
     * A real ringing call uses exactly the same compiled note track as the
     * muted ringtone test. The ROM plays the ringtone itself, so Glyph only
     * loops the silent frames until the call is answered or rejected.
     */
    private void startCallGlyphs() {
        releaseTestPlayer();
        // Keep the service alive while MediaCodec decodes a long ringtone.
        soundUntil = System.currentTimeMillis() + 20000L;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri uri = RingtoneManager.getActualDefaultRingtoneUri(
                        GlyphService.this, RingtoneManager.TYPE_RINGTONE);
                if (uri == null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            fallbackCallAlert();
                        }
                    });
                    return;
                }
                try {
                    int mode = Prefs.autogenMode(GlyphService.this);
                    SoundCompiler.Result result = SoundCompiler.cached(
                            GlyphService.this, uri, mode);
                    if (result == null) {
                        result = SoundCompiler.compile(GlyphService.this, uri, mode);
                        SoundCompiler.store(GlyphService.this, uri, mode, result);
                    }
                    final SoundCompiler.Result compiled = result;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!callActive) {
                                return;
                            }
                            startCallPlayback(compiled);
                        }
                    });
                } catch (final Throwable error) {
                    Log.w(TAG, "call ringtone compile failed, using fixed pattern", error);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            fallbackCallAlert();
                        }
                    });
                }
            }
        }, "glyph-call-compile").start();
    }

    private void startCallPlayback(SoundCompiler.Result result) {
        /*
         * No MediaPlayer here: the system is already ringing. Looping the
         * precompiled frames keeps the note map in sync with the ringtone for
         * the whole call instead of showing one short pulse and going dark.
         */
        GlyphEngine.get().playFrames(this, result.frames, SoundCompiler.FPS,
                GlyphEngine.LEVEL_CALL, true, null);
        Log.d(TAG, "call compiled ringtone mode="
                + Prefs.AUTOGEN_NAMES[Prefs.autogenMode(this)]
                + " frames=" + result.frames.length
                + " durationMs=" + result.durationMs);
    }

    /** Only used if the ringtone cannot be decoded at all. */
    private void fallbackCallAlert() {
        if (callActive) {
            startAlert(120000L, GlyphEffects.STYLE_CALL);
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

    private void playNotificationTone() {
        stopTestTone();
        try {
            android.net.Uri uri = RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_NOTIFICATION);
            testTone = RingtoneManager.getRingtone(this, uri);
            if (testTone != null) {
                Log.i(TAG, "test notification playing uri=" + uri);
                testTone.play();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        stopTestTone();
                    }
                }, 2500L);
            } else {
                Log.w(TAG, "test notification is null for uri=" + uri);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "notification tone unavailable", error);
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

    private void releaseTestPlayer() {
        if (testPlayer != null) {
            try {
                if (testPlayer.isPlaying()) {
                    testPlayer.stop();
                }
            } catch (Throwable ignored) {
                // Already stopped.
            }
            try {
                testPlayer.release();
            } catch (Throwable ignored) {
                // Already released.
            }
            testPlayer = null;
        }
    }

    /**
     * Decodes the phone's actual default sound into 36x60 frames, then plays
     * those frames. Muted tests use exactly the same frames with no player at
     * all, so the animation is not a separate approximation.
     */
    private void startSoundTest(final int type, final boolean mute) {
        int gate = type == RingtoneManager.TYPE_RINGTONE
                ? OutputGate.RINGTONE : OutputGate.GLYPHS;
        if (!OutputGate.allows(this, gate)) {
            return;
        }
        releaseTestPlayer();
        stopTestTone();
        // Keep the service alive while MediaCodec decodes and MediaPlayer
        // prepares; this can take a second on a long ringtone.
        soundUntil = System.currentTimeMillis() + 20000L;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri uri = RingtoneManager.getActualDefaultRingtoneUri(GlyphService.this, type);
                if (uri == null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            startAlert(type == RingtoneManager.TYPE_RINGTONE ? 6000L : 2600L,
                                    type == RingtoneManager.TYPE_RINGTONE
                                            ? GlyphEffects.STYLE_CALL
                                            : GlyphEffects.STYLE_PULSE);
                        }
                    });
                    return;
                }
                try {
                    int mode = Prefs.autogenMode(GlyphService.this);
                    SoundCompiler.Result result = SoundCompiler.cached(
                            GlyphService.this, uri, mode);
                    if (result == null) {
                        result = SoundCompiler.compile(GlyphService.this, uri, mode);
                        SoundCompiler.store(GlyphService.this, uri, mode, result);
                    }
                    final SoundCompiler.Result compiled = result;
                    MediaPlayer player = null;
                    if (!mute) {
                        player = new MediaPlayer();
                        player.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(type == RingtoneManager.TYPE_RINGTONE
                                        ? AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                                        : AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build());
                        player.setDataSource(GlyphService.this, uri);
                        player.prepare();
                    }
                    final MediaPlayer prepared = player;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            startCompiledPlayback(compiled, prepared,
                                    type == RingtoneManager.TYPE_RINGTONE
                                            ? GlyphEffects.STYLE_CALL
                                            : GlyphEffects.STYLE_PULSE, mute);
                        }
                    });
                } catch (final Throwable error) {
                    Log.w(TAG, "sound compile failed, using fixed pattern", error);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            startAlert(type == RingtoneManager.TYPE_RINGTONE ? 6000L : 2600L,
                                    type == RingtoneManager.TYPE_RINGTONE
                                            ? GlyphEffects.STYLE_CALL
                                            : GlyphEffects.STYLE_PULSE);
                        }
                    });
                }
            }
        }, "glyph-sound-compile").start();
    }

    private void startCompiledPlayback(SoundCompiler.Result result, MediaPlayer player,
                                       int style, boolean mute) {
        releaseTestPlayer();
        if (player != null) {
            testPlayer = player;
            try {
                player.start();
            } catch (Throwable error) {
                Log.w(TAG, "sound player failed to start", error);
                releaseTestPlayer();
            }
        }
        int level = style == GlyphEffects.STYLE_CALL ? GlyphEngine.LEVEL_CALL
                : GlyphEngine.LEVEL_NOTIFICATION;
        GlyphEngine.get().playFrames(this, result.frames, SoundCompiler.FPS, level,
                new Runnable() {
                    @Override
                    public void run() {
                        releaseTestPlayer();
                    }
                });
        soundUntil = System.currentTimeMillis() + result.durationMs + 1200L;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                maybeStop();
            }
        }, result.durationMs + 1400L);
        Log.d(TAG, "compiled alert mode=" + Prefs.AUTOGEN_NAMES[Prefs.autogenMode(this)]
                + " frames=" + result.frames.length
                + " durationMs=" + result.durationMs + " mute=" + mute);
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
                || !OutputGate.allows(this, OutputGate.VOLUME)
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
                    && OutputGate.allows(GlyphService.this, OutputGate.BATTERY)
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
                if (!Prefs.allowed(GlyphService.this)
                        || !OutputGate.allows(GlyphService.this, OutputGate.BATTERY)) {
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

    private void startVisualizer() {
        if (visualizer != null && visualizer.isRunning()) {
            return;
        }
        if (Prefs.allOff(this)) {
            Prefs.setVisualizer(this, false);
            return;
        }
        if (!AudioPulse.hasPermission(this)) {
            Log.w(TAG, "visualizer needs RECORD_AUDIO");
            Prefs.setVisualizer(this, false);
            return;
        }
        AudioPulse pulse = new AudioPulse(this, new Runnable() {
            @Override
            public void run() {
                if (visualizer != null && !visualizer.isRunning()) {
                    visualizer = null;
                    maybeStop();
                }
            }
        });
        visualizer = pulse;
        if (!pulse.start()) {
            visualizer = null;
            Prefs.setVisualizer(this, false);
            Log.w(TAG, "visualizer failed to start");
        } else {
            Log.i(TAG, "visualizer started");
        }
    }

    private void stopVisualizer() {
        AudioPulse pulse = visualizer;
        visualizer = null;
        if (pulse != null) {
            pulse.stop();
            Log.i(TAG, "visualizer stopped");
        }
    }

    private void maybeStop() {
        boolean soundActive = soundUntil > System.currentTimeMillis();
        boolean visualizerActive = visualizer != null && visualizer.isRunning();
        // Battery behaviors must keep the service (and its dynamic power
        // receiver) alive even while unplugged. Otherwise the plug-in broadcast
        // had no listener and charging stayed dark until the app was opened.
        boolean batteryEnabled = Prefs.allowed(this)
                && OutputGate.allows(this, OutputGate.BATTERY)
                && (Prefs.anyZoneHas(this, Prefs.B_CHARGE_FULL)
                || Prefs.anyZoneHas(this, Prefs.B_CHARGE_BRIEF));
        if (!callActive && !volumeRegistered && !batteryEnabled && !soundActive
                && !visualizerActive) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        soundUntil = 0;
        stopVisualizer();
        stopTestTone();
        releaseTestPlayer();
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
