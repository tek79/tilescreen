package app.tilescreen.ui;

import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.tilescreen.core.Json;

/**
 * Thermo dial (alexcoscia/tessera-thermo-dial 0.2.0): a tile for a climate entity with the target large and the keys
 * -, mode and + under it (2x1 or wider), and a card with a dial to drag, as Home Assistant's thermostat card. The ring is
 * amber while heating, blue while cooling and grey when idle or off. A drag sends one climate.set_temperature when the
 * finger lifts; - and + send one 600 ms after the last tap; a target Home Assistant does not report within 5 s goes
 * back to the real one. Range and step are the entity's (min_temp, max_temp, target_temp_step).
 */
final class ThermoDialPlugin extends PluginKit.Plugin {
    ThermoDialPlugin() { super("thermo_dial", "0.2.0"); }

    @Override
    String[] tiles() { return new String[]{"dial"}; }

    private static final long DEBOUNCE_MS = 600, CONFIRM_MS = 5000;

    /** What one thermostat shows now, in tenths of a degree; kept per entity between frames. */
    private static final class Dial {
        int lo = 70, hi = 350, step = 5;
        Integer reported, current, shown, pending;
        String state = "", action = "";
        final List<String> modes = new ArrayList<String>();
        long debounceAt, confirmAt;
        boolean dragging;
    }

    private final Map<String, Dial> dials = new HashMap<String, Dial>();

    private Dial read(PluginKit.Ctx c) {
        Dial d = dials.get(c.entity);
        if (d == null) {
            d = new Dial();
            dials.put(c.entity, d);
        }
        Map<String, Object> data = c.data();
        Map<String, Object> a = Json.obj(data.get("attributes"));
        if (a == null) a = new LinkedHashMap<String, Object>();
        double lo = num(a.get("min_temp"), 7), hi = num(a.get("max_temp"), 35), st = num(a.get("target_temp_step"), 0.5);
        if (hi > lo) { d.lo = tenths(lo); d.hi = tenths(hi); }
        d.step = tenths(st) > 0 ? tenths(st) : 5;
        if (d.hi - d.lo < d.step) d.hi = d.lo + d.step;
        d.reported = a.get("temperature") instanceof Double ? tenths((Double) a.get("temperature")) : null;
        d.current = a.get("current_temperature") instanceof Double ? tenths((Double) a.get("current_temperature")) : null;
        d.state = data.get("state") instanceof String ? (String) data.get("state") : "";
        d.action = a.get("hvac_action") instanceof String ? (String) a.get("hvac_action") : "";
        d.modes.clear();
        List<Object> modes = Json.arr(a.get("hvac_modes"));
        if (modes != null) for (Object m : modes) if (m instanceof String && d.modes.size() < 8) d.modes.add((String) m);
        long now = SystemClock.uptimeMillis();
        if (d.pending != null && d.reported != null && d.pending.intValue() == d.reported.intValue()) settle(d);
        if (d.debounceAt != 0 && now >= d.debounceAt) {
            d.debounceAt = 0;
            send(c, d, d.shown);
        }
        if (d.confirmAt != 0 && now >= d.confirmAt) {
            settle(d);
            d.shown = d.reported;
        }
        if (d.pending == null && !d.dragging) d.shown = d.reported;
        if (d.debounceAt != 0 || d.confirmAt != 0) c.host.redraw(200);
        return d;
    }

    private static double num(Object v, double fallback) { return v instanceof Double ? (Double) v : fallback; }

    private static int tenths(double degrees) { return (int) Math.round(degrees * 10); }

    private static String format(int t) {
        int a = Math.abs(t);
        return (t < 0 ? "-" : "") + a / 10 + "." + a % 10;
    }

    private static int steps(Dial d) { return (d.hi - d.lo) / d.step; }

    private static int toStep(int t, Dial d) {
        int clamped = Math.min(Math.max(t, d.lo), d.hi);
        return Math.min((clamped - d.lo + d.step / 2) / d.step, steps(d));
    }

    private static int fromStep(int n, Dial d) { return d.lo + Math.min(Math.max(n, 0), steps(d)) * d.step; }

    private static boolean unavailable(Dial d) {
        return d.state.isEmpty() || d.state.equals("unavailable") || d.state.equals("unknown");
    }

