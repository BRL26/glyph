package dev.brl.glyph;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;

/**
 * Turns the phone's current audio output into a temporary 36-LED overlay.
 *
 * It listens on the global audio session (the same path the ROM's own
 * visualizer uses). The overlay only claims the strips once real audio energy
 * is present, so a silent notification or a vibrate-only call still leaves the
 * normal glyph behavior alone.
 */
public final class AudioPulse {

    private static final String TAG = "GlyphAudio";

    private final Context context;
    /** Identity used so a stopped pulse cannot clear a newer override. */
    private final Object owner = new Object();
    private final int priorityLevel;
    private final long maxDurationMs;
    private final long silenceMs;
    private final Runnable onStopped;
    /** Arming the visualizer keeps capture open through silence and alerts. */
    private boolean persistent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NoteMap noteMap;
    private boolean[] allowedLeds;

    private HandlerThread thread;
    private Handler handler;
    private Visualizer visualizer;
    private volatile boolean running;
    private boolean stoppedNotified;
    private boolean overrideStarted;
    private boolean loggedFrame;
    private volatile boolean frameQueued;
    private long startedAt;
    private long lastEnergyAt;
    private int captureSize = 1024;
    private int sampleRate = 44100;

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public AudioPulse(Context context, long maxDurationMs, long silenceMs,
                      int priorityLevel, Runnable onStopped) {
        this.context = context.getApplicationContext();
        this.priorityLevel = priorityLevel;
        this.maxDurationMs = maxDurationMs;
        this.silenceMs = silenceMs;
        this.onStopped = onStopped;
        this.persistent = false;
    }

