package app.tilescreen.ui;

import java.util.Locale;

import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/** Colors and words: what a tile looks like in each state. */
final class Style {
    // The look: Tessera's Dark mode (black page, graphite cards, soft white text) or its light one.
    static int BG, CARD, CARD_ON, RAISED, TEXT, DIM, FAINT, LINE;
    private static Boolean dark;

    static {
        use(true);
    }

    static boolean isDark() { return dark == null || dark; }

    static void use(boolean d) {
        if (dark != null && dark == d) return;
        dark = d;
        if (d) {
            BG = 0xFF0F1115; CARD = 0xFF1C1F26; CARD_ON = 0xFF262A33; RAISED = 0xFF2C313B;
            TEXT = 0xFFF2F4F8; DIM = 0xFF9AA3B2; FAINT = 0xFF5D6573; LINE = 0xFF343A46;
        } else {
            BG = 0xFFE9ECF1; CARD = 0xFFFFFFFF; CARD_ON = 0xFFFFFFFF; RAISED = 0xFFEDEFF3;
            TEXT = 0xFF1E2430; DIM = 0xFF5D6573; FAINT = 0xFFB4BBC6; LINE = 0xFFD5D9E0;
        }
    }

    static final int DARK_TEXT = 0xFF1E2430, DARK_DIM = 0xFF4A5363,
            AMBER = 0xFFFFC857, BLUE = 0xFF4FC3F7, ORANGE = 0xFFFF8A50, GREEN = 0xFF81C784,
            RED = 0xFFFF6B6B, PURPLE = 0xFFB39DDB, TEAL = 0xFF4DD0C4;

    /** Tessera's pastel tile colors (core.TILE_BACKGROUNDS). */
    static int pastel(String name) {
        if (name == null) return 0;
        if (name.equals("red")) return 0xFFFADADD;
        if (name.equals("orange")) return 0xFFFFE1C6;
        if (name.equals("yellow")) return 0xFFFFF0C2;
        if (name.equals("green")) return 0xFFD9EEDC;
        if (name.equals("mint")) return 0xFFD5F0EA;
        if (name.equals("blue")) return 0xFFD9EAFB;
        if (name.equals("purple")) return 0xFFE9DDF5;
        if (name.equals("pink")) return 0xFFF7DDEC;
        if (name.equals("gray") || name.equals("grey")) return 0xFFE5E7EB;
        return 0;
    }

    /** A named color for alerts and buttons; the accent otherwise. */
    static int named(String name, int fallback) {
        int p = pastel(name);
        if (p != 0) return p;
        if (name == null) return fallback;
        if (name.equals("white")) return 0xFFFFFFFF;
        return fallback;
    }

    static boolean active(Tessera.Tile t) {
        String s = t.state == null ? "" : t.state;
        String d = t.domain();
        if (d.equals("climate")) return !s.equals("off") && !s.equals("unavailable");
        if (d.equals("cover")) return s.equals("open") || s.equals("opening");
        if (d.equals("lock")) return s.equals("unlocked") || s.equals("open");
        // Home Assistant's stateActive(), as Tessera's Tile::active: a player is active in every state but off, standby,
        // unknown and unavailable, so a TV or a receiver that is on shows its colour.
        if (d.equals("media_player")) return !s.isEmpty() && !s.equals("off") && !s.equals("standby")
                && !s.equals("unknown") && !s.equals("unavailable");
        if (d.equals("alarm_control_panel")) return s.startsWith("armed") || s.equals("triggered");
        if (d.equals("person") || d.equals("device_tracker")) return s.equals("home");
        if (d.equals("timer")) return s.equals("active");
        if (d.equals("vacuum")) return s.equals("cleaning");
        return s.equals("on") || s.equals("open") || s.equals("home") || s.equals("playing");
    }

    /**
     * Tessera's Tile::available, turned round: "unavailable", or "unknown" for all but the entities whose "unknown" still
     * takes a tap - a blind without feedback (Home Assistant's own cover keys stay on), a scene and a button.
     */
    static boolean unavailable(Tessera.Tile t) {
        if ("unavailable".equals(t.state)) return true;
        if (!"unknown".equals(t.state)) return false;
        String d = t.domain();
        return !(d.equals("sensor") || d.equals("cover") || d.equals("scene") || d.equals("button") || d.equals("input_button"));
    }

    static int accent(Tessera.Tile t) {
        String d = t.domain();
        if (d.equals("light")) {
            java.util.List<Object> hs = t.list("hs_color");
            if (hs != null && hs.size() == 2 && hs.get(0) instanceof Double && hs.get(1) instanceof Double) {
                float sat = ((Double) hs.get(1)).floatValue() / 100f;
                if (sat > 0.15f) return hsv(((Double) hs.get(0)).floatValue(), Math.max(0.45f, sat * 0.8f), 1f);
            }
            return AMBER;
        }
        if (d.equals("climate")) {
            String action = t.text("hvac_action");
            String s = action != null ? action : t.state;
            if (s.startsWith("cool")) return BLUE;
            if (s.startsWith("dry") || s.startsWith("fan")) return TEAL;
            return ORANGE;
        }
        if (d.equals("cover") || d.equals("person") || d.equals("device_tracker")) return GREEN;
        if (d.equals("lock")) return "locked".equals(t.state) ? GREEN : RED;
        if (d.equals("alarm_control_panel")) return "triggered".equals(t.state) ? RED : GREEN;
        if (d.equals("media_player")) return BLUE;
        if (d.equals("binary_sensor")) return ORANGE;
        if (d.equals("fan") || d.equals("humidifier")) return TEAL;
        return BLUE;
    }

