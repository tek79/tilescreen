package app.tilescreen.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.tilescreen.App;
import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/**
 * Tessera's screensaver (docs/SCREENSAVER.md): the cover of what plays, a camera, photos, or the clock with the
 * date, the outside temperature and a row of entities, white on black. Tessera picks which is shown and when.
 */
public final class SaverView extends View {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Pictures pictures = new Pictures(new Pictures.Redraw() {
        public void redraw() { postInvalidate(); }
    });
    private final Runnable tick = new Runnable() {
        public void run() {
            invalidate();
            ui.postDelayed(this, 1000);
        }
    };

    private final ClockFaces faces;

    public SaverView(Context c) {
        super(c);
        glyphs.setTypeface(Icons.font(c));
        glyphs.setTextAlign(Paint.Align.CENTER);
        faces = new ClockFaces(glyphs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ui.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float u = Math.min(w, h) / 100f;
        c.drawColor(0xFF000000);
        keys = null;
        App app = App.get();
        Tessera tessera = app.screen().tessera;
        Tessera.Layout layout = tessera.layout();
        Map<String, Object> saver = tessera.shown() ? tessera.saver() : null;
        String kind = saver == null ? "clock" : nz(Json.str(saver, "k"));
        if (kind.equals("media") || kind.equals("camera") || kind.equals("photos")) {
            if (drawPicture(c, tessera, saver, kind, w, h, u)) return;
        }
        // A plugin's face of the step, or a step of its own (`pf`, docs/PLUGINS.md "A screensaver").
        Map<String, Object> pf = saver == null ? null : Json.obj(saver.get("pf"));
        if (pf != null) {
            String[] pk = PluginKit.key(Json.str(pf, "p"));
            PluginKit.Plugin plugin = pk == null ? null : PluginKit.of(pk[0]);
            if (plugin != null) {
                PluginKit.Paints p = new PluginKit.Paints();
                p.text = text;
                p.glyphs = glyphs;
                p.fill = fill;
                p.regular = regular;
                p.medium = medium;
                p.light = light;
                p.context = getContext();
                if (plugin.drawSaver(pk[1], Json.obj(pf.get("o")), Json.obj(pf.get("x")), c, w, h, mm(), p)) return;
            }
        }
        ClockFaces.Face face = ClockFaces.Face.of(saver);
        if (face.kind != ClockFaces.DIGITAL) {
            faces.draw(c, face, w, h, mm(), app.screen().screenSettings.dark(), row(saver));
            return;
        }
        drawClock(c, saver, layout.clock24, w, h, u);
    }

    /**
     * A millimetre of glass in pixels (ui::mm), from the display's density; a 7-inch tablet's short side is about
     * 90 mm where the density is unknown.
     */
    private float mm() {
        return dpi > 50 ? dpi / 25.4f : Math.min(getWidth(), getHeight()) / 90f;
    }

    /** The display's dots per inch, set by the activity. */
    public static float dpi;

    /** The clock's row, {icon glyph or null, words}: the entities Tessera picked, else the outside temperature. */
    private static List<String[]> row(Map<String, Object> saver) {
        List<String[]> parts = new ArrayList<String[]>();
        List<Object> items = saver == null ? null : Json.arr(saver.get("wi"));
        if (items != null) {
            for (Object o : items) {
                Map<String, Object> it = Json.obj(o);
                if (it == null) continue;
                String k = nz(Json.str(it, "k")), words = nz(Json.str(it, "t"));
                if (k.equals("ago")) {
                    Double e = Json.dbl(it, "e");
                    if (e != null) {
                        long mins = Math.max(0, (System.currentTimeMillis() / 1000 - e.longValue()) / 60);
                        String ago = mins < 1 ? "now" : mins < 60 ? mins + " min ago" : mins < 1440 ? (mins / 60) + " h ago" : (mins / 1440) + " d ago";
                        words = words.isEmpty() ? ago : words + " " + ago;
                    }
                }
                String icon = Json.str(it, "i");
                parts.add(new String[]{icon == null || icon.isEmpty() ? null : Icons.glyph(icon), words});
            }
        } else if (saver != null && Json.str(saver, "w") != null) {
            parts.add(new String[]{null, Json.str(saver, "w")});
        }
        return parts;
    }

    private boolean drawPicture(Canvas c, final Tessera tessera, Map<String, Object> saver, final String kind,
                                int w, int h, float u) {
        final String entity = nz(Json.str(saver, "e"));
        Map<String, Object> x = Json.obj(saver.get("x"));
        String mark = nz(Json.str(x, "pic"));
        pictures.want("saver", kind + "|" + entity + "|" + mark);
        long every = kind.equals("camera") ? 15000 : 3600000;
        Bitmap b = pictures.keepFresh(tessera, "saver", entity, every, new Runnable() {
            public void run() {
                Map<String, String> f = new LinkedHashMap<String, String>();
                f.put("saver", kind);
                f.put("entity", entity);
                f.put("size", "");
                f.put("bg", "000000");
                tessera.askPicture("saver", f);
            }
        });
        if (b == null) return false;
        float textRight = w - 5 * u;
        float scale = Math.min((float) w / b.getWidth(), (float) h / b.getHeight());
        float pw = b.getWidth() * scale, ph = b.getHeight() * scale;
        c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()),
                new RectF((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2), null);
        // The words small at the bottom left: the title and who plays it, or the camera's name.
        String big = null, small = null;
        if (kind.equals("media")) {
            big = nz(Json.str(saver, "t"));
            if (big.isEmpty()) big = nz(Json.str(saver, "n"));
            List<String> by = new ArrayList<String>();
            for (String part : new String[]{Json.str(x, "artist"), Json.str(x, "album")}) {
                if (part != null && !part.isEmpty()) by.add(part);
            }
            small = TextUtils.join(" · ", by);
        } else if (kind.equals("camera")) {
            big = nz(Json.str(saver, "n"));
        }
        // A player's keys in the bottom right corner (saver_view::keys): the words end before them.
        keys = kind.equals("media") ? drawKeys(c, saver, w, h, u) : null;
        if (keys != null) textRight = keyLeft - 2.5f * u;
        float y = h - 5 * u;
        text.setTextAlign(Paint.Align.LEFT);
        if (small != null && !small.isEmpty()) {
            text.setTypeface(regular);
            text.setTextSize(3.6f * u);
            text.setColor(0xBFFFFFFF);
            c.drawText(fit(small, textRight - 5 * u), 5 * u, y, text);
            y -= 5.5f * u;
        }
        if (big != null && !big.isEmpty()) {
            text.setTypeface(medium);
            text.setTextSize(5.6f * u);
            text.setColor(0xFFFFFFFF);
            c.drawText(fit(big, textRight - 5 * u), 5 * u, y, text);
        }
        return true;
    }

