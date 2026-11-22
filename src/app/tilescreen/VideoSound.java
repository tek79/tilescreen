package app.tilescreen;

import app.tilescreen.core.Assist;
import app.tilescreen.core.Rtsp;

/**
 * The microphone for the video while someone watches (Tessera's Screen camera, part Video: the microphone listens only
 * then). Android before 10 lets one recording use the microphone at a time, so the video shares: it rides on the wake
 * word's recording while that runs, and otherwise records itself, holding the wake word back meanwhile (as a call does)
 * and letting a question ride on it. A call or Talk takes the microphone from it (pause); the video's sound has a gap
 * then.
 */
final class VideoSound {
    private final App app;
    private final Rtsp rtsp;
    private final Object lock = new Object();
    private volatile boolean on, own;
    private volatile int paused;
    private volatile Assist.Sink guest;
    private Thread worker;

    private final Assist.Sink toVideo = new Assist.Sink() {
        public void part(byte[] pcm) { rtsp.sound(pcm, System.nanoTime()); }
    };

    VideoSound(App app, Rtsp rtsp) {
        this.app = app;
        this.rtsp = rtsp;
    }

    synchronized void start() {
        if (on) return;
        on = true;
        worker = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "video-sound");
        worker.start();
    }

    synchronized void stop() {
        on = false;
        synchronized (lock) { lock.notifyAll(); }
    }

    /** A call or Talk takes the microphone (true), or gives it back. */
    void pause(boolean p) {
        synchronized (this) { paused = Math.max(0, paused + (p ? 1 : -1)); }
        synchronized (lock) { lock.notifyAll(); }
        // Taking waits, up to two seconds, until the video's own recording is closed, so the call's opens on a free
        // microphone.
        long until = System.currentTimeMillis() + 2000;
        while (p && own && System.currentTimeMillis() < until) {
            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
        }
    }

    /** A question rides on the video's own recording, when it has one. */
    boolean attach(Assist.Sink s) {
        if (!own) return false;
        guest = s;
        return true;
    }

    void detach() { guest = null; }

    private void loop() {
        WakeListener tapped = null;
        app.log("video: the microphone listens while someone watches");
        try {
            while (on) {
                WakeListener w = app.wakeListener();
                if (paused > 0) {
                    if (tapped != null) { tapped.tap = null; tapped = null; }
                    rest(300);
                    continue;
                }
                if (w != null && w.live()) {
                    // The wake word's recording runs: the video hears it too.
                    if (tapped != w) {
                        if (tapped != null) tapped.tap = null;
                        w.tap = toVideo;
                        tapped = w;
                    }
                    rest(300);
                    continue;
                }
                if (tapped != null) { tapped.tap = null; tapped = null; }
                record(w);
            }
        } finally {
            if (tapped != null) tapped.tap = null;
            app.log("video: the microphone rests");
        }
    }

    /** Records itself until the video ends or a call takes the microphone, the wake word held back meanwhile. */
    private void record(WakeListener w) {
        if (w != null) w.take(true);
        Mic.Input in = null;
        try {
            in = Mic.open();
            if (in == null) {
                app.log("video: no input of the microphone gave sound; the video goes without for now");
                rest(10000);
                return;
            }
            own = true;
            byte[] part = new byte[Mic.PART];
            while (on && paused == 0 && in.read(part)) {
                rtsp.sound(part, System.nanoTime());
                Assist.Sink g = guest;
                if (g != null) g.part(part.clone());
            }
        } finally {
            own = false;
            guest = null;
            if (in != null) in.close();
            if (w != null) w.take(false);
        }
    }

    private void rest(long ms) {
        synchronized (lock) {
            try { lock.wait(ms); } catch (InterruptedException ignored) {}
        }
    }
}
