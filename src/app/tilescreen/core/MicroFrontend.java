package app.tilescreen.core;

/**
 * The audio front end microWakeWord's models hear through: TensorFlow Lite Micro's microfrontend (window, kissfft's
 * 16-bit fixed-point real FFT, a 40-channel mel filterbank, noise reduction, PCAN gain control, log scale) with the
 * settings ESPHome's micro_wake_word uses (preprocessor_settings.h), 16 kHz in, one row of 40 features every 10 ms.
 * It is integer arithmetic throughout, written to give the C code's numbers exactly (test/wake_test.py checks it against
 * the C); the tables the C works out with float maths come from the C itself (MicroTables), as Java's maths can differ.
 * Apache 2.0 (TensorFlow) and BSD (kissfft): see NOTICE.
 */
public final class MicroFrontend {
    public static final int CHANNELS = 40, RATE = 16000;
    static final int WINDOW = 30 * RATE / 1000, STEP = 10 * RATE / 1000, FFT = 512;
    static final int WINDOW_BITS = 12, FILTERBANK_BITS = 12, NOISE_BITS = 14, SMOOTHING_BITS = 10;
    static final int PCAN_SNR_BITS = 12, PCAN_OUTPUT_BITS = 6, GAIN_BITS = 21, WIDE_BITS = 32, SCALE_SHIFT = 6;
    static final int LOG_SCALE_LOG2 = 16, LOG_SEGMENTS_LOG2 = 7, LOG_SCALE = 65536, LOG_COEFF = 45426;

    // The window and the FFT.
    final short[] coefficients = new short[WINDOW];
    private final short[] input = new short[WINDOW];
    private final short[] windowed = new short[WINDOW];
    private int inputUsed, maxAbs;
    private final short[] fftIn = new short[FFT];
    private final short[] spectrum = new short[(FFT / 2 + 1) * 2];
    private final Kiss kiss = new Kiss(FFT / 2);
    final short[] superTwiddles = new short[FFT / 2];
    private final short[] tmp = new short[FFT];

    // The filterbank.
    final int startIndex, endIndex;
    final short[] channelFrequencyStarts = new short[CHANNELS + 1], channelWeightStarts = new short[CHANNELS + 1],
            channelWidths = new short[CHANNELS + 1];
    final short[] weights, unweights;
    // Padded: a channel's last block of four may read past the spectrum, always with zero weights.
    private final int[] energy = new int[FFT / 2 + 1 + 8];
    private final long[] work = new long[CHANNELS + 1];
    private final int[] signal = new int[CHANNELS];

    // Noise reduction and gain control.
    final int evenSmoothing, oddSmoothing, minSignalRemaining;
    private final int[] estimate = new int[CHANNELS];
    final short[] gainLut = new short[4 * WIDE_BITS];
    final int snrShift, correctionBits;

    private final int[] out = new int[CHANNELS];

    public MicroFrontend() {
        System.arraycopy(MicroTables.get(MicroTables.WINDOW), 0, coefficients, 0, WINDOW);
        System.arraycopy(MicroTables.get(MicroTables.SUPER), 0, superTwiddles, 0, FFT / 2);
        short[] bank = MicroTables.get(MicroTables.BANK);
        startIndex = bank[0];
        endIndex = bank[1];
        for (int i = 0; i <= CHANNELS; i++) {
            channelFrequencyStarts[i] = bank[2 + 3 * i];
            channelWeightStarts[i] = bank[3 + 3 * i];
            channelWidths[i] = bank[4 + 3 * i];
        }
        weights = MicroTables.get(MicroTables.WEIGHTS);
        unweights = MicroTables.get(MicroTables.UNWEIGHTS);
        short[] lut = MicroTables.get(MicroTables.LUT);
        System.arraycopy(lut, 0, gainLut, 0, lut.length);
        evenSmoothing = (int) (0.025f * (1 << NOISE_BITS));
        oddSmoothing = (int) (0.06f * (1 << NOISE_BITS));
        minSignalRemaining = (int) (0.05f * (1 << NOISE_BITS));
        correctionBits = msb(FFT) - 1 - (FILTERBANK_BITS / 2);
        snrShift = GAIN_BITS - correctionBits - PCAN_SNR_BITS;
    }

