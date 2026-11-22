package app.tilescreen.core;

/**
 * The wake word on the tablet itself, as Tessera's voice_assist plugin has it on a screen (ESPHome's micro_wake_word):
 * the microphone's sound goes through the microfrontend (MicroFrontend), one wake phrase's model and the voice activity
 * model (MicroModel) hear it, and a phrase counts once the average of its last few probabilities passes the model's
 * cutoff while the voice model hears a voice. The numbers are micro_wake_word's (streaming_model.cpp): cutoffs from the
 * models' manifests quantized as ESPHome does, a sliding window of 5, a second of quiet before the first detection and
 * after each one. Nothing here leaves the tablet; this class only says which phrase it heard.
 */
public final class WakeWord {
    /** The three phrases Tessera's plugin carries, in its Wake phrase order: {id, phrase, cutoff, window}. */
    public static final String[] IDS = {"okay_nabu", "hey_jarvis", "hey_mycroft"};
    public static final String[] PHRASES = {"Okay Nabu", "Hey Jarvis", "Hey Mycroft"};
    static final float[] CUTOFFS = {0.97f, 0.97f, 0.95f};
    static final float VAD_CUTOFF = 0.5f;
    static final int WINDOW = 5, VAD_WINDOW = 5;
    /** micro_wake_word's MIN_SLICES_BEFORE_DETECTION: feature rows (10 ms) of quiet before a phrase counts. */
    static final int QUIET_SLICES = 100;

    /** Where the models come from (the app's assets; files in the test). */
    public interface Models { byte[] load(String id) throws java.io.IOException; }

    private final Models models;
    private final MicroFrontend frontend = new MicroFrontend();
    private final byte[] row = new byte[MicroFrontend.CHANNELS];
    private final short[] samples = new short[MicroFrontend.RATE / 50];
    private Streaming phrase, vad;
    private int chosen = -1;
    /** Probabilities of the last detection, for the log: average and most, 0..255. */
    public int lastAverage, lastMost;
    /** A phrase the voice model didn't hear as a voice (micro_wake_word logs these too). */
    public volatile String blocked;

    public WakeWord(Models models) { this.models = models; }

    /** Which phrase listens (Wake phrase, 0..2); loads its model. */
    public synchronized void choose(int index) throws java.io.IOException {
        index = Math.max(0, Math.min(IDS.length - 1, index));
        if (index == chosen && phrase != null) return;
        phrase = new Streaming(new MicroModel(models.load(IDS[index])), (int) (CUTOFFS[index] * 255), WINDOW);
        if (vad == null) vad = new Streaming(new MicroModel(models.load("vad")), (int) (VAD_CUTOFF * 255), VAD_WINDOW);
        chosen = index;
        reset();
    }

    public synchronized String phrase() { return chosen < 0 ? "" : PHRASES[chosen]; }

    /** As when the microphone opens: silence before, and a second of quiet before a phrase counts. */
    public synchronized void reset() {
        frontend.reset();
        if (phrase != null) phrase.reset();
        if (vad != null) vad.reset();
    }

    /** 16 kHz 16-bit little-endian mono; returns the phrase when this part completed it, else null. */
    public synchronized String hear(byte[] pcm, int length) {
        if (phrase == null) return null;
        String heard = null;
        int n = length / 2;
        short[] s = n <= samples.length ? samples : new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        for (int at = 0; at < n; ) {
            at += frontend.take(s, at, n - at, row);
            if (!frontend.ready) continue;
            phrase.feed(row);
            vad.feed(row);
            if (!phrase.fresh) continue;
            phrase.fresh = false;
            if (phrase.ignore < 0) continue;
            int sum = 0, most = 0;
            for (int p : phrase.recent) { sum += p; most = Math.max(most, p); }
            if (sum <= phrase.cutoff * phrase.recent.length) continue;
            int vadSum = 0;
            for (int p : vad.recent) vadSum += p;
            if (vadSum > vad.cutoff * vad.recent.length) {
                lastAverage = sum / phrase.recent.length;
                lastMost = most;
                heard = PHRASES[chosen];
                phrase.reset();
            } else {
                blocked = PHRASES[chosen];
            }
        }
        return heard;
    }

    /** One model streaming one feature row at a time (StreamingModel). */
    static final class Streaming {
        final MicroModel model;
        final int cutoff;
        final int[] recent;
        int last, step, ignore = -QUIET_SLICES;
        boolean fresh;

        Streaming(MicroModel model, int cutoff, int window) {
            this.model = model;
            this.cutoff = cutoff;
            this.recent = new int[window];
        }

        void reset() {
            java.util.Arrays.fill(recent, 0);
            ignore = -QUIET_SLICES;
        }

        void feed(byte[] row) {
            byte[] in = model.input();
            step %= model.stride;
            System.arraycopy(row, 0, in, step * model.features, model.features);
            if (++step >= model.stride) {
                int p = model.invoke();
                if (++last == recent.length) last = 0;
                recent[last] = p;
                fresh = true;
            }
            if (recent[last] < cutoff) ignore = Math.min(ignore + 1, 0);
        }
    }
}
