package app.tilescreen.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import app.tilescreen.core.Tessera;

/**
 * Pictures Tessera prepares for this screen (pkg/tessera/widgets/photo.go): ask for one, wait for the link the add-on
 * answers with, fetch it, and fetch it again as often as the picture changes. One slot per kind.
 */
final class Pictures {
    /** host:port of a link, for messages. */
    private static final class URL0 {
        final String u;
        URL0(String u) { this.u = u; }
        String host() {
            try {
                URL x = new URL(u);
                return x.getHost() + ":" + x.getPort();
            } catch (Exception e) {
                return u;
            }
        }
    }

    interface Redraw { void redraw(); }

    /** A link that does not come is asked for again after this, as camera_view::ASK_AGAIN_MS. */
    private static final long PATIENT = 10000;
    /** What a slot showed stays this long while the picture after it is on its way (MEDIA_FACE_WAIT_MS). */
    private static final long HOLD_OLD = 8000;
    private static final int FAILS = 3;

    private static final class Slot {
        String wish = "";
        Bitmap old;
        long oldUntil;
        long asked, fetched;
        boolean busy;
        int fails;
        Bitmap img;
        String url;
        /** A live stream's link this slot pulls pictures from now, or null; when it was last wanted. */
        volatile String liveUrl;
        volatile long lastWant;
        /** The most pictures a second it asks for. */
        volatile int pps = 5;
        /** A live link that ended: never pulled again, the next picture comes from a new question. */
        volatile String deadUrl;
    }

    private final Map<String, Slot> slots = new HashMap<String, Slot>();
    private final Redraw redraw;

    Pictures(Redraw redraw) { this.redraw = redraw; }

    private synchronized Slot slot(String kind) {
        Slot s = slots.get(kind);
        if (s == null) {
            s = new Slot();
            slots.put(kind, s);
        }
        return s;
    }

    /** Whether what is wanted of a kind changed; if so its picture is forgotten and asked for again. */
    synchronized boolean want(String kind, String wish) { return want(kind, wish, true); }

    /** As want(kind, wish); `holdOld` false when the picture shown no longer fits what is asked (another page's frames). */
    synchronized boolean want(String kind, String wish, boolean holdOld) {
        Slot s = slot(kind);
        if (wish.equals(s.wish)) return false;
        if (!holdOld) s.old = null;
        s.wish = wish;
        s.asked = 0;
        s.fetched = 0;
        s.fails = 0;
        // The picture it showed stays a moment, so a new track or a new size doesn't flash an empty tile.
        if (s.img != null && holdOld) {
            s.old = s.img;
            s.oldUntil = System.currentTimeMillis() + HOLD_OLD;
        }
        s.img = null;
        s.url = null;
        s.liveUrl = null;
        return true;
    }

    /** The most pictures a second a live slot of this kind asks for (a tile 5, a camera full screen 8). */
    synchronized void pace(String kind, int pps) { slot(kind).pps = Math.max(1, Math.min(10, pps)); }

    synchronized void forget(String kind) {
        slots.remove(kind);
    }

    /**
     * The picture of a kind as far as it has come, asking and fetching as needed: `every` is how often a fresh one
     * is fetched, `ask` asks the add-on for a link.
     */
    Bitmap keepFresh(Tessera tessera, String kind, String entity, long every, Runnable ask) {
        long now = System.currentTimeMillis();
        Tessera.Picture p = tessera.picture(kind);
        boolean askNow = false, loadNow = false;
        Slot s;
        synchronized (this) {
            s = slot(kind);
            boolean ready = p != null && (entity == null || entity.isEmpty() || p.entity.isEmpty() || entity.equals(p.entity))
                    && !p.url.equals(s.deadUrl);
            s.lastWant = now;
            if (s.asked == 0 || s.fails >= FAILS || (!ready && now - s.asked > PATIENT)) {
                s.asked = now;
                s.fails = 0;
                s.liveUrl = null;
                askNow = true;
            } else if (ready && p.url.endsWith(".mjpeg")) {
                // A live stream: one puller for it, which asks for each next picture while the slot is wanted.
                if (!p.url.equals(s.liveUrl)) {
                    s.liveUrl = p.url;
                    pull(s, p.url);
                }
            } else if (ready && !s.busy && (now - s.fetched >= every || !p.url.equals(s.url))) {
                s.busy = true;
                loadNow = true;
            }
        }
        if (askNow) ask.run();
        if (loadNow) load(s, p.url);
        synchronized (this) {
            if (s.img != null) {
                s.old = null;
                return s.img;
            }
            if (s.old != null && System.currentTimeMillis() < s.oldUntil) return s.old;
            s.old = null;
            return null;
        }
    }