    /** Back to silence, as when the microphone opens again. */
    public void reset() {
        java.util.Arrays.fill(input, (short) 0);
        java.util.Arrays.fill(estimate, 0);
        inputUsed = 0;
    }

    /**
     * Takes samples from `pcm` starting at `from`; returns how many it took. When that filled a window, the 40 features
     * are in `features` (ESPHome's int8 scaling, generate_features_) and `ready` is set; otherwise `ready` is false.
     */
    public int take(short[] pcm, int from, int count, byte[] features) {
        ready = false;
        int n = Math.min(WINDOW - inputUsed, count);
        System.arraycopy(pcm, from, input, inputUsed, n);
        inputUsed += n;
        if (inputUsed < WINDOW) return n;
        process();
        for (int i = 0; i < CHANNELS; i++) {
            int value = ((out[i] * 256) + (666 / 2)) / 666 - 128;
            features[i] = (byte) Math.max(-128, Math.min(127, value));
        }
        ready = true;
        return n;
    }

    public boolean ready;

    /** The raw 16-bit features of the last window (the test compares these with the C). */
    int[] raw() { return out; }

    private void process() {
        int max = 0;
        for (int i = 0; i < WINDOW; i++) {
            int v = (input[i] * coefficients[i]) >> WINDOW_BITS;
            windowed[i] = (short) v;
            short a = (short) v;
            if (a < 0) a = (short) -a; // as the C's int16: -32768 stays negative and never counts
            if (a > max) max = a;
        }
        System.arraycopy(input, STEP, input, 0, WINDOW - STEP);
        inputUsed -= STEP;
        maxAbs = max;

        int shift = 15 - msb(maxAbs);
        for (int i = 0; i < WINDOW; i++) fftIn[i] = (short) ((windowed[i] & 0xFFFF) << shift);
        for (int i = WINDOW; i < FFT; i++) fftIn[i] = 0;
        fftr(fftIn, spectrum);

        for (int i = startIndex; i < endIndex; i++) {
            int re = spectrum[2 * i], im = spectrum[2 * i + 1];
            energy[i] = re * re + im * im;
        }
        accumulate();
        for (int i = 0; i < CHANNELS; i++) signal[i] = (int) (sqrt64(work[i + 1]) >>> shift);

        noiseReduction();
        for (int i = 0; i < CHANNELS; i++) {
            long gain = wide(estimate[i]) & 0xFFFFFFFFL;
            long snr = ((signal[i] & 0xFFFFFFFFL) * gain) >>> snrShift;
            signal[i] = shrink((int) snr);
        }
        int correction = msb(FFT) - 1 - (FILTERBANK_BITS / 2);
        for (int i = 0; i < CHANNELS; i++) {
            long value = signal[i] & 0xFFFFFFFFL;
            if (correction < 0) value >>>= -correction; else value = (value << correction) & 0xFFFFFFFFL;
            value = value > 1 ? log((int) value) & 0xFFFFFFFFL : 0;
            out[i] = (int) (value < 0xFFFF ? value : 0xFFFF);
        }
    }

    // ---- kissfft (v130, FIXED_POINT 16) ----

    static short sround(int x) { return (short) ((x + (1 << 14)) >> 15); }

