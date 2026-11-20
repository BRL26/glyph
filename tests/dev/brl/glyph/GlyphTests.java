package dev.brl.glyph;

import java.util.Arrays;

public final class GlyphTests {

    private static int checks;

    private GlyphTests() {
    }

    public static void main(String[] args) {
        layoutTests();
        behaviorTests();
        progressTests();
        previewTests();
        effectsTests();
        blendTests();
        frameClaimTests();
        effectOrderTests();
        spectrumMaskTests();
        defaultsTests();
        spectrumTests();
        audioSwitchTests();
        System.out.println("PASS: " + checks + " assertions");
    }

    private static void layoutTests() {
        check(GlyphLayout.ZONE_LEDS[0] == 11, "A has 11 LEDs");
        check(GlyphLayout.ZONE_LEDS[1] == 5, "B has 5 LEDs");
        check(GlyphLayout.ZONE_LEDS[2] == 20, "C has 20 LEDs");
        check(GlyphLayout.hwIndex(0, 0) == 30, "A bottom is hardware 30");
        check(GlyphLayout.hwIndex(0, 10) == 20, "A top is hardware 20");
        check(GlyphLayout.hwIndex(1, 0) == 31, "B bottom is hardware 31");
        check(GlyphLayout.hwIndex(1, 4) == 35, "B top-left is hardware 35");
        check(GlyphLayout.hwIndex(2, 0) == 0, "C bottom is hardware 0");
        check(GlyphLayout.hwIndex(2, 19) == 19, "C top-right is hardware 19");
        check(GlyphLayout.hwIndex(-1, 0) == -1, "negative zone is invalid");
        check(GlyphLayout.hwIndex(3, 0) == -1, "zone 3 is invalid");
        check(GlyphLayout.hwIndex(2, -5) == 0, "negative position clamps to bottom");
        check(GlyphLayout.hwIndex(2, 99) == 19, "high position clamps to top");

        boolean[] seen = new boolean[36];
        for (int zone = 0; zone < 3; zone++) {
            for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
                int hw = GlyphLayout.hwIndex(zone, pos);
                check(hw >= 0 && hw < 36, "hardware index in range");
                check(!seen[hw], "hardware index used once");
                seen[hw] = true;
            }
        }
        for (boolean value : seen) {
            check(value, "all 36 hardware indices covered");
        }

        check(GlyphLayout.MAX_VALUE == 255, "frame values fit the driver's 8-bit register");
        check(GlyphLayout.zoneValue(100, 100) == 255, "full zone value");
        check(GlyphLayout.zoneValue(50, 100) == 128, "50% zone value");
        check(GlyphLayout.zoneValue(50, 50) == 64, "50% level and brightness");
        check(GlyphLayout.zoneValue(-1, 200) == 0, "zone value clamps");

        check(GlyphLayout.litCount(20, 0) == 0, "0% has no LEDs");
        check(GlyphLayout.litCount(20, 1) == 1, "1% shows one LED");
        check(GlyphLayout.litCount(20, 50) == 10, "20 at 50%");
        check(GlyphLayout.litCount(20, 100) == 20, "20 at 100%");
        check(GlyphLayout.litCount(11, 50) == 6, "11 at 50% rounds up");
        check(GlyphLayout.litCount(5, 50) == 3, "5 at 50% rounds up");

