package dev.brl.glyph;

import java.util.Random;

/**
 * Faithful Java port of the website auto generators at
 * better-nothing-glyph-composer (NPC 3a).
 *
 * The website's 3a ledIdx is [[0..19], [20..30], [31..35]], which is exactly
 * the driver's frame order: C, A, B. The generators therefore write the same
 * 36 physical columns as the site; only the 0..4095 site range is scaled to
 * the app's 0..255 frame register.
 *
 * Beats/Smooth: autoGen1.newFuncTest (antiFlicker false/true)
 * Frequency: autoGen2.autoGenerateGlyphsViaFrequency
 * Pattern: autoGen3.autoGenerateGlyphsViaPattern
 */
public final class AutoGen {

    public static final int MODE_BEATS = 1;
    public static final int MODE_SMOOTH = 2;
    public static final int MODE_FREQUENCY = 3;
    public static final int MODE_PATTERN = 4;

    private static final int LEDS = 36;
    private static final int ZONE_C = 0;
    private static final int ZONE_A = 1;
    private static final int ZONE_B = 2;
    private static final int[] ZONE_LEDS = {20, 11, 5};
    private static final int[] ZONE_START = {0, 20, 31};
    private static final int MAX_VALUE = 4095;
    private static final int MAX_FRAMES = 15 * 60;
    private static final int BUFFER_SIZE = MeydaFeatures.BUFFER_SIZE;

    private AutoGen() {
    }

    public static int[][] render(short[] pcm, int pcmCount, int sampleRate, int mode) {
        return render(pcm, pcmCount, sampleRate, mode, 0x6C796768L);
    }

    public static int[][] render(short[] pcm, int pcmCount, int sampleRate, int mode,
                                 long seed) {
        if (pcm == null || pcmCount < BUFFER_SIZE || sampleRate <= 0) {
            return new int[0][];
        }
        int hop = Math.max(1, (int) Math.floor((16.666 / 1000.0) * sampleRate));
        int totalFrames = (int) Math.floor((pcmCount - BUFFER_SIZE) / (double) hop) + 1;
        if (totalFrames > MAX_FRAMES) {
            totalFrames = MAX_FRAMES;
        }
        if (totalFrames < 1) {
            return new int[0][];
        }

        MeydaFeatures analyzer = new MeydaFeatures(sampleRate);
        MeydaFeatures.Frame[] features = new MeydaFeatures.Frame[totalFrames];
        for (int frame = 0; frame < totalFrames; frame++) {
            features[frame] = analyzer.analyze(pcm, pcmCount, frame * hop);
        }

        int[][] raw;
        if (mode == MODE_FREQUENCY) {
            raw = frequency(features, totalFrames, sampleRate);
        } else if (mode == MODE_PATTERN) {
            raw = pattern(features, totalFrames, sampleRate, hop, seed);
        } else {
            raw = beats(features, totalFrames, sampleRate, hop, mode == MODE_SMOOTH,
                    seed);
        }
        return scale(raw);
    }