    /** kiss_fftr: the real FFT of `time` (FFT samples) into FFT/2+1 complex values. */
    private void fftr(short[] time, short[] freq) {
        int ncfft = FFT / 2;
        kiss.fft(time, tmp);
        int tdcR = sround(tmp[0] * 16383), tdcI = sround(tmp[1] * 16383);
        freq[0] = (short) (tdcR + tdcI);
        freq[2 * ncfft] = (short) (tdcR - tdcI);
        freq[1] = freq[2 * ncfft + 1] = 0;
        for (int k = 1; k <= ncfft / 2; k++) {
            short fpkR = sround(tmp[2 * k] * 16383), fpkI = sround(tmp[2 * k + 1] * 16383);
            short fpnkR = sround(tmp[2 * (ncfft - k)] * 16383);
            short fpnkI = sround(((short) -tmp[2 * (ncfft - k) + 1]) * 16383);
            short f1kR = (short) (fpkR + fpnkR), f1kI = (short) (fpkI + fpnkI);
            short f2kR = (short) (fpkR - fpnkR), f2kI = (short) (fpkI - fpnkI);
            short twR = superTwiddles[2 * (k - 1)], twI = superTwiddles[2 * (k - 1) + 1];
            short tR = sround(f2kR * twR - f2kI * twI), tI = sround(f2kR * twI + f2kI * twR);
            freq[2 * k] = (short) ((f1kR + tR) >> 1);
            freq[2 * k + 1] = (short) ((f1kI + tI) >> 1);
            freq[2 * (ncfft - k)] = (short) ((f1kR - tR) >> 1);
            freq[2 * (ncfft - k) + 1] = (short) ((tI - f1kI) >> 1);
        }
    }

    /** kissfft's complex forward FFT, for a power-of-four length (only radix 4 is needed: 256 = 4·4·4·4). */
    static final class Kiss {
        final int n;
        final short[] twiddles;
        final int[] factors;

        Kiss(int n) {
            this.n = n;
            twiddles = MicroTables.get(MicroTables.TWIDDLES);
            int stages = 0;
            for (int m = n; m > 1; m /= 4) {
                if (m % 4 != 0) throw new IllegalArgumentException("kissfft here needs a power of 4");
                stages++;
            }
            factors = new int[2 * stages];
            for (int i = 0, m = n; m > 1; i++) { m /= 4; factors[2 * i] = 4; factors[2 * i + 1] = m; }
        }

        void fft(short[] in, short[] out) { work(out, 0, in, 0, 1, 0); }

        private void work(short[] out, int o, short[] in, int f, int fstride, int fi) {
            int p = factors[fi], m = factors[fi + 1];
            int end = o + 2 * p * m;
            if (m == 1) {
                for (int k = o; k != end; k += 2) {
                    out[k] = in[f]; out[k + 1] = in[f + 1];
                    f += 2 * fstride;
                }
            } else {
                for (int k = o; k != end; k += 2 * m) {
                    work(out, k, in, f, fstride * p, fi + 2);
                    f += 2 * fstride;
                }
            }
            bfly4(out, o, fstride, m);
        }

        /** kf_bfly4, written out (sround by hand, locals for every value): it is most of the front end's time. */
        private void bfly4(short[] F, int o, int fstride, int m) {
            final short[] tw = twiddles;
            int tw1 = 0, tw2 = 0, tw3 = 0;
            final int m1 = 2 * m, m2 = 4 * m, m3 = 6 * m;
            final int s1 = 2 * fstride, s2 = 4 * fstride, s3 = 6 * fstride;
            for (int k = 0; k < m; k++, o += 2) {
                // C_FIXDIV(.., 4): times 8191, rounded, as int16.
                int f0r = (short) ((F[o] * 8191 + 16384) >> 15), f0i = (short) ((F[o + 1] * 8191 + 16384) >> 15);
                int f1r = (short) ((F[o + m1] * 8191 + 16384) >> 15), f1i = (short) ((F[o + m1 + 1] * 8191 + 16384) >> 15);
                int f2r = (short) ((F[o + m2] * 8191 + 16384) >> 15), f2i = (short) ((F[o + m2 + 1] * 8191 + 16384) >> 15);
                int f3r = (short) ((F[o + m3] * 8191 + 16384) >> 15), f3i = (short) ((F[o + m3 + 1] * 8191 + 16384) >> 15);
                int w1r = tw[tw1], w1i = tw[tw1 + 1], w2r = tw[tw2], w2i = tw[tw2 + 1], w3r = tw[tw3], w3i = tw[tw3 + 1];
                int s0r = (short) ((f1r * w1r - f1i * w1i + 16384) >> 15), s0i = (short) ((f1r * w1i + f1i * w1r + 16384) >> 15);
                int s1r = (short) ((f2r * w2r - f2i * w2i + 16384) >> 15), s1i = (short) ((f2r * w2i + f2i * w2r + 16384) >> 15);
                int s2r = (short) ((f3r * w3r - f3i * w3i + 16384) >> 15), s2i = (short) ((f3r * w3i + f3i * w3r + 16384) >> 15);
                int s5r = (short) (f0r - s1r), s5i = (short) (f0i - s1i);
                f0r = (short) (f0r + s1r);
                f0i = (short) (f0i + s1i);
                int s3r = (short) (s0r + s2r), s3i = (short) (s0i + s2i);
                int s4r = (short) (s0r - s2r), s4i = (short) (s0i - s2i);
                F[o + m2] = (short) (f0r - s3r);
                F[o + m2 + 1] = (short) (f0i - s3i);
                F[o] = (short) (f0r + s3r);
                F[o + 1] = (short) (f0i + s3i);
                F[o + m1] = (short) (s5r + s4i);
                F[o + m1 + 1] = (short) (s5i - s4r);
                F[o + m3] = (short) (s5r - s4i);
                F[o + m3 + 1] = (short) (s5i + s4r);
                tw1 += s1;
                tw2 += s2;
                tw3 += s3;
            }
        }
    }

