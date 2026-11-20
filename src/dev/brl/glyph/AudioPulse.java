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
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioSpectrum spectrum = new AudioSpectrum();
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
        spectrum.reset();
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
        if (now - startedAt > maxDurationMs) {
            stopFromWorker();
            return;
        }

        int brightness = Prefs.clamp(Prefs.brightness(context), 0, 100);
        int[] frame = spectrum.process(fft, sampleRate, captureSize, brightness, allowedLeds);
        float energy = spectrum.lastEnergy();

        if (energy > 0.02f) {
            lastEnergyAt = now;
        } else if (now - lastEnergyAt > silenceMs) {
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
        GlyphEngine.get().audioFrame(frame);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - startedAt > maxDurationMs || now - lastEnergyAt > silenceMs) {
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
            GlyphEngine.get().endAudioOverride(owner);
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
