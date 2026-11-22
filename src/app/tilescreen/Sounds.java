package app.tilescreen;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The sounds you choose in Tessera (docs/SOUNDS.md, add-on 0.4.100): a call's ringtone (`rt`), a doorbell's chime (`ch`)
 * and an alert's sound (the op `sound`), each a file of the add-on (docs/SCREEN_FILES.md) named by its content: a WAV of
 * 16 kHz, mono, 16 bits, at the volume chosen for its kind. A file is downloaded once and kept by its name, in memory and
 * in the app's cache (so a restart needs no download); `none` is silence. A sound waits at most 1.5 s for a file on its
 * way, as on a Tessera screen; then the tablet plays its own (Chime), and an alert none.
 */
final class Sounds {
    static final String NONE = "none";
    static final long WAIT_MS = 1500;
    static final int MOST_BYTES = 6 * 16000 * 2 + 1024;   // a sound is at most 5 s; a little room
    static final int KEPT = 12;

    private static final Map<String, short[]> kept = new LinkedHashMap<String, short[]>(16, 0.75f, true);
    private static final Object lock = new Object();
    private static AudioTrack once, ring;

    /** A file's name in its link: /files/<sha256>.wav. */
    static String nameOf(String link) {
        int slash = link.lastIndexOf('/'), dot = link.lastIndexOf('.');
        String name = link.substring(slash + 1, dot > slash ? dot : link.length());
        return name.replaceAll("[^0-9A-Za-z_-]", "");
    }

    /** The samples of `link`, downloading it if needed; null when it can't be had within `waitMs`. */
    static short[] get(final String link, long waitMs) {
        final String name = nameOf(link);
        if (name.isEmpty()) return null;
        synchronized (lock) {
            short[] s = kept.get(name);
            if (s != null) return s;
        }
        File disk = new File(App.get().getCacheDir(), "sounds/" + name + ".wav");
        if (disk.isFile()) {
            try {
                short[] s = wav(read(new FileInputStream(disk)));
                if (s != null) {
                    keep(name, s);
                    return s;
                }
            } catch (Exception ignored) {
                // fetched again below
            }
        }
        final short[][] got = new short[1][];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(link).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(10000);
                    if (c.getResponseCode() != 200) {
                        App.get().log("sound: " + link + " said " + c.getResponseCode());
                        return;
                    }
                    byte[] body = read(c.getInputStream());
                    short[] s = wav(body);
                    if (s == null) {
                        App.get().log("sound: " + name + " isn't a WAV of 16 kHz, mono, 16 bits");
                        return;
                    }
                    keep(name, s);
                    File dir = new File(App.get().getCacheDir(), "sounds");
                    dir.mkdirs();
                    FileOutputStream out = new FileOutputStream(new File(dir, name + ".wav"));
                    out.write(body);
                    out.close();
                    trimDisk(dir);
                    synchronized (got) {
                        got[0] = s;
                        got.notifyAll();
                    }
                } catch (Exception e) {
                    App.get().log("sound: downloading " + name + " failed (" + e + ")");
                }
            }
        }, "sound-fetch");
        t.start();
        long until = SystemClock.uptimeMillis() + waitMs;
        synchronized (got) {
            while (got[0] == null && t.isAlive() && SystemClock.uptimeMillis() < until) {
                try { got.wait(50); } catch (InterruptedException e) { break; }
            }
            return got[0];
        }
    }

    private static void keep(String name, short[] s) {
        synchronized (lock) {
            kept.put(name, s);
            while (kept.size() > KEPT) kept.remove(kept.keySet().iterator().next());
        }
    }

    private static void trimDisk(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEPT) return;
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        for (int i = 0; i < files.length - KEPT; i++) files[i].delete();
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MOST_BYTES) throw new java.io.IOException("larger than a sound may be");
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** The samples of a WAV of 16 kHz, mono, 16 bits (wav.h's checks), else null. */
    static short[] wav(byte[] b) {
        if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F' || b[8] != 'W' || b[9] != 'A') return null;
        int at = 12, rate = 0, channels = 0, bits = 0, format = 0;
        while (at + 8 <= b.length) {
            String id = new String(b, at, 4, java.nio.charset.Charset.forName("US-ASCII"));
            int size = le32(b, at + 4);
            int body = at + 8;
            if (size < 0 || body + size > b.length) size = b.length - body;
            if (id.equals("fmt ") && size >= 16) {
                format = le16(b, body);
                channels = le16(b, body + 2);
                rate = le32(b, body + 4);
                bits = le16(b, body + 14);
            } else if (id.equals("data")) {
                if (format != 1 || channels != 1 || rate != 16000 || bits != 16) return null;
                short[] s = new short[size / 2];
                for (int i = 0; i < s.length; i++) s[i] = (short) ((b[body + 2 * i] & 0xFF) | (b[body + 2 * i + 1] << 8));
                return s.length > 0 ? s : null;
            }
            at = body + size + (size & 1);
        }
        return null;
    }

    private static int le16(byte[] b, int at) { return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8); }

    private static int le32(byte[] b, int at) { return le16(b, at) | (le16(b, at + 2) << 16); }

    private static AudioTrack track(int stream, short[] s, boolean loop) {
        AudioTrack t = new AudioTrack(stream, 16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                s.length * 2, AudioTrack.MODE_STATIC);
        t.write(s, 0, s.length);
        if (loop) t.setLoopPoints(0, s.length, -1);
        t.play();
        return t;
    }

    /** Plays `s` once on `stream` (a chime, an alert's sound), replacing a sound that plays once already. */
    static void playOnce(short[] s, int stream, String what) {
        synchronized (lock) {
            try {
                if (once != null) {
                    try { once.stop(); } catch (Exception ignored) {}
                    once.release();
                }
                once = track(stream, s, false);
            } catch (Exception e) {
                App.get().log(what + ": the sound didn't play: " + e);
            }
        }
    }

    /** A ringtone back to back (it carries its own pause) on the ring stream, until stopRing. */
    static void startRing(short[] s) {
        synchronized (lock) {
            stopRingLocked();
            try {
                ring = track(AudioManager.STREAM_RING, s, true);
            } catch (Exception e) {
                App.get().log("call: the ringtone didn't play: " + e);
            }
        }
    }

    static void stopRing() {
        synchronized (lock) { stopRingLocked(); }
    }

    private static void stopRingLocked() {
        if (ring == null) return;
        try { ring.stop(); } catch (Exception ignored) {}
        ring.release();
        ring = null;
    }

    /** The op `sound` (an alert's, or a doorbell's on a screen that shows its alert): its file once, else nothing. */
    static void alert(final String link) {
        if (link == null || link.isEmpty() || link.equals(NONE)) return;
        new Thread(new Runnable() {
            public void run() {
                short[] s = get(link, WAIT_MS);
                if (s == null) App.get().log("sound: the alert's sound wasn't there in time; none played");
                else playOnce(s, AudioManager.STREAM_NOTIFICATION, "alert");
            }
        }, "sound").start();
    }

    /** A doorbell's chime: Tessera's chosen file, else the tablet's own; `none` is silence. */
    static void chime(final String link) {
        if (NONE.equals(link)) return;
        if (link == null || link.isEmpty()) {
            Chime.play();
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                short[] s = get(link, WAIT_MS);
                if (s == null) Chime.play();
                else playOnce(s, AudioManager.STREAM_NOTIFICATION, "doorbell");
            }
        }, "chime").start();
    }
}