    /**
     * autoGen1. On a three-zone device the default distribution is
     * lows=0, mids=1, highs=2. The mids group therefore owns one zone and the
     * highs group owns the other two, with 250..2000, 2000..11000 and
     * 11000..20000 Hz sub-bands.
     */
    private static int[][] beats(MeydaFeatures.Frame[] features, int totalFrames,
                                 int sampleRate, int hop, boolean antiFlicker, long seed) {
        Random random = new Random(seed);
        int[] order = {ZONE_C, ZONE_A, ZONE_B};
        int reassignInterval = Math.max(1,
                (int) Math.floor((sampleRate / (double) hop) / 2.0));

        shuffle(order, random);
        double[] loudnessValues = new double[totalFrames];
        double maxLoudness = 0.0;
        for (int frame = 0; frame < totalFrames; frame++) {
            loudnessValues[frame] = features[frame].loudness;
            if (features[frame].loudness > maxLoudness) {
                maxLoudness = features[frame].loudness;
            }
        }
        double[] sorted = loudnessValues.clone();
        java.util.Arrays.sort(sorted);
        int percentileIndex = Math.min(sorted.length - 1,
                (int) Math.floor(sorted.length * 0.90));
        double heavyThreshold = sorted.length == 0 ? 0.0 : sorted[percentileIndex];
        double dynamicThreshold = 0.5 * maxLoudness;

        double[] current = new double[LEDS];
        int[][] frames = new int[totalFrames][LEDS];
        int[] target = new int[LEDS];

        for (int frame = 0; frame < totalFrames; frame++) {
            if (frame % reassignInterval == 0) {
                shuffle(order, random);
            }
            java.util.Arrays.fill(target, 0);
            MeydaFeatures.Frame feature = features[frame];
            double loudness = feature.loudness;

            if (loudness > heavyThreshold) {
                // autoGen1's super-heavy path: one physical LED, direction
                // alternating every ten frames.
                int direction = (frame / 10) % 2 == 0 ? 1 : -1;
                int led = (frame / 10) % LEDS;
                if (direction < 0) {
                    led = LEDS - led - 1;
                }
                if (led >= 0 && led < LEDS) {
                    target[led] = MAX_VALUE;
                }
            } else if (loudness >= dynamicThreshold && loudness > 0.0) {
                fillZone(target, order[0], clamp(Math.floor(
                        MeydaFeatures.bandEnergy(feature.amplitude, 250.0, 2000.0,
                                sampleRate, BUFFER_SIZE) / loudness * MAX_VALUE)));
                fillZone(target, order[1], clamp(Math.floor(
                        MeydaFeatures.bandEnergy(feature.amplitude, 2000.0, 11000.0,
                                sampleRate, BUFFER_SIZE) / loudness * MAX_VALUE)));
                fillZone(target, order[2], clamp(Math.floor(
                        MeydaFeatures.bandEnergy(feature.amplitude, 11000.0, 20000.0,
                                sampleRate, BUFFER_SIZE) / loudness * MAX_VALUE)));
            }

            int[] out = frames[frame];
            if (antiFlicker) {
                for (int led = 0; led < LEDS; led++) {
                    current[led] = clamp(Math.floor(current[led] * 0.9
                            + target[led] * 0.1));
                    out[led] = (int) current[led];
                }
            } else {
                System.arraycopy(target, 0, out, 0, LEDS);
            }
        }
        return frames;
    }

    /**
     * autoGen2's two-pass frequency generator. The default distribution on a
     * 3-zone device puts all three zones in one mid group, split into
     * 500..1000, 1000..1500 and 1500..2000 Hz sub-bands. Its maxActiveLEDs is
     * floor(3 * 0.8) == 2, so the first qualifying zone normally owns the
     * frame; later zones are skipped once that many LEDs are active.
     */
    private static int[][] frequency(MeydaFeatures.Frame[] features, int totalFrames,
                                     int sampleRate) {
        final int maxActiveLeds = Math.max(1, (int) Math.floor(3 * 0.8));
        double[] averageEnergies = new double[3];
        int energyCount = 0;
        double totalAmplitude = 0.0;

        for (int frame = 0; frame < totalFrames; frame++) {
            MeydaFeatures.Frame feature = features[frame];
            totalAmplitude += feature.loudness;
            for (int zone = 0; zone < 3; zone++) {
                averageEnergies[zone] += MeydaFeatures.bandEnergy(feature.amplitude,
                        500.0 + zone * 500.0, 1000.0 + zone * 500.0,
                        sampleRate, BUFFER_SIZE);
                energyCount++;
            }
        }
        double threshold = energyCount == 0 ? 0.0
                : (sum(averageEnergies) / energyCount) * 0.7;
        double amplitudeThreshold = totalFrames == 0 ? 0.0
                : (totalAmplitude / totalFrames) * 0.6;

        double[] current = new double[LEDS];
        int[] sustain = new int[LEDS];
        int[][] frames = new int[totalFrames][LEDS];

        for (int frame = 0; frame < totalFrames; frame++) {
            MeydaFeatures.Frame feature = features[frame];
            int[] out = frames[frame];
            if (feature.loudness < amplitudeThreshold) {
                // The site pushes a zero frame here and does not run decay.
                continue;
            }
            int active = 0;
            for (int zone = 0; zone < 3 && active < maxActiveLeds; zone++) {
                double energy = MeydaFeatures.bandEnergy(feature.amplitude,
                        500.0 + zone * 500.0, 1000.0 + zone * 500.0,
                        sampleRate, BUFFER_SIZE);
                if (energy <= threshold || feature.loudness <= 0.0) {
                    continue;
                }
                int brightness = clamp(Math.floor(
                        (energy / feature.loudness) * MAX_VALUE));
                for (int led = ZONE_START[zone];
                     led < ZONE_START[zone] + ZONE_LEDS[zone]; led++) {
                    if (sustain[led] > 0) {
                        continue;
                    }
                    sustain[led] = 5;
                    current[led] = brightness;
                    out[led] = brightness;
                    active++;
                }
            }

            for (int led = 0; led < LEDS; led++) {
                if (sustain[led] > 0) {
                    sustain[led]--;
                } else {
                    current[led] *= 0.85;
                }
                out[led] = clamp(Math.floor(current[led]));
            }
        }
        return frames;
    }