    private void drawClock(Canvas c, Map<String, Object> saver, boolean clock24, int w, int h, float u) {
        Date now = new Date();
        // A few pixels' drift each minute, so nothing stands still on the glass for hours.
        long minute = now.getTime() / 60000;
        float dx = (float) Math.sin(minute * 0.7) * 2 * u, dy = (float) Math.cos(minute * 0.5) * 2 * u;
        String time = new SimpleDateFormat(clock24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(light);
        text.setColor(0xFFFFFFFF);
        float size = Math.min(h * 0.38f, w / (time.length() * 0.6f));
        text.setTextSize(size);
        float cy = h * 0.47f + dy, cx = w / 2f + dx;
        c.drawText(time, cx, cy + size * 0.35f, text);
        if (!clock24) {
            text.setTypeface(regular);
            text.setTextSize(size * 0.16f);
            String ampm = new SimpleDateFormat("a", Locale.getDefault()).format(now);
            float tw;
            text.setTextSize(size);
            text.setTypeface(light);
            tw = text.measureText(time);
            text.setTypeface(regular);
            text.setTextSize(size * 0.16f);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(ampm, cx + tw / 2 + u, cy + size * 0.35f, text);
            text.setTextAlign(Paint.Align.CENTER);
        }
        text.setTypeface(regular);
        text.setTextSize(4.4f * u);
        text.setColor(0xFFB0B0B0);
        c.drawText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now), cx, cy + size * 0.35f + 8 * u, text);

