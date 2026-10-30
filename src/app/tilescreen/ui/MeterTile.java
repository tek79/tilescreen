package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/**
 * A sensor's meter (firmware 0.56.0, docs/METER.md, meter_card.h): where its value stands in a range, as a ring open
 * at the bottom, a tape (a ruler with a pointer) or a bar of cells. The range, the segments with their colours and the
 * thresholds are the tile's options (meter.py); a value outside the range stands at its end.
 */
final class MeterTile {
    private static final int GREEN = 0xFF4CAF50, ORANGE = 0xFFFF9800, RED = 0xFFF44336, POINTER = 0xFFFF6F22;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final android.content.Context context;

    MeterTile(android.content.Context context, TextPaint glyphs) {
        this.context = context;
        this.glyphs = glyphs;
        line.setStyle(Paint.Style.STROKE);
    }

    /** The meter's range, segments and colours, read from the tile's options. */
    private static final class Plan {
        double low = 0, high = 100, value = Double.NaN;
        int[] colors;
        double[] thresholds;
        String style = "ring";

        int colorAt(double v) {
            int i = 0;
            if (thresholds != null) while (i < thresholds.length && v >= thresholds[i]) i++;
            return colors[Math.min(i, colors.length - 1)];
        }

        float fraction(double v) {
            if (Double.isNaN(v)) return 0;
            return (float) Math.max(0, Math.min(1, (v - low) / (high - low)));
        }
    }

    private static Plan plan(Tessera.Tile t, int accent) {
        Plan p = new Plan();
        Map<String, Object> o = t.options;
        String style = Json.str(o, "meter");
        if ("tape".equals(style) || "bar".equals(style)) p.style = style;
        Double lo = Json.dbl(o, "min"), hi = Json.dbl(o, "max");
        if (lo != null && hi != null && hi > lo) {
            p.low = lo;
            p.high = hi;
        }
        try {
            p.value = Double.parseDouble(t.state);
        } catch (Exception ignored) {
        }
        Integer seg = Json.integer(o, "segments");
        int segments = seg == null ? 1 : Math.max(1, Math.min(3, seg));
        List<Object> colors = Json.arr(o == null ? null : o.get("colors"));
        p.colors = new int[segments];
        int[] defaults = segments == 1 ? new int[]{accent} : segments == 2 ? new int[]{GREEN, RED} : new int[]{GREEN, ORANGE, RED};
        for (int i = 0; i < segments; i++) {
            Object c = colors != null && i < colors.size() ? colors.get(i) : null;
            p.colors[i] = c instanceof Double ? 0xFF000000 | (((Double) c).intValue() & 0xFFFFFF) : defaults[i];
        }
        if (segments > 1) {
            // A screen that gets no thresholds puts them at 80 % and 90 % of the range.
            List<Object> th = Json.arr(o == null ? null : o.get("thresholds"));
            p.thresholds = new double[segments - 1];
            double[] shares = {0.8, 0.9};
            for (int i = 0; i < segments - 1; i++) {
                Object v = th != null && i < th.size() ? th.get(i) : null;
                p.thresholds[i] = v instanceof Double ? (Double) v : p.low + (p.high - p.low) * shares[segments == 2 ? 0 : i];
            }
        }
        return p;
    }

    private String valueText(Tessera.Tile t, Plan p) {
        String unit = t.text("unit_of_measurement");
        String word = Json.str(t.extra, "sw");  // Home Assistant's words for a duration ("1h 30m")
        if (word != null && !word.isEmpty()) return word;
        String v = Double.isNaN(p.value) ? t.state : t.state;
        if (unit == null || unit.isEmpty()) return v;
        return unit.equals("%") ? v + "%" : v + " " + unit;
    }


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

    private static int dim(int color) { return (color & 0xFFFFFF) | 0x40000000; }

    /** The card's own colour, which unlit cells and the pointer's edge are mixed with. */
    private int cardColor = 0xFFFFFFFF;

