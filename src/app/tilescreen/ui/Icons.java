package app.tilescreen.ui;

import android.content.Context;
import android.graphics.Typeface;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

import app.tilescreen.core.Tessera;

/** Material Design Icons: the font, names to glyphs, and the default icon of each kind of tile. */
final class Icons {
    private static Typeface font;
    private static Map<String, String> names;

    static synchronized Typeface font(Context c) {
        if (font == null) font = Typeface.createFromAsset(c.getAssets(), "mdi.ttf");
        return font;
    }

    private static synchronized Map<String, String> names(Context c) {
        if (names != null) return names;
        Map<String, String> m = new HashMap<String, String>(9000);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("mdi-names.txt"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp > 0) m.put(line.substring(0, sp), line.substring(sp + 1));
            }
            r.close();
        } catch (Exception ignored) {
        }
        names = m;
        return m;
    }

    /** A glyph from a hex codepoint ("F0335"). */
    static String glyph(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        try {
            int cp = Integer.parseInt(hex.trim(), 16);
            if (cp < 0xF0000 || cp > 0xF2FFF) return null;
            return new String(Character.toChars(cp));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A glyph from "mdi:name", "name" or a hex codepoint. */
    static String named(Context c, String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.startsWith("mdi:")) n = n.substring(4);
        String hex = names(c).get(n);
        if (hex != null) return glyph(hex);
        return glyph(n);
    }

    /** Like named, but never null, for drawing. */
    static String sure(Context c, String name) {
        String g = named(c, name);
        return g == null ? "" : g;
    }

    /** The icon a tile shows: Tessera's choice when it sent one, else a default for its kind and state. */
    static String forTile(Context c, Tessera.Tile t) {
        String g = glyph(t.icon);
        if (g == null) g = glyph(Tessera_str(t.options.get("icon")));
        if (g == null) g = named(c, t.text("icon"));
        if (g == null) g = named(c, defaultName(t));
        return g == null ? named(c, "help-circle-outline") : g;
    }

    private static String Tessera_str(Object o) { return o instanceof String ? (String) o : null; }

    static String defaultName(Tessera.Tile t) {
        String d = t.domain(), s = t.state, dc = t.text("device_class");
        boolean on = Style.active(t);
        if (t.entity.startsWith("screen.page_")) return "arrow-right-circle-outline";
        if (t.entity.equals("screen.clock") || t.entity.equals("screen.nightstand")) return "clock-outline";
        if (t.entity.equals("screen.settings")) return "cog-outline";
        if (t.entity.equals("screen.call")) return "phone";
        if (t.entity.equals("screen.energy")) return "lightning-bolt";
        if (t.entity.equals("screen.map")) return "map-outline";
        if (d.equals("light")) return on ? "lightbulb" : "lightbulb-outline";
        if (d.equals("switch") || d.equals("input_boolean")) return on ? "toggle-switch" : "toggle-switch-off-outline";
        if (d.equals("fan")) return on ? "fan" : "fan-off";
        if (d.equals("climate")) return "thermostat";
        if (d.equals("water_heater")) return "water-boiler";
        if (d.equals("humidifier")) return "air-humidifier";
        if (d.equals("cover")) {
            boolean open = "open".equals(s) || "opening".equals(s);
            if ("garage".equals(dc)) return open ? "garage-open" : "garage";
            if ("door".equals(dc) || "gate".equals(dc)) return open ? "door-open" : "door-closed";
            if ("blind".equals(dc) || "curtain".equals(dc)) return open ? "blinds-open" : "blinds";
            return open ? "window-shutter-open" : "window-shutter";
        }
        if (d.equals("lock")) return "locked".equals(s) ? "lock" : "lock-open-variant";
        if (d.equals("media_player")) return on ? "speaker-play" : "speaker";
        if (d.equals("scene")) return "palette-outline";
        if (d.equals("script")) return "script-text-outline";
        // An automation that is unavailable looks it: Home Assistant's confused robot.
        if (d.equals("automation")) return "unavailable".equals(s) ? "robot-confused" : "robot-outline";
        if (d.equals("button") || d.equals("input_button")) return "gesture-tap-button";
        if (d.equals("number") || d.equals("input_number")) return "ray-vertex";
        if (d.equals("select") || d.equals("input_select")) return "format-list-bulleted";
        if (d.equals("person") || d.equals("device_tracker")) return "home".equals(s) ? "account" : "account-outline";
        if (d.equals("weather")) return weather(s);
        if (d.equals("sun")) return "above_horizon".equals(s) ? "white-balance-sunny" : "weather-night";
        if (d.equals("vacuum")) return "robot-vacuum";
        if (d.equals("lawn_mower")) return "robot-mower";
        if (d.equals("camera") || d.equals("image")) return "cctv";
        if (d.equals("alarm_control_panel")) return AlarmPanel.icon(s);
        if (d.equals("timer")) return "timer-outline";
        if (d.equals("remote")) return "remote";
        if (d.equals("update")) return "package-up";
        if (d.equals("calendar")) return "calendar";
        if (d.equals("valve")) return on ? "valve-open" : "valve-closed";
        if (d.equals("siren")) return "bullhorn";
        if (d.equals("binary_sensor")) {
            if ("door".equals(dc) || "garage_door".equals(dc)) return on ? "door-open" : "door-closed";
            if ("window".equals(dc)) return on ? "window-open" : "window-closed";
            if ("motion".equals(dc) || "occupancy".equals(dc) || "presence".equals(dc)) return on ? "motion-sensor" : "motion-sensor-off";
            if ("moisture".equals(dc)) return on ? "water-alert" : "water-off";
            if ("smoke".equals(dc)) return "smoke-detector";
            return on ? "checkbox-marked-circle" : "checkbox-blank-circle-outline";
        }
        if (d.equals("sensor")) {
            if ("temperature".equals(dc)) return "thermometer";
            if ("humidity".equals(dc)) return "water-percent";
            if ("power".equals(dc) || "current".equals(dc) || "voltage".equals(dc)) return "flash";
            if ("energy".equals(dc)) return "lightning-bolt";
            if ("battery".equals(dc)) return "battery";
            if ("illuminance".equals(dc)) return "brightness-5";
            if ("pressure".equals(dc) || "atmospheric_pressure".equals(dc)) return "gauge";
            if ("carbon_dioxide".equals(dc)) return "molecule-co2";
            if ("timestamp".equals(dc)) return "clock-outline";
            return "eye-outline";
        }
        return "help-circle-outline";
    }

    static String weather(String condition) {
        if (condition == null) return "weather-partly-cloudy";
        if (condition.equals("clear-night")) return "weather-night";
        if (condition.equals("cloudy")) return "weather-cloudy";
        if (condition.equals("fog")) return "weather-fog";
        if (condition.equals("hail")) return "weather-hail";
        if (condition.equals("lightning")) return "weather-lightning";
        if (condition.equals("lightning-rainy")) return "weather-lightning-rainy";
        if (condition.equals("partlycloudy")) return "weather-partly-cloudy";
        if (condition.equals("pouring")) return "weather-pouring";
        if (condition.equals("rainy")) return "weather-rainy";
        if (condition.equals("snowy")) return "weather-snowy";
        if (condition.equals("snowy-rainy")) return "weather-snowy-rainy";
        if (condition.equals("sunny")) return "weather-sunny";
        if (condition.equals("windy")) return "weather-windy";
        if (condition.equals("windy-variant")) return "weather-windy-variant";
        return "weather-partly-cloudy";
    }

    private Icons() {}
}
