package dev.brl.glyph;

import java.util.Arrays;

/**
 * Stock-style music visualizer: three frequency bands drive three whole
 * strips. This is the same shape as the ROM's MusicVisualizerService and
 * AnimationManager.playMusic(Set), adapted to direct frame writes:
 *
 *   low   40 Hz .. 250 Hz   -> C, the 20-LED top-left strip
 *   mid  250 Hz .. 5 kHz    -> A, the 11-LED right strip
 *   high 5 kHz .. 10 kHz    -> B, the 5-LED bottom-left strip
 *
 * A band turns on hard when its energy appears and fades out when it drops,
 * so a ringtone switches whole strips instead of drawing one bright LED with
 * a dim gradient around it.
 */
public final class AudioSpectrum {

    public static final int LEDS = GlyphLayout.LEDS;
    public static final int BANDS = 3;

    private static final float FULL_SCALE = 128f;
    private static final float INITIAL_PEAK = 0.12f;
    private static final float PEAK_DECAY = 0.995f;
    private static final float RELEASE = 0.86f;
    private static final float NOISE_GATE = 0.12f;
    private static final float ABSOLUTE_FLOOR = 0.02f;

    // Public zone order A (right), B (bottom-left), C (top-left).
    private static final float[] BAND_LOW = {250f, 5000f, 40f};
    private static final float[] BAND_HIGH = {5000f, 10000f, 250f};
    private static final float[] TILT = {1.0f, 1.8f, 1.0f};

    private final float[] raw = new float[BANDS];
    private final float[] display = new float[BANDS];
    private float peak = INITIAL_PEAK;
    private float lastEnergy;

    public AudioSpectrum() {
        reset();
    }

    public void reset() {
        Arrays.fill(raw, 0f);
        Arrays.fill(display, 0f);
        peak = INITIAL_PEAK;
        lastEnergy = 0f;
    }

    public float lastEnergy() {
        return lastEnergy;
    }

    /** Decayed 0..1 level for a public zone, useful for diagnostics/tests. */
    public float bandLevel(int zone) {
        return zone < 0 || zone >= BANDS ? 0f : display[zone];
    }

    /**
     * @param fft          Visualizer FFT bytes (real/imag pairs, signed bytes).
     * @param sampleRate   Real sample rate in Hz.
     * @param captureSize  Visualizer capture size.
     * @param brightness   Global brightness percentage (0..100).
     * @return 36 values in frame_brightness order.
     */
    public int[] process(byte[] fft, int sampleRate, int captureSize, int brightness) {
        return process(fft, sampleRate, captureSize, brightness, null);
    }

    /**
     * @param allowed LEDs that may be written. Disabled strips stay dark, and
     *                a band whose strip is disabled folds into the largest
     *                remaining strip so the sound still shows on a 5, 11 or
     *                20-LED setup.
     */
    public int[] process(byte[] fft, int sampleRate, int captureSize, int brightness,
                         boolean[] allowed) {
        int[] frame = new int[LEDS];
        lastEnergy = 0f;
        if (fft == null || sampleRate <= 0 || captureSize <= 0) {
            return frame;
        }
        int bins = Math.min(fft.length / 2, captureSize / 2);
        double hzPerBin = sampleRate / (double) captureSize;
        if (bins <= 1 || hzPerBin <= 0.0) {
            return frame;
        }

        float framePeak = 0f;
        for (int zone = 0; zone < BANDS; zone++) {
            int first = Math.max(1, (int) Math.floor(BAND_LOW[zone] / hzPerBin));
            int last = Math.min(bins - 1, (int) Math.ceil(BAND_HIGH[zone] / hzPerBin));
            if (last < first) {
                last = first;
            }
            float magnitude = 0f;
            for (int bin = first; bin <= last; bin++) {
                int index = bin * 2;
                if (index + 1 >= fft.length) {
                    break;
                }
                float real = fft[index];
                float imaginary = fft[index + 1];
                float value = (float) Math.hypot(real, imaginary);
                if (value > magnitude) {
                    magnitude = value;
                }
            }
            raw[zone] = magnitude / FULL_SCALE * TILT[zone];
            if (raw[zone] > framePeak) {
                framePeak = raw[zone];
            }
        }

        lastEnergy = framePeak;
        peak = Math.max(framePeak, peak * PEAK_DECAY);
        if (peak < INITIAL_PEAK) {
            peak = INITIAL_PEAK;
        }

        float gain = Math.max(0, Math.min(100, brightness)) / 100f;
        boolean[] permit = allowed;
        if (permit == null || permit.length != LEDS) {
            permit = new boolean[LEDS];
            Arrays.fill(permit, true);
        }
        int[] levels = new int[BANDS];
        for (int zone = 0; zone < BANDS; zone++) {
            float normalized = raw[zone] / peak;
            boolean active = raw[zone] >= ABSOLUTE_FLOOR && normalized > NOISE_GATE;
            // Stock-style beat mode: the strip is fully on while its band is
            // present, then fades out instead of drawing a per-LED gradient.
            display[zone] = active ? 1f : display[zone] * RELEASE;
            levels[zone] = Math.round(display[zone] * GlyphLayout.MAX_VALUE * gain);
        }
        // Prefer C, then A, then B as the destination when a band's own strip
        // is disabled.
        int fallback = -1;
        for (int zone : new int[]{2, 0, 1}) {
            if (zoneAllowed(permit, zone)) {
                fallback = zone;
                break;
            }
        }
        int[] output = new int[BANDS];
        for (int zone = 0; zone < BANDS; zone++) {
            int target = zoneAllowed(permit, zone) ? zone : fallback;
            if (target >= 0 && levels[zone] > output[target]) {
                output[target] = levels[zone];
            }
        }
        for (int zone = 0; zone < BANDS; zone++) {
            for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
                int hw = GlyphLayout.hwIndex(zone, pos);
                if (permit[hw]) {
                    frame[hw] = output[zone];
                }
            }
        }
        return frame;
    }

    private static boolean zoneAllowed(boolean[] allowed, int zone) {
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
            if (allowed[GlyphLayout.hwIndex(zone, pos)]) {
                return true;
            }
        }
        return false;
    }
}