    /**
     * The meter in `box` (the card's content): `glass` is how many pixels here a pixel of the board's glass is, for the
     * sizes the layout counts in the board's pixels; `history` the last hours, for a ring with room for them.
     */
    void draw(Canvas c, Tessera.Tile t, RectF box, float fs, int ink, int muted, int track, int accent, boolean dead, float glass,
              List<Double> history, int card) {
        Plan p = plan(t, accent);
        cardColor = card;
        // Laid out in the board's own pixels, as the mockup and the firmware count them, then drawn at this glass's size.
        float g = Math.max(0.25f, glass);
        RectF b = new RectF(0, 0, box.width() / g, box.height() / g);
        c.save();
        c.translate(box.left, box.top);
        c.scale(g, g);
        if (p.style.equals("tape")) tape(c, t, p, b, fs, ink, muted, track, dead, 1);
        else if (p.style.equals("bar")) bar(c, t, p, b, fs, ink, muted, track, dead, 1);
        else ring(c, t, p, b, fs, ink, muted, track, dead, 1, history);
        c.restore();
    }

    // ---- the layout of the editor's mockup (web/src/model/meter.ts meterLayout), which follows meter_card.h ----
    private static final float LINE = 1.2f;

    private static float clamp(float v, float lo, float hi) { return Math.min(hi, Math.max(lo, v)); }

    /** A text's width in em at the value's weight. */
    private float ems(String s) {
        text.setTypeface(medium);
        text.setTextSize(100);
        return text.measureText(s == null ? "" : s) / 100f;
    }

    /** The largest size up to `most` at which a text fits a width, never under `least`. */
    private float fitSize(String s, float width, float most, float least) {
        return clamp(Math.min(most, width / Math.max(ems(s), 0.5f)), least, most);
    }

