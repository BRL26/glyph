package dev.brl.glyph;

/**
 * The feature set the website's auto generators get from Meyda.
 *
 * Meyda windows the signal with a Hanning window before the FFT, and its rms
 * extractor is fed that windowed signal, not the raw PCM. The amplitude
 * spectrum is the magnitude of the first half of the FFT, stored as a
 * Float32Array. Loudness follows Meyda's 24-band Bark implementation.
 *
 * Kept Android-free so the host reference tests can compare it with the
 * JavaScript implementation.
 */
public final class MeydaFeatures {

    public static final int BUFFER_SIZE = 2048;
    public static final int BINS = BUFFER_SIZE / 2;

    public static final class Frame {
        public final float[] amplitude;
        public final float rms;
        public final float loudness;
        public final float spectralCentroid;
        public final float spectralFlatness;

        Frame(float[] amplitude, float rms, float loudness,
              float spectralCentroid, float spectralFlatness) {
            this.amplitude = amplitude;
            this.rms = rms;
            this.loudness = loudness;
            this.spectralCentroid = spectralCentroid;
            this.spectralFlatness = spectralFlatness;
        }
    }

    private final int sampleRate;
    private final float[] barkScale;
    private final float[] window;

    public MeydaFeatures(int sampleRate) {
        this.sampleRate = sampleRate;
        this.barkScale = createBarkScale(BUFFER_SIZE, sampleRate, BUFFER_SIZE);
        this.window = hanning(BUFFER_SIZE);
    }

    public Frame analyze(short[] pcm, int count, int start) {
        double[] signal = new double[BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            int index = start + i;
            float sample = index >= 0 && index < count ? pcm[index] / 32768f : 0f;
            // Exactly Meyda's pointwise multiplication of the Float32 signal
            // and the Float32 Hanning window.
            signal[i] = (double) sample * (double) window[i];
        }

        double energy = 0.0;
        for (double sample : signal) {
            energy += sample * sample;
        }
        float rms = (float) Math.sqrt(energy / signal.length);

        double[] real = signal.clone();
        double[] imaginary = new double[BUFFER_SIZE];
        fft(real, imaginary);

        float[] amplitude = new float[BINS];
        for (int i = 0; i < BINS; i++) {
            amplitude[i] = (float) Math.sqrt(real[i] * real[i] + imaginary[i] * imaginary[i]);
        }

        return new Frame(amplitude, rms, loudness(amplitude, barkScale),
                centroid(amplitude), flatness(amplitude));
    }

    public static double bandEnergy(float[] amplitude, double minFreq, double maxFreq,
                                    int sampleRate, int bufferSize) {
        if (amplitude == null) {
            return 0.0;
        }
        double nyquist = sampleRate / 2.0;
        int start = (int) Math.floor((minFreq / nyquist) * (bufferSize / 2.0));
        int end = (int) Math.ceil((maxFreq / nyquist) * (bufferSize / 2.0));
        double energy = 0.0;
        for (int i = start; i <= end; i++) {
            if (i >= 0 && i < amplitude.length) {
                energy += (double) amplitude[i] * (double) amplitude[i];
            }
        }
        return Math.sqrt(energy);
    }

    public static float[] createBarkScale(int length, int sampleRate, int bufferSize) {
        float[] scale = new float[length];
        for (int i = 0; i < scale.length; i++) {
            double frequency = (i * (double) sampleRate) / bufferSize;
            scale[i] = (float) (13.0 * Math.atan(frequency / 1315.8)
                    + 3.5 * Math.atan(Math.pow(frequency / 7518.0, 2.0)));
        }
        return scale;
    }

    private static float[] hanning(int size) {
        float[] values = new float[size];
        for (int i = 0; i < size; i++) {
            values[i] = (float) (0.5 - 0.5 * Math.cos((2.0 * Math.PI * i) / (size - 1)));
        }
        return values;
    }

    private static float loudness(float[] amplitude, float[] barkScale) {
        final int bands = 24;
        int[] limits = new int[bands + 1];
        limits[0] = 0;
        // Meyda's barkScale is bufferSize long, but its loudness extractor
        // indexes the endpoint with normalisedSpectrum.length - 1 (1023).
        double barkMax = barkScale[amplitude.length - 1];
        double currentBandEnd = barkMax / bands;
        int currentBand = 1;
        for (int i = 0; i < amplitude.length; i++) {
            while (currentBand < bands && barkScale[i] > currentBandEnd) {
                limits[currentBand++] = i;
                currentBandEnd = (currentBand * barkMax) / bands;
            }
        }
        limits[bands] = amplitude.length - 1;

        double total = 0.0;
        for (int band = 0; band < bands; band++) {
            double sum = 0.0;
            int end = limits[band + 1];
            for (int i = limits[band]; i < end; i++) {
                sum += amplitude[i];
            }
            // Meyda stores this in a Float32Array before summing total.
            total += (float) Math.pow(sum, 0.23);
        }
        return (float) total;
    }

    private static float centroid(float[] amplitude) {
        double numerator = 0.0;
        double denominator = 0.0;
        for (int i = 0; i < amplitude.length; i++) {
            numerator += i * (double) Math.abs(amplitude[i]);
            denominator += amplitude[i];
        }
        return (float) (numerator / denominator);
    }

    private static float flatness(float[] amplitude) {
        double numerator = 0.0;
        double denominator = 0.0;
        for (float value : amplitude) {
            numerator += Math.log(value);
            denominator += value;
        }
        return (float) ((Math.exp(numerator / amplitude.length) * amplitude.length)
                / denominator);
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
