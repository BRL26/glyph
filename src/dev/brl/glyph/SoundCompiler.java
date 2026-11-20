package dev.brl.glyph;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a sound into 36-column, 60 fps note frames.
 *
 * This is a piano-roll mapping, not a loudness meter: each analysis window is
 * FFT'd, the strongest tonal peaks become MIDI notes, and every note owns one
 * LED. A held note stays lit and a released note decays. Chords light several
 * LEDs at once.
 */
public final class SoundCompiler {

    private static final String TAG = "SoundCompiler";
    public static final int FPS = 60;
    private static final Map<String, Result> CACHE = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    private static final int FFT_SIZE = 2048;
    private static final int MAX_SECONDS = 15;
    private static final int MAX_FRAMES = FPS * MAX_SECONDS;
    private static final int MIN_MIDI = 48; // C3
    private static final int MAX_MIDI = 83; // B5, 36 semitones for 36 LEDs
    private static final int MAX_NOTES = 2;
    private static final double DECAY = 0.58;
    private static final int NOTE_HOLD_FRAMES = 6;
    private static final int MIN_ATTACK_FRAMES = 4;
    private static final int FLUX_HISTORY = 30;

    public static final class Result {
        public final int[][] frames;
        public final long durationMs;

        Result(int[][] frames) {
            this.frames = frames;
            this.durationMs = Math.round(frames.length * (1000.0 / FPS));
        }
    }

    private static final class Note {
        final int midi;
        final double strength;
        final double frequency;

        Note(int midi, double strength, double frequency) {
            this.midi = midi;
            this.strength = strength;
            this.frequency = frequency;
        }
    }

    private SoundCompiler() {
    }

    public static Result compile(Context context, Uri uri) throws Exception {
        return compile(context, uri, Prefs.autogenMode(context));
    }