    /** A text box of the mockup: top `y`, `size` letters on a line of 1.2; left-aligned at x or centred in x..x+w. */
    private void box(Canvas c, String s, float x, float y, float w, float size, boolean centre, int color, Typeface f) {
        text.setTypeface(f);
        text.setTextSize(size);
        text.setColor(color);
        String shown = TextUtils.ellipsize(s == null ? "" : s, text, Math.max(1, w), TextUtils.TruncateAt.END).toString();
        float base = y + size * (LINE - 1) / 2 + size * 0.93f;
        text.setTextAlign(centre ? Paint.Align.CENTER : Paint.Align.LEFT);
        c.drawText(shown, centre ? x + w / 2 : x, base, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** The ring's arc, open at the bottom: 240°, from 150° clockwise, from one share of it to another. */
    private void ringArc(Canvas c, float cx, float cy, float r, float from, float to) {
        arc(c, new RectF(cx - r, cy - r, cx + r, cy + r), 150 + 240 * from, 240 * (to - from));
    }

    // ---- the ring: the tile's icon in a tinted circle in a 240° ring; the words under it, or beside it on a long card,
    // and the last hours beside it on a roomy wide one
    private void ring(Canvas c, Tessera.Tile t, Plan p, RectF b, float fs, int ink, int muted, int track, boolean dead, float glass,
                      List<Double> history) {
        String valueText = valueText(t, p), name = t.name;
        float w = b.width(), h = b.height();
        boolean roomy = history.size() > 1 && w / glass >= 360 && w > 1.3f * h;
        float side = roomy ? w / 2 : w;
        boolean beside = side > 1.3f * h;
        float cx, cy, r, stroke;
        float vx, vy, vw, size, nx = 0, ny = 0, nw = 0, nameSize = 0;
        boolean centred, showName;
        if (beside) {
            stroke = clamp(h / 14, 2, 14);
            float need = ems(valueText) * 10 + clamp(side * 0.05f, 4, 16);
            r = Math.max(Math.max(4, h * 0.2f), Math.min(Math.min((h - stroke) / 1.5f, (side * 0.45f - stroke) / 2), (side - need - stroke) / 2));
            cx = r + stroke / 2;
            cy = (h - 1.5f * r) / 2 + r;
            float x = cx + r + stroke / 2 + clamp(side * 0.05f, 4, 16), room = side - x;
            size = fitSize(valueText, room, clamp(h * 0.3f, 11, 40), 9);
            nameSize = fitSize(name, room, clamp(size * 0.62f, 8, 16), 7);
            showName = size * LINE + nameSize * LINE <= h;
            if (!showName) size = Math.min(size, h / LINE);
            float top = (h - (size * LINE + (showName ? nameSize * LINE : 0))) / 2;
            vx = x; vy = top; vw = room; centred = false;
            nx = x; ny = top + size * LINE; nw = room;
        } else {
            r = Math.min(side / 2, h / 2);
            for (int i = 0; i < 40; i++) {
                float st = clamp(r / 7, 2, 16), sz = clamp(2 * r * 0.16f, 10, 34), ns = clamp(sz * 0.62f, 8, 16);
                float needed = st / 2 + r + 0.42f * r + sz * LINE + ns * LINE;
                if (needed <= h && 2 * r + st <= side) break;
                r *= 0.95f;
            }
            stroke = clamp(r / 7, 2, 16);
            size = clamp(2 * r * 0.16f, 10, 34);
            nameSize = fitSize(name, side, clamp(size * 0.62f, 8, 16), 7);
            size = fitSize(valueText, side, size, 8);
            float cy0 = stroke / 2 + r;
            boolean under = ems(valueText) * size > 1.4f * r && cy0 + 0.5f * r + stroke / 2 + 2 + size * LINE <= h;
            float top = under ? cy0 + 0.5f * r + stroke / 2 + 2 : cy0 + 0.42f * r;
            showName = top + size * LINE + nameSize * LINE <= h;
            if (!showName) top = Math.min(top, h - size * LINE);
            if (top + size * LINE > h) { size = Math.max(6, (h - top) / LINE); showName = false; }
            float total = top + size * LINE + (showName ? nameSize * LINE : 0), drop = Math.max(0, (h - total) / 2);
            cx = side / 2;
            cy = cy0 + drop;
            vx = 0; vy = top + drop; vw = side; centred = true;
            nx = 0; ny = top + drop + size * LINE; nw = side;
        }
        float ox = b.left, oy = b.top;
        double v = p.value;
        boolean known = !dead && !Double.isNaN(v);
        int color = known ? p.colorAt(Math.max(p.low, Math.min(p.high, v))) : muted;
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(stroke);
        line.setColor(track);
        ringArc(c, ox + cx, oy + cy, r, 0, 1);
        float frac = known ? p.fraction(v) : 0;
        if (frac > 0) {
            line.setColor(color);
            ringArc(c, ox + cx, oy + cy, r, 0, Math.max(frac, 0.005f));
        }
        fill.setColor((color & 0xFFFFFF) | 0x33000000);
        c.drawCircle(ox + cx, oy + cy, r * 0.48f, fill);
        glyphs.setColor(color);
        glyphs.setTextSize(r * 0.42f * 1.3f);
        c.drawText(Icons.forTile(context, t), ox + cx, oy + cy + r * 0.42f * 0.45f, glyphs);
        box(c, valueText, ox + vx, oy + vy, vw, size, centred, ink, medium);
        if (showName) box(c, name, ox + nx, oy + ny, nw, nameSize, centred, muted, regular);
        if (roomy) {
            // The last hours, their line and the room under it in the value's colour, beside the ring.
            float lx = w / 2 + clamp(w * 0.02f, 4, 12), lw = w / 2 - clamp(w * 0.02f, 4, 12);
            double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
            for (double hv : history) { low = Math.min(low, hv); high = Math.max(high, hv); }
            Path path = new Path(), area = new Path();
            float pad = 2;
            for (int i = 0; i < history.size(); i++) {
                float x = ox + lx + (float) i / Math.max(1, history.size() - 1) * lw;
                float y = oy + h - pad - (float) (high > low ? (history.get(i) - low) / (high - low) : 0.5) * (h - 2 * pad - 4);
                if (i == 0) { path.moveTo(x, y); area.moveTo(x, oy + h); area.lineTo(x, y); }
                else { path.lineTo(x, y); area.lineTo(x, y); }
            }
            area.lineTo(ox + lx + lw, oy + h);
            area.close();
            fill.setColor((color & 0xFFFFFF) | 0x33000000);
            c.drawPath(area, fill);
            line.setColor(color);
            line.setStrokeWidth(Math.max(1.5f, 1.5f * glass));
            line.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(path, line);
        }
    }

    // ---- the tape: the name in small capitals, the value large over a still ruler, one pointer at the value
    private void tape(Canvas c, Tessera.Tile t, Plan p, RectF b, float fs, int ink, int muted, int track, boolean dead, float glass) {
        String unit = Json.str(t.extra, "sw") != null && !Json.str(t.extra, "sw").isEmpty() ? "" : t.text("unit_of_measurement");
        if (unit == null) unit = "";
        String number = Json.str(t.extra, "sw") != null && !Json.str(t.extra, "sw").isEmpty() ? Json.str(t.extra, "sw") : t.state;
        String name = t.name;
        boolean percent = "%".equals(unit);
        String[] ends = percent ? null : new String[]{number(p.low), number(p.high)};
        float w = b.width(), h = b.height(), hair = Math.max(1, glass);
        String caps = name.toUpperCase(Locale.getDefault());
        float capsEms = ems(caps) + 0.04f * name.length();
        float nameSize = clamp(Math.min(clamp(h * 0.09f, 8, 14), w / Math.max(capsEms, 0.5f)), 7, 14);
        float size = fitSize(number + (unit.isEmpty() ? "" : "MM"), w, clamp(h * 0.34f, 12, 72), 10);
        float lng = clamp(h * 0.12f, 6, 28);
        float endSize = clamp(nameSize, 8, 13);
        float pointerOver = Math.max(2, lng * 0.25f), rulerRoom = pointerOver + lng;
        float endsRoom = ends != null ? endSize * LINE + 2 : 0;
        boolean stacked = nameSize * LINE + size * LINE + rulerRoom + endsRoom + 6 <= h;
        if (!stacked) {
            size = fitSize(number + (unit.isEmpty() ? "" : "MM") + name, w, clamp(h * 0.4f, 10, 40), 9);
            nameSize = clamp(size * 0.55f, 7, 12);
            lng = clamp(h * 0.18f, 4, 20);
            if (size * LINE + lng * 1.25f + endsRoom + 4 > h) endsRoom = 0;
            if (size * LINE + lng * 1.25f + 4 > h) size = Math.max(7, (h - lng * 1.25f - 4) / LINE);
        }
        boolean showEnds = ends != null && endsRoom > 0;
        float endWidth = 0;
        if (showEnds) {
            text.setTypeface(medium);
            text.setTextSize(endSize);
            endWidth = Math.max(text.measureText(ends[0]), text.measureText(ends[1]));
        }
        float x0 = Math.max(hair, endWidth / 2), x1 = w - x0;
        float over = Math.max(2, lng * 0.25f), below = Math.min(over, 2);
        float base = h - (showEnds ? endsRoom : 0) - below - hair;
        float wordsH = stacked ? nameSize * LINE + size * LINE : size * LINE;
        float top = Math.max(0, (base - lng - over - wordsH) / 2);
        float middle = Math.max(nameSize * LINE, (nameSize * LINE + base - lng - over - size * LINE) / 2);
        float ox = b.left, oy = b.top;
        // The words: the name above (or beside) the value, the unit small after the number.
        float nx = 0, ny, nw, vx, vy, vw;
        if (stacked) { ny = 0; nw = w; vx = 0; vy = middle; vw = w; }
        else { ny = top + (size - nameSize) * 0.8f; nw = w / 2; vx = w / 2; vy = top; vw = w / 2; }
        text.setTypeface(medium);
        text.setTextSize(nameSize);
        box(c, caps, ox + nx, oy + ny, nw, nameSize, false, muted, regular);
        text.setTypeface(regular);
        text.setTextSize(size);
        float numW = text.measureText(number);
        text.setTextSize(size * 0.45f);
        float unitW = unit.isEmpty() ? 0 : text.measureText(unit) + size * 0.15f;
        float startX = stacked ? vx + Math.max(0, (vw - numW - unitW) / 2) : vx;
        box(c, number, ox + startX, oy + vy, vw, size, false, ink, regular);
        if (!unit.isEmpty() && !dead) {
            float base2 = oy + vy + size * (LINE - 1) / 2 + size * 0.93f;
            text.setTypeface(regular);
            text.setTextSize(size * 0.45f);
            text.setColor(muted);
            c.drawText(unit, ox + startX + numW + size * 0.15f, base2, text);
        }
        // The ruler: a hairline with long ticks on round steps and short ones between.
        line.setStrokeCap(Paint.Cap.BUTT);
        line.setStrokeWidth(hair);
        line.setColor(track);
        c.drawLine(ox + x0, oy + base, ox + x1, oy + base, line);
        for (float[] tick : tapeTicks(p.low, p.high, x0, x1, 5 * glass)) {
            line.setColor(tick[1] > 0 ? muted : track);
            c.drawLine(ox + tick[0], oy + base, ox + tick[0], oy + base - (tick[1] > 0 ? lng : lng * 0.5f), line);
        }
        boolean known = !dead && !Double.isNaN(p.value);
        if (known) {
            float px = ox + x0 + p.fraction(p.value) * (x1 - x0);
            int pointer = p.colors.length > 1 || (t.options != null && Json.arr(t.options.get("colors")) != null) ? p.colorAt(p.value) : POINTER;
            float pw = Math.max(2, 3 * glass);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeWidth(pw + 2 * hair);
            line.setColor(cardColor);
            c.drawLine(px, oy + base - lng - over, px, oy + base + below, line);
            line.setStrokeWidth(pw);
            line.setColor(pointer);
            c.drawLine(px, oy + base - lng - over, px, oy + base + below, line);
        }
        if (showEnds) {
            text.setTypeface(medium);
            text.setTextSize(endSize);
            text.setColor(muted);
            text.setTextAlign(Paint.Align.CENTER);
            float ey = oy + base + below + 2 + endSize * (LINE - 1) / 2 + endSize * 0.93f;
            c.drawText(ends[0], ox + x0, ey, text);
            c.drawText(ends[1], ox + x1, ey, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** A tape's still ruler (meter.ts tapeTicks): {x, long (1/0)} for at most five long ticks on round steps of the range
     * (its ends among them), short ones between where they stand at least `room` apart. */
    private static List<float[]> tapeTicks(double low, double high, float x0, float x1, float room) {
        List<float[]> ticks = new java.util.ArrayList<float[]>();
        double span = high - low, width = x1 - x0;
        if (!(span > 0) || !(width > 0)) return ticks;
        double power = Math.pow(10, Math.floor(Math.log10(span)));
        double step = span;
        for (double m : new double[]{0.1, 0.2, 0.25, 0.5, 1, 2, 2.5, 5, 10}) {
            if (between(low, high, m * power).size() <= 3) { step = m * power; break; }
        }
        List<Double> longs = new java.util.ArrayList<Double>();
        longs.add(low);
        longs.addAll(between(low, high, step));
        longs.add(high);
        for (double v : longs) ticks.add(new float[]{(float) (x0 + (v - low) / span * width), 1});
        for (int parts : new int[]{5, 2}) {
            double gap = step / parts / span * width;
            if (gap < room) continue;
            for (int i = 0; i < longs.size() - 1; i++) {
                for (double v = longs.get(i) + step / parts; v < longs.get(i + 1) - step / parts / 2; v += step / parts) {
                    ticks.add(new float[]{(float) (x0 + (v - low) / span * width), 0});
                }
            }
            break;
        }
        return ticks;
    }

    private static List<Double> between(double low, double high, double step) {
        List<Double> out = new java.util.ArrayList<Double>();
        for (double v = Math.ceil(low / step - 1e-9) * step; v < high - step / 2; v += step) if (v > low + step / 2) out.add(v);
        return out;
    }

    // ---- the bar: lit cells in the colour of their place, unlit ones a dimmed tint of it; standing up on a tall card
    private void bar(Canvas c, Tessera.Tile t, Plan p, RectF b, float fs, int ink, int muted, int track, boolean dead, float glass) {
        String valueText = valueText(t, p), title = t.name;
        float w = b.width(), h = b.height(), ox = b.left, oy = b.top;
        float cell = clamp(4 * glass, 2, 8), gap = Math.max(1, cell * 0.6f);
        boolean known = !dead && !Double.isNaN(p.value);
        float frac = known ? p.fraction(p.value) : 0;
        boolean standing = h > 1.1f * w;
        if (standing) {
            float barW = Math.max(cell * 3, w * 0.3f), x = w - barW, room = x - clamp(w * 0.05f, 4, 12);
            float size = fitSize(valueText, room, clamp(w * 0.16f, 10, 36), 8), nameSize = fitSize(title, room, clamp(size * 0.62f, 8, 16), 7);
            int count = Math.max(4, (int) ((h + gap) / (cell + gap)));
            box(c, valueText, ox, oy, room, size, false, ink, medium);
            box(c, title, ox, oy + size * LINE, room, nameSize, false, muted, regular);
            cells(c, p, ox + x, oy, barW, h, count, gap, true, frac, known, muted);
            return;
        }
        float size = fitSize(valueText, w, clamp(Math.min(h * 0.24f, w * 0.14f), 10, 40), 8), nameSize = fitSize(title, w, clamp(size * 0.62f, 8, 16), 7);
        float least = Math.max(6, h * 0.22f);
        float wordsH = size * LINE + nameSize * LINE + clamp(h * 0.05f, 2, 10);
        float nx = 0, ny = size * LINE, nw = w;
        boolean showName = true;
        if (h - wordsH < least) {
            size = Math.min(size, Math.max(7, (h - least - 2) / LINE));
            nameSize = clamp(size * 0.7f, 7, 14);
            wordsH = size * LINE + 2;
            float valueW = Math.min(w * 0.55f, ems(valueText) * size + 4);
            nx = valueW + 4; ny = (size - nameSize) * 0.8f; nw = Math.max(0, w - valueW - 4);
            if (nw < nameSize * 2) showName = false;
        }
        int count = Math.max(4, (int) ((w + gap) / (cell + gap)));
        box(c, valueText, ox, oy, showName && nx > 0 ? nx - 4 : w, size, false, ink, medium);
        if (showName) box(c, title, ox + nx, oy + ny, nw, nameSize, false, muted, regular);
        cells(c, p, ox, oy + wordsH, w, Math.max(2, h - wordsH), count, gap, false, frac, known, muted);
    }

    private void cells(Canvas c, Plan p, float x, float y, float w, float h, int count, float gap, boolean vertical, float frac,
                       boolean known, int off) {
        float length = vertical ? h : w, pitch = (length + gap) / count, size = pitch - gap;
        for (int i = 0; i < count; i++) {
            float middle = (i + 0.5f) / count;
            int paint = known ? p.colorAt(p.low + middle * (p.high - p.low)) : off;
            fill.setColor(known && middle <= frac ? paint : mix(paint, cardColor, 0.22f));
            if (vertical) c.drawRect(x, y + h - (i + 1) * pitch + gap, x + w, y + h - (i + 1) * pitch + gap + size, fill);
            else c.drawRect(x + i * pitch, y, x + i * pitch + size, y + h, fill);
        }
    }

    private static int mix(int a, int b, float weight) {
        int out = 0xFF000000;
        for (int shift : new int[]{16, 8, 0}) {
            int v = Math.round(((a >> shift) & 255) * weight + ((b >> shift) & 255) * (1 - weight));
            out |= v << shift;
        }
        return out;
    }

    private static String number(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.US, "%.1f", v);
    }

    /** An arc as a path of short lines from `start` (degrees, clockwise from 3 o'clock) through `sweep`. */
    private void arc(Canvas c, RectF oval, float start, float sweep) {
        Path path = new Path();
        int steps = Math.max(6, (int) (Math.abs(sweep) / 4));
        float cx = oval.centerX(), cy = oval.centerY(), r = oval.width() / 2;
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(start + sweep * i / steps);
            float x = cx + r * (float) Math.cos(a), y = cy + r * (float) Math.sin(a);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, line);
    }
}
