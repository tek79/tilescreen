package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.tilescreen.App;
import app.tilescreen.core.Screen;
import app.tilescreen.core.ScreenSettings;
import app.tilescreen.core.Tessera;

/**
 * The settings page on the screen itself, after Tessera's settings_screen.h (docs/SETTINGS.md): a menu of groups
 * (Brightness, Night, Screen, This screen), each a page of rows. A toggle is a switch, a number or a time has - and +
 * that repeat while held, the rotation is two chips; every change goes through ScreenSettings.set, as Home Assistant's
 * and Tessera's do, so the three never disagree.
 */
final class SettingsPage {
    /** What the page's parts do; the view routes the touches. */
    interface Hits {
        void tap(RectF r, Runnable run);
        /** A key that steps once on a tap and again and again while held; `held` says the hold has gone on a while. */
        void repeat(RectF r, Stepper step);
        /** Everything outside the page's keys: taken, so nothing under the page acts. */
        void block(RectF r);
    }

    interface Stepper { void step(boolean held); }

    interface Host {
        void close();
        void appSettings();
    }

    static final String MENU = "", LIGHT = "light", NIGHT = "night", SCREEN = "screen", ABOUT = "about";
    private static final int TOGGLE = 0, NUMBER = 1, DURATION = 2, MOMENT = 3, ROTATION = 4, INFO = 5, ACTION = 6;

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final android.content.Context context;

    String group = MENU;
    int page;
    /** The action row tapped once, which a second tap within a few seconds runs ("Tap again to restart"). */
    private String armed;
    private long armedUntil;

    SettingsPage(android.content.Context context, TextPaint glyphs) {
        this.context = context;
        this.glyphs = glyphs;
    }

    private static final class Row {
        int kind;
        String label, key, unit = "", icon;
        int low, high, step;
        boolean enabled = true;
        String value;     // an info row's words
        Runnable action;  // an action row's deed
        String again;     // what an action row says before it runs
    }

    private static Row row(int kind, String label, String key) {
        Row r = new Row();
        r.kind = kind;
        r.label = label;
        r.key = key;
        return r;
    }

    private static Row number(String label, String key, int low, int high, int step, String unit) {
        Row r = row(NUMBER, label, key);
        r.low = low;
        r.high = high;
        r.step = step;
        r.unit = unit;
        return r;
    }

    private static Row duration(String label, String key, int low, int high) {
        Row r = row(DURATION, label, key);
        r.low = low;
        r.high = high;
        return r;
    }