    public static Result compile(Context context, Uri uri, int mode) throws Exception {
        if (uri == null) {
            throw new IllegalArgumentException("sound uri is null");
        }
        mode = Prefs.clamp(mode, 0, Prefs.AUTOGEN_COUNT - 1);
        final int renderMode = mode;
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            int track = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    inputFormat = format;
                    break;
                }
            }
            if (track < 0 || inputFormat == null) {
                throw new IllegalArgumentException("no audio track");
            }
            extractor.selectTrack(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            if (sampleRate <= 0 || channels <= 0) {
                throw new IllegalArgumentException("invalid audio format");
            }
            short[] pcm = new short[Math.min(MAX_SECONDS * sampleRate, 1600000)];
            int pcmCount = 0;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone && pcmCount < pcm.length) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000L);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        int size = input == null ? -1 : extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10000L);
                if (outputIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outputIndex);
                    if (output != null && info.size > 0) {
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        output.get(data);
                        pcmCount = consumePcm(data, channels, pcm, pcmCount);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }

            int totalFrames = Math.min(MAX_FRAMES,
                    (int) ((long) pcmCount * FPS / Math.max(1, sampleRate)));
            if (totalFrames < 2) {
                throw new IllegalStateException("sound too short");
            }
            int hop = Math.max(1, sampleRate / FPS);
            if (renderMode != Prefs.AUTOGEN_NOTE_MAP) {
                // AutoGen owns the website's fixed 16.666 ms hop and exact
                // floor((samples - 2048) / hop) + 1 frame grid.
                int[][] generated = AutoGen.render(pcm, pcmCount, sampleRate, renderMode);
                if (generated == null || generated.length == 0) {
                    throw new IllegalStateException("auto generation produced no frames");
                }
                int lit = 0;
                for (int[] generatedFrame : generated) {
                    if (GlyphEffects.sum(generatedFrame) > 0) {
                        lit++;
                    }
                }
                if (lit == 0) {
                    throw new IllegalStateException("auto generation produced no light");
                }
                Log.d(TAG, "compiled mode=" + Prefs.AUTOGEN_NAMES[renderMode]
                        + " frames=" + generated.length + " litFrames=" + lit);
                return new Result(generated);
            }
            int[] pitchLeds = pitchLedOrder(context);
            if (pitchLeds.length == 0) {
                throw new IllegalStateException("no enabled glyph strips");
            }
            int[] zoneForLed = zoneForLed();
            int brightness = Prefs.clamp(Prefs.brightness(context), 0, 100);
            int[] zoneValue = new int[3];
            for (int zone = 0; zone < 3; zone++) {
                zoneValue[zone] = Math.round(255f * Prefs.zoneLevel(context, zone)
                        * brightness / 10000f);
            }

            double[] ledBrightness = new double[GlyphLayout.LEDS];
            int[] hold = new int[GlyphLayout.LEDS];
            float[] lastStrength = new float[128];
            boolean[] present = new boolean[128];
            double[] previousSpectrum = null;
            double[] fluxHistory = new double[FLUX_HISTORY];
            int fluxCount = 0;
            int fluxIndex = 0;
            double fluxTotal = 0.0;
            int lastAttackFrame = -100;
            int onsetFrames = 0;
            double energyPeak = 0.05;
            double[] bandDisplay = new double[3];
            int patternPosition = 0;
            int patternHold = 0;
            int pulseHold = 0;
            int[][] frames = new int[totalFrames][GlyphLayout.LEDS];
            Set<Integer> uniqueNotes = new TreeSet<>();
            int noteEvents = 0;
            int litFrames = 0;

            for (int frame = 0; frame < totalFrames; frame++) {
                Arrays.fill(present, false);
                for (int led = 0; led < GlyphLayout.LEDS; led++) {
                    ledBrightness[led] *= DECAY;
                    if (hold[led] > 0) {
                        hold[led]--;
                    }
                    if (ledBrightness[led] < 3.0) {
                        ledBrightness[led] = 0.0;
                    }
                }

                double[] spectrum = spectrum(pcm, pcmCount, frame * hop, sampleRate);
                double flux = previousSpectrum == null ? 1.0
                        : spectralFlux(spectrum, previousSpectrum);
                previousSpectrum = spectrum;
                if (fluxCount < FLUX_HISTORY) {
                    fluxHistory[fluxCount++] = flux;
                    fluxTotal += flux;
                } else {
                    fluxTotal -= fluxHistory[fluxIndex];
                    fluxHistory[fluxIndex] = flux;
                    fluxTotal += flux;
                    fluxIndex = (fluxIndex + 1) % FLUX_HISTORY;
                }
                double averageFlux = fluxTotal / Math.max(1, fluxCount);
                boolean onset = flux > Math.max(0.07, averageFlux * 1.2);
                if (onset) {
                    onsetFrames++;
                }
                double[] bands = bandEnergies(spectrum, sampleRate);
                double bandPeak = Math.max(bands[0], Math.max(bands[1], bands[2]));
                energyPeak = Math.max(bandPeak, energyPeak * 0.995);
                double energy = Math.min(1.0, bandPeak / Math.max(0.0001, energyPeak));

                if (renderMode == Prefs.AUTOGEN_NOTE_MAP) {
                List<Note> notes = onset ? detectNotes(spectrum, sampleRate)
                        : Collections.<Note>emptyList();
                for (Note note : notes) {
                    present[note.midi] = true;
                    boolean attack = lastStrength[note.midi] <= 0f
                            || note.strength > lastStrength[note.midi] * 1.35f + 0.02f;
                    lastStrength[note.midi] = Math.max((float) note.strength,
                            lastStrength[note.midi] * 0.92f);
                    if (!attack || frame - lastAttackFrame < MIN_ATTACK_FRAMES) {
                        continue;
                    }
                    lastAttackFrame = frame;
                    noteEvents++;
                    uniqueNotes.add(note.midi);
                    int pitchPosition = note.midi - MIN_MIDI;
                    while (pitchPosition < 0) {
                        pitchPosition += 12;
                    }
                    while (pitchPosition > 35) {
                        pitchPosition -= 12;
                    }
                    int index = (int) Math.round(
                            pitchPosition * (pitchLeds.length - 1) / 35.0);
                    index = Math.max(0, Math.min(pitchLeds.length - 1, index));
                    int led = pitchLeds[index];
                    int zone = zoneForLed[led];
                    if (zone < 0 || !Prefs.zoneOn(context, zone)) {
                        continue;
                    }
                    hold[led] = NOTE_HOLD_FRAMES;
                }

                for (int led = 0; led < GlyphLayout.LEDS; led++) {
                    if (hold[led] <= 0) {
                        continue;
                    }
                    int zone = zoneForLed[led];
                    if (zone < 0) {
                        continue;
                    }
                    int value = zoneValue[zone];
                    ledBrightness[led] = Math.max(ledBrightness[led], value);
                    // Two quieter neighbours make a single note readable on
                    // the physical strips without turning it into a bar.
                    int pitchIndex = pitchIndexForLed(pitchLeds, led);
                    if (pitchIndex > 0) {
                        int neighbour = pitchLeds[pitchIndex - 1];
                        ledBrightness[neighbour] = Math.max(ledBrightness[neighbour],
                                Math.round(value * 0.28));
                    }
                    if (pitchIndex >= 0 && pitchIndex + 1 < pitchLeds.length) {
                        int neighbour = pitchLeds[pitchIndex + 1];
                        ledBrightness[neighbour] = Math.max(ledBrightness[neighbour],
                                Math.round(value * 0.28));
                    }
                }

                for (int midi = 0; midi < 128; midi++) {
                    if (!present[midi]) {
                        lastStrength[midi] = 0f;
                    }
                }
                } else if (renderMode == Prefs.AUTOGEN_PATTERN) {
                    if (onset && patternHold <= 0) {
                        noteEvents++;
                        patternPosition = (patternPosition + 1
                                + (int) Math.round(energy * 2.0)) % pitchLeds.length;
                        patternHold = 5;
                    }
                    if (patternHold > 0) {
                        int zone = zoneForLed[pitchLeds[patternPosition]];
                        if (zone >= 0) {
                            int value = zoneValue[zone];
                            ledBrightness[pitchLeds[patternPosition]] = value;
                            int next = (patternPosition + 1) % pitchLeds.length;
                            int nextZone = zoneForLed[pitchLeds[next]];
                            if (nextZone >= 0) {
                                ledBrightness[pitchLeds[next]] = Math.max(
                                        ledBrightness[pitchLeds[next]],
                                        Math.round(value * 0.45));
                            }
                        }
                        patternHold--;
                    }
                } else if (renderMode == Prefs.AUTOGEN_FREQUENCY) {
                    for (int band = 0; band < 3; band++) {
                        double target = Math.min(1.0, bands[band]
                                / Math.max(0.0001, energyPeak));
                        if (target < 0.08) {
                            target = 0.0;
                        }
                        bandDisplay[band] = target > bandDisplay[band]
                                ? target : bandDisplay[band] * 0.82;
                    }
                    // C is low, A is mid, B is high. Each strip fills from
                    // its bottom, matching the frequency auto-gen modes.
                    int[] zones = {2, 0, 1};
                    for (int band = 0; band < 3; band++) {
                        int zone = zones[band];
                        if (!Prefs.zoneOn(context, zone)) {
                            continue;
                        }
                        int lit = GlyphLayout.litCount(GlyphLayout.ZONE_LEDS[zone],
                                (int) Math.round(bandDisplay[band] * 100.0));
                        for (int position = 0; position < lit; position++) {
                            ledBrightness[GlyphLayout.hwIndex(zone, position)] =
                                    Math.max(ledBrightness[GlyphLayout.hwIndex(zone, position)],
                                            zoneValue[zone]);
                        }
                    }
                } else {
                    if (onset) {
                        noteEvents++;
                        pulseHold = 5;
                    }
                    if (pulseHold > 0) {
                        for (int zone = 0; zone < 3; zone++) {
                            if (!Prefs.zoneOn(context, zone)) {
                                continue;
                            }
                            int value = Math.round(zoneValue[zone]
                                    * (0.55f + 0.45f * (float) energy));
                            for (int position = 0; position < GlyphLayout.ZONE_LEDS[zone];
                                 position++) {
                                int hw = GlyphLayout.hwIndex(zone, position);
                                ledBrightness[hw] = Math.max(ledBrightness[hw], value);
                            }
                        }
                        pulseHold--;
                    }
                }

                int[] frameData = frames[frame];
                boolean lit = false;
                for (int led = 0; led < GlyphLayout.LEDS; led++) {
                    int value = (int) Math.round(ledBrightness[led]);
                    if (value > 255) {
                        value = 255;
                    }
                    frameData[led] = value;
                    lit |= value > 0;
                }
                if (lit) {
                    litFrames++;
                }
            }

            if (litFrames == 0) {
                throw new IllegalStateException("no glyph frames detected");
            }
            Log.d(TAG, "compiled mode=" + Prefs.AUTOGEN_NAMES[renderMode]
                    + " frames=" + totalFrames + " noteEvents=" + noteEvents
                    + " uniqueNotes=" + uniqueNotes.size()
                    + " onsetFrames=" + onsetFrames
                    + " litFrames=" + litFrames
                    + " notes=" + noteNames(uniqueNotes));
            return new Result(frames);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                    // Already stopped.
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                    // Already released.
                }
            }
            extractor.release();
        }
    }

    /** A compiled track is keyed by source and mode so a mode switch never
     * plays a stale render. */
    private static String cacheKey(Uri uri, int mode) {
        return uri.toString() + "#" + mode;
    }

    public static Result cached(Context context, Uri uri, int mode) {
        if (uri == null) {
            return null;
        }
        synchronized (CACHE) {
            return CACHE.get(cacheKey(uri, Prefs.clamp(mode, 0, Prefs.AUTOGEN_COUNT - 1)));
        }
    }

    public static void store(Context context, Uri uri, int mode, Result result) {
        if (uri == null || result == null) {
            return;
        }
        synchronized (CACHE) {
            if (CACHE.size() > 12) {
                CACHE.clear();
            }
            CACHE.put(cacheKey(uri, Prefs.clamp(mode, 0, Prefs.AUTOGEN_COUNT - 1)), result);
        }
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
            LOADING.clear();
        }
    }

    /** Precompiles the current default sounds in the app process. The
     * notification listener calls this on connect, so a live alert normally
     * starts from a ready track instead of decoding after the sound began. */
    public static void preloadDefaults(final Context context) {
        final Context app = context.getApplicationContext();
        final int mode = Prefs.autogenMode(app);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int[] types = {RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_RINGTONE};
                for (int type : types) {
                    Uri uri = RingtoneManager.getActualDefaultRingtoneUri(app, type);
                    if (uri == null || cached(app, uri, mode) != null) {
                        continue;
                    }
                    String key = cacheKey(uri, mode);
                    synchronized (LOADING) {
                        if (LOADING.contains(key)) {
                            continue;
                        }
                        LOADING.add(key);
                    }
                    try {
                        store(app, uri, mode, compile(app, uri, mode));
                        Log.d(TAG, "preloaded " + (type == RingtoneManager.TYPE_RINGTONE
                                ? "ringtone" : "notification") + " mode=" + mode);
                    } catch (Throwable error) {
                        Log.w(TAG, "preload failed type=" + type, error);
                    } finally {
                        synchronized (LOADING) {
                            LOADING.remove(key);
                        }
                    }
                }
            }
        }, "glyph-preload").start();
    }

    private static int consumePcm(byte[] data, int channels, short[] pcm, int count) {
        int frameBytes = Math.max(1, channels) * 2;
        for (int offset = 0; offset + frameBytes <= data.length; offset += frameBytes) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                int position = offset + channel * 2;
                int low = data[position] & 0xff;
                int high = data[position + 1];
                sum += (short) ((high << 8) | low);
            }
            pcm[count++] = (short) (sum / channels);
            if (count >= pcm.length) {
                return count;
            }
        }
        return count;
    }

    static double[] spectrum(short[] pcm, int pcmCount, int start, int sampleRate) {
        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            int index = start + i;
            if (index < pcmCount) {
                double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1));
                real[i] = (pcm[index] / 32768.0) * window;
            }
        }
        fft(real, imag);
        double[] magnitude = new double[FFT_SIZE / 2];
        for (int i = 0; i < magnitude.length; i++) {
            magnitude[i] = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
        }
        return magnitude;
    }

    private static List<Note> detectNotes(double[] magnitude, int sampleRate) {
        int minBin = Math.max(1, (int) (70.0 * FFT_SIZE / sampleRate));
        int maxBin = Math.min(magnitude.length - 2,
                (int) (5000.0 * FFT_SIZE / sampleRate));
        double peak = 0.0;
        for (int bin = minBin; bin <= maxBin; bin++) {
            if (magnitude[bin] > peak) {
                peak = magnitude[bin];
            }
        }
        if (peak < 1.0e-5) {
            return Collections.emptyList();
        }

        List<Note> candidates = new ArrayList<>();
        for (int bin = minBin + 1; bin < maxBin; bin++) {
            double center = magnitude[bin];
            if (center <= magnitude[bin - 1] || center < magnitude[bin + 1]
                    || center < peak * 0.08) {
                continue;
            }
            double left = magnitude[bin - 1];
            double right = magnitude[bin + 1];
            double denominator = left - 2.0 * center + right;
            double delta = Math.abs(denominator) < 1.0e-12
                    ? 0.0 : 0.5 * (left - right) / denominator;
            double frequency = (bin + delta) * sampleRate / (double) FFT_SIZE;
            if (frequency < 70.0 || frequency > 5000.0) {
                continue;
            }
            int midi = (int) Math.round(69.0 + 12.0 * Math.log(frequency / 440.0)
                    / Math.log(2.0));
            if (midi < 24 || midi > 108) {
                continue;
            }
            candidates.add(new Note(midi, center / peak, frequency));
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(candidates, new Comparator<Note>() {
            @Override
            public int compare(Note left, Note right) {
                return Double.compare(right.strength, left.strength);
            }
        });

        List<Note> accepted = new ArrayList<>();
        for (Note candidate : candidates) {
            boolean harmonic = false;
            for (Note root : accepted) {
                double ratio = candidate.frequency / root.frequency;
                int multiple = (int) Math.round(ratio);
                if (multiple >= 2 && Math.abs(ratio - multiple) < 0.08) {
                    harmonic = true;
                    break;
                }
            }
            if (harmonic) {
                continue;
            }
            accepted.add(candidate);
            if (accepted.size() >= MAX_NOTES) {
                break;
            }
        }
        return accepted;
    }

    private static double[] bandEnergies(double[] magnitude, int sampleRate) {
        double[] bands = new double[3];
        bands[0] = bandEnergy(magnitude, 40.0, 250.0, sampleRate);
        bands[1] = bandEnergy(magnitude, 250.0, 5000.0, sampleRate);
        bands[2] = bandEnergy(magnitude, 5000.0, 10000.0, sampleRate);
        return bands;
    }

    static double bandEnergy(double[] magnitude, double low, double high, int sampleRate) {
        int start = Math.max(1, (int) Math.floor(low * FFT_SIZE / sampleRate));
        int end = Math.min(magnitude.length - 1,
                (int) Math.ceil(high * FFT_SIZE / sampleRate));
        double energy = 0.0;
        for (int bin = start; bin <= end; bin++) {
            energy += magnitude[bin] * magnitude[bin];
        }
        return Math.sqrt(energy);
    }

    private static double spectralFlux(double[] current, double[] previous) {
        int count = Math.min(current.length, previous.length);
        double positive = 0.0;
        double total = 0.0;
        for (int bin = 1; bin < count; bin++) {
            double now = Math.log1p(current[bin]);
            double before = Math.log1p(previous[bin]);
            if (now > before) {
                positive += now - before;
            }
            total += now;
        }
        return total > 1.0e-9 ? positive / total : 0.0;
    }

    private static int pitchIndexForLed(int[] pitchLeds, int led) {
        for (int i = 0; i < pitchLeds.length; i++) {
            if (pitchLeds[i] == led) {
                return i;
            }
        }
        return -1;
    }

    private static int[] pitchLedOrder(Context context) {
        ArrayList<Integer> leds = new ArrayList<>();
        // Low notes on C (the 20-LED strip), then A, then B. This gives the
        // 36 LEDs a continuous low-to-high pitch order.
        int[] zones = {2, 0, 1};
        for (int zone : zones) {
            if (!Prefs.zoneOn(context, zone)) {
                continue;
            }
            for (int position = 0; position < GlyphLayout.ZONE_LEDS[zone]; position++) {
                leds.add(GlyphLayout.hwIndex(zone, position));
            }
        }
        int[] result = new int[leds.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = leds.get(i);
        }
        return result;
    }

    private static int[] zoneForLed() {
        int[] zones = new int[GlyphLayout.LEDS];
        Arrays.fill(zones, -1);
        for (int zone = 0; zone < 3; zone++) {
            for (int position = 0; position < GlyphLayout.ZONE_LEDS[zone]; position++) {
                zones[GlyphLayout.hwIndex(zone, position)] = zone;
            }
        }
        return zones;
    }

    private static String noteNames(Set<Integer> notes) {
        StringBuilder builder = new StringBuilder();
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int count = 0;
        for (Integer midi : notes) {
            if (count++ > 0) {
                builder.append(' ');
            }
            builder.append(names[Math.floorMod(midi, 12)]).append(midi / 12 - 1);
            if (count >= 16) {
                builder.append(" ...");
                break;
            }
        }
        return builder.toString();
    }

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double temp = real[i];
                real[i] = real[j];
                real[j] = temp;
                temp = imag[i];
                imag[i] = imag[j];
                imag[j] = temp;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            double wReal = Math.cos(angle);
            double wImag = Math.sin(angle);
            for (int offset = 0; offset < n; offset += length) {
                double curReal = 1.0;
                double curImag = 0.0;
                for (int k = 0; k < length / 2; k++) {
                    int even = offset + k;
                    int odd = offset + k + length / 2;
                    double oddReal = real[odd] * curReal - imag[odd] * curImag;
                    double oddImag = real[odd] * curImag + imag[odd] * curReal;
                    real[odd] = real[even] - oddReal;
                    imag[odd] = imag[even] - oddImag;
                    real[even] += oddReal;
                    imag[even] += oddImag;
                    double nextReal = curReal * wReal - curImag * wImag;
                    curImag = curReal * wImag + curImag * wReal;
                    curReal = nextReal;
                }
            }
        }
    }
}