    // ---- filterbank ----

    private void accumulate() {
        long weight = 0, unweight = 0;
        for (int i = 0; i <= CHANNELS; i++) {
            int mag = channelFrequencyStarts[i], ws = channelWeightStarts[i], width = channelWidths[i];
            for (int j = 0; j < width; j++) {
                long e = energy[mag + j]; // int32 to uint64 sign-extends, as in the C
                weight += weights[ws + j] * e;
                unweight += unweights[ws + j] * e;
            }
            work[i] = weight;
            weight = unweight;
            unweight = 0;
        }
    }

    static int msb(int n) { return 32 - Integer.numberOfLeadingZeros(n); }

    static int sqrt32(int num) {
        if (num == 0) return 0;
        long n = num & 0xFFFFFFFFL, res = 0;
        int maxBit = (32 - msb(num)) | 1;
        long bit = 1L << (31 - maxBit);
        int iterations = (31 - maxBit) / 2 + 1;
        while (iterations-- > 0) {
            if (n >= res + bit) { n -= res + bit; res = (res >>> 1) + bit; } else res >>>= 1;
            bit >>>= 2;
        }
        if (n > res && res != 0xFFFF) res++;
        return (int) (res & 0xFFFF);
    }

    static long sqrt64(long num) {
        if ((num >>> 32) == 0) return sqrt32((int) num) & 0xFFFFL;
        // Unsigned 64-bit; the filterbank's sums stay far below 2^63.
        long res = 0;
        int maxBit = (64 - (64 - Long.numberOfLeadingZeros(num))) | 1;
        long bit = 1L << (63 - maxBit);
        int iterations = (63 - maxBit) / 2 + 1;
        while (iterations-- > 0) {
            if (unsignedAtLeast(num, res + bit)) { num -= res + bit; res = (res >>> 1) + bit; } else res >>>= 1;
            bit >>>= 2;
        }
        if (num != res && unsignedAtLeast(num, res) && res != 0xFFFFFFFFL) res++;
        return res & 0xFFFFFFFFL;
    }

    /** a >= b as unsigned 64-bit (Long.compareUnsigned is Java 8, newer than old Androids). */
    static boolean unsignedAtLeast(long a, long b) { return (a ^ Long.MIN_VALUE) >= (b ^ Long.MIN_VALUE); }

    // ---- noise reduction, PCAN, log ----

    private void noiseReduction() {
        for (int i = 0; i < CHANNELS; i++) {
            long smoothing = (i & 1) == 0 ? evenSmoothing : oddSmoothing;
            long oneMinus = (1 << NOISE_BITS) - smoothing;
            long s = signal[i] & 0xFFFFFFFFL;
            long scaledUp = (s << SMOOTHING_BITS) & 0xFFFFFFFFL;
            long est = ((scaledUp * smoothing) + ((estimate[i] & 0xFFFFFFFFL) * oneMinus)) >>> NOISE_BITS;
            est &= 0xFFFFFFFFL;
            estimate[i] = (int) est;
            if (est > scaledUp) est = scaledUp;
            long floor = (s * minSignalRemaining) >>> NOISE_BITS;
            long subtracted = (scaledUp - est) >>> SMOOTHING_BITS;
            signal[i] = (int) (subtracted > floor ? subtracted : floor);
        }
    }

