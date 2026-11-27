package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/**
 * The weather tile's forecast and week, and the weather card's Daily | Hourly forecast (Tessera's forecast_tile.h,
 * weather_week.h, weather_chart.h, weather_card_lvgl.h). The add-on sends a weather tile its forecast as the tile's
 * extras (core.extras): `days`, up to seven, each with its short name (d), weekday (w, Sunday 0), condition (c), high (h),
 * low (l), chance of rain (p) and rain (r); `hours`, the next 48, each with its hour since today's midnight (o), condition,
 * temperature (h) and rain (r). Drawn here:
 *
 * - a wide tile of one row: the weather now at the left, a column per day beside it;
 * - a tile of two rows or more: the head (the condition's circle, the name, the condition, the temperature) and the week
 *   under it, day columns in a light grid over one temperature curve drawn through the hours;
 * - the card: the weather now with its high and low and attributes, a Daily | Hourly key, and the week or the next
 *   hours as the same chart.
 *
 * The columns are the forecast's own days: a list that starts tomorrow (Buienradar in the evening) counts the curve's
 * hours from tomorrow's midnight (GitHub #280), so today's hours never stand under tomorrow's column.
 */
final class WeatherCard {
    interface Hits { void tap(RectF r, Runnable run); }

    static final class Day { String name = "", cond = ""; int weekday = -1; double high = Double.NaN, low = Double.NaN, rain = Double.NaN, chance = Double.NaN; }
    static final class Hour { int at; String cond = ""; double temp = Double.NaN, rain = Double.NaN; }

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final android.content.Context context;
    /** The card's view: false daily, true hourly. */
    boolean hourly;

    WeatherCard(android.content.Context context, TextPaint glyphs) {
        this.context = context;
        this.glyphs = glyphs;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    // ---- the data

    static List<Day> days(Tessera.Tile t) {
        List<Day> out = new ArrayList<Day>();
        List<Object> list = Json.arr(t.extra.get("days"));
        if (list == null) return out;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            Day d = new Day();
            d.name = str(m, "d");
            d.cond = str(m, "c");
            Double w = Json.dbl(m, "w");
            d.weekday = w == null ? -1 : w.intValue();
            d.high = num(m, "h");
            d.low = num(m, "l");
            d.chance = num(m, "p");
            d.rain = num(m, "r");
            out.add(d);
        }
        return out;
    }