    /** Creates an armed visualizer: no duration cap and no silence shutdown. */
    public AudioPulse(Context context, Runnable onStopped) {
        this(context, Long.MAX_VALUE / 4L, Long.MAX_VALUE / 4L, 0, onStopped);
        this.persistent = true;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean start() {
        if (running) {
            return true;
        }
        if (!hasPermission(context)) {
            Log.w(TAG, "RECORD_AUDIO is not granted");
            return false;
        }
        running = true;
        stoppedNotified = false;
        overrideStarted = false;
        loggedFrame = false;
        frameQueued = false;
        startedAt = SystemClock.uptimeMillis();
        lastEnergyAt = startedAt;
        allowedLeds = Prefs.enabledLeds(context);
        noteMap = buildNoteMap(allowedLeds);
        if (persistent) {
            // Claim the strips immediately. Frames are rejected while a call
            // or notification owns the LEDs, then the visualizer resumes.
            overrideStarted = true;
            GlyphEngine.get().beginVisualizer(owner);
        }
        thread = new HandlerThread("glyph-audio");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                open();
            }
        });
        handler.postDelayed(watchdog, 250L);
        return true;
    }

    /** Safe from any thread. The stop callback is always posted to main. */
    public void stop() {
        Handler local = handler;
        if (local != null) {
            local.post(new Runnable() {
                @Override
                public void run() {
                    stopFromWorker();
                }
            });
        } else {
            stopFromWorker();
        }
    }

    private final Visualizer.OnDataCaptureListener captureListener =
            new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rate) {
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, final byte[] fft, int rate) {
                    final Handler local = handler;
                    if (local == null || !running || fft == null || frameQueued) {
                        return;
                    }
                    frameQueued = true;
                    local.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                process(fft);
                            } finally {
                                frameQueued = false;
                            }
                        }
                    });
                }
            };

    private void open() {
        if (!running) {
            return;
        }
        try {
            visualizer = new Visualizer(0);
            captureSize = Visualizer.getCaptureSizeRange()[1];
            visualizer.setCaptureSize(captureSize);
            int rate = visualizer.getSamplingRate();
            if (rate > 1000) {
                rate /= 1000; // Visualizer reports milliHertz.
            }
            if (rate > 0) {
                sampleRate = rate;
            }
            visualizer.setDataCaptureListener(captureListener,
                    Visualizer.getMaxCaptureRate(), false, true);
            visualizer.setEnabled(true);
            Log.i(TAG, "global session capture started rate=" + sampleRate
                    + " size=" + captureSize);
        } catch (Throwable error) {
            Log.e(TAG, "could not open global audio session", error);
            stopFromWorker();
        }
    }

    private void process(byte[] fft) {
        if (!running) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!persistent && now - startedAt > maxDurationMs) {
            stopFromWorker();
            return;
        }

        int brightness = Prefs.clamp(Prefs.brightness(context), 0, 100);
        double[] magnitude = NoteMap.fftMagnitudes(fft);
        int[] frame = noteMap == null
                ? new int[GlyphLayout.LEDS]
                : noteMap.process(magnitude, sampleRate, captureSize, brightness);
        float energy = noteMap == null ? 0f : noteMap.lastEnergy();

        if (energy > 0.02f) {
            lastEnergyAt = now;
        } else if (!persistent && now - lastEnergyAt > silenceMs) {
            stopFromWorker();
            return;
        }

        if (!overrideStarted && energy > 0.02f) {
            overrideStarted = true;
            GlyphEngine.get().beginAudioOverride(owner, priorityLevel);
            Log.i(TAG, "audio energy detected, taking over all strips");
        }
        if (!overrideStarted) {
            return;
        }

        if (!loggedFrame) {
            loggedFrame = true;
            int max = 0;
            int minLit = GlyphLayout.MAX_VALUE;
            long sumLit = 0L;
            int lit = 0;
            int[] zoneLit = new int[3];
            for (int hw = 0; hw < frame.length; hw++) {
                if (frame[hw] > max) {
                    max = frame[hw];
                }
                if (frame[hw] > 0) {
                    lit++;
                    sumLit += frame[hw];
                    if (frame[hw] < minLit) {
                        minLit = frame[hw];
                    }
                    for (int zone = 0; zone < 3; zone++) {
                        int start = zone == 0 ? 20 : zone == 1 ? 31 : 0;
                        int end = start + GlyphLayout.ZONE_LEDS[zone];
                        if (hw >= start && hw < end) {
                            zoneLit[zone]++;
                        }
                    }
                }
            }
            int average = lit == 0 ? 0 : (int) (sumLit / lit);
            Log.i(TAG, "first audio frame max=" + max + " min=" + minLit
                    + " avg=" + average + " lit=" + lit
                    + "/36 A=" + zoneLit[0] + "/11 B=" + zoneLit[1]
                    + "/5 C=" + zoneLit[2] + "/20 energy=" + energy);
        }
        if (persistent) {
            GlyphEngine.get().visualizerFrame(frame);
        } else {
            GlyphEngine.get().audioFrame(frame);
        }
    }

    /**
     * The visualizer and the compiled sounds share one pitch order: low notes
     * on C, then A, then B, each strip bottom-up. This is the same order
     * SoundCompiler uses for its Note map mode.
     */
    private NoteMap buildNoteMap(boolean[] allowed) {
        ArrayList<Integer> pitchLeds = new ArrayList<>();
        int[] zones = {2, 0, 1};
        for (int zone : zones) {
            boolean any = false;
            for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
                int hw = GlyphLayout.hwIndex(zone, pos);
                if (allowed == null || allowed.length != GlyphLayout.LEDS || allowed[hw]) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                continue;
            }
            for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
                int hw = GlyphLayout.hwIndex(zone, pos);
                if (allowed == null || allowed.length != GlyphLayout.LEDS || allowed[hw]) {
                    pitchLeds.add(hw);
                }
            }
        }
        int[] order = new int[pitchLeds.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = pitchLeds.get(i);
        }
        int[] zoneForLed = new int[GlyphLayout.LEDS];
        for (int led = 0; led < zoneForLed.length; led++) {
            zoneForLed[led] = -1;
        }
        for (int zone = 0; zone < 3; zone++) {
            for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
                zoneForLed[GlyphLayout.hwIndex(zone, pos)] = zone;
            }
        }
        int[] zoneLevel = new int[3];
        for (int zone = 0; zone < 3; zone++) {
            zoneLevel[zone] = Prefs.zoneLevel(context, zone);
        }
        return new NoteMap(order, zoneForLed, zoneLevel);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (!persistent
                    && (now - startedAt > maxDurationMs || now - lastEnergyAt > silenceMs)) {
                stopFromWorker();
                return;
            }
            Handler local = handler;
            if (local != null) {
                local.postDelayed(this, 250L);
            }
        }
    };

    private void stopFromWorker() {
        if (!running) {
            return;
        }
        running = false;
        frameQueued = false;
        Handler local = handler;
        if (local != null) {
            local.removeCallbacksAndMessages(null);
        }
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
            } catch (Throwable ignored) {
                // Already detached.
            }
            try {
                visualizer.release();
            } catch (Throwable ignored) {
                // Already released.
            }
            visualizer = null;
        }
        if (overrideStarted) {
            overrideStarted = false;
            if (persistent) {
                GlyphEngine.get().endVisualizer(owner);
            } else {
                GlyphEngine.get().endAudioOverride(owner);
            }
        }
        HandlerThread localThread = thread;
        thread = null;
        handler = null;
        if (localThread != null) {
            localThread.quitSafely();
        }
        notifyStopped();
    }

    private void notifyStopped() {
        if (stoppedNotified) {
            return;
        }
        stoppedNotified = true;
        if (onStopped != null) {
            main.post(onStopped);
        }
    }
}