    private List<Row> rows(String g, ScreenSettings s, final Host host) {
        List<Row> out = new ArrayList<Row>();
        if (g.equals(LIGHT)) {
            Row bright = number("Brightness", "brightness", 5, 100, 5, "%");
            bright.enabled = s.get("system_brightness") == 0 || s.get("android_saver") != 0;
            out.add(bright);
            out.add(row(TOGGLE, "Dark mode", "dark_mode"));
            Row android = row(TOGGLE, "Use Android screensaver", "android_saver");
            out.add(row(TOGGLE, "Auto standby", "standby_enabled"));
            Row after = duration("Standby after", "standby_seconds", 60, 86400);
            Row dim = number("Standby brightness", "standby_brightness", 0, 100, 5, "%");
            after.enabled = dim.enabled = s.get("standby_enabled") != 0 && s.get("android_saver") == 0;
            out.get(out.size() - 1).enabled = s.get("android_saver") == 0;
            out.add(after);
            out.add(dim);
            Row lift = row(TOGGLE, "Lift to wake", "lift_wake");
            lift.enabled = s.get("android_saver") == 0;
            out.add(lift);
            Row system = row(TOGGLE, "Android brightness", "system_brightness");
            system.enabled = s.get("android_saver") == 0;
            out.add(system);
            Row battery = row(TOGGLE, "Screen off in standby on battery", "battery_dark");
            battery.enabled = s.get("android_saver") == 0;
            out.add(battery);
            out.add(android);
        } else if (g.equals(NIGHT)) {
            out.add(row(TOGGLE, "Night mode", "night_enabled"));
            Row a = row(MOMENT, "Starts", "night_start"), b = row(MOMENT, "Ends", "night_end");
            Row n = number("Night brightness", "night_brightness", 0, 100, 5, "%");
            a.enabled = b.enabled = n.enabled = s.get("night_enabled") != 0;
            out.add(a);
            out.add(b);
            out.add(n);
        } else if (g.equals(SCREEN)) {
            out.add(row(TOGGLE, "Back to Home", "auto_home"));
            Row after = duration("After", "auto_home_seconds", 30, 3600);
            after.enabled = s.get("auto_home") != 0;
            out.add(after);
            out.add(row(TOGGLE, "Also on standby", "home_on_standby"));
            out.add(row(TOGGLE, "Swipe between pages", "swipe_pages"));
            out.add(row(TOGGLE, "Page buttons", "page_buttons"));
            out.add(row(TOGGLE, "Show home button", "home_button"));
            out.add(row(TOGGLE, "Icon toggles, tap opens controls", "icon_toggles"));
            // The settings of the plugins this app draws (tap_sound, waste_collection).
            out.add(row(TOGGLE, "Tap sound", "tap_sound"));
            out.add(row(TOGGLE, "Waste in top bar", "waste_in_bar"));
            out.add(number("Waste days ahead", "waste_days_ahead", 0, 3, 1, ""));
            out.add(row(ROTATION, "Rotation", "rotation"));
        } else if (g.equals(ABOUT)) {
            App app = App.get();
            Screen screen = app.screen();
            out.add(info("Screen", app.friendly()));
            out.add(info("Address", app.ip().isEmpty() ? "No address" : app.ip()));
            out.add(info("Wi-Fi", app.wifiText()));
            out.add(info("Firmware", Tessera.FIRMWARE + " · Tile Screen " + app.version()));
            int clients = screen.device.clients();
            out.add(info("Home Assistant", clients == 0 ? "Not connected"
                    : screen.device.actionSubscribers() == 0 ? "Connected, actions not allowed" : "Connected"));
            out.add(info("Tessera", !screen.tessera.shown() ? "Waiting for a layout" : screen.tessera.fresh() ? "Synced" : "Not heard from lately"));
            Row tablet = row(ACTION, "Tablet settings", null);
            tablet.icon = "tablet-cellphone";
            tablet.action = new Runnable() {
                public void run() { host.appSettings(); }
            };
            out.add(tablet);
            Row restart = row(ACTION, "Restart", "restart");
            restart.icon = "restart";
            restart.again = "Tap again to restart";
            restart.action = new Runnable() {
                public void run() {
                    host.close();
                    App.get().restart();
                }
            };
            out.add(restart);
        }
        return out;
    }

    private static Row info(String label, String value) {
        Row r = row(INFO, label, null);
        r.value = value;
        return r;
    }

