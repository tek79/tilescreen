package app.tilescreen.ui;

import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.tilescreen.core.Json;

/**
 * AV wall panel controls (MrEcosse/Tessera-Plugin-AV-Wall-Panel-Controls 0.1.1): five tiles of keys for a panel on the
 * wall. Transport (skip back, stop, play, pause, play/pause, skip forward, eject), a D-pad (arrows round OK, with back,
 * home, menu and guide), volume or channel keys, a number pad, and a selector grid of an entity's options. Every key runs
 * the action typed for it ("service [entity] [field=value]", panel_command.h); a key lights blue while its action goes
 * out, red when it could not; keys go grey while the entity is unavailable; a key is never higher than 14 mm nor wider
 * than half again its height.
 */
final class AvPanelPlugin extends PluginKit.Plugin {
    AvPanelPlugin() { super("av_wall_panel_controls", "0.1.1"); }

    @Override
    String[] tiles() { return new String[]{"transport", "dpad", "volume", "numpad", "grid"}; }

    // ---- a key's text: action, its own entity, one field (panel_command.h) ----

    static final class Command {
        String action = "", target = "", key = "", value = "";
    }

    private static boolean wordChar(char c) { return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_'; }

    private static boolean dotted(String s) {
        if (s.length() < 3 || s.length() > 64) return false;
        int dot = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (dot >= 0) return false;
                dot = i;
            } else if (!wordChar(c)) {
                return false;
            }
        }
        return dot > 0 && dot + 1 < s.length() && !(s.charAt(0) >= '0' && s.charAt(0) <= '9');
    }

    /** null for an empty text, a Command with an empty action for one that is not an action. */
    static Command parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        Command out = new Command();
        String t = text.trim();
        String[] first = t.split("\\s+", 2);
        if (!dotted(first[0])) return out;
        String rest = first.length > 1 ? first[1].trim() : "";
        String target = "";
        if (!rest.isEmpty()) {
            String[] second = rest.split("\\s+", 2);
            if (!second[0].contains("=")) {
                if (!dotted(second[0])) return out;
                target = second[0];
                rest = second.length > 1 ? second[1].trim() : "";
            }
        }
        if (!rest.isEmpty()) {
            int eq = rest.indexOf('=');
            if (eq <= 0 || eq > 32) return out;
            for (int i = 0; i < eq; i++) if (!wordChar(rest.charAt(i))) return out;
            String value = rest.substring(eq + 1).trim();
            if (value.isEmpty() || value.length() > 64) return out;
            out.key = rest.substring(0, eq);
            out.value = value;
        }
        out.action = first[0];
        out.target = target;
        return out;
    }

    /** Lit keys: blue while an action goes out, red when it could not, by tile and key. */
    private final Map<String, long[]> flashes = new HashMap<String, long[]>();

    private boolean run(PluginKit.Ctx c, Command cmd, String flash, String digit, String code) {
        boolean ok = cmd != null && !cmd.action.isEmpty() && (!cmd.target.isEmpty() || !c.entity.isEmpty());
        if (ok) {
            Map<String, String> data = new LinkedHashMap<String, String>();
            data.put("entity_id", cmd.target.isEmpty() ? c.entity : cmd.target);
            if (!cmd.key.isEmpty()) {
                String v = cmd.value.replace("{digit}", digit == null ? "" : digit).replace("{code}", code == null ? "" : code);
                data.put(cmd.key, v.length() > 64 ? v.substring(0, 64) : v);
            }
            ok = c.host.perform(cmd.action, data, null);
        }
        flashes.put(c.index + ":" + flash, new long[]{SystemClock.uptimeMillis() + (ok ? 400 : 900), ok ? 1 : 0});
        c.host.redraw(ok ? 420 : 920);
        return ok;
    }

    /** The colour of a key now: lit blue or red for a moment, else its own ground. */
    private int keyGround(PluginKit.Ctx c, String flash, boolean on) {
        long[] f = flashes.get(c.index + ":" + flash);
        if (f != null && SystemClock.uptimeMillis() < f[0]) return f[1] == 1 ? Style.BLUE : Style.RED;
        return on ? Style.BLUE : c.raised;
    }

    private static boolean unavailable(PluginKit.Ctx c) {
        Object s = c.data().get("state");
        return "unavailable".equals(s) || "unknown".equals(s) && c.entity.startsWith("media_player.");
    }

    /** A key's size within its cell: never higher than 14 mm, never wider than half again its height. */
    private static RectF key(PluginKit.Ctx c, float cx, float cy, float cw, float ch) {
        float h = Math.min(ch, 14 * c.mm), w = Math.min(cw, h * 1.5f);
        return new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
    }

    private void drawKey(final PluginKit.Ctx c, RectF k, String icon, String word, final String flash, boolean on,
                         boolean grey, final Runnable tap) {
        c.fill.setColor(keyGround(c, flash, on));
        float r = Math.min(k.width(), k.height()) * 0.28f;
        c.c.drawRoundRect(k, r, r, c.fill);
        long[] f = flashes.get(c.index + ":" + flash);
        boolean lit = on || f != null && SystemClock.uptimeMillis() < f[0];
        int ink = grey ? c.dim : lit ? 0xFFFFFFFF : c.ink;
        if (icon != null) {
            float s = Math.min(k.height() * 0.55f, k.width() * 0.55f);
            c.icon(icon, k.centerX(), k.centerY() + s * 0.36f, s, ink);
        } else {
            float s = Math.min(k.height() * 0.42f, c.fs * 1.4f);
            while (s > 6 && c.width(word, s, c.medium) > k.width() - c.u) s -= 1;
            c.words(word, k.centerX(), k.centerY() + s * 0.36f, s, ink, c.medium, Paint.Align.CENTER);
        }
        if (!grey && tap != null) c.host.tap(k, tap);
    }

    @Override
    boolean drawTile(String tile, PluginKit.Ctx c) {
        if (tile.equals("transport")) transport(c);
        else if (tile.equals("dpad")) dpad(c);
        else if (tile.equals("volume")) volume(c);
        else if (tile.equals("numpad")) numpad(c);
        else if (tile.equals("grid")) grid(c);
        else return false;
        return true;
    }

    // ---- transport: one row of media keys, wrapping on a narrow tile ----

    private static final String[][] TRANSPORT = {{"previous", "skip-previous"}, {"stop", "stop"}, {"play", "play"},
            {"pause", "pause"}, {"play_pause", "play-pause"}, {"next", "skip-next"}, {"eject", null}};

    private void transport(final PluginKit.Ctx c) {
        List<String[]> keys = new ArrayList<String[]>();
        for (String[] k : TRANSPORT) if (parse(c.option(k[0], "")) != null) keys.add(k);
        if (keys.isEmpty()) {
            c.words("Give a key an action", c.box.left, c.box.centerY(), c.fs * 0.9f, c.dim, c.regular, Paint.Align.LEFT);
            return;
        }
        String state = c.data().get("state") instanceof String ? (String) c.data().get("state") : "";
        boolean media = c.entity.startsWith("media_player."), grey = unavailable(c);
        RectF b = c.box;
        int n = keys.size(), rows = 1;
        while (rows < 3 && b.width() / Math.ceil(n / (double) rows) < Math.min(b.height() / rows, 14 * c.mm) * 0.9f) rows++;
        int cols = (int) Math.ceil(n / (double) rows);
        float cw = b.width() / cols, ch = b.height() / rows;
        for (int i = 0; i < n; i++) {
            final String[] k = keys.get(i);
            int row = i / cols, col = i % cols, inRow = Math.min(cols, n - row * cols);
            float x0 = b.centerX() - inRow * cw / 2;
            RectF r = key(c, x0 + col * cw + cw / 2, b.top + row * ch + ch / 2, cw - c.u, ch - c.u);
            boolean on = media && ((k[0].equals("play") || k[0].equals("play_pause")) && state.equals("playing")
                    || k[0].equals("pause") && state.equals("paused"));
            drawKey(c, r, k[1], "Eject", k[0], on, grey, new Runnable() {
                public void run() { AvPanelPlugin.this.run(c, parse(c.option(k[0], "")), k[0], null, null); }
            });
        }
    }

    // ---- the D-pad: three by three, a key's place kept when it has no action ----

    private static final String[][] DPAD = {{"back", "arrow-left"}, {"up", "chevron-up"}, {"home", "home"},
            {"left", "chevron-left"}, {"ok", null}, {"right", "chevron-right"},
            {"menu", "menu"}, {"down", "chevron-down"}, {"guide", "television-guide"}};

    private void dpad(final PluginKit.Ctx c) {
        RectF b = c.box;
        float cell = Math.min(b.width() / 3, b.height() / 3);
        float x0 = b.centerX() - cell * 1.5f, y0 = b.centerY() - cell * 1.5f;
        boolean grey = unavailable(c), any = false;
        for (int i = 0; i < 9; i++) {
            final String[] k = DPAD[i];
            if (parse(c.option(k[0], "")) == null) continue;
            any = true;
            RectF r = key(c, x0 + (i % 3) * cell + cell / 2, y0 + (i / 3) * cell + cell / 2, cell - c.u, cell - c.u);
            drawKey(c, r, k[1], "OK", k[0], false, grey, new Runnable() {
                public void run() { AvPanelPlugin.this.run(c, parse(c.option(k[0], "")), k[0], null, null); }
            });
        }
        if (!any) c.words("Give a key an action", b.left, b.centerY(), c.fs * 0.9f, c.dim, c.regular, Paint.Align.LEFT);
    }

    // ---- volume or channel: across on a wide tile, down on a tall one ----

    private void volume(final PluginKit.Ctx c) {
        boolean channel = "channel".equals(c.option("style", "volume"));
        RectF b = c.box;
        boolean down = b.height() > b.width();
        List<String[]> keys = new ArrayList<String[]>();
        String[][] order = channel
                ? (down ? new String[][]{{"up", "chevron-up"}, {"down", "chevron-down"}} : new String[][]{{"down", "chevron-left"}, {"up", "chevron-right"}})
                : (down ? new String[][]{{"up", "volume-plus"}, {"mute", "volume-off"}, {"down", "volume-minus"}}
                        : new String[][]{{"down", "volume-minus"}, {"mute", "volume-off"}, {"up", "volume-plus"}});
        for (String[] k : order) if (parse(c.option(k[0], "")) != null) keys.add(k);
        if (keys.isEmpty()) {
            c.words("Give a key an action", b.left, b.centerY(), c.fs * 0.9f, c.dim, c.regular, Paint.Align.LEFT);
            return;
        }
        Map<String, Object> a = Json.obj(c.data().get("attributes"));
        boolean muted = a != null && Boolean.TRUE.equals(a.get("is_volume_muted"));
        boolean grey = unavailable(c);
        int n = keys.size();
        float cw = down ? b.width() : b.width() / n, ch = down ? b.height() / n : b.height();
        for (int i = 0; i < n; i++) {
            final String[] k = keys.get(i);
            float cx = down ? b.centerX() : b.left + i * cw + cw / 2, cy = down ? b.top + i * ch + ch / 2 : b.centerY();
            RectF r = key(c, cx, cy, cw - c.u, ch - c.u);
            drawKey(c, r, k[1], "", k[0], k[0].equals("mute") && muted, grey, new Runnable() {
                public void run() { AvPanelPlugin.this.run(c, parse(c.option(k[0], "")), k[0], null, null); }
            });
        }
    }

    // ---- the number pad ----

    private final Map<Integer, String> codes = new HashMap<Integer, String>();
    private final Map<Integer, Long> typedAt = new HashMap<Integer, Long>();

    private void numpad(final PluginKit.Ctx c) {
        final Command cmd = parse(c.option("action", ""));
        final boolean onEnter = "enter".equals(c.option("send", "each"));
        boolean enterKey = onEnter || c.flag("enter", false), clearKey = c.flag("clear", true);
        final int most = (int) Math.max(1, Math.min(8, c.number("max", 3)));
        long idle = (long) (c.number("idle", 10) * 1000);
        boolean show = onEnter && c.flag("show", true);
        String code = codes.containsKey(c.index) ? codes.get(c.index) : "";
        Long at = typedAt.get(c.index);
        if (!code.isEmpty() && idle > 0 && at != null && SystemClock.uptimeMillis() - at > idle) {
            code = "";
            codes.remove(c.index);
        }
        if (!code.isEmpty() && idle > 0) c.host.redraw(1000);
        boolean grey = unavailable(c), bad = cmd != null && cmd.action.isEmpty();
        RectF b = new RectF(c.box);
        if (show) {
            float lh = c.fs * 1.6f;
            c.words(code.isEmpty() ? "–" : code, b.centerX(), b.top + lh * 0.75f, c.fs * 1.3f, code.isEmpty() ? c.dim : c.ink,
                    c.medium, Paint.Align.CENTER);
            b.top += lh;
        }
        List<String> keys = new ArrayList<String>();
        for (int d = 1; d <= 9; d++) keys.add(String.valueOf(d));
        keys.add(clearKey ? "clear" : "");
        keys.add("0");
        keys.add(enterKey ? "enter" : "");
        // A telephone's layout where the keys come out large enough, else rows of six on a wide, low tile.
        int cols = 3, rows = 4;
        if (b.width() > b.height() * 2.2f) { cols = 6; rows = 2; }
        float cw = b.width() / cols, ch = b.height() / rows;
        for (int i = 0; i < keys.size(); i++) {
            final String k = keys.get(i);
            if (k.isEmpty()) continue;
            RectF r = key(c, b.left + (i % cols) * cw + cw / 2, b.top + (i / cols) * ch + ch / 2, cw - c.u, ch - c.u);
            String word = k.equals("clear") ? "C" : k.equals("enter") ? null : k;
            String icon = k.equals("enter") ? "keyboard-return" : null;
            final String shown = code;
            boolean red = bad && !k.equals("clear") && !k.equals("enter");
            if (red) {
                c.fill.setColor(Style.RED & 0x55FFFFFF);
                c.c.drawRoundRect(r, r.height() * 0.28f, r.height() * 0.28f, c.fill);
            }
            drawKey(c, r, icon, word, "n" + k, false, grey, new Runnable() {
                public void run() {
                    String now = shown;
                    if (k.equals("clear")) {
                        codes.remove(c.index);
                        return;
                    }
                    if (k.equals("enter")) {
                        Command enter = parse(c.option("enter_action", ""));
                        if (enter == null && !onEnter) return;
                        if (onEnter && now.isEmpty() && enter == null) {
                            flashes.put(c.index + ":nenter", new long[]{SystemClock.uptimeMillis() + 900, 0});
                            c.host.redraw(920);
                            return;
                        }
                        AvPanelPlugin.this.run(c, enter != null ? enter : cmd, "nenter", "", now);
                        codes.remove(c.index);
                        return;
                    }
                    // A digit: kept for Enter, or sent at once.
                    String next = onEnter ? (now.length() >= most ? now : now + k) : (now.length() >= most ? k : now + k);
                    codes.put(c.index, next);
                    typedAt.put(c.index, SystemClock.uptimeMillis());
                    if (!onEnter) AvPanelPlugin.this.run(c, cmd, "n" + k, k, next);
                }
            });
        }
    }

    // ---- the selector grid: every option of the entity as a key, paged ----

    private final Map<Integer, Integer> pages = new HashMap<Integer, Integer>();

    private void grid(final PluginKit.Ctx c) {
        Map<String, Object> a = Json.obj(c.data().get("attributes"));
        if (a == null) a = new LinkedHashMap<String, Object>();
        String domain = c.entity.contains(".") ? c.entity.substring(0, c.entity.indexOf('.')) : "";
        String listKey = domain.equals("media_player") ? "source_list" : domain.equals("remote") ? "activity_list" : "options";
        String state = c.data().get("state") instanceof String ? (String) c.data().get("state") : "";
        Object cur = domain.equals("media_player") ? a.get("source") : domain.equals("remote") ? a.get("current_activity") : state;
        final String current = cur instanceof String ? (String) cur : "";
        List<Object> raw = Json.arr(a.get(listKey));
        List<Object> hide = Json.arr(c.options().get("hide"));
        final List<String> options = new ArrayList<String>();
        if (raw != null) for (Object o : raw) if (o instanceof String && (hide == null || !hide.contains(o))) options.add((String) o);
        RectF b = c.box;
        if (options.isEmpty()) {
            c.words("No options to show", b.left, b.centerY(), c.fs * 0.9f, c.dim, c.regular, Paint.Align.LEFT);
            return;
        }
        boolean grey = unavailable(c);
        // Keys of a finger at least (16 x 9 mm), twelve a page at most.
        float minW = 16 * c.mm, minH = 9 * c.mm;
        int cols = Math.max(1, Math.min(4, (int) (b.width() / minW))), rows = Math.max(1, (int) (b.height() / minH));
        int per = Math.min(12, cols * rows);
        boolean paged = options.size() > per;
        float strip = 0;
        if (paged) {
            strip = Math.max(minH * 0.7f, c.fs * 1.6f);
            rows = Math.max(1, (int) ((b.height() - strip) / minH));
            per = Math.min(12, cols * rows);
        }
        final int pagesCount = (options.size() + per - 1) / per;
        Integer page = pages.get(c.index);
        if (page == null || page >= pagesCount) {
            int at = options.indexOf(current);
            page = at >= 0 ? at / per : 0;
            pages.put(c.index, page);
        }
        int shown = Math.min(per, options.size() - page * per);
        // As few columns as the rows allow, so the words get the room.
        cols = Math.max(1, Math.min(cols, (int) Math.ceil(shown / (double) Math.max(1, rows))));
        int usedRows = (int) Math.ceil(shown / (double) cols);
        float cw = b.width() / cols, ch = Math.min((b.height() - strip) / Math.max(1, rows), 14 * c.mm * 1.2f);
        float top = b.top + (b.height() - strip - usedRows * ch) / 2;
        for (int i = 0; i < shown; i++) {
            final String option = options.get(page * per + i);
            RectF r = new RectF(b.left + (i % cols) * cw + c.u / 2, top + (i / cols) * ch + c.u / 2,
                    b.left + (i % cols + 1) * cw - c.u / 2, top + (i / cols + 1) * ch - c.u / 2);
            boolean on = option.equals(current);
            c.fill.setColor(keyGround(c, "g" + option, on));
            c.c.drawRoundRect(r, Math.min(r.height(), r.width()) * 0.22f, Math.min(r.height(), r.width()) * 0.22f, c.fill);
            long[] f = flashes.get(c.index + ":g" + option);
            boolean lit = on || f != null && SystemClock.uptimeMillis() < f[0];
            float s = Math.min(c.fs, r.height() * 0.35f);
            c.text.setTextSize(s);
            c.words(c.fit(option, r.width() - c.u * 1.5f, c.text), r.centerX(), r.centerY() + s * 0.36f, s,
                    grey ? c.dim : lit ? 0xFFFFFFFF : c.ink, c.regular, Paint.Align.CENTER);
            final String dom = domain;
            if (!grey) c.host.tap(r, new Runnable() {
                public void run() {
                    Command cmd = new Command();
                    if (dom.equals("media_player")) { cmd.action = "media_player.select_source"; cmd.key = "source"; }
                    else if (dom.equals("remote")) { cmd.action = "remote.turn_on"; cmd.key = "activity"; }
                    else { cmd.action = dom + ".select_option"; cmd.key = "option"; }
                    cmd.value = option;
                    AvPanelPlugin.this.run(c, cmd, "g" + option, null, null);
                }
            });
        }
        if (paged) {
            final int p = page;
            RectF bar = new RectF(b.left, b.bottom - strip, b.right, b.bottom);
            c.icon("chevron-left", bar.left + strip * 0.5f, bar.centerY() + c.fs * 0.4f, c.fs * 1.2f, c.dim);
            c.icon("chevron-right", bar.right - strip * 0.5f, bar.centerY() + c.fs * 0.4f, c.fs * 1.2f, c.dim);
            c.words((p + 1) + " / " + pagesCount, bar.centerX(), bar.centerY() + c.fs * 0.32f, c.fs * 0.85f, c.dim, c.regular, Paint.Align.CENTER);
            c.host.tap(new RectF(bar.left, bar.top, bar.centerX(), bar.bottom), new Runnable() {
                public void run() { pages.put(c.index, (p - 1 + pagesCount) % pagesCount); }
            });
            c.host.tap(new RectF(bar.centerX(), bar.top, bar.right, bar.bottom), new Runnable() {
                public void run() { pages.put(c.index, (p + 1) % pagesCount); }
            });
        }
    }
}
