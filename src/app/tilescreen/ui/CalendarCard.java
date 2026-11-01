package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.tilescreen.App;
import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/**
 * The calendar card (firmware 0.56.0, docs/CALENDAR.md, calendar_card.h): a Home Assistant calendar, or several on one
 * card, that shows more as it gets more room - Now, Up next, Agenda, Columns, Week - and a full screen a tap opens with
 * Up next, Day, Week and Month. "Free or busy" says Free, Starts soon or Busy; "Status and booking" is a room's sign
 * with keys that book it. The events come filtered from the add-on (calendar_feed.card, the tile's `x.cal`), as minutes
 * from today 00:00 in Home Assistant's time zone.
 */
final class CalendarCard {
    interface Hits { void tap(RectF r, Runnable run); }

    static final int ALL_DAY = 1, TENTATIVE = 2, PRIVATE = 4;
    private static final int FREE = 0xFF4CAF50, BUSY = 0xFFF44336, AMBER = 0xFFFFC107, NOW_LINE = 0xFFE53935;
    private static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] DAYS_LONG = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final android.content.Context context;

    CalendarCard(android.content.Context context, TextPaint glyphs) {
        this.context = context;
        this.glyphs = glyphs;
    }

    // ---- the data (page_receiver.cpp reads the same `x.cal`)
    static final class Ev {
        int s, e, flags, cal;
        String title = "", place = "";

        boolean allDay() { return (flags & ALL_DAY) != 0; }

        /** A timed event of a day or more stands with the all-day ones. */
        boolean banded() { return allDay() || e - s >= 1440; }

        boolean on(int day) { return s < (day + 1) * 1440 && e > day * 1440; }
    }

    static final class Data {
        int year, month, day, weekday, from, to = 7, leftOut, glanceFrom;
        final List<Integer> colors = new ArrayList<Integer>();
        final List<String> names = new ArrayList<String>();
        final List<Ev> events = new ArrayList<Ev>();
        String notes = "", bookOn = "", bookPlace = "", bookTitle = "";
        int[] glance = new int[0];
        /** Events come short (`a`, calendar_detail): a tap asks the app for the whole title, place and notes. */
        boolean compact;

        /** The day the screen calls today, as the app reads it in a question: 2026-10-31. */
        String today() { return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", year, month, day); }
    }

    /** Asks the app for the rest of a short event (esphome.screen_calendar). */
    interface Asker { void ask(int cal, int start, int end, String day); }

    Asker asker;
    /** The whole title, place and notes of events the app answered for, by "calendar:start:end"; null values for gone. */
    java.util.Map<String, String[]> details = new java.util.HashMap<String, String[]>();

    static Data parse(Map<String, Object> x) {
        Map<String, Object> c = x == null ? null : Json.obj(x.get("cal"));
        if (c == null) return null;
        Data d = new Data();
        List<Object> day = Json.arr(c.get("d"));
        if (day != null && day.size() >= 4) {
            d.year = num(day.get(0));
            d.month = Math.max(1, Math.min(12, num(day.get(1))));
            d.day = Math.max(1, Math.min(31, num(day.get(2))));
            d.weekday = Math.max(0, Math.min(6, num(day.get(3))));
        }
        List<Object> r = Json.arr(c.get("r"));
        if (r != null && r.size() >= 2) {
            d.from = Math.max(-62, Math.min(0, num(r.get(0))));
            d.to = Math.max(0, Math.min(62, num(r.get(1))));
        }
        List<Object> cals = Json.arr(c.get("c"));
        if (cals != null) for (Object o : cals) {
            List<Object> k = Json.arr(o);
            if (k == null || k.size() < 2 || d.colors.size() >= 8) continue;
            d.colors.add(0xFF000000 | (num(k.get(0)) & 0xFFFFFF));
            d.names.add(String.valueOf(k.get(1)));
        }
        List<Object> ev = Json.arr(c.get("e"));
        if (ev != null) for (Object o : ev) {
            List<Object> e = Json.arr(o);
            if (e == null || e.size() < 4 || d.events.size() >= 80) continue;
            Ev v = new Ev();
            v.s = num(e.get(0));
            v.e = Math.max(v.s + 1, num(e.get(1)));
            v.flags = num(e.get(2));
            v.cal = Math.max(0, Math.min(7, num(e.get(3))));
            v.title = e.size() > 4 && e.get(4) != null ? String.valueOf(e.get(4)) : "";
            v.place = e.size() > 5 && e.get(5) != null ? String.valueOf(e.get(5)) : "";
            d.events.add(v);
        }
        java.util.Collections.sort(d.events, new java.util.Comparator<Ev>() {
            public int compare(Ev a, Ev b) { return a.s != b.s ? Integer.compare(a.s, b.s) : Integer.compare(a.e, b.e); }
        });
        Integer m = Json.integer(c, "m");
        d.leftOut = m == null ? 0 : Math.max(0, m);
        String n = Json.str(c, "n");
        d.notes = n == null ? "" : n;
        Integer a = Json.integer(c, "a");
        d.compact = a != null && a == 1;
        List<Object> g = Json.arr(c.get("g"));
        if (g != null && g.size() >= 2) {
            d.glanceFrom = Math.max(-62, Math.min(0, num(g.get(0))));
            List<Object> masks = Json.arr(g.get(1));
            if (masks != null) {
                d.glance = new int[Math.min(62, masks.size())];
                for (int i = 0; i < d.glance.length; i++) d.glance[i] = num(masks.get(i));
            }
        }
        List<Object> b = Json.arr(c.get("b"));
        if (b != null && b.size() >= 3 && String.valueOf(b.get(0)).startsWith("calendar.")) {
            d.bookOn = String.valueOf(b.get(0));
            d.bookPlace = b.get(1) == null ? "" : String.valueOf(b.get(1));
            d.bookTitle = b.get(2) == null ? "" : String.valueOf(b.get(2));
        }
        return d;
    }

    private static int num(Object o) { return o instanceof Double ? ((Double) o).intValue() : 0; }

    // ---- the card's choices (catalogue/calendar.yaml, calendar_feed.KEYS the screen reads)
    static final class Options {
        boolean busyFree, room, fromMonday, weekNumbers, h24;
        int soon, hoursFrom, hoursTo;
        String startView = "";
    }

    static Options options(Tessera.Tile t, boolean h24) {
        Options o = new Options();
        Map<String, Object> m = t.options;
        String style = Json.str(m, "style");
        o.busyFree = "busy_free".equals(style);
        o.room = "room".equals(style);
        Integer soon = Json.integer(m, "soon");
        o.soon = soon == null ? 0 : soon;
        String sv = Json.str(m, "start_view");
        o.startView = sv == null ? "" : sv;
        o.fromMonday = "monday".equals(Json.str(m, "week_start"));
        List<Object> hours = Json.arr(m == null ? null : m.get("hours"));
        if (hours != null && hours.size() == 2) {
            o.hoursFrom = num(hours.get(0));
            o.hoursTo = num(hours.get(1));
        }
        o.weekNumbers = Boolean.TRUE.equals(m == null ? null : m.get("week_numbers"));
        o.h24 = h24;
        return o;
    }

    // ---- the moment: minutes from Tessera's today 00:00
    static int now(Data d) {
        Calendar c = Calendar.getInstance();
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        // The add-on's today, against the tablet's: a card sent before midnight still counts from its own today.
        Calendar today = Calendar.getInstance();
        today.set(d.year, d.month - 1, d.day, 0, 0, 0);
        today.set(Calendar.MILLISECOND, 0);
        long days = Math.round((startOfDay(c) - today.getTimeInMillis()) / 86400000.0);
        return (int) days * 1440 + minute;
    }

    private static long startOfDay(Calendar c) {
        Calendar s = (Calendar) c.clone();
        s.set(Calendar.HOUR_OF_DAY, 0);
        s.set(Calendar.MINUTE, 0);
        s.set(Calendar.SECOND, 0);
        s.set(Calendar.MILLISECOND, 0);
        return s.getTimeInMillis();
    }

    private static int dayOf(int minute) { return minute >= 0 ? minute / 1440 : -((-minute + 1439) / 1440); }

    /** The date of a day from Tessera's today: {year, month 1-12, day, weekday 0 = Monday}. */
    static int[] date(Data d, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(d.year, d.month - 1, d.day);
        c.add(Calendar.DAY_OF_MONTH, day);
        int wd = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        return new int[]{c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), wd};
    }

    static String clock(int minute, boolean h24) {
        int m = ((minute % 1440) + 1440) % 1440, h = m / 60;
        if (h24) return String.format(Locale.US, "%02d:%02d", h, m % 60);
        return m % 60 == 0 ? (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM")
                : String.format(Locale.US, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m % 60, h < 12 ? "AM" : "PM");
    }

    static String duration(int minutes) {
        if (minutes >= 1440 && minutes % 1440 == 0) return minutes / 1440 + " d";
        if (minutes < 60) return minutes + " min";
        return minutes % 60 == 0 ? minutes / 60 + " h" : minutes / 60 + " h " + minutes % 60 + " min";
    }

    private String dayName(Data d, int day) {
        if (day == 0) return "Today";
        if (day == 1) return "Tomorrow";
        int[] dt = date(d, day);
        return DAYS_LONG[dt[3]] + " " + dt[2] + " " + MONTHS[dt[1] - 1].substring(0, 3);
    }

    private String longDay(Data d, int day) {
        int[] dt = date(d, day);
        return DAYS_LONG[dt[3]] + " " + dt[2] + " " + MONTHS[dt[1] - 1];
    }

    /** "3:00 – 4:30 PM", or with days for an event over midnight ("Wed 22:00 – Thu 06:00"), or "All day". */
    private String when(Data d, Ev e, boolean h24) {
        if (e.allDay()) {
            int days = Math.max(1, (e.e - e.s) / 1440);
            return days == 1 ? "All day" : "All day · " + days + " days";
        }
        int a = dayOf(e.s), b = dayOf(e.e - 1);
        if (a == b) return clock(e.s, h24) + " – " + clock(e.e, h24);
        return DAYS[date(d, a)[3]] + " " + clock(e.s, h24) + " – " + DAYS[date(d, b)[3]] + " " + clock(e.e, h24);
    }

    private String title(Ev e) { return (e.flags & PRIVATE) != 0 || e.title.isEmpty() ? "Busy" : e.title; }

    // ---- the status: the event of the moment and the next one
    private static int running(Data d, int t) {
        for (int i = 0; i < d.events.size(); i++) {
            Ev e = d.events.get(i);
            if (!e.allDay() && e.s <= t && t < e.e) return i;
        }
        return -1;
    }

    private static int next(Data d, int t) {
        for (int i = 0; i < d.events.size(); i++) {
            Ev e = d.events.get(i);
            if (!e.allDay() && e.s > t) return i;
        }
        for (int i = 0; i < d.events.size(); i++) if (d.events.get(i).s > t) return i;
        return -1;
    }

    private int color(Data d, int cal) { return cal < d.colors.size() ? d.colors.get(cal) : Style.BLUE; }

    private static int block(int color) { return (color & 0xFFFFFF) | (Style.BG < 0xFF808080 ? 0x55000000 : 0x3A000000); }

    // ---- drawing helpers
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

    private float width(String s, float size, Typeface f) {
        text.setTypeface(f);
        text.setTextSize(size);
        return text.measureText(s);
    }

    private void box(Canvas c, float l, float t, float r, float b, int color, float radius) {
        fill.setColor(color);
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, fill);
    }

    // =================================================================================================== the tile

    /**
     * The tile: its view by the room it has. `mm` is a millimetre of glass in pixels. Returns the rectangle a tap
     * books in (a room's keys are their own hits) or nothing; a tap anywhere else opens the full screen.
     */
    void drawTile(Canvas c, Tessera.Tile t, RectF box, float fs, float mm, int ink, int muted, int card, boolean h24, Hits hits,
                  final Booker booker) {
        Data d = parse(t.extra);
        if (d == null) {
            words(c, "Waiting for the events…", box.centerX(), box.centerY(), fs * 0.85f, muted, regular, Paint.Align.CENTER);
            return;
        }
        Options o = options(t, h24);
        int now = now(d);
        float W = box.width(), H = box.height();
        float headH = fs * 2.6f;
        if (o.room && H >= headH * 2) {
            room(c, d, o, t, box, fs, ink, muted, now, hits, booker);
            return;
        }
        // The ladder: Week where four days of 16 mm and six readable hours fit, Columns on a wide card, Agenda with
        // room for a list, Up next with room for a block, else Now.
        boolean week = W >= 4 * 16 * mm + 6 * mm && H >= headH + fs * 1.6f + 6 * fs * 1.5f;
        boolean columns = !week && W > H * 1.7f && W >= 3 * 22 * mm && H >= headH + fs * 4;
        boolean agenda = !week && !columns && H >= headH + fs * 3.2f + 3 * fs * 2.0f;
        boolean upNext = !week && !columns && !agenda && H >= headH + fs * 3.2f;
        int run = running(d, now), nx = next(d, now);
        if (week) {
            head(c, d, o, t, box.left, box.top, W, headH, fs, ink, muted, now, false);
            weekGrid(c, d, o, new RectF(box.left, box.top + headH + fs * 0.4f, box.right, box.bottom), fs * 0.85f, ink, muted, card, now,
                     o.fromMonday ? -d.weekday : 0, Math.min(7, (int) (W / (16 * mm))), null);
        } else if (columns) {
            head(c, d, o, t, box.left, box.top, W, headH, fs, ink, muted, now, false);
            columns(c, d, o, new RectF(box.left, box.top + headH + fs * 0.4f, box.right, box.bottom), fs, ink, muted, now,
                    Math.min(4, Math.max(2, (int) (W / (22 * mm)))));
        } else if (agenda) {
            head(c, d, o, t, box.left, box.top, W, headH, fs, ink, muted, now, false);
            float uy = box.top + headH + fs * 0.4f, uh = fs * 3.4f;
            upNext(c, d, o, new RectF(box.left, uy, box.right, uy + uh), fs, ink, muted, now);
            list(c, d, o, new RectF(box.left, uy + uh + fs * 0.5f, box.right, box.bottom), fs * 0.9f, ink, muted, now, run >= 0 ? run : nx, null);
        } else if (upNext) {
            head(c, d, o, t, box.left, box.top, W, headH, fs, ink, muted, now, false);
            upNext(c, d, o, new RectF(box.left, box.top + headH + fs * 0.4f, box.right, box.bottom), fs, ink, muted, now);
        } else {
            // Now: the head alone, with a thin bar of how far the event has come.
            float y0 = box.top + Math.max(0, (H - headH - fs * 0.9f) / 2);
            head(c, d, o, t, box.left, y0, W, headH, fs, ink, muted, now, true);
            if (run >= 0) {
                Ev e = d.events.get(run);
                float frac = Math.max(0, Math.min(1, (now - e.s) / (float) Math.max(1, e.e - e.s)));
                float by = Math.min(box.bottom - fs * 0.3f, y0 + headH + fs * 0.4f), bh = Math.max(3, fs * 0.25f);
                box(c, box.left, by, box.right, by + bh, Style.RAISED, bh / 2);
                box(c, box.left, by, box.left + Math.max(bh, W * frac), by + bh, color(d, e.cal), bh / 2);
            }
        }
    }

    /** The head: the round icon (busy in amber while a timed event runs), and two lines. */
    private void head(Canvas c, Data d, Options o, Tessera.Tile t, float x, float y, float W, float h, float fs, int ink, int muted,
                      int now, boolean brief) {
        int run = running(d, now), nx = next(d, now);
        boolean busy = run >= 0;
        float ic = Math.min(h, fs * 2.4f);
        fill.setColor(busy ? (AMBER & 0xFFFFFF) | 0x44000000 : Style.RAISED);
        c.drawCircle(x + ic / 2, y + h / 2, ic / 2, fill);
        glyphs.setColor(busy ? AMBER : muted);
        glyphs.setTextSize(ic * 0.55f);
        c.drawText(Icons.sure(context, busy ? "calendar-check" : "calendar-blank"), x + ic / 2, y + h / 2 + ic * 0.2f, glyphs);
        String first, second;
        boolean soon = nx >= 0 && o.soon > 0 && d.events.get(nx).s - now <= o.soon && d.events.get(nx).s > now;
        if (o.busyFree || o.room) {
            first = busy ? "Busy" : soon ? "Starts soon" : "Free";
            if (busy) second = "until " + clock(d.events.get(run).e, o.h24);
            else if (nx >= 0) second = "Next " + nextWhen(d, d.events.get(nx), now, o.h24);
            else second = "Nothing planned";
        } else if (brief) {
            if (busy) {
                first = title(d.events.get(run));
                second = "until " + clock(d.events.get(run).e, o.h24);
            } else {
                first = "Free";
                second = nx >= 0 ? title(d.events.get(nx)) + " · " + nextWhen(d, d.events.get(nx), now, o.h24) : "Nothing planned";
            }
        } else {
            first = t.name.isEmpty() ? "Calendar" : t.name;
            second = longDay(d, dayOf(now));
        }
        float tx = x + ic + fs * 0.6f, tw = x + W - tx;
        words(c, fit(first, tw, fs, medium), tx, y + h / 2 - fs * 0.1f, fs, o.busyFree && !busy && !soon ? FREE : o.busyFree && busy ? BUSY : ink, medium, Paint.Align.LEFT);
        words(c, fit(second, tw, fs * 0.8f, regular), tx, y + h / 2 + fs * 0.85f, fs * 0.8f, muted, regular, Paint.Align.LEFT);
    }

    /** When the next one is: "at 3 PM", "Tomorrow 9 AM", "Next Wed 4 Nov". */
    private String nextWhen(Data d, Ev e, int now, boolean h24) {
        int day = dayOf(e.s), today = dayOf(now);
        if (day == today) return e.allDay() ? "today" : "at " + clock(e.s, h24);
        if (day == today + 1) return "Tomorrow" + (e.allDay() ? "" : " " + clock(e.s, h24));
        int[] dt = date(d, day);
        if (day < today + 7) return DAYS[dt[3]] + (e.allDay() ? "" : " " + clock(e.s, h24));
        return DAYS[dt[3]] + " " + dt[2] + " " + MONTHS[dt[1] - 1].substring(0, 3);
    }

    /** Up next: one block in the event's colour, its title as large as fits, its time and place under it. */
    private void upNext(Canvas c, Data d, Options o, RectF r, float fs, int ink, int muted, int now) {
        int run = running(d, now), nx = next(d, now);
        int i = run >= 0 ? run : nx;
        if (i < 0) {
            box(c, r.left, r.top, r.right, r.bottom, Style.RAISED, fs * 0.6f);
            words(c, "Nothing planned", r.centerX(), r.centerY() + fs * 0.35f, fs, muted, regular, Paint.Align.CENTER);
            return;
        }
        Ev e = d.events.get(i);
        int col = color(d, e.cal);
        box(c, r.left, r.top, r.right, r.bottom, block(col), fs * 0.6f);
        box(c, r.left, r.top, r.left + Math.max(4, fs * 0.3f), r.bottom, col, fs * 0.15f);
        float pad = fs * 0.8f, tw = r.width() - pad * 2;
        String line2 = run >= 0 ? when(d, e, o.h24) : nextWhen(d, e, now, o.h24) + (e.s > now && e.s - now < 1440 && !e.allDay() ? " · in " + duration(e.s - now) : "");
        boolean withPlace = !e.place.isEmpty() && (e.flags & PRIVATE) == 0 && r.height() > fs * 5;
        float big = Math.min(fs * 1.6f, (r.height() - fs * (withPlace ? 2.4f : 1.4f)) * 0.75f);
        big = Math.max(fs * 0.9f, big);
        String t = title(e);
        if (width(t, big, medium) > tw) big = Math.max(fs * 0.9f, big * tw / width(t, big, medium));
        float y = r.top + pad * 0.6f + big;
        words(c, fit(t, tw, big, medium), r.left + pad, y, big, ink, medium, Paint.Align.LEFT);
        words(c, fit(line2, tw, fs * 0.8f, regular), r.left + pad, y + fs * 1.1f, fs * 0.8f, muted, regular, Paint.Align.LEFT);
        if (withPlace) words(c, fit(e.place, tw, fs * 0.8f, regular), r.left + pad, y + fs * 2.1f, fs * 0.8f, muted, regular, Paint.Align.LEFT);
    }

    /** The agenda: what follows, grouped by day, the rest one quiet line. `hits` makes each row open its sheet. */
    private void list(Canvas c, Data d, Options o, RectF r, float fs, int ink, int muted, int now, int skip, EventTaps taps) {
        float rowH = fs * 1.9f, headH = fs * 1.5f, y = r.top;
        int lastDay = Integer.MIN_VALUE, shown = 0, rest = 0;
        for (int i = 0; i < d.events.size(); i++) {
            Ev e = d.events.get(i);
            if (i == skip || e.e <= now) continue;
            int day = Math.max(dayOf(e.s), dayOf(now));
            boolean newDay = day != lastDay;
            float need = rowH + (newDay ? headH : 0);
            if (y + need > r.bottom - (rest > 0 || true ? fs * 1.2f : 0)) {
                rest++;
                continue;
            }
            if (newDay) {
                words(c, dayName(d, day).toUpperCase(Locale.getDefault()), r.left, y + fs * 1.0f, fs * 0.75f, muted, medium, Paint.Align.LEFT);
                y += headH;
                lastDay = day;
            }
            int col = color(d, e.cal);
            box(c, r.left, y + fs * 0.2f, r.left + Math.max(3, fs * 0.22f), y + rowH - fs * 0.3f, col, fs * 0.1f);
            String time = e.allDay() ? "All day" : clock(e.s, o.h24);
            float timeW = width("12:00 PM", fs * 0.85f, regular) + fs * 0.6f;
            words(c, time, r.left + fs * 0.6f, y + rowH * 0.62f, fs * 0.85f, muted, regular, Paint.Align.LEFT);
            words(c, fit(title(e), r.width() - timeW - fs * 0.8f, fs, medium), r.left + fs * 0.6f + timeW, y + rowH * 0.62f, fs,
                    (e.flags & TENTATIVE) != 0 ? muted : ink, medium, Paint.Align.LEFT);
            if (taps != null) taps.event(new RectF(r.left, y, r.right, y + rowH), e);
            y += rowH;
            shown++;
        }
        rest += d.leftOut;
        if (rest > 0) words(c, rest + " more", r.left, Math.min(r.bottom, y + fs * 1.1f), fs * 0.8f, muted, regular, Paint.Align.LEFT);
        else if (shown == 0 && skip < 0) words(c, "Nothing planned", r.left, y + fs * 1.1f, fs * 0.85f, muted, regular, Paint.Align.LEFT);
    }

    interface EventTaps { void event(RectF r, Ev e); }

    /** Columns: today and the next days side by side, each with its events as blocks. */
    private void columns(Canvas c, Data d, Options o, RectF r, float fs, int ink, int muted, int now, int n) {
        float gap = fs * 0.5f, cw = (r.width() - (n - 1) * gap) / n;
        int today = dayOf(now);
        for (int k = 0; k < n; k++) {
            int day = today + k;
            float x = r.left + k * (cw + gap), y = r.top;
            words(c, fit(k == 0 ? "Today" : k == 1 ? "Tomorrow" : DAYS_LONG[date(d, day)[3]], cw, fs * 0.8f, medium), x, y + fs * 0.85f,
                    fs * 0.8f, k == 0 ? ink : muted, medium, Paint.Align.LEFT);
            y += fs * 1.4f;
            int more = 0;
            for (Ev e : d.events) {
                if (!e.on(day) || e.e <= now) continue;
                float bh = fs * 2.5f;
                if (y + bh > r.bottom) {
                    more++;
                    continue;
                }
                int col = color(d, e.cal);
                box(c, x, y, x + cw, y + bh - fs * 0.3f, block(col), fs * 0.4f);
                box(c, x, y, x + Math.max(3, fs * 0.2f), y + bh - fs * 0.3f, col, fs * 0.1f);
                words(c, fit(title(e), cw - fs * 0.9f, fs * 0.85f, medium), x + fs * 0.55f, y + fs * 0.95f, fs * 0.85f, ink, medium, Paint.Align.LEFT);
                words(c, fit(e.allDay() ? "All day" : clock(e.s, o.h24), cw - fs * 0.9f, fs * 0.72f, regular), x + fs * 0.55f, y + fs * 1.85f,
                        fs * 0.72f, muted, regular, Paint.Align.LEFT);
                y += bh;
            }
            if (more > 0) words(c, "+" + more, x, Math.min(r.bottom, y + fs), fs * 0.75f, muted, regular, Paint.Align.LEFT);
        }
    }

    /**
     * The week: days as columns, all-day events in a band over the hours, the hours as rows, a red line at now, and
     * a tag such as "1 earlier" on a day with events outside the hours shown.
     */
    private void weekGrid(Canvas c, Data d, Options o, RectF r, float fs, int ink, int muted, int card, int now, int firstDay, int days,
                          EventTaps taps) {
        days = Math.max(1, days);
        int today = dayOf(now);
        // The hours: the tile's own, else the events' own, eight to eight at least.
        int h0 = o.hoursFrom, h1 = o.hoursTo;
        if (h1 <= h0) {
            h0 = 8;
            h1 = 20;
            for (Ev e : d.events) {
                for (int k = 0; k < days; k++) {
                    int day = today + firstDay + k;
                    if (e.banded() || !e.on(day)) continue;
                    h0 = Math.min(h0, Math.max(0, (e.s - day * 1440)) / 60);
                    h1 = Math.max(h1, Math.min(24, (int) Math.ceil(Math.min(1440, e.e - day * 1440) / 60.0)));
                }
            }
        }
        float labelW = width("12 PM", fs * 0.75f, regular) + fs * 0.4f;
        float headH = fs * 1.5f;
        float colW = (r.width() - labelW) / days;
        // The all-day band: up to two rows, then a count.
        int bandRows = 0;
        for (int k = 0; k < days; k++) {
            int n = 0;
            for (Ev e : d.events) if (e.banded() && e.on(today + firstDay + k)) n++;
            bandRows = Math.max(bandRows, Math.min(3, n));
        }
        float bandRow = fs * 1.3f, bandH = bandRows * bandRow;
        float gridTop = r.top + headH + bandH + (bandRows > 0 ? fs * 0.3f : 0), gridH = r.bottom - gridTop;
        float hourH = gridH / Math.max(1, h1 - h0);
        for (int k = 0; k < days; k++) {
            int day = today + firstDay + k;
            int[] dt = date(d, day);
            float x = r.left + labelW + k * colW;
            boolean isToday = day == today;
            String head = DAYS[dt[3]] + " " + dt[2];
            if (isToday) box(c, x + 1, r.top, x + colW - 1, r.top + headH * 0.95f, (Style.BLUE & 0xFFFFFF) | 0x33000000, fs * 0.4f);
            words(c, fit(head, colW - 4, fs * 0.8f, medium), x + colW / 2, r.top + headH * 0.68f, fs * 0.8f, isToday ? ink : muted, medium, Paint.Align.CENTER);
            // The band.
            int row = 0, extra = 0;
            for (Ev e : d.events) {
                if (!e.banded() || !e.on(day)) continue;
                if (row >= 2 && bandRows > 2) {
                    extra++;
                    continue;
                }
                float by = r.top + headH + row * bandRow;
                int col = color(d, e.cal);
                box(c, x + 1, by + 1, x + colW - 1, by + bandRow - 1, block(col), fs * 0.25f);
                words(c, fit(title(e), colW - fs * 0.5f, fs * 0.7f, medium), x + fs * 0.25f, by + bandRow * 0.72f, fs * 0.7f, ink, medium, Paint.Align.LEFT);
                if (taps != null) taps.event(new RectF(x, by, x + colW, by + bandRow), e);
                row++;
            }
            if (extra > 0) words(c, "+" + extra, x + fs * 0.25f, r.top + headH + 2 * bandRow + bandRow * 0.72f, fs * 0.7f, muted, regular, Paint.Align.LEFT);
            // The hours.
            fill.setColor(Style.LINE);
            c.drawRect(x, gridTop, x + 1, r.bottom, fill);
            int earlier = 0, later = 0;
            for (Ev e : d.events) {
                if (e.banded() || !e.on(day)) continue;
                int s = Math.max(e.s - day * 1440, 0), en = Math.min(e.e - day * 1440, 1440);
                if (en <= h0 * 60) { earlier++; continue; }
                if (s >= h1 * 60) { later++; continue; }
                float y0 = gridTop + (Math.max(s, h0 * 60) - h0 * 60) / 60f * hourH, y1 = gridTop + (Math.min(en, h1 * 60) - h0 * 60) / 60f * hourH;
                int col = color(d, e.cal);
                box(c, x + 2, y0 + 1, x + colW - 2, Math.max(y0 + fs * 0.6f, y1 - 1), block(col), fs * 0.25f);
                box(c, x + 2, y0 + 1, x + 2 + Math.max(2, fs * 0.15f), Math.max(y0 + fs * 0.6f, y1 - 1), col, 1);
                // A short event keeps its title in a smaller size, as far as its block holds it.
                float ts = Math.min(fs * 0.7f, (y1 - y0) * 0.75f);
                if (ts >= fs * 0.45f) words(c, fit(title(e), colW - fs * 0.6f, ts, medium), x + fs * 0.4f, y0 + Math.min(fs * 0.8f, (y1 - y0) * 0.5f + ts * 0.36f), ts, ink, medium, Paint.Align.LEFT);
                if (y1 - y0 >= fs * 1.8f) words(c, fit(clock(e.s, o.h24), colW - fs * 0.6f, fs * 0.62f, regular), x + fs * 0.4f, y0 + fs * 1.6f, fs * 0.62f, muted, regular, Paint.Align.LEFT);
                if (taps != null) taps.event(new RectF(x, y0, x + colW, Math.max(y0 + fs, y1)), e);
            }
            if (earlier > 0) words(c, earlier + " earlier", x + colW / 2, gridTop + fs * 0.7f, fs * 0.62f, muted, regular, Paint.Align.CENTER);
            if (later > 0) words(c, later + " later", x + colW / 2, r.bottom - fs * 0.2f, fs * 0.62f, muted, regular, Paint.Align.CENTER);
            // The red line at now.
            int m = now - day * 1440;
            if (isToday && m >= h0 * 60 && m <= h1 * 60) {
                float ny = gridTop + (m - h0 * 60) / 60f * hourH;
                fill.setColor(NOW_LINE);
                c.drawRect(x, ny - 1, x + colW, ny + 1, fill);
                c.drawCircle(x + 2, ny, Math.max(3, fs * 0.2f), fill);
            }
        }
        // The hour lines and their labels, every other hour where they would crowd.
        int every = hourH < fs * 1.1f ? 2 : 1;
        for (int h = h0; h <= h1; h++) {
            float y = gridTop + (h - h0) * hourH;
            fill.setColor(Style.LINE);
            c.drawRect(r.left + labelW, y, r.right, y + 1, fill);
            if ((h - h0) % every == 0 && h < h1)
                words(c, o.h24 ? String.format(Locale.US, "%02d", h) : (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM"), r.left + labelW - fs * 0.3f,
                        y + fs * 0.75f, fs * 0.7f, muted, regular, Paint.Align.RIGHT);
        }
    }

    // ---- Status and booking: a room's sign
    interface Booker { void book(Data d, int start, int end); boolean booking(); }

    private void room(Canvas c, Data d, Options o, Tessera.Tile t, RectF r, float fs, int ink, int muted, int now, Hits hits, final Booker booker) {
        final int run = running(d, now), nx = next(d, now);
        boolean busy = run >= 0;
        boolean soon = !busy && nx >= 0 && o.soon > 0 && d.events.get(nx).s - now <= o.soon;
        String word = busy ? "Busy" : soon ? "Starts soon" : "Free";
        int col = busy ? BUSY : soon ? AMBER : FREE;
        float big = Math.min(fs * 3, r.height() * 0.28f);
        big = Math.min(big, big * r.width() / Math.max(1, width(word, big, medium)));
        words(c, word, r.left, r.top + big * 0.85f, big, col, medium, Paint.Align.LEFT);
        String until;
        if (busy) until = "until " + clock(d.events.get(run).e, o.h24);
        else if (nx < 0) until = "all day";
        else {
            Ev n = d.events.get(nx);
            until = dayOf(n.s) == dayOf(now) && !n.allDay() ? "until " + clock(n.s, o.h24) : "until " + nextWhen(d, n, now, o.h24);
        }
        words(c, fit(until, r.width(), fs, regular), r.left, r.top + big + fs * 1.1f, fs, ink, regular, Paint.Align.LEFT);
        float y = r.top + big + fs * 2.4f;
        Ev shown = busy ? d.events.get(run) : nx >= 0 ? d.events.get(nx) : null;
        if (shown != null) {
            words(c, fit((busy ? "Now: " : "Next: ") + title(shown), r.width(), fs * 0.9f, medium), r.left, y, fs * 0.9f, muted, medium, Paint.Align.LEFT);
            y += fs * 1.4f;
        }
        // On a free room, keys that book it from now: 15 min, 30 min, 1 h, or until the next meeting.
        if (busy || d.bookOn.isEmpty() || booker == null) return;
        if (booker.booking()) {
            words(c, "Booking…", r.left, Math.min(r.bottom - fs * 0.3f, y + fs * 1.2f), fs, muted, medium, Paint.Align.LEFT);
            return;
        }
        int limit = nx >= 0 ? d.events.get(nx).s : Integer.MAX_VALUE;
        List<int[]> keys = new ArrayList<int[]>(); // {end minute, label kind}
        for (int m : new int[]{15, 30, 60}) if (now + m <= limit) keys.add(new int[]{now + m, m});
        if (nx >= 0 && limit - now > 60 && limit - now <= 8 * 60) keys.add(new int[]{limit, -1});
        float kh = fs * 2.3f, gap = fs * 0.5f, x = r.left, ky = r.bottom - kh;
        if (ky < y) return;
        for (final int[] k : keys) {
            String label = k[1] == 15 ? "15 min" : k[1] == 30 ? "30 min" : k[1] == 60 ? "1 h" : "Until " + clock(k[0], o.h24);
            float kw = width(label, fs * 0.9f, medium) + fs * 1.6f;
            if (x + kw > r.right) break;
            RectF key = new RectF(x, ky, x + kw, ky + kh);
            box(c, key.left, key.top, key.right, key.bottom, (FREE & 0xFFFFFF) | 0x33000000, kh / 2);
            words(c, label, key.centerX(), key.centerY() + fs * 0.32f, fs * 0.9f, ink, medium, Paint.Align.CENTER);
            final Data data = d;
            final int start = now;
            hits.tap(key, new Runnable() {
                public void run() { booker.book(data, start, k[0]); }
            });
            x += kw + gap;
        }
    }

    // =================================================================================================== the full screen

    static final String[] VIEWS = {"up_next", "day", "week", "month"};
    private static final String[] VIEW_WORDS = {"Up next", "Day", "Week", "Month"};
    String view = "";
    int dayShown, weekShown, monthShown;   // offsets from today: a day, a week (in weeks), a month (in months)
    Ev sheet;
    boolean choosing;
    final Set<Integer> hidden = new HashSet<Integer>();

    /** Opens the full screen on the tile's own first view, else Week on wide glass, else Up next. */
    void open(Tessera.Tile t, int w, int h) {
        String sv = Json.str(t.options, "start_view");
        view = sv != null && !sv.equals("auto") && !sv.isEmpty() ? sv : w > h ? "week" : "up_next";
        dayShown = weekShown = monthShown = 0;
        sheet = null;
        choosing = false;
        hidden.clear();
    }

    interface FullHost { void close(); }

    /** The full screen over the glass: the header with its keys, the view, and a sheet over it for a tapped event. */
    void drawFull(Canvas c, Tessera.Tile t, int w, int h, float u, boolean h24, final FullHost host, final Hits hits) {
        final Data all = parse(t.extra);
        fill.setColor(Style.BG);
        c.drawRect(0, 0, w, h, fill);
        hits.tap(new RectF(0, 0, w, h), null);
        float fs = 4.2f * u, pad = 3 * u, barH = 12 * u;
        // The header: close, the name, the view key, Today, back and forward.
        RectF close = new RectF(pad, 2 * u, pad + 10 * u, 12 * u);
        box(c, close.left, close.top, close.right, close.bottom, Style.RAISED, 5 * u);
        glyphs.setColor(Style.TEXT);
        glyphs.setTextSize(6 * u);
        c.drawText(Icons.sure(context, "close"), close.centerX(), close.centerY() + 2.1f * u, glyphs);
        hits.tap(close, new Runnable() {
            public void run() { host.close(); }
        });
        float x = close.right + 3 * u;
        words(c, fit(t.name.isEmpty() ? "Calendar" : t.name, w * 0.3f, fs * 1.2f, medium), x, close.centerY() + fs * 0.42f, fs * 1.2f, Style.TEXT, medium, Paint.Align.LEFT);
        float kx = w - pad, kh = 9 * u, ky = 3 * u;
        boolean narrow = w < 120 * u;
        if (!narrow) {
            for (final int step : new int[]{1, -1}) {
                RectF k = new RectF(kx - 10 * u, ky, kx, ky + kh);
                box(c, k.left, k.top, k.right, k.bottom, Style.RAISED, kh / 2);
                glyphs.setTextSize(5 * u);
                c.drawText(Icons.sure(context, step > 0 ? "chevron-right" : "chevron-left"), k.centerX(), k.centerY() + 1.8f * u, glyphs);
                hits.tap(k, new Runnable() {
                    public void run() { move(step); }
                });
                kx -= 11 * u;
            }
        }
        float todayW = width("Today", fs * 0.9f, medium) + 4 * u;
        RectF today = new RectF(kx - todayW, ky, kx, ky + kh);
        box(c, today.left, today.top, today.right, today.bottom, Style.RAISED, kh / 2);
        words(c, "Today", today.centerX(), today.centerY() + fs * 0.32f, fs * 0.9f, Style.TEXT, medium, Paint.Align.CENTER);
        hits.tap(today, new Runnable() {
            public void run() { dayShown = weekShown = monthShown = 0; }
        });
        kx = today.left - u;
        int vi = Math.max(0, java.util.Arrays.asList(VIEWS).indexOf(view));
        String vw = VIEW_WORDS[vi];
        float viewW = width(vw, fs * 0.9f, medium) + 8 * u;
        final RectF viewKey = new RectF(kx - viewW, ky, kx, ky + kh);
        box(c, viewKey.left, viewKey.top, viewKey.right, viewKey.bottom, (Style.BLUE & 0xFFFFFF) | 0x33000000, kh / 2);
        words(c, vw, viewKey.centerX() - 1.5f * u, viewKey.centerY() + fs * 0.32f, fs * 0.9f, Style.TEXT, medium, Paint.Align.CENTER);
        glyphs.setTextSize(4.5f * u);
        c.drawText(Icons.sure(context, "menu-down"), viewKey.right - 3 * u, viewKey.centerY() + 1.6f * u, glyphs);
        hits.tap(viewKey, new Runnable() {
            public void run() { choosing = !choosing; }
        });
        if (all == null) {
            words(c, "Waiting for the events…", w / 2f, h / 2f, fs, Style.DIM, regular, Paint.Align.CENTER);
            return;
        }
        // The calendars shown: hidden ones leave the full screen until it closes.
        final Data d = visible(all);
        Options o = options(t, h24);
        int now = now(d);
        RectF body = new RectF(pad, barH + u, w - pad, h - pad);
        EventTaps taps = new EventTaps() {
            public void event(final RectF r, final Ev e) {
                hits.tap(r, new Runnable() {
                    public void run() {
                        sheet = e;
                        if (all.compact && asker != null && (e.flags & PRIVATE) == 0) asker.ask(e.cal, e.s, e.e, all.today());
                    }
                });
            }
        };
        if (view.equals("week")) {
            int start = (o.fromMonday ? -d.weekday : 0) + weekShown * 7;
            weekGrid(c, d, o, body, fs * 0.9f, Style.TEXT, Style.DIM, Style.CARD, now, start, 7, taps);
        } else if (view.equals("day")) {
            dayView(c, d, o, body, fs, now, u, taps, hits);
        } else if (view.equals("month")) {
            monthView(c, d, o, body, fs, now, u, taps, hits);
        } else {
            float half = w > h ? body.width() * 0.45f : body.width();
            RectF block = new RectF(body.left, body.top, body.left + half, Math.min(body.bottom, body.top + fs * 6));
            upNext(c, d, o, block, fs * 1.2f, Style.TEXT, Style.DIM, now);
            int run = running(d, now), nx = next(d, now);
            RectF rest = w > h ? new RectF(body.left + half + 3 * u, body.top, body.right, body.bottom) : new RectF(body.left, block.bottom + 2 * u, body.right, body.bottom);
            list(c, d, o, rest, fs, Style.TEXT, Style.DIM, now, run >= 0 ? run : nx, taps);
        }
        if (choosing) chooser(c, all, viewKey, fs, u, hits);
        if (sheet != null) drawSheet(c, all, o, w, h, fs, u, hits);
    }

    private Data visible(Data all) {
        if (hidden.isEmpty()) return all;
        Data d = new Data();
        d.year = all.year; d.month = all.month; d.day = all.day; d.weekday = all.weekday; d.from = all.from; d.to = all.to;
        d.colors.addAll(all.colors);
        d.names.addAll(all.names);
        d.notes = all.notes;
        d.glanceFrom = all.glanceFrom;
        d.glance = new int[all.glance.length];
        int mask = 0;
        for (int i : hidden) mask |= 1 << i;
        for (int i = 0; i < all.glance.length; i++) d.glance[i] = all.glance[i] & ~mask;
        for (Ev e : all.events) if (!hidden.contains(e.cal)) d.events.add(e);
        return d;
    }

    private void move(int step) {
        if (view.equals("day")) dayShown += step;
        else if (view.equals("week")) weekShown += step;
        else if (view.equals("month")) monthShown += step;
    }

    /** The view key's choice: the views, and the card's calendars to show or hide when it has more than one. */
    private void chooser(Canvas c, Data d, RectF key, float fs, float u, Hits hits) {
        int rows = 4 + (d.names.size() > 1 ? 1 + d.names.size() : 0);
        float rowH = fs * 2.2f, w = Math.max(48 * u, key.width() + 20 * u);
        RectF menu = new RectF(key.right - w, key.bottom + u, key.right, key.bottom + u + rows * rowH + u);
        box(c, menu.left, menu.top, menu.right, menu.bottom, Style.CARD, 2.5f * u);
        fill.setColor(Style.LINE);
        float y = menu.top + u / 2;
        for (int i = 0; i < 4; i++) {
            final String v = VIEWS[i];
            RectF r = new RectF(menu.left, y, menu.right, y + rowH);
            if (v.equals(view)) box(c, r.left + u, r.top, r.right - u, r.bottom, (Style.BLUE & 0xFFFFFF) | 0x33000000, 1.5f * u);
            words(c, VIEW_WORDS[i], r.left + 3 * u, r.centerY() + fs * 0.35f, fs, Style.TEXT, medium, Paint.Align.LEFT);
            hits.tap(r, new Runnable() {
                public void run() {
                    view = v;
                    choosing = false;
                }
            });
            y += rowH;
        }
        if (d.names.size() > 1) {
            words(c, "CALENDARS", menu.left + 3 * u, y + rowH * 0.65f, fs * 0.75f, Style.DIM, medium, Paint.Align.LEFT);
            y += rowH;
            for (int i = 0; i < d.names.size(); i++) {
                final int cal = i;
                RectF r = new RectF(menu.left, y, menu.right, y + rowH);
                boolean on = !hidden.contains(i);
                fill.setColor(color(d, i));
                if (on) c.drawCircle(r.left + 4 * u, r.centerY(), 1.6f * u, fill);
                else {
                    Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                    ring.setStyle(Paint.Style.STROKE);
                    ring.setStrokeWidth(2);
                    ring.setColor(color(d, i));
                    c.drawCircle(r.left + 4 * u, r.centerY(), 1.6f * u, ring);
                }
                words(c, fit(d.names.get(i), menu.width() - 9 * u, fs * 0.9f, regular), r.left + 7 * u, r.centerY() + fs * 0.32f, fs * 0.9f, on ? Style.TEXT : Style.DIM, regular, Paint.Align.LEFT);
                hits.tap(r, new Runnable() {
                    public void run() {
                        if (!hidden.remove(cal)) hidden.add(cal);
                    }
                });
                y += rowH;
            }
        }
    }

    /** The day: a strip of the week's days with a dot per calendar busy that day, then the chosen day's events. */
    private void dayView(Canvas c, final Data d, Options o, RectF r, float fs, int now, float u, EventTaps taps, Hits hits) {
        int today = dayOf(now), shown = today + dayShown;
        int monday = shown - date(d, shown)[3];
        float stripH = fs * 3.2f, cw = r.width() / 7;
        for (int k = 0; k < 7; k++) {
            final int day = monday + k;
            int[] dt = date(d, day);
            float x = r.left + k * cw;
            RectF cell = new RectF(x + 2, r.top, x + cw - 2, r.top + stripH);
            if (day == shown) box(c, cell.left, cell.top, cell.right, cell.bottom, (Style.BLUE & 0xFFFFFF) | 0x33000000, 2 * u);
            words(c, DAYS[dt[3]], cell.centerX(), cell.top + fs * 1.0f, fs * 0.75f, Style.DIM, medium, Paint.Align.CENTER);
            words(c, Integer.toString(dt[2]), cell.centerX(), cell.top + fs * 2.1f, fs, day == today ? Style.BLUE : Style.TEXT, medium, Paint.Align.CENTER);
            // A dot per calendar with something that day.
            List<Integer> cals = new ArrayList<Integer>();
            for (Ev e : d.events) if (e.on(day) && !cals.contains(e.cal)) cals.add(e.cal);
            float dx = cell.centerX() - (cals.size() - 1) * u;
            for (int cal : cals) {
                fill.setColor(color(d, cal));
                c.drawCircle(dx, cell.top + fs * 2.75f, 0.6f * u, fill);
                dx += 2 * u;
            }
            hits.tap(cell, new Runnable() {
                public void run() { dayShown = day - dayOf(CalendarCard.now(d)); }
            });
        }
        words(c, longDay(d, shown), r.left, r.top + stripH + fs * 1.5f, fs * 1.1f, Style.TEXT, medium, Paint.Align.LEFT);
        RectF rest = new RectF(r.left, r.top + stripH + fs * 2.2f, r.right, r.bottom);
        // A timeline on tall glass, else the day's events as a list of blocks.
        Options one = o;
        if (rest.height() > fs * 14) {
            weekGrid(c, d, one, rest, fs * 0.9f, Style.TEXT, Style.DIM, Style.CARD, now, shown - today, 1, taps);
        } else {
            float y = rest.top, bh = fs * 2.8f;
            boolean any = false;
            for (Ev e : d.events) {
                if (!e.on(shown)) continue;
                any = true;
                if (y + bh > rest.bottom) break;
                int col = color(d, e.cal);
                box(c, rest.left, y, rest.right, y + bh - fs * 0.35f, block(col), fs * 0.5f);
                box(c, rest.left, y, rest.left + Math.max(4, fs * 0.25f), y + bh - fs * 0.35f, col, fs * 0.1f);
                words(c, fit(title(e), rest.width() * 0.6f, fs, medium), rest.left + fs * 0.8f, y + fs * 1.0f, fs, Style.TEXT, medium, Paint.Align.LEFT);
                words(c, fit(when(d, e, o.h24), rest.width() * 0.6f, fs * 0.8f, regular), rest.left + fs * 0.8f, y + fs * 2.0f, fs * 0.8f, Style.DIM, regular, Paint.Align.LEFT);
                if (taps != null) taps.event(new RectF(rest.left, y, rest.right, y + bh), e);
                y += bh;
            }
            if (!any) words(c, shown - today > d.to ? "Beyond the days this card looks ahead" : "Nothing planned", rest.left, rest.top + fs * 1.2f, fs * 0.9f, Style.DIM, regular, Paint.Align.LEFT);
        }
    }

    /** The month: a glance of dots per calendar, or the first titles in large cells; the chosen day's events beside it. */
    private void monthView(Canvas c, final Data d, Options o, RectF r, float fs, int now, float u, EventTaps taps, Hits hits) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(d.year, d.month - 1, 1);
        cal.add(Calendar.MONTH, monthShown);
        int year = cal.get(Calendar.YEAR), month = cal.get(Calendar.MONTH);
        int firstWd = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        Calendar today = Calendar.getInstance();
        today.clear();
        today.set(d.year, d.month - 1, d.day);
        int firstDay = (int) Math.round((cal.getTimeInMillis() - today.getTimeInMillis()) / 86400000.0) - firstWd;
        int todayIndex = dayOf(now);
        boolean wide = r.width() > r.height() * 1.3f;
        float gridW = wide ? r.width() * 0.62f : r.width();
        float weekNoW = o.weekNumbers ? fs * 1.8f : 0;
        float titleH = fs * 1.6f, headH = fs * 1.3f;
        words(c, MONTHS[month] + " " + year, r.left, r.top + fs * 1.1f, fs * 1.1f, Style.TEXT, medium, Paint.Align.LEFT);
        float gx = r.left + weekNoW, gy = r.top + titleH + headH, cw = (gridW - weekNoW) / 7;
        float gridH = (wide ? r.bottom : r.top + (r.height()) * 0.62f) - gy, ch = gridH / 6;
        for (int k = 0; k < 7; k++) words(c, DAYS[k].substring(0, 2), gx + k * cw + cw / 2, gy - fs * 0.35f, fs * 0.7f, Style.DIM, medium, Paint.Align.CENTER);
        int selected = todayIndex + dayShown;
        for (int i = 0; i < 42; i++) {
            final int day = firstDay + i;
            int[] dt = date(d, day);
            float x = gx + (i % 7) * cw, y = gy + (i / 7) * ch;
            RectF cell = new RectF(x + 1, y + 1, x + cw - 1, y + ch - 1);
            boolean inMonth = dt[1] - 1 == month;
            if (day == selected) box(c, cell.left, cell.top, cell.right, cell.bottom, (Style.BLUE & 0xFFFFFF) | 0x22000000, u);
            if (day == todayIndex) {
                fill.setColor(Style.BLUE);
                c.drawCircle(cell.left + fs * 0.95f, cell.top + fs * 0.85f, fs * 0.68f, fill);
            }
            words(c, Integer.toString(dt[2]), cell.left + fs * 0.95f, cell.top + fs * 1.1f, fs * 0.8f,
                    day == todayIndex ? Style.DARK_TEXT : inMonth ? Style.TEXT : Style.FAINT, medium, Paint.Align.CENTER);
            // Large cells: the first titles; small cells: a dot per calendar (the glance).
            int mask = 0;
            int gi = day - d.glanceFrom;
            if (gi >= 0 && gi < d.glance.length) mask = d.glance[gi];
            for (Ev e : d.events) if (e.on(day)) mask |= 1 << e.cal;
            if (ch > fs * 3.6f) {
                float ty = cell.top + fs * 2.3f;
                int n = 0;
                for (Ev e : d.events) {
                    if (!e.on(day)) continue;
                    if (ty + fs * 0.4f > cell.bottom) break;
                    box(c, cell.left + 2, ty - fs * 0.75f, cell.right - 2, ty + fs * 0.2f, block(color(d, e.cal)), fs * 0.2f);
                    words(c, fit(title(e), cell.width() - 6, fs * 0.62f, regular), cell.left + 4, ty, fs * 0.62f, Style.TEXT, regular, Paint.Align.LEFT);
                    ty += fs * 1.05f;
                    n++;
                }
                if (n == 0) dots(c, d, mask, cell, fs, u);
            } else {
                dots(c, d, mask, cell, fs, u);
            }
            hits.tap(cell, new Runnable() {
                public void run() { dayShown = day - dayOf(CalendarCard.now(d)); }
            });
        }
        if (o.weekNumbers) {
            for (int wk = 0; wk < 6; wk++) {
                Calendar w = Calendar.getInstance(Locale.UK);
                w.setFirstDayOfWeek(Calendar.MONDAY);
                w.setMinimalDaysInFirstWeek(4);
                int[] dt = date(d, firstDay + wk * 7 + 3);
                w.clear();
                w.set(dt[0], dt[1] - 1, dt[2]);
                words(c, Integer.toString(w.get(Calendar.WEEK_OF_YEAR)), r.left + weekNoW / 2, gy + wk * ch + fs * 1.1f, fs * 0.65f, Style.FAINT, regular, Paint.Align.CENTER);
            }
        }
        // The chosen day's events: beside the month on wide glass, under it on square glass.
        RectF side = wide ? new RectF(r.left + gridW + 3 * u, r.top, r.right, r.bottom) : new RectF(r.left, gy + gridH + u, r.right, r.bottom);
        words(c, fit(longDay(d, selected), side.width(), fs, medium), side.left, side.top + fs * 1.1f, fs, Style.TEXT, medium, Paint.Align.LEFT);
        float y = side.top + fs * 2.0f, bh = fs * 2.6f;
        boolean any = false;
        for (Ev e : d.events) {
            if (!e.on(selected)) continue;
            any = true;
            if (y + bh > side.bottom) break;
            int col = color(d, e.cal);
            box(c, side.left, y, side.right, y + bh - fs * 0.35f, block(col), fs * 0.45f);
            box(c, side.left, y, side.left + Math.max(4, fs * 0.25f), y + bh - fs * 0.35f, col, fs * 0.1f);
            words(c, fit(title(e), side.width() - fs * 1.2f, fs * 0.9f, medium), side.left + fs * 0.7f, y + fs * 0.95f, fs * 0.9f, Style.TEXT, medium, Paint.Align.LEFT);
            words(c, fit(when(d, e, o.h24), side.width() - fs * 1.2f, fs * 0.72f, regular), side.left + fs * 0.7f, y + fs * 1.85f, fs * 0.72f, Style.DIM, regular, Paint.Align.LEFT);
            if (taps != null) taps.event(new RectF(side.left, y, side.right, y + bh), e);
            y += bh;
        }
        if (!any) words(c, selected - todayIndex > d.to || selected < todayIndex + d.from ? "Not in the days this card holds" : "Nothing planned",
                side.left, y + fs * 0.4f, fs * 0.85f, Style.DIM, regular, Paint.Align.LEFT);
    }

    private void dots(Canvas c, Data d, int mask, RectF cell, float fs, float u) {
        List<Integer> cals = new ArrayList<Integer>();
        for (int i = 0; i < 8; i++) if ((mask & (1 << i)) != 0 && !hidden.contains(i)) cals.add(i);
        float dx = cell.centerX() - (cals.size() - 1) * u * 0.9f;
        for (int cal : cals) {
            fill.setColor(color(d, cal));
            c.drawCircle(dx, cell.bottom - fs * 0.55f, 0.55f * u, fill);
            dx += 1.8f * u;
        }
    }

    /** An event's sheet: its title, calendar, day, times, place and (for the event of the moment or the next) notes. */
    private void drawSheet(Canvas c, Data d, Options o, int w, int h, float fs, float u, Hits hits) {
        final Ev e = sheet;
        fill.setColor(0x99000000);
        c.drawRect(0, 0, w, h, fill);
        hits.tap(new RectF(0, 0, w, h), new Runnable() {
            public void run() { sheet = null; }
        });
        float sw = Math.min(w * 0.85f, 110 * u), sh = Math.min(h * 0.8f, 75 * u);
        RectF r = new RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2);
        box(c, r.left, r.top, r.right, r.bottom, Style.CARD, 3 * u);
        hits.tap(r, null);
        float pad = 4 * u, x = r.left + pad, tw = r.width() - pad * 2, y = r.top + pad + fs * 1.2f;
        int col = color(d, e.cal);
        box(c, x, y - fs * 1.0f, x + Math.max(4, fs * 0.3f), y + fs * 0.2f, col, 2);
        // A short event's whole title, place and notes, once the app answered (calendar_detail).
        String[] more = d.compact ? details.get(e.cal + ":" + e.s + ":" + e.e) : null;
        String whole = more != null && !more[0].isEmpty() && (e.flags & PRIVATE) == 0 ? more[0] : title(e);
        List<String> titleLines = wrapIn(whole, tw - fs, fs * 1.3f, medium);
        for (int i = 0; i < Math.min(2, titleLines.size()); i++) {
            String line = i == 1 && titleLines.size() > 2 ? fit(titleLines.get(1) + " " + titleLines.get(2), tw - fs, fs * 1.3f, medium) : titleLines.get(i);
            words(c, line, x + fs * 0.7f, y, fs * 1.3f, Style.TEXT, medium, Paint.Align.LEFT);
            if (i == 0 && titleLines.size() > 1) y += fs * 1.55f;
        }
        y += fs * 1.8f;
        String calName = e.cal < d.names.size() ? d.names.get(e.cal) : "";
        String[][] rows = {
                {"calendar", calName},
                {"calendar-today", longDay(d, dayOf(e.s))},
                {"clock-outline", when(d, e, o.h24) + (e.allDay() ? "" : " · " + duration(e.e - e.s))},
                {"map-marker-outline", (e.flags & PRIVATE) != 0 ? "" : more != null && !more[1].isEmpty() ? more[1] : e.place}};
        for (String[] row : rows) {
            if (row[1] == null || row[1].isEmpty()) continue;
            glyphs.setColor(Style.DIM);
            glyphs.setTextSize(fs * 1.1f);
            c.drawText(Icons.sure(context, row[0]), x + fs * 0.55f, y + fs * 0.05f, glyphs);
            words(c, fit(row[1], tw - fs * 2, fs * 0.95f, regular), x + fs * 1.6f, y, fs * 0.95f, Style.TEXT, regular, Paint.Align.LEFT);
            y += fs * 1.6f;
        }
        // The notes travel for the event of the moment, or the next one; a short event's come with its answer.
        int now = now(d), focus = running(d, now);
        if (focus < 0) focus = next(d, now);
        String notes = more != null && !more[2].isEmpty() ? more[2] : focus >= 0 && d.events.get(focus) == e ? d.notes : "";
        if (!notes.isEmpty()) {
            y += fs * 0.3f;
            for (String line : wrap(notes, tw, fs * 0.85f)) {
                if (y > r.bottom - pad) break;
                words(c, line, x, y, fs * 0.85f, Style.DIM, regular, Paint.Align.LEFT);
                y += fs * 1.2f;
            }
        }
    }

    private List<String> wrapIn(String s, float w, float size, android.graphics.Typeface face) {
        List<String> out = new ArrayList<String>();
        text.setTypeface(face);
        text.setTextSize(size);
        String line = "";
        for (String word : s.replace('\n', ' ').split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (text.measureText(next) > w && !line.isEmpty()) {
                out.add(line);
                line = word;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) out.add(line);
        if (out.isEmpty()) out.add("");
        return out;
    }

    private List<String> wrap(String s, float w, float size) {
        List<String> out = new ArrayList<String>();
        text.setTypeface(regular);
        text.setTextSize(size);
        String line = "";
        for (String word : s.replace('\n', ' ').split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (text.measureText(next) > w && !line.isEmpty()) {
                out.add(line);
                line = word;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    /** What a booking sends (calendar_view.cpp card_tap): calendar.create_event from now to the key's end. */
    static Map<String, String> booking(Data d, int start, int end) {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity_id", d.bookOn);
        data.put("summary", d.bookTitle.isEmpty() ? "Booked on the screen" : d.bookTitle);
        data.put("start_date_time", stamp(d, start));
        data.put("end_date_time", stamp(d, end));
        if (!d.bookPlace.isEmpty()) data.put("location", d.bookPlace);
        return data;
    }

    private static String stamp(Data d, int minute) {
        int[] dt = date(d, dayOf(minute));
        int m = ((minute % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:00", dt[0], dt[1], dt[2], m / 60, m % 60);
    }
}