    /**
     * autoGen3's pattern generator. The website's beat analyzer needs 12,000
     * analysis frames before it starts emitting beats; the app caps clips at
     * 15 seconds (900 frames), so the live path is the website's 120 BPM
     * fallback grid, followed by its wave/spiral/bounce/pulse/chase/ripple
     * branches and per-LED sustain/decay. This is not the old approximation:
     * all six branches and the site's 21-LED activation cap are present.
     */
    private static int[][] pattern(MeydaFeatures.Frame[] features, int totalFrames,
                                   int sampleRate, int hop, long seed) {
        Random random = new Random(seed);
        int beatInterval = Math.max(1, (int) Math.floor(
                ((60.0 / 120.0) * sampleRate) / hop));
        int maxActiveLeds = (int) Math.floor(LEDS * 0.6);
        int sustainDuration = 8;
        int patternLength = 4;
        int patternPosition = 0;
        int patternDirection = 1;
        int patternChangeCounter = 0;
        int currentPattern = 0; // wave
        int lastBeatFrame = -1;

        double[] energyHistory = new double[10];
        double[] bassHistory = new double[10];
        double[] highHistory = new double[10];
        int energyCount = 0;
        int bassCount = 0;
        int highCount = 0;

        double[] current = new double[LEDS];
        int[] sustain = new int[LEDS];
        int[][] frames = new int[totalFrames][LEDS];

        for (int frame = 0; frame < totalFrames; frame++) {
            MeydaFeatures.Frame feature = features[frame];
            int[] out = frames[frame];
            if (feature.rms == 0f) {
                // Meyda rms === 0 makes the site skip the whole body for this
                // frame, including decay. It pushes a dark frame and leaves
                // the previous sustain/brightness state untouched.
                continue;
            }

            double energy = feature.rms;
            double bassEnergy = MeydaFeatures.bandEnergy(feature.amplitude,
                    20.0, 250.0, sampleRate, BUFFER_SIZE);
            double highEnergy = MeydaFeatures.bandEnergy(feature.amplitude,
                    2000.0, 8000.0, sampleRate, BUFFER_SIZE);
            energyCount = pushHistory(energyHistory, energyCount, energy);
            bassCount = pushHistory(bassHistory, bassCount, bassEnergy);
            highCount = pushHistory(highHistory, highCount, highEnergy);

            double avgEnergy = average(energyHistory, energyCount);
            double avgBass = average(bassHistory, bassCount);
            double avgHigh = average(highHistory, highCount);
            double energyRatio = energy / Math.max(avgEnergy, 0.1);
            double bassRatio = bassEnergy / Math.max(avgBass, 0.1);
            double highRatio = highEnergy / Math.max(avgHigh, 0.1);

            int event = EVENT_NONE;
            double intensity = 0.0;
            boolean isBeat = frame >= beatInterval && frame % beatInterval == 0;
            if (isBeat && frame != lastBeatFrame) {
                event = EVENT_BEAT;
                intensity = Math.min(energyRatio * 1.5, 2.5);
                lastBeatFrame = frame;
            } else if (energyRatio > 1.3 || bassRatio > 1.5 || highRatio > 1.4) {
                event = EVENT_ENERGY;
                intensity = Math.min(Math.max(energyRatio,
                        Math.max(bassRatio, highRatio)), 2.0);
            }

            if (event != EVENT_NONE) {
                int progressionSpeed = (int) Math.floor(intensity);
                for (int i = 0; i < progressionSpeed; i++) {
                    patternPosition = (patternPosition + 1) % patternLength;
                }
                patternChangeCounter++;

                int patternMod = 6 + (int) Math.floor(intensity * 2.0);
                if (patternMod > 0 && patternChangeCounter % patternMod == 0) {
                    int next = selectPatternBasedOnAudio(bassRatio, highRatio,
                            energyRatio, currentPattern, random);
                    if (next != currentPattern) {
                        currentPattern = next;
                    }
                }
                int directionMod = 3 + (int) Math.floor(intensity);
                if (directionMod > 0 && patternChangeCounter % directionMod == 0) {
                    patternDirection *= -1;
                }
            }

            double brightnessMultiplier;
            if (event == EVENT_BASS) {
                brightnessMultiplier = 1.5 + (bassRatio - 1.0) * 0.5;
            } else if (event == EVENT_BEAT) {
                brightnessMultiplier = 1.2 + (energyRatio - 1.0) * 0.3;
            } else if (event == EVENT_NOTE) {
                brightnessMultiplier = 1.0 + (highRatio - 1.0) * 0.4;
            } else if (event == EVENT_ENERGY) {
                brightnessMultiplier = 1.0 + intensity * 0.5;
            } else {
                brightnessMultiplier = 0.8;
            }

            double effectiveEnergy = Math.max(energy, 0.1);
            int brightness = clamp(Math.floor(effectiveEnergy * MAX_VALUE
                    * brightnessMultiplier));
            int[] selectedZones = selectZonesBasedOnAudio(bassRatio, highRatio,
                    energyRatio, patternPosition);
            int[] physical = new int[LEDS];
            drawPattern(currentPattern, selectedZones, selectedZones.length,
                    brightness, physical, current, sustain, sustainDuration,
                    maxActiveLeds, patternDirection, intensity);

            if (patternChangeCounter % 20 == 0 && energyRatio < 0.8) {
                activateAllLeds((int) Math.floor(brightness * 0.2), physical,
                        current, sustain, sustainDuration);
            }

            for (int led = 0; led < LEDS; led++) {
                if (sustain[led] > 0) {
                    sustain[led]--;
                } else {
                    current[led] *= 0.9;
                }
                out[led] = clamp(Math.floor(current[led]));
            }
        }
        return frames;
    }

