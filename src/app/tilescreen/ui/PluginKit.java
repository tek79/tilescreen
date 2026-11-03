package app.tilescreen.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.tilescreen.core.Json;
import app.tilescreen.core.ScreenSettings;
import app.tilescreen.core.Tessera;

/**
 * Tessera's plugins, ported to this app one by one (docs/PLUGINS.md). A Tessera plugin is C++ built into a screen's
 * firmware; here each is a class drawing the same parts in the same places. The hello names them with the version of
 * Tessera's release each was ported from, so Tessera's editor, which builds nothing for a screen it has no firmware
 * profile of, records a plugin added there as active and sees from the hello that the tablet runs it.
 *
 * Drawing is immediate, as every tile here: a tile, a card or a screensaver face draws itself whole on every frame and
 * says where its keys are (Host.tap); what a plugin keeps between frames it keeps in its own object.
 */
final class PluginKit {
    private PluginKit() {}

    /** What a plugin reaches of the screen. */
    interface Host {
        Context context();
        /** A key: `run` on a tap inside `r`. */
        void tap(RectF r, Runnable run);
        /** A place a finger drags in: at() on the landing, every move and (done) the lifting. */
        void drag(RectF r, Drag d);
        /** A Home Assistant action through the screen's route (allowed actions), false when it could not go. */
        boolean perform(String service, Map<String, String> data, Map<String, String> templates);
        /** A question to Home Assistant through the app (tessera::send): its number, 0 when it could not go. */
        int ask(String plugin, Map<String, Object> request);
        void openCard(String plugin, String card, String entity, int tile);
        void closeCard();
        /** Draw again after `ms`. */
        void redraw(long ms);
        ScreenSettings settings();
        boolean clock24();
        /** Says something briefly at the foot of the glass. */
        void say(String words);
    }

    interface Drag { void at(float x, float y, boolean done); }

    /** What a tile or card draws with: its room, its tile, the look and the paints. */
    static final class Ctx {
        Canvas c;
        RectF box;
        Tessera.Tile tile;
        int index = -1;
        String entity = "";
        float fs, u, mm;
        int ink, dim, accent, card, raised;
        boolean pastel;
        TextPaint text, glyphs;
        Paint fill, line;
        Typeface regular, medium, light;
        Host host;

        Map<String, Object> options() {
            Map<String, Object> o = tile == null ? null : Json.obj(tile.options.get("plugin"));
            return o == null ? new LinkedHashMap<String, Object>() : o;
        }

        Map<String, Object> data() { return tile == null ? new LinkedHashMap<String, Object>() : tile.extra; }

        String option(String key, String fallback) {
            Object v = options().get(key);
            return v instanceof String ? (String) v : fallback;
        }

        boolean flag(String key, boolean fallback) {
            Object v = options().get(key);
            return v instanceof Boolean ? (Boolean) v : fallback;
        }

        double number(String key, double fallback) {
            Object v = options().get(key);
            return v instanceof Double ? (Double) v : fallback;
        }

        String fit(String s, float width, TextPaint p) {
            if (s == null || width <= 0) return "";
            return TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
        }

        /** Words in a face and size, aligned, in a colour. */
        void words(String s, float x, float y, float size, int color, Typeface face, Paint.Align align) {
            text.setTypeface(face);
            text.setTextSize(size);
            text.setColor(color);
            text.setTextAlign(align);
            c.drawText(s, x, y, text);
            text.setTextAlign(Paint.Align.LEFT);
        }

        float width(String s, float size, Typeface face) {
            text.setTypeface(face);
            text.setTextSize(size);
            return text.measureText(s);
        }

        void icon(String mdiName, float cx, float baseline, float size, int color) {
            glyphs.setColor(color);
            glyphs.setTextSize(size);
            c.drawText(Icons.sure(host.context(), mdiName), cx, baseline, glyphs);
        }
    }

    /** An item of the top bar: an icon (a codepoint of the icon font), words, and a tone, or nothing. */
    static final class Bar {
        boolean shown;
        String icon = "", words = "";
        int color;
    }

