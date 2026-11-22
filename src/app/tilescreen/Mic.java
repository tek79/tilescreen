package app.tilescreen;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.SystemClock;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

import app.tilescreen.core.Voice;

/**
 * The microphone up to the add-on (Voice.Uplink, talk_uplink.cpp): plain 16 kHz 16-bit mono PCM in 20 ms chunks of a
 * chunked POST. The add-on answers once the far end stands; until then the microphone's parts are thrown away, so nobody
 * hears what was said before the key said Talking.
 *
 * Tablets differ in what their microphone takes. The voice path (VOICE_COMMUNICATION, with Android's echo canceller) is
 * tried first, then the plain microphone and the default input, each at 16 kHz and then at 48 or 44.1 kHz brought down
 * to 16 kHz here: some audio drivers open an input that never delivers a sample (every read 0), so an input counts only
 * once it has given sound. The log says which one it took.
 *
 * Nothing runs until something starts it, and it all stops with stop(): the microphone is open only while Talk or a
 * call is. The wake word (WakeListener) gives the microphone up for that time.
 */
final class Mic implements Voice.Uplink {
    static final int RATE = 16000;
    static final int PART = RATE * 2 * 20 / 1000;   // 20 ms at 16 kHz, one chunk
    static final long ANSWER_MS = 18000;            // the add-on connects the far end first (talk_bridge.ANSWER_SECONDS)
    static final float FLOOR_DB = -55f, CEILING_DB = -18f, VOICE_DB = -44f;

    private volatile int state = Voice.OFF;
    private volatile float level;
    private volatile long lastVoice;
    private volatile boolean muted;
    private Thread worker;
    private volatile Socket socket;
    private volatile boolean stopping;
    /** The input that worked last, tried first next time: {source, rate, effects 1/0}; the wake word's apart. */
    private static volatile int[] good, goodWake;

    /** Whether the tablet can record at all. */
    static boolean usable() {
        try {
            return AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
                    || AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean echoCanceller() {
        try {
            return android.os.Build.VERSION.SDK_INT >= 16 && AcousticEchoCanceler.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized boolean start(final String url) {
        if (worker != null && worker.isAlive()) return false;
        stopping = false;
        muted = false;
        state = Voice.CONNECTING;
        level = 0;
        lastVoice = SystemClock.uptimeMillis();
        worker = new Thread(new Runnable() {
            public void run() { talk(url); }
        }, "voice");
        worker.start();
        return true;
    }

    public void stop() {
        stopping = true;
        Socket s = socket;
        if (s != null) {
            try { s.shutdownInput(); } catch (Exception ignored) {}
        }
    }

    public int state() { return state; }
    public float level() { return level; }
    public long lastVoice() { return lastVoice; }
    public void mute(boolean on) { muted = on; }

    // ----- one input of the microphone -----

    private static final int[] SOURCES = {MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT};
    /** The wake word hears best what a speech recognizer would: the voice recognition input, plain. */
    private static final int[] WAKE_SOURCES = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT};
    private static final int[] RATES = {16000, 48000, 44100};

    private static String sourceName(int s) {
        return s == MediaRecorder.AudioSource.VOICE_COMMUNICATION ? "voice path"
                : s == MediaRecorder.AudioSource.VOICE_RECOGNITION ? "voice recognition input"
                : s == MediaRecorder.AudioSource.MIC ? "microphone" : "default input";
    }

    /** An input that gives sound, with Android's echo canceller, noise suppressor and gain control where it has them. */
    static final class Input {
        final AudioRecord rec;
        final int source, rate;
        final short[] raw;
        AcousticEchoCanceler aec;
        NoiseSuppressor ns;
        AutomaticGainControl agc;
        double at;          // where the next 16 kHz sample falls in `raw`, for an input at another rate
        int rawHave;

        int lastRead;       // what Android's read said last, for the log

        Input(AudioRecord rec, int source, int rate, boolean effects) {
            this.rec = rec;
            this.source = source;
            this.rate = rate;
            this.raw = new short[rate / 50 + 2];
            if (effects && android.os.Build.VERSION.SDK_INT >= 16) {
                try {
                    int session = rec.getAudioSessionId();
                    if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(session); if (aec != null) aec.setEnabled(true); }
                    if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(session); if (ns != null) ns.setEnabled(true); }
                    if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(session); if (agc != null) agc.setEnabled(true); }
                } catch (Throwable ignored) {
                    // an effect a driver refuses: the voice goes without it
                }
            }
        }

        /**
         * 20 ms at 16 kHz into `out` (little-endian bytes); false when the input stopped giving sound. An input at 48 or
         * 44.1 kHz is brought down by linear steps between its samples; for a voice that is clean enough.
         */
        boolean read(byte[] out) {
            int n = PART / 2;
            if (rate == RATE) {
                short[] s = new short[n];
                int got = 0;
                while (got < n) {
                    int r = rec.read(s, got, n - got);
                    lastRead = r;
                    if (r <= 0) return false;
                    got += r;
                }
                for (int i = 0; i < n; i++) { out[2 * i] = (byte) s[i]; out[2 * i + 1] = (byte) (s[i] >> 8); }
                return true;
            }
            double step = rate / (double) RATE;
            for (int i = 0; i < n; i++) {
                int need = (int) Math.floor(at) + 2;
                while (rawHave < need) {
                    if (rawHave >= raw.length) {
                        // what was used goes, what is left slides to the front
                        int keep = Math.min((int) Math.floor(at), rawHave);
                        System.arraycopy(raw, keep, raw, 0, rawHave - keep);
                        rawHave -= keep;
                        at -= keep;
                        need -= keep;
                        continue;
                    }
                    int r = rec.read(raw, rawHave, raw.length - rawHave);
                    lastRead = r;
                    if (r <= 0) return false;
                    rawHave += r;
                }
                int k = (int) Math.floor(at);
                double f = at - k;
                int v = (int) Math.round(raw[k] * (1 - f) + raw[k + 1] * f);
                out[2 * i] = (byte) v;
                out[2 * i + 1] = (byte) (v >> 8);
                at += step;
            }
            return true;
        }

        void close() {
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
            if (aec != null) aec.release();
            if (ns != null) ns.release();
            if (agc != null) agc.release();
        }
    }