    private static final int EVENT_NONE = 0;
    private static final int EVENT_BASS = 1;
    private static final int EVENT_BEAT = 2;
    private static final int EVENT_NOTE = 3;
    private static final int EVENT_ENERGY = 4;

    private static int pushHistory(double[] history, int count, double value) {
        if (count < history.length) {
            history[count] = value;
            return count + 1;
        }
        for (int i = 1; i < history.length; i++) {
            history[i - 1] = history[i];
        }
        history[history.length - 1] = value;
        return history.length;
    }

    private static double average(double[] history, int count) {
        if (count == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < count; i++) {
            total += history[i];
        }
        return total / count;
    }

    private static int selectPatternBasedOnAudio(double bassRatio, double highRatio,
                                                  double energyRatio, int current,
                                                  Random random) {
        if (bassRatio > 1.5) {
            return random.nextBoolean() ? 2 : 3; // bounce, pulse
        }
        if (highRatio > 1.4) {
            return random.nextBoolean() ? 4 : 5; // chase, ripple
        }
        if (energyRatio > 1.2 && Math.abs(bassRatio - highRatio) < 0.3) {
            return random.nextBoolean() ? 0 : 1; // wave, spiral
        }
        int[] available = new int[5];
        int count = 0;
        for (int pattern = 0; pattern < 6; pattern++) {
            if (pattern != current) {
                available[count++] = pattern;
            }
        }
        return available[random.nextInt(count)];
    }

    private static int[] selectZonesBasedOnAudio(double bassRatio, double highRatio,
                                                  double energyRatio, int patternPosition) {
        int[] selected = new int[3];
        int count = 0;
        if (bassRatio > 1.3) {
            selected[count++] = 0;
        }
        if (highRatio > 1.3 && !contains(selected, count, 2)) {
            selected[count++] = 2;
        }
        if (energyRatio > 1.2 && Math.abs(bassRatio - highRatio) < 0.3
                && !contains(selected, count, 1)) {
            selected[count++] = 1;
        }
        if (count == 0) {
            selected[count++] = patternPosition % 3;
        }
        int[] result = new int[count];
        System.arraycopy(selected, 0, result, 0, count);
        return result;
    }