    // ---- the words of a value
    static String duration(int seconds) {
        if (seconds < 60) return seconds + " s";
        if (seconds < 3600) return seconds % 60 == 0 ? seconds / 60 + " min" : seconds / 60 + " min " + seconds % 60 + " s";
        int h = seconds / 3600, m = seconds % 3600 / 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    static String moment(int minutes, boolean h24) {
        int h = minutes / 60, m = minutes % 60;
        if (h24) return String.format(Locale.US, "%02d:%02d", h, m);
        return String.format(Locale.US, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "AM" : "PM");
    }

    /** How coarse a duration steps (ladder_step): seconds near the bottom, quarters of an hour at the top. */
    static int ladder(int seconds) {
        if (seconds < 300) return 30;
        if (seconds < 900) return 60;
        if (seconds < 3600) return 300;
        if (seconds < 7200) return 900;
        return 1800;
    }

    /** One press of - or + (settings_screen::stepped): times wrap round midnight, the rest stop at their ends. */
    private static int stepped(Row r, int value, int direction, boolean held) {
        if (r.kind == MOMENT) {
            int step = held ? 60 : 15;
            int next = held && value % 60 != 0 ? (value / 60) * 60 + (direction > 0 ? 60 : 0) : value + direction * step;
            next %= 1440;
            return next < 0 ? next + 1440 : next;
        }
        int step = r.kind == DURATION ? ladder(direction < 0 ? value - 1 : value) : r.step;
        return Math.max(r.low, Math.min(r.high, value + direction * step));
    }

    private String valueText(Row r, int v, boolean h24) {
        if (r.kind == DURATION) return duration(v);
        if (r.kind == MOMENT) return moment(v, h24);
        return v + r.unit;
    }

    // ---- drawing
    private void words(Canvas c, String s, float x, float baseline, float size, int color, Typeface f, Paint.Align a) {
        text.setTypeface(f);
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(a);
        c.drawText(s, x, baseline, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private String fit(String s, float w, float size, Typeface f) {
        text.setTypeface(f);
        text.setTextSize(size);
        return TextUtils.ellipsize(s == null ? "" : s, text, Math.max(1, w), TextUtils.TruncateAt.END).toString();
    }

    private void pill(Canvas c, RectF r, int color) {
        fill.setColor(color);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
    }

    void draw(Canvas c, int w, int h, float u, boolean h24, final Host host, final Hits hits) {
        final ScreenSettings s = App.get().screen().screenSettings;
        fill.setColor(Style.BG);
        c.drawRect(0, 0, w, h, fill);
        hits.block(new RectF(0, 0, w, h));
        float fs = 4.2f * u, pad = 4 * u, barH = 12 * u;
        // The top bar: the back key (the menu's closes the page), the title.
        String title = group.equals(LIGHT) ? "Brightness" : group.equals(NIGHT) ? "Night" : group.equals(SCREEN) ? "Screen"
                : group.equals(ABOUT) ? "This screen" : "Settings";
        RectF back = new RectF(pad, 2 * u, pad + 10 * u, 2 * u + 10 * u);
        pill(c, back, Style.RAISED);
        glyphs.setColor(Style.TEXT);
        glyphs.setTextSize(6 * u);
        c.drawText(Icons.sure(context, group.equals(MENU) ? "close" : "arrow-left"), back.centerX(), back.centerY() + 2.1f * u, glyphs);
        hits.tap(back, new Runnable() {
            public void run() {
                if (group.equals(MENU)) host.close();
                else {
                    group = MENU;
                    page = 0;
                }
            }
        });
        words(c, title, back.right + 3 * u, back.centerY() + fs * 0.42f, fs * 1.25f, Style.TEXT, medium, Paint.Align.LEFT);

        float top = barH + 2 * u, bottom = h - pad;
        float side = Math.min(w - 2 * pad, 150 * u), left = (w - side) / 2;
        if (group.equals(MENU)) {
            // The groups, each a card with its icon, its name and a line of what it holds.
            final String[][] groups = {
                    {LIGHT, "brightness-6", "Brightness", "Brightness, dark mode, standby"},
                    {NIGHT, "weather-night", "Night", "Darker between two times"},
                    {SCREEN, "gesture-tap", "Screen", "Back to Home, swiping, buttons, rotation"},
                    {ABOUT, "information-outline", "This screen", "Address, Wi-Fi, Home Assistant, Tessera"}};
            boolean twoUp = w * 2 > h * 3;
            int cols = twoUp ? 2 : 1;
            float gap = 2 * u, cw = (side - (cols - 1) * gap) / cols;
            float ch = Math.min(22 * u, (bottom - top - (4 / cols - 1) * gap) / (4 / cols));
            float blockH = (4 / cols) * ch + (4 / cols - 1) * gap, y0 = top + Math.max(0, (bottom - top - blockH) / 2);
            for (int i = 0; i < groups.length; i++) {
                final String[] g = groups[i];
                float x = left + (i % cols) * (cw + gap), y = y0 + (i / cols) * (ch + gap);
                RectF card = new RectF(x, y, x + cw, y + ch);
                fill.setColor(Style.CARD);
                c.drawRoundRect(card, 3 * u, 3 * u, fill);
                float ic = Math.min(ch * 0.55f, 11 * u);
                fill.setColor(Style.RAISED);
                c.drawCircle(card.left + pad + ic / 2, card.centerY(), ic / 2, fill);
                glyphs.setColor(Style.TEXT);
                glyphs.setTextSize(ic * 0.55f);
                c.drawText(Icons.sure(context, g[1]), card.left + pad + ic / 2, card.centerY() + ic * 0.2f, glyphs);
                float tx = card.left + pad * 1.6f + ic;
                words(c, g[2], tx, card.centerY() - fs * 0.15f, fs * 1.1f, Style.TEXT, medium, Paint.Align.LEFT);
                words(c, fit(g[3], card.right - tx - pad * 2, fs * 0.85f, regular), tx, card.centerY() + fs * 1.05f, fs * 0.85f, Style.DIM, regular, Paint.Align.LEFT);
                glyphs.setColor(Style.DIM);
                glyphs.setTextSize(fs * 1.3f);
                c.drawText(Icons.sure(context, "chevron-right"), card.right - pad, card.centerY() + fs * 0.45f, glyphs);
                hits.tap(card, new Runnable() {
                    public void run() {
                        group = g[0];
                        page = 0;
                    }
                });
            }
            return;
        }

        final List<Row> rows = rows(group, s, host);
        float rowH = Math.max(9.5f * u, Math.min(14 * u, (bottom - top) / Math.max(1, rows.size())));
        int per = Math.max(1, (int) ((bottom - top) / rowH));
        float pagerH = 0;
        if (rows.size() > per) {
            pagerH = 10 * u;
            per = Math.max(1, (int) ((bottom - top - pagerH) / rowH));
        }
        final int pages = (rows.size() + per - 1) / per;
        page = Math.max(0, Math.min(pages - 1, page));
        int first = page * per, last = Math.min(rows.size(), first + per);
        float blockH = (last - first) * rowH, y = top + (pagerH == 0 ? Math.max(0, (bottom - top - blockH) / 2) : 0);
        RectF card = new RectF(left, y, left + side, y + blockH);
        fill.setColor(Style.CARD);
        c.drawRoundRect(card, 3 * u, 3 * u, fill);
        long now = System.currentTimeMillis();
        if (now > armedUntil) armed = null;
        for (int i = first; i < last; i++) {
            final Row r = rows.get(i);
            RectF band = new RectF(card.left + pad, y, card.right - pad, y + rowH);
            if (i > first) {
                fill.setColor(Style.LINE);
                c.drawRect(band.left, y, band.right, y + 1, fill);
            }
            int ink = r.enabled ? Style.TEXT : Style.FAINT;
            float mid = band.centerY() + fs * 0.35f;
            String label = r.kind == ACTION && r.again != null && r.key.equals(armed) ? r.again : r.label;
            float labelW = band.width() * (r.kind == INFO ? 0.4f : 0.5f);
            if (r.kind == ACTION) {
                glyphs.setColor(ink);
                glyphs.setTextSize(fs * 1.2f);
                c.drawText(Icons.sure(context, r.icon), band.left + fs * 0.6f, band.centerY() + fs * 0.42f, glyphs);
                words(c, label, band.left + fs * 1.8f, mid, fs, r.key != null && r.key.equals(armed) ? Style.RED : ink, medium, Paint.Align.LEFT);
                hits.tap(band, new Runnable() {
                    public void run() {
                        if (r.again != null && !r.key.equals(armed)) {
                            armed = r.key;
                            armedUntil = System.currentTimeMillis() + 4000;
                            return;
                        }
                        armed = null;
                        r.action.run();
                    }
                });
                y += rowH;
                continue;
            }
            words(c, fit(label, labelW, fs, regular), band.left, mid, fs, ink, regular, Paint.Align.LEFT);
            if (r.kind == INFO) {
                words(c, fit(r.value, band.width() - labelW - 2 * u, fs * 0.95f, regular), band.right, mid, fs * 0.95f, Style.DIM, regular, Paint.Align.RIGHT);
            } else if (r.kind == TOGGLE) {
                final boolean on = s.get(r.key) != 0;
                float sw = 13 * u, sh = 7.2f * u;
                RectF track = new RectF(band.right - sw, band.centerY() - sh / 2, band.right, band.centerY() + sh / 2);
                pill(c, track, on && r.enabled ? Style.BLUE : Style.RAISED);
                fill.setColor(r.enabled ? 0xFFFFFFFF : Style.LINE);
                float knob = sh / 2 - 0.7f * u;
                c.drawCircle(on ? track.right - sh / 2 : track.left + sh / 2, track.centerY(), knob, fill);
                if (r.enabled) hits.tap(band, new Runnable() {
                    public void run() { s.set(r.key, on ? 0 : 1); }
                });
            } else if (r.kind == ROTATION) {
                final int turn = s.get("rotation");
                String[] chips = {"0°", "180°"};
                float cwid = 17 * u, chh = 7.5f * u, gap = 1.2f * u;
                for (int k = 0; k < 2; k++) {
                    final int value = k * 180;
                    RectF chip = new RectF(band.right - (2 - k) * cwid - (1 - k) * gap, band.centerY() - chh / 2,
                            band.right - (1 - k) * (cwid + gap), band.centerY() + chh / 2);
                    boolean chosen = turn == value;
                    pill(c, chip, chosen ? Style.BLUE : Style.RAISED);
                    words(c, chips[k], chip.centerX(), chip.centerY() + fs * 0.33f, fs * 0.9f, chosen ? Style.DARK_TEXT : Style.TEXT, medium, Paint.Align.CENTER);
                    hits.tap(chip, new Runnable() {
                        public void run() { s.set("rotation", value); }
                    });
                }
            } else {
                // - and + around the value; they repeat while held, with bigger steps once the hold goes on.
                final int v = s.get(r.key);
                float kw = 11 * u, kh = 7.5f * u, valueW = 22 * u;
                RectF plus = new RectF(band.right - kw, band.centerY() - kh / 2, band.right, band.centerY() + kh / 2);
                RectF minus = new RectF(plus.left - valueW - kw, plus.top, plus.left - valueW, plus.bottom);
                boolean lowEnd = r.kind != MOMENT && stepped(r, v, -1, false) == v, highEnd = r.kind != MOMENT && stepped(r, v, 1, false) == v;
                pill(c, minus, Style.RAISED);
                pill(c, plus, Style.RAISED);
                glyphs.setTextSize(fs * 1.3f);
                glyphs.setColor(r.enabled && !lowEnd ? Style.TEXT : Style.FAINT);
                c.drawText(Icons.sure(context, "minus"), minus.centerX(), minus.centerY() + fs * 0.45f, glyphs);
                glyphs.setColor(r.enabled && !highEnd ? Style.TEXT : Style.FAINT);
                c.drawText(Icons.sure(context, "plus"), plus.centerX(), plus.centerY() + fs * 0.45f, glyphs);
                words(c, valueText(r, v, h24), (minus.right + plus.left) / 2, mid, fs, ink, medium, Paint.Align.CENTER);
                if (r.enabled) {
                    hits.repeat(minus, new Stepper() {
                        public void step(boolean held) { s.set(r.key, stepped(r, s.get(r.key), -1, held)); }
                    });
                    hits.repeat(plus, new Stepper() {
                        public void step(boolean held) { s.set(r.key, stepped(r, s.get(r.key), 1, held)); }
                    });
                }
            }
            y += rowH;
        }
        if (pages > 1) {
            float ky = bottom - 8 * u;
            RectF prev = new RectF(left, ky, left + 20 * u, bottom), next = new RectF(left + side - 20 * u, ky, left + side, bottom);
            pill(c, prev, page > 0 ? Style.RAISED : Style.CARD);
            pill(c, next, page < pages - 1 ? Style.RAISED : Style.CARD);
            words(c, "‹", prev.centerX(), prev.centerY() + fs * 0.4f, fs * 1.3f, page > 0 ? Style.TEXT : Style.FAINT, medium, Paint.Align.CENTER);
            words(c, "›", next.centerX(), next.centerY() + fs * 0.4f, fs * 1.3f, page < pages - 1 ? Style.TEXT : Style.FAINT, medium, Paint.Align.CENTER);
            words(c, (page + 1) + " / " + pages, left + side / 2, prev.centerY() + fs * 0.33f, fs * 0.9f, Style.DIM, regular, Paint.Align.CENTER);
            hits.tap(prev, new Runnable() {
                public void run() { page--; }
            });
            hits.tap(next, new Runnable() {
                public void run() { page++; }
            });
        }
    }
}