        int[] sound = GlyphLayout.soundFrame(new boolean[]{true, false, true},
                new int[]{100, 100, 100}, 100);
        checkZoneLit(sound, 0, "sound frame lights enabled A");
        checkZoneDark(sound, 1, "sound frame leaves disabled B dark");
        checkZoneLit(sound, 2, "sound frame lights enabled C");
    }

    private static void behaviorTests() {
        boolean[] on = {true, true, true};
        int[] levels = {100, 100, 100};
        int all = b(0) | b(1) | b(2) | b(3) | b(4) | b(5) | b(6);
        int[] allBits = {all, all, all};

        for (int index = 0; index < Behavior.COUNT; index++) {
            int bit = b(index);
            int[] bits = {bit, 0, 0};
            int[] frame = GlyphLayout.behaviorFrame(on, bits, levels, 100, bit);
            checkZoneLit(frame, 0, "behavior " + index + " lights A");
            checkZoneDark(frame, 1, "behavior " + index + " leaves B dark");
            checkZoneDark(frame, 2, "behavior " + index + " leaves C dark");
        }

        int[] frame = GlyphLayout.behaviorFrame(on, allBits, levels, 100, b(2));
        checkZoneLit(frame, 0, "volume lights A when assigned");
        checkZoneLit(frame, 1, "volume lights B when assigned");
        checkZoneLit(frame, 2, "volume lights C when assigned");

        frame = GlyphLayout.behaviorFrame(new boolean[]{true, false, true},
                allBits, levels, 100, b(1));
        checkZoneLit(frame, 0, "calls light A");
        checkZoneDark(frame, 1, "disabled B is dark");
        checkZoneLit(frame, 2, "calls light C when assigned");

        frame = GlyphLayout.behaviorFrame(on, new int[]{0, 0, 0}, levels, 100, b(0));
        checkDark(frame, 0, 35, "no assignment is dark");

        frame = GlyphLayout.behaviorFrame(on, new int[]{b(0), 0, 0},
                new int[]{50, 50, 50}, 50, b(0));
        check(frame[30] == 64, "behavior applies strip level and brightness");
        checkDark(frame, 0, 19, "unassigned strips stay dark");

        boolean[] mask = GlyphLayout.behaviorMask(on,
                new int[]{0, 0, b(4)}, b(4));
        check(countMask(mask) == 20, "mask covers C only");
        for (int pos = 0; pos < 20; pos++) {
            check(mask[GlyphLayout.hwIndex(2, pos)], "mask includes C LED");
        }
        check(!mask[30], "mask excludes A");
        check(!mask[31], "mask excludes B");
    }

    private static void progressTests() {
        boolean[] on = {true, true, true};
        int[] levels = {100, 100, 100};

        for (int zone = 0; zone < 3; zone++) {
            int[] bits = {0, 0, 0};
            bits[zone] = b(0);
            int leds = GlyphLayout.ZONE_LEDS[zone];

            int[] frame = GlyphLayout.progressFrame(on, bits, levels, 100, b(0), 0);
            checkFrameDarkOutside(frame, zone, "0% outside zone");

            for (int percent : new int[]{1, 10, 25, 50, 75, 100}) {
                frame = GlyphLayout.progressFrame(on, bits, levels, 100, b(0), percent);
                int expected = GlyphLayout.litCount(leds, percent);
                checkFrameFill(frame, zone, expected, percent + "% fill for zone " + zone);
            }

            frame = GlyphLayout.progressFrame(on, bits, levels, 100, b(0), 1);
            check(frame[GlyphLayout.hwIndex(zone, 0)] == GlyphLayout.MAX_VALUE,
                    "zone " + zone + " 1% starts at the bottom");
            if (leds > 1) {
                check(frame[GlyphLayout.hwIndex(zone, 1)] == 0,
                        "zone " + zone + " 1% does not jump to the top");
            }
        }

        int[] bits = {b(0), 0, 0};
        int[] frame = GlyphLayout.progressFrame(on, bits,
                new int[]{50, 100, 100}, 50, b(0), 100);
        check(frame[30] == 64, "progress scales by level and brightness");

        frame = GlyphLayout.progressFrame(on, new int[]{0, 0, b(0)},
                new int[]{100, 100, 100}, 100, b(0), 50);
        check(frame[0] == GlyphLayout.MAX_VALUE, "C progress starts at hardware 0");
        check(frame[9] == GlyphLayout.MAX_VALUE, "C 50% reaches 10 LEDs");
        check(frame[10] == 0, "C 50% leaves the top half dark");
        checkDark(frame, 20, 35, "C-only progress leaves other strips dark");
    }

    private static void previewTests() {
        boolean[] on = {false, true, false};
        int[] levels = {50, 50, 50};
        int[] frame = GlyphLayout.zoneFrame(on, levels, 100, 0);
        checkLit(frame, 20, 30, "disabled A preview uses full value");
        checkDark(frame, 0, 19, "zone preview only lights one strip");
        checkDark(frame, 31, 35, "zone preview leaves B dark");

        frame = GlyphLayout.zoneFrame(on, levels, 100, 1);
        checkZoneValue(frame, 1, 128, "enabled B preview uses its level");

        frame = GlyphLayout.zoneFrame(new boolean[]{false, true, true}, levels, 50, 2);
        checkZoneValue(frame, 2, 64, "C preview uses level and brightness");

        frame = GlyphLayout.allFrame(on, levels, 100);
        checkLit(frame, 20, 30, "all preview shows disabled A");
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[1]; pos++) {
            check(frame[GlyphLayout.hwIndex(1, pos)] == 128,
                    "all preview keeps enabled B value (pos " + pos + ")");
        }
        checkLit(frame, 0, 19, "all preview shows disabled C");
        check(frame[0] == GlyphLayout.MAX_VALUE, "all preview keeps disabled C full");

        frame = GlyphLayout.allFrame(new boolean[]{true, true, true}, levels, 50);
        check(frame[20] == 64, "all preview respects levels");
        check(frame[31] == 64, "all preview respects B levels");

        frame = GlyphLayout.fullZone(2);
        checkLit(frame, 0, 19, "full C");
        checkDark(frame, 20, 35, "full C leaves others dark");

        frame = GlyphLayout.fullZone(-1);
        checkDark(frame, 0, 35, "invalid full zone is dark");

        frame = GlyphLayout.fullFrame();
        for (int i = 0; i < 36; i++) {
            check(frame[i] == GlyphLayout.MAX_VALUE, "full frame covers all LEDs");
        }
    }

    private static void effectsTests() {
        int[] source = new int[36];
        Arrays.fill(source, 1000);

        int[] frame = GlyphEffects.compose(GlyphEffects.STYLE_PULSE, 0, source);
        check(frame[0] == 350, "pulse starts at its floor");
        frame = GlyphEffects.compose(GlyphEffects.STYLE_PULSE, 310, source);
        check(frame[0] == 1000, "pulse reaches full at half period");

        frame = GlyphEffects.compose(GlyphEffects.STYLE_BREATHE, 0, source);
        check(frame[0] == 60, "breathe starts near dark");
        frame = GlyphEffects.compose(GlyphEffects.STYLE_BREATHE, 1300, source);
        check(frame[0] == 1000, "breathe reaches full at half period");

        frame = GlyphEffects.compose(GlyphEffects.STYLE_WAVE, 0, source);
        check(frame[0] != frame[35], "wave phase varies across the frame");
        check(GlyphEffects.sum(frame) > 0, "wave is visible");

        frame = GlyphEffects.compose(GlyphEffects.STYLE_CALL, 600, source);
        check(GlyphEffects.sum(frame) > 0, "call effect is visible");
        check(frame[0] != frame[35], "call effect moves across the frame");

        frame = GlyphEffects.compose(GlyphEffects.STYLE_SEQUENCE, 0, source);
        check(GlyphEffects.sum(frame) == 0, "sequence starts dark");
        frame = GlyphEffects.compose(GlyphEffects.STYLE_SEQUENCE, 1200, source);
        check(GlyphEffects.sum(frame) == 18 * 1000, "sequence fills halfway");
        frame = GlyphEffects.compose(GlyphEffects.STYLE_SEQUENCE, 2400, source);
        for (int i = 0; i < 36; i++) {
            check(frame[i] == 1000, "sequence completes the frame");
        }

        frame = GlyphEffects.compose(99, 100, source);
        check(GlyphEffects.sum(frame) == 0, "unknown style is dark");
        frame = GlyphEffects.compose(GlyphEffects.STYLE_PULSE, 100, null);
        check(GlyphEffects.sum(frame) == 0, "null source is dark");
        frame = GlyphEffects.compose(GlyphEffects.STYLE_PULSE, 100, new int[12]);
        check(GlyphEffects.sum(frame) == 0, "wrong source length is dark");
        check(GlyphEffects.sum(null) == 0, "null sum is zero");
    }

    private static void defaultsTests() {
        check(GlyphLayout.ZONE_LEDS[0] == 11, "A is the 11-LED right strip");
        check(GlyphLayout.ZONE_LEDS[1] == 5, "B is the 5-LED bottom-left strip");
        check(GlyphLayout.ZONE_LEDS[2] == 20, "C is the 20-LED top-left strip");

        int a = Behavior.DEFAULT_ZONE_BITS[0];
        int b = Behavior.DEFAULT_ZONE_BITS[1];
        int c = Behavior.DEFAULT_ZONE_BITS[2];
        check((a & Behavior.ESSENTIAL) != 0, "essential defaults to A");
        check((a & Behavior.CALLS) == 0, "calls are not a per-strip default");
        check(!Behavior.PER_ZONE[1], "calls are global, not a per-zone behavior");
        check((a & Behavior.ESSENTIAL) != 0, "essential has a default strip");
        check((a & Behavior.CHARGE_FULL) != 0, "charging defaults to A");
        check((b & Behavior.CHARGE_BRIEF) != 0, "charging brief defaults to B");
        check((c & Behavior.VOLUME) != 0, "volume defaults to the 20-LED C strip");
        check((c & Behavior.MEDIA) != 0, "media progress defaults to C");
        check((c & Behavior.NOTIFICATION_PROGRESS) != 0,
                "notification progress defaults to C");

        for (int index = 0; index < Behavior.COUNT; index++) {
            int bit = b(index);
            int assigned = 0;
            for (int zone = 0; zone < 3; zone++) {
                if ((Behavior.DEFAULT_ZONE_BITS[zone] & bit) != 0) {
                    assigned++;
                }
            }
            if (!Behavior.PER_ZONE[index]) {
                check(assigned == 0, "global behavior " + index + " has no strip");
            } else {
                check(assigned == 1, "behavior " + index + " has exactly one default strip");
            }
        }

        check(Behavior.NAMES.length == Behavior.COUNT, "all behaviors have names");
        check(Behavior.DESCRIPTIONS.length == Behavior.COUNT,
                "all behaviors have descriptions");
        for (int i = 0; i < Behavior.COUNT; i++) {
            check(Behavior.NAMES[i] != null && !Behavior.NAMES[i].isEmpty(),
                    "behavior name " + i + " is present");
            check(Behavior.DESCRIPTIONS[i] != null && !Behavior.DESCRIPTIONS[i].isEmpty(),
                    "behavior description " + i + " is present");
            check(Behavior.bit(i) == (1 << i), "behavior bit " + i + " is stable");
        }
    }

    private static void spectrumTests() {
        AudioSpectrum spectrum = new AudioSpectrum();
        check(spectrum.lastEnergy() == 0f, "new spectrum has no energy");
        check(spectrum.bandLevel(0) == 0f, "new spectrum has no A level");
        check(spectrum.bandLevel(1) == 0f, "new spectrum has no B level");
        check(spectrum.bandLevel(2) == 0f, "new spectrum has no C level");

        int[] silence = spectrum.process(new byte[1024], 48000, 1024, 100);
        checkDark(silence, 0, 35, "silence is dark");
        check(spectrum.lastEnergy() == 0f, "silence has no energy");

        AudioSpectrum low = new AudioSpectrum();
        int[] lowFrame = low.process(tone(100f, 100), 48000, 1024, 100);
        checkStrip(lowFrame, 2, GlyphLayout.MAX_VALUE, "low tone lights the whole C strip");
        checkStrip(lowFrame, 0, 0, "low tone leaves A dark");
        checkStrip(lowFrame, 1, 0, "low tone leaves B dark");
        check(countLit(lowFrame) == 20, "low tone uses all 20 C LEDs");
        check(low.lastEnergy() > 0f, "low tone has energy");
        check(low.bandLevel(2) > 0f, "low tone has C level");

        AudioSpectrum mid = new AudioSpectrum();
        int[] midFrame = mid.process(tone(1000f, 100), 48000, 1024, 100);
        checkStrip(midFrame, 0, GlyphLayout.MAX_VALUE, "mid tone lights the whole A strip");
        checkStrip(midFrame, 1, 0, "mid tone leaves B dark");
        checkStrip(midFrame, 2, 0, "mid tone leaves C dark");
        check(countLit(midFrame) == 11, "mid tone uses all 11 A LEDs, not one LED");
        check(mid.bandLevel(0) > 0f, "mid tone has A level");

        AudioSpectrum high = new AudioSpectrum();
        int[] highFrame = high.process(tone(8000f, 100), 48000, 1024, 100);
        checkStrip(highFrame, 1, GlyphLayout.MAX_VALUE, "high tone lights the whole B strip");
        checkStrip(highFrame, 0, 0, "high tone leaves A dark");
        checkStrip(highFrame, 2, 0, "high tone leaves C dark");
        check(countLit(highFrame) == 5, "high tone uses all 5 B LEDs");
        check(high.bandLevel(1) > 0f, "high tone has B level");

        AudioSpectrum broad = new AudioSpectrum();
        int[] broadFrame = broad.process(broadband(), 48000, 1024, 100);
        checkStrip(broadFrame, 0, GlyphLayout.MAX_VALUE, "broadband lights A fully");
        checkStrip(broadFrame, 1, GlyphLayout.MAX_VALUE, "broadband lights B fully");
        checkStrip(broadFrame, 2, GlyphLayout.MAX_VALUE, "broadband lights C fully");
        check(countLit(broadFrame) == 36, "broadband uses all 36 LEDs");

        AudioSpectrum quiet = new AudioSpectrum();
        int[] quietFrame = quiet.process(tone(1000f, 1), 48000, 1024, 100);
        check(countLit(quietFrame) == 0, "very quiet sound stays dark");
        check(quiet.lastEnergy() > 0f, "quiet input still reports its level");

        AudioSpectrum zeroBrightness = new AudioSpectrum();
        int[] zeroFrame = zeroBrightness.process(tone(1000f, 100), 48000, 1024, 0);
        check(countLit(zeroFrame) == 0, "zero brightness writes zero");

        AudioSpectrum decay = new AudioSpectrum();
        int[] loud = decay.process(tone(1000f, 100), 48000, 1024, 100);
        checkStrip(loud, 0, GlyphLayout.MAX_VALUE, "decay starts with the whole A strip lit");
        int[] faded = decay.process(new byte[1024], 48000, 1024, 100);
        check(countLit(faded) > 0, "decay first frame still lit");
        for (int i = 0; i < 100; i++) {
            faded = decay.process(new byte[1024], 48000, 1024, 100);
        }
        check(countLit(faded) == 0, "decay reaches zero");
        decay.reset();
        check(decay.lastEnergy() == 0f, "reset clears energy");
        check(decay.bandLevel(0) == 0f, "reset clears band level");

        AudioSpectrum invalid = new AudioSpectrum();
        check(countLit(invalid.process(null, 48000, 1024, 100)) == 0,
                "null FFT is dark");
        check(countLit(invalid.process(new byte[1024], 0, 1024, 100)) == 0,
                "invalid sample rate is dark");
        check(countLit(invalid.process(new byte[1024], 48000, 0, 100)) == 0,
                "invalid capture size is dark");
        check(invalid.bandLevel(-1) == 0f, "invalid low zone is silent");
        check(invalid.bandLevel(3) == 0f, "invalid high zone is silent");
    }

    /** Every effect must fill whatever strip count it is given. */
    private static void effectOrderTests() {
        int[] bOnly = new int[GlyphLayout.LEDS];
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[1]; pos++) {
            bOnly[GlyphLayout.hwIndex(1, pos)] = GlyphLayout.MAX_VALUE;
        }
        check(GlyphEffects.order(bOnly).length == 5, "order counts only the B strip");

        int[] seqStart = GlyphEffects.compose(GlyphEffects.STYLE_SEQUENCE, 0, bOnly);
        check(countLit(seqStart) == 0, "B sequence starts dark");
        int[] seqEnd = GlyphEffects.compose(GlyphEffects.STYLE_SEQUENCE, 2400, bOnly);
        check(countLit(seqEnd) == 5, "B sequence reaches all 5 LEDs");
        for (int pos = 0; pos < 5; pos++) {
            check(seqEnd[GlyphLayout.hwIndex(1, pos)] == GlyphLayout.MAX_VALUE,
                    "B sequence lights LED " + pos);
        }
        checkDark(seqEnd, 0, 30, "B sequence never writes A or C");

        int[] wave = GlyphEffects.compose(GlyphEffects.STYLE_WAVE, 0, bOnly);
        check(countLit(wave) == 5, "B wave lights every LED, none at zero");
        int[] call = GlyphEffects.compose(GlyphEffects.STYLE_CALL, 700, bOnly);
        check(countLit(call) == 5, "B call pulse keeps every LED alive");

        int[] aOnly = new int[GlyphLayout.LEDS];
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[0]; pos++) {
            aOnly[GlyphLayout.hwIndex(0, pos)] = GlyphLayout.MAX_VALUE;
        }
        check(GlyphEffects.order(aOnly).length == 11, "order counts only the A strip");
        int[] aEnd = GlyphEffects.compose(GlyphEffects.STYLE_SEQUENCE, 2400, aOnly);
        check(countLit(aEnd) == 11, "A sequence reaches all 11 LEDs");
        checkDark(aEnd, 0, 19, "A sequence leaves C dark");

        check(GlyphEffects.order(GlyphLayout.fullFrame()).length == 36,
                "full frame orders all 36 LEDs");
        check(GlyphEffects.order(null).length == 0, "null frame has no order");
    }

    /** Sound must stay inside enabled strips and fold into a smaller setup. */
    private static void spectrumMaskTests() {
        boolean[] allOff = new boolean[GlyphLayout.LEDS];
        AudioSpectrum silent = new AudioSpectrum();
        int[] dark = silent.process(tone(100f, 100), 48000, 1024, 100, allOff);
        check(countLit(dark) == 0, "audio respects an all-off mask");

        boolean[] bOnly = new boolean[GlyphLayout.LEDS];
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[1]; pos++) {
            bOnly[GlyphLayout.hwIndex(1, pos)] = true;
        }
        AudioSpectrum onB = new AudioSpectrum();
        int[] bFrame = onB.process(tone(100f, 100), 48000, 1024, 100, bOnly);
        check(countLit(bFrame) == 5, "low band folds into a 5-LED-only setup");
        checkStrip(bFrame, 1, GlyphLayout.MAX_VALUE, "folded low band fills B");
        checkDark(bFrame, 0, 30, "folded low band leaves A and C dark");

        boolean[] aOnly = new boolean[GlyphLayout.LEDS];
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[0]; pos++) {
            aOnly[GlyphLayout.hwIndex(0, pos)] = true;
        }
        AudioSpectrum onA = new AudioSpectrum();
        int[] aFrame = onA.process(tone(100f, 100), 48000, 1024, 100, aOnly);
        check(countLit(aFrame) == 11, "low band folds into an 11-LED-only setup");

        boolean[] cOnly = new boolean[GlyphLayout.LEDS];
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[2]; pos++) {
            cOnly[GlyphLayout.hwIndex(2, pos)] = true;
        }
        AudioSpectrum onC = new AudioSpectrum();
        int[] cFrame = onC.process(tone(1000f, 100), 48000, 1024, 100, cOnly);
        check(countLit(cFrame) == 20, "mid band folds into a 20-LED-only setup");
        checkDark(cFrame, 20, 35, "folded mid band leaves A and B dark");
    }

    /** A new sound must take over the LEDs instead of waiting for the old one. */
    private static void audioSwitchTests() {
        AudioSpectrum spectrum = new AudioSpectrum();
        int[] low = spectrum.process(tone(100f, 100), 48000, 1024, 100);
        checkStrip(low, 2, GlyphLayout.MAX_VALUE, "switch: low fills C");

        int[] high = spectrum.process(tone(8000f, 100), 48000, 1024, 100);
        checkStrip(high, 1, GlyphLayout.MAX_VALUE, "switch: high fills B immediately");
        check(high[GlyphLayout.hwIndex(2, 19)] < GlyphLayout.MAX_VALUE,
                "switch: the previous low strip is fading, not held on");
        check(high[GlyphLayout.hwIndex(0, 5)] == 0,
                "switch: the unrelated middle strip stays dark");

        int[] mid = spectrum.process(tone(1000f, 100), 48000, 1024, 100);
        checkStrip(mid, 0, GlyphLayout.MAX_VALUE, "switch: mid fills A immediately");

        for (int i = 0; i < 60; i++) {
            mid = spectrum.process(new byte[1024], 48000, 1024, 100);
        }
        check(countLit(mid) == 0, "switch: everything is dark after the sound stops");
    }

    /** A newer static claim owns its strip; earlier claims stay on other strips. */
    private static void frameClaimTests() {
        boolean[] a = new boolean[GlyphLayout.LEDS];
        boolean[] b = new boolean[GlyphLayout.LEDS];
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[0]; pos++) {
            a[GlyphLayout.hwIndex(0, pos)] = true;
        }
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[1]; pos++) {
            b[GlyphLayout.hwIndex(1, pos)] = true;
        }
        int[] older = new int[GlyphLayout.LEDS];
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[0]; pos++) {
            older[GlyphLayout.hwIndex(0, pos)] = 255;
        }
        int[] newer = new int[GlyphLayout.LEDS];
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[1]; pos++) {
            newer[GlyphLayout.hwIndex(1, pos)] = 255;
        }
        int[] combined = FrameClaims.compose(Arrays.asList(older, newer),
                Arrays.asList(a, b));
        checkZoneLit(combined, 0, "claims: older strip stays lit");
        checkZoneLit(combined, 1, "claims: newer strip is lit");
        checkZoneDark(combined, 2, "claims: unclaimed strip stays dark");

        int[] replacement = new int[GlyphLayout.LEDS];
        int[] sameStrip = FrameClaims.compose(Arrays.asList(older, replacement),
                Arrays.asList(a, a));
        checkZoneDark(sameStrip, 0, "claims: newest write can turn a strip off");

        int[] dim = new int[GlyphLayout.LEDS];
        dim[0] = 40;
        int[] bright = new int[GlyphLayout.LEDS];
        bright[0] = 90;
        int[] legacy = FrameClaims.compose(Arrays.asList(dim, bright),
                Arrays.<boolean[]>asList(null, null));
        check(legacy[0] == 90, "claims: unmasked frames keep the brighter value");
    }

    /** Fades must hit both endpoints exactly and never overshoot. */
    private static void blendTests() {
        int[] from = new int[GlyphLayout.LEDS];
        int[] target = new int[GlyphLayout.LEDS];
        for (int i = 0; i < GlyphLayout.LEDS; i++) {
            from[i] = GlyphLayout.MAX_VALUE;
            target[i] = i * 10;
        }
        int[] start = GlyphEffects.blend(from, target, 0f);
        int[] end = GlyphEffects.blend(from, target, 1f);
        int[] mid = GlyphEffects.blend(from, target, 0.5f);
        check(start.length == GlyphLayout.LEDS, "blend keeps frame length");
        check(Arrays.equals(start, from), "blend at zero is the source frame");
        check(Arrays.equals(end, target), "blend at one is the target frame");
        check(mid[0] == Math.round((from[0] + target[0]) * 0.5f), "blend midpoint");
        int previous = GlyphLayout.MAX_VALUE;
        int[] off = new int[GlyphLayout.LEDS];
        for (int step = 1; step <= 12; step++) {
            int value = GlyphEffects.blend(from, off, step / 12f)[0];
            check(value <= previous, "fade never rises mid-ramp");
            previous = value;
        }
        check(previous == 0, "fade reaches zero");
        check(GlyphEffects.blend(from, target, -1f)[0] == from[0], "blend clamps low");
        check(GlyphEffects.blend(from, target, 2f)[0] == target[0], "blend clamps high");
        check(GlyphEffects.blend(null, target, 0.5f)[0] == 0, "null source is dark");
        check(GlyphEffects.blend(from, null, 0.5f)[0] == 0, "null target is dark");
    }

    private static int b(int index) {
        return 1 << index;
    }

    private static byte[] tone(float hz, int amplitude) {
        byte[] fft = new byte[1024];
        double hzPerBin = 48000.0 / 1024.0;
        int bin = Math.max(1, Math.min(511, (int) Math.round(hz / hzPerBin)));
        fft[bin * 2] = (byte) amplitude;
        return fft;
    }

    private static byte[] broadband() {
        byte[] fft = new byte[1024];
        for (int bin = 1; bin < 340; bin++) {
            fft[bin * 2] = (byte) 100;
        }
        return fft;
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

    private static int countMask(boolean[] mask) {
        int count = 0;
        for (boolean value : mask) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static void checkStrip(int[] frame, int zone, int value, String message) {
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
            check(frame[GlyphLayout.hwIndex(zone, pos)] == value,
                    message + " (pos " + pos + ")");
        }
    }

    private static void checkZoneValue(int[] frame, int zone, int value, String message) {
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
            check(frame[GlyphLayout.hwIndex(zone, pos)] == value,
                    message + " (pos " + pos + ")");
        }
        checkFrameDarkOutside(frame, zone, message + " outside");
    }

    private static void checkZoneLit(int[] frame, int zone, String message) {
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
            check(frame[GlyphLayout.hwIndex(zone, pos)] == GlyphLayout.MAX_VALUE,
                    message + " (pos " + pos + ")");
        }
    }

    private static void checkZoneDark(int[] frame, int zone, String message) {
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
            check(frame[GlyphLayout.hwIndex(zone, pos)] == 0,
                    message + " (pos " + pos + ")");
        }
    }

    private static void checkFrameFill(int[] frame, int zone, int count, String message) {
        for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[zone]; pos++) {
            int value = frame[GlyphLayout.hwIndex(zone, pos)];
            if (pos < count) {
                check(value == GlyphLayout.MAX_VALUE, message + " lit at pos " + pos);
            } else {
                check(value == 0, message + " dark at pos " + pos);
            }
        }
        checkFrameDarkOutside(frame, zone, message + " outside");
    }

    private static void checkFrameDarkOutside(int[] frame, int zone, String message) {
        for (int other = 0; other < 3; other++) {
            if (other == zone) {
                continue;
            }
            for (int pos = 0; pos < GlyphLayout.ZONE_LEDS[other]; pos++) {
                check(frame[GlyphLayout.hwIndex(other, pos)] == 0,
                        message + " (other zone " + other + ")");
            }
        }
    }

    private static void checkLit(int[] frame, int from, int to, String message) {
        for (int i = from; i <= to; i++) {
            check(frame[i] == GlyphLayout.MAX_VALUE, message + " (index " + i + ")");
        }
    }

    private static void checkDark(int[] frame, int from, int to, String message) {
        for (int i = from; i <= to; i++) {
            check(frame[i] == 0, message + " (index " + i + ")");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
