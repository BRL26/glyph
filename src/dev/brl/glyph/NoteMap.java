package dev.brl.glyph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Live note map for the persistent music visualizer.
 *
 * This is the same mapping as SoundCompiler's "Note map" mode: the strongest
 * tonal peaks in each analysis window become MIDI notes, every note owns one
 * LED, held notes stay lit, and released notes decay. It deliberately does not
 * produce a three-band loudness bar; the visualizer is a piano roll of the
 * audio that is currently playing.
 */
final class NoteMap {

    private static final int LEDS = GlyphLayout.LEDS;
    private static final int MAX_NOTES = 2;
    private static final double DECAY = 0.58;
    private static final int NOTE_HOLD_FRAMES = 6;
    // Visualizer FFT callbacks arrive at roughly 20 Hz, not 60 Hz. Four
    // analysis frames would mute every new note for 200 ms; one frame keeps
    // fast passages and chords responsive.
    private static final int MIN_ATTACK_FRAMES = 1;

    private final int[] pitchLeds;
    private final int[] zoneForLed;
    private final int[] zoneLevel;

    private final double[] ledBrightness = new double[LEDS];
    private final int[] hold = new int[LEDS];
    private final float[] lastStrength = new float[128];
    private final boolean[] present = new boolean[128];

    private int lastAttackFrame = -100;
    private int frameIndex;
    private float lastEnergy;

    NoteMap(int[] pitchLeds, int[] zoneForLed, int[] zoneLevel) {
        this.pitchLeds = pitchLeds == null ? new int[0] : pitchLeds.clone();
        this.zoneForLed = zoneForLed == null ? new int[0] : zoneForLed.clone();
        this.zoneLevel = zoneLevel == null ? new int[3] : zoneLevel.clone();
    }

    float lastEnergy() {
        return lastEnergy;
    }

    /**
     * @param magnitude    FFT magnitudes, index 0 = DC, last = Nyquist.
     * @param sampleRate   Real sample rate in Hz.
     * @param fftSize      Capture size used for the FFT (frequency bin width).
     * @param brightness   Global brightness percentage (0..100).
     * @return 36 values in frame_brightness order.
     */
    int[] process(double[] magnitude, int sampleRate, int fftSize, int brightness) {
        int[] frame = new int[LEDS];
        lastEnergy = 0f;
        if (magnitude == null || sampleRate <= 0 || fftSize < 2
                || magnitude.length < 3) {
            return frame;
        }

        frameIndex++;
        for (int led = 0; led < LEDS; led++) {
            ledBrightness[led] *= DECAY;
            if (ledBrightness[led] < 3.0) {
                ledBrightness[led] = 0.0;
            }
            if (hold[led] > 0) {
                hold[led]--;
            }
        }

        double peak = 0.0;
        for (int bin = 1; bin < magnitude.length; bin++) {
            if (magnitude[bin] > peak) {
                peak = magnitude[bin];
            }
        }
        lastEnergy = (float) Math.min(1.0, peak / 128.0);
        Arrays.fill(present, false);
        if (peak >= 1.0e-5) {
            List<Note> notes = detectNotes(magnitude, sampleRate, fftSize);
            for (Note note : notes) {
                present[note.midi] = true;
                boolean attack = lastStrength[note.midi] <= 0f
                        || note.strength > lastStrength[note.midi] * 1.35f + 0.02f;
                lastStrength[note.midi] = Math.max((float) note.strength,
                        lastStrength[note.midi] * 0.92f);
                // A chord must be able to start several notes in the same
                // frame; the minimum attack spacing only applies to later
                // analysis frames.
                if (!attack || (frameIndex != lastAttackFrame
                        && frameIndex - lastAttackFrame < MIN_ATTACK_FRAMES)) {
                    continue;
                }
                lastAttackFrame = frameIndex;
                int led = ledForMidi(note.midi);
                if (led < 0 || zoneForLed.length != LEDS || zoneForLed[led] < 0) {
                    continue;
                }
                hold[led] = NOTE_HOLD_FRAMES;
            }
        }
        for (int midi = 0; midi < 128; midi++) {
            if (!present[midi]) {
                lastStrength[midi] = 0f;
            }
        }

        for (int led = 0; led < LEDS; led++) {
            if (hold[led] <= 0 || zoneForLed.length != LEDS) {
                continue;
            }
            int zone = zoneForLed[led];
            if (zone < 0 || zone >= 3) {
                continue;
            }
            // Apply the global brightness once, in writeFrame().
            int value = GlyphLayout.zoneValue(zoneLevel[zone], 100);
            ledBrightness[led] = Math.max(ledBrightness[led], value);
            int pitchIndex = pitchIndexForLed(led);
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

        writeFrame(frame, brightness);
        return frame;
    }

    private void writeFrame(int[] frame, int brightness) {
        int gain = Math.max(0, Math.min(100, brightness));
        for (int led = 0; led < LEDS; led++) {
            int value = (int) Math.round(ledBrightness[led] * gain / 100.0);
            if (value < 0) {
                value = 0;
            } else if (value > GlyphLayout.MAX_VALUE) {
                value = GlyphLayout.MAX_VALUE;
            }
            frame[led] = value;
        }
    }

    private int ledForMidi(int midi) {
        if (pitchLeds.length == 0) {
            return -1;
        }
        int pitchPosition = midi - 48;
        while (pitchPosition < 0) {
            pitchPosition += 12;
        }
        while (pitchPosition > 35) {
            pitchPosition -= 12;
        }
        int index = (int) Math.round(
                pitchPosition * (pitchLeds.length - 1) / 35.0);
        index = Math.max(0, Math.min(pitchLeds.length - 1, index));
        return pitchLeds[index];
    }

    private int pitchIndexForLed(int led) {
        for (int i = 0; i < pitchLeds.length; i++) {
            if (pitchLeds[i] == led) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Android returns the FFT as DC, Nyquist, then interleaved real/imaginary
     * pairs. Keep all bins so the peak detector can see DC and Nyquist too.
     */
    static double[] fftMagnitudes(byte[] fft) {
        if (fft == null || fft.length < 4) {
            return new double[0];
        }
        int n = fft.length;
        int bins = n / 2;
        double[] magnitude = new double[bins + 1];
        magnitude[0] = Math.abs(fft[0]);
        magnitude[bins] = Math.abs(fft[1]);
        for (int bin = 1; bin < bins; bin++) {
            int index = bin * 2;
            if (index + 1 >= n) {
                break;
            }
            magnitude[bin] = Math.hypot(fft[index], fft[index + 1]);
        }
        return magnitude;
    }

    private static List<Note> detectNotes(double[] magnitude, int sampleRate,
                                          int fftSize) {
        int minBin = Math.max(1, (int) (70.0 * fftSize / sampleRate));
        int maxBin = Math.min(magnitude.length - 2,
                (int) (5000.0 * fftSize / sampleRate));
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
            double frequency = (bin + delta) * sampleRate / (double) fftSize;
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
            if (!harmonic) {
                accepted.add(candidate);
                if (accepted.size() >= MAX_NOTES) {
                    break;
                }
            }
        }
        return accepted;
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
}