    private short wide(int xi) {
        long x = xi & 0xFFFFFFFFL;
        if (x <= 2) return gainLut[(int) x];
        int interval = msb(xi);
        int base = 4 * interval - 6;
        int frac = (int) (((interval < 11) ? (x << (11 - interval)) : (x >>> (interval - 11))) & 0x3FF);
        int result = (gainLut[base + 2] * frac) >> 5;
        result += (int) ((gainLut[base + 1] & 0xFFFFFFFFL) << 5);
        result *= frac;
        result = (result + (1 << 14)) >> 15;
        result += gainLut[base];
        return (short) result;
    }

    private static int shrink(int xi) {
        long x = xi & 0xFFFFFFFFL;
        if (x < (2 << PCAN_SNR_BITS)) return (int) ((x * x) >>> (2 + 2 * PCAN_SNR_BITS - PCAN_OUTPUT_BITS));
        return (int) ((x >>> (PCAN_SNR_BITS - PCAN_OUTPUT_BITS)) - (1 << PCAN_OUTPUT_BITS));
    }

    static final short[] LOG_LUT = {0, 224, 442, 654, 861, 1063, 1259, 1450, 1636, 1817, 1992, 2163, 2329, 2490, 2646,
            2797, 2944, 3087, 3224, 3358, 3487, 3611, 3732, 3848, 3960, 4068, 4172, 4272, 4368, 4460, 4549, 4633, 4714, 4791,
            4864, 4934, 5001, 5063, 5123, 5178, 5231, 5280, 5326, 5368, 5408, 5444, 5477, 5507, 5533, 5557, 5578, 5595, 5610,
            5622, 5631, 5637, 5640, 5641, 5638, 5633, 5626, 5615, 5602, 5586, 5568, 5547, 5524, 5498, 5470, 5439, 5406, 5370,
            5332, 5291, 5249, 5203, 5156, 5106, 5054, 5000, 4944, 4885, 4825, 4762, 4697, 4630, 4561, 4490, 4416, 4341, 4264,
            4184, 4103, 4020, 3935, 3848, 3759, 3668, 3575, 3481, 3384, 3286, 3186, 3084, 2981, 2875, 2768, 2659, 2549, 2437,
            2323, 2207, 2090, 1971, 1851, 1729, 1605, 1480, 1353, 1224, 1094, 963, 830, 695, 559, 421, 282, 142, 0, 0};

    private static long log2Fraction(long x, int log2x) {
        int frac = (int) (x - (1L << log2x));
        if (log2x < LOG_SCALE_LOG2) frac <<= LOG_SCALE_LOG2 - log2x; else frac >>= log2x - LOG_SCALE_LOG2;
        int baseSeg = (int) ((frac & 0xFFFFFFFFL) >>> (LOG_SCALE_LOG2 - LOG_SEGMENTS_LOG2));
        int segUnit = (1 << LOG_SCALE_LOG2) >> LOG_SEGMENTS_LOG2;
        int c0 = LOG_LUT[baseSeg], c1 = LOG_LUT[baseSeg + 1];
        int segBase = segUnit * baseSeg;
        int relPos = ((c1 - c0) * (frac - segBase)) >> LOG_SCALE_LOG2;
        return (frac + c0 + relPos) & 0xFFFFFFFFL;
    }

    private static int log(int xi) {
        long x = xi & 0xFFFFFFFFL;
        int integer = msb(xi) - 1;
        long fraction = log2Fraction(x, integer);
        long log2 = (((long) integer << LOG_SCALE_LOG2) + fraction) & 0xFFFFFFFFL;
        long round = LOG_SCALE / 2;
        long loge = ((LOG_COEFF * log2 + round) >>> LOG_SCALE_LOG2) & 0xFFFFFFFFL;
        long scaled = (((loge << SCALE_SHIFT) & 0xFFFFFFFFL) + round) >>> LOG_SCALE_LOG2;
        return (int) scaled;
    }
}