    static int hsv(float h, float s, float v) {
        float[] hsv = {h, s, v};
        return android.graphics.Color.HSVToColor(hsv);
    }

    static String words(String state) {
        if (state == null || state.isEmpty()) return "";
        if (state.equals("on")) return "On";
        if (state.equals("off")) return "Off";
        if (state.equals("unavailable")) return "Unavailable";
        if (state.equals("unknown")) return "Unknown";
        if (state.equals("heat_cool")) return "Heat/Cool";
        if (state.equals("not_home")) return "Away";
        if (state.equals("above_horizon")) return "Above horizon";
        if (state.equals("below_horizon")) return "Below horizon";
        if (state.equals("partlycloudy")) return "Partly cloudy";
        if (state.equals("clear-night")) return "Clear";
        String s = state.replace('_', ' ').replace('-', ' ');
        return s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    static String number(Double v) {
        if (v == null) return "";
        double d = v;
        if (Math.abs(d - Math.rint(d)) < 1e-9) return Long.toString((long) Math.rint(d));
        if (Math.abs(d * 10 - Math.rint(d * 10)) < 1e-9) return String.format(Locale.US, "%.1f", d);
        return String.format(Locale.US, "%.2f", d);
    }

    /** The line under a tile's name. */
    static String stateLine(Tessera.Tile t) {
        String d = t.domain(), s = t.state;
        if (unavailable(t)) return words(s);
        if (d.equals("light")) {
            Double b = t.number("brightness");
            if ("on".equals(s) && b != null) return Math.round(b / 255.0 * 100) + "%";
            return words(s);
        }
        if (d.equals("fan")) {
            Double p = t.number("percentage");
            if ("on".equals(s) && p != null) return number(p) + "%";
            return words(s);
        }
        if (d.equals("cover")) {
            Double p = t.number("current_position");
            if (p != null && p > 0 && p < 100) return words(s) + " · " + number(p) + "%";
            return words(s);
        }
        if (d.equals("climate")) {
            String unit = degree(t);
            Double cur = t.number("current_temperature"), target = t.number("temperature");
            StringBuilder b = new StringBuilder(words(t.text("hvac_action") != null ? t.text("hvac_action") : s));
            if (cur != null) b.append(" · ").append(number(cur)).append(unit);
            if (target != null && !"off".equals(s)) b.append(" → ").append(number(target)).append(unit);
            return b.toString();
        }
        if (d.equals("media_player")) {
            String title = t.text("media_title");
            if (title != null && !title.isEmpty() && ("playing".equals(s) || "paused".equals(s))) return title;
            return words(s);
        }
        if (d.equals("weather")) {
            Double temp = t.number("temperature");
            String unit = t.text("temperature_unit");
            return words(s) + (temp == null ? "" : " · " + number(temp) + (unit == null ? "°" : unit));
        }
        if (d.equals("sensor") || d.equals("number") || d.equals("input_number")) {
            String unit = t.text("unit_of_measurement");
            return s + (unit == null || unit.isEmpty() ? "" : (unit.startsWith("°") || unit.equals("%") ? "" : " ") + unit);
        }
        if (d.equals("vacuum")) {
            double b = VacuumCard.battery(t);
            return VacuumCard.stateWord(s) + (Double.isNaN(b) ? "" : " · " + Math.round(b) + "%");
        }
        if (d.equals("timer")) {
            String r = t.text("remaining");
            return words(s) + (r != null && !"idle".equals(s) ? " · " + r : "");
        }
        if (d.equals("binary_sensor")) {
            String dc = t.text("device_class");
            boolean on = "on".equals(s);
            if ("door".equals(dc) || "window".equals(dc) || "garage_door".equals(dc) || "opening".equals(dc)) return on ? "Open" : "Closed";
            if ("motion".equals(dc) || "occupancy".equals(dc) || "presence".equals(dc)) return on ? "Detected" : "Clear";
            if ("moisture".equals(dc)) return on ? "Wet" : "Dry";
            if ("lock".equals(dc)) return on ? "Unlocked" : "Locked";
            if ("connectivity".equals(dc)) return on ? "Connected" : "Disconnected";
            if ("battery".equals(dc)) return on ? "Low" : "Normal";
            if ("problem".equals(dc) || "safety".equals(dc) || "smoke".equals(dc) || "gas".equals(dc)) return on ? "Detected" : "OK";
            return words(s);
        }
        if (d.equals("scene") || d.equals("script") || d.equals("button") || d.equals("input_button")) return "";
        if (t.entity.startsWith("screen.")) return "";
        return words(s);
    }

    static String degree(Tessera.Tile t) {
        String u = t.text("temperature_unit");
        return u != null && u.startsWith("°") ? u.substring(0, 1) : "°";
    }

    /** True when the tile's main text is a number worth showing large. */
    static boolean bigValue(Tessera.Tile t) {
        String d = t.domain();
        if (!(d.equals("sensor") || d.equals("number") || d.equals("input_number"))) return false;
        return Json.tryParse(t.state) instanceof Double;
    }

    private Style() {}
}