    private static boolean contains(int[] values, int count, int value) {
        for (int i = 0; i < count; i++) {
            if (values[i] == value) {
                return true;
            }
        }
        return false;
    }

    private static void drawPattern(int pattern, int[] zones, int zoneCount,
                                    int brightness, int[] physical, double[] current,
                                    int[] sustain, int sustainDuration, int maxActiveLeds,
                                    int direction, double intensity) {
        switch (pattern) {
            case 1:
                drawSpiral(zones, zoneCount, brightness, physical, current, sustain,
                        sustainDuration, maxActiveLeds, intensity);
                break;
            case 2:
                drawBounce(zones, zoneCount, brightness, physical, current, sustain,
                        sustainDuration, maxActiveLeds, intensity);
                break;
            case 3:
                drawPulse(zones, zoneCount, brightness, physical, current, sustain,
                        sustainDuration, maxActiveLeds, intensity);
                break;
            case 4:
                drawChase(zones, zoneCount, brightness, physical, current, sustain,
                        sustainDuration, maxActiveLeds, intensity);
                break;
            case 5:
                drawRipple(zones, zoneCount, brightness, physical, current, sustain,
                        sustainDuration, maxActiveLeds, intensity);
                break;
            default:
                drawWave(zones, zoneCount, brightness, physical, current, sustain,
                        sustainDuration, maxActiveLeds, intensity);
                break;
        }
    }

    private static void drawWave(int[] zones, int zoneCount, int brightness,
                                 int[] physical, double[] current, int[] sustain,
                                 int sustainDuration, int maxActiveLeds, double intensity) {
        int waveWidth = Math.max(1, (int) Math.floor(zoneCount / 2.0));
        int effectiveWidth = (int) Math.floor(waveWidth * (1 + intensity * 0.5));
        int activated = 0;
        for (int i = 0; i < effectiveWidth && activated < maxActiveLeds; i++) {
            int zone = zones[i % zoneCount];
            if (sustain[ZONE_START[zone]] > 0) {
                continue;
            }
            int value = (int) Math.floor(brightness * (1.0 - i / (double) effectiveWidth));
            for (int led = ZONE_START[zone];
                 led < ZONE_START[zone] + ZONE_LEDS[zone] && activated < maxActiveLeds;
                 led++) {
                sustain[led] = sustainDuration;
                current[led] = value;
                physical[led] = value;
                activated++;
            }
        }
    }

    private static void drawSpiral(int[] zones, int zoneCount, int brightness,
                                   int[] physical, double[] current, int[] sustain,
                                   int sustainDuration, int maxActiveLeds, double intensity) {
        int center = zoneCount / 2;
        int radius = zoneCount / 2 + (int) Math.floor(intensity);
        int activated = 0;
        for (int i = 0; i < zoneCount && activated < maxActiveLeds; i++) {
            int distance = Math.abs(i - center);
            if (distance > radius) {
                continue;
            }
            int zone = zones[i];
            if (sustain[ZONE_START[zone]] > 0) {
                continue;
            }
            int value = (int) Math.floor(brightness
                    * (1.0 - distance / (double) Math.max(radius, 1)));
            for (int led = ZONE_START[zone];
                 led < ZONE_START[zone] + ZONE_LEDS[zone] && activated < maxActiveLeds;
                 led++) {
                sustain[led] = sustainDuration;
                current[led] = value;
                physical[led] = value;
                activated++;
            }
        }
    }

    private static void drawBounce(int[] zones, int zoneCount, int brightness,
                                   int[] physical, double[] current, int[] sustain,
                                   int sustainDuration, int maxActiveLeds, double intensity) {
        int position = zoneCount / 2;
        int zone = zones[position % zoneCount];
        if (sustain[ZONE_START[zone]] > 0) {
            return;
        }
        int value = (int) Math.floor(brightness * (1 + intensity * 0.3));
        int activated = 0;
        for (int led = ZONE_START[zone];
             led < ZONE_START[zone] + ZONE_LEDS[zone] && activated < maxActiveLeds; led++) {
            sustain[led] = sustainDuration;
            current[led] = value;
            physical[led] = value;
            activated++;
        }
    }

