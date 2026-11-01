package app.tilescreen.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/**
 * A light's presets (docs/LIGHT_PRESETS.md, module_light_presets.h): round keys that each set the light to a white shade or
 * a colour and a brightness, a brightness alone, an effect, or the lamp's own preset. The tile's options carry them as
 * the add-on stores them: `{b, k}`, `{b, h, s}` or `{b}`, with `fx` (an effect) and `rw` ([entity, value] pairs).
 */
final class LightPresets {
    static final int MOST = 4;

    static final class Preset {
        int b = -1, k = -1;
        double h = -1, s = -1;
        String fx;
        final List<String[]> rows = new ArrayList<String[]>();

        boolean white() { return k > 0; }
        boolean colour() { return h >= 0 && s >= 0; }
        /** No colour to show: an effect, or the lamp's own preset. */
        boolean named() { return !white() && !colour() && (fx != null || !rows.isEmpty()); }

        /** The first two letters of its effect's or its own preset's name. */
        String letters() {
            String name = fx != null ? fx : rows.isEmpty() ? "" : rows.get(0)[1];
            name = name.trim();
            return name.length() <= 2 ? name : name.substring(0, 2);
        }
    }

    private LightPresets() {}

    static List<Preset> of(Tessera.Tile t) {
        List<Preset> out = new ArrayList<Preset>();
        List<Object> list = Json.arr(t.options.get("presets"));
        if (list == null) return out;
        for (Object o : list) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) continue;
            Preset p = new Preset();
            Integer b = Json.integer(m, "b"), k = Json.integer(m, "k");
            Double h = Json.dbl(m, "h"), s = Json.dbl(m, "s");
            if (b != null) p.b = Math.max(1, Math.min(100, b));
            if (k != null) p.k = k;
            if (h != null && s != null) { p.h = h; p.s = s; }
            p.fx = Json.str(m, "fx");
            List<Object> rows = Json.arr(m.get("rw"));
            if (rows != null) {
                for (Object r : rows) {
                    List<Object> pair = Json.arr(r);
                    if (pair != null && pair.size() == 2) p.rows.add(new String[]{String.valueOf(pair.get(0)), value(pair.get(1))});
                }
            }
            if (p.b < 0 && !p.named()) continue;
            out.add(p);
            if (out.size() == MOST) break;
        }
        return out;
    }

    private static String value(Object v) {
        if (v instanceof Double && ((Double) v) == Math.rint((Double) v)) return String.valueOf(((Double) v).longValue());
        return String.valueOf(v);
    }

    static boolean levels(Tessera.Tile t) { return Boolean.TRUE.equals(t.options.get("preset_levels")); }

    /** The key's colour: the white shade or the colour at full value, as Home Assistant computes them; 0 for none. */
    static int colour(Preset p) {
        if (p.white()) return temperature(p.k);
        if (p.colour()) return hs(p.h, p.s);
        return 0;
    }

    /** color_temperature_to_rgb (Home Assistant's color util, Tanner Helland's fit). */
    static int temperature(int kelvin) {
        double t = Math.max(1000, Math.min(40000, kelvin)) / 100.0;
        double r, g, b;
        if (t <= 66) {
            r = 255;
            g = 99.4708025861 * Math.log(t) - 161.1195681661;
        } else {
            r = 329.698727446 * Math.pow(t - 60, -0.1332047592);
            g = 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        }
        if (t >= 66) b = 255;
        else if (t <= 19) b = 0;
        else b = 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        return rgb(r, g, b);
    }

    /** color_hs_to_RGB at full value. */
    static int hs(double hue, double sat) {
        double h = ((hue % 360) + 360) % 360 / 60.0, s = Math.max(0, Math.min(100, sat)) / 100.0;
        int i = (int) Math.floor(h);
        double f = h - i, p = 1 - s, q = 1 - s * f, w = 1 - s * (1 - f);
        double r, g, b;
        switch (i) {
            case 0: r = 1; g = w; b = p; break;
            case 1: r = q; g = 1; b = p; break;
            case 2: r = p; g = 1; b = w; break;
            case 3: r = p; g = q; b = 1; break;
            case 4: r = w; g = p; b = 1; break;
            default: r = 1; g = p; b = q; break;
        }
        return rgb(r * 255, g * 255, b * 255);
    }

    private static int rgb(double r, double g, double b) {
        int R = (int) Math.round(Math.max(0, Math.min(255, r))), G = (int) Math.round(Math.max(0, Math.min(255, g))),
                B = (int) Math.round(Math.max(0, Math.min(255, b)));
        return 0xFF000000 | (R << 16) | (G << 8) | B;
    }

    /** Relative luminance, for dark ink on a light key and a hairline round a white one. */
    static double luminance(int c) {
        double r = ((c >> 16) & 255) / 255.0, g = ((c >> 8) & 255) / 255.0, b = (c & 255) / 255.0;
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
    }

    private static double lin(double v) { return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }

    /**
     * Whether the light shows this preset now: on, its brightness within a percent, its white shade within 50 K, its
     * colour within a few degrees, and its effect running. Settings of the lamp's device are not on the tile, so a preset
     * of them alone never shows as current.
     */
    static boolean shows(Tessera.Tile t, Preset p) {
        if (!"on".equals(t.state)) return false;
        if (p.fx == null && !p.rows.isEmpty() && p.b < 0) return false;
        if (p.b > 0) {
            Double br = t.number("brightness");
            if (br == null || Math.abs(Math.round(br / 2.55) - p.b) > 1) return false;
        }
        if (p.fx != null && !p.fx.equals(t.text("effect"))) return false;
        if (p.white()) {
            Double k = t.number("color_temp_kelvin");
            if (k == null || Math.abs(k - p.k) > 50) return false;
        } else if (p.colour()) {
            List<Object> hs = t.list("hs_color");
            if (hs == null || hs.size() < 2 || !(hs.get(0) instanceof Double) || !(hs.get(1) instanceof Double)) return false;
            double dh = Math.abs((Double) hs.get(0) - p.h) % 360;
            if (Math.min(dh, 360 - dh) > 4 || Math.abs((Double) hs.get(1) - p.s) > 4) return false;
        }
        return true;
    }

    /** One action of a tap: a service, its plain fields and its templated ones. */
    static final class Call {
        final String service;
        final Map<String, String> data = new LinkedHashMap<String, String>(), templates = new LinkedHashMap<String, String>();
        Call(String service) { this.service = service; }
    }

    /** What a tap sends: light.turn_on with the brightness, the shade or colour and the effect, then each setting. */
    static List<Call> calls(Tessera.Tile t, Preset p) {
        List<Call> out = new ArrayList<Call>();
        if (p.b > 0 || p.white() || p.colour() || p.fx != null) {
            Call on = new Call("light.turn_on");
            on.data.put("entity_id", t.entity);
            if (p.b > 0) on.data.put("brightness_pct", String.valueOf(p.b));
            if (p.white()) on.data.put("color_temp_kelvin", String.valueOf(p.k));
            else if (p.colour()) on.templates.put("hs_color", "{{ [" + num(p.h) + ", " + num(p.s) + "] }}");
            if (p.fx != null) on.data.put("effect", p.fx);
            out.add(on);
        }
        for (String[] row : p.rows) {
            String domain = row[0].contains(".") ? row[0].substring(0, row[0].indexOf('.')) : "";
            Call set;
            if (domain.equals("select") || domain.equals("input_select")) {
                set = new Call(domain + ".select_option");
                set.data.put("option", row[1]);
            } else if (domain.equals("number") || domain.equals("input_number")) {
                set = new Call(domain + ".set_value");
                set.data.put("value", row[1]);
            } else {
                continue;
            }
            set.data.put("entity_id", row[0]);
            out.add(set);
        }
        return out;
    }

    private static String num(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }
}
