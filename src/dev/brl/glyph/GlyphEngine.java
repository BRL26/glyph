package dev.brl.glyph;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes frames on a dedicated thread. Behaviors publish static frames
 * (charging level, media/notification progress); short effects animate on top.
 * Nothing is written while the glyphs are idle.
 */
public final class GlyphEngine {

    private static final String TAG = "GlyphEngine";
    private static final int FRAME_MS = 33;
    private static final int FADE_STEP_MS = 16;
    private static final long FADE_MS = 200L;
    private static final long VOLUME_HOLD_MS = 1600L;

    private static GlyphEngine instance;

    private final Root root = Root.get();
    private final Handler handler;
    private final HashMap<Integer, int[]> statics = new HashMap<>();
    private final HashMap<Integer, boolean[]> staticMasks = new HashMap<>();
    private final HashMap<Integer, Long> staticStamps = new HashMap<>();
    private final HashMap<Integer, Integer> progressValues = new HashMap<>();
    private long staticClock;
    private final HashMap<Integer, Runnable> tempClears = new HashMap<>();
    private final Object frameLock = new Object();
    private Runnable tick;
    private boolean animating;
    private boolean chargingFill;
    private boolean hasWritten;
    private volatile boolean suspended;
    private volatile long generation;
    private Context app;
    private volatile boolean flashlight;
    private volatile boolean audioOverride;
    private volatile Object audioOwner;
    private volatile boolean priorityAnimation;
    private volatile int priorityLevel;
    private volatile boolean visualizerActive;
    private volatile Object visualizerOwner;

    /** Notification audio is level 1; a ringing call is level 2. */
    public static final int LEVEL_NOTIFICATION = 1;
    public static final int LEVEL_CALL = 2;
    private volatile int[] volumeFrame;
    private volatile boolean[] volumeMask;
    private volatile int[] lastFrame = new int[GlyphLayout.LEDS];
    private Runnable volumeClear;