    private static void drawPulse(int[] zones, int zoneCount, int brightness,
                                  int[] physical, double[] current, int[] sustain,
                                  int sustainDuration, int maxActiveLeds, double intensity) {
        int value = (int) Math.floor(brightness
                * Math.abs(Math.sin(intensity * Math.PI)));
        int activated = 0;
        for (int i = 0; i < zoneCount && activated < maxActiveLeds; i++) {
            int zone = zones[i];
            for (int led = ZONE_START[zone];
                 led < ZONE_START[zone] + ZONE_LEDS[zone] && activated < maxActiveLeds;
                 led++) {
                sustain[led] = sustainDuration;
                current[led] = value;
                physical[led] = value;
                activated++;
            }
        }
    }

    private static void drawChase(int[] zones, int zoneCount, int brightness,
                                  int[] physical, double[] current, int[] sustain,
                                  int sustainDuration, int maxActiveLeds, double intensity) {
        int chaseLength = Math.max(2, (int) Math.floor(zoneCount
                * (1 + intensity * 0.5)));
        int activated = 0;
        for (int i = 0; i < chaseLength && activated < maxActiveLeds; i++) {
            int zone = zones[i % zoneCount];
            if (sustain[ZONE_START[zone]] > 0) {
                continue;
            }
            int value = (int) Math.floor(brightness
                    * (1.0 - i / (double) chaseLength));
            for (int led = ZONE_START[zone];
                 led < ZONE_START[zone] + ZONE_LEDS[zone] && activated < maxActiveLeds;
                 led++) {
                sustain[led] = sustainDuration;
                current[led] = value;
                physical[led] = value;
                activated++;
            }
        }
    }

    private static void drawRipple(int[] zones, int zoneCount, int brightness,
                                   int[] physical, double[] current, int[] sustain,
                                   int sustainDuration, int maxActiveLeds, double intensity) {
        int maxRadius = (int) Math.floor(zoneCount / 2.0);
        if (maxRadius <= 0) {
            return;
        }
        int rippleRadius = (int) Math.floor(maxRadius * intensity);
        int rippleCount = Math.max(1, (int) Math.floor(intensity));
        int activated = 0;
        for (int ripple = 0; ripple < rippleCount; ripple++) {
            int currentRadius = (rippleRadius + ripple * 2) % maxRadius;
            for (int i = 0; i < zoneCount && activated < maxActiveLeds; i++) {
                int distance = Math.abs(i - zoneCount / 2);
                if (distance != currentRadius) {
                    continue;
                }
                int zone = zones[i];
                if (sustain[ZONE_START[zone]] > 0) {
                    continue;
                }
                int value = (int) Math.floor(brightness
                        * (1.0 - currentRadius / (double) maxRadius)
                        * (1.0 - ripple * 0.3));
                for (int led = ZONE_START[zone];
                     led < ZONE_START[zone] + ZONE_LEDS[zone] && activated < maxActiveLeds;
                     led++) {
                    sustain[led] = sustainDuration;
                    current[led] = value;
                    physical[led] = value;
                    activated++;
                }
            }
        }
    }

    private static void activateAllLeds(int brightness, int[] physical,
                                        double[] current, int[] sustain,
                                        int sustainDuration) {
        for (int zone = 0; zone < 3; zone++) {
            for (int led = ZONE_START[zone];
                 led < ZONE_START[zone] + ZONE_LEDS[zone]; led++) {
                sustain[led] = sustainDuration;
                current[led] = brightness;
                physical[led] = brightness;
            }
        }
    }

    private static int clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return (int) Math.max(0, Math.min(MAX_VALUE, value));
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total;
    }

    private static void fillZone(int[] frame, int zone, int value) {
        if (zone < 0 || zone >= 3) {
            return;
        }
        for (int led = ZONE_START[zone]; led < ZONE_START[zone] + ZONE_LEDS[zone]; led++) {
            frame[led] = value;
        }
    }

    private static void shuffle(int[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int index = random.nextInt(i + 1);
            int temp = values[i];
            values[i] = values[index];
            values[index] = temp;
        }
    }

    private static int[][] scale(int[][] raw) {
        int[][] scaled = new int[raw.length][LEDS];
        for (int frame = 0; frame < raw.length; frame++) {
            for (int led = 0; led < LEDS; led++) {
                scaled[frame][led] = (int) Math.round(raw[frame][led] * 255.0 / MAX_VALUE);
            }
        }
        return scaled;
    }
}
