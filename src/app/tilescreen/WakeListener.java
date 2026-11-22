package app.tilescreen;

import android.os.SystemClock;

import app.tilescreen.core.Assist;
import app.tilescreen.core.EspDevice;
import app.tilescreen.core.ScreenSettings;
import app.tilescreen.core.WakeWord;

/**
 * The wake word's microphone (Tessera's voice_assist: micro_wake_word on the screen). While Wake word is on, Home
 * Assistant takes the screen as its voice assistant and the app is on the glass, one recording runs and WakeWord hears
 * it; a question uses the same recording (attach), as voice_assistant shares micro_wake_word's microphone. A call or
 * the doorbell's Talk takes the microphone for itself (take): the wake word closes its recording first and opens it
 * again once they are done.
 *
 * Android before 10 gives the microphone to one recording at a time: while this listens, other apps (another voice
 * assistant, a recorder) can't record. It rests while another app or Android's screensaver is in front, so they can.
 */
final class WakeListener {
    private final App app;
    private final EspDevice device;
    private final ScreenSettings settings;
    private final Assist assist;
    private final WakeWord word;
    private final Object lock = new Object();

    private volatile boolean visible = true, live, closed;
    private volatile int taken;
    private volatile Assist.Sink sink;
    private volatile boolean changed;
    private Thread worker;
    /** How much of a core hearing takes, measured once over the first minute of listening. */
    private long hearNanos, heardParts;
    private boolean measured;

    WakeListener(App app, EspDevice device, ScreenSettings settings, Assist assist) {
        this.app = app;
        this.device = device;
        this.settings = settings;
        this.assist = assist;
        this.word = new WakeWord(new WakeWord.Models() {
            public byte[] load(String id) throws java.io.IOException {
                java.io.InputStream in = WakeListener.this.app.getAssets().open("wake/" + id + ".tflite");
                try {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    return out.toByteArray();
                } finally {
                    in.close();
                }
            }
        });
    }

    /** Wake word, Wake phrase, Home Assistant or the glass changed: listen or rest accordingly. */
    synchronized void update() {
        if (closed) return;
        changed = true;
        synchronized (lock) { lock.notifyAll(); }
        if (!settings.assistWakeWord()) return; // a worker that runs sees it and rests
        if (worker != null) return; // it runs for good once started, idle while it rests
        worker = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "wake");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    /** The screen goes (a restart): the recording closes and the thread ends. */
    void close() {
        closed = true;
        synchronized (lock) { lock.notifyAll(); }
    }

    void visible(boolean on) {
        visible = on;
        update();
    }

    /** Whether a question can use this recording now. */
    boolean attach(Assist.Sink s) {
        if (!live) return false;
        sink = s;
        return true;
    }

    void detach() { sink = null; }

    /**
     * A call or Talk takes the microphone (`on`), or gives it back. Taking waits, up to two seconds, until the wake
     * word's recording is closed, so the call's opens on a free microphone.
     */
    void take(boolean on) {
        synchronized (lock) {
            taken = Math.max(0, taken + (on ? 1 : -1));
            lock.notifyAll();
            if (!on) return;
            long until = SystemClock.uptimeMillis() + 2000;
            while (live && SystemClock.uptimeMillis() < until) {
                try { lock.wait(100); } catch (InterruptedException e) { return; }
            }
        }
    }

    private boolean wanted() {
        return !closed && settings.assistWakeWord() && visible && taken == 0 && device.voiceReady()
                // a question that opened the microphone itself (Wake word was off) keeps it until it is done
                && (live || !assist.busy());
    }

    private void loop() {
        Mic.Input in = null;
        byte[] part = new byte[Mic.PART];
        int failures = 0;
        long blockedLogged = 0;
        try {
            while (!closed) {
                if (!wanted()) {
                    if (in != null) {
                        in.close();
                        in = null;
                        setLive(false);
                        app.log("wake: resting (" + reason() + ")");
                    }
                    synchronized (lock) {
                        lock.notifyAll();
                        try { lock.wait(500); } catch (InterruptedException e) { return; }
                    }
                    continue;
                }
                if (in == null) {
                    try {
                        word.choose(settings.assistWakePhrase());
                    } catch (Exception e) {
                        app.log("wake: the model didn't load (" + e + ")");
                        waitChange(60000);
                        continue;
                    }
                    in = Mic.open(true);
                    if (in == null) {
                        app.log("wake: no input of the microphone gave sound; trying again in a minute");
                        waitChange(60000);
                        continue;
                    }
                    word.reset();
                    changed = false;
                    setLive(true);
                    app.log("wake: listening for \"" + word.phrase() + "\"");
                }
                if (changed) {
                    // The phrase may be another now; the recording stays.
                    changed = false;
                    try {
                        String before = word.phrase();
                        word.choose(settings.assistWakePhrase());
                        if (!before.equals(word.phrase())) app.log("wake: listening for \"" + word.phrase() + "\"");
                    } catch (Exception e) {
                        app.log("wake: the model didn't load (" + e + ")");
                    }
                }
                if (!in.read(part)) {
                    app.log("wake: the microphone stopped (read " + in.lastRead + ")");
                    in.close();
                    in = null;
                    setLive(false);
                    if (++failures > 3) {
                        app.log("wake: the microphone keeps stopping; trying again in a minute");
                        waitChange(60000);
                        failures = 0;
                    }
                    continue;
                }
                failures = 0;
                Assist.Sink s = sink;
                if (s != null) s.part(part.clone());
                long t0 = System.nanoTime();
                String heard = word.hear(part, part.length);
                measure(System.nanoTime() - t0);
                String blocked = word.blocked;
                if (blocked != null) {
                    word.blocked = null;
                    // It says so at every step while it lasts; once in a few seconds is enough for the log.
                    if (SystemClock.uptimeMillis() - blockedLogged > 5000) {
                        blockedLogged = SystemClock.uptimeMillis();
                        app.log("wake: \"" + blocked + "\" without a voice behind it, left alone");
                    }
                }
                if (heard != null) {
                    app.log("wake: heard \"" + heard + "\" (average " + percent(word.lastAverage) + ", most " + percent(word.lastMost) + ")");
                    final String phrase = heard;
                    // The question starts over the network; the recording goes on meanwhile.
                    new Thread(new Runnable() {
                        public void run() { assist.wake(phrase); }
                    }, "wake-ask").start();
                }
            }
        } finally {
            if (in != null) in.close();
            setLive(false);
        }
    }

    private void setLive(boolean on) {
        synchronized (lock) {
            live = on;
            if (!on) sink = null;
            lock.notifyAll();
        }
    }

    private void waitChange(long ms) {
        changed = false;
        long until = SystemClock.uptimeMillis() + ms;
        synchronized (lock) {
            while (!changed && !closed && SystemClock.uptimeMillis() < until) {
                try { lock.wait(500); } catch (InterruptedException e) { return; }
            }
        }
    }

    private String reason() {
        if (!settings.assistWakeWord()) return "Wake word is off";
        if (taken > 0) return "a call or Talk has the microphone";
        if (!visible) return "another app or Android's screensaver is in front";
        return "Home Assistant isn't connected";
    }

    private void measure(long nanos) {
        if (measured) return;
        hearNanos += nanos;
        if (++heardParts == 3000) { // a minute of 20 ms parts
            measured = true;
            app.log("wake: hearing takes " + String.format(java.util.Locale.US, "%.1f", hearNanos / 600000000.0)
                    + "% of one core");
        }
    }

    private static String percent(int p) { return Math.round(p * 100 / 255f) + "%"; }
}
