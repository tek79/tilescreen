package app.tilescreen.ui;

import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/**
 * Waste collection (tessera-plugins/plugins/waste_collection 1.0.7): when the next bin goes out, from a calendar in
 * Home Assistant. A tile ("next": the colour and name of what goes out, "Tomorrow" large, the date under it), a card
 * ("upcoming": the collections of four weeks, asked of Home Assistant with calendar.get_events through the app), a tap
 * action that opens that card from a calendar tile, an item for the top bar ("soon": "Tomorrow: Paper") and its two
 * settings, Waste in top bar and Waste days ahead.
 *
 * On a Tessera screen the top bar reads the calendar chosen when the plugin was added; Tessera does not send that choice
 * to a screen, so here the top bar reads the calendar of the first Next collection tile of the layout.
 */
final class WastePlugin extends PluginKit.Plugin {
    WastePlugin() { super("waste_collection", "1.0.7"); }

    @Override
    String[] tiles() { return new String[]{"next"}; }

    /** The colour of a kind of waste by the words collection calendars use (Dutch and English). */
    static int colourOf(String what, int muted) {
        String low = what.toLowerCase(Locale.ROOT);
        if (has(low, "papier", "paper", "karton", "cardboard")) return Style.BLUE;
        if (has(low, "gft", "groen", "green", "garden", "tuin", "bio", "organic", "food", "compost")) return Style.GREEN;
        if (has(low, "plastic", "pmd", "pbd", "packag", "verpakking", "blik")) return Style.AMBER;
        if (has(low, "glas", "glass")) return Style.PURPLE;
        return muted;
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static Map<String, Object> attributes(Map<String, Object> data) {
        Map<String, Object> a = Json.obj(data.get("attributes"));
        return a == null ? new LinkedHashMap<String, Object>() : a;
    }

    // ---- the tile ----

    @Override
    boolean drawTile(String tile, PluginKit.Ctx c) {
        if (!tile.equals("next")) return false;
        if (c.entity.startsWith("calendar.")) firstCalendar = c.entity;
        Map<String, Object> data = c.data(), a = attributes(data);
        String what = a.get("message") instanceof String ? (String) a.get("message") : "";
        long start = a.get("start_time") instanceof Double ? ((Double) a.get("start_time")).longValue() : 0;
        boolean waiting = data.get("wait") instanceof String;
        int days = start > 0 ? PluginKit.daysFromToday(start) : Integer.MIN_VALUE;
        RectF b = c.box;
        float head = c.fs * 1.5f, dot = c.fs * 0.6f;
        if (what.isEmpty() || days < 0) {
            c.words(waiting ? "Asking the calendar" : "Nothing planned", b.left, b.centerY(), c.fs, c.dim, c.regular, Paint.Align.LEFT);
            return true;
        }
        c.fill.setColor(colourOf(what, c.dim));
        c.c.drawCircle(b.left + dot / 2, b.top + head / 2, dot / 2, c.fill);
        c.words(c.fit(what, b.width() - dot * 1.8f, c.text), b.left + dot * 1.6f, b.top + head / 2 + c.fs * 0.36f, c.fs, c.ink, c.medium, Paint.Align.LEFT);
        String when = days == 0 ? "Today" : PluginKit.daysText(days), date = PluginKit.dateText(start);
        float dateH = c.fs * 1.3f;
        float room = b.height() - head - dateH;
        // The largest face the words fit in, between the name and the date.
        float size = Math.min(room * 0.8f, c.fs * 2.6f);
        while (size > c.fs && c.width(when, size, c.light) > b.width()) size -= 1;
        c.words(when, b.left, b.top + head + room / 2 + size * 0.36f, size, c.ink, c.light, Paint.Align.LEFT);
        c.words(c.fit(date, b.width(), c.text), b.left, b.bottom - c.fs * 0.2f, c.fs * 0.85f, c.dim, c.regular, Paint.Align.LEFT);
        return true;
    }

    @Override
    void tapTile(String tile, PluginKit.Ctx c) {
        c.host.openCard(id, "upcoming", c.entity, c.index);
    }

    @Override
    boolean tapAction(String action, Tessera.Tile t, int index, PluginKit.Host h) {
        if (!action.equals("upcoming")) return false;
        h.openCard(id, "upcoming", t.entity, index);
        return true;
    }

    // ---- the card: the coming collections of four weeks ----

    private int asked;
    private String cardFor = "", note = "";
    private final List<Object[]> rows = new ArrayList<Object[]>();
    private long openedAt;

    @Override
    String cardTitle(String card) { return "Coming collections"; }

    @Override
    boolean drawCard(String card, PluginKit.Ctx c) {
        if (!card.equals("upcoming")) return false;
        String calendar = c.entity != null && c.entity.startsWith("calendar.") ? c.entity : firstCalendar;
        if (!calendar.equals(cardFor) || asked == 0 && android.os.SystemClock.uptimeMillis() - openedAt > 15000) {
            // Opened (or for another calendar): ask Home Assistant through the app.
            cardFor = calendar;
            rows.clear();
            openedAt = android.os.SystemClock.uptimeMillis();
            Map<String, Object> request = new LinkedHashMap<String, Object>(), data = new LinkedHashMap<String, Object>(),
                    duration = new LinkedHashMap<String, Object>();
            request.put("ask", "call_service:calendar.get_events");
            data.put("entity_id", calendar);
            duration.put("days", 28.0);
            data.put("duration", duration);
            request.put("data", data);
            asked = calendar.isEmpty() ? 0 : c.host.ask(id, request);
            note = asked == 0 ? "The calendar did not answer" : "Asking the calendar";
        }
        RectF b = c.box;
        if (rows.isEmpty()) {
            c.words(note, b.left, b.top + c.fs * 1.4f, c.fs * 1.05f, c.dim, c.regular, Paint.Align.LEFT);
            if (asked != 0 && note.startsWith("Asking")) c.host.redraw(500);
            return true;
        }
        float line = c.fs * 1.6f, gap = c.fs * 0.6f, dot = c.fs * 0.6f, step = line + gap;
        int fit = Math.max(1, (int) ((b.height() + gap) / step));
        for (int i = 0; i < rows.size() && i < fit; i++) {
            float y = b.top + i * step;
            String what = (String) rows.get(i)[1];
            long day = (Long) rows.get(i)[0];
            int days = PluginKit.daysFromToday(day);
            String when = days == 0 ? "Today" : days == 1 ? "Tomorrow" : PluginKit.dateText(day);
            c.fill.setColor(colourOf(what, c.dim));
            c.c.drawCircle(b.left + dot / 2, y + line / 2, dot / 2, c.fill);
            float ww = c.width(when, c.fs * 0.9f, c.regular);
            c.words(when, b.right, y + line / 2 + c.fs * 0.32f, c.fs * 0.9f, c.dim, c.regular, Paint.Align.RIGHT);
            c.text.setTextSize(c.fs * 1.05f);
            c.words(c.fit(what, b.width() - ww - dot - 3 * c.u, c.text), b.left + dot + 1.5f * c.u, y + line / 2 + c.fs * 0.38f,
                    c.fs * 1.05f, c.ink, c.regular, Paint.Align.LEFT);
        }
        return true;
    }

    /** The answer: {"re", "ok", "result": {"calendar.x": {"events": [{"start", "summary"}, ...]}}}. */
    @Override
    void onMessage(Map<String, Object> m) {
        Double re = Json.dbl(m, "re");
        if (re == null || re.intValue() != asked || asked == 0) return;
        if (!Boolean.TRUE.equals(m.get("ok"))) {
            note = "The calendar did not answer";
            return;
        }
        Map<String, Object> result = Json.obj(m.get("result")), cal = result == null ? null : Json.obj(result.get(cardFor));
        List<Object> events = cal == null ? null : Json.arr(cal.get("events"));
        List<Object[]> found = new ArrayList<Object[]>();
        if (events != null) {
            for (Object o : events) {
                Map<String, Object> e = Json.obj(o);
                if (e == null) continue;
                long day = dayOf(Json.str(e, "start"));
                if (day > 0) found.add(new Object[]{day, Json.str(e, "summary") == null ? "" : Json.str(e, "summary")});
            }
        }
        Collections.sort(found, new Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) { return Long.compare((Long) a[0], (Long) b[0]); }
        });
        synchronized (this) {
            rows.clear();
            rows.addAll(found);
        }
        if (found.isEmpty()) note = "Nothing planned";
    }

    /** Noon of the day of "2026-10-09", "2026-10-09 07:00:00" or "2026-10-09T07:00:00+02:00", in seconds; 0 for none. */
    private static long dayOf(String text) {
        if (text == null || text.length() < 10) return 0;
        try {
            java.util.Calendar k = java.util.Calendar.getInstance();
            k.clear();
            k.set(Integer.parseInt(text.substring(0, 4)), Integer.parseInt(text.substring(5, 7)) - 1,
                    Integer.parseInt(text.substring(8, 10)), 12, 0, 0);
            return k.getTimeInMillis() / 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- the top bar: "Tomorrow: Paper" ----

    private String firstCalendar = "";

    @Override
    PluginKit.Bar bar(String item, Tessera.Layout layout, PluginKit.Host h) {
        PluginKit.Bar out = new PluginKit.Bar();
        if (!item.equals("soon")) return out;
        Tessera.Tile source = null;
        for (Tessera.Tile t : layout.tiles) {
            if (t.entity.equals("plugin:waste_collection.next")) { source = t; break; }
        }
        if (source == null) return out;
        Object pe = source.options.get("pe");
        if (pe instanceof String) firstCalendar = (String) pe;
        if (h.settings().get("waste_in_bar") == 0) return out;
        Map<String, Object> a = attributes(source.extra);
        String what = a.get("message") instanceof String ? (String) a.get("message") : "";
        long start = a.get("start_time") instanceof Double ? ((Double) a.get("start_time")).longValue() : 0;
        if (what.isEmpty() || start == 0) return out;
        int days = PluginKit.daysFromToday(start), ahead = h.settings().get("waste_days_ahead");
        if (days < 0 || days > ahead) return out;
        out.shown = true;
        out.icon = "F044C";
        out.words = (days == 0 ? "Today" : days == 1 ? "Tomorrow" : PluginKit.daysText(days)) + ": " + what;
        out.color = colourOf(what, 0);
        return out;
    }
}