    /** heating, cooling, idle, off or unavailable (mood_of). */
    private static String mood(Dial d) {
        if (unavailable(d)) return "unavailable";
        if (d.state.equals("off")) return "off";
        if (d.action.equals("heating")) return "heating";
        if (d.action.equals("cooling")) return "cooling";
        if (d.action.isEmpty()) {
            if (d.state.equals("heat")) return "heating";
            if (d.state.equals("cool")) return "cooling";
        }
        return "idle";
    }

    private static String modeWord(String mode) {
        if (mode.equals("heat_cool")) return "Heat/Cool";
        if (mode.equals("fan_only")) return "Fan";
        return mode.isEmpty() ? "" : Character.toUpperCase(mode.charAt(0)) + mode.substring(1).replace('_', ' ');
    }

    private static void settle(Dial d) {
        d.pending = null;
        d.confirmAt = 0;
    }

    private void send(PluginKit.Ctx c, Dial d, Integer value) {
        if (value == null || d.reported != null && value.intValue() == d.reported.intValue()) {
            settle(d);
            return;
        }
        d.pending = value;
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity_id", c.entity);
        data.put("temperature", format(value));
        if (!c.host.perform("climate.set_temperature", data, null)) c.host.say("Allow this device to perform Home Assistant actions");
        d.confirmAt = SystemClock.uptimeMillis() + CONFIRM_MS;
        c.host.redraw(200);
    }

    private void bump(PluginKit.Ctx c, Dial d, int direction) {
        Integer base = d.shown != null ? d.shown : d.reported;
        if (base == null) return;
        d.shown = fromStep(toStep(base, d) + direction, d);
        d.pending = d.shown;
        d.debounceAt = SystemClock.uptimeMillis() + DEBOUNCE_MS;
        c.host.redraw(DEBOUNCE_MS + 20);
    }

    private void nextMode(PluginKit.Ctx c, Dial d) {
        if (d.modes.size() < 2) return;
        int i = d.modes.indexOf(d.state);
        String next = d.modes.get(i < 0 ? 0 : (i + 1) % d.modes.size());
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity_id", c.entity);
        data.put("hvac_mode", next);
        c.host.perform("climate.set_hvac_mode", data, null);
    }

    // ---- the tile: the name, the target large, and - mode + under it ----

    @Override
    boolean drawTile(String tile, final PluginKit.Ctx c) {
        if (!tile.equals("dial")) return false;
        final Dial d = read(c);
        final boolean off = unavailable(d);
        RectF b = c.box;
        float keyH = Math.max(9 * c.mm, c.fs * 2.4f);
        boolean keys = b.width() > c.fs * 9 && b.height() >= c.fs * 1.3f + c.fs * 2.2f + keyH + c.u;
        c.words(c.fit(c.tile.name, b.width(), c.text), b.left, b.top + c.fs, c.fs * 0.9f, c.dim, c.regular, Paint.Align.LEFT);
        float top = b.top + c.fs * 1.3f, bottom = keys ? b.bottom - keyH - c.u : b.bottom;
        String value = !off && d.shown != null ? format(d.shown) + "°" : "-";
        float size = Math.min((bottom - top) * 0.75f, c.fs * 3.2f);
        while (size > c.fs && c.width(value, size, c.light) > b.width()) size -= 1;
        c.words(value, b.centerX(), top + (bottom - top) / 2 + size * 0.36f, size, off ? c.dim : c.ink, c.light, Paint.Align.CENTER);
        if (keys) {
            final int count = d.modes.size() >= 2 ? 3 : 2;
            float gap = c.u, kw = (b.width() - gap * (count - 1)) / count;
            int slot = 0;
            for (int i = 0; i < 3; i++) {
                if (i == 1 && count == 2) continue;
                final int which = i;
                RectF k = new RectF(b.left + slot * (kw + gap), b.bottom - keyH, b.left + slot * (kw + gap) + kw, b.bottom);
                slot++;
                c.fill.setColor(c.raised);
                c.c.drawRoundRect(k, keyH / 2, keyH / 2, c.fill);
                String word = i == 0 ? "-" : i == 2 ? "+" : modeWord(d.state);
                float ws = i == 1 ? c.fs * 0.9f : c.fs * 1.3f;
                c.words(c.fit(word, k.width() - c.u, c.text), k.centerX(), k.centerY() + ws * 0.36f, ws, off ? c.dim : c.ink,
                        c.medium, Paint.Align.CENTER);
                if (!off) c.host.tap(k, new Runnable() {
                    public void run() {
                        if (which == 1) nextMode(c, d);
                        else bump(c, d, which == 0 ? -1 : 1);
                    }
                });
            }
        }
        return true;
    }