    static List<Hour> hours(Tessera.Tile t) {
        List<Hour> out = new ArrayList<Hour>();
        List<Object> list = Json.arr(t.extra.get("hours"));
        if (list == null) return out;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            Hour h = new Hour();
            Double at = Json.dbl(m, "o");
            if (at == null) continue;
            h.at = at.intValue();
            h.cond = str(m, "c");
            h.temp = num(m, "h");
            h.rain = num(m, "r");
            out.add(h);
        }
        return out;
    }

    static boolean has(Tessera.Tile t) { return !days(t).isEmpty(); }

    private static String str(Map<String, Object> m, String k) {
        String s = Json.str(m, k);
        return s == null ? "" : s;
    }

    private static double num(Map<String, Object> m, String k) {
        Double d = Json.dbl(m, k);
        return d == null ? Double.NaN : d;
    }

    /** Hours from today's midnight to the forecast's first day's: 0 when it starts today, 24 when tomorrow. */
    static int firstDayAt(List<Day> days) {
        if (days.isEmpty() || days.get(0).weekday < 0) return 0;
        int today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1; // Sunday 0
        return ((days.get(0).weekday - today + 7) % 7) * 24;
    }

    // ---- colours (theme.h)

    static int sky(String condition) {
        if (condition == null) return 0xFF78909C;
        if (condition.equals("sunny")) return 0xFFFFB300;
        if (condition.equals("clear-night")) return 0xFF5C6BC0;
        if (condition.equals("partlycloudy")) return 0xFF7E9BB5;
        if (condition.equals("rainy") || condition.equals("pouring")) return 0xFF1E88E5;
        if (condition.startsWith("snowy") || condition.equals("hail")) return 0xFF4FC3F7;
        if (condition.startsWith("lightning")) return 0xFFFFA000;
        return 0xFF78909C;
    }

    /** A temperature as Home Assistant's hues (theme::temperature), in degrees Celsius. */
    static int hue(double celsius) {
        double[] at = {-5, 5, 11, 16, 20, 25, 32};
        int[] col = {0xFF3F51B5, 0xFF2196F3, 0xFF00BCD4, 0xFF4CAF50, 0xFFFFC107, 0xFFFF9800, 0xFFF44336};
        if (!(celsius > at[0])) return col[0];
        for (int i = 1; i < at.length; i++) {
            if (celsius > at[i]) continue;
            double s = (celsius - at[i - 1]) / (at[i] - at[i - 1]);
            return mix(col[i - 1], col[i], s);
        }
        return col[col.length - 1];
    }

    private static int mix(int a, int b, double s) {
        int r = (int) (((a >> 16) & 255) * (1 - s) + ((b >> 16) & 255) * s);
        int g = (int) (((a >> 8) & 255) * (1 - s) + ((b >> 8) & 255) * s);
        int bl = (int) ((a & 255) * (1 - s) + (b & 255) * s);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static double celsius(Tessera.Tile t, double v) {
        String u = t.text("temperature_unit");
        return u != null && u.contains("F") ? (v - 32) * 5 / 9 : v;
    }

    static String deg(double v) { return Double.isNaN(v) ? "--" : Math.round(v) + "°"; }

    // ---- the tile

    /**
     * The tile's forecast in `box`: a column per day beside the weather now on a wide tile of one row, the head and the
     * week on a taller one. False when the forecast has nothing to draw (the tile then draws as usual).
     */
    boolean drawTile(Canvas c, Tessera.Tile t, RectF box, float fs, int fg, int dim, int track, boolean tall) {
        List<Day> days = days(t);
        if (days.isEmpty()) return false;
        if (tall) {
            float head = Math.min(box.height() * 0.32f, fs * 3.6f);
            drawHead(c, t, new RectF(box.left, box.top, box.right, box.top + head), fs, fg, dim, false);
            drawWeek(c, t, days, hours(t), new RectF(box.left, box.top + head + fs * 0.5f, box.right, box.bottom), fs, fg, dim, track, false);
            return true;
        }
        // The weather now at the left: the condition's circle, the temperature and the word under it.
        float circle = Math.min(box.height() * 0.62f, fs * 3.2f);
        float cy = box.centerY();
        drawCircle(c, t, box.left + circle / 2, cy, circle);
        Double now = t.number("temperature");
        text.setTypeface(light);
        text.setTextSize(fs * 2.1f);
        text.setColor(fg);
        text.setTextAlign(Paint.Align.LEFT);
        String temp = now == null ? "--" : deg(now);
        float tx = box.left + circle + fs * 0.6f;
        c.drawText(temp, tx, cy + fs * 0.2f, text);
        float tw = text.measureText(temp);
        text.setTypeface(regular);
        text.setTextSize(fs * 0.85f);
        text.setColor(dim);
        String cond = Style.words(t.state);
        float cw = Math.min(text.measureText(cond), fs * 6);
        c.drawText(TextUtils.ellipsize(cond, text, cw, TextUtils.TruncateAt.END).toString(), tx, cy + fs * 1.3f, text);
        float left = tx + Math.max(tw, cw) + fs;
        drawColumns(c, days, new RectF(left, box.top, box.right, box.bottom), fs, fg, dim, track, 5);
        return true;
    }

    /** The card's head row: the circle, the name (or the condition), the temperature large at the end. */
    private void drawHead(Canvas c, Tessera.Tile t, RectF r, float fs, int fg, int dim, boolean card) {
        float circle = Math.min(r.height(), fs * 3.2f);
        drawCircle(c, t, r.left + circle / 2, r.top + circle / 2, circle);
        Double now = t.number("temperature");
        String temp = now == null ? "--" : deg(now);
        text.setTypeface(light);
        text.setTextSize(Math.min(fs * (card ? 3.2f : 2.4f), r.height() * 0.9f));
        text.setColor(fg);
        text.setTextAlign(Paint.Align.RIGHT);
        float tw = text.measureText(temp);
        c.drawText(temp, r.right, r.top + circle / 2 + text.getTextSize() * 0.36f, text);
        float x = r.left + circle + fs * 0.6f, room = r.right - tw - fs - x;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(medium);
        text.setTextSize(fs);
        text.setColor(fg);
        String name = card ? Style.words(t.state) : t.name;
        c.drawText(TextUtils.ellipsize(name, text, room, TextUtils.TruncateAt.END).toString(), x, r.top + circle / 2 - fs * 0.15f, text);
        text.setTypeface(regular);
        text.setTextSize(fs * 0.85f);
        text.setColor(dim);
        String sub = card ? highLow(t) : Style.words(t.state);
        c.drawText(TextUtils.ellipsize(sub, text, room, TextUtils.TruncateAt.END).toString(), x, r.top + circle / 2 + fs * 1.0f, text);
    }

    private String highLow(Tessera.Tile t) {
        List<Day> days = days(t);
        StringBuilder b = new StringBuilder();
        if (!days.isEmpty()) b.append(deg(days.get(0).high)).append(" / ").append(deg(days.get(0).low));
        Double feels = t.number("apparent_temperature");
        if (feels != null) b.append(b.length() > 0 ? " · " : "").append("Feels like ").append(Math.round(feels)).append("°");
        return b.toString();
    }

    private void drawCircle(Canvas c, Tessera.Tile t, float x, float y, float d) {
        boolean dead = "unavailable".equals(t.state) || "unknown".equals(t.state);
        int sky = dead ? 0xFF9E9E9E : sky(t.state);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor((sky & 0x00FFFFFF) | 0x61000000);
        c.drawCircle(x, y, d / 2, fill);
        glyphs.setColor(sky);
        glyphs.setTextSize(d * 0.55f);
        glyphs.setTextAlign(Paint.Align.CENTER);
        c.drawText(Icons.sure(context, Icons.weather(t.state)), x, y + d * 0.2f, glyphs);
    }

    /** Day columns: the name, the icon, the high and the low, the chance of rain where one is worth saying (30 %). */
    private void drawColumns(Canvas c, List<Day> days, RectF r, float fs, int fg, int dim, int track, int most) {
        float minW = fs * 2.6f;
        int n = Math.max(1, Math.min(Math.min(days.size(), most), (int) (r.width() / minW)));
        float w = r.width() / n;
        boolean rain = false;
        for (int i = 0; i < n; i++) if (days.get(i).chance >= 30) rain = true;
        float lineH = fs * 1.15f, need = lineH * (rain ? 4.4f : 3.4f);
        float top = r.top + Math.max(0, (r.height() - need) / 2);
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            Day d = days.get(i);
            float x = r.left + w * i + w / 2;
            if (i == 0) {
                fill.setColor(track);
                c.drawRoundRect(new RectF(x - w / 2 + 2, top - fs * 0.3f, x + w / 2 - 2, top + need), fs * 0.6f, fs * 0.6f, fill);
            }
            text.setTypeface(regular);
            text.setTextSize(fs * 0.8f);
            text.setColor(dim);
            c.drawText(d.name, x, top + fs * 0.75f, text);
            glyphs.setTextSize(fs * 1.5f);
            glyphs.setColor(sky(d.cond));
            c.drawText(Icons.sure(context, Icons.weather(d.cond)), x, top + lineH + fs * 1.2f, glyphs);
            text.setTypeface(medium);
            text.setTextSize(fs * 0.9f);
            text.setColor(fg);
            float y = top + lineH * 2 + fs * 1.0f;
            c.drawText(deg(d.high), x, y, text);
            text.setTypeface(regular);
            text.setColor(dim);
            c.drawText(deg(d.low), x, y + lineH * 0.9f, text);
            if (rain && d.chance >= 30) {
                text.setColor(0xFF1E88E5);
                text.setTextSize(fs * 0.75f);
                c.drawText(Math.round(d.chance) + "%", x, y + lineH * 1.85f, text);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * The week: a column per day (name, icon, high, low, rain) in a light grid, the temperature through the hours drawn
     * across it as one curve in the temperature's hues, each day where the forecast says it is.
     */
    private void drawWeek(Canvas c, Tessera.Tile t, List<Day> days, List<Hour> hours, RectF r, float fs, int fg, int dim,
                          int track, boolean card) {
        int n = Math.max(1, Math.min(days.size(), (int) (r.width() / (fs * 2.4f))));
        float w = r.width() / n;
        // The grid's lines between the days.
        fill.setColor(track);
        for (int i = 1; i < n; i++) c.drawRect(r.left + w * i - 0.5f, r.top, r.left + w * i + 0.5f, r.bottom, fill);
        float lineH = fs * 1.1f;
        text.setTextAlign(Paint.Align.CENTER);
        boolean rain = false;
        for (int i = 0; i < n; i++) if (days.get(i).chance >= 30 || days.get(i).rain > 0) rain = true;
        float headH = lineH + fs * 1.7f;
        for (int i = 0; i < n; i++) {
            Day d = days.get(i);
            float x = r.left + w * i + w / 2;
            text.setTypeface(i == 0 ? medium : regular);
            text.setTextSize(fs * 0.8f);
            text.setColor(i == 0 ? fg : dim);
            c.drawText(d.name, x, r.top + fs * 0.8f, text);
            glyphs.setTextSize(fs * 1.5f);
            glyphs.setColor(sky(d.cond));
            c.drawText(Icons.sure(context, Icons.weather(d.cond)), x, r.top + lineH + fs * 1.2f, glyphs);
        }
        float footH = (rain ? lineH * 2.9f : lineH * 2f);
        RectF plot = new RectF(r.left, r.top + headH + fs * 0.3f, r.right, r.bottom - footH);
        // The high and low under each column.
        for (int i = 0; i < n; i++) {
            Day d = days.get(i);
            float x = r.left + w * i + w / 2;
            float y = plot.bottom + lineH;
            text.setTypeface(medium);
            text.setTextSize(fs * 0.9f);
            text.setColor(fg);
            c.drawText(deg(d.high), x, y, text);
            text.setTypeface(regular);
            text.setColor(dim);
            c.drawText(deg(d.low), x, y + lineH * 0.95f, text);
            if (rain) {
                String rr = d.chance >= 30 ? Math.round(d.chance) + "%" : d.rain > 0 ? (Math.round(d.rain * 10) / 10.0) + " mm" : "";
                text.setColor(0xFF1E88E5);
                text.setTextSize(fs * 0.7f);
                c.drawText(rr, x, y + lineH * 1.85f, text);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
        if (plot.height() < fs * 1.2f) return;
        // The curve: the hours where there are any, else each day's high at the middle of its column.
        int from = firstDayAt(days);
        List<float[]> pts = new ArrayList<float[]>();
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Hour h : hours) {
            float pos = (h.at - from) / 24f;
            if (pos < 0 || pos > n || Double.isNaN(h.temp)) continue;
            pts.add(new float[]{pos, (float) h.temp});
        }
        if (pts.size() < 2) {
            pts.clear();
            for (int i = 0; i < n; i++) if (!Double.isNaN(days.get(i).high)) pts.add(new float[]{i + 0.5f, (float) days.get(i).high});
        }
        // Past the hours, the days' highs and lows carry the curve on (the week is longer than 48 hours).
        if (!pts.isEmpty()) {
            float last = pts.get(pts.size() - 1)[0];
            for (int i = (int) Math.ceil(last); i < n; i++) {
                Day d = days.get(i);
                if (!Double.isNaN(d.low)) pts.add(new float[]{i + 0.25f, (float) d.low});
                if (!Double.isNaN(d.high)) pts.add(new float[]{i + 0.65f, (float) d.high});
            }
        }
        if (pts.size() < 2) return;
        for (float[] p : pts) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
        if (hi - lo < 4) { double m = (hi + lo) / 2; hi = m + 2; lo = m - 2; }
        line.setStrokeWidth(Math.max(2, fs * 0.18f));
        float px = 0, py = 0;
        for (int i = 0; i < pts.size(); i++) {
            float[] p = pts.get(i);
            float x = r.left + p[0] * w;
            float y = plot.bottom - (float) ((p[1] - lo) / (hi - lo)) * plot.height();
            if (i > 0) {
                line.setColor(hue(celsius(t, (p[1] + pts.get(i - 1)[1]) / 2)));
                c.drawLine(px, py, x, y, line);
            }
            px = x;
            py = y;
        }
    }

    // ---- the card

    /** The card: the weather now, its attributes, the Daily | Hourly key and the chart. */
    void drawCard(Canvas c, final Tessera.Tile t, RectF body, float fs, int fg, int dim, int track, int raised, Hits hits) {
        // The card's own head already names it and says the condition and temperature: today's high and low and feels
        // like first, then the attributes the entity has, on one line: humidity, wind, air pressure, visibility.
        float y = body.top + fs;
        StringBuilder b = new StringBuilder(highLow(t));
        attr(b, "Humidity", t.number("humidity"), "%");
        Double wind = t.number("wind_speed");
        if (wind != null) {
            String u = t.text("wind_speed_unit");
            b.append(b.length() > 0 ? "   " : "").append("Wind ").append(Style.number(wind)).append(u == null ? "" : " " + u);
        }
        Double pr = t.number("pressure");
        if (pr != null) {
            String u = t.text("pressure_unit");
            b.append(b.length() > 0 ? "   " : "").append("Pressure ").append(Style.number(pr)).append(u == null ? "" : " " + u);
        }
        Double vis = t.number("visibility");
        if (vis != null) {
            String u = t.text("visibility_unit");
            b.append(b.length() > 0 ? "   " : "").append("Visibility ").append(Style.number(vis)).append(u == null ? "" : " " + u);
        }
        if (b.length() > 0) {
            text.setTypeface(regular);
            text.setTextSize(fs * 0.85f);
            text.setColor(dim);
            c.drawText(TextUtils.ellipsize(b.toString(), text, body.width(), TextUtils.TruncateAt.END).toString(), body.left, y, text);
            y += fs * 1.2f;
        }
        List<Day> days = days(t);
        final List<Hour> hours = hours(t);
        if (days.isEmpty()) return;
        // Daily | Hourly, where there are hours.
        if (!hours.isEmpty()) {
            float kw = Math.min(fs * 5.5f, body.width() / 2), kh = fs * 2.0f;
            String[] names = {"Daily", "Hourly"};
            for (int i = 0; i < 2; i++) {
                final boolean h = i == 1;
                RectF k = new RectF(body.left + kw * i, y, body.left + kw * (i + 1) - fs * 0.3f, y + kh);
                fill.setStyle(hourly == h ? Paint.Style.FILL : Paint.Style.STROKE);
                fill.setStrokeWidth(Math.max(1, fs * 0.08f));
                fill.setColor(hourly == h ? (fg & 0x00FFFFFF) | 0x26000000 : track);
                c.drawRoundRect(k, kh / 2, kh / 2, fill);
                fill.setStyle(Paint.Style.FILL);
                text.setTypeface(hourly == h ? medium : regular);
                text.setTextSize(fs * 0.9f);
                text.setColor(hourly == h ? fg : dim);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(names[i], k.centerX(), k.centerY() + fs * 0.32f, text);
                text.setTextAlign(Paint.Align.LEFT);
                hits.tap(k, new Runnable() {
                    public void run() { hourly = h; }
                });
            }
            y += kh + fs * 0.8f;
        } else {
            hourly = false;
        }
        RectF chart = new RectF(body.left, y, body.right, body.bottom);
        if (hourly) drawHours(c, t, hours, chart, fs, fg, dim, track);
        else drawWeek(c, t, days, hours, chart, fs, fg, dim, track, true);
    }

    private static void attr(StringBuilder b, String name, Double v, String unit) {
        if (v == null) return;
        b.append(b.length() > 0 ? "   " : "").append(name).append(" ").append(Style.number(v)).append(unit);
    }

    /** The next hours: a column every few hours (its time, icon, temperature, rain) over one curve through every hour. */
    private void drawHours(Canvas c, Tessera.Tile t, List<Hour> hours, RectF r, float fs, int fg, int dim, int track) {
        int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        List<Hour> next = new ArrayList<Hour>();
        for (Hour h : hours) if (h.at >= now && next.size() < 24) next.add(h);
        if (next.size() < 2) return;
        int cols = Math.max(2, Math.min(8, (int) (r.width() / (fs * 3f))));
        int step = Math.max(1, (int) Math.ceil(next.size() / (double) cols));
        int shown = (next.size() + step - 1) / step;
        float w = r.width() / shown, lineH = fs * 1.1f;
        fill.setColor(track);
        for (int i = 1; i < shown; i++) c.drawRect(r.left + w * i - 0.5f, r.top, r.left + w * i + 0.5f, r.bottom, fill);
        text.setTextAlign(Paint.Align.CENTER);
        float headH = lineH + fs * 1.7f, footH = lineH * 2f;
        for (int i = 0; i < shown; i++) {
            Hour h = next.get(i * step);
            float x = r.left + w * i + w / 2;
            text.setTypeface(i == 0 ? medium : regular);
            text.setTextSize(fs * 0.8f);
            text.setColor(i == 0 ? fg : dim);
            c.drawText(i == 0 ? "Now" : String.format(java.util.Locale.US, "%02d:00", h.at % 24), x, r.top + fs * 0.8f, text);
            glyphs.setTextSize(fs * 1.5f);
            glyphs.setColor(sky(h.cond));
            c.drawText(Icons.sure(context, Icons.weather(h.cond)), x, r.top + lineH + fs * 1.2f, glyphs);
            text.setTypeface(medium);
            text.setTextSize(fs * 0.9f);
            text.setColor(fg);
            c.drawText(deg(h.temp), x, r.bottom - footH + lineH, text);
            if (h.rain > 0) {
                text.setTypeface(regular);
                text.setTextSize(fs * 0.7f);
                text.setColor(0xFF1E88E5);
                c.drawText((Math.round(h.rain * 10) / 10.0) + " mm", x, r.bottom - footH + lineH * 1.9f, text);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
        RectF plot = new RectF(r.left, r.top + headH + fs * 0.3f, r.right, r.bottom - footH - fs * 0.2f);
        if (plot.height() < fs) return;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Hour h : next) if (!Double.isNaN(h.temp)) { lo = Math.min(lo, h.temp); hi = Math.max(hi, h.temp); }
        if (lo > hi) return;
        if (hi - lo < 4) { double m = (hi + lo) / 2; hi = m + 2; lo = m - 2; }
        line.setStrokeWidth(Math.max(2, fs * 0.18f));
        float px = 0, py = 0;
        boolean first = true;
        for (int i = 0; i < next.size(); i++) {
            Hour h = next.get(i);
            if (Double.isNaN(h.temp)) continue;
            float x = r.left + (i + 0.5f) / step * w;
            float y = plot.bottom - (float) ((h.temp - lo) / (hi - lo)) * plot.height();
            if (!first) {
                line.setColor(hue(celsius(t, h.temp)));
                c.drawLine(px, py, x, y, line);
            }
            first = false;
            px = x;
            py = y;
        }
    }
}