    /** The first input that opens, records and gives a part of sound within half a second; null when none does. */
    static Input open() { return open(false); }

    /**
     * The same for the wake word (`wake`): the voice recognition input without Android's effects first, as the wake word
     * models were trained on plain sound and Home Assistant cleans up the question itself.
     */
    static Input open(boolean wake) {
        java.util.List<int[]> tries = new java.util.ArrayList<int[]>();
        int[] last = wake ? goodWake : good;
        if (last != null) tries.add(last);
        // With Android's echo canceller, noise suppressor and gain control first; then without them, which some drivers
        // can't take on a recording (it opens and never gives a sample). The wake word the other way round.
        for (int e = 0; e < 2; e++) {
            int effects = wake ? e : 1 - e;
            for (int rate : RATES) for (int source : wake ? WAKE_SOURCES : SOURCES) tries.add(new int[]{source, rate, effects});
        }
        App.get().log("voice: finding the microphone" + (wake ? " for the wake word" : "") + " (Android "
                + android.os.Build.VERSION.RELEASE + ", " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ")");
        for (int[] t : tries) {
            Input in = tryInput(t[0], t[1], t[2] == 1);
            if (in != null) {
                if (wake) goodWake = t; else good = t;
                App.get().log("voice: " + sourceName(t[0]) + " at " + t[1] + " Hz"
                        + (t[2] == 1 ? "" : ", without Android's voice effects")
                        + (in.aec != null ? ", echo cancelling" : "") + (in.ns != null ? ", noise suppression" : ""));
                return in;
            }
        }
        return null;
    }