    @Override
    void tapTile(String tile, PluginKit.Ctx c) {
        Dial d = dials.get(c.entity);
        if (d != null && unavailable(d)) return;
        c.host.openCard(id, "dial", c.entity, c.index);
    }

    // ---- the card: the dial ----

    @Override
    String cardTitle(String card) { return ""; }

    @Override
    boolean drawCard(String card, final PluginKit.Ctx c) {
        if (!card.equals("dial")) return false;
        final Dial d = read(c);
        final boolean off = unavailable(d);
        RectF b = c.box;
        float dia = Math.min(b.width(), b.height()) - 2 * c.u;
        final RectF ring = new RectF(b.centerX() - dia / 2, b.centerY() - dia / 2, b.centerX() + dia / 2, b.centerY() + dia / 2);
        float width = Math.min(Math.max(5 * c.mm, dia / 9), dia / 6);
        RectF arc = new RectF(ring.left + width / 2, ring.top + width / 2, ring.right - width / 2, ring.bottom - width / 2);
        String m = mood(d);
        int tone = m.equals("heating") ? Style.ORANGE : m.equals("cooling") ? Style.BLUE : 0xFF9AA0A8;
        c.line.setStrokeCap(Paint.Cap.ROUND);
        c.line.setStrokeWidth(width);
        c.line.setColor(c.raised);
        c.c.drawArc(arc, 135, 270, false, c.line);
        int s = d.shown == null ? 0 : toStep(d.shown, d), n = Math.max(1, steps(d));
        float sweep = 270f * s / n;
        if (!off && d.shown != null) {
            c.line.setColor(tone);
            c.c.drawArc(arc, 135, Math.max(0.5f, sweep), false, c.line);
            // The knob.
            double angle = Math.toRadians(135 + sweep);
            float kx = arc.centerX() + (float) Math.cos(angle) * arc.width() / 2, ky = arc.centerY() + (float) Math.sin(angle) * arc.height() / 2;
            c.fill.setColor(0xFFFFFFFF);
            c.c.drawCircle(kx, ky, width / 2 - c.u * 0.3f, c.fill);
            c.line.setStrokeWidth(Math.max(1, c.u * 0.25f));
            c.line.setColor(tone);
            c.c.drawCircle(kx, ky, width / 2 - c.u * 0.3f, c.line);
        }
        c.line.setStrokeCap(Paint.Cap.BUTT);
        // The target large in the middle, "Now 19.8°" and what it is doing under it.
        float big = Math.min(dia * 0.2f, c.fs * 4.5f);
        String value = !off && d.shown != null ? format(d.shown) + "°" : "-";
        c.words(value, ring.centerX(), ring.centerY() + big * 0.2f, big, off ? c.dim : c.ink, c.light, Paint.Align.CENTER);
        if (d.current != null) {
            c.words("Now " + format(d.current) + "°", ring.centerX(), ring.centerY() + big * 0.2f + c.fs * 1.6f, c.fs * 1.05f, c.dim,
                    c.regular, Paint.Align.CENTER);
        }
        String word = m.equals("unavailable") ? "Unavailable" : Character.toUpperCase(m.charAt(0)) + m.substring(1);
        String icon = m.equals("heating") ? "fire" : m.equals("cooling") ? "snowflake" : m.equals("off") ? "power" : null;
        float sy = ring.centerY() + big * 0.2f + c.fs * 3.1f;
        float ww = c.width(word, c.fs * 0.95f, c.regular), iw = icon == null ? 0 : c.fs * 1.3f;
        float x0 = ring.centerX() - (ww + iw) / 2;
        if (icon != null) c.icon(icon, x0 + c.fs * 0.5f, sy, c.fs * 1.05f, tone);
        c.words(word, x0 + iw, sy, c.fs * 0.95f, c.dim, c.regular, Paint.Align.LEFT);
        if (!off) c.host.drag(ring, new PluginKit.Drag() {
            public void at(float x, float y, boolean done) {
                double angle = Math.toDegrees(Math.atan2(y - ring.centerY(), x - ring.centerX()));
                double from = (angle - 135 + 720) % 360;
                if (from > 270) from = from > 315 ? 0 : 270;
                d.dragging = !done;
                d.shown = fromStep((int) Math.round(from / 270 * steps(d)), d);
                if (done) send(c, d, d.shown);
                c.host.redraw(0);
            }
        });
        return true;
    }
}