    /**
     * A live camera's pictures (docs/CAMERA.md, "Live on the P4 boards"): GET the link's `.jpg` over a kept connection, and
     * the add-on answers with the JPEG of the camera's newest frame once it is newer than the last, or 204 after two
     * seconds. An add-on that does not know the ask answers 404: the `.mjpeg` stream is read then, keeping the newest
     * picture. It ends when the slot no longer wants it (another page, a card over it) or its link changed, and asks for a
     * new link when the stream goes away.
     */
    private void pull(final Slot s, final String streamUrl) {
        new Thread(new Runnable() {
            public void run() {
                String ask = streamUrl.substring(0, streamUrl.length() - ".mjpeg".length()) + ".jpg";
                int errors = 0, frames = 0;
                long started = System.currentTimeMillis(), lastPicture = started;
                boolean stale = false;
                app.tilescreen.App.get().log("live: pulling " + ask);
                while (streamUrl.equals(s.liveUrl) && System.currentTimeMillis() - s.lastWant < 2500) {
                    long begun = System.currentTimeMillis();
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(ask).openConnection();
                        c.setConnectTimeout(5000);
                        c.setReadTimeout(8000);
                        int code = c.getResponseCode();
                        if (code == 200) {
                            InputStream in = c.getInputStream();
                            Bitmap got = BitmapFactory.decodeStream(in);
                            in.close();
                            if (got != null && streamUrl.equals(s.liveUrl)) {
                                synchronized (Pictures.this) {
                                    s.img = got;
                                    s.url = streamUrl;
                                }
                                frames++;
                                lastPicture = System.currentTimeMillis();
                                redraw.redraw();
                            }
                            errors = 0;
                        } else if (code == 204) {
                            c.getInputStream().close();
                            errors = 0;
                        } else if (code == 404) {
                            c.disconnect();
                            readStream(s, streamUrl);
                            break;
                        } else {
                            c.disconnect();
                            errors++;
                        }
                    } catch (Exception e) {
                        errors++;
                        if (errors == 1) app.tilescreen.App.get().log("live: " + ask + ": " + e);
                    }
                    if (errors >= 4) break;
                    // Only "nothing newer" for this long: the add-on's camera started over under the old link (its
                    // pictures count from 0 again, below the last one this link took), so it would answer 204 for ever.
                    if (System.currentTimeMillis() - lastPicture > 10000) {
                        stale = true;
                        break;
                    }
                    long wait = 1000 / Math.max(1, s.pps) - (System.currentTimeMillis() - begun);
                    if (errors > 0) wait = Math.max(wait, 500);
                    if (wait > 0) {
                        try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
                    }
                }
                long secs = Math.max(1, (System.currentTimeMillis() - started) / 1000);
                app.tilescreen.App.get().log("live: ended " + ask + " after " + secs + " s, " + frames + " pictures"
                        + (errors >= 4 ? " (the link stopped answering)" : stale ? " (no new picture for 10 s; asking again)" : ""));
                synchronized (Pictures.this) {
                    if (streamUrl.equals(s.liveUrl)) {
                        s.liveUrl = null;
                        // A link is used once: the next time the slot is wanted (now, or back on its page) it asks a new
                        // question, which gets a new link, or a still when the streams are full.
                        s.deadUrl = streamUrl;
                        s.asked = 0;
                    }
                }
            }
        }, "live").start();
    }

    /** The `.mjpeg` stream itself (an add-on without the ask): each part a JPEG with its Content-Length. */
    private void readStream(Slot s, String streamUrl) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(streamUrl).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(16000);
            InputStream in = new java.io.BufferedInputStream(c.getInputStream(), 65536);
            while (streamUrl.equals(s.liveUrl) && System.currentTimeMillis() - s.lastWant < 2500) {
                int length = -1;
                String line;
                while ((line = readLine(in)) != null) {
                    if (line.isEmpty() && length >= 0) break;
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(15).trim());
                    }
                }
                if (line == null || length <= 0 || length > 4 * 1024 * 1024) break;
                byte[] jpeg = new byte[length];
                int at = 0;
                while (at < length) {
                    int n = in.read(jpeg, at, length - at);
                    if (n < 0) break;
                    at += n;
                }
                if (at < length) break;
                Bitmap got = BitmapFactory.decodeByteArray(jpeg, 0, length);
                if (got != null) {
                    synchronized (this) {
                        s.img = got;
                        s.url = streamUrl;
                    }
                    redraw.redraw();
                }
            }
            in.close();
            c.disconnect();
        } catch (Exception e) {
            app.tilescreen.App.get().log("live: stream " + streamUrl + ": " + e);
        }
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        StringBuilder b = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') return b.toString().trim();
            if (b.length() < 200) b.append((char) ch);
        }
        return b.length() > 0 ? b.toString().trim() : null;
    }

    private void load(final Slot s, final String url) {
        new Thread(new Runnable() {
            public void run() {
                Bitmap got = null;
                String why = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    int code = c.getResponseCode();
                    if (code == 200) {
                        InputStream in = c.getInputStream();
                        got = BitmapFactory.decodeStream(in);
                        in.close();
                        if (got == null) why = "the picture could not be decoded";
                    } else {
                        why = "HTTP " + code;
                    }
                    c.disconnect();
                } catch (java.net.SocketTimeoutException e) {
                    why = "timed out reaching " + new URL0(url).host() + " (a firewall or VLAN rule between the tablet and Home Assistant?)";
                } catch (Exception e) {
                    why = e.toString();
                }
                app.tilescreen.App.get().log(got != null ? "pictures: fetched " + got.getWidth() + "x" + got.getHeight()
                        : "pictures: fetching " + url + " failed: " + why);
                synchronized (Pictures.this) {
                    s.busy = false;
                    s.fetched = System.currentTimeMillis();
                    if (got != null) {
                        s.img = got;
                        s.url = url;
                        s.fails = 0;
                    } else {
                        s.fails++;
                    }
                }
                redraw.redraw();
            }
        }, "picture").start();
    }
}