    private static Input tryInput(int source, int rate, boolean effects) {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return null;
            rec = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, rate / 50 * 2 * 10));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return null;
            }
            Input in = new Input(rec, source, rate, effects);
            String what = "the " + sourceName(source) + " at " + rate + " Hz" + (effects ? "" : " without effects");
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                App.get().log("voice: " + what + " didn't start (" + rec.getRecordingState() + ")");
                in.close();
                return null;
            }
            // It counts once it gave sound: a read of 0 means a driver opened an input that never delivers.
            byte[] probe = new byte[PART];
            long until = SystemClock.uptimeMillis() + 500;
            boolean gave = false;
            while (SystemClock.uptimeMillis() < until) {
                if (in.read(probe)) {
                    gave = true;
                    break;
                }
            }
            if (!gave) {
                App.get().log("voice: " + what + " gave no sound (read " + in.lastRead + ")");
                in.close();
                return null;
            }
            return in;
        } catch (Throwable t) {
            if (rec != null) {
                try { rec.release(); } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    // ----- the talk -----

    private void talk(String url) {
        Input mic = null;
        int ended = Voice.FAILED;
        App.get().holdSpeaker(true);
        App.get().takeMicrophone(true);
        try {
            URI u = new URI(url);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 80;
            String path = u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            mic = open();
            if (mic == null) {
                App.get().log("voice: no input of the microphone gave sound");
                return;
            }
            Socket s = new Socket();
            socket = s;
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(200);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            String head = "POST " + path + " HTTP/1.1\r\nHost: " + host + ":" + port
                    + "\r\nContent-Type: audio/x-s16le;rate=16000\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes("US-ASCII"));
            out.flush();
            byte[] part = new byte[PART];
            // The answer's head comes once the far end stands; the microphone's parts meanwhile go nowhere.
            StringBuilder answer = new StringBuilder();
            long since = SystemClock.uptimeMillis();
            int status = 0;
            byte[] one = new byte[256];
            while (!stopping && SystemClock.uptimeMillis() - since < ANSWER_MS) {
                mic.read(part);
                try {
                    int n = in.read(one);
                    if (n < 0) break;
                    answer.append(new String(one, 0, n, "ISO-8859-1"));
                    int end = answer.indexOf("\r\n");
                    if (end > 0) {
                        String[] first = answer.substring(0, end).split(" ");
                        status = first.length > 1 ? Integer.parseInt(first[1].trim()) : 0;
                        break;
                    }
                } catch (java.net.SocketTimeoutException waiting) {
                    // not yet
                }
            }
            if (status == 409) {
                ended = Voice.BUSY;
                App.get().log("voice: someone else is talking there");
                return;
            }
            if (status != 200) {
                App.get().log(status == 0 ? "voice: no answer from the add-on" : "voice: the add-on said " + status);
                return;
            }
            state = Voice.LIVE;
            App.get().log("voice: live after " + (SystemClock.uptimeMillis() - since) + " ms");
            byte[] chunkHead = Integer.toHexString(PART).concat("\r\n").getBytes("US-ASCII");
            byte[] crlf = {'\r', '\n'};
            float smooth = 0;
            int sent = 0, reopened = 0;
            while (!stopping) {
                if (!mic.read(part)) {
                    if (stopping) break;
                    // Android changed its audio path under the recording (a call's voice path, another app): the
                    // microphone opens again, a few times at most, and the voice goes on.
                    App.get().log("voice: the microphone stopped (read " + mic.lastRead + ")");
                    mic.close();
                    mic = ++reopened > 3 ? null : open();
                    if (mic == null) {
                        App.get().log("voice: the microphone stopped giving sound");
                        break;
                    }
                    App.get().log("voice: the microphone opened again");
                    continue;
                }
                if (muted) java.util.Arrays.fill(part, (byte) 0);
                out.write(chunkHead);
                out.write(part);
                out.write(crlf);
                out.flush();
                sent++;
                float db = levelDb(part);
                float bar = db <= FLOOR_DB ? 0 : db >= CEILING_DB ? 1 : (db - FLOOR_DB) / (CEILING_DB - FLOOR_DB);
                smooth = Math.max(bar, smooth * 0.8f);
                level = smooth;
                if (db > VOICE_DB && !muted) lastVoice = SystemClock.uptimeMillis();
            }
            try {
                out.write("0\r\n\r\n".getBytes("US-ASCII"));
                out.flush();
            } catch (Exception ignored) {}
            App.get().log("voice: ended, " + sent + " parts sent");
            ended = Voice.OFF;
        } catch (Exception e) {
            if (stopping) ended = Voice.OFF;
            else App.get().log("voice: " + e);
        } finally {
            if (mic != null) mic.close();
            Socket s = socket;
            socket = null;
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
            level = 0;
            state = stopping ? Voice.OFF : ended;
            App.get().holdSpeaker(false);
            App.get().takeMicrophone(false);
        }
    }

    /** dB below full scale of a part's RMS; -120 for silence. */
    static float levelDb(byte[] pcm) {
        double sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i < n; i++) {
            int v = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            sum += (double) v * v;
        }
        double rms = n == 0 ? 0 : Math.sqrt(sum / n);
        return rms < 1 ? -120f : (float) (20 * Math.log10(rms / 32768.0));
    }
}
