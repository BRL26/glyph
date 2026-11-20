package dev.brl.glyph;

public final class NoteMapTests {

    private static int checks;

    private NoteMapTests() {
    }

    public static void main(String[] args) {
        fftParsingTests();
        noteMapTests();
        System.out.println("PASS: " + checks + " note-map assertions");
    }

    private static void fftParsingTests() {
        byte[] fft = new byte[1024];
        fft[0] = 10;
        fft[1] = 20;
        fft[2] = 3;
        fft[3] = 4;
        double[] magnitude = NoteMap.fftMagnitudes(fft);
        check(magnitude.length == 513, "fft keeps DC plus Nyquist slot");
        check(magnitude[0] == 10.0, "fft reads DC");
        check(magnitude[512] == 20.0, "fft reads Nyquist");
        check(magnitude[1] == 5.0, "fft reads interleaved real/imaginary");
        check(NoteMap.fftMagnitudes(null).length == 0, "null fft is empty");
        check(NoteMap.fftMagnitudes(new byte[2]).length == 0, "short fft is empty");
    }

    private static void noteMapTests() {
        int[] pitchLeds = fullPitchLeds();
        int[] zones = zonesForLeds();
        int[] levels = {100, 100, 100};
        NoteMap map = new NoteMap(pitchLeds, zones, levels);

        int[] silence = map.process(new double[513], 48000, 1024, 100);
        check(countLit(silence) == 0, "silence is dark");
        check(map.lastEnergy() == 0f, "silence has no energy");

        int[] note = map.process(spectrum(440f, 1.0), 48000, 1024, 100);
        check(note[30] == GlyphLayout.MAX_VALUE, "440 Hz note gets its own LED");
        check(note[19] > 0 && note[19] < GlyphLayout.MAX_VALUE,
                "lower neighbour is dim");
        check(note[29] > 0 && note[29] < GlyphLayout.MAX_VALUE,
                "upper neighbour is dim");
        check(countLit(note) == 3, "one note is one LED plus two neighbours");
        check(map.lastEnergy() > 0f, "note frame reports energy");

        int[] faded = note;
        for (int i = 0; i < 30; i++) {
            faded = map.process(new double[513], 48000, 1024, 100);
        }
        check(countLit(faded) == 0, "released note fades to dark");

        NoteMap chord = new NoteMap(pitchLeds, zones, levels);
        chord.process(spectrum(440f, 1.0), 48000, 1024, 100);
        chord.process(new double[513], 48000, 1024, 100);
        int[] chordFrame = chord.process(chordSpectrum(), 48000, 1024, 100);
        check(chordFrame[30] == GlyphLayout.MAX_VALUE, "chord keeps the first note");
        check(chordFrame[22] == GlyphLayout.MAX_VALUE, "chord adds the second note");

        NoteMap dim = new NoteMap(pitchLeds, zones, levels);
        int[] dimFrame = dim.process(spectrum(440f, 1.0), 48000, 1024, 50);
        check(dimFrame[30] == 128, "brightness is applied once");

        int[] bOnly = new int[5];
        for (int pos = 0; pos < 5; pos++) {
            bOnly[pos] = GlyphLayout.hwIndex(1, pos);
        }
        NoteMap small = new NoteMap(bOnly, zones, levels);
        int[] smallFrame = small.process(spectrum(440f, 1.0), 48000, 1024, 100);
        check(smallFrame[33] == GlyphLayout.MAX_VALUE,
                "note maps onto a five-LED strip");
        check(countLit(smallFrame) <= 3, "small strip never writes outside itself");
        check(smallFrame[30] == 0, "small strip leaves the old A LED dark");

        NoteMap empty = new NoteMap(new int[0], zones, levels);
        check(countLit(empty.process(spectrum(440f, 1.0), 48000, 1024, 100)) == 0,
                "empty note map is dark");
        check(countLit(map.process(null, 48000, 1024, 100)) == 0,
                "null spectrum is dark");
    }

    private static int[] fullPitchLeds() {
        int[] leds = new int[GlyphLayout.LEDS];
        int index = 0;
        for (int zone : new int[]{2, 0, 1}) {
            for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
                leds[index++] = GlyphLayout.hwIndex(zone, pos);
            }
        }
        return leds;
    }

    private static int[] zonesForLeds() {
        int[] zones = new int[GlyphLayout.LEDS];
        for (int led = 0; led < zones.length; led++) {
            zones[led] = -1;
        }
        for (int zone = 0; zone < 3; zone++) {
            for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
                zones[GlyphLayout.hwIndex(zone, pos)] = zone;
            }
        }
        return zones;
    }

    private static double[] spectrum(float hz, double amplitude) {
        double[] magnitude = new double[513];
        int bin = Math.round(hz * 1024f / 48000f);
        magnitude[bin] = 100.0 * amplitude;
        magnitude[bin - 1] = 20.0 * amplitude;
        magnitude[bin + 1] = 20.0 * amplitude;
        return magnitude;
    }

    private static double[] chordSpectrum() {
        double[] magnitude = new double[513];
        magnitude[9] = 100.0;
        magnitude[8] = 20.0;
        magnitude[10] = 20.0;
        magnitude[14] = 100.0;
        magnitude[13] = 20.0;
        magnitude[15] = 20.0;
        return magnitude;
    }

    private static int countLit(int[] frame) {
        int lit = 0;
        for (int value : frame) {
            if (value > 0) {
                lit++;
            }
        }
        return lit;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