    abstract static class Plugin {
        final String id, version;

        Plugin(String id, String version) {
            this.id = id;
            this.version = version;
        }

        String[] tiles() { return new String[0]; }
        String[] savers() { return new String[0]; }

        /** A tile of this plugin: false when it has no such tile. */
        boolean drawTile(String tile, Ctx c) { return false; }
        /** A tap on the tile where none of its keys is. */
        void tapTile(String tile, Ctx c) {}
        /** A card of this plugin over the page: false when it has no such card. */
        boolean drawCard(String card, Ctx c) { return false; }
        String cardTitle(String card) { return ""; }
        /** A face of a screensaver step (`pf`): false when it has no such saver. */
        boolean drawSaver(String saver, Map<String, Object> options, Map<String, Object> contract, Canvas c, int w, int h,
                          float mm, Paints p) { return false; }
        /** A tap action of this plugin set on another tile: false when it has none of that id. */
        boolean tapAction(String action, Tessera.Tile t, int index, Host h) { return false; }
        /** An item of the top bar, from the layout it reads. */
        Bar bar(String item, Tessera.Layout layout, Host h) { return null; }
        /** A finger landed on something that takes it (plugin API 0.11 on_press). */
        void onPress(Host h) {}
        /** A value under the finger passed a step (on_step); `edge` at either end of its range. */
        void onStep(boolean edge, Host h) {}
        /** The app's answer to a question (op plugin). */
        void onMessage(Map<String, Object> m) {}
        /** The screen's settings page: rows of this plugin, or none. */
        List<Object[]> settings() { return null; }

        Map<String, Object> hello() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("version", version);
            List<Object> t = new ArrayList<Object>(), s = new ArrayList<Object>();
            for (String x : tiles()) t.add(x);
            for (String x : savers()) s.add(x);
            m.put("tiles", t);
            m.put("savers", s);
            return m;
        }
    }

    /** The paints a screensaver face draws with. */
    static final class Paints {
        TextPaint text, glyphs;
        Paint fill;
        Typeface regular, medium, light;
        Context context;
    }

    // ---- the register ----

    private static List<Plugin> all;

    static synchronized List<Plugin> all() {
        if (all == null) {
            all = new ArrayList<Plugin>();
            all.add(new FlipClockPlugin());
            all.add(new TapSoundPlugin());
            all.add(new WastePlugin());
            all.add(new ThermoDialPlugin());
            all.add(new AvPanelPlugin());
            List<Map<String, Object>> hello = new ArrayList<Map<String, Object>>();
            for (Plugin p : all) hello.add(p.hello());
            Tessera.plugins = hello;
        }
        return all;
    }

    static Plugin of(String id) {
        for (Plugin p : all()) if (p.id.equals(id)) return p;
        return null;
    }

    /** "plugin:<plugin>.<part>" as {plugin, part}, or null. */
    static String[] key(String s) {
        if (s == null || !s.startsWith("plugin:")) return null;
        String rest = s.substring(7);
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) return null;
        return new String[]{rest.substring(0, dot), rest.substring(dot + 1)};
    }

    // ---- shared bits: days and dates as the screen says them (tessera::days_text, date_text) ----

    static String daysText(int days) {
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        return "In " + days + " days";
    }

    static String dateText(long epochSeconds) {
        return new java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(new java.util.Date(epochSeconds * 1000));
    }

    /** Whole days from today to the day of `epochSeconds` on this tablet's calendar. */
    static int daysFromToday(long epochSeconds) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        b.setTimeInMillis(epochSeconds * 1000);
        for (java.util.Calendar k : new java.util.Calendar[]{a, b}) {
            k.set(java.util.Calendar.HOUR_OF_DAY, 12);
            k.set(java.util.Calendar.MINUTE, 0);
            k.set(java.util.Calendar.SECOND, 0);
            k.set(java.util.Calendar.MILLISECOND, 0);
        }
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
    }
}
