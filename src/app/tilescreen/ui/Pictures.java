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
        return true;
    }

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
            boolean ready = p != null && (entity == null || entity.isEmpty() || entity.equals(p.entity));
            if (s.asked == 0 || s.fails >= FAILS || (!ready && now - s.asked > PATIENT)) {
                s.asked = now;
                s.fails = 0;
                askNow = true;
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
