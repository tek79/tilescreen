package app.tilescreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

import app.tilescreen.ui.CallMedia;

/**
 * A call's stream from the add-on (live_stream.py): multipart parts, each a JPEG of the other's picture or a piece of its
 * voice (`audio/x-s16le;rate=16000`, X-Sound: talk). The voice plays on Android's voice path with its echo canceller's
 * reference, after a run-up of 200 ms, which kept a call's voice whole on the add-on's measurements (docs/CALLS.md); a
 * voice that falls more than a second behind is let go by the add-on (live_stream.SOUND_QUEUE), so it is never late. Nothing of it runs outside a call.
 */
final class CallStream implements CallMedia {
    private static final int RATE = 16000;
    private static final int RUN_UP = RATE * 2 / 5;      // 200 ms of bytes
    private static final int MOST_WAITING = RATE * 2;    // a second of bytes
    private final Context context;
    private volatile Thread worker;
    private volatile Socket socket;
    private volatile boolean stopping;
    private volatile Bitmap picture;
    private volatile long heard;
    private int savedMode = -1;
    private boolean savedSpeaker;

    CallStream(Context c) { context = c.getApplicationContext(); }

    public Bitmap picture() { return picture; }

    public boolean hearing() { return SystemClock.uptimeMillis() - heard < 600; }

    public synchronized void start(final String link, final Runnable changed) {
        stop();
        stopping = false;
        picture = null;
        voicePath(true);
        Thread t = new Thread(new Runnable() {
            public void run() { read(link, changed); }
        }, "call-stream");
        worker = t;
        t.start();
    }

    public synchronized void stop() {
        stopping = true;
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        Thread t = worker;
        worker = null;
        if (t != null) {
            try { t.join(1500); } catch (InterruptedException ignored) {}
        }
        picture = null;
        voicePath(false);
    }

    /** Android's voice path for a call: its echo canceller hears what plays, and the loudspeaker plays it. */
    private void voicePath(boolean on) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (on && savedMode < 0) {
                savedMode = am.getMode();
                savedSpeaker = am.isSpeakerphoneOn();
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(true);
            } else if (!on && savedMode >= 0) {
                am.setMode(savedMode);
                am.setSpeakerphoneOn(savedSpeaker);
                savedMode = -1;
            }
        } catch (Exception e) {
            App.get().log("call: the voice path: " + e);
        }
    }

    private void read(String link, Runnable changed) {
        AudioTrack track = null;
        try {
            URI u = new URI(link);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 80;
            String path = u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            Socket s = new Socket();
            socket = s;
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(15000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            out.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), 16384);
            String status = line(in);
            if (status == null || !status.contains(" 200")) {
                App.get().log("call: the stream said " + status);
                return;
            }
            while (true) {
                String h = line(in);
                if (h == null) return;
                if (h.isEmpty()) break;
            }
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_VOICE_CALL, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, MOST_WAITING), AudioTrack.MODE_STREAM);
            boolean playing = false;
            int waiting = 0;
            long pictures = 0, sounds = 0;
            while (!stopping) {
                String h = line(in);
                if (h == null) break;
                if (!h.startsWith("--")) continue;
                String type = "";
                int length = -1;
                while (true) {
                    String head = line(in);
                    if (head == null) return;
                    if (head.isEmpty()) break;
                    int colon = head.indexOf(':');
                    if (colon < 0) continue;
                    String name = head.substring(0, colon).trim().toLowerCase(java.util.Locale.US), value = head.substring(colon + 1).trim();
                    if (name.equals("content-type")) type = value;
                    else if (name.equals("content-length")) {
                        try { length = Integer.parseInt(value); } catch (NumberFormatException e) { length = -1; }
                    }
                }
                if (length < 0 || length > 2 * 1024 * 1024) break;
                byte[] body = new byte[length];
                int at = 0;
                while (at < length) {
                    int n = in.read(body, at, length - at);
                    if (n < 0) return;
                    at += n;
                }
                if (type.startsWith("image/")) {
                    Bitmap b = BitmapFactory.decodeByteArray(body, 0, length);
                    if (b != null) {
                        picture = b;
                        pictures++;
                        if (changed != null) changed.run();
                    }
                } else if (type.startsWith("audio/")) {
                    int whole = length & ~1;
                    heard = SystemClock.uptimeMillis();
                    sounds++;
                    if (!playing) {
                        // The run-up: a little voice waits before the first plays, so a late part finds some left.
                        track.write(body, 0, whole);
                        waiting += whole;
                        if (waiting >= RUN_UP) {
                            track.play();
                            playing = true;
                        }
                    } else {
                        track.write(body, 0, whole);
                    }
                }
            }
            App.get().log("call: stream ended, " + pictures + " pictures, " + sounds + " parts of voice");
        } catch (Exception e) {
            if (!stopping) App.get().log("call: stream: " + e);
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                track.release();
            }
            Socket s = socket;
            socket = null;
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** One line of the head, without its CRLF; null at the end. */
    private static String line(InputStream in) throws java.io.IOException {
        StringBuilder b = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) return b.length() == 0 ? null : b.toString();
            if (c == '\n') {
                int n = b.length();
                if (n > 0 && b.charAt(n - 1) == '\r') b.setLength(n - 1);
                return b.toString();
            }
            b.append((char) c);
            if (b.length() > 512) return b.toString();
        }
    }
}