        // The temperature and the entities, in one line at the bottom.
        List<String[]> parts = row(saver);
        if (parts.isEmpty()) return;
        text.setTextSize(4.2f * u);
        text.setTextAlign(Paint.Align.LEFT);
        glyphs.setTextSize(4.6f * u);
        glyphs.setColor(0xFFB0B0B0);
        float gap = 3 * u, total = 0;
        float[] widths = new float[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            String[] p = parts.get(i);
            widths[i] = text.measureText(p[1]) + (p[0] != null ? 5.4f * u : 0);
            total += widths[i] + (i > 0 ? gap : 0);
        }
        float x = cx - total / 2, base = h - 7 * u + dy * 0.3f;
        for (int i = 0; i < parts.size(); i++) {
            String[] p = parts.get(i);
            if (i > 0) {
                fill.setColor(0xFF707070);
                c.drawCircle(x + gap / 2 - gap, base - 1.4f * u, 0.5f * u, fill);
            }
            if (p[0] != null) {
                c.drawText(p[0], x + 2.3f * u, base + 0.3f * u, glyphs);
                x += 5.4f * u;
            }
            c.drawText(p[1], x, base, text);
            x += text.measureText(p[1]) + gap;
        }
    }

    // ---- a player's keys (firmware 0.33.0, saver_keys): play or pause, volume down over it and volume up at the top.
    // A tap on one does what it says, and the screen stays in standby; a hold on volume down mutes.

    private static final int PAUSE = 1, VOLUME_SET = 4, VOLUME_MUTE = 8, VOLUME_STEP = 1024, PLAY = 16384;
    private static final long MUTE_HOLD_MS = 700, WISH_MS = 6000;
    /** plus, minus, play; null where the player has no such key. */
    private RectF[] keys;
    private float keyLeft;
    private String keysEntity = "";
    private Boolean playingWish, mutedWish;
    private long wishUntil;

    private RectF[] drawKeys(Canvas c, Map<String, Object> saver, int w, int h, float u) {
        Double f = Json.dbl(saver, "f");
        if (f == null) return null;   // a screen message without keys (the add-on sends `f` to screens that draw them)
        int features = f.intValue();
        boolean withPlay = (features & (PLAY | PAUSE)) != 0, withVolume = (features & (VOLUME_SET | VOLUME_STEP)) != 0;
        if (!withPlay && !withVolume) return null;
        keysEntity = nz(Json.str(saver, "e"));
        if (System.currentTimeMillis() > wishUntil) playingWish = mutedWish = null;
        boolean playing = playingWish != null ? playingWish : "playing".equals(Json.str(saver, "s"));
        boolean muted = mutedWish != null ? mutedWish : Json.dbl(saver, "m") != null;
        float margin = 4.5f * u, play = 12 * u, key = 9 * u, gap = 2.3f * u;
        float x = w - margin - play, y = h - margin;
        RectF[] out = new RectF[3];
        if (withPlay) {
            y -= play;
            out[2] = new RectF(x, y, x + play, y + play);
            y -= gap;
        }
        if (withVolume) {
            out[1] = new RectF(x + (play - key) / 2, y - key, x + (play + key) / 2, y);
            out[0] = new RectF(out[1].left, out[1].top - gap - key, out[1].right, out[1].top - gap);
        }
        keyLeft = x;
        String[] icons = {"plus", muted ? "volume-off" : "minus", playing ? "pause" : "play"};
        for (int i = 0; i < 3; i++) {
            RectF r = out[i];
            if (r == null) continue;
            boolean primary = i == 2;
            fill.setColor(primary ? 0xFFFFFFFF : 0x38FFFFFF);
            c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill);
            glyphs.setColor(primary ? 0xFF000000 : 0xFFFFFFFF);
            glyphs.setTextSize(r.width() * 0.55f);
            c.drawText(Icons.sure(getContext(), icons[i]), r.centerX(), r.centerY() + r.width() * 0.2f, glyphs);
        }
        return out;
    }

    /** The key under a finger, 0 volume up, 1 volume down, 2 play or pause; -1 for none. A key takes a little more. */
    public int keyAt(float x, float y) {
        RectF[] k = keys;
        if (k == null || getVisibility() != VISIBLE) return -1;
        float more = Math.min(getWidth(), getHeight()) / 100f;
        for (int i = 0; i < 3; i++) {
            if (k[i] != null && x >= k[i].left - more && x <= k[i].right + more && y >= k[i].top - more && y <= k[i].bottom + more) return i;
        }
        return -1;
    }

    /** A key let go after `held` ms: it does what it says for the player on the glass, shown at once. */
    public void press(int n, long held) {
        if (keysEntity.isEmpty()) return;
        Map<String, Object> saver = App.get().screen().tessera.saver();
        boolean playing = playingWish != null ? playingWish : saver != null && "playing".equals(Json.str(saver, "s"));
        boolean muted = mutedWish != null ? mutedWish : saver != null && Json.dbl(saver, "m") != null;
        Double f = saver == null ? null : Json.dbl(saver, "f");
        int features = f == null ? 0 : f.intValue();
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity_id", keysEntity);
        String service;
        if (n == 2) {
            service = "media_player.media_play_pause";
            playingWish = !playing;
        } else if (n == 1 && held >= MUTE_HOLD_MS && !muted && (features & VOLUME_MUTE) != 0) {
            service = "media_player.volume_mute";
            data.put("is_volume_muted", "true");
            mutedWish = true;
        } else if (muted && (features & VOLUME_MUTE) != 0) {
            // The next tap on a volume key undoes the mute instead of stepping.
            service = "media_player.volume_mute";
            data.put("is_volume_muted", "false");
            mutedWish = false;
        } else {
            service = n == 0 ? "media_player.volume_up" : "media_player.volume_down";
        }
        wishUntil = System.currentTimeMillis() + WISH_MS;
        App.get().screen().perform(service, data, null);
        App.get().log("screensaver key: " + service.substring(service.indexOf('.') + 1) + " on " + keysEntity);
        invalidate();
    }

    private String fit(String s, float width) {
        return TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
