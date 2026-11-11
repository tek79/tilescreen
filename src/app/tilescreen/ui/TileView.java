package app.tilescreen.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.tilescreen.App;
import app.tilescreen.core.Json;
import app.tilescreen.core.Screen;
import app.tilescreen.core.Tessera;

/** Draws a Tessera layout and turns touches into Home Assistant actions. */
public final class TileView extends View {

    public interface Host {
        /** The app's own settings dialog. */
        void openAppSettings();
        void wake();
        /** The page of Android widgets, left of page 1 (a swipe right from the first page). */
        void openWidgets();
    }

    // ----- what can be touched -----

    private static final class Hit {
        final RectF r;
        Runnable tap, hold;
        Slider slider;
        boolean scrub;
        SettingsPage.Stepper repeat;
        PluginKit.Drag drag;

        Hit(RectF r) { this.r = r; }
    }

    private interface Setter { void set(double v); }

    private static final class Slider {
        RectF track;
        double min, max, step, value;
        String key;
        Setter set;
    }

    private static final class Wish {
        String from, state;
        long until;
    }

    private static final class Held {
        double value;
        long until;
    }

    private final App app;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = stroke();

    private static Paint stroke() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        return p;
    }
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final List<Hit> hits = new ArrayList<Hit>();
    private final Map<String, Wish> wishes = new HashMap<String, Wish>();
    private final Map<String, Held> held = new HashMap<String, Held>();
    private final List<Integer> route = new ArrayList<Integer>();
    private final Pictures pictures = new Pictures(new Pictures.Redraw() {
        public void redraw() { changed(); }
    });
    /** Where each tile's picture sits in the page's picture, by tile index, for the request that is out. */

    private Tessera.Layout layout;
    private int page = -1;
    private long seenAsk;
    private int cardTile = -1;
    private Screen.Alert alert;
    private Runnable alertTimeout;
    private String banner;
    private long bannerUntil;
    private long lastTouch = SystemClock.uptimeMillis();
    private float u = 6;

    // touch
    private float downX, downY;
    private boolean swiping, longFired;
    private Hit downHit;
    private Slider dragging;
    private boolean topSwipe;
    private boolean moved;
    private final Runnable longPress = new Runnable() {
        public void run() {
            longFired = true;
            dragging = null;
            if (downHit != null && downHit.hold != null) {
                performHapticFeedback(0);
                downHit.hold.run();
                invalidate();
            }
        }
    };

    public TileView(Context c, Host host) {
        super(c);
        this.app = App.get();
        this.host = host;
        glyphs.setTypeface(Icons.font(c));
        glyphs.setTextAlign(Paint.Align.CENTER);
        plugins();
        app.screen().tessera.setPluginMessages(new app.tilescreen.core.Tessera.PluginMessages() {
            public void message(String plugin, Map<String, Object> m) {
                PluginKit.Plugin p = PluginKit.of(plugin);
                if (p != null) p.onMessage(m);
                changed();
            }
        });
        tick.run();
    }

    /** The plugins this app draws, named in the hello (PluginKit). Called at start, before Tessera asks. */
    public static void plugins() { PluginKit.all(); }

    // ----- updates from the screen -----

    public void changed() {
        ui.post(new Runnable() {
            public void run() { invalidate(); }
        });
    }

    public void showAlert(final Screen.Alert a) {
        ui.post(new Runnable() {
            public void run() {
                // A new alert closes a ring's view, as on a Tessera screen.
                if (ring != null) finishRing("closed");
                if (alert != null) app.screen().alertEnded(alert, "replaced");
                alert = a;
                if (alertTimeout != null) ui.removeCallbacks(alertTimeout);
                alertTimeout = null;
                if (a.timeoutSeconds > 0) {
                    alertTimeout = new Runnable() {
                        public void run() { endAlert("timeout"); }
                    };
                    ui.postDelayed(alertTimeout, a.timeoutSeconds * 1000L);
                }
                invalidate();
            }
        });
    }

    public void dismissAlert() {
        ui.post(new Runnable() {
            public void run() { endAlert("remote"); }
        });
    }

    private void endAlert(String how) {
        if (alert == null) return;
        Screen.Alert a = alert;
        alert = null;
        if (alertTimeout != null) ui.removeCallbacks(alertTimeout);
        alertTimeout = null;
        app.screen().alertEnded(a, how);
        invalidate();
    }

    /** Standby and Back to page 1: every card closes, and with `home` the home page shows. */
    public void goHome() {
        ui.post(new Runnable() {
            public void run() { home(true); }
        });
    }

    public void closeCards() {
        ui.post(new Runnable() {
            public void run() { home(false); }
        });
    }

    private void home(boolean toHome) {
        cardTile = -1;
        pluginCard = null;
        settingsOpen = false;
        calendarOpen = -1;
        pictures.forget("full");
        if (toHome && layout != null) {
            route.clear();
            page = layout.home;
        }
        invalidate();
    }

    private long lastMinute = -1;
    private final Runnable tick = new Runnable() {
        public void run() {
            // Back to page 1 (core.yaml back_to_page_1_when_due): away from home, or a card open, long enough.
            if (layout != null && (cardTile >= 0 || page != layout.home) && alert == null && ring == null
                    && app.screen().screenSettings.homeDue()) {
                home(true);
            }
            long minute = System.currentTimeMillis() / 60000;
            if (minute != lastMinute) {
                lastMinute = minute;
                invalidate();
            }
            ui.postDelayed(this, 1000);
        }
    };

    // ----- geometry -----

    private static final class Cell {
        int page, column, row, columns = 1, rows = 1;
    }

    private Cell place(int slot, String size, int cols, int rws) {
        int cells = cols * rws;
        Cell c = new Cell();
        c.page = slot / cells;
        int at = slot % cells;
        c.column = at % cols;
        c.row = at / cols;
        if ("full".equals(size)) {
            c.column = 0;
            c.row = 0;
            c.columns = cols;
            c.rows = rws;
        } else if ("wide".equals(size)) {
            c.columns = Math.min(2, cols);
        } else if ("tall".equals(size)) {
            c.rows = 2;
        } else if ("square".equals(size)) {
            c.columns = 2;
            c.rows = 2;
        } else if (size != null && size.length() == 3 && size.charAt(1) == 'x') {
            c.columns = size.charAt(0) - '0';
            c.rows = size.charAt(2) - '0';
        }
        c.columns = Math.max(1, Math.min(c.columns, cols - c.column));
        c.rows = Math.max(1, Math.min(c.rows, rws - c.row));
        return c;
    }

    // ----- drawing -----

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        app.setCanvas(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        hits.clear();
        int w = getWidth(), h = getHeight();
        u = Math.min(w, h) / 100f;
        Screen screen = app.screen();
        Style.use(screen.screenSettings.dark());
        c.drawColor(Style.BG);
        layout = screen.tessera.layout();
        boolean shown = screen.tessera.shown() && !layout.pages.isEmpty();
        if (!shown) {
            drawWaiting(c, screen, w, h);
        } else {
            settlePage(screen);
            drawPage(c, screen, w, h);
            if (cardTile >= 0 && cardTile < layout.tiles.size()) {
                drawCard(c, cardOverride != null ? cardOverride : effective(layout.tiles.get(cardTile)), w, h);
            } else {
                cardTile = -1;
            }
            if (cardTile < 0) cardOverride = null;
            if (pluginCard != null) drawPluginCard(c, w, h);
            if (speakerAsk != null) drawSpeakers(c, w, h);
        }
        if (calendarOpen >= 0) drawCalendarFull(c, w, h);
        if (settingsOpen) drawSettings(c, w, h);
        if (ring != null) drawRing(c, w, h);
        if (alert != null) drawAlert(c, w, h);
        if (banner != null && SystemClock.uptimeMillis() < bannerUntil) drawBanner(c, w, h);
    }

    private void settlePage(Screen screen) {
        int n = layout.pages.size();
        long ask = screen.tessera.wantedAsk();
        if (ask != seenAsk) {
            seenAsk = ask;
            int want = screen.tessera.wantedPage();
            if (want >= 0 && want < n) {
                page = want;
                cardTile = -1;
                route.clear();
            }
        }
        if (page < 0 || page >= n) page = Math.min(Math.max(layout.home, 0), n - 1);
    }

    private void drawWaiting(Canvas c, Screen screen, int w, int h) {
        float cx = w / 2f;
        float y = h * 0.2f;
        glyphs.setColor(Style.BLUE);
        glyphs.setTextSize(14 * u);
        c.drawText(Icons.sure(getContext(), "view-dashboard-outline"), cx, y + 10 * u, glyphs);
        y += 20 * u;
        text.setTypeface(medium);
        text.setColor(Style.TEXT);
        text.setTextSize(5.2f * u);
        text.setTextAlign(Paint.Align.CENTER);
        int clients = screen.device.clients(), actions = screen.device.actionSubscribers();
        String title, line1, line2;
        if (clients == 0) {
            title = "Add this screen to Home Assistant";
            line1 = "Settings → Devices & services → ESPHome: \"" + app.friendly() + "\" should be discovered.";
            line2 = "Or add an ESPHome device by address: " + (app.ip().isEmpty() ? "(no network)" : app.ip()) + ", port " + App.API_PORT;
        } else if (!screen.tessera.heard()) {
            title = "Connected to Home Assistant";
            line1 = "Open Tessera: this screen appears there as \"" + app.friendly() + "\".";
            line2 = actions == 0 ? "Then turn on \"Allow the device to perform Home Assistant actions\" in this device's ESPHome options."
                    : "Add pages and tiles there, and they show up here.";
        } else {
            title = "Ready for tiles";
            line1 = "Add pages and tiles to \"" + app.friendly() + "\" in Tessera.";
            line2 = actions == 0 ? "Turn on \"Allow the device to perform Home Assistant actions\" so taps work." : "";
        }
        c.drawText(title, cx, y, text);
        text.setTypeface(regular);
        text.setColor(Style.DIM);
        text.setTextSize(3.4f * u);
        y += 8 * u;
        c.drawText(fit(line1, w - 10 * u), cx, y, text);
        y += 5.5f * u;
        c.drawText(fit(line2, w - 10 * u), cx, y, text);
        y += 10 * u;
        text.setColor(Style.FAINT);
        text.setTextSize(2.8f * u);
        c.drawText(app.node() + "  ·  " + (app.ip().isEmpty() ? "offline" : app.ip()) + "  ·  " + w + "×" + h + "  ·  grid "
                + app.shape().columns() + "×" + app.shape().rows(), cx, y, text);
        RectF b = new RectF(cx - 14 * u, h - 16 * u, cx + 14 * u, h - 7 * u);
        drawButton(c, b, "Settings", Style.RAISED, Style.TEXT);
        Hit hit = new Hit(b);
        hit.tap = new Runnable() {
            public void run() { host.openAppSettings(); }
        };
        hits.add(hit);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawPage(Canvas c, Screen screen, int w, int h) {
        Tessera.Page p = layout.pages.get(page);
        float top = 10 * u, pad = 1.8f * u, gap = 1.6f * u;
        List<Integer> included = included();
        // Page buttons off: the bar goes and the tiles take its room; a detail page's Back moves to the top bar.
        boolean pager = screen.screenSettings.pageButtons() && (included.size() > 1 || p.excluded);
        float bottom = pager ? 5 * u : pad;
        drawBar(c, screen, p, w, top);
        int cols = app.shape().columns(), rws = app.shape().rows();
        RectF area = new RectF(pad, top, w - pad, h - bottom);
        float cw = (area.width() - gap * (cols - 1)) / cols, ch = (area.height() - gap * (rws - 1)) / rws;
        List<Integer> shown = new ArrayList<Integer>();
        List<RectF> rects = new ArrayList<RectF>();
        List<Cell> cells = new ArrayList<Cell>();
        for (int i = 0; i < layout.tiles.size(); i++) {
            Tessera.Tile t = layout.tiles.get(i);
            if (!t.received || t.parent >= 0) continue;
            Cell cell = place(t.slot, t.size, cols, rws);
            if (cell.page != page) continue;
            RectF r = new RectF(area.left + cell.column * (cw + gap), area.top + cell.row * (ch + gap), 0, 0);
            r.right = r.left + cell.columns * cw + (cell.columns - 1) * gap;
            r.bottom = r.top + cell.rows * ch + (cell.rows - 1) * gap;
            shown.add(i);
            rects.add(r);
            cells.add(cell);
        }
        livePictures(screen, shown, rects, w, h);
        for (int k = 0; k < shown.size(); k++) {
            int i = shown.get(k);
            drawTile(c, i, effective(layout.tiles.get(i)), rects.get(k), cells.get(k), Math.min(cw, ch * 1.4f));
        }
        if (pager) drawPager(c, p, included, w, h, bottom);
    }

    // ----- pictures on tiles (widgets/live.go) -----

    static boolean pictured(Tessera.Tile t) {
        String d = t.domain();
        if ("live".equals(t.display)) return d.equals("camera") || d.equals("image");
        if ("cover".equals(t.display)) return d.equals("media_player") && !mark(t).isEmpty();
        if ("map".equals(t.display)) return d.equals("person") || t.entity.equals("screen.map");
        if ("favorite".equals(t.display)) return d.equals("media_player") && !mark(t).isEmpty();
        return false;
    }

    private static String mark(Tessera.Tile t) {
        String m = "cover".equals(t.display) ? Json.str(t.extra, "pic") : "map".equals(t.display) ? Json.str(t.extra, "mk")
                : "favorite".equals(t.display) ? Json.str(t.extra, "fm") : "";
        return m == null ? "" : m;
    }

    /** One picture for every pictured tile of the page, each in its own place, asked for when the page changes. */
    /**
     * The pictures of a page's tiles (tile_picture.h, GitHub #183): each tile asks for its own, painted for exactly its
     * place, at its own pace, and keeps it under its own key. A new track replaces one cover and nothing else, a camera
     * refreshes at the pace its own tile chose, and no answer can land in another tile's place. The question is the one
     * a page has always been asked with, for a page of one tile: an atlas of one frame at the top left.
     */
    private void livePictures(Screen screen, List<Integer> shown, List<RectF> rects, int w, int h) {
        float radius = 2.4f * u;
        final Tessera tessera = screen.tessera;
        Map<Integer, Bitmap> now = new HashMap<Integer, Bitmap>();
        boolean waiting = false;
        // The first camera tiles set to Live stream, as many as this tablet streams at once (caps.live_tiles); the rest
        // keep a still that comes again at their pace, as a Tessera screen's do (picture_engine::limits().streams).
        int streams = 0, most = Tessera.liveTiles;
        for (int k = 0; k < shown.size(); k++) {
            final int index = shown.get(k);
            Tessera.Tile t = layout.tiles.get(index);
            if (!pictured(t)) continue;
            RectF r = rects.get(k);
            final int fw = Math.max(1, Math.round(r.width())), fh = Math.max(1, Math.round(r.height()));
            final int rad = Math.min((int) radius, Math.min(fw, fh) / 2), shade = "cover".equals(t.display) ? 170 : 0;
            final String ground = String.format(Locale.US, "%06X", Style.BG & 0xFFFFFF);
            final String entity = t.entity;
            boolean camera = "live".equals(t.display) && (t.domain().equals("camera") || t.domain().equals("image"));
            final boolean live = camera && t.domain().equals("camera") && t.refresh == 0 && fw >= 64 && fh >= 64 && streams < most;
            if (live) streams++;
            // A camera's still comes again at its tile's pace (Live over the streams' count: Tessera's 15 s).
            final long pace = !camera ? 0 : (t.refresh > 0 ? t.refresh : 15) * 1000L;
            String kind = "live:" + index;
            // What makes it another picture: the tile, its place, the track or choice it shows, the look, streaming or not.
            pictures.want(kind, entity + "|" + fw + "x" + fh + "|" + mark(t) + "|" + ground + "|" + live);
            pictures.pace(kind, 5);
            Bitmap b = pictures.keepFresh(tessera, kind, null, pace > 0 ? pace : 3600000L, new Runnable() {
                public void run() {
                    Map<String, Object> q = question("tile", index, entity, "tile:" + index, fw, fh, rad, shade, "cover", ground);
                    if (pace > 0) q.put("pace", (double) pace);
                    if (live) q.put("live", numbers(fw, fh));
                    tessera.askQuestion("live:" + index, q);
                }
            });
            if (b != null) now.put(index, b);
            else waiting = true;
        }
        tilePictures = now;
        ui.postDelayed(new Runnable() {
            public void run() { invalidate(); }
        }, waiting ? 500 : 1000);
    }

    /**
     * A picture question (docs/PICTURES.md, "The question"): who asks, its source, the picture's size with one place over
     * all of it, how it fills, the colour behind its corners, the look, and the formats this tablet decodes, JPEG first.
     */
    static Map<String, Object> question(String owner, int index, String of, String source, int w, int h, int radius, int shade,
                                        String fit, String ground) {
        Map<String, Object> q = new LinkedHashMap<String, Object>();
        q.put("o", owner);
        if (owner.equals("tile")) q.put("i", (double) index);
        if (of != null && !of.isEmpty()) q.put("for", of);
        List<Object> src = new ArrayList<Object>();
        src.add(source);
        q.put("src", src);
        w = Math.max(1, Math.min(2048, w));
        h = Math.max(1, Math.min(2048, h));
        q.put("box", numbers(w, h));
        List<Object> at = new ArrayList<Object>();
        List<Object> place = new ArrayList<Object>();
        for (int n : new int[]{0, 0, w, h, Math.min(radius, Math.min(w, h) / 2), shade}) place.add((double) n);
        at.add(place);
        q.put("at", at);
        List<Object> fits = new ArrayList<Object>(), grounds = new ArrayList<Object>(), formats = new ArrayList<Object>();
        fits.add(fit);
        grounds.add(ground);
        q.put("fit", fits);
        q.put("bg", grounds);
        q.put("look", Style.isDark() ? "d" : "l");
        formats.add("jpeg");
        formats.add("bmp24");
        q.put("fmt", formats);
        return q;
    }

    private static List<Object> numbers(int a, int b) {
        List<Object> out = new ArrayList<Object>();
        out.add((double) a);
        out.add((double) b);
        return out;
    }

    /** Each pictured tile's own picture on the page shown now. */
    private Map<Integer, Bitmap> tilePictures = new HashMap<Integer, Bitmap>();

    /** Draws a tile's own picture over its card; false while there is none yet. */
    private boolean drawLive(Canvas c, int index, RectF r) {
        Bitmap b = tilePictures.get(index);
        if (b == null) return false;
        // The card's own round corners, whatever colour the picture's corners came in.
        float radius = 2.4f * u;
        Path round = new Path();
        round.addRoundRect(new RectF(r), radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(round);
        c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), r, null);
        c.restore();
        return true;
    }

    /** Words on a picture: white with a dark edge, so they read on any part of it without a band over it. */
    private void outlined(Canvas c, String s, float x, float y) {
        Paint.Style was = text.getStyle();
        int colour = text.getColor();
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(Math.max(2, text.getTextSize() * 0.16f));
        text.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(0xD0000000);
        c.drawText(s, x, y, text);
        text.setStyle(was);
        text.setColor(colour);
        c.drawText(s, x, y, text);
    }

    private List<Integer> included() {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < layout.pages.size(); i++) if (!layout.pages.get(i).excluded) out.add(i);
        return out;
    }

    // ----- the top bar -----

    private void drawBar(Canvas c, final Screen screen, Tessera.Page p, int w, float height) {
        float mid = height / 2f + 0.6f * u;
        float x = 2.2f * u;
        boolean away = p.excluded || (page != layout.home && screen.screenSettings.homeButton());
        RectF titleHit = new RectF(0, 0, w * 0.45f, height);
        if (away) {
            glyphs.setColor(Style.TEXT);
            glyphs.setTextSize(5.4f * u);
            c.drawText(Icons.sure(getContext(), p.excluded ? "arrow-left" : "home-outline"), x + 2.7f * u, mid + 1.9f * u, glyphs);
            Hit back = new Hit(new RectF(0, 0, x + 7 * u, height));
            back.tap = new Runnable() {
                public void run() { back(); }
            };
            back.hold = settingsHold();
            hits.add(back);
            x += 7.5f * u;
        }
        String title = p.title.isEmpty() ? layout.title : p.title;
        text.setTypeface(medium);
        text.setTextSize(4.2f * u);
        text.setColor(Style.TEXT);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(fit(title, w * 0.45f - x), x, mid + 1.5f * u, text);
        Hit t = new Hit(titleHit);
        t.hold = settingsHold();
        hits.add(t);

        // Items from the right.
        float right = w - 2.4f * u;
        text.setTypeface(regular);
        text.setTextSize(3.8f * u);
        text.setTextAlign(Paint.Align.RIGHT);
        List<Tessera.BarItem> items = new ArrayList<Tessera.BarItem>(p.bar);
        for (int i = items.size() - 1; i >= 0; i--) {
            Tessera.BarItem it = items.get(i);
            if (!it.standing()) continue;
            float was = right;
            right = drawBarItem(c, it, right, mid) - 2.6f * u;
            if (it.doorbell) {
                // A missed ring: a tap opens it again (header_bar's DOORBELL_CARD).
                Hit h = new Hit(new RectF(right, 0, was + 1.3f * u, height));
                h.tap = new Runnable() {
                    public void run() { reopenRing(); }
                };
                hits.add(h);
            }
            if (right < w * 0.5f) break;
        }
        if (screen.device.clients() == 0 || (screen.tessera.shown() && !screen.tessera.fresh())) {
            glyphs.setColor(Style.RED);
            glyphs.setTextSize(4.2f * u);
            c.drawText(Icons.sure(getContext(), "cloud-off-outline"), right - 2 * u, mid + 1.5f * u, glyphs);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private Runnable settingsHold() {
        return new Runnable() {
            public void run() { openSettingsPage(); }
        };
    }

    // ----- the settings page on the screen (settings_screen.h) -----

    private SettingsPage settingsPage;
    private boolean settingsOpen;

    /** Opens the settings page from anywhere: the settings tile, a hold on the title, a swipe from the top, Home Assistant. */
    public void openSettingsPage() {
        ui.post(new Runnable() {
            public void run() {
                if (settingsPage == null) settingsPage = new SettingsPage(getContext(), glyphs);
                settingsPage.group = SettingsPage.MENU;
                settingsPage.page = 0;
                settingsOpen = true;
                cardTile = -1;
                invalidate();
            }
        });
    }

    private void drawSettings(Canvas c, int w, int h) {
        if (settingsPage == null) settingsPage = new SettingsPage(getContext(), glyphs);
        settingsPage.draw(c, w, h, u, layout != null && layout.clock24, new SettingsPage.Host() {
            public void close() { settingsOpen = false; }

            public void appSettings() {
                settingsOpen = false;
                host.openAppSettings();
            }
        }, new SettingsPage.Hits() {
            public void tap(RectF r, Runnable run) {
                Hit hit = new Hit(new RectF(r));
                hit.tap = run;
                hits.add(hit);
            }

            public void repeat(RectF r, SettingsPage.Stepper step) {
                Hit hit = new Hit(new RectF(r));
                hit.repeat = step;
                hits.add(hit);
            }

            public void block(RectF r) { hits.add(new Hit(new RectF(r))); }
        });
    }

    /** A - or + held: steps again and again, bigger after a second (settings_screen hold_step). */
    private SettingsPage.Stepper repeating;
    private long repeatDown;
    private final Runnable repeater = new Runnable() {
        public void run() {
            SettingsPage.Stepper r = repeating;
            if (r == null) return;
            r.step(SystemClock.uptimeMillis() - repeatDown > 1200);
            // A held key steps once more (touch_feedback::step).
            for (PluginKit.Plugin p : PluginKit.all()) p.onStep(false, pluginHost());
            invalidate();
            ui.postDelayed(this, 140);
        }
    };

    private float drawBarItem(Canvas c, Tessera.BarItem it, float right, float mid) {
        String k = it.kind;
        String words = null, icon = Icons.glyph(it.icon);
        if (k.equals("plugin")) {
            // A plugin's item (plugin:<plugin>.<item>): what the plugin says now, or nothing.
            String[] pk = PluginKit.key(it.text);
            PluginKit.Plugin p = pk == null ? null : PluginKit.of(pk[0]);
            PluginKit.Bar bar = p == null ? null : p.bar(pk[1], layout, pluginHost());
            if (bar == null || !bar.shown) return right + 2.6f * u;
            int col = bar.color != 0 ? bar.color : Style.TEXT;
            text.setColor(col);
            if (!it.iconOnly && !bar.words.isEmpty()) {
                c.drawText(bar.words, right, mid + 1.4f * u, text);
                right -= text.measureText(bar.words) + 1.0f * u;
            }
            String g = Icons.glyph(bar.icon);
            if (g != null) {
                glyphs.setColor(col);
                glyphs.setTextSize(4.4f * u);
                c.drawText(g, right - 2.2f * u, mid + 1.6f * u, glyphs);
                right -= 4.4f * u;
            }
            return right;
        }
        Date now = new Date();
        if (k.equals("clock") || k.equals("analog")) {
            words = new SimpleDateFormat(layout.clock24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(now);
        } else if (k.equals("date")) {
            words = new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now);
        } else if (k.equals("text")) {
            words = it.text;
        } else if (k.equals("ago")) {
            long mins = Math.max(0, (System.currentTimeMillis() / 1000 - it.epoch) / 60);
            words = mins < 1 ? "now" : mins < 60 ? mins + " min" : mins < 1440 ? (mins / 60) + " h" : (mins / 1440) + " d";
            if (it.doorbell) words = mins < 1 ? "Just now" : words + " ago";
            if (!it.text.isEmpty()) words = it.text + " " + words;
        } else if (k.equals("wifi")) {
            icon = Icons.named(getContext(), app.ip().isEmpty() ? "wifi-off" : "wifi");
        } else if (k.equals("link")) {
            boolean ok = app.screen().tessera.fresh();
            icon = Icons.named(getContext(), ok ? "link-variant" : "link-variant-off");
        } else if (k.equals("battery")) {
            int level = battery();
            icon = Icons.named(getContext(), level >= 90 ? "battery" : level >= 50 ? "battery-60" : level >= 20 ? "battery-30" : "battery-alert");
            words = level >= 0 ? level + "%" : null;
        }
        int color = it.color.isEmpty() ? (it.doorbell ? Style.ORANGE : Style.TEXT) : Style.named(it.color, Style.TEXT);
        if (it.weak != 0) color = Style.DIM;
        text.setColor(color);
        if (words != null && !words.isEmpty()) {
            c.drawText(words, right, mid + 1.4f * u, text);
            right -= text.measureText(words) + (icon != null ? 1.0f * u : 0);
        }
        if (icon != null) {
            glyphs.setColor(color);
            glyphs.setTextSize(4.4f * u);
            c.drawText(icon, right - 2.2f * u, mid + 1.6f * u, glyphs);
            right -= 4.4f * u;
        }
        return right;
    }

    private int battery() {
        // The battery's last broadcast (App.battery), which every Android has; BatteryManager's property is Android 5+.
        if (Tessera.batteryLevel >= 0) return Tessera.batteryLevel;
        try {
            BatteryManager bm = (BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Throwable e) {
            return -1;
        }
    }

    private void drawPager(Canvas c, Tessera.Page p, List<Integer> included, int w, int h, float height) {
        float y = h - height / 2f;
        if (p.excluded) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Style.DIM);
            text.setTextSize(3.2f * u);
            text.setTypeface(medium);
            c.drawText("‹  Back", w / 2f, y + 1.1f * u, text);
            Hit back = new Hit(new RectF(w / 2f - 12 * u, h - height, w / 2f + 12 * u, h));
            back.tap = new Runnable() {
                public void run() { back(); }
            };
            hits.add(back);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        // Previous and Next at the ends of the bar, the page dots between.
        int at = included.indexOf(page);
        String[] keys = {"chevron-left", "chevron-right"};
        for (int k = 0; k < 2; k++) {
            final int target = at < 0 ? -1 : k == 0 ? (at > 0 ? included.get(at - 1) : -1) : (at < included.size() - 1 ? included.get(at + 1) : -1);
            float kx = k == 0 ? 6 * u : w - 6 * u;
            glyphs.setColor(target < 0 ? Style.LINE : Style.DIM);
            glyphs.setTextSize(height * 0.85f);
            c.drawText(Icons.sure(getContext(), keys[k]), kx, y + height * 0.3f, glyphs);
            if (target >= 0) {
                Hit hit = new Hit(new RectF(kx - 6 * u, h - height, kx + 6 * u, h));
                hit.tap = new Runnable() {
                    public void run() { go(target); }
                };
                hits.add(hit);
            }
        }
        float dot = 0.9f * u, space = 3.2f * u;
        float x = w / 2f - (included.size() - 1) * space / 2f;
        for (int i : included) {
            fill.setColor(i == page ? Style.TEXT : Style.FAINT);
            c.drawCircle(x, y, i == page ? dot * 1.2f : dot, fill);
            final int target = i;
            Hit hit = new Hit(new RectF(x - space / 2, h - height, x + space / 2, h));
            hit.tap = new Runnable() {
                public void run() { go(target); }
            };
            hits.add(hit);
            x += space;
        }
    }

    // ----- tiles -----

    private Tessera.Tile effective(Tessera.Tile t) {
        Wish wish = wishes.get(t.entity);
        if (wish == null) return t;
        if (!t.state.equals(wish.from) || SystemClock.uptimeMillis() > wish.until) {
            wishes.remove(t.entity);
            return t;
        }
        Tessera.Tile copy = new Tessera.Tile();
        copy.entity = t.entity; copy.size = t.size; copy.display = t.display; copy.tap = t.tap; copy.sub = t.sub;
        copy.controls = t.controls; copy.inline = t.inline; copy.guard = t.guard; copy.name = t.name;
        copy.icon = t.icon; copy.background = t.background; copy.slot = t.slot; copy.parent = t.parent;
        copy.options = t.options; copy.attributes = t.attributes; copy.extra = t.extra; copy.history = t.history;
        copy.received = true;
        copy.state = wish.state;
        return copy;
    }

    private TextCard textCard;

    private TextCard textCard() {
        if (textCard == null) textCard = new TextCard(regular, medium);
        return textCard;
    }

    private void drawTile(Canvas c, final int index, final Tessera.Tile t, RectF r, Cell cell, float base) {
        String bgName = t.background;
        int pastel = Style.pastel(bgName);
        boolean card = !"none".equals(bgName);
        // The template tile (docs/TEXT_TILE.md): rows Home Assistant rendered; none hides the card, ground and all.
        String[] textError = {null};
        List<TextCard.Row> textRows = t.entity.equals("screen.text") ? TextCard.rows(t.extra, textError) : null;
        if (t.entity.equals("screen.text") && (textRows == null || textRows.isEmpty() && textError[0] == null)) return;
        boolean on = Style.active(t), dead = Style.unavailable(t);
        int accent = Style.accent(t);
        int fg = pastel != 0 ? Style.DARK_TEXT : Style.TEXT, dim = pastel != 0 ? Style.DARK_DIM : Style.DIM;
        float radius = 2.4f * u;
        if (card) {
            fill.setColor(pastel != 0 ? pastel : on ? Style.CARD_ON : Style.CARD);
            c.drawRoundRect(r, radius, radius, fill);
        }
        int alpha = dead ? 110 : 255;

        float fs = Math.max(11, Math.min(base / 8.5f, 4.2f * u));
        float pad = fs * 0.95f;
        String e = t.entity;

        Hit hit = new Hit(new RectF(r));
        final boolean iconMode = app.screen().screenSettings.iconToggles() && opensCard(t);
        hit.tap = tapFor(index, t, iconMode);
        hit.hold = tapFor(index, t, true);
        hits.add(hit);

        if ("favorite".equals(t.display) && t.domain().equals("media_player")) {
            drawFavorite(c, index, t, r, fs, pad, card, pastel);
            return;
        }
        if (pictured(t) && drawLive(c, index, r)) {
            if (!"cover".equals(t.display)) {
                // A camera or a map fills its card; its name stands on it unless the tile says none.
                if (!"none".equals(Json.str(t.options, "overlay"))) {
                    text.setTypeface(medium);
                    text.setTextSize(fs * 0.9f);
                    text.setColor(0xFFFFFFFF);
                    text.setTextAlign(Paint.Align.LEFT);
                    String label = t.domain().equals("person") ? t.name + " · " + Style.stateLine(t) : t.name;
                    outlined(c, fit(label, r.width() - pad * 2), r.left + pad, r.bottom - fs * 0.85f);
                }
                return;
            }
            // An album cover is the card's dimmed ground; the tile draws over it in white.
            // White on it in the light look too: the picture is dark whatever the look.
            pastel = 0;
            fg = 0xFFFFFFFF;
            dim = 0xFFD0D4DC;
        }

        if (e.startsWith("plugin:")) {
            drawPluginTile(c, index, t, r, fs, pad, fg, dim, accent, pastel, card, hit);
            return;
        }
        if (textRows != null) {
            hit.tap = hit.hold = null;  // a tap on the tile does nothing
            float tfs = Math.max(11, Math.min(base / 9.5f, 3.9f * u));
            textCard().draw(c, textRows, textError[0], new RectF(r.left + pad, r.top + pad * 0.8f, r.right - pad, r.bottom - pad * 0.6f),
                    tfs, fg, dim);
            return;
        }
        if (t.domain().equals("vacuum") && VacuumTile.face(t.controls) && cell.rows >= 2 && r.height() > 40 * u) {
            hit.tap = hit.hold = new Runnable() {
                public void run() { cardTile = index; }
            };
            drawVacuumTile(c, index, t, new RectF(r.left + pad, r.top + pad * 0.8f, r.right - pad, r.bottom - pad * 0.8f), fs,
                    fg, dim, pastel != 0 ? 0x22000000 : Style.RAISED);
            return;
        }
        if (e.equals("screen.clock")) {
            ClockTile.Card k = new ClockTile.Card();
            k.box = new RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad);
            k.ink = fg;
            k.muted = dim;
            k.accent = accent;
            k.card = card ? (pastel != 0 ? pastel : Style.CARD) : Style.BG;
            k.block = pastel != 0 ? mix(pastel, 0xFF000000, 0.12f) : Style.RAISED;
            k.fs = fs;
            k.clock24 = layout.clock24;
            k.wide = cell.columns >= 2;
            k.full = "full".equals(t.size);
            k.rows = cell.rows;
            k.awake = !app.screen().screenSettings.dimmed();
            clocks.draw(c, t.display, k);
            return;
        }
        if (e.equals("screen.energy")) {
            RectF box = new RectF(r.left + pad * 0.6f, r.top + pad * 0.6f, r.right - pad * 0.6f, r.bottom - pad * 0.6f);
            final int energyIndex = index;
            boolean moving = energyCardFor().draw(c, t.extra, box, fs, fg, dim, Style.LINE, card ? (pastel != 0 ? pastel : Style.CARD) : Style.BG,
                    "lines".equals(t.flow), new EnergyCard.Taps() {
                        public void circle(RectF rr, final String entity, final String name, final String state, final String unit) {
                            Hit hit = new Hit(new RectF(rr));
                            hit.tap = new Runnable() {
                                public void run() { openSensorCard(energyIndex, entity, name, state, unit); }
                            };
                            hits.add(hit);
                        }
                    });
            // The dots run while the card is on the glass, awake and with nothing over it, about 12 frames a second.
            if (moving && cardTile < 0 && !settingsOpen && alert == null && !app.screen().screenSettings.dimmed()) {
                ui.removeCallbacks(energyFrame);
                ui.postDelayed(energyFrame, 80);
            }
            return;
        }
        // A calendar (firmware 0.56.0): its view by its room; a tap opens the full screen, a room's keys book it.
        if (t.domain().equals("calendar")) {
            RectF box = new RectF(r.left + pad, r.top + pad * 0.8f, r.right - pad, r.bottom - pad * 0.8f);
            calendar().drawTile(c, t, box, fs, mm(), fg, dim, card ? (pastel != 0 ? pastel : Style.CARD) : Style.BG,
                    layout.clock24, new CalendarCard.Hits() {
                        public void tap(RectF rr, Runnable run) {
                            Hit hit = new Hit(new RectF(rr));
                            hit.tap = run;
                            hits.add(hit);
                        }
                    }, new CalendarCard.Booker() {
                        public void book(CalendarCard.Data d, int start, int end) {
                            if (app.screen().perform("calendar.create_event", CalendarCard.booking(d, start, end), null)) {
                                bookingUntil = SystemClock.uptimeMillis() + 15000;
                                say("Booking until " + CalendarCard.clock(end, layout.clock24));
                            } else {
                                noActions();
                            }
                        }

                        public boolean booking() { return SystemClock.uptimeMillis() < bookingUntil; }
                    });
            return;
        }
        // A sensor's meter (firmware 0.56.0): a ring, a tape or a bar of cells.
        if ("meter".equals(t.display) && t.domain().equals("sensor")) {
            if (meterTile == null) meterTile = new MeterTile(getContext(), glyphs);
            RectF box = new RectF(r.left + pad, r.top + pad * 0.8f, r.right - pad, r.bottom - pad * 0.8f);
            int cardColour = pastel != 0 ? pastel : on ? Style.CARD_ON : Style.CARD;
            meterTile.draw(c, t, box, fs, fg, dim, pastel != 0 ? 0x22000000 : Style.RAISED, accent, dead, glass(), samples(t), cardColour);
            return;
        }
        // A sensor as a Big number (display "watch", ui-scale watchCard) or with its graph under its head ("graph").
        if ("watch".equals(t.display) && !dead && !e.startsWith("screen.")) {
            drawWatch(c, t, new RectF(r.left + pad, r.top + pad * 0.8f, r.right - pad, r.bottom - pad * 0.8f), fs, fg, dim, accent, on, pastel);
            return;
        }
        if ("graph".equals(t.display) && t.domain().equals("sensor") && samples(t).size() >= 2) {
            drawGraphTile(c, t, new RectF(r.left + pad, r.top + pad * 0.8f, r.right - pad, r.bottom - pad * 0.8f), fs, fg, dim, accent, on, pastel, dead);
            return;
        }
        if (e.equals("screen.nightstand")) {
            drawClockTile(c, r, fg, dim, fs);
            return;
        }

        String[] plan = dead ? null : controlPlan(t, cell);
        boolean tallClimate = plan != null && plan[0].equals("bigstep");
        float inner = r.height() - pad * 2;
        float bandH = plan == null ? 0 : Math.min(fs * 2.6f, inner * (plan.length > 1 ? 0.3f : 0.42f));
        float controlsH = plan == null ? 0 : tallClimate ? inner * 0.62f : bandH * plan.length + pad * 0.5f * (plan.length - 1);
        float ic = Math.min(fs * 2.7f, r.height() * 0.42f);
        // A tile whose controls leave little room puts its name beside the icon, so the controls get the bottom.
        boolean side = plan != null && (tallClimate || inner - controlsH < ic + fs * 2.6f);
        if (side) ic = Math.max(fs * 1.6f, Math.min(ic, inner - controlsH - pad * 0.4f));

        // The icon in its circle.
        float icx = r.left + pad + ic / 2f, icy = r.top + pad + ic / 2f;
        fill.setColor(on && !dead ? withAlpha(accent, pastel != 0 ? 90 : 60) : pastel != 0 ? 0x22000000 : Style.RAISED);
        c.drawCircle(icx, icy, ic / 2f, fill);
        glyphs.setColor(on && !dead ? (pastel != 0 ? Style.DARK_TEXT : accent) : dim);
        glyphs.setAlpha(alpha);
        glyphs.setTextSize(ic * 0.56f);
        c.drawText(Icons.forTile(getContext(), t), icx, icy + ic * 0.2f, glyphs);
        glyphs.setAlpha(255);
        // Icon toggles: the circle does what a tap did (on and off, play and pause), the rest of the tile opens the card.
        final Runnable quick = iconMode && !dead ? quickAction(index, t) : null;
        if (quick != null) {
            float reach = Math.max(ic, 12 * u);
            Hit q = new Hit(new RectF(icx - reach / 2, icy - reach / 2, icx + reach / 2, icy + reach / 2));
            q.tap = quick;
            q.hold = tapFor(index, t, true);
            hits.add(q);
        }
        if (t.domain().equals("climate") && plan != null && !dead) {
            // A thermostat's circle turns it on and off; off is not on its mode bar.
            Hit power = new Hit(new RectF(icx - ic / 2, icy - ic / 2, icx + ic / 2, icy + ic / 2));
            power.tap = new Runnable() {
                public void run() { call("off".equals(t.state) ? "climate.turn_on" : "climate.turn_off", t); }
            };
            hits.add(power);
        }

        // A large value at the top right for numbers, the weather, and thermostats without controls.
        String value = null;
        if (Style.bigValue(t)) {
            String unit = t.text("unit_of_measurement");
            value = t.state + (unit == null ? "" : unit.startsWith("°") || unit.equals("%") ? unit : " " + unit);
        } else if (t.domain().equals("weather")) {
            Double temp = t.number("temperature");
            if (temp != null) value = Style.number(temp) + Style.degree(t);
        } else if (t.domain().equals("climate") && t.number("current_temperature") != null && !dead && plan == null) {
            value = Style.number(t.number("current_temperature")) + Style.degree(t);
        }
        float valueW = 0;
        if (value != null) {
            text.setTypeface(light);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(fg);
            text.setAlpha(alpha);
            float vs = Math.min(fs * (cell.columns >= 2 || cell.rows >= 2 ? 2.6f : 2.0f), ic * (side ? 0.8f : 0.95f));
            text.setTextSize(vs);
            float avail = r.width() - pad * 2 - ic - fs;
            if (side) avail *= 0.45f;
            while (text.measureText(value) > avail && vs > fs) {
                vs *= 0.9f;
                text.setTextSize(vs);
            }
            valueW = text.measureText(value);
            c.drawText(value, r.right - pad, icy + vs * 0.35f, text);
            text.setTextAlign(Paint.Align.LEFT);
        }

        // Name and state.
        String state = e.startsWith("screen.page_") ? "" : Style.stateLine(t);
        if (value != null && Style.bigValue(t)) state = "";
        if (t.domain().equals("weather")) state = Style.words(t.state);
        if (t.domain().equals("climate")) {
            String action = t.text("hvac_action");
            Double cur = t.number("current_temperature"), target = t.number("temperature");
            String mode = Style.words("off".equals(t.state) || action == null || action.equals("idle") ? t.state : action);
            if (plan != null) state = mode + (cur != null ? " · " + Style.number(cur) + Style.degree(t) : "");
            else state = mode + (target != null && !"off".equals(t.state) ? " · " + Style.number(target) + Style.degree(t) : "");
            if (dead) state = Style.words(t.state);
        }
        // What the second line was set to in Tessera (firmware 0.2.90+) wins over every line the screen works out
        // itself, but not over an unavailable entity: that still says so (chosen_subtitle).
        String chosen = chosenLine(t);
        if (chosen != null && !dead) state = chosen;
        float nameX, nameY, stateY, width;
        if (side) {
            nameX = r.left + pad + ic + pad * 0.6f;
            width = r.right - pad - nameX - (valueW > 0 ? valueW + fs * 0.6f : 0);
            boolean two = !state.isEmpty();
            nameY = two ? icy - fs * 0.12f : icy + fs * 0.36f;
            stateY = icy + fs * 1.05f;
        } else {
            nameX = r.left + pad;
            width = r.width() - pad * 2;
            float bottom = r.bottom - pad - (plan == null ? 0 : controlsH + pad * 0.6f);
            stateY = bottom;
            nameY = state.isEmpty() ? bottom : bottom - fs * 1.25f;
        }
        text.setTypeface(regular);
        text.setTextSize(fs * 0.86f);
        text.setColor(dim);
        text.setAlpha(alpha);
        if (!state.isEmpty()) c.drawText(fit(state, width), nameX, stateY, text);
        text.setTypeface(medium);
        text.setTextSize(fs);
        text.setColor(fg);
        text.setAlpha(alpha);
        c.drawText(fit(t.name, width), nameX, nameY, text);
        text.setAlpha(255);

        if (plan != null) {
            float cfs = Math.min(fs, bandH / 2.6f * 1.15f);
            if (tallClimate) {
                float modesH = plan.length > 1 ? bandH : 0;
                RectF big = new RectF(r.left + pad, r.bottom - pad - controlsH, r.right - pad, r.bottom - pad - modesH - (modesH > 0 ? pad * 0.5f : 0));
                drawBigSetpoint(c, t, big, fs, pastel != 0);
                if (modesH > 0) drawControl(c, t, "modes", new RectF(r.left + pad, r.bottom - pad - modesH, r.right - pad, r.bottom - pad), cfs, accent, pastel != 0);
            } else {
                float y = r.bottom - pad - controlsH;
                for (String kind : plan) {
                    drawControl(c, t, kind, new RectF(r.left + pad, y, r.right - pad, y + bandH), cfs, accent, pastel != 0);
                    y += bandH + pad * 0.5f;
                }
            }
        }
    }

    /**
     * The tile's second line as chosen in Tessera (`o.sub`): null for the line the screen works out itself ("auto"),
     * "" for none, words of your own, or a value of the entity, which the add-on sends finished (`x.s`) or as the
     * moment it names (`x.sm`, seconds), said here as a time ago.
     */
    static String chosenLine(Tessera.Tile t) {
        String choice = t.sub;
        if (choice == null || choice.isEmpty() || choice.equals("auto")) return null;
        if (choice.equals("none")) return "";
        if (choice.startsWith("text:")) return choice.substring(5);
        if (!choice.startsWith("attr:")) return null;
        Double at = Json.dbl(t.extra, "sm");
        if (at != null && at > 0) return ago((long) (at * 1000));
        String line = Json.str(t.extra, "s");
        return line == null ? "" : line;
    }

    private static String ago(long at) {
        long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (s < 60) return "Just now";
        if (s < 3600) return s / 60 + " min ago";
        if (s < 86400) return s / 3600 + " h ago";
        long days = s / 86400;
        return days == 1 ? "Yesterday" : days + " days ago";
    }

    /** The value and its unit apart, as a Big number shows them: Home Assistant's own words for a duration carry theirs. */
    private static String[] valueAndUnit(Tessera.Tile t) {
        String words = Json.str(t.extra, "sw");
        if (words != null && !words.isEmpty()) return new String[]{words, ""};
        String unit = t.text("unit_of_measurement");
        String v = t.state;
        if (t.domain().equals("climate") && t.number("current_temperature") != null) {
            v = Style.number(t.number("current_temperature"));
            unit = Style.degree(t);
        }
        return new String[]{Style.words(v).equals(v) || Json.tryParse(v) instanceof Double ? v : Style.words(v), unit == null ? "" : unit};
    }

    /**
     * A Big number card (display "watch", firmware 0.35.0+, ui-scale watchCard): the icon and the name in a head above
     * the number, the three as one group in the middle of the card, the number in the largest face that fits under the
     * head (the setpoint's digits, the watch face, then the headline); a card too low for that keeps the name small in
     * the top corner and the number under it, and the icon goes. The unit stands at the right in small letters.
     */
    private void drawWatch(Canvas c, Tessera.Tile t, RectF b, float fs, int fg, int dim, int accent, boolean on, int pastel) {
        String[] vu = valueAndUnit(t);
        String value = vu[0], unit = vu[1];
        float w = b.width(), h = b.height();
        float label = fs, small = fs * 0.88f, nameLine = label * 1.17f;
        float circle = fs * 1.45f, gap = 0.6f * u, header = Math.max(circle, nameLine);
        text.setTypeface(regular);
        text.setTextSize(small);
        float unitW = unit.isEmpty() ? 0 : Math.min(text.measureText(unit), w - 20);
        float unitGap = unitW > 0 ? unitW + 1.2f * u : 0, numberW = w - unitGap;
        boolean digits = value.matches("[-.,°%0-9]+");
        float[] faces = digits ? new float[]{fs * 3.55f, fs * 2.1f, fs * 1.5f} : new float[]{fs * 2.1f, fs * 1.5f};
        float face = 0;
        for (float f : faces) {
            text.setTypeface(light);
            text.setTextSize(f);
            if (header + gap + f * 1.17f <= h && text.measureText(value) <= numberW) { face = f; break; }
        }
        int ink = on ? fg : fg;
        if (face > 0) {
            float line = face * 1.17f, groupY = b.top + Math.max(0, (h - header - gap - line) / 2), valueY = groupY + header + gap;
            fill.setColor(on ? withAlpha(accent, pastel != 0 ? 90 : 60) : pastel != 0 ? 0x22000000 : Style.RAISED);
            float cy = groupY + header / 2;
            c.drawCircle(b.left + circle / 2, cy, circle / 2, fill);
            glyphs.setColor(on ? (pastel != 0 ? Style.DARK_TEXT : accent) : dim);
            glyphs.setTextSize(circle * 0.56f);
            c.drawText(Icons.forTile(getContext(), t), b.left + circle / 2, cy + circle * 0.2f, glyphs);
            text.setTypeface(medium);
            text.setTextSize(label);
            text.setColor(fg);
            float tx = b.left + circle + 1.2f * u;
            c.drawText(fit(t.name, b.right - tx), tx, cy + label * 0.36f, text);
            text.setTypeface(light);
            text.setTextSize(face);
            text.setColor(ink);
            float base = valueY + face * 0.95f;
            c.drawText(fit(value, numberW), b.left, base, text);
            if (!unit.isEmpty()) {
                text.setTypeface(regular);
                text.setTextSize(small);
                text.setColor(dim);
                text.setTextAlign(Paint.Align.RIGHT);
                c.drawText(unit, b.right, base, text);
                text.setTextAlign(Paint.Align.LEFT);
            }
            return;
        }
        // A low card: the name small in the corner, the number under it in the largest face that fits.
        text.setTypeface(medium);
        text.setTextSize(small);
        text.setColor(dim);
        c.drawText(fit(t.name, w), b.left, b.top + small, text);
        float below = small * 1.17f + 0.3f * u, fitted = small;
        for (float f : new float[]{fs * 2.1f, fs * 1.5f, small}) {
            text.setTypeface(light);
            text.setTextSize(f);
            if (below + f * 1.17f <= h + 0.3f * u && text.measureText(value) <= numberW) { fitted = f; break; }
        }
        text.setTypeface(light);
        text.setTextSize(fitted);
        float vw = Math.min(text.measureText(value), numberW), x = b.left + Math.max(0, (w - vw - unitGap) / 2);
        float line = fitted * 1.17f, y = b.top + Math.min(Math.max(below, (h - line) / 2), h - line);
        text.setColor(ink);
        c.drawText(fit(value, numberW), x, y + fitted * 0.95f, text);
        if (!unit.isEmpty()) {
            text.setTypeface(regular);
            text.setTextSize(small);
            text.setColor(dim);
            c.drawText(unit, x + vw + 1.2f * u, y + fitted * 0.95f, text);
        }
    }

    /** A sensor's graph tile (display "graph"): the icon with the name and value beside it, the last hours under them. */
    private void drawGraphTile(Canvas c, Tessera.Tile t, RectF b, float fs, int fg, int dim, int accent, boolean on, int pastel, boolean dead) {
        float ic = Math.min(fs * 2.2f, b.height() * 0.4f);
        float icx = b.left + ic / 2, icy = b.top + ic / 2;
        fill.setColor(on && !dead ? withAlpha(accent, pastel != 0 ? 90 : 60) : pastel != 0 ? 0x22000000 : Style.RAISED);
        c.drawCircle(icx, icy, ic / 2, fill);
        glyphs.setColor(on && !dead ? (pastel != 0 ? Style.DARK_TEXT : accent) : dim);
        glyphs.setTextSize(ic * 0.56f);
        c.drawText(Icons.forTile(getContext(), t), icx, icy + ic * 0.2f, glyphs);
        float tx = b.left + ic + 1.2f * u, tw = b.right - tx;
        text.setTypeface(medium);
        text.setTextSize(fs);
        text.setColor(fg);
        c.drawText(fit(t.name, tw), tx, icy - fs * 0.12f, text);
        String line = chosenLine(t);
        if (line == null) line = Style.stateLine(t);
        text.setTypeface(regular);
        text.setTextSize(fs * 0.86f);
        text.setColor(dim);
        c.drawText(fit(line, tw), tx, icy + fs * 1.05f, text);
        RectF g = new RectF(b.left, b.top + ic + 1.2f * u, b.right, b.bottom);
        if (g.height() < 2 * u) return;
        List<Double> v = samples(t);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double x : v) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
        if (hi - lo < 1e-9) { hi += 1; lo -= 1; }
        Path path = new Path(), area = new Path();
        float pad = 0.4f * u;
        for (int i = 0; i < v.size(); i++) {
            float x = g.left + g.width() * i / (v.size() - 1);
            float y = (float) (g.bottom - pad - (v.get(i) - lo) / (hi - lo) * (g.height() - 2 * pad));
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, g.bottom); area.lineTo(x, y); }
            else { path.lineTo(x, y); area.lineTo(x, y); }
        }
        area.lineTo(g.right, g.bottom);
        area.close();
        int colour = dead ? dim : accent;
        fill.setColor(withAlpha(colour, 50));
        c.drawPath(area, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(0.45f * u);
        fill.setColor(colour);
        c.drawPath(path, fill);
        fill.setStyle(Paint.Style.FILL);
    }

    private final ClockTile clocks = new ClockTile();
    private EnergyCard energyCard;
    private MeterTile meterTile;
    private CalendarCard calendarCard;
    private long bookingUntil;
    /** The calendar whose full screen is open, or -1. */
    private int calendarOpen = -1;

    private CalendarCard calendar() {
        if (calendarCard == null) calendarCard = new CalendarCard(getContext(), glyphs);
        return calendarCard;
    }

    /** How many pixels here a pixel of the board Tessera lays out for is (a 1024 x 600 board unless told otherwise). */
    private float glass() {
        int w = Math.max(getWidth(), getHeight()), h = Math.min(getWidth(), getHeight());
        return Math.max(0.5f, Math.min(w / 1024f, h / 600f));
    }

    /** A millimetre of glass in pixels. */
    private float mm() {
        return SaverView.dpi > 50 ? SaverView.dpi / 25.4f : Math.min(getWidth(), getHeight()) / 90f;
    }

    private void drawCalendarFull(Canvas c, int w, int h) {
        if (calendarOpen < 0 || layout == null || calendarOpen >= layout.tiles.size()) {
            calendarOpen = -1;
            return;
        }
        final Tessera.Tile calTile = layout.tiles.get(calendarOpen);
        final int calIndex = calendarOpen;
        final Tessera tessera = app.screen().tessera;
        calendar().asker = new CalendarCard.Asker() {
            public void ask(int cal, int start, int end, String day) {
                if (tessera.calendarDetail(cal + ":" + start + ":" + end) == null)
                    tessera.askCalendarEvent(calTile.entity, calIndex, cal, start, end, day);
            }
        };
        calendar().details = new java.util.AbstractMap<String, String[]>() {
            public String[] get(Object key) { return tessera.calendarDetail(String.valueOf(key)); }
            public java.util.Set<Map.Entry<String, String[]>> entrySet() { return java.util.Collections.emptySet(); }
        };
        calendar().drawFull(c, layout.tiles.get(calendarOpen), w, h, u, layout.clock24, new CalendarCard.FullHost() {
            public void close() { calendarOpen = -1; }
        }, new CalendarCard.Hits() {
            public void tap(RectF rr, Runnable run) {
                Hit hit = new Hit(new RectF(rr));
                hit.tap = run;
                hits.add(hit);
            }
        });
    }
    private final Runnable energyFrame = new Runnable() {
        public void run() { invalidate(); }
    };

    private EnergyCard energyCardFor() {
        if (energyCard == null) energyCard = new EnergyCard(getContext(), glyphs);
        return energyCard;
    }

    /** A sensor behind a circle of the energy card, its card opened over the page as its own tile's would be. */
    private Tessera.Tile cardOverride;

    private void openSensorCard(int index, String entity, String name, String state, String unit) {
        Tessera.Tile t = new Tessera.Tile();
        t.entity = entity;
        t.name = name;
        t.state = state == null ? "" : state;
        t.received = true;
        if (unit != null && !unit.isEmpty()) t.attributes.put("unit_of_measurement", unit);
        if (unit != null && unit.endsWith("W")) t.attributes.put("device_class", "power");
        cardOverride = t;
        cardTile = index;
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void drawClockTile(Canvas c, RectF r, int fg, int dim, float fs) {
        Date now = new Date();
        String time = new SimpleDateFormat(layout.clock24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        text.setTypeface(light);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(fg);
        float size = Math.min(r.height() * 0.5f, r.width() / (time.length() * 0.62f));
        text.setTextSize(size);
        c.drawText(time, r.centerX(), r.centerY() + size * 0.2f, text);
        text.setTypeface(regular);
        text.setTextSize(Math.max(fs, size * 0.18f));
        text.setColor(dim);
        c.drawText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now), r.centerX(),
                r.centerY() + size * 0.2f + text.getTextSize() * 1.6f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * The rows of controls a tile draws, top to bottom, from the set chosen in Tessera (catalogue/<domain>.yaml), or
     * null. A set of two rows on a card one row high draws its one-row form.
     */
    private String[] controlPlan(Tessera.Tile t, Cell cell) {
        String d = t.domain(), ctl = t.controls;
        boolean tall = cell.rows >= 2;
        if (ctl.isEmpty() || ctl.equals("none")) {
            return "slider".equals(t.inline) && hasLevel(t) ? new String[]{"slider"} : null;
        }
        if (ctl.equals("toggle")) return new String[]{"toggle"};
        // A light's presets (docs/LIGHT_PRESETS.md): the keys, or the slider with the keys under it on two rows.
        if (d.equals("light") && ctl.equals("presets")) return LightPresets.of(t).isEmpty() ? new String[]{"slider"} : new String[]{"presets"};
        if (d.equals("light") && ctl.equals("brightness_presets")) {
            if (LightPresets.of(t).isEmpty()) return new String[]{"slider"};
            return tall ? new String[]{"slider", "presets"} : new String[]{"presets"};
        }
        if (ctl.equals("run")) return new String[]{"run"};
        if (ctl.equals("brightness") || ctl.equals("speed") || ctl.equals("position") || ctl.equals("slider")) return new String[]{"slider"};
        if (d.equals("cover")) {
            if (ctl.equals("buttons")) return new String[]{"cover"};
            if (ctl.equals("tilt")) return new String[]{"tilt"};
            if (ctl.equals("buttons_tilt")) return tall ? new String[]{"cover", "tilt"} : new String[]{"cover"};
            if (ctl.equals("position_tilt")) return tall ? new String[]{"slider", "tilt"} : new String[]{"slider"};
        }
        if (d.equals("climate") || d.equals("humidifier")) {
            if (ctl.equals("setpoint")) return tall && d.equals("climate") ? new String[]{"bigstep"} : new String[]{"stepper"};
            if (ctl.equals("mode")) return new String[]{"modes"};
            if (ctl.equals("setpoint_mode")) return tall ? new String[]{"bigstep", "modes"} : new String[]{"stepper"};
        }
        if (d.equals("media_player")) {
            if (ctl.equals("volume")) return new String[]{"volume"};
            if (ctl.equals("playback")) return new String[]{"media"};
            if (ctl.equals("volume_playback")) return tall ? new String[]{"volume", "media"} : new String[]{"volume"};
        }
        if (ctl.equals("stepper")) return d.endsWith("select") ? new String[]{"select"} : new String[]{"stepper"};
        if (ctl.equals("buttons") && (d.equals("timer") || d.equals("vacuum"))) return new String[]{d};
        // A robot's larger tile on one row (or a screen too small for it): its keys.
        if (d.equals("vacuum") && VacuumTile.face(ctl)) return new String[]{"vacuum"};
        return null;
    }

    private static boolean hasLevel(Tessera.Tile t) {
        String d = t.domain();
        return d.equals("light") || d.equals("fan") || d.equals("cover") || d.equals("media_player")
                || d.equals("number") || d.equals("input_number") || d.equals("humidifier");
    }

    private static boolean dimmable(Tessera.Tile t) {
        List<Object> modes = t.list("supported_color_modes");
        if (modes == null) return t.number("brightness") != null;
        for (Object m : modes) if (!"onoff".equals(m)) return true;
        return false;
    }

    /** The target temperature large between - and +, with the room's below it. */
    private void drawBigSetpoint(Canvas c, final Tessera.Tile t, RectF area, float fs, boolean pastel) {
        final double step = orElse(t.number("target_temp_step"), "°F".equals(t.text("temperature_unit")) ? 1 : 0.5);
        final double lo = orElse(t.number("min_temp"), 5), hi = orElse(t.number("max_temp"), 35);
        Double target = t.number("temperature");
        final boolean range = target == null && t.number("target_temp_high") != null;
        final double now = target != null ? target : orElse(t.number(range ? "target_temp_high" : "temperature"), 20);
        String label = range ? Style.number(t.number("target_temp_low")) + "–" + Style.number(now) + Style.degree(t)
                : Style.number(now) + Style.degree(t);
        float bs = Math.min(area.height() * 0.62f, fs * 3.0f);
        RectF minus = new RectF(area.left, area.centerY() - bs / 2, area.left + bs, area.centerY() + bs / 2);
        RectF plus = new RectF(area.right - bs, area.centerY() - bs / 2, area.right, area.centerY() + bs / 2);
        int raised = pastel ? 0x22000000 : Style.RAISED, ink = pastel ? Style.DARK_TEXT : Style.TEXT;
        drawRound(c, minus, raised);
        drawRound(c, plus, raised);
        glyphs.setColor(ink);
        glyphs.setTextSize(bs * 0.5f);
        c.drawText(Icons.sure(getContext(), "minus"), minus.centerX(), minus.centerY() + bs * 0.18f, glyphs);
        c.drawText(Icons.sure(getContext(), "plus"), plus.centerX(), plus.centerY() + bs * 0.18f, glyphs);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(light);
        text.setColor(ink);
        float size = Math.min(area.height() * 0.5f, fs * 3.2f);
        text.setTextSize(size);
        while (text.measureText(label) > plus.left - minus.right - fs && size > fs) {
            size *= 0.9f;
            text.setTextSize(size);
        }
        float base = area.centerY() + size * 0.3f;
        c.drawText(label, area.centerX(), base, text);
        Double cur = t.number("current_temperature");
        if (cur != null && area.bottom - base > fs * 1.2f) {
            text.setTypeface(regular);
            text.setTextSize(fs * 0.85f);
            text.setColor(pastel ? Style.DARK_DIM : Style.DIM);
            c.drawText("Now " + Style.number(cur) + Style.degree(t), area.centerX(), Math.min(area.bottom, base + fs * 1.5f), text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        final String field = range ? "target_temp_high" : "temperature";
        Hit m = new Hit(minus), p = new Hit(plus);
        m.tap = new Runnable() {
            public void run() { setpoint(t, field, Math.max(lo, now - step)); }
        };
        p.tap = new Runnable() {
            public void run() { setpoint(t, field, Math.min(hi, now + step)); }
        };
        hits.add(m);
        hits.add(p);
    }

    private void setpoint(Tessera.Tile t, String field, double v) {
        if (field.equals("target_temp_high")) {
            call("climate.set_temperature", t, "target_temp_low", Style.number(orElse(t.number("target_temp_low"), v - 2)),
                    "target_temp_high", Style.number(v));
        } else {
            call("climate.set_temperature", t, "temperature", Style.number(v));
        }
    }

    private void drawRound(Canvas c, RectF b, int color) {
        fill.setColor(color);
        c.drawRoundRect(b, b.height() / 2f, b.height() / 2f, fill);
    }

    private static String modeIcon(String mode) {
        if (mode.equals("heat")) return "fire";
        if (mode.equals("cool")) return "snowflake";
        if (mode.equals("heat_cool")) return "sun-snowflake-variant";
        if (mode.equals("auto")) return "thermostat-auto";
        if (mode.equals("dry")) return "water-percent";
        if (mode.equals("fan_only")) return "fan";
        if (mode.equals("off")) return "power";
        return null;
    }

    private static int modeColor(String mode) {
        if (mode.equals("heat")) return Style.ORANGE;
        if (mode.equals("cool")) return Style.BLUE;
        if (mode.equals("heat_cool") || mode.equals("auto")) return Style.GREEN;
        if (mode.equals("dry") || mode.equals("fan_only")) return Style.TEAL;
        return Style.PURPLE;
    }

    private void drawControl(Canvas c, final Tessera.Tile t, String kind, RectF band, float fs, int accent, boolean pastel) {
        String d = t.domain();
        int raised = pastel ? 0x22000000 : Style.RAISED, ink = pastel ? Style.DARK_TEXT : Style.TEXT;
        if (kind.equals("presets")) {
            drawPresets(c, t, band, pastel);
            return;
        }
        if (kind.equals("slider") || kind.equals("tilt") || kind.equals("volume")) {
            final Slider s = new Slider();
            s.track = band;
            s.min = 0; s.max = 100; s.step = 1;
            if (kind.equals("volume") || d.equals("media_player") && kind.equals("slider")) {
                // Tessera's volume row (firmware 0.39.0): volume down, the slider and volume up. The keys step the player
                // (volume_down and volume_up, for players with either VOLUME_STEP or VOLUME_SET); the slider only where it
                // takes a level (VOLUME_SET). Volume down held mutes; a tap on either key while muted takes the mute off.
                final int f = t.number("supported_features") == null ? 0 : t.number("supported_features").intValue();
                final boolean muted = Boolean.TRUE.equals(t.attributes.get("is_volume_muted"));
                final boolean canMute = f == 0 || (f & 8) != 0;
                boolean stepping = f == 0 || (f & (4 | 1024)) != 0 || (muted && canMute);
                boolean setting = f == 0 || (f & 4) != 0;
                float bw = band.height() * 1.25f, g = band.height() * 0.3f;
                RectF minus = new RectF(band.left, band.top, band.left + bw, band.bottom);
                RectF plus = new RectF(band.right - bw, band.top, band.right, band.bottom);
                s.track = new RectF(minus.right + g, band.top, plus.left - g, band.bottom);
                drawRound(c, minus, stepping ? raised : withAlpha(raised, 90));
                drawRound(c, plus, stepping ? raised : withAlpha(raised, 90));
                glyphs.setColor(stepping ? ink : withAlpha(ink, 90));
                glyphs.setTextSize(band.height() * 0.55f);
                c.drawText(Icons.sure(getContext(), muted ? "volume-off" : "minus"), minus.centerX(), minus.centerY() + band.height() * 0.2f, glyphs);
                c.drawText(Icons.sure(getContext(), "plus"), plus.centerX(), plus.centerY() + band.height() * 0.2f, glyphs);
                if (stepping) {
                    Hit down = new Hit(minus);
                    down.tap = new Runnable() {
                        public void run() {
                            if (muted && canMute) call("media_player.volume_mute", t, "is_volume_muted", "false");
                            else call("media_player.volume_down", t);
                        }
                    };
                    if (canMute && !muted) down.hold = new Runnable() {
                        public void run() { call("media_player.volume_mute", t, "is_volume_muted", "true"); }
                    };
                    hits.add(down);
                    Hit up = new Hit(plus);
                    up.tap = new Runnable() {
                        public void run() {
                            if (muted && canMute) call("media_player.volume_mute", t, "is_volume_muted", "false");
                            else call("media_player.volume_up", t);
                        }
                    };
                    hits.add(up);
                }
                s.value = orElse(t.number("volume_level"), 0) * 100;
                if (!setting) {
                    // A player that only steps: the slider shows the level it reports, faded, and takes no finger.
                    fill.setColor(withAlpha(raised, 90));
                    c.drawRoundRect(s.track, s.track.height() / 2, s.track.height() / 2, fill);
                    if (t.number("volume_level") != null && s.value > 0) {
                        RectF lvl = new RectF(s.track.left, s.track.top, s.track.left + Math.max(s.track.height(), (float) (s.track.width() * s.value / 100)), s.track.bottom);
                        fill.setColor(withAlpha(accent, 90));
                        c.drawRoundRect(lvl, lvl.height() / 2, lvl.height() / 2, fill);
                    }
                    return;
                }
                s.set = new Setter() {
                    public void set(double v) { call("media_player.volume_set", t, "volume_level", Style.number(Math.round(v) / 100.0)); }
                };
            } else if (kind.equals("tilt")) {
                s.value = orElse(t.number("current_tilt_position"), 0);
                s.set = new Setter() {
                    public void set(double v) { call("cover.set_cover_tilt_position", t, "tilt_position", Style.number(v)); }
                };
            } else if (d.equals("light")) {
                Double b = t.number("brightness");
                s.min = 1;
                s.value = "on".equals(t.state) && b != null ? Math.round(b / 255.0 * 100) : 0;
                s.set = new Setter() {
                    public void set(double v) { call("light.turn_on", t, "brightness_pct", Style.number(v)); }
                };
            } else if (d.equals("fan")) {
                s.value = orElse(t.number("percentage"), 0);
                s.set = new Setter() {
                    public void set(double v) { call("fan.set_percentage", t, "percentage", Style.number(v)); }
                };
            } else if (d.equals("cover")) {
                s.value = orElse(t.number("current_position"), 0);
                s.set = new Setter() {
                    public void set(double v) { call("cover.set_cover_position", t, "position", Style.number(v)); }
                };
            } else if (d.equals("humidifier")) {
                s.min = orElse(t.number("min_humidity"), 0);
                s.max = orElse(t.number("max_humidity"), 100);
                s.value = orElse(t.number("humidity"), s.min);
                s.set = new Setter() {
                    public void set(double v) { call("humidifier.set_humidity", t, "humidity", Style.number(v)); }
                };
            } else if (d.equals("number") || d.equals("input_number")) {
                s.min = orElse(t.number("min"), 0);
                s.max = orElse(t.number("max"), 100);
                s.step = orElse(t.number("step"), 1);
                s.value = orElse(numberState(t), s.min);
                final String dom = d;
                s.set = new Setter() {
                    public void set(double v) { call(dom + ".set_value", t, "value", Style.number(v)); }
                };
            } else if (d.equals("media_player")) {
                s.value = orElse(t.number("volume_level"), 0) * 100;
                s.set = new Setter() {
                    public void set(double v) { call("media_player.volume_set", t, "volume_level", Style.number(Math.round(v) / 100.0)); }
                };
            } else {
                return;
            }
            s.key = t.entity + ":" + kind;
            Held h = held.get(s.key);
            if (h != null && SystemClock.uptimeMillis() < h.until) s.value = h.value;
            drawSlider(c, s, accent, pastel, fs);
            Hit hit = new Hit(new RectF(s.track.left, s.track.top - fs * 0.5f, s.track.right, s.track.bottom + fs * 0.3f));
            hit.slider = s;
            hit.hold = new Runnable() {
                public void run() { openCard(t); }
            };
            hits.add(hit);
        } else if (kind.equals("stepper")) {
            boolean climate = d.equals("climate"), humid = d.equals("humidifier");
            final double step = climate ? orElse(t.number("target_temp_step"), "°F".equals(t.text("temperature_unit")) ? 1 : 0.5)
                    : humid ? 1 : orElse(t.number("step"), 1);
            final double now = climate ? orElse(t.number("temperature"), orElse(t.number("target_temp_high"), 20))
                    : humid ? orElse(t.number("humidity"), 50) : orElse(numberState(t), 0);
            final double lo = climate ? orElse(t.number("min_temp"), 5) : humid ? orElse(t.number("min_humidity"), 0) : orElse(t.number("min"), 0);
            final double hi = climate ? orElse(t.number("max_temp"), 35) : humid ? orElse(t.number("max_humidity"), 100) : orElse(t.number("max"), 100);
            String label = Style.number(now) + (climate ? Style.degree(t) : humid ? "%" : "");
            final boolean range = climate && t.number("temperature") == null;
            float bw = band.height() * 1.25f;
            RectF minus = new RectF(band.right - bw * 3.7f, band.top, band.right - bw * 2.7f, band.bottom);
            RectF plus = new RectF(band.right - bw, band.top, band.right, band.bottom);
            drawButton(c, minus, "−", raised, ink);
            drawButton(c, plus, "+", raised, ink);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(medium);
            text.setTextSize(fs * 1.05f);
            text.setColor(ink);
            c.drawText(label, (minus.right + plus.left) / 2f, band.centerY() + fs * 0.38f, text);
            text.setTextAlign(Paint.Align.LEFT);
            final String dom = d;
            Hit m = new Hit(minus), p = new Hit(plus);
            m.tap = new Runnable() {
                public void run() { step(t, dom, range, Math.max(lo, now - step)); }
            };
            p.tap = new Runnable() {
                public void run() { step(t, dom, range, Math.min(hi, now + step)); }
            };
            hits.add(m);
            hits.add(p);
        } else if (kind.equals("modes")) {
            drawModes(c, t, band, fs, pastel);
        } else if (kind.equals("select")) {
            final List<Object> options = t.list("options");
            float bw = band.height() * 1.25f;
            RectF prev = new RectF(band.left, band.top, band.left + bw, band.bottom);
            RectF next = new RectF(band.right - bw, band.top, band.right, band.bottom);
            drawRound(c, prev, raised);
            drawRound(c, next, raised);
            glyphs.setColor(ink);
            glyphs.setTextSize(band.height() * 0.55f);
            c.drawText(Icons.sure(getContext(), "chevron-left"), prev.centerX(), prev.centerY() + band.height() * 0.2f, glyphs);
            c.drawText(Icons.sure(getContext(), "chevron-right"), next.centerX(), next.centerY() + band.height() * 0.2f, glyphs);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(medium);
            text.setTextSize(fs);
            text.setColor(ink);
            c.drawText(fit(t.state, next.left - prev.right - fs), band.centerX(), band.centerY() + fs * 0.36f, text);
            text.setTextAlign(Paint.Align.LEFT);
            final String dom = d;
            Hit ph = new Hit(prev), nh = new Hit(next);
            ph.tap = new Runnable() {
                public void run() { call(dom + ".select_previous", t, "cycle", "true"); }
            };
            nh.tap = new Runnable() {
                public void run() { call(dom + ".select_next", t, "cycle", "true"); }
            };
            hits.add(ph);
            hits.add(nh);
        } else if (kind.equals("cover")) {
            drawIconButtons(c, t, band, new String[]{"arrow-up", "stop", "arrow-down"},
                    new String[]{"cover.open_cover", "cover.stop_cover", "cover.close_cover"}, pastel);
        } else if (kind.equals("media")) {
            drawIconButtons(c, t, band, new String[]{"skip-previous", "playing".equals(t.state) ? "pause" : "play", "skip-next"},
                    new String[]{"media_player.media_previous_track", "media_player.media_play_pause", "media_player.media_next_track"}, pastel);
        } else if (kind.equals("timer")) {
            drawIconButtons(c, t, band, new String[]{"active".equals(t.state) ? "pause" : "play", "stop"},
                    new String[]{"active".equals(t.state) ? "timer.pause" : "timer.start", "timer.cancel"}, pastel);
        } else if (kind.equals("vacuum")) {
            List<String[]> keys = VacuumCard.tileKeys(t);
            String[] icons = new String[keys.size()], services = new String[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                icons[i] = keys.get(i)[0];
                services[i] = keys.get(i)[1];
            }
            if (icons.length > 0) drawIconButtons(c, t, band, icons, services, pastel);
        } else if (kind.equals("run")) {
            String service = d.equals("button") || d.equals("input_button") ? d + ".press"
                    : d.equals("automation") ? "automation.trigger" : d + ".turn_on";
            drawIconButtons(c, t, band, new String[]{"play"}, new String[]{service}, pastel);
        } else if (kind.equals("toggle")) {
            float tw = band.height() * 1.9f;
            RectF sw = new RectF(band.right - tw, band.top + band.height() * 0.15f, band.right, band.bottom - band.height() * 0.15f);
            boolean on = Style.active(t);
            fill.setColor(on ? accent : pastel ? 0x33000000 : Style.LINE);
            c.drawRoundRect(sw, sw.height() / 2, sw.height() / 2, fill);
            fill.setColor(0xFFFFFFFF);
            float knob = sw.height() * 0.4f;
            c.drawCircle(on ? sw.right - sw.height() / 2 : sw.left + sw.height() / 2, sw.centerY(), knob, fill);
            Hit h = new Hit(new RectF(sw.left - fs, band.top, band.right, band.bottom));
            h.tap = new Runnable() {
                public void run() { toggle(t); }
            };
            hits.add(h);
        }
    }

    private void step(Tessera.Tile t, String domain, boolean range, double v) {
        if (domain.equals("climate")) setpoint(t, range ? "target_temp_high" : "temperature", v);
        else if (domain.equals("humidifier")) call("humidifier.set_humidity", t, "humidity", Style.number(v));
        else call(domain + ".set_value", t, "value", Style.number(v));
    }

    /** One segment per mode, the active one filled; heat and cool first, and "…" for what doesn't fit. */
    private void drawModes(Canvas c, final Tessera.Tile t, RectF band, float fs, boolean pastel) {
        boolean climate = t.domain().equals("climate");
        List<Object> all = climate ? t.list("hvac_modes") : t.list("available_modes");
        if (all == null) return;
        List<String> modes = new ArrayList<String>();
        for (String first : new String[]{"heat", "cool"}) if (all.contains(first)) modes.add(first);
        for (Object o : all) if (o instanceof String && !"off".equals(o) && !modes.contains(o)) modes.add((String) o);
        if (modes.isEmpty()) return;
        final String current = climate ? t.state : t.text("mode");
        float minW = band.height() * 1.6f;
        int fits = Math.max(1, (int) (band.width() / minW));
        boolean more = modes.size() > fits;
        int shown = more ? fits - 1 : modes.size();
        int slots = shown + (more ? 1 : 0);
        float segW = band.width() / slots;
        drawRound(c, band, pastel ? 0x22000000 : Style.RAISED);
        text.setTypeface(medium);
        text.setTextSize(fs * 0.78f);
        for (int i = 0; i < slots; i++) {
            RectF seg = new RectF(band.left + i * segW, band.top, band.left + (i + 1) * segW, band.bottom);
            Hit h = new Hit(seg);
            if (i == shown) {
                text.setTextAlign(Paint.Align.CENTER);
                text.setColor(pastel ? Style.DARK_TEXT : Style.TEXT);
                c.drawText("…", seg.centerX(), seg.centerY() + fs * 0.3f, text);
                h.tap = new Runnable() {
                    public void run() { openCard(t); }
                };
                hits.add(h);
                continue;
            }
            final String mode = modes.get(i);
            boolean active = mode.equals(current);
            if (active) {
                RectF f = new RectF(seg.left + 2, seg.top + 2, seg.right - 2, seg.bottom - 2);
                drawRound(c, f, modeColor(mode));
            }
            int ink = active ? Style.DARK_TEXT : pastel ? Style.DARK_TEXT : Style.DIM;
            String icon = climate ? modeIcon(mode) : null;
            String label = Style.words(mode);
            boolean words = segW > band.height() * 1.4f;
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(ink);
            glyphs.setColor(ink);
            glyphs.setTextSize(band.height() * 0.42f);
            if (icon != null && words) {
                float tw = text.measureText(label) + band.height() * 0.5f;
                if (tw < segW * 0.92f) {
                    float x0 = seg.centerX() - tw / 2;
                    c.drawText(Icons.sure(getContext(), icon), x0 + band.height() * 0.2f, seg.centerY() + band.height() * 0.15f, glyphs);
                    text.setTextAlign(Paint.Align.LEFT);
                    c.drawText(label, x0 + band.height() * 0.5f, seg.centerY() + fs * 0.28f, text);
                } else {
                    c.drawText(Icons.sure(getContext(), icon), seg.centerX(), seg.centerY() + band.height() * 0.15f, glyphs);
                }
            } else if (icon != null) {
                c.drawText(Icons.sure(getContext(), icon), seg.centerX(), seg.centerY() + band.height() * 0.15f, glyphs);
            } else {
                c.drawText(fit(label, segW * 0.9f), seg.centerX(), seg.centerY() + fs * 0.28f, text);
            }
            text.setTextAlign(Paint.Align.LEFT);
            h.tap = new Runnable() {
                public void run() {
                    if (t.domain().equals("climate")) call("climate.set_hvac_mode", t, "hvac_mode", mode);
                    else call("humidifier.set_mode", t, "mode", mode);
                }
            };
            hits.add(h);
        }
    }

    private void openCard(Tessera.Tile t) {
        if (layout == null) return;
        for (int i = 0; i < layout.tiles.size(); i++) {
            if (layout.tiles.get(i).entity.equals(t.entity)) {
                cardTile = i;
                return;
            }
        }
    }

    private void drawIconButtons(Canvas c, final Tessera.Tile t, RectF band, String[] icons, final String[] services, boolean pastel) {
        float bw = band.height() * 1.25f, gap = bw * 0.25f;
        float x = band.right - icons.length * bw - (icons.length - 1) * gap;
        for (int i = 0; i < icons.length; i++) {
            RectF b = new RectF(x, band.top, x + bw, band.bottom);
            drawButton(c, b, null, pastel ? 0x22000000 : Style.RAISED, 0);
            glyphs.setColor(pastel ? Style.DARK_TEXT : Style.TEXT);
            glyphs.setTextSize(b.height() * 0.55f);
            c.drawText(Icons.sure(getContext(), icons[i]), b.centerX(), b.centerY() + b.height() * 0.2f, glyphs);
            final String service = services[i];
            Hit h = new Hit(b);
            h.tap = new Runnable() {
                public void run() { call(service, t); }
            };
            hits.add(h);
            x += bw + gap;
        }
    }

    private void drawSlider(Canvas c, Slider s, int accent, boolean pastel, float fs) {
        RectF tr = s.track;
        float rad = tr.height() / 2f;
        fill.setColor(pastel ? 0x22000000 : Style.RAISED);
        c.drawRoundRect(tr, rad, rad, fill);
        double frac = s.max > s.min ? (s.value - s.min) / (s.max - s.min) : 0;
        frac = Math.max(0, Math.min(1, frac));
        if (frac > 0) {
            RectF f = new RectF(tr.left, tr.top, tr.left + Math.max(tr.height(), (float) (tr.width() * frac)), tr.bottom);
            fill.setColor(pastel ? withAlpha(Style.DARK_TEXT, 120) : withAlpha(accent, 200));
            c.drawRoundRect(f, rad, rad, fill);
        }
        text.setTypeface(medium);
        text.setTextSize(fs * 0.85f);
        text.setColor(pastel ? Style.DARK_TEXT : Style.TEXT);
        String label = Style.number((double) Math.round(s.value / s.step) * s.step) + (s.max == 100 && s.min <= 1 ? "%" : "");
        c.drawText(label, tr.left + rad * 0.8f, tr.centerY() + fs * 0.3f, text);
    }

    private void drawButton(Canvas c, RectF b, String label, int bg, int fg) {
        fill.setColor(bg);
        c.drawRoundRect(b, b.height() / 2f, b.height() / 2f, fill);
        if (label != null) {
            text.setTypeface(medium);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(b.height() * 0.42f);
            text.setColor(fg);
            c.drawText(label, b.centerX(), b.centerY() + b.height() * 0.15f, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ----- the detail card -----

    private void drawCard(Canvas c, final Tessera.Tile t, int w, int h) {
        fill.setColor(0xB0000000);
        c.drawRect(0, 0, w, h, fill);
        Hit outside = new Hit(new RectF(0, 0, w, h));
        outside.tap = new Runnable() {
            public void run() { cardTile = -1; }
        };
        hits.add(outside);
        // The library takes nearly the whole glass, as Tessera's page does; every other card a hand's width.
        boolean wideCard = t.domain().equals("media_player") && cardView.equals("lib") && cardTile == cardViewTile
                || t.domain().equals("vacuum") && VacuumCard.wide(t, w, h);
        float cw = wideCard ? w * 0.96f : Math.min(w * 0.86f, 120 * u), chh = wideCard ? h * 0.94f : h * 0.82f;
        RectF r = new RectF((w - cw) / 2, (h - chh) / 2, (w + cw) / 2, (h + chh) / 2);
        fill.setColor(Style.CARD);
        c.drawRoundRect(r, 3 * u, 3 * u, fill);
        hits.add(new Hit(new RectF(r))); // swallow taps on the card itself
        float pad = 4 * u, fs = 4.2f * u;
        int accent = Style.accent(t);
        boolean on = Style.active(t);

        // Header.
        float ic = 11 * u;
        fill.setColor(on ? withAlpha(accent, 60) : Style.RAISED);
        c.drawCircle(r.left + pad + ic / 2, r.top + pad + ic / 2, ic / 2, fill);
        glyphs.setColor(on ? accent : Style.DIM);
        glyphs.setTextSize(ic * 0.56f);
        boolean backKey = t.domain().equals("vacuum") && vacuumCard != null && vacuumCard.view != VacuumCard.MAIN && cardTile == cardViewTile;
        c.drawText(backKey ? Icons.glyph(VacuumCard.ICON_BACK) : Icons.forTile(getContext(), t), r.left + pad + ic / 2, r.top + pad + ic * 0.7f, glyphs);
        float tx = r.left + pad * 1.5f + ic;
        boolean vacuumView = t.domain().equals("vacuum") && vacuum().view != VacuumCard.MAIN && cardTile == cardViewTile;
        text.setTypeface(medium);
        text.setTextSize(fs * 1.2f);
        text.setColor(Style.TEXT);
        c.drawText(fit(vacuumView ? vacuum().viewTitle() : t.name, r.right - tx - 22 * u), tx, r.top + pad + ic * 0.45f, text);
        text.setTypeface(regular);
        text.setTextSize(fs * 0.9f);
        text.setColor(Style.DIM);
        c.drawText(fit(vacuumView ? t.name : Style.stateLine(t), r.right - tx - 22 * u), tx, r.top + pad + ic * 0.9f, text);
        if (vacuumView) {
            // A view of the vacuum card: the round key at the left goes back to the card, as Tessera's back key does.
            Hit back = new Hit(new RectF(r.left, r.top, tx, r.top + pad * 2 + ic));
            back.tap = new Runnable() {
                public void run() { vacuum().back(); }
            };
            hits.add(back);
        }
        glyphs.setColor(Style.DIM);
        glyphs.setTextSize(6 * u);
        c.drawText(Icons.sure(getContext(), "close"), r.right - pad - 3 * u, r.top + pad + 5 * u, glyphs);
        Hit close = new Hit(new RectF(r.right - pad - 9 * u, r.top, r.right, r.top + pad + 10 * u));
        close.tap = new Runnable() {
            public void run() { cardTile = -1; }
        };
        hits.add(close);

        if (cardTile != cardViewTile) {
            cardViewTile = cardTile;
            if (vacuumCard != null) vacuumCard.reset();
            cardView = "";
            pickPage = 0;
            historyFor = "";
            scrubX = Float.NaN;
        }
        // The sparkles key at the top right, beside the close key: the light's effects page (effects_page.h).
        if (t.domain().equals("light") && effectsOffered(t) && cardView.isEmpty()) {
            float kx = r.right - pad - 13 * u;
            glyphs.setColor(Style.DIM);
            glyphs.setTextSize(6 * u);
            c.drawText(Icons.sure(getContext(), "creation"), kx, r.top + pad + 5 * u, glyphs);
            Hit fx = new Hit(new RectF(kx - 5 * u, r.top, kx + 5 * u, r.top + pad + 10 * u));
            fx.tap = new Runnable() {
                public void run() { cardView = "effects"; }
            };
            hits.add(fx);
        }

        // The library key at the top right of a player whose library opens, as Tessera's card has it.
        if (t.domain().equals("media_player") && Json.integer(t.extra, "lb") != null && cardView.isEmpty()) {
            float kx = r.right - pad - 13 * u;
            glyphs.setColor(Style.DIM);
            glyphs.setTextSize(6 * u);
            c.drawText(Icons.sure(getContext(), "library-shelves"), kx, r.top + pad + 5 * u, glyphs);
            Hit lib = new Hit(new RectF(kx - 5 * u, r.top, kx + 5 * u, r.top + pad + 10 * u));
            lib.tap = new Runnable() {
                public void run() {
                    cardView = "lib";
                    libraryPath.clear();
                    libraryPage = 0;
                    app.screen().tessera.askFolder(t.entity, 0);
                }
            };
            hits.add(lib);
        }
        RectF body = new RectF(r.left + pad, r.top + pad * 2 + ic, r.right - pad, r.bottom - pad);
        if (t.domain().equals("remote") && Json.arr(t.extra.get("keys")) != null) {
            // A remote whose integration names its keys: the keypad, the power key in the top bar (render_remote_detail).
            final boolean powered = "on".equals(t.state);
            float kx = r.right - pad - 13 * u;
            glyphs.setColor(powered ? Style.accent(t) : Style.DIM);
            glyphs.setTextSize(6 * u);
            c.drawText(Icons.glyph("F0425"), kx, r.top + pad + 5 * u, glyphs);
            Hit power = new Hit(new RectF(kx - 5 * u, r.top, kx + 5 * u, r.top + pad + 10 * u));
            power.tap = new Runnable() {
                public void run() { call(powered ? "remote.turn_off" : "remote.turn_on", t); }
            };
            hits.add(power);
            drawRemoteKeypad(c, t, body);
            return;
        }
        if (t.domain().equals("vacuum")) {
            drawVacuum(c, t, body);
            // The top bar's keys: suction and water at the edge beside the close key, Locate beside it.
            float kx = r.right - pad - 13 * u;
            if (vacuum().barHow) {
                barKey(c, kx, r.top + pad, VacuumCard.ICON_HOW, new Runnable() {
                    public void run() { vacuum().open(VacuumCard.HOW); }
                });
                kx -= 10 * u;
            }
            if (vacuum().barLocate) {
                barKey(c, kx, r.top + pad, VacuumCard.ICON_LOCATE, new Runnable() {
                    public void run() { call("vacuum.locate", t); }
                });
            }
            return;
        }
        if (t.domain().equals("light") && !cardView.isEmpty()) {
            drawEffects(c, t, body, fs, accent);
            return;
        }
        if (t.domain().equals("media_player") && cardView.equals("lib")) {
            drawLibrary(c, t, body, fs, accent);
            return;
        }
        drawCardBody(c, t, body, fs, accent);
    }

    // ----- a remote's keypad (render_remote_keypad, firmware 0.22.0) -----

    private static final int RK_UP = 0, RK_DOWN = 1, RK_LEFT = 2, RK_RIGHT = 3, RK_OK = 4, RK_BACK = 5, RK_HOME = 6,
            RK_PLAY = 7, RK_VOLUME_UP = 8, RK_VOLUME_DOWN = 9, RK_MUTE = 10;

    /**
     * A ring with four arrows round OK, as every TV remote has it, and Back, Home and Play/Pause and the volume as round
     * keys: a row of them under the ring, two rows, or a column each side, whichever leaves the largest ring. Only the keys
     * the remote's integration takes are drawn (`x.keys`, the command of each key); a key sends remote.send_command.
     */
    private void drawRemoteKeypad(Canvas c, final Tessera.Tile t, RectF body) {
        final List<Object> keys = Json.arr(t.extra.get("keys"));
        List<Integer> lefts = new ArrayList<Integer>(), rights = new ArrayList<Integer>();
        for (int k : new int[]{RK_BACK, RK_HOME, RK_PLAY}) if (remoteHas(keys, k)) lefts.add(k);
        for (int k : new int[]{RK_VOLUME_UP, RK_MUTE, RK_VOLUME_DOWN}) if (remoteHas(keys, k)) rights.add(k);
        float gap = 2 * u, room = body.height(), span = body.width(), most = 80 * mm();
        float key = Math.max(10 * u, 7 * mm());
        float[] fit = remoteFit(key, gap, room, span, most);
        float grown = Math.min(Math.max(key, fit[0] / 5), 14 * mm());
        if (grown > key) {
            key = grown;
            fit = remoteFit(key, gap, room, span, most);
        }
        float ring = fit[0];
        boolean below = fit[1] > 0;
        int rows = (int) fit[2];
        // The whole keypad centred in the card's body.
        float total = below ? ring + (lefts.isEmpty() && rights.isEmpty() ? 0 : gap + key + (rows == 2 && !lefts.isEmpty() && !rights.isEmpty() ? gap + key : 0)) : ring;
        float x = body.centerX() - ring / 2, y = body.top + Math.max(0, (body.height() - total) / 2);
        fill.setColor(Style.RAISED);
        c.drawCircle(x + ring / 2, y + ring / 2, ring / 2, fill);
        float ok = ring * 2 / 5, third = ring / 3, deep = (ring - ok) / 2;
        float[][] zones = {{RK_UP, third, 0, third, deep}, {RK_DOWN, third, ring - deep, third, deep},
                {RK_LEFT, 0, third, deep, third}, {RK_RIGHT, ring - deep, third, deep, third}};
        String[] arrows = {"F0143", "F0140", "F0141", "F0142"};
        glyphs.setColor(Style.TEXT);
        glyphs.setTextSize(Math.min(deep * 0.5f, 9 * u));
        for (int i = 0; i < 4; i++) {
            float[] z = zones[i];
            RectF zr = new RectF(x + z[1], y + z[2], x + z[1] + z[3], y + z[2] + z[4]);
            if (!remoteHas(keys, (int) z[0])) continue;
            c.drawText(Icons.glyph(arrows[i]), zr.centerX(), zr.centerY() + glyphs.getTextSize() * 0.36f, glyphs);
            remoteHit(t, keys, (int) z[0], zr);
        }
        RectF centre = new RectF(x + (ring - ok) / 2, y + (ring - ok) / 2, x + (ring + ok) / 2, y + (ring + ok) / 2);
        fill.setColor(Style.CARD);
        c.drawOval(centre, fill);
        text.setTypeface(medium);
        text.setTextSize(Math.min(ok * 0.2f, 6 * u));
        text.setColor(Style.TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("OK", centre.centerX(), centre.centerY() + text.getTextSize() * 0.36f, text);
        text.setTextAlign(Paint.Align.LEFT);
        if (remoteHas(keys, RK_OK)) remoteHit(t, keys, RK_OK, centre);
        if (below && rows == 1) {
            List<Integer> all = new ArrayList<Integer>(lefts);
            all.addAll(rights);
            remoteRow(c, t, keys, all, body, y + ring + gap, key, gap);
        } else if (below) {
            remoteRow(c, t, keys, lefts, body, y + ring + gap, key, gap);
            remoteRow(c, t, keys, rights, body, y + ring + gap + (lefts.isEmpty() ? 0 : key + gap), key, gap);
        } else {
            remoteColumn(c, t, keys, lefts, x - gap - key, y, ring, key, gap);
            remoteColumn(c, t, keys, rights, x + ring + gap, y, ring, key, gap);
        }
    }

    /** {ring, below (1/0), rows} for round keys of `key`: the layout that leaves the largest ring. */
    private float[] remoteFit(float key, float gap, float room, float span, float most) {
        int rows = 6 * key + 5 * gap <= span ? 1 : 3 * key + 2 * gap <= span ? 2 : 0;
        float under = rows > 0 ? Math.min(Math.min(room - rows * (key + gap), span), most) : 0;
        float beside = Math.min(Math.min(room, span - 2 * (key + gap)), most);
        boolean below = under >= beside;
        return new float[]{Math.max(30 * u, below ? under : beside), below ? 1 : 0, rows};
    }

    private static boolean remoteHas(List<Object> keys, int k) {
        return keys != null && k < keys.size() && keys.get(k) instanceof String && !((String) keys.get(k)).isEmpty();
    }

    private void remoteHit(final Tessera.Tile t, final List<Object> keys, final int k, RectF r) {
        Hit h = new Hit(new RectF(r));
        h.tap = new Runnable() {
            public void run() {
                if (!Style.unavailable(t)) call("remote.send_command", t, "command", (String) keys.get(k));
            }
        };
        hits.add(h);
    }

    private void remoteKey(Canvas c, Tessera.Tile t, List<Object> keys, int k, float kx, float ky, float key) {
        String g = k == RK_BACK ? "F17B3" : k == RK_HOME ? "F02DC" : k == RK_PLAY ? "F040E" : k == RK_VOLUME_UP ? "F075D"
                : k == RK_VOLUME_DOWN ? "F075E" : "F075F";
        RectF r = new RectF(kx, ky, kx + key, ky + key);
        fill.setColor(Style.RAISED);
        c.drawOval(r, fill);
        glyphs.setColor(Style.TEXT);
        glyphs.setTextSize(key * 0.42f);
        c.drawText(Icons.glyph(g), r.centerX(), r.centerY() + key * 0.15f, glyphs);
        remoteHit(t, keys, k, r);
    }

    private void remoteRow(Canvas c, Tessera.Tile t, List<Object> keys, List<Integer> list, RectF body, float ky, float key, float gap) {
        int n = list.size();
        float step = key + gap, start = body.centerX() - ((n - 1) * step + key) / 2;
        for (int i = 0; i < n; i++) remoteKey(c, t, keys, list.get(i), start + i * step, ky, key);
    }

    private void remoteColumn(Canvas c, Tessera.Tile t, List<Object> keys, List<Integer> list, float kx, float y, float ring, float key, float gap) {
        int n = list.size();
        float span = n * key + (n - 1) * gap, start = y + (ring - span) / 2;
        for (int i = 0; i < n; i++) remoteKey(c, t, keys, list.get(i), kx, start + i * (key + gap), key);
    }

    // ----- a robot vacuum's card (module_vacuum_card.cpp) -----

    private VacuumCard vacuumCard;
    /** The map asked for last, so a new mark or size asks again. */
    private String vacuumMapWish = "";
    private boolean vacuumMapMissing;

    private VacuumCard vacuum() {
        if (vacuumCard == null) vacuumCard = new VacuumCard(getContext(), glyphs);
        return vacuumCard;
    }

    private void barKey(Canvas c, float kx, float top, String icon, Runnable run) {
        glyphs.setColor(Style.DIM);
        glyphs.setTextSize(6 * u);
        c.drawText(Icons.glyph(icon), kx, top + 5 * u, glyphs);
        Hit hit = new Hit(new RectF(kx - 5 * u, top - 4 * u, kx + 5 * u, top + 10 * u));
        hit.tap = run;
        hits.add(hit);
    }

    private final Map<Integer, VacuumTile> vacuumTiles = new HashMap<Integer, VacuumTile>();
    private final Map<Integer, Boolean> vacuumTileMissing = new HashMap<Integer, Boolean>();

    /** A robot's larger tile (VacuumTile): its map asked for as the source vacuum:<entity> at the map's exact size. */
    private void drawVacuumTile(Canvas c, final int index, final Tessera.Tile t, RectF box, float fs, int fg, int dim, int raised) {
        VacuumTile vt = vacuumTiles.get(index);
        if (vt == null) {
            vt = new VacuumTile(getContext(), glyphs);
            vacuumTiles.put(index, vt);
        }
        final Tessera tessera = app.screen().tessera;
        vt.draw(c, t, box, u, fs, mm(), fg, dim, raised, new VacuumTile.Host() {
            public void tap(RectF rr, Runnable run) {
                Hit hit = new Hit(new RectF(rr));
                hit.tap = run;
                hits.add(hit);
            }

            public void call(String service, Map<String, String> data, Map<String, String> templates) {
                if (!app.screen().perform(service, data, templates)) noActions();
                invalidate();
            }

            public void openCard() { cardTile = index; }

            public Bitmap map(String mapEntity, String mark, final int w, final int h, final int bg, final boolean dark) {
                final String kind = "vtile:" + index;
                final String entity = t.entity;
                final String ground = String.format(Locale.US, "%06X", bg & 0xFFFFFF);
                if (pictures.want(kind, entity + "|" + mark + "|" + w + "x" + h + "|" + ground + "|" + dark)) vacuumTileMissing.remove(index);
                Bitmap b = pictures.keepFresh(tessera, kind, null, 3600000L, new Runnable() {
                    public void run() {
                        Map<String, Object> q = question("tile", index, entity, "vacuum:" + entity, w, h, 0, 0, "contain", ground);
                        // A map's lines and words stay sharp as BMP (docs/PICTURES.md).
                        List<Object> formats = new ArrayList<Object>();
                        formats.add("bmp24");
                        q.put("fmt", formats);
                        tessera.askQuestion(kind, q);
                    }
                });
                boolean missing = b == null && tessera.answeredEmpty(kind);
                vacuumTileMissing.put(index, missing);
                if (b == null && !missing) {
                    ui.postDelayed(new Runnable() {
                        public void run() { invalidate(); }
                    }, 150);
                }
                return b;
            }

            public boolean mapMissing() { return Boolean.TRUE.equals(vacuumTileMissing.get(index)); }
        });
    }

    private void drawVacuum(Canvas c, final Tessera.Tile t, RectF body) {
        final Tessera tessera = app.screen().tessera;
        vacuum().draw(c, t, body, u, new VacuumCard.Host() {
            public void tap(RectF rr, Runnable run) {
                Hit hit = new Hit(new RectF(rr));
                hit.tap = run;
                hits.add(hit);
            }

            public void call(String service, Map<String, String> data, Map<String, String> templates) {
                if (!app.screen().perform(service, data, templates)) noActions();
                invalidate();
            }

            public Bitmap map(String mapEntity, String mark, final int w, final int h, final int bg, final boolean dark) {
                final String entity = t.entity;
                String wish = entity + "|" + mark + "|" + w + "x" + h + "|" + Integer.toHexString(bg & 0xFFFFFF) + "|" + dark;
                if (pictures.want("vmap", wish)) {
                    vacuumMapWish = wish;
                    vacuumMapMissing = false;
                }
                Bitmap b = pictures.keepFresh(tessera, "vmap", entity, 3600000L, new Runnable() {
                    public void run() {
                        Map<String, String> f = new LinkedHashMap<String, String>();
                        f.put("entity", entity);
                        f.put("vmap", w + "x" + h);
                        f.put("bg", String.format(java.util.Locale.ROOT, "%06X", bg & 0xFFFFFF));
                        f.put("dark", dark ? "1" : "0");
                        tessera.askPicture("vmap", f);
                    }
                });
                vacuumMapMissing = b == null && tessera.answeredEmpty("vmap");
                if (b == null && !vacuumMapMissing) {
                    ui.postDelayed(new Runnable() {
                        public void run() { invalidate(); }
                    }, 120);
                }
                return b;
            }

            public boolean mapMissing() { return vacuumMapMissing; }
        });
    }

    // ----- a light's presets (module_light_presets.h) -----

    /** A tapped preset on its way: the light and the preset's place, until Home Assistant reports it (or six seconds). */
    private final Map<String, long[]> presetSent = new HashMap<String, long[]>();

    private void drawPresets(Canvas c, final Tessera.Tile t, RectF band, boolean pastel) {
        final List<LightPresets.Preset> presets = LightPresets.of(t);
        if (presets.isEmpty()) return;
        boolean down = band.height() > band.width() * 1.5f;
        float d = down ? band.width() : band.height(), gap = d * 0.35f;
        int fit = Math.max(1, (int) (((down ? band.height() : band.width()) + gap) / (d + gap)));
        int n = Math.min(fit, presets.size());
        long now = SystemClock.uptimeMillis();
        long[] sent = presetSent.get(t.entity);
        if (sent != null && (now > sent[1] || sent[0] < presets.size() && LightPresets.shows(t, presets.get((int) sent[0])))) {
            presetSent.remove(t.entity);
            sent = null;
        }
        boolean levels = LightPresets.levels(t);
        int ink = pastel ? Style.DARK_TEXT : Style.TEXT;
        float x = down ? band.top : band.left;
        for (int i = 0; i < n; i++) {
            final int index = i;
            LightPresets.Preset p = presets.get(i);
            float cx = down ? band.centerX() : x + d / 2, cy = down ? x + d / 2 : band.centerY(), rad = d / 2 - d * 0.1f;
            int colour = LightPresets.colour(p);
            int disc = colour != 0 ? colour : pastel ? 0x22000000 : Style.RAISED;
            fill.setColor(disc);
            c.drawCircle(cx, cy, rad, fill);
            if (colour != 0 && LightPresets.luminance(colour) > 0.8) {
                // A hairline round a key nearly white, so it stands off a light card.
                line.setColor(withAlpha(ink, 60));
                line.setStrokeWidth(Math.max(1, d * 0.02f));
                c.drawCircle(cx, cy, rad, line);
            }
            int keyInk = colour != 0 ? (LightPresets.luminance(colour) > 0.35 ? 0xFF1E2430 : 0xFFFFFFFF) : ink;
            String word = p.named() ? p.letters() : colour == 0 || levels ? p.b + "%" : null;
            if (word != null) {
                text.setTypeface(p.named() ? medium : regular);
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(rad * (p.named() ? 0.7f : 0.55f));
                text.setColor(colour != 0 && levels ? withAlpha(keyInk, 190) : keyInk);
                c.drawText(word, cx, cy + rad * 0.2f, text);
                text.setTextAlign(Paint.Align.LEFT);
            }
            boolean pending = sent != null && sent[0] == i;
            line.setColor(ink);
            line.setStrokeWidth(Math.max(1.5f, d * 0.05f));
            RectF ring = new RectF(cx - d / 2 + d * 0.02f, cy - d / 2 + d * 0.02f, cx + d / 2 - d * 0.02f, cy + d / 2 - d * 0.02f);
            if (pending) {
                // On its way: a spinner where its ring will be.
                c.drawArc(ring, (now / 3) % 360, 100, false, line);
            } else if (sent == null && LightPresets.shows(t, p)) {
                c.drawCircle(cx, cy, ring.width() / 2, line);
            }
            Hit h = new Hit(down ? new RectF(band.left, x - gap / 2, band.right, x + d + gap / 2)
                    : new RectF(x - gap / 2, band.top, x + d + gap / 2, band.bottom));
            h.tap = new Runnable() {
                public void run() { sendPreset(t, presets.get(index), index); }
            };
            hits.add(h);
            x += d + gap;
        }
        if (sent != null) {
            ui.postDelayed(new Runnable() {
                public void run() { invalidate(); }
            }, 60);
        }
    }

    private void sendPreset(Tessera.Tile t, LightPresets.Preset p, int index) {
        long now = SystemClock.uptimeMillis();
        long[] sent = presetSent.get(t.entity);
        // A preset is several wishes at once: the other keys wait for this one.
        if (sent != null && now < sent[1]) return;
        boolean ok = true;
        for (LightPresets.Call call : LightPresets.calls(t, p)) {
            ok &= app.screen().perform(call.service, call.data, call.templates.isEmpty() ? null : call.templates);
        }
        if (!ok) {
            noActions();
            return;
        }
        if (!LightPresets.shows(t, p)) presetSent.put(t.entity, new long[]{index, now + 6000});
        invalidate();
    }

    // ----- Tessera's plugins, ported (PluginKit) -----

    /** The card of a plugin over the page: {plugin, card, entity, tile index}, or null. */
    private String[] pluginCard;
    private PluginKit.Drag dragged;
    private int lastStep = Integer.MIN_VALUE;
    private PluginKit.Host pluginHost;

    private PluginKit.Host pluginHost() {
        if (pluginHost == null) pluginHost = new PluginKit.Host() {
            public Context context() { return getContext(); }

            public void tap(RectF r, Runnable run) {
                Hit h = new Hit(new RectF(r));
                h.tap = run;
                hits.add(h);
            }

            public void drag(RectF r, PluginKit.Drag d) {
                Hit h = new Hit(new RectF(r));
                h.drag = d;
                hits.add(h);
            }

            public boolean perform(String service, Map<String, String> data, Map<String, String> templates) {
                boolean ok = app.screen().perform(service, data, templates);
                if (!ok) noActions();
                invalidate();
                return ok;
            }

            public int ask(String plugin, Map<String, Object> request) {
                return app.screen().tessera.askForPlugin(plugin, request);
            }

            public void openCard(String plugin, String card, String entity, int tile) {
                pluginCard = new String[]{plugin, card, entity == null ? "" : entity, String.valueOf(tile)};
                invalidate();
            }

            public void closeCard() {
                pluginCard = null;
                invalidate();
            }

            public void redraw(long ms) {
                ui.postDelayed(new Runnable() {
                    public void run() { invalidate(); }
                }, Math.max(16, ms));
            }

            public app.tilescreen.core.ScreenSettings settings() { return app.screen().screenSettings; }

            public boolean clock24() { return layout != null && layout.clock24; }

            public void say(String words) { TileView.this.say(words); }
        };
        return pluginHost;
    }

    /** A finger landed on something that takes it: every plugin hears it (Tap sound ticks). */
    private void pressed() {
        for (PluginKit.Plugin p : PluginKit.all()) p.onPress(pluginHost());
    }

    /** A dragged value passed a step: every 5 % of its range, or each value of a range of 20 or fewer. */
    private void stepped(double v, double min, double max) {
        if (max <= min) return;
        int steps = max - min <= 20 ? (int) Math.round(max - min) : 20;
        int at = (int) Math.round((v - min) / (max - min) * steps);
        if (at == lastStep) return;
        boolean first = lastStep == Integer.MIN_VALUE;
        lastStep = at;
        if (first) return;  // the first value after a landing only marks the start
        for (PluginKit.Plugin p : PluginKit.all()) p.onStep(at == 0 || at == steps, pluginHost());
    }

    private PluginKit.Ctx pluginCtx(Canvas c, int index, Tessera.Tile t, RectF box, float fs, int fg, int dim, int accent, boolean pastel) {
        PluginKit.Ctx k = new PluginKit.Ctx();
        k.c = c;
        k.box = box;
        k.tile = t;
        k.index = index;
        Object pe = t == null ? null : t.options.get("pe");
        k.entity = pe instanceof String ? (String) pe : "";
        k.fs = fs;
        k.u = u;
        k.mm = mm();
        k.ink = fg;
        k.dim = dim;
        k.accent = accent;
        k.card = Style.CARD;
        k.raised = pastel ? 0x22000000 : Style.RAISED;
        k.pastel = pastel;
        k.text = text;
        k.glyphs = glyphs;
        k.fill = fill;
        k.line = line;
        k.regular = regular;
        k.medium = medium;
        k.light = light;
        k.host = pluginHost();
        return k;
    }

    /** A plugin tile: the plugin draws in the card's room; one this app has not ported is its icon, name and "Plugin missing". */
    private void drawPluginTile(Canvas c, final int index, final Tessera.Tile t, RectF r, float fs, float pad, int fg, int dim,
                                int accent, int pastel, boolean card, Hit hit) {
        String[] pk = PluginKit.key(t.entity);
        final PluginKit.Plugin p = pk == null ? null : PluginKit.of(pk[0]);
        RectF box = new RectF(r.left + pad, r.top + pad * 0.8f, r.right - pad, r.bottom - pad * 0.8f);
        final PluginKit.Ctx k = pluginCtx(c, index, t, box, fs, fg, dim, accent, pastel != 0);
        final String tileId = pk == null ? "" : pk[1];
        hit.hold = null;
        if (p != null && !"none".equals(t.tap)) {
            hit.tap = new Runnable() {
                public void run() { p.tapTile(tileId, k); }
            };
        } else {
            hit.tap = null;
        }
        if (p != null && p.drawTile(tileId, k)) return;
        float ic = Math.min(fs * 2.7f, r.height() * 0.42f);
        fill.setColor(pastel != 0 ? 0x22000000 : Style.RAISED);
        c.drawCircle(r.left + pad + ic / 2, r.top + pad + ic / 2, ic / 2, fill);
        glyphs.setColor(dim);
        glyphs.setTextSize(ic * 0.56f);
        String g = Icons.glyph(t.icon);
        c.drawText(g != null ? g : Icons.sure(getContext(), "puzzle-outline"), r.left + pad + ic / 2, r.top + pad + ic * 0.7f, glyphs);
        text.setTypeface(medium);
        text.setTextSize(fs);
        text.setColor(fg);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(fit(t.name, r.width() - pad * 2), r.left + pad, r.bottom - pad - fs * 1.3f, text);
        text.setTypeface(regular);
        text.setTextSize(fs * 0.85f);
        text.setColor(dim);
        c.drawText("Plugin missing", r.left + pad, r.bottom - pad, text);
    }

    /** A plugin's card over the page, as every card: its title and the plugin's room under it; a tap outside closes it. */
    private void drawPluginCard(Canvas c, int w, int h) {
        final String[] pc = pluginCard;
        PluginKit.Plugin p = PluginKit.of(pc[0]);
        if (p == null) {
            pluginCard = null;
            return;
        }
        fill.setColor(0xB0000000);
        c.drawRect(0, 0, w, h, fill);
        Hit outside = new Hit(new RectF(0, 0, w, h));
        outside.tap = new Runnable() {
            public void run() { pluginCard = null; }
        };
        hits.add(outside);
        float cw = Math.min(w * 0.86f, 120 * u), chh = h * 0.82f;
        RectF r = new RectF((w - cw) / 2, (h - chh) / 2, (w + cw) / 2, (h + chh) / 2);
        fill.setColor(Style.CARD);
        c.drawRoundRect(r, 3 * u, 3 * u, fill);
        hits.add(new Hit(new RectF(r)));
        float pad = 4 * u, fs = 4.2f * u;
        int index = -1;
        try { index = Integer.parseInt(pc[3]); } catch (NumberFormatException ignored) {}
        Tessera.Tile t = layout != null && index >= 0 && index < layout.tiles.size() ? layout.tiles.get(index) : null;
        // Its title: the card's own words, else the tile's name; the close key at the right.
        String title = p.cardTitle(pc[1]);
        if (title.isEmpty() && t != null) title = t.name;
        text.setTypeface(medium);
        text.setTextSize(fs * 1.2f);
        text.setColor(Style.TEXT);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(fit(title, cw - pad * 2 - 10 * u), r.left + pad, r.top + pad + fs, text);
        glyphs.setColor(Style.DIM);
        glyphs.setTextSize(6 * u);
        c.drawText(Icons.sure(getContext(), "close"), r.right - pad - 2 * u, r.top + pad + fs * 1.05f, glyphs);
        Hit close = new Hit(new RectF(r.right - pad - 8 * u, r.top, r.right, r.top + pad + fs * 2));
        close.tap = new Runnable() {
            public void run() { pluginCard = null; }
        };
        hits.add(close);
        RectF body = new RectF(r.left + pad, r.top + pad + fs * 2.2f, r.right - pad, r.bottom - pad);
        PluginKit.Ctx k = pluginCtx(c, index, t, body, fs, Style.TEXT, Style.DIM, Style.BLUE, false);
        if (!pc[2].isEmpty()) k.entity = pc[2];
        if (t == null || !t.entity.startsWith("plugin:")) {
            // Opened by a tap action on a tile of Home Assistant's own: the plugin reads that tile's entity.
            k.tile = t;
        }
        p.drawCard(pc[1], k);
    }

    // ----- a light's colour rows and effects page (light_controls.h, effects_page.h) -----

    /** "" for the card itself, "effects" for the effects page, "pick:<entity>" for a picker of its names. */
    private String cardView = "";
    private int cardViewTile = -1, pickPage;
    /** A name just chosen in a picker, shown until Home Assistant says so too (a wish, docs/OPTIMISTIC.md). */
    private final Map<String, Wish> chosen = new HashMap<String, Wish>();
    /** LightEntityFeature.EFFECT. */
    private static final int EFFECT = 4;
    private static final int WARM = 0xFFFF9C32, COOL = 0xFFC6E6FF;

    private static boolean hasMode(Tessera.Tile t, String mode) {
        List<Object> modes = t.list("supported_color_modes");
        if (modes == null) return false;
        for (Object m : modes) if (m instanceof String && ((String) m).contains(mode)) return true;
        return false;
    }

    private static boolean colourLight(Tessera.Tile t) { return hasMode(t, "hs") || hasMode(t, "rgb") || hasMode(t, "xy"); }

    private static boolean whiteLight(Tessera.Tile t) { return hasMode(t, "color_temp"); }

    private static boolean kelvinReady(Tessera.Tile t) {
        Double lo = t.number("min_color_temp_kelvin"), hi = t.number("max_color_temp_kelvin");
        return lo != null && hi != null && lo >= 1000 && hi <= 15000 && hi > lo;
    }

    private static boolean effectsOffered(Tessera.Tile t) {
        Double f = t.number("supported_features");
        boolean effects = f != null && (f.intValue() & EFFECT) != 0;
        return effects || Json.arr(t.extra.get("rows")) != null || Json.arr(t.extra.get("nums")) != null;
    }

    private static int hsv(float hue) {
        float h = ((hue % 360) + 360) % 360 / 60f;
        int i = (int) Math.floor(h);
        float f = h - i, q = 1 - f;
        float r, g, b;
        switch (i) {
            case 0: r = 1; g = f; b = 0; break;
            case 1: r = q; g = 1; b = 0; break;
            case 2: r = 0; g = 1; b = f; break;
            case 3: r = 0; g = q; b = 1; break;
            case 4: r = f; g = 0; b = 1; break;
            default: r = 1; g = 0; b = q; break;
        }
        return 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** A colour or white-shade row: its name and value over a gradient pill with a round knob in the chosen colour. */
    private void drawColourRow(Canvas c, final Tessera.Tile t, final boolean hue, RectF band, float fs) {
        final double lo = hue ? 0 : orElse(t.number("min_color_temp_kelvin"), 2000);
        final double hi = hue ? 360 : orElse(t.number("max_color_temp_kelvin"), 6500);
        Slider s = new Slider();
        s.min = lo;
        s.max = hi;
        s.step = hue ? 1 : 50;
        List<Object> hs = t.list("hs_color");
        Double k = t.number("color_temp_kelvin");
        s.value = hue ? (hs != null && !hs.isEmpty() && hs.get(0) instanceof Double ? (Double) hs.get(0) : 0)
                : (k != null ? k : (lo + hi) / 2);
        s.key = t.entity + (hue ? ":hue" : ":kelvin");
        Held h = held.get(s.key);
        if (h != null && SystemClock.uptimeMillis() < h.until) s.value = h.value;
        s.value = Math.max(lo, Math.min(hi, s.value));
        s.set = new Setter() {
            public void set(double v) {
                if (hue) {
                    Map<String, String> data = new LinkedHashMap<String, String>();
                    data.put("entity_id", t.entity);
                    Map<String, String> templates = new LinkedHashMap<String, String>();
                    templates.put("hs_color", "[" + Math.round(v) + ", 100]");
                    if (!app.screen().perform("light.turn_on", data, templates)) noActions();
                } else {
                    call("light.turn_on", t, "color_temp_kelvin", Long.toString(Math.round(v)));
                }
            }
        };
        // The words: the row's name at the left, its value at the right.
        float labelH = fs * 1.25f;
        text.setTypeface(regular);
        text.setTextSize(fs * 0.9f);
        text.setColor(Style.DIM);
        c.drawText(hue ? "Color" : "White shade", band.left, band.top + fs * 0.9f, text);
        text.setTextAlign(Paint.Align.RIGHT);
        boolean waiting = !hue && !kelvinReady(t);
        c.drawText(waiting ? "Waiting for the light…" : hue ? Math.round(s.value) + "°" : Math.round(s.value) + " K",
                band.right, band.top + fs * 0.9f, text);
        text.setTextAlign(Paint.Align.LEFT);
        RectF tr = new RectF(band.left, band.top + labelH, band.right, band.bottom);
        float rad = tr.height() / 2;
        // The track: round ends and a gradient between them, drawn as thin strips.
        int steps = 96;
        float span = tr.width() - tr.height();
        fill.setColor(hue ? hsv(0) : WARM);
        c.drawCircle(tr.left + rad, tr.centerY(), rad, fill);
        fill.setColor(hue ? hsv(360) : COOL);
        c.drawCircle(tr.right - rad, tr.centerY(), rad, fill);
        for (int i = 0; i < steps; i++) {
            float f = (i + 0.5f) / steps;
            fill.setColor(hue ? hsv(f * 360) : mix(WARM, COOL, f));
            float x0 = tr.left + rad + span * i / steps, x1 = tr.left + rad + span * (i + 1) / steps + 0.6f;
            c.drawRect(x0, tr.top, x1, tr.bottom, fill);
        }
        // The knob travels between the centres of the two round ends.
        float frac = (float) ((s.value - lo) / (hi - lo));
        float kx = tr.left + rad + span * frac;
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(kx, tr.centerY(), rad + 2, fill);
        fill.setColor(hue ? hsv((float) s.value) : mix(WARM, COOL, frac));
        c.drawCircle(kx, tr.centerY(), rad - Math.max(3, rad * 0.25f), fill);
        if (waiting) return;
        // Dragged between the round ends' centres, as the knob moves.
        Slider drag = s;
        drag.track = new RectF(tr.left + rad, tr.top, tr.right - rad, tr.bottom);
        Hit hit = new Hit(new RectF(tr.left, tr.top - fs * 0.4f, tr.right, tr.bottom + fs * 0.3f));
        hit.slider = drag;
        hits.add(hit);
    }

    private boolean callEntity(String service, String entity, String key, String value) {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity_id", entity);
        data.put(key, value);
        boolean ok = app.screen().perform(service, data, null);
        if (!ok) noActions();
        invalidate();
        return ok;
    }

    /** What a row shows: the name Home Assistant reports, the one just chosen, or a dash. */
    private String rowValue(String entity, String current) {
        Wish w = chosen.get(entity);
        if (w != null) {
            if (SystemClock.uptimeMillis() < w.until && !w.state.equals(current)) return w.state;
            chosen.remove(entity);
        }
        return current == null || current.isEmpty() || current.equals("unknown") || current.equals("unavailable")
                || current.equals("None") ? "—" : current;
    }

    private void drawEffects(Canvas c, final Tessera.Tile t, RectF body, float fs, int accent) {
        float rowH = fs * 2.2f, gap = 1.4f * u, y = body.top;
        // The way back: a picker to its page, the page to the card.
        RectF backKey = new RectF(body.left, y, body.left + 26 * u, y + rowH * 0.8f);
        drawButton(c, backKey, "‹  " + (cardView.startsWith("pick:") ? "Effects" : t.name), Style.RAISED, Style.TEXT);
        Hit back = new Hit(backKey);
        back.tap = new Runnable() {
            public void run() {
                cardView = cardView.startsWith("pick:") ? "effects" : "";
                pickPage = 0;
            }
        };
        hits.add(back);
        y += rowH * 0.8f + gap;
        if (cardView.startsWith("pick:")) {
            drawPicker(c, t, cardView.substring(5), new RectF(body.left, y, body.right, body.bottom), fs, accent);
            return;
        }
        // The rows: the light's effect, then the selects of its device (palette, preset, playlist).
        List<String[]> rows = new ArrayList<String[]>(); // {name, value, entity, icon}
        Double f = t.number("supported_features");
        if (f != null && (f.intValue() & EFFECT) != 0) rows.add(new String[]{"Effect", rowValue(t.entity, t.text("effect")), t.entity, null});
        List<Object> extraRows = Json.arr(t.extra.get("rows"));
        if (extraRows != null) {
            for (Object o : extraRows) {
                Map<String, Object> m = Json.obj(o);
                if (m == null || Json.str(m, "e") == null) continue;
                String e = Json.str(m, "e");
                rows.add(new String[]{nz(Json.str(m, "n")), rowValue(e, Json.str(m, "s")), e, Json.str(m, "i")});
            }
        }
        for (final String[] row : rows) {
            RectF rr = new RectF(body.left, y, body.right, y + rowH);
            fill.setColor(Style.RAISED);
            c.drawRoundRect(rr, 2 * u, 2 * u, fill);
            String icon = row[3] != null ? Icons.glyph(row[3]) : null;
            if (icon == null) icon = Icons.sure(getContext(), row[2].equals(t.entity) ? "creation" : "palette");
            glyphs.setColor(Style.DIM);
            glyphs.setTextSize(fs * 1.1f);
            c.drawText(icon, rr.left + fs * 1.1f, rr.centerY() + fs * 0.4f, glyphs);
            text.setTypeface(medium);
            text.setTextSize(fs);
            text.setColor(Style.TEXT);
            c.drawText(fit(row[0], rr.width() * 0.4f), rr.left + fs * 2.2f, rr.centerY() + fs * 0.35f, text);
            text.setTypeface(regular);
            text.setColor(Style.DIM);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(fit(row[1], rr.width() * 0.45f) + "  ›", rr.right - fs * 0.8f, rr.centerY() + fs * 0.35f, text);
            text.setTextAlign(Paint.Align.LEFT);
            Hit hit = new Hit(rr);
            hit.tap = new Runnable() {
                public void run() {
                    cardView = "pick:" + row[2];
                    pickPage = -1;
                    app.screen().tessera.askOptions(row[2]);
                }
            };
            hits.add(hit);
            y += rowH + gap;
        }
        // The numbers of its device (speed, intensity) as sliders, side by side under the rows.
        List<Object> nums = Json.arr(t.extra.get("nums"));
        if (nums != null && !nums.isEmpty()) {
            int n = Math.min(2, nums.size());
            float sliderH = fs * 1.6f, w = (body.width() - (n - 1) * 2 * u) / n;
            float top = Math.min(y, body.bottom - fs * 1.25f - sliderH);
            for (int i = 0; i < n; i++) {
                final Map<String, Object> m = Json.obj(nums.get(i));
                if (m == null || Json.str(m, "e") == null) continue;
                final String e = Json.str(m, "e");
                float x = body.left + i * (w + 2 * u);
                Slider s = new Slider();
                s.min = orElse(Json.dbl(m, "lo"), 0);
                s.max = orElse(Json.dbl(m, "hi"), 100);
                s.step = orElse(Json.dbl(m, "st"), 1);
                s.value = orElse(Json.dbl(m, "v"), s.min);
                s.key = e + ":number";
                Held h = held.get(s.key);
                if (h != null && SystemClock.uptimeMillis() < h.until) s.value = h.value;
                s.set = new Setter() {
                    public void set(double v) { callEntity("number.set_value", e, "value", Style.number(v)); }
                };
                text.setTypeface(regular);
                text.setTextSize(fs * 0.9f);
                text.setColor(Style.DIM);
                c.drawText(fit(nz(Json.str(m, "n")), w), x, top + fs * 0.9f, text);
                s.track = new RectF(x, top + fs * 1.25f, x + w, top + fs * 1.25f + sliderH);
                drawSlider(c, s, accent, false, fs);
                Hit hit = new Hit(new RectF(s.track.left, s.track.top - fs * 0.4f, s.track.right, s.track.bottom + fs * 0.3f));
                hit.slider = s;
                hits.add(hit);
            }
        }
    }

    /** A picker: every name Home Assistant lists for the light's effects or a select, a page at a time; a tap chooses. */
    private void drawPicker(Canvas c, final Tessera.Tile t, final String entity, RectF area, float fs, int accent) {
        List<String> names = app.screen().tessera.options(entity);
        if (names == null) {
            text.setTypeface(regular);
            text.setTextSize(fs);
            text.setColor(Style.DIM);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(app.screen().device.actionSubscribers() == 0 ? "The names need Home Assistant actions allowed"
                    : "Loading the names…", area.centerX(), area.centerY(), text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        String current = entity.equals(t.entity) ? t.text("effect") : null;
        if (current == null) {
            List<Object> extraRows = Json.arr(t.extra.get("rows"));
            if (extraRows != null) for (Object o : extraRows) {
                Map<String, Object> m = Json.obj(o);
                if (m != null && entity.equals(Json.str(m, "e"))) current = Json.str(m, "s");
            }
        }
        current = rowValue(entity, current);
        float rowH = fs * 2.2f, gap = 0.8f * u, keys = fs * 2.4f;
        int per = Math.max(1, (int) ((area.height() - keys - gap) / (rowH + gap)));
        int cols = area.width() > 90 * u ? 2 : 1;
        per *= cols;
        int pages = Math.max(1, (names.size() + per - 1) / per);
        // The first page shown is the one with the current name on it.
        if (pickPage < 0) pickPage = Math.max(0, names.indexOf(current)) / per;
        pickPage = Math.max(0, Math.min(pages - 1, pickPage));
        float colW = (area.width() - (cols - 1) * gap) / cols;
        for (int i = 0; i < per && pickPage * per + i < names.size(); i++) {
            final String name = names.get(pickPage * per + i);
            int col = i / (per / cols), rowIx = i % (per / cols);
            RectF rr = new RectF(area.left + col * (colW + gap), area.top + rowIx * (rowH + gap),
                    area.left + col * (colW + gap) + colW, area.top + rowIx * (rowH + gap) + rowH);
            boolean on = name.equals(current);
            fill.setColor(on ? withAlpha(accent, 90) : Style.RAISED);
            c.drawRoundRect(rr, 2 * u, 2 * u, fill);
            text.setTypeface(on ? medium : regular);
            text.setTextSize(fs * 0.95f);
            text.setColor(Style.TEXT);
            c.drawText(fit(name, rr.width() - fs * 1.6f), rr.left + fs * 0.8f, rr.centerY() + fs * 0.33f, text);
            Hit hit = new Hit(rr);
            hit.tap = new Runnable() {
                public void run() {
                    boolean sent = entity.equals(t.entity) ? call("light.turn_on", t, "effect", name)
                            : callEntity("select.select_option", entity, "option", name);
                    if (sent) {
                        Wish w = new Wish();
                        w.state = name;
                        w.until = SystemClock.uptimeMillis() + 8000;
                        chosen.put(entity, w);
                    }
                    cardView = "effects";
                }
            };
            hits.add(hit);
        }
        if (pages > 1) {
            float ky = area.bottom - keys;
            RectF prev = new RectF(area.left, ky, area.left + 22 * u, area.bottom);
            RectF next = new RectF(area.right - 22 * u, ky, area.right, area.bottom);
            drawButton(c, prev, "‹", pickPage > 0 ? Style.RAISED : Style.CARD, pickPage > 0 ? Style.TEXT : Style.FAINT);
            drawButton(c, next, "›", pickPage < pages - 1 ? Style.RAISED : Style.CARD, pickPage < pages - 1 ? Style.TEXT : Style.FAINT);
            text.setTypeface(regular);
            text.setTextSize(fs * 0.9f);
            text.setColor(Style.DIM);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText((pickPage + 1) + " / " + pages, area.centerX(), ky + keys / 2 + fs * 0.3f, text);
            text.setTextAlign(Paint.Align.LEFT);
            Hit hp = new Hit(prev);
            hp.tap = new Runnable() {
                public void run() { pickPage--; }
            };
            hits.add(hp);
            Hit hn = new Hit(next);
            hn.tap = new Runnable() {
                public void run() { pickPage++; }
            };
            hits.add(hn);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void drawCardBody(Canvas c, final Tessera.Tile t, RectF body, float fs, int accent) {
        String d = t.domain(), s = t.state;
        float rowH = fs * 2.9f;
        float y = body.top;
        // A light's presets on glass much wider than high: a column at the right of its rows, one key under the other.
        boolean presetColumn = false;
        if (d.equals("light") && !LightPresets.of(t).isEmpty() && body.width() > body.height() * 1.25f) {
            float key = fs * 2.6f;
            float colW = key + 2 * u;
            text.setTypeface(regular);
            text.setTextSize(fs * 0.85f);
            text.setColor(Style.DIM);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Presets", body.right - colW / 2, body.top + fs * 0.9f, text);
            text.setTextAlign(Paint.Align.LEFT);
            drawPresets(c, t, new RectF(body.right - colW / 2 - key / 2, body.top + fs * 1.6f, body.right - colW / 2 + key / 2, body.bottom), false);
            body.right -= colW + 4 * u;
            presetColumn = true;
        }
        List<String[]> buttons = new ArrayList<String[]>();
        if (d.equals("light") || d.equals("switch") || d.equals("input_boolean") || d.equals("fan")
                || d.equals("automation") || d.equals("humidifier") || d.equals("siren")) {
            buttons.add(new String[]{Style.active(t) ? "Turn off" : "Turn on", d + (Style.active(t) ? ".turn_off" : ".turn_on")});
        }
        if (d.equals("automation")) buttons.add(new String[]{"Run now", "automation.trigger"});
        if (d.equals("cover")) {
            buttons.add(new String[]{"Open", "cover.open_cover"});
            buttons.add(new String[]{"Stop", "cover.stop_cover"});
            buttons.add(new String[]{"Close", "cover.close_cover"});
        }
        if (d.equals("lock")) {
            buttons.add(new String[]{"Lock", "lock.lock"});
            buttons.add(new String[]{"Unlock", "lock.unlock"});
        }
        if (d.equals("media_player")) {
            // Only the keys the player has (MediaPlayerEntityFeature), as Home Assistant's dialog: a TV may have none.
            int f = t.number("supported_features") == null ? 0 : t.number("supported_features").intValue();
            boolean off = "off".equals(s) || "standby".equals(s);
            if (f == 0 || (f & (off ? 128 : 256)) != 0)
                buttons.add(new String[]{off ? "Turn on" : "Turn off", off ? "media_player.turn_on" : "media_player.turn_off"});
            if (f == 0 || (f & 16) != 0) buttons.add(new String[]{"Previous", "media_player.media_previous_track"});
            if (f == 0 || (f & (1 | 16384)) != 0) buttons.add(new String[]{"playing".equals(s) ? "Pause" : "Play", "media_player.media_play_pause"});
            if (f == 0 || (f & 32) != 0) buttons.add(new String[]{"Next", "media_player.media_next_track"});
        }
        if (d.equals("alarm_control_panel")) {
            buttons.add(new String[]{"Disarm", "alarm_control_panel.alarm_disarm"});
            buttons.add(new String[]{"Arm home", "alarm_control_panel.alarm_arm_home"});
            buttons.add(new String[]{"Arm away", "alarm_control_panel.alarm_arm_away"});
        }
        if (d.equals("timer")) {
            buttons.add(new String[]{"Start", "timer.start"});
            buttons.add(new String[]{"Pause", "timer.pause"});
            buttons.add(new String[]{"Cancel", "timer.cancel"});
        }
        if (d.equals("scene") || d.equals("script")) buttons.add(new String[]{"Run", d + ".turn_on"});
        if (d.equals("button") || d.equals("input_button")) buttons.add(new String[]{"Press", d + ".press"});
        if (d.equals("remote")) buttons.add(new String[]{Style.active(t) ? "Turn off" : "Turn on", "remote." + (Style.active(t) ? "turn_off" : "turn_on")});

        if (!buttons.isEmpty()) {
            float bw = Math.min((body.width() - (buttons.size() - 1) * 2 * u) / buttons.size(), 34 * u);
            float x = body.left;
            for (final String[] b : buttons) {
                RectF br = new RectF(x, y, x + bw, y + rowH);
                drawButton(c, br, b[0], Style.RAISED, Style.TEXT);
                Hit hit = new Hit(br);
                hit.tap = new Runnable() {
                    public void run() { call(b[1], t); }
                };
                hits.add(hit);
                x += bw + 2 * u;
            }
            y += rowH + 3 * u;
        }

        // A slider where the entity has a level.
        String slider = null;
        if (d.equals("light") && dimmable(t)) slider = "slider";
        if (d.equals("fan") || d.equals("cover") && t.number("current_position") != null) slider = "slider";
        if (d.equals("number") || d.equals("input_number") || d.equals("media_player")) slider = "slider";
        if (slider != null) {
            drawControl(c, t, "slider", new RectF(body.left, y, body.right, y + rowH), fs, accent, false);
            y += rowH + 3 * u;
        }
        // A light with a colour or a white shade: their rows under its brightness (light_controls.h).
        if (d.equals("light")) {
            float colourH = fs * 2.9f;
            if (colourLight(t) && y + colourH <= body.bottom) {
                drawColourRow(c, t, true, new RectF(body.left, y, body.right, y + colourH), fs);
                y += colourH + 3 * u;
            }
            if (whiteLight(t) && y + colourH <= body.bottom) {
                drawColourRow(c, t, false, new RectF(body.left, y, body.right, y + colourH), fs);
                y += colourH + 3 * u;
            }
            // Its presets, a row under the colour, the white shade and the brightness.
            float presetH = fs * 2.6f;
            if (!presetColumn && !LightPresets.of(t).isEmpty() && y + presetH + fs * 1.4f <= body.bottom) {
                text.setTypeface(regular);
                text.setTextSize(fs * 0.85f);
                text.setColor(Style.DIM);
                c.drawText("Presets", body.left, y + fs * 0.9f, text);
                y += fs * 1.4f;
                drawPresets(c, t, new RectF(body.left, y, body.right, y + presetH), false);
                y += presetH + 3 * u;
            }
            // The effects page as a row of its own too, besides the sparkles key in the corner.
            float keyH = fs * 2.4f;
            if (effectsOffered(t) && y + keyH <= body.bottom) {
                RectF fx = new RectF(body.left, y, body.right, y + keyH);
                fill.setColor(Style.RAISED);
                c.drawRoundRect(fx, 2 * u, 2 * u, fill);
                glyphs.setColor(Style.DIM);
                glyphs.setTextSize(fs * 1.1f);
                c.drawText(Icons.sure(getContext(), "creation"), fx.left + fs * 1.1f, fx.centerY() + fs * 0.4f, glyphs);
                text.setTypeface(medium);
                text.setTextSize(fs);
                text.setColor(Style.TEXT);
                c.drawText("Effects", fx.left + fs * 2.2f, fx.centerY() + fs * 0.35f, text);
                text.setTypeface(regular);
                text.setColor(Style.DIM);
                text.setTextAlign(Paint.Align.RIGHT);
                String now = t.text("effect");
                c.drawText(fit(now == null ? "" : now, fx.width() * 0.5f) + "  ›", fx.right - fs * 0.8f, fx.centerY() + fs * 0.35f, text);
                text.setTextAlign(Paint.Align.LEFT);
                Hit hit = new Hit(fx);
                hit.tap = new Runnable() {
                    public void run() { cardView = "effects"; }
                };
                hits.add(hit);
                y += keyH + 3 * u;
            }
        }

        if (d.equals("climate")) {
            if (t.number("temperature") != null) {
                drawControl(c, t, "stepper", new RectF(body.left, y, body.right, y + rowH), fs * 1.1f, accent, false);
                text.setColor(Style.DIM);
                text.setTypeface(regular);
                text.setTextSize(fs * 0.9f);
                c.drawText("Target", body.left, y + rowH * 0.62f, text);
                y += rowH + 3 * u;
            } else {
                Double lo = t.number("target_temp_low"), hi = t.number("target_temp_high");
                if (lo != null && hi != null) {
                    text.setColor(Style.TEXT);
                    text.setTextSize(fs);
                    c.drawText("Range " + Style.number(lo) + Style.degree(t) + " – " + Style.number(hi) + Style.degree(t),
                            body.left, y + rowH * 0.62f, text);
                    y += rowH;
                }
            }
            List<Object> modes = t.list("hvac_modes");
            if (modes != null) y = chips(c, t, modes, s, "climate.set_hvac_mode", "hvac_mode", body, y, fs);
        }
        if (d.equals("select") || d.equals("input_select")) {
            List<Object> options = t.list("options");
            if (options != null) y = chips(c, t, options, s, d + ".select_option", "option", body, y, fs);
        }
        if (d.equals("media_player")) {
            List<Object> sources = Json.arr(t.extra.get("sl"));
            if (sources != null) {
                text.setTypeface(regular);
                text.setTextSize(fs * 0.9f);
                text.setColor(Style.DIM);
                c.drawText("Input", body.left, y + fs, text);
                y = chips(c, t, sources, Json.str(t.extra, "so"), "media_player.select_source", "source", body, y + fs * 1.6f, fs);
            }
        }
        // A player whose library opens (x.lb): the way in, as the round key at the top of Tessera's card.
        if (d.equals("media_player") && Json.integer(t.extra, "lb") != null && y + fs * 2.4f <= body.bottom) {
            y = navRow(c, body, y, fs, "library-shelves", "Library", "", new Runnable() {
                public void run() {
                    cardView = "lib";
                    libraryPath.clear();
                    libraryPage = 0;
                    app.screen().tessera.askFolder(t.entity, 0);
                }
            });
        }
        if (d.equals("fan")) {
            List<Object> modes = t.list("preset_modes");
            if (modes != null) y = chips(c, t, modes, t.text("preset_mode"), "fan.set_preset_mode", "preset_mode", body, y, fs);
        }

        // Sensors, binary sensors, switches, people and numbers: their history (history_card).
        if (HistoryCard.shows(d) && body.bottom - y > fs * 10) {
            drawHistory(c, t, new RectF(body.left, y, body.right, body.bottom), fs, accent);
            return;
        }
        if (d.equals("camera") || d.equals("image")) {
            final Tessera tessera = app.screen().tessera;
            final String entity = t.entity;
            final RectF box = new RectF(body.left, y, body.right, body.bottom);
            // A camera opened: live where it can (a camera full screen goes before the tiles it covers), else a still of
            // every four seconds; an image entity a still. A picture at its own proportions within the room.
            final int bw = Math.max(64, Math.round(box.width())), bh = Math.max(64, Math.round(box.height()));
            final boolean stream = d.equals("camera");
            pictures.want("full", entity + "|" + bw + "x" + bh);
            pictures.pace("full", 8);
            Bitmap b = pictures.keepFresh(tessera, "full", null, 4000, new Runnable() {
                public void run() {
                    Map<String, Object> q = question("view", -1, entity, "camera:" + entity, bw, bh, 0, 0, "shrink", "000000");
                    q.put("pace", 4000.0);
                    if (stream) q.put("live", numbers(bw, bh));
                    tessera.askQuestion("full", q);
                }
            });
            if (b != null) {
                float scale = Math.min(box.width() / b.getWidth(), box.height() / b.getHeight());
                float pw = b.getWidth() * scale, ph = b.getHeight() * scale;
                RectF dst = new RectF(box.centerX() - pw / 2, box.top, box.centerX() + pw / 2, box.top + ph);
                c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), dst, null);
            } else {
                text.setTypeface(regular);
                text.setTextSize(fs);
                text.setColor(Style.DIM);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(app.screen().device.actionSubscribers() == 0 ? "Pictures need Home Assistant actions allowed"
                        : "Loading the picture…", box.centerX(), box.centerY(), text);
                text.setTextAlign(Paint.Align.LEFT);
            }
            ui.postDelayed(new Runnable() {
                public void run() { invalidate(); }
            }, 1000);
            return;
        }
        if (d.equals("sensor") || d.equals("binary_sensor") || d.equals("weather") || d.equals("person")
                || d.equals("sun") || d.equals("camera") || d.equals("image") || t.entity.startsWith("screen.")) {
            text.setTypeface(light);
            text.setColor(Style.TEXT);
            text.setTextSize(fs * 3);
            String big = Style.stateLine(t);
            if (d.equals("weather")) {
                Double temp = t.number("temperature");
                big = temp == null ? Style.words(s) : Style.number(temp) + Style.degree(t) + "  " + Style.words(s);
            }
            if (t.entity.startsWith("screen.")) big = "Not drawn on this screen yet";
            if (big.length() > 28) text.setTextSize(fs * 1.4f);
            c.drawText(fit(big, body.width()), body.left, y + fs * 3, text);
            y += fs * 4.2f;
            List<Double> samples = samples(t);
            if (samples.size() >= 2) {
                RectF g = new RectF(body.left, y, body.right, Math.min(body.bottom, y + body.height() * 0.45f));
                drawGraph(c, samples, g, accent);
                y = g.bottom + 2 * u;
            }
            if (d.equals("weather")) {
                StringBuilder b = new StringBuilder();
                Double hum = t.number("humidity"), wind = t.number("wind_speed");
                if (hum != null) b.append("Humidity ").append(Style.number(hum)).append("%   ");
                if (wind != null) b.append("Wind ").append(Style.number(wind)).append(" ").append(t.text("wind_speed_unit") == null ? "" : t.text("wind_speed_unit"));
                text.setTypeface(regular);
                text.setTextSize(fs);
                text.setColor(Style.DIM);
                c.drawText(b.toString(), body.left, y + fs, text);
            }
        }
    }

    // ----- a player's library and its favourites (media_library.h) -----

    private final List<Object[]> libraryPath = new ArrayList<Object[]>(); // {token, title} of the folders opened
    private int libraryPage;
    private final Map<Integer, Long> favoriteStarted = new HashMap<Integer, Long>();
    private static final long STARTING_MS = 12000;

    /** A row that leads somewhere: an icon, words, a value and a chevron; returns where the next thing goes. */
    private float navRow(Canvas c, RectF body, float y, float fs, String icon, String words, String value, Runnable tap) {
        float keyH = fs * 2.4f;
        RectF fx = new RectF(body.left, y, body.right, y + keyH);
        fill.setColor(Style.RAISED);
        c.drawRoundRect(fx, 2 * u, 2 * u, fill);
        glyphs.setColor(Style.DIM);
        glyphs.setTextSize(fs * 1.1f);
        c.drawText(Icons.sure(getContext(), icon), fx.left + fs * 1.1f, fx.centerY() + fs * 0.4f, glyphs);
        text.setTypeface(medium);
        text.setTextSize(fs);
        text.setColor(Style.TEXT);
        c.drawText(words, fx.left + fs * 2.2f, fx.centerY() + fs * 0.35f, text);
        text.setTypeface(regular);
        text.setColor(Style.DIM);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fit(value, fx.width() * 0.5f) + "  ›", fx.right - fs * 0.8f, fx.centerY() + fs * 0.35f, text);
        text.setTextAlign(Paint.Align.LEFT);
        Hit hit = new Hit(fx);
        hit.tap = tap;
        hits.add(hit);
        return y + keyH + 3 * u;
    }

    /** The line under a favourite's name (favorite_line): its kind and speaker, "Starting…", or "Playing". */
    private String favoriteLine(int index, Tessera.Tile t) {
        String kind = nz(Json.str(t.extra, "fk")), own = nz(Json.str(t.extra, "fo")), now = nz(Json.str(t.extra, "so"));
        String speaker = !own.isEmpty() ? own : now;
        boolean playing = Json.integer(t.extra, "fp") != null;
        Long started = favoriteStarted.get(index);
        if (started != null && (playing || SystemClock.uptimeMillis() - started >= STARTING_MS)) {
            favoriteStarted.remove(index);
            started = null;
        }
        if (started != null) return speaker.isEmpty() ? "Loading…" : "Starting on " + speaker;
        if (playing) return now.isEmpty() ? "Playing" : "Playing · " + now;
        if (kind.isEmpty()) return own;
        return own.isEmpty() ? kind : kind + " · " + own;
    }

    /** A favourite: its picture with its name over it, or the icon of its kind on the card; a tap plays or pauses it. */
    private void drawFavorite(Canvas c, int index, Tessera.Tile t, RectF r, float fs, float pad, boolean card, int pastel) {
        String line = favoriteLine(index, t);
        boolean playing = Json.integer(t.extra, "fp") != null && "playing".equals(t.state);
        if (favoriteStarted.containsKey(index)) {
            ui.postDelayed(new Runnable() {
                public void run() { invalidate(); }
            }, 1000);
        }
        if (pictured(t) && drawLive(c, index, r)) {
            text.setTypeface(medium);
            text.setTextSize(fs * 0.9f);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.LEFT);
            outlined(c, fit(t.name, r.width() - pad * 2), r.left + pad, r.bottom - fs * 1.95f);
            text.setTypeface(regular);
            text.setTextSize(fs * 0.8f);
            text.setColor(0xFFE6E8EC);
            outlined(c, fit(line, r.width() - pad * 2), r.left + pad, r.bottom - fs * 0.75f);
        } else {
            int fg = pastel != 0 ? Style.DARK_TEXT : Style.TEXT, dim = pastel != 0 ? Style.DARK_DIM : Style.DIM;
            float ic = Math.min(fs * 2.7f, r.height() * 0.42f);
            float icx = r.left + pad + ic / 2f, icy = r.top + pad + ic / 2f;
            fill.setColor(playing ? withAlpha(Style.accent(t), 60) : pastel != 0 ? 0x22000000 : Style.RAISED);
            c.drawCircle(icx, icy, ic / 2f, fill);
            String glyph = Icons.glyph(Json.str(t.extra, "fi"));
            glyphs.setColor(playing ? Style.accent(t) : dim);
            glyphs.setTextSize(ic * 0.56f);
            c.drawText(glyph != null ? glyph : Icons.forTile(getContext(), t), icx, icy + ic * 0.2f, glyphs);
            if (app.screen().screenSettings.iconToggles()) {
                // Icon toggles: the circle plays or pauses the favourite, the rest of the tile opens the player.
                float reach = Math.max(ic, 12 * u);
                Hit q = new Hit(new RectF(icx - reach / 2, icy - reach / 2, icx + reach / 2, icy + reach / 2));
                q.tap = tapFor(index, t, false);
                q.hold = tapFor(index, t, true);
                hits.add(q);
            }
            text.setTypeface(medium);
            text.setTextSize(fs);
            text.setColor(fg);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(fit(t.name, r.width() - pad * 2), r.left + pad, r.bottom - pad - fs * 1.3f, text);
            text.setTypeface(regular);
            text.setTextSize(fs * 0.85f);
            text.setColor(dim);
            c.drawText(fit(line, r.width() - pad * 2), r.left + pad, r.bottom - pad, text);
        }
        // A play or pause key at the top right says what a tap does.
        glyphs.setColor(pictured(t) ? 0xFFFFFFFF : Style.TEXT);
        glyphs.setTextSize(fs * 1.4f);
        c.drawText(Icons.sure(getContext(), playing ? "pause-circle" : "play-circle"), r.right - pad - fs * 0.6f, r.top + pad + fs * 1.1f, glyphs);
    }

    /** The library over the player's card: the folder's items as covers or cards, a page at a time. */
    private void drawLibrary(Canvas c, final Tessera.Tile t, RectF body, float fs, int accent) {
        final Tessera tessera = app.screen().tessera;
        Tessera.Folder f = tessera.library();
        float rowH = fs * 2.2f, gap = 1.4f * u, y = body.top;
        String backWords = libraryPath.isEmpty() ? t.name : f != null && !f.title.isEmpty() ? f.title : "Library";
        RectF backKey = new RectF(body.left, y, body.left + Math.min(body.width() * 0.7f, width(backWords, fs * 0.9f) + 8 * u), y + rowH * 0.8f);
        drawButton(c, backKey, "‹  " + fit(backWords, backKey.width() - 6 * u), Style.RAISED, Style.TEXT);
        Hit back = new Hit(backKey);
        back.tap = new Runnable() {
            public void run() {
                libraryPage = 0;
                if (libraryPath.isEmpty()) {
                    cardView = "";
                    return;
                }
                libraryPath.remove(libraryPath.size() - 1);
                long token = libraryPath.isEmpty() ? 0 : (Long) libraryPath.get(libraryPath.size() - 1)[0];
                tessera.askFolder(t.entity, token);
            }
        };
        hits.add(back);
        y += rowH * 0.8f + gap;
        RectF area = new RectF(body.left, y, body.right, body.bottom);
        if (f == null || !f.entity.equals(t.entity) || !f.complete) {
            text.setTypeface(regular);
            text.setTextSize(fs);
            text.setColor(Style.DIM);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(app.screen().device.actionSubscribers() == 0 ? "The library needs Home Assistant actions allowed"
                    : "Opening the library…", area.centerX(), area.centerY(), text);
            text.setTextAlign(Paint.Align.LEFT);
            ui.postDelayed(new Runnable() {
                public void run() { invalidate(); }
            }, 1000);
            return;
        }
        if (f.failed || f.items.isEmpty()) {
            text.setTypeface(regular);
            text.setTextSize(fs);
            text.setColor(Style.DIM);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(f.failed ? "The library did not open" : "Nothing in here", area.centerX(), area.centerY(), text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        boolean covers = false;
        for (Object[] it : f.items) if (((Integer) it[2] & Tessera.PICTURED) != 0) covers = true;
        // A grid: covers with the title under each, or cards with an icon and the name; the pager only when needed.
        float keys = fs * 2.4f;
        // Five covers a row on wide glass, three on narrow (cover_columns); four or two cards.
        boolean wideGlass = area.width() * 2 > area.height() * 3;
        int columns = covers ? (wideGlass ? 5 : 3) : (wideGlass ? 3 : 2);
        float cellW = (area.width() - (columns - 1) * gap) / columns;
        float titleH = fs * 1.4f;
        float cellH = covers ? 0 : fs * 2.6f;
        int rowsFit;
        float art = 0;
        boolean pager;
        int per;
        {
            float room = area.height();
            if (covers) {
                // Two rows of covers, each as large as the room leaves it.
                art = Math.min(cellW, (room - keys - 2 * gap) / 2 - titleH);
                cellH = art + titleH;
                rowsFit = 2;
            } else {
                rowsFit = Math.max(1, (int) ((room + gap) / (cellH + gap)));
            }
            per = rowsFit * columns;
            pager = f.items.size() > per;
            if (pager && !covers) {
                room -= keys + gap;
                rowsFit = Math.max(1, (int) ((room + gap) / (cellH + gap)));
                per = rowsFit * columns;
            }
        }
        int pages = Math.max(1, (f.items.size() + per - 1) / per);
        libraryPage = Math.max(0, Math.min(pages - 1, libraryPage));
        int first = libraryPage * per, last = Math.min(f.items.size(), first + per);
        // The covers of the page as one picture, in their frames (library_art_request).
        Bitmap atlasPic = null;
        Map<Integer, Rect> frames = new HashMap<Integer, Rect>();
        if (covers) {
            StringBuilder tokens = new StringBuilder(), atlas = new StringBuilder("[");
            for (int i = first; i < last; i++) {
                Object[] it = f.items.get(i);
                if (((Integer) it[2] & Tessera.PICTURED) == 0) continue;
                int n = i - first, col = n % columns, row = n / columns;
                int fx = Math.round(col * (cellW + gap) + (cellW - art) / 2), fy = Math.round(row * (cellH + gap));
                int side = Math.round(art), rad = Math.min(side / 2, Math.round(1.6f * u));
                if (frames.size() > 0) {
                    tokens.append(',');
                    atlas.append(',');
                }
                tokens.append(it[0]);
                atlas.append('[').append(fx).append(',').append(fy).append(',').append(side).append(',').append(side)
                        .append(',').append(rad).append(",0]");
                frames.put(i, new Rect(fx, fy, fx + side, fy + side));
            }
            atlas.append(']');
            if (!frames.isEmpty()) {
                final String items = tokens.toString(), frameText = atlas.toString(), entity = t.entity;
                pictures.want("lib", entity + "|" + f.token + "|" + items + "|" + frameText);
                atlasPic = pictures.keepFresh(tessera, "lib", entity, 3600000, new Runnable() {
                    public void run() {
                        Map<String, String> fl = new LinkedHashMap<String, String>();
                        fl.put("entity", entity);
                        fl.put("lib", items);
                        fl.put("atlas", frameText);
                        fl.put("bg", String.format(Locale.US, "%06X", Style.CARD & 0xFFFFFF));
                        tessera.askPicture("lib", fl);
                    }
                });
            }
        }
        for (int i = first; i < last; i++) {
            final Object[] it = f.items.get(i);
            final int flags = (Integer) it[2];
            int n = i - first, col = n % columns, row = n / columns;
            float cx = area.left + col * (cellW + gap), cy = area.top + row * (cellH + gap);
            RectF cell = new RectF(cx, cy, cx + cellW, cy + cellH);
            boolean playing = (flags & Tessera.PLAYING) != 0;
            String icon = Icons.glyph((String) it[3]);
            if (icon == null) icon = Icons.sure(getContext(), (flags & Tessera.CAN_EXPAND) != 0 ? "folder-music" : "music");
            if (covers) {
                RectF cover = new RectF(cx + (cellW - art) / 2, cy, cx + (cellW + art) / 2, cy + art);
                Rect fr = frames.get(i);
                if (atlasPic != null && fr != null && fr.right <= atlasPic.getWidth() && fr.bottom <= atlasPic.getHeight()) {
                    c.drawBitmap(atlasPic, fr, cover, null);
                } else {
                    fill.setColor(Style.RAISED);
                    c.drawRoundRect(cover, 1.6f * u, 1.6f * u, fill);
                    glyphs.setColor(Style.DIM);
                    glyphs.setTextSize(art * 0.35f);
                    c.drawText(icon, cover.centerX(), cover.centerY() + art * 0.13f, glyphs);
                }
                if (playing) {
                    line.setColor(accent);
                    line.setStrokeWidth(Math.max(3, 0.6f * u));
                    c.drawRoundRect(cover, 1.6f * u, 1.6f * u, line);
                }
                text.setTypeface(playing ? medium : regular);
                text.setTextSize(fs * 0.8f);
                text.setColor(Style.TEXT);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(fit((String) it[1], art), cover.centerX(), cover.bottom + fs * 1.05f, text);
                text.setTextAlign(Paint.Align.LEFT);
            } else {
                fill.setColor(playing ? withAlpha(accent, 90) : Style.RAISED);
                c.drawRoundRect(cell, 2 * u, 2 * u, fill);
                glyphs.setColor(Style.DIM);
                glyphs.setTextSize(fs * 1.2f);
                c.drawText(icon, cell.left + fs * 1.2f, cell.centerY() + fs * 0.45f, glyphs);
                text.setTypeface(medium);
                text.setTextSize(fs * 0.95f);
                text.setColor(Style.TEXT);
                c.drawText(fit((String) it[1], cell.width() - fs * 3.2f), cell.left + fs * 2.4f, cell.centerY() + fs * 0.33f, text);
            }
            Hit hit = new Hit(cell);
            // What plays, plays; a folder opens; a hold opens what also plays (an artist, a playlist).
            hit.tap = new Runnable() {
                public void run() {
                    if ((flags & Tessera.CAN_PLAY) != 0) {
                        if (needsSpeaker(t, "")) {
                            speakerAsk = new Object[]{t.entity, it[0], -1};
                            return;
                        }
                        tessera.play(t.entity, (Long) it[0], "");
                        say("Starting " + it[1]);
                    } else if ((flags & Tessera.CAN_EXPAND) != 0) {
                        openFolder(t, it);
                    }
                }
            };
            if ((flags & Tessera.CAN_EXPAND) != 0) {
                hit.hold = new Runnable() {
                    public void run() { openFolder(t, it); }
                };
            }
            hits.add(hit);
        }
        if (pager && pages > 1) {
            float ky = area.bottom - keys;
            RectF prev = new RectF(area.left, ky, area.left + 22 * u, area.bottom);
            RectF next = new RectF(area.right - 22 * u, ky, area.right, area.bottom);
            drawButton(c, prev, "‹", libraryPage > 0 ? Style.RAISED : Style.CARD, libraryPage > 0 ? Style.TEXT : Style.FAINT);
            drawButton(c, next, "›", libraryPage < pages - 1 ? Style.RAISED : Style.CARD, libraryPage < pages - 1 ? Style.TEXT : Style.FAINT);
            text.setTypeface(regular);
            text.setTextSize(fs * 0.9f);
            text.setColor(Style.DIM);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText((libraryPage + 1) + " / " + pages, area.centerX(), ky + keys / 2 + fs * 0.3f, text);
            text.setTextAlign(Paint.Align.LEFT);
            Hit hp = new Hit(prev);
            hp.tap = new Runnable() {
                public void run() { libraryPage--; }
            };
            hits.add(hp);
            Hit hn = new Hit(next);
            hn.tap = new Runnable() {
                public void run() { libraryPage++; }
            };
            hits.add(hn);
        }
    }

    /** {entity, library item or -1, favourite tile or -1}: what waits for a speaker to be chosen. */
    private Object[] speakerAsk;
    /** MediaPlayerEntityFeature.PLAY_MEDIA. */
    private static final int PLAY_MEDIA = 512;

    /**
     * Whether a start would play nowhere and so asks for a speaker first (media_library.cpp play, favorite_tap): no
     * speaker of its own, the player plays nowhere, it cannot be told to play, and it has speakers to choose from.
     */
    private static boolean needsSpeaker(Tessera.Tile t, String own) {
        Double f = t.number("supported_features");
        boolean canPlay = f != null && (f.intValue() & PLAY_MEDIA) != 0;
        List<Object> sources = Json.arr(t.extra.get("sl"));
        return own.isEmpty() && nz(Json.str(t.extra, "so")).isEmpty() && !canPlay && sources != null && !sources.isEmpty();
    }

    /** The speakers to start on, as the menu that opens then: a tap starts what waited there. */
    private void drawSpeakers(Canvas c, int w, int h) {
        final Object[] ask = speakerAsk;
        Tessera.Tile player = null;
        for (Tessera.Tile t : layout.tiles) if (t.entity.equals(ask[0])) player = t;
        List<Object> sources = player == null ? null : Json.arr(player.extra.get("sl"));
        if (sources == null || sources.isEmpty()) {
            speakerAsk = null;
            return;
        }
        fill.setColor(0xB0000000);
        c.drawRect(0, 0, w, h, fill);
        Hit outside = new Hit(new RectF(0, 0, w, h));
        outside.tap = new Runnable() {
            public void run() { speakerAsk = null; }
        };
        hits.add(outside);
        float fs = 4.2f * u, rowH = fs * 2.4f, gap = 1.4f * u, pad = 4 * u;
        int n = Math.min(sources.size(), Math.max(1, (int) ((h * 0.8f - pad * 3 - fs * 2) / (rowH + gap))));
        float cw = Math.min(w * 0.8f, 90 * u), chh = pad * 2.5f + fs * 1.6f + n * (rowH + gap);
        RectF r = new RectF((w - cw) / 2, (h - chh) / 2, (w + cw) / 2, (h + chh) / 2);
        fill.setColor(Style.CARD);
        c.drawRoundRect(r, 3 * u, 3 * u, fill);
        hits.add(new Hit(new RectF(r)));
        text.setTypeface(medium);
        text.setTextSize(fs * 1.1f);
        text.setColor(Style.TEXT);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("Play on", r.left + pad, r.top + pad + fs, text);
        float y = r.top + pad * 1.5f + fs * 1.6f;
        for (int i = 0; i < n; i++) {
            final String name = String.valueOf(sources.get(i));
            RectF row = new RectF(r.left + pad, y, r.right - pad, y + rowH);
            fill.setColor(Style.RAISED);
            c.drawRoundRect(row, 2 * u, 2 * u, fill);
            glyphs.setColor(Style.DIM);
            glyphs.setTextSize(fs * 1.1f);
            c.drawText(Icons.sure(getContext(), "speaker"), row.left + fs * 1.1f, row.centerY() + fs * 0.4f, glyphs);
            text.setTypeface(regular);
            text.setTextSize(fs);
            text.setColor(Style.TEXT);
            c.drawText(fit(name, row.width() - fs * 3), row.left + fs * 2.2f, row.centerY() + fs * 0.35f, text);
            Hit hit = new Hit(row);
            hit.tap = new Runnable() {
                public void run() {
                    speakerAsk = null;
                    Tessera tessera = app.screen().tessera;
                    String entity = (String) ask[0];
                    long item = ((Number) ask[1]).longValue();
                    int tile = ((Number) ask[2]).intValue();
                    if (tile >= 0) {
                        favoriteStarted.put(tile, SystemClock.uptimeMillis());
                        tessera.playFavoriteOn(entity, tile, name);
                    } else {
                        tessera.play(entity, item, name);
                        say("Starting on " + name);
                    }
                }
            };
            hits.add(hit);
            y += rowH + gap;
        }
    }

    private void openFolder(Tessera.Tile t, Object[] item) {
        libraryPath.add(new Object[]{item[0], item[1]});
        libraryPage = 0;
        app.screen().tessera.askFolder(t.entity, (Long) item[0]);
    }

    private float width(String s, float size) {
        text.setTypeface(medium);
        text.setTextSize(size);
        return text.measureText(s);
    }

    // ----- history (history_view.h) -----

    private final HistoryCard historyCard = new HistoryCard();
    private String historyFor = "";
    private int historyHours = 24;
    private long historyAsked;
    private float scrubX = Float.NaN;

    private void drawHistory(Canvas c, final Tessera.Tile t, RectF area, float fs, int accent) {
        Tessera tessera = app.screen().tessera;
        if (!historyFor.equals(t.entity)) {
            // A card opens on the range its tile chose: an hour, else a day.
            historyFor = t.entity;
            Integer h = t.options == null ? null : Json.integer(t.options, "history_hours");
            historyHours = h != null && h == 1 ? 1 : 24;
            historyAsked = 0;
        }
        Tessera.History h = tessera.history(t.entity, historyHours);
        long now = SystemClock.uptimeMillis();
        // Asked once per opening and range; again after 30 s without an answer, and every five minutes it stays open.
        boolean asked = tessera.askedHistory().equals(t.entity + "|" + historyHours) && historyAsked != 0;
        if (!asked || (h == null && now - historyAsked > 30000) || (h != null && System.currentTimeMillis() - h.received > 300000 && now - historyAsked > 300000)) {
            historyAsked = now;
            tessera.askHistory(t.entity, historyHours);
        }
        final List<Hit> made = new ArrayList<Hit>();
        historyCard.draw(c, t, h, historyHours, area, fs, u, accent, layout.clock24, scrubX, Style.stateLine(t), new HistoryCard.Hits() {
            public void range(RectF r, final int hours) {
                Hit hit = new Hit(new RectF(r));
                hit.tap = new Runnable() {
                    public void run() {
                        if (historyHours != hours) {
                            historyHours = hours;
                            historyAsked = 0;
                        }
                    }
                };
                made.add(hit);
            }

            public void scrub(RectF r) {
                Hit hit = new Hit(new RectF(r));
                hit.scrub = true;
                made.add(hit);
            }
        });
        hits.addAll(made);
        if (h == null) {
            ui.postDelayed(new Runnable() {
                public void run() { invalidate(); }
            }, 1000);
        }
    }

    private float chips(Canvas c, final Tessera.Tile t, List<Object> options, String current, final String service,
                        final String field, RectF body, float y, float fs) {
        float x = body.left, h = fs * 2.4f;
        text.setTypeface(medium);
        text.setTextSize(h * 0.4f);
        for (Object o : options) {
            if (!(o instanceof String)) continue;
            final String opt = (String) o;
            String label = Style.words(opt);
            float cw = text.measureText(label) + h;
            if (x + cw > body.right) {
                x = body.left;
                y += h + 1.5f * u;
            }
            if (y + h > body.bottom) break;
            RectF b = new RectF(x, y, x + cw, y + h);
            boolean chosen = opt.equals(current);
            drawButton(c, b, label, chosen ? withAlpha(Style.accent(t), 170) : Style.RAISED, chosen ? Style.DARK_TEXT : Style.TEXT);
            Hit hit = new Hit(b);
            hit.tap = new Runnable() {
                public void run() { call(service, t, field, opt); }
            };
            hits.add(hit);
            x += cw + 1.5f * u;
        }
        return y + h + 3 * u;
    }

    private static List<Double> samples(Tessera.Tile t) {
        List<Double> out = new ArrayList<Double>();
        Map<String, Object> h = Json.obj(t.history);
        List<Object> values = h == null ? Json.arr(t.history) : Json.arr(h.get("values"));
        if (values == null) return out;
        for (Object v : values) if (v instanceof Double) out.add((Double) v);
        return out;
    }

    private void drawGraph(Canvas c, List<Double> v, RectF g, int accent) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double x : v) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        if (hi - lo < 1e-9) {
            hi += 1;
            lo -= 1;
        }
        Path p = new Path();
        for (int i = 0; i < v.size(); i++) {
            float x = g.left + g.width() * i / (v.size() - 1);
            float y = (float) (g.bottom - (v.get(i) - lo) / (hi - lo) * g.height());
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(0.6f * u);
        fill.setColor(accent);
        c.drawPath(p, fill);
        fill.setStyle(Paint.Style.FILL);
    }

    // ----- the doorbell (module_doorbell.cpp, docs/DOORBELL.md) -----

    /** Someone touched it: it stays this long after the last touch or ring. The ring around the disc while it rings. */
    private static final long ANSWERED_MS = 300000, RINGING_MS = 15000;
    /** The door key: armed for a second tap, waiting for the lock's new state, "Sent" after an action (doorbell_door.h). */
    private static final long ARM_MS = 5000, WAIT_MS = 10000, SENT_MS = 2000;

    private Tessera.Ring ring, lastRing;
    private boolean ringWasTouched, ringEnded;
    private long ringMs, ringTouchMs, doorArmed, doorSent;
    private String doorState = "";

    private final Runnable ringTick = new Runnable() {
        public void run() {
            if (ring == null) return;
            long now = SystemClock.uptimeMillis();
            if (!ringWasTouched && now - ringMs > ring.timeout * 1000L) {
                report("missed");
                finishRing(null);
                return;
            }
            if (ringWasTouched && now - Math.max(ringMs, ringTouchMs) > ANSWERED_MS) {
                ringEnded = true;
                finishRing(null);
                return;
            }
            invalidate();
            ui.postDelayed(this, 250);
        }
    };

    private static Tessera.Ring copy(Tessera.Ring r) {
        Tessera.Ring c = new Tessera.Ring();
        c.camera = r.camera; c.name = r.name; c.key = r.key; c.doorState = r.doorState; c.link = r.link;
        c.rang = r.rang; c.timeout = r.timeout; c.door = r.door; c.quiet = r.quiet; c.chime = r.chime;
        return c;
    }

    /** The op `doorbell`: the view opens over everything, or rings again when it already shows that camera. */
    public void ring(final Tessera.Ring r) {
        ui.post(new Runnable() {
            public void run() { ringNow(r); }
        });
    }

    private void ringNow(Tessera.Ring r) {
        long now = SystemClock.uptimeMillis();
        if (ring != null && ring.camera.equals(r.camera) && ring.door == r.door) {
            // It rings again while it shows: the moment it rang, the ring around the disc, the timeout over.
            ringMs = now;
            ring.key = r.key;
            ring.rang = r.rang;
            ring.timeout = r.timeout;
            setDoor(r.doorState);
            lastRing = copy(ring);
            ringEnded = false;
            invalidate();
            return;
        }
        if (ring != null) finishRing("closed");
        // Over any card, the settings page, the calendar or another camera.
        cardTile = -1;
        settingsOpen = false;
        calendarOpen = -1;
        pictures.forget("full");
        ring = r;
        lastRing = copy(r);
        ringWasTouched = ringEnded = false;
        ringMs = now;
        ringTouchMs = doorArmed = doorSent = 0;
        doorState = r.doorState;
        pictures.forget("ring");
        app.screen().screenSettings.ringing(true);
        ui.removeCallbacks(ringTick);
        ui.postDelayed(ringTick, 250);
        invalidate();
    }

    /** Ring `key` was answered on another screen or dismissed: it closes here without a word. */
    public void closeRing(final String key) {
        ui.post(new Runnable() {
            public void run() {
                if (ring == null || !ring.key.equals(key)) return;
                ringEnded = true;
                finishRing(null);
            }
        });
    }

    public void ringDoorState(final String key, final String state) {
        ui.post(new Runnable() {
            public void run() {
                if (lastRing != null && lastRing.key.equals(key)) lastRing.doorState = state;
                if (ring != null && ring.key.equals(key)) setDoor(state);
                invalidate();
            }
        });
    }

    private void setDoor(String state) {
        if (!state.equals(doorState)) doorSent = 0;
        doorState = state;
        if (ring != null) ring.doorState = state;
    }

    private void report(String action) {
        if (ring == null) return;
        if (!action.equals("answered") && !action.equals("door") && !action.equals("seen")) ringEnded = true;
        app.screen().doorbellReport(action, ring.key);
    }

    /** The first touch on the view answers the ring; every touch keeps it open. */
    private void ringTouched() {
        ringTouchMs = SystemClock.uptimeMillis();
        if (ringWasTouched) return;
        ringWasTouched = true;
        report("answered");
    }

    /** The view closes: `how` is said when nothing said how it ended yet (its close key, a new alert). */
    private void finishRing(String how) {
        if (ring == null) return;
        if (!ringEnded && how != null) report(how);
        ringEnded = true;
        ring = null;
        pictures.forget("ring");
        ui.removeCallbacks(ringTick);
        app.screen().screenSettings.ringing(false);
        invalidate();
    }

    /** The missed ring's item in the top bar: the ring this screen showed last, opened again without a chime. */
    private void reopenRing() {
        if (lastRing == null) return;
        if (ring != null) finishRing("closed");
        ring = copy(lastRing);
        ringWasTouched = true;
        ringEnded = false;
        long now = SystemClock.uptimeMillis();
        ringMs = now - RINGING_MS;
        ringTouchMs = now;
        doorArmed = doorSent = 0;
        doorState = ring.doorState;
        app.screen().screenSettings.ringing(true);
        report("seen");
        ui.removeCallbacks(ringTick);
        ui.postDelayed(ringTick, 250);
        invalidate();
    }

    public boolean ringing() { return ring != null; }

    private static String agoText(long rang) {
        long now = System.currentTimeMillis() / 1000;
        if (rang <= 0) return "";
        long s = Math.max(0, now - rang);
        if (s < 60) return "Just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + (s < 7200 ? " hour ago" : " hours ago");
        return "Yesterday";
    }

    private void drawRing(Canvas c, int w, int h) {
        final Tessera.Ring r = ring;
        long now = SystemClock.uptimeMillis();
        fill.setColor(0xFF000000);
        c.drawRect(0, 0, w, h, fill);
        hits.add(new Hit(new RectF(0, 0, w, h)));
        float bar = 12 * u, pad = 2.6f * u;
        // The doorbell's disc where the back key stands, ringed while it rings.
        float d = 8.4f * u, cx = pad + d / 2, cy = bar / 2;
        boolean ringingNow = now - ringMs < RINGING_MS;
        if (ringingNow) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(now / 160.0));
            line.setColor(withAlpha(Style.ORANGE, 120 + (int) (100 * pulse)));
            line.setStrokeWidth(0.6f * u);
            c.drawCircle(cx, cy, d / 2 + 1.0f * u, line);
        }
        fill.setColor(withAlpha(Style.ORANGE, 70));
        c.drawCircle(cx, cy, d / 2, fill);
        glyphs.setColor(Style.ORANGE);
        glyphs.setTextSize(d * 0.6f);
        c.drawText(Icons.sure(getContext(), "doorbell"), cx, cy + d * 0.21f, glyphs);
        float tx = pad * 2 + d;
        float closeW = 12 * u;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(medium);
        text.setTextSize(4.4f * u);
        text.setColor(0xFFFFFFFF);
        String name = fit(r.name, w - tx - closeW - 30 * u);
        c.drawText(name, tx, cy + 1.5f * u, text);
        float nx = tx + text.measureText(name) + 2.5f * u;
        text.setTypeface(regular);
        text.setTextSize(3.8f * u);
        text.setColor(0xFFB0B6C0);
        c.drawText(agoText(r.rang), nx, cy + 1.4f * u, text);
        // The close key at the right: it answers the ring too.
        glyphs.setColor(0xFFFFFFFF);
        glyphs.setTextSize(5.6f * u);
        c.drawText(Icons.sure(getContext(), "close"), w - pad - closeW / 2, cy + 2.0f * u, glyphs);
        Hit close = new Hit(new RectF(w - closeW - pad * 2, 0, w, bar));
        close.tap = new Runnable() {
            public void run() { finishRing("closed"); }
        };
        hits.add(close);

        // The picture beside the keys on glass lying down, above them on square or standing glass; never under them.
        RectF room = new RectF(pad, bar, w - pad, h - pad);
        RectF keys = null;
        if (r.door != 0) {
            if (w > h * 1.15f) {
                keys = new RectF(w - pad - 34 * u, bar, w - pad, h - pad);
                room.right = keys.left - pad;
            } else {
                keys = new RectF(pad, h - pad - 18 * u, w - pad, h - pad);
                room.bottom = keys.top - pad;
            }
        }
        drawRingPicture(c, r, room);
        if (keys != null) drawDoorKey(c, keys, now);
    }

    private void drawRingPicture(Canvas c, Tessera.Ring r, RectF room) {
        final Tessera tessera = app.screen().tessera;
        final String entity = r.camera;
        final int bw = Math.max(64, Math.round(room.width())), bh = Math.max(64, Math.round(room.height()));
        pictures.want("ring", entity + "|" + bw + "x" + bh);
        pictures.pace("ring", 8);
        Bitmap b = pictures.keepFresh(tessera, "ring", null, 4000, new Runnable() {
            public void run() {
                // The camera full view's question, live (docs/PICTURES.md): the add-on streams it if it can, else a still.
                Map<String, Object> q = question("view", -1, entity, "camera:" + entity, bw, bh, 0, 0, "shrink", "000000");
                q.put("pace", 4000.0);
                q.put("live", numbers(bw, bh));
                tessera.askQuestion("ring", q);
            }
        });
        if (b != null) {
            float scale = Math.min(room.width() / b.getWidth(), room.height() / b.getHeight());
            float pw = b.getWidth() * scale, ph = b.getHeight() * scale;
            RectF dst = new RectF(room.centerX() - pw / 2, room.centerY() - ph / 2, room.centerX() + pw / 2, room.centerY() + ph / 2);
            c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), dst, null);
        } else {
            text.setTypeface(regular);
            text.setTextSize(4 * u);
            text.setColor(0xFFB0B6C0);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(app.screen().device.actionSubscribers() == 0 ? "Pictures need Home Assistant actions allowed"
                    : "Connecting to the camera…", room.centerX(), room.centerY(), text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private boolean doorSending(long now) { return doorSent != 0 && now - doorSent < (ring.door == 1 ? WAIT_MS : SENT_MS); }

    private boolean doorOpens(long now) {
        if (doorSending(now)) return false;
        if (ring.door == 2) return true;
        return ring.door == 1 && (doorState.equals("locked") || doorState.equals("jammed"));
    }

    /** The Door key: one tap arms it ("Tap again to open"), a second within five seconds opens the door. */
    private void drawDoorKey(Canvas c, RectF keys, final long now) {
        boolean armed = doorArmed != 0 && now - doorArmed < ARM_MS;
        int color;
        String icon, line;
        if (armed) {
            color = Style.ORANGE; icon = "lock-open-variant"; line = "Tap again to open";
        } else if (ring.door == 2) {
            color = 0xFFB0B6C0; icon = "door-open"; line = doorSending(now) ? "Sent" : "";
        } else if (doorSending(now)) {
            color = Style.ORANGE; icon = "lock-clock"; line = "Unlocking";
        } else {
            String st = doorState;
            color = st.equals("locked") ? Style.GREEN : st.equals("unlocked") || st.equals("open") || st.equals("jammed") ? Style.RED
                    : st.equals("locking") || st.equals("unlocking") || st.equals("opening") ? Style.ORANGE : 0xFFB0B6C0;
            icon = st.equals("locked") ? "lock" : st.equals("jammed") ? "lock-alert" : st.equals("locking") || st.equals("unlocking") ? "lock-clock" : "lock-open-variant";
            line = st.isEmpty() || st.equals("unavailable") || st.equals("unknown") ? "Unavailable" : Character.toUpperCase(st.charAt(0)) + st.substring(1);
        }
        boolean column = keys.height() > keys.width();
        RectF k = column ? new RectF(keys.left, keys.top + 2 * u, keys.right, keys.top + 2 * u + Math.min(30 * u, keys.height() * 0.4f))
                : new RectF(keys.centerX() - Math.min(45 * u, keys.width() / 2), keys.top, keys.centerX() + Math.min(45 * u, keys.width() / 2), keys.bottom);
        fill.setColor(armed ? withAlpha(Style.ORANGE, 70) : 0xFF22262E);
        c.drawRoundRect(k, 3 * u, 3 * u, fill);
        glyphs.setColor(color);
        if (column) {
            glyphs.setTextSize(9 * u);
            c.drawText(Icons.sure(getContext(), icon), k.centerX(), k.top + k.height() * 0.42f, glyphs);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(medium);
            text.setTextSize(4.2f * u);
            text.setColor(0xFFFFFFFF);
            c.drawText("Door", k.centerX(), k.top + k.height() * 0.66f, text);
            if (!line.isEmpty()) {
                text.setTypeface(regular);
                text.setTextSize(3.2f * u);
                text.setColor(color);
                c.drawText(fit(line, k.width() - 2 * u), k.centerX(), k.top + k.height() * 0.85f, text);
            }
        } else {
            glyphs.setTextSize(k.height() * 0.5f);
            c.drawText(Icons.sure(getContext(), icon), k.left + k.height() * 0.6f, k.centerY() + k.height() * 0.18f, glyphs);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(medium);
            text.setTextSize(4.4f * u);
            text.setColor(0xFFFFFFFF);
            float lx = k.left + k.height() * 1.2f;
            c.drawText("Door", lx, line.isEmpty() ? k.centerY() + 1.5f * u : k.centerY() - 0.5f * u, text);
            if (!line.isEmpty()) {
                text.setTypeface(regular);
                text.setTextSize(3.4f * u);
                text.setColor(color);
                c.drawText(fit(line, k.right - lx - 2 * u), lx, k.centerY() + 4 * u, text);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
        Hit h = new Hit(k);
        h.tap = new Runnable() {
            public void run() {
                long t = SystemClock.uptimeMillis();
                if (ring == null) return;
                if (!doorOpens(t)) {
                    doorArmed = 0;
                } else if (doorArmed != 0 && t - doorArmed < ARM_MS) {
                    doorArmed = 0;
                    doorSent = t;
                    report("door");
                } else {
                    doorArmed = t;
                }
                invalidate();
            }
        };
        hits.add(h);
    }

    // ----- alerts and banners -----

    private void drawAlert(Canvas c, int w, int h) {
        final Screen.Alert a = alert;
        fill.setColor(0xC8000000);
        c.drawRect(0, 0, w, h, fill);
        hits.add(new Hit(new RectF(0, 0, w, h)));
        int bg = Style.pastel(a.color);
        int fg = bg != 0 ? Style.DARK_TEXT : Style.TEXT, dim = bg != 0 ? Style.DARK_DIM : Style.DIM;
        float cw = Math.min(w * 0.8f, 110 * u), chh = Math.min(h * 0.75f, 70 * u);
        RectF r = new RectF((w - cw) / 2, (h - chh) / 2, (w + cw) / 2, (h + chh) / 2);
        fill.setColor(bg != 0 ? bg : Style.CARD);
        c.drawRoundRect(r, 3 * u, 3 * u, fill);
        String icon = Icons.named(getContext(), a.icon == null || a.icon.isEmpty() ? "alert-outline" : a.icon);
        if (icon == null) icon = Icons.named(getContext(), "alert-outline");
        glyphs.setColor(fg);
        glyphs.setTextSize(13 * u);
        c.drawText(icon, r.centerX(), r.top + 16 * u, glyphs);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(medium);
        text.setColor(fg);
        text.setTextSize(5.4f * u);
        c.drawText(fit(a.title, cw - 8 * u), r.centerX(), r.top + 27 * u, text);
        text.setTypeface(regular);
        text.setColor(dim);
        text.setTextSize(3.8f * u);
        if (a.subtitle != null && !a.subtitle.isEmpty()) {
            float y = r.top + 34 * u;
            for (String line : a.subtitle.split("\n")) {
                c.drawText(fit(line, cw - 8 * u), r.centerX(), y, text);
                y += 5 * u;
                if (y > r.bottom - 16 * u) break;
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
        boolean two = a.button2 != null && !a.button2.isEmpty();
        float bw = 30 * u, bh = 9 * u, by = r.bottom - bh - 4 * u;
        RectF first = two ? new RectF(r.centerX() + 2 * u, by, r.centerX() + 2 * u + bw, by + bh)
                : new RectF(r.centerX() - bw / 2, by, r.centerX() + bw / 2, by + bh);
        drawButton(c, first, a.button, Style.named(a.buttonColor, bg != 0 ? 0x33000000 : Style.BLUE), bg != 0 ? Style.DARK_TEXT : Style.DARK_TEXT);
        Hit ok = new Hit(first);
        ok.tap = new Runnable() {
            public void run() { endAlert("ok"); }
        };
        hits.add(ok);
        if (two) {
            RectF second = new RectF(r.centerX() - 2 * u - bw, by, r.centerX() - 2 * u, by + bh);
            drawButton(c, second, a.button2, Style.named(a.button2Color, bg != 0 ? 0x22000000 : Style.RAISED), fg);
            Hit no = new Hit(second);
            no.tap = new Runnable() {
                public void run() { endAlert("button2"); }
            };
            hits.add(no);
        }
    }

    private void say(String message) {
        banner = message;
        bannerUntil = SystemClock.uptimeMillis() + 4000;
        ui.postDelayed(new Runnable() {
            public void run() { invalidate(); }
        }, 4100);
        invalidate();
    }

    private void drawBanner(Canvas c, int w, int h) {
        text.setTypeface(medium);
        text.setTextSize(3.4f * u);
        String m = fit(banner, w * 0.8f);
        float tw = text.measureText(m) + 8 * u;
        RectF r = new RectF((w - tw) / 2, h - 16 * u, (w + tw) / 2, h - 7 * u);
        fill.setColor(0xF0303642);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        text.setColor(Style.TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(m, r.centerX(), r.centerY() + 1.2f * u, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ----- what a tap does (widgets/tap.go) -----

    private Runnable tapFor(final int index, final Tessera.Tile t, final boolean hold) {
        return new Runnable() {
            public void run() { route(index, t, hold); }
        };
    }

    private void route(int index, Tessera.Tile t, boolean hold) {
        String d = t.domain(), e = t.entity;
        if (e.startsWith("screen.page_")) {
            try {
                int target = Integer.parseInt(e.substring("screen.page_".length())) - 1;
                if (target >= 0 && target < layout.pages.size()) {
                    if (layout.pages.get(target).excluded) {
                        if (route.size() >= 8) route.remove(0);
                        route.add(page);
                    }
                    page = target;
                }
            } catch (NumberFormatException ignored) {
            }
            return;
        }
        if (e.equals("screen.settings")) {
            openSettingsPage();
            return;
        }
        if (e.startsWith("screen.")) return;
        if ("none".equals(t.tap)) return;
        // A tap action of a plugin (plugin:<plugin>.<action>) set on this tile.
        String[] pk = PluginKit.key(t.tap);
        if (!hold && pk != null) {
            PluginKit.Plugin p = PluginKit.of(pk[0]);
            if (p != null && p.tapAction(pk[1], t, index, pluginHost())) return;
        }
        if (!hold && "action".equals(t.tap) && t.actionService() != null) {
            custom(t);
            return;
        }
        if (!hold && "toggle".equals(t.tap)) {
            toggle(t);
            return;
        }
        // A favourite plays what it holds, or pauses it while it plays (favorite_tap); a hold opens the player's card.
        if (!hold && "favorite".equals(t.display) && d.equals("media_player")) {
            if (Json.integer(t.extra, "fp") != null && "playing".equals(t.state)) {
                call("media_player.media_pause", t);
            } else if (needsSpeaker(t, nz(Json.str(t.extra, "fo")))) {
                speakerAsk = new Object[]{t.entity, -1L, index};
            } else {
                favoriteStarted.put(index, SystemClock.uptimeMillis());
                app.screen().tessera.playFavorite(t.entity, index);
            }
            return;
        }
        if (d.equals("camera") || d.equals("image")) {
            cardTile = index;
            return;
        }
        if (d.equals("calendar")) {
            calendar().open(t, getWidth(), getHeight());
            calendarOpen = index;
            return;
        }
        if (d.equals("automation") && !"detail".equals(t.tap)) {
            if (hold) cardTile = index; else call("automation.trigger", t);
            return;
        }
        if (d.equals("lock")) {
            cardTile = index;
            return;
        }
        boolean open = hold || "detail".equals(t.tap) || d.equals("vacuum") || d.equals("cover");
        if (d.equals("timer") && !open) {
            call("active".equals(t.state) ? "timer.pause" : "timer.start", t);
            return;
        }
        if (open || CARDS.contains(d)) {
            cardTile = index;
            return;
        }
        if (d.equals("light") || d.equals("switch") || d.equals("input_boolean") || d.equals("fan")) {
            toggle(t);
        } else if (d.equals("scene") || d.equals("script")) {
            call(d + ".turn_on", t);
        } else if (d.equals("button") || d.equals("input_button")) {
            call(d + ".press", t);
        } else {
            cardTile = index;
        }
    }

    /** Whether a tap on this tile can open a card (icon toggles changes only those): not a page, a scene or a button. */
    private boolean opensCard(Tessera.Tile t) {
        String d = t.domain(), e = t.entity;
        if (e.startsWith("screen.") || "none".equals(t.tap) || "action".equals(t.tap)) return false;
        return !(d.equals("scene") || d.equals("script") || d.equals("button") || d.equals("input_button"));
    }

    /**
     * What the icon does with icon toggles on: switch it on or off, play or pause, start or pause, open or close a blind;
     * null where the icon has nothing quicker than the card (a sensor, a lock, a thermostat), so it opens the card too.
     */
    private Runnable quickAction(final int index, final Tessera.Tile t) {
        final String d = t.domain();
        if ("favorite".equals(t.display) && d.equals("media_player")) return tapFor(index, t, false);
        if (d.equals("light") || d.equals("switch") || d.equals("input_boolean") || d.equals("fan") || d.equals("humidifier")
                || d.equals("siren") || d.equals("remote") || d.equals("automation") && "toggle".equals(t.tap)) {
            return new Runnable() {
                public void run() { toggle(t); }
            };
        }
        if (d.equals("automation")) return new Runnable() {
            public void run() { call("automation.trigger", t); }
        };
        if (d.equals("cover")) return new Runnable() {
            public void run() { call("cover.toggle", t); }
        };
        if (d.equals("timer")) return new Runnable() {
            public void run() { call("active".equals(t.state) ? "timer.pause" : "timer.start", t); }
        };
        if (d.equals("vacuum")) {
            final List<String[]> keys = VacuumCard.tileKeys(t);
            if (keys.isEmpty()) return null;
            return new Runnable() {
                public void run() { call(keys.get(0)[1], t); }
            };
        }
        if (d.equals("media_player")) {
            // A TV or a receiver (Home Assistant's device class, or a player with power and no play/pause) switches on
            // and off; a speaker plays and pauses, and turns on when it is off.
            final int f = t.number("supported_features") == null ? 0 : t.number("supported_features").intValue();
            String dc = t.text("device_class");
            boolean power = (f & (128 | 256)) != 0;
            final boolean tv = "tv".equals(dc) || "receiver".equals(dc)
                    || dc == null && power && (f & (1 | 16384)) == 0;
            return new Runnable() {
                public void run() {
                    boolean off = "off".equals(t.state) || "standby".equals(t.state);
                    if (off) call("media_player.turn_on", t);
                    else if (tv) call("media_player.turn_off", t);
                    else call("media_player.media_play_pause", t);
                }
            };
        }
        return null;
    }

    private static final java.util.Set<String> CARDS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "sensor", "binary_sensor", "weather", "number", "input_number", "select", "input_select", "media_player",
            "vacuum", "cover", "sun", "person", "timer", "climate", "humidifier", "alarm_control_panel", "remote"));

    private void toggle(Tessera.Tile t) {
        String d = t.domain();
        String service = d + ".toggle";
        if (d.equals("scene") || d.equals("script")) service = d + ".turn_on";
        if (call(service, t)) {
            Wish w = new Wish();
            w.from = t.state;
            w.state = Style.active(t) ? "off" : "on";
            w.until = SystemClock.uptimeMillis() + 6000;
            wishes.put(t.entity, w);
        }
    }

    private void custom(Tessera.Tile t) {
        String service = t.actionService();
        Map<String, String> data = t.actionPairs("d");
        Map<String, String> templates = t.actionPairs("t");
        String domain = service.contains(".") ? service.substring(0, service.indexOf('.')) : "";
        if (!data.containsKey("entity_id") && domain.equals(t.domain())) data.put("entity_id", t.entity);
        if (!app.screen().perform(service, data, templates)) noActions();
    }

    private boolean call(String service, Tessera.Tile t, String... kv) {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity_id", t.entity);
        for (int i = 0; i + 1 < kv.length; i += 2) data.put(kv[i], kv[i + 1]);
        boolean ok = app.screen().perform(service, data, null);
        if (!ok) noActions();
        invalidate();
        return ok;
    }

    private void noActions() {
        say(app.screen().device.clients() == 0 ? "Not connected to Home Assistant"
                : "Allow this device to perform Home Assistant actions in its ESPHome options");
    }

    private void go(int target) {
        if (layout == null || target < 0 || target >= layout.pages.size()) return;
        page = target;
        cardTile = -1;
        if (!layout.pages.get(target).excluded) route.clear();
        invalidate();
    }

    private void back() {
        cardTile = -1;
        if (!route.isEmpty()) {
            int to = route.remove(route.size() - 1);
            if (to >= 0 && layout != null && to < layout.pages.size()) {
                page = to;
                invalidate();
                return;
            }
        }
        route.clear();
        if (layout != null) page = layout.home;
        invalidate();
    }

    private void swipe(int direction) {
        if (layout == null || cardTile >= 0 || alert != null || !app.screen().screenSettings.swipe()) return;
        List<Integer> inc = included();
        int at = inc.indexOf(page);
        if (at < 0) return;
        int next = at + direction;
        if (next < 0 && app.widgetPage()) {
            host.openWidgets();
            return;
        }
        if (next < 0 || next >= inc.size()) return;
        go(inc.get(next));
    }

    // ----- touch -----

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        lastTouch = SystemClock.uptimeMillis();
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (ring != null) ringTouched();
                downX = x;
                downY = y;
                swiping = false;
                longFired = false;
                dragging = null;
                lastStep = Integer.MIN_VALUE;
                moved = false;
                downHit = hitAt(x, y);
                topSwipe = y < 3 * u && !settingsOpen;
                // Touch feedback (plugin API 0.11 on_press): a finger landed on something that takes it.
                if (downHit != null && (downHit.tap != null || downHit.hold != null || downHit.slider != null
                        || downHit.repeat != null || downHit.drag != null)) pressed();
                if (downHit != null && downHit.drag != null) {
                    dragged = downHit.drag;
                    dragged.at(x, y, false);
                    invalidate();
                    return true;
                }
                if (downHit != null && downHit.repeat != null) {
                    repeating = downHit.repeat;
                    repeatDown = SystemClock.uptimeMillis();
                    repeating.step(false);
                    invalidate();
                    ui.postDelayed(repeater, 450);
                    return true;
                }
                // A slider waits: a drag moves it, a tap sets it there, and a hold opens the card.
                if (downHit != null && downHit.slider != null) dragging = downHit.slider;
                // A finger on a history graph reads the moment under it until it lifts.
                if (downHit != null && downHit.scrub) {
                    scrubX = x;
                    invalidate();
                    return true;
                }
                ui.postDelayed(longPress, 550);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (repeating != null) return true;
                if (dragged != null) {
                    dragged.at(x, y, false);
                    invalidate();
                    return true;
                }
                // A swipe down from the top edge opens the settings page (firmware 0.28.0+), as swiping allows.
                if (topSwipe && y - downY > 12 * u && Math.abs(x - downX) < y - downY && app.screen().screenSettings.swipe()) {
                    topSwipe = false;
                    ui.removeCallbacks(longPress);
                    downHit = null;
                    openSettingsPage();
                    return true;
                }
                if (!Float.isNaN(scrubX)) {
                    scrubX = x;
                    invalidate();
                    return true;
                }
                if (dragging != null) {
                    if (!moved && Math.abs(x - downX) < 1.5f * u) return true;
                    moved = true;
                    ui.removeCallbacks(longPress);
                    slide(x);
                    return true;
                }
                if (Math.abs(x - downX) > 6 * u || Math.abs(y - downY) > 6 * u) {
                    ui.removeCallbacks(longPress);
                    if (Math.abs(x - downX) > 6 * u && Math.abs(x - downX) > Math.abs(y - downY)) swiping = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                ui.removeCallbacks(longPress);
                if (dragged != null) {
                    PluginKit.Drag d = dragged;
                    dragged = null;
                    d.at(x, y, true);
                    invalidate();
                    return true;
                }
                if (repeating != null) {
                    repeating = null;
                    ui.removeCallbacks(repeater);
                    return true;
                }
                if (!Float.isNaN(scrubX)) {
                    scrubX = Float.NaN;
                    invalidate();
                    return true;
                }
                if (dragging != null) {
                    slide(x);
                    Slider s = dragging;
                    dragging = null;
                    s.set.set(held.get(s.key).value);
                    held.get(s.key).until = SystemClock.uptimeMillis() + 5000;
                    invalidate();
                    return true;
                }
                if (swiping) {
                    swipe(x < downX ? 1 : -1);
                    return true;
                }
                if (!longFired && downHit != null && downHit.tap != null && downHit.r.contains(x, y)) {
                    downHit.tap.run();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPress);
                if (dragged != null) {
                    PluginKit.Drag d = dragged;
                    dragged = null;
                    d.at(downX, downY, true);
                }
                repeating = null;
                ui.removeCallbacks(repeater);
                scrubX = Float.NaN;
                dragging = null;
                return true;
        }
        return true;
    }

    private void slide(float x) {
        Slider s = dragging;
        if (s == null) return;
        double frac = Math.max(0, Math.min(1, (x - s.track.left) / s.track.width()));
        double v = s.min + frac * (s.max - s.min);
        if (s.step > 0) v = Math.round(v / s.step) * s.step;
        stepped(v, s.min, s.max);
        Held h = held.get(s.key);
        if (h == null) {
            h = new Held();
            held.put(s.key, h);
        }
        h.value = v;
        h.until = SystemClock.uptimeMillis() + 60000;
        invalidate();
    }

    /** The topmost thing under a point: later hits were drawn over earlier ones. */
    private Hit hitAt(float x, float y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.r.contains(x, y)) return h;
        }
        return null;
    }

    // ----- helpers -----

    private String fit(String s, float width) {
        if (s == null) return "";
        if (width <= 0) return "";
        return TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
    }

    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    private static double orElse(Double v, double fallback) { return v == null ? fallback : v; }

    private static Double numberState(Tessera.Tile t) {
        try {
            return Double.valueOf(t.state);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