    private GlyphEngine() {
        HandlerThread thread = new HandlerThread("glyph-frames");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public static synchronized GlyphEngine get() {
        if (instance == null) {
            instance = new GlyphEngine();
        }
        return instance;
    }

    public void init(Context context) {
        app = context.getApplicationContext();
    }

    /** Full-white override. It is checked before every normal write path. */
    public void setFlashlight(boolean on) {
        flashlight = on;
        if (on) {
            resume();
            stopAnimation();
            chargingFill = false;
        audioOverride = false;
        priorityAnimation = false;
        priorityLevel = 0;
        visualizerActive = false;
        visualizerOwner = null;
        cancelVolumeClear();
        volumeFrame = null;
        volumeMask = null;
        write(GlyphLayout.fullFrame());
        } else if (suspended || (app != null && !OutputGate.allows(app, OutputGate.GLYPHS))) {
            // All off (or a closed output gate) suspends normal rendering.
            // Turning the flashlight off still has to clear the latched white
            // frame; render() would return early while suspended.
            synchronized (frameLock) {
                int[] off = new int[GlyphLayout.LEDS];
                root.writeFrame(off);
                lastFrame = off;
                hasWritten = true;
            }
        } else {
            render();
        }
        Log.i(TAG, "glyph lights=" + on);
    }

    /** Re-evaluate category gates after a toggle without changing zone prefs. */
    public void refreshGates(Context context) {
        if (!OutputGate.allows(context, OutputGate.MUSIC)) {
            clearStatic(Prefs.B_MEDIA);
        }
        if (!OutputGate.allows(context, OutputGate.VOLUME)) {
            cancelVolumeClear();
            volumeFrame = null;
            volumeMask = null;
        }
        if (!OutputGate.allows(context, OutputGate.BATTERY)) {
            chargingFill = false;
            clearStatic(Prefs.B_CHARGE_BRIEF);
            clearStatic(Prefs.B_CHARGE_FULL);
        }
        if (!OutputGate.allows(context, OutputGate.RINGTONE)
                && priorityAnimation && priorityLevel == LEVEL_CALL) {
            endPriority();
        }
        if (!OutputGate.allows(context, OutputGate.GLYPHS)) {
            clear();
        } else {
            resumeIfSuspended();
            render();
        }
    }

    // ------------------------------------------------------------ statics

    public void setProgress(Context context, int behavior, int percent) {
        if (!OutputGate.allowsBehavior(context, behavior)) {
            clearStatic(behavior);
            return;
        }
        // Storing a static must not cancel a running effect. The old resume()
        // bumped the generation, which killed the plug-in fill the instant the
        // battery broadcast arrived.
        resumeIfSuspended();
        int value = Prefs.clamp(percent, 0, 100);
        progressValues.put(behavior, value);
        putStatic(behavior, Prefs.progressFrame(context, behavior, value),
                Prefs.behaviorMask(context, behavior));
        render();
    }

    public void clearStatic(int behavior) {
        progressValues.remove(behavior);
        Runnable clear = tempClears.remove(behavior);
        if (clear != null) {
            handler.removeCallbacks(clear);
        }
        statics.remove(behavior);
        staticMasks.remove(behavior);
        staticStamps.remove(behavior);
        if (!animating && !audioOverride && !suspended && GlyphEffects.sum(lastFrame) != 0) {
            fadeThenRender();
        } else {
            render();
        }
    }

    public void apply(Context context) {
        resume();
        for (Map.Entry<Integer, Integer> entry : progressValues.entrySet()) {
            int behavior = entry.getKey();
            statics.put(behavior, Prefs.progressFrame(context, behavior, entry.getValue()));
            staticMasks.put(behavior, Prefs.behaviorMask(context, behavior));
        }
        // A settings write must not cut off a call or notification animation.
        if (audioOverride || priorityAnimation) {
            return;
        }
        stopAnimation();
        render();
    }

    /**
     * Hard off. This is a latch, not a hint: queued animation ticks and audio
     * frames that were already in flight are rejected until a new behavior
     * explicitly resumes the engine.
     */
    public void clear() {
        suspended = true;
        generation++;
        audioOverride = false;
        priorityAnimation = false;
        visualizerActive = false;
        visualizerOwner = null;
        chargingFill = false;
        stopAnimation();
        cancelVolumeClear();
        volumeFrame = null;
        volumeMask = null;
        statics.clear();
        staticMasks.clear();
        staticStamps.clear();
        progressValues.clear();
        priorityLevel = 0;
        for (Runnable clear : tempClears.values()) {
            handler.removeCallbacks(clear);
        }
        tempClears.clear();
        synchronized (frameLock) {
            int[] off = flashlight ? GlyphLayout.fullFrame() : new int[GlyphLayout.LEDS];
            root.writeFrame(off);
            lastFrame = off;
        }
        Log.i(TAG, "hard off: animations stopped" + (flashlight ? " (lights preserved)" : " and frame latched to zero"));
    }

    private void resume() {
        suspended = false;
        generation++;
    }

    /** Lifts a hard-off latch without interrupting an animation. */
    private void resumeIfSuspended() {
        if (suspended) {
            suspended = false;
            generation++;
        }
    }

    // ---------------------------------------------------------- effects

    public void playNotification(Context context) {
        if (!OutputGate.allowsBehavior(context, Prefs.B_ESSENTIAL)) {
            return;
        }
        // Notifications keep their own pulse; they are the deliberate
        // exception to the quick fade used by the other displays.
        play(GlyphEffects.STYLE_PULSE, 1900, false,
                Prefs.frameForBehavior(context, Prefs.B_ESSENTIAL), false);
    }

    public void playChargingBrief(Context context) {
        if (!OutputGate.allows(context, OutputGate.BATTERY)) {
            return;
        }
        playFill(context, Prefs.B_CHARGE_BRIEF, 2800L);
    }

    /** Bottom-up fill used by the charge animation. */
    private void playFill(final Context context, final int behavior, final long durationMs) {
        if (audioOverride || priorityAnimation || visualizerActive) {
            return;
        }
        resume();
        stopAnimation();
        animating = true;
        chargingFill = true;
        final long started = SystemClock.uptimeMillis();
        final long token = generation;
        tick = new Runnable() {
            @Override
            public void run() {
                if (!animating || audioOverride || suspended || token != generation) {
                    return;
                }
                long elapsed = SystemClock.uptimeMillis() - started;
                int percent = (int) Math.min(100L, (elapsed * 100L) / durationMs);
                write(Prefs.progressFrame(context, behavior, percent));
                if (elapsed < durationMs) {
                    handler.postDelayed(this, FRAME_MS);
                } else {
                    chargingFill = false;
                    fadeThenRender();
                }
            }
        };
        handler.post(tick);
    }

    /**
     * Shows the current volume as a bottom-up bar. The volume mask overrides
     * only the LEDs assigned to Volume, so media progress and other strips keep
     * their values underneath.
     */
    public void playVolume(Context context, int percent) {
        if (!OutputGate.allows(context, OutputGate.VOLUME)) {
            return;
        }
        // Calls and notification sounds own the LEDs until their animation
        // finishes; volume waits instead of drawing over them.
        if (audioOverride || priorityAnimation || visualizerActive) {
            return;
        }
        resume();
        // A key press takes over immediately instead of waiting for the
        // notification or charging animation to finish its current tick.
        stopAnimation();
        chargingFill = false;
        int[] frame = Prefs.progressFrame(context, Prefs.B_VOLUME, percent);
        boolean[] mask = Prefs.behaviorMask(context, Prefs.B_VOLUME);
        if (!hasMask(mask)) {
            cancelVolumeClear();
            volumeFrame = null;
            volumeMask = null;
            render();
            return;
        }
        volumeFrame = frame;
        volumeMask = mask;
        // Start from the stable statics, not a half-faded frame. Using
        // lastFrame here left the unassigned strips stuck at whatever
        // brightness a previous fade had reached.
        int[] current = staticFrame();
        overlayVolume(current);
        write(current);

        cancelVolumeClear();
        volumeClear = new Runnable() {
            @Override
            public void run() {
                volumeClear = null;
                volumeFrame = null;
                volumeMask = null;
                fadeThenRender();
            }
        };
        handler.postDelayed(volumeClear, VOLUME_HOLD_MS);
    }

    private void cancelVolumeClear() {
        if (volumeClear != null) {
            handler.removeCallbacks(volumeClear);
            volumeClear = null;
        }
    }

    public void startCall(Context context) {
        playSound(context, GlyphEffects.STYLE_CALL, 120000L, Prefs.soundFrame(context));
    }

    public void stopCall() {
        endPriority();
    }

    /**
     * Ends a priority animation and fades back to whatever was underneath it.
     * Statics (media/notification progress, battery, essential bars) were kept
     * in the claim map while the alert owned the LEDs, so this is the path that
     * makes them come back instead of staying dark.
     */
    private void endPriority() {
        priorityAnimation = false;
        priorityLevel = 0;
        stopAnimation();
        fadeThenRender();
    }

    /**
     * Persistent essential-notification bar. It is a static, not a pulse: it
     * stays lit until the matching notification is removed.
     */
    public void showEssential(Context context) {
        if (suspended) {
            return;
        }
        if (!OutputGate.allowsBehavior(context, Prefs.B_ESSENTIAL)) {
            clearStatic(Prefs.B_ESSENTIAL);
            return;
        }
        putStatic(Prefs.B_ESSENTIAL, Prefs.frameForBehavior(context, Prefs.B_ESSENTIAL),
                Prefs.behaviorMask(context, Prefs.B_ESSENTIAL));
        render();
    }

    public void preview(Context context, int style) {
        play(style, 2600, false, Prefs.frameForAll(context), true);
    }

    public void previewZone(Context context, int zone) {
        play(GlyphEffects.STYLE_PULSE, 1600, false, Prefs.frameForZone(context, zone), true);
    }

    /** Hardware test: show a raw frame, then fade back to the normal statics. */
    public void previewRaw(final int[] frame, final long durationMs) {
        if (frame == null || frame.length != GlyphLayout.LEDS
                || audioOverride || priorityAnimation || visualizerActive) {
            return;
        }
        resume();
        stopAnimation();
        animating = true;
        final long token = generation;
        write(frame);
        tick = new Runnable() {
            @Override
            public void run() {
                if (!animating || audioOverride || suspended || token != generation) {
                    return;
                }
                animating = false;
                tick = null;
                fadeThenRender();
            }
        };
        handler.postDelayed(tick, durationMs);
    }

    // --------------------------------------------------------- animation

    public void stop() {
        endPriority();
    }

    /** Cancels the plug-in fill when the charger is removed. */
    public void stopCharging() {
        if (!chargingFill) {
            return;
        }
        chargingFill = false;
        stopAnimation();
        fadeThenRender();
    }

    private void stopAnimation() {
        animating = false;
        if (tick != null) {
            handler.removeCallbacks(tick);
            tick = null;
        }
    }

    public void play(final int style, final long durationMs, final boolean loop,
                     final int[] source, final boolean fadeOut) {
        play(style, durationMs, loop, source, fadeOut, false);
    }

    /**
     * Starts the global notification/call animation. It owns the LEDs until it
     * ends; volume, battery and progress displays wait for it rather than
     * drawing over it.
     */
    public void playSound(Context context, int style, long durationMs, int[] source) {
        if (!OutputGate.allowsAlert(context, levelForStyle(style))) {
            return;
        }
        play(style, durationMs, false, source, false, true);
    }

    private static int levelForStyle(int style) {
        return style == GlyphEffects.STYLE_CALL ? LEVEL_CALL : LEVEL_NOTIFICATION;
    }

    private void play(final int style, final long durationMs, final boolean loop,
                      final int[] source, final boolean fadeOut, final boolean priority) {
        int level = priority ? levelForStyle(style) : 0;
        // Calls outrank notifications, and both outrank every normal display.
        if (audioOverride || (visualizerActive && !priority)
                || (priorityAnimation && (!priority || level < priorityLevel))
                || source == null || GlyphEffects.sum(source) == 0) {
            return;
        }
        resume();
        stopAnimation();
        chargingFill = false;
        animating = true;
        if (priority) {
            priorityAnimation = true;
            priorityLevel = level;
        }
        final long started = SystemClock.uptimeMillis();
        final long token = generation;
        tick = new Runnable() {
            @Override
            public void run() {
                if (!animating || audioOverride || suspended || token != generation) {
                    return;
                }
                long elapsed = SystemClock.uptimeMillis() - started;
                write(GlyphEffects.compose(style, elapsed, source));
                if (loop) {
                    handler.postDelayed(this, FRAME_MS);
                } else if (elapsed < durationMs) {
                    handler.postDelayed(this, FRAME_MS);
                } else if (fadeOut) {
                    fadeThenRender();
                } else {
                    animating = false;
                    tick = null;
                    if (priority) {
                        endPriority();
                    } else {
                        fadeThenRender();
                    }
                }
            }
        };
        handler.post(tick);
    }

    /**
     * Plays a precompiled 36-column script at its native frame rate. The
     * notification/call priority rules are the same as playSound(); muted
     * alerts use this too, so the pattern is identical with or without audio.
     */
    public void playFrames(Context context, final int[][] frames, final int fps,
                           final int level, final Runnable onDone) {
        playFrames(context, frames, fps, level, false, onDone);
    }

    /**
     * Same as playFrames(), but a looping track repeats until a call ends or
     * another priority alert replaces it. Real ringing calls use this so the
     * compiled ringtone keeps replaying for the whole ring instead of going
     * dark after one pass.
     */
    public void playFrames(Context context, final int[][] frames, final int fps,
                           final int level, final boolean loop, final Runnable onDone) {
        if (!OutputGate.allowsAlert(context, level)) {
            return;
        }
        if (frames == null || frames.length == 0 || fps <= 0 || audioOverride
                || (priorityAnimation && level < priorityLevel)) {
            return;
        }
        resume();
        stopAnimation();
        chargingFill = false;
        animating = true;
        priorityAnimation = true;
        priorityLevel = level;
        final long token = generation;
        final long started = SystemClock.uptimeMillis();
        final double frameMs = 1000.0 / fps;
        final long totalMs = Math.round(frames.length * frameMs);
        tick = new Runnable() {
            @Override
            public void run() {
                if (!animating || audioOverride || suspended || token != generation) {
                    return;
                }
                long elapsed = SystemClock.uptimeMillis() - started;
                if (elapsed >= totalMs) {
                    if (loop) {
                        elapsed %= totalMs;
                    } else {
                        animating = false;
                        tick = null;
                        if (onDone != null) {
                            onDone.run();
                        }
                        endPriority();
                        return;
                    }
                }
                int index = GlyphEffects.frameIndex(elapsed, frames.length, fps, loop);
                write(frames[index]);
                handler.postDelayed(this, 16L);
            }
        };
        handler.post(tick);
    }

    /**
     * Arms the persistent visualizer. It owns normal output until disarmed,
     * but unlike a notification/call override it yields to priority audio.
     */
    public void beginVisualizer(Object owner) {
        if (app != null && OutputGate.allOff(app)) {
            return;
        }
        resumeIfSuspended();
        visualizerActive = true;
        visualizerOwner = owner;
        cancelVolumeClear();
        volumeFrame = null;
        volumeMask = null;
        if (!priorityAnimation && !audioOverride) {
            stopAnimation();
            chargingFill = false;
        }
        Log.i(TAG, "visualizer armed");
    }

    public boolean isVisualizerActive() {
        return visualizerActive;
    }

    /** Writes one live visualizer frame. Calls/notifications always outrank it. */
    public void visualizerFrame(int[] frame) {
        if (!visualizerActive || suspended || frame == null
                || frame.length != GlyphLayout.LEDS) {
            return;
        }
        synchronized (frameLock) {
            if (!visualizerActive || suspended || priorityAnimation || audioOverride) {
                return;
            }
            if (flashlight) {
                return;
            }
            if (app != null && OutputGate.allOff(app)) {
                return;
            }
            root.writeFrame(frame);
            lastFrame = frame.clone();
            hasWritten = true;
        }
    }

    public void endVisualizer(Object owner) {
        if (owner != null && visualizerOwner != owner) {
            return;
        }
        visualizerActive = false;
        visualizerOwner = null;
        Log.i(TAG, "visualizer disarmed");
        if (!priorityAnimation && !audioOverride) {
            fadeThenRender();
        }
    }

    /** Audio is about to write its own frames; stop normal animations. */
    public void beginAudioOverride(Object owner, int level) {
        resume();
        audioOverride = true;
        audioOwner = owner;
        priorityAnimation = true;
        priorityLevel = Math.max(priorityLevel, level);
        stopAnimation();
    }

    public void audioFrame(int[] frame) {
        if (!audioOverride || suspended || frame == null || frame.length != GlyphLayout.LEDS) {
            return;
        }
        synchronized (frameLock) {
            if (flashlight) {
                int[] white = GlyphLayout.fullFrame();
                root.writeFrame(white);
                lastFrame = white.clone();
                return;
            }
            if (!audioOverride || suspended) {
                return;
            }
            if (app != null && !OutputGate.allows(app, OutputGate.GLYPHS)) {
                return;
            }
            root.writeFrame(frame);
            lastFrame = frame.clone();
        }
    }

    /**
     * Restore whatever static behavior was underneath the sound. An owner
     * check keeps a stopped pulse from cancelling the pulse that replaced it;
     * passing null forces the override off before a new sound starts.
     */
    public void endAudioOverride(Object owner) {
        if (owner != null && audioOwner != owner) {
            return;
        }
        audioOwner = null;
        audioOverride = false;
        endPriority();
    }

    /**
     * Static displays claim strips, not individual LEDs. The newest claim on a
     * strip wins, so a fresh volume/progress/battery write replaces an older
     * one instead of being blended by max(). Earlier claims on other strips
     * stay visible.
     */
    private void putStatic(int behavior, int[] frame, boolean[] mask) {
        statics.put(behavior, frame);
        staticMasks.put(behavior, mask);
        staticStamps.put(behavior, ++staticClock);
    }

    private int[] staticFrame() {
        List<Integer> behaviors = new ArrayList<>(statics.keySet());
        Collections.sort(behaviors, new Comparator<Integer>() {
            @Override
            public int compare(Integer left, Integer right) {
                long a = staticStamps.containsKey(left) ? staticStamps.get(left) : 0L;
                long b = staticStamps.containsKey(right) ? staticStamps.get(right) : 0L;
                return Long.compare(a, b);
            }
        });
        List<int[]> frames = new ArrayList<>();
        List<boolean[]> masks = new ArrayList<>();
        for (Integer behavior : behaviors) {
            frames.add(statics.get(behavior));
            masks.add(staticMasks.get(behavior));
        }
        return FrameClaims.compose(frames, masks);
    }

    private void render() {
        if (audioOverride || priorityAnimation || visualizerActive || suspended) {
            return;
        }
        if (animating) {
            return;
        }
        int[] frame = staticFrame();
        write(frame);
    }

    /**
     * Quick fade used by previews, charging and progress cleanup. Notification
     * and ringtone animations bypass this and keep their own timing.
     */
    private void fadeThenRender() {
        if (suspended || audioOverride || priorityAnimation || visualizerActive) {
            return;
        }
        // Only one fade may exist. Two overlapping fades used to write
        // alternating frames, which is exactly what the flicker looked like.
        stopAnimation();
        final int[] from = lastFrame.clone();
        final int[] target = staticFrame();
        if (Arrays.equals(from, target)) {
            render();
            return;
        }
        animating = true;
        final long started = SystemClock.uptimeMillis();
        final long token = generation;
        Log.d(TAG, "fade " + GlyphEffects.sum(from) + " -> " + GlyphEffects.sum(target));
        tick = new Runnable() {
            @Override
            public void run() {
                if (!animating || audioOverride || suspended || token != generation) {
                    return;
                }
                long elapsed = SystemClock.uptimeMillis() - started;
                float t = Math.min(1f, elapsed / (float) FADE_MS);
                // Ease-out keeps the last steps small so the tail does not
                // read as the LEDs blinking off.
                float eased = 1f - (1f - t) * (1f - t);
                if (t < 1f) {
                    write(GlyphEffects.blend(from, target, eased));
                    handler.postDelayed(this, FADE_STEP_MS);
                } else {
                    animating = false;
                    tick = null;
                    write(target);
                    // A progress update can land during the fade. Re-render the
                    // current claim map so the restored bar is the latest one,
                    // not the snapshot taken when the alert ended.
                    render();
                }
            }
        };
        handler.post(tick);
    }

    private void write(int[] frame) {
        synchronized (frameLock) {
            if (flashlight) {
                int[] white = GlyphLayout.fullFrame();
                root.writeFrame(white);
                lastFrame = white.clone();
                hasWritten = true;
                return;
            }
            if (suspended) {
                return;
            }
            if (app != null && !OutputGate.allows(app, OutputGate.GLYPHS)) {
                return;
            }
            overlayVolume(frame);
            if (hasWritten && Arrays.equals(frame, lastFrame)) {
                return;
            }
            root.writeFrame(frame);
            lastFrame = frame.clone();
            hasWritten = true;
        }
    }

    private void overlayVolume(int[] frame) {
        int[] overlay = volumeFrame;
        boolean[] mask = volumeMask;
        if (overlay == null || mask == null || overlay.length != frame.length) {
            return;
        }
        for (int i = 0; i < frame.length; i++) {
            if (mask[i]) {
                frame[i] = overlay[i];
            }
        }
    }

    private static boolean hasMask(boolean[] mask) {
        if (mask == null) {
            return false;
        }
        for (boolean value : mask) {
            if (value) {
                return true;
            }
        }
        return false;
    }

    public void logState() {
        Log.d(TAG, "animating=" + animating + " statics=" + statics.size()
                + " volume=" + (volumeFrame != null) + " root=" + root.isReady());
    }
}
