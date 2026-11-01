package app.tilescreen.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.tilescreen.core.Tessera;

/**
 * A robot vacuum's larger tile (docs/VACUUM.md, "A larger tile"; vacuum_tile.h, module_vacuum_tile.cpp): a small screen
 * of its own on a tile two rows high. A header (the icon, the name, the state, chips for how far it is and its battery),
 * a frame with the map, the rooms to pick, the routines or the map with the rooms, and a dock at the bottom with the
 * cleaning mode as icons and the robot's two keys, the first larger. A short tile gives way in Tessera's order: the mode
 * first, then the keys move up into the header, then a last room tile says "+N" and opens the card.
 */
final class VacuumTile {
    interface Host extends VacuumCard.Host {
        /** The tile's card, for "+N" and a tap anywhere else. */
        void openCard();
    }

    static boolean face(String controls) {
        return controls.equals("map") || controls.equals("rooms") || controls.equals("routines") || controls.equals("map_rooms");
    }

    private static final int TEAL = 0xFF009688, ACCENT = 0xFF03A9F4, ERROR = 0xFFDB4437, CHARGING = 0xFF43A047;

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final android.content.Context context;
    /** The rooms picked on this tile, in the order of the taps. */
    final List<Integer> picks = new ArrayList<Integer>();
    private String entity = "";

    VacuumTile(android.content.Context context, TextPaint glyphs) {
        this.context = context;
        this.glyphs = glyphs;
    }

    private float u, fs, gap, key, row, pad;
    private Tessera.Tile t;
    private VacuumCard.Data d;
    private Host host;
    private int ink, dim, raised;
    private boolean avail;

    void draw(Canvas c, Tessera.Tile tile, RectF box, float unit, float fontSize, float mm, int ink, int dim, int raised, Host h) {
        if (!tile.entity.equals(entity)) {
            entity = tile.entity;
            picks.clear();
        }
        t = tile;
        d = VacuumCard.parse(tile);
        host = h;
        u = unit;
        fs = fontSize;
        this.ink = ink;
        this.dim = dim;
        this.raised = raised;
        avail = !Style.unavailable(tile);
        gap = 1.4f * u;
        key = Math.max(7 * mm, 8 * u);
        row = Math.max(7 * mm, 6.5f * u);
        pad = 1.2f * u;
        String face = tile.controls;
        List<String> rooms = new ArrayList<String>(), routines = new ArrayList<String>();
        if (d != null) {
            for (int i = picks.size() - 1; i >= 0; i--) if (picks.get(i) >= d.rooms.size()) picks.remove(i);
            for (VacuumCard.Room r : d.rooms) rooms.add(r.name);
            for (VacuumCard.Routine r : d.routines) routines.add(r.name);
        }
        VacuumCard.Can can = VacuumCard.can(VacuumCard.supported(tile));
        boolean byArea = can.byArea;
        if (!byArea) rooms.clear();
        int first = VacuumCard.firstKey(tile.state, can), second = VacuumCard.secondKey(tile.state, can);
        VacuumCard.Choice mode = VacuumCard.choice(tile, 'm');
        int modes = mode != null && mode.values.size() >= 2 && mode.values.size() <= 4 ? mode.values.size() : 0;

        float ic = Math.min(fs * 2.7f, 11 * u);
        float head = Math.max(ic, fs * 2.3f);
        float top = box.top + head + gap, dockH = key * 1.2f;
        RectF withDock = new RectF(box.left, top, box.right, box.bottom - dockH - gap);
        boolean docked = contentOk(face, withDock, 16 * mm, rooms, routines);
        RectF frame = docked ? withDock : new RectF(box.left, top, box.right, box.bottom);

        // The header: the icon in its circle, the name and the state line.
        boolean on = "cleaning".equals(tile.state) || "returning".equals(tile.state);
        int accent = "error".equals(tile.state) ? ERROR : TEAL;
        fill.setColor(on ? withAlpha(accent, 60) : raised);
        c.drawCircle(box.left + ic / 2, box.top + head / 2, ic / 2, fill);
        glyphs.setColor(on ? accent : dim);
        glyphs.setTextSize(ic * 0.56f);
        c.drawText(Icons.forTile(context, tile), box.left + ic / 2, box.top + head / 2 + ic * 0.2f, glyphs);
        float tx = box.left + ic + 1.6f * u;

        // The keys: in the dock, the first one size up; else in the header.
        float firstD = docked ? key * 1.2f : key;
        float pairW = (second != VacuumCard.NONE ? key + gap : 0) + firstD;
        float right = box.right;
        boolean withMode = false;
        if (docked) {
            float cy = box.bottom - dockH / 2;
            float modeW = modes * Math.max(key * 1.25f, fs * 3) + 2 * Math.max(3, key / 12);
            withMode = modes >= 2 && !face.equals("routines") && box.width() >= modeW + 2 * gap + pairW;
            float x = withMode ? box.right - pairW : box.centerX() - pairW / 2;
            if (second != VacuumCard.NONE) keyAt(c, new RectF(x, cy - key / 2, x + key, cy + key / 2), second, false, 0);
            keyAt(c, new RectF(x + pairW - firstD, cy - firstD / 2, x + pairW, cy + firstD / 2), first, true, picks.size());
            if (withMode) modes(c, mode, new RectF(box.left, cy - key / 2, box.left + modeW, cy + key / 2));
        } else {
            float y = box.top + (head - key) / 2;
            float x = box.right - pairW;
            if (second != VacuumCard.NONE) keyAt(c, new RectF(x, y, x + key, y + key), second, false, 0);
            keyAt(c, new RectF(box.right - key, y, box.right, y + key), first, true, picks.size());
            right = x - gap;
        }
        // Chips at the header's right where there is room beside the name; the battery else in the state line.
        double battery = VacuumCard.battery(tile);
        boolean chipsShown = false;
        if (docked) {
            List<String[]> chips = new ArrayList<String[]>();
            if ("cleaning".equals(tile.state) && d != null && d.progress >= 0) chips.add(new String[]{"progress-clock", d.progress + " %", String.valueOf(TEAL)});
            if (!Double.isNaN(battery)) {
                boolean charging = VacuumCard.charging(tile);
                int b = (int) Math.round(battery);
                chips.add(new String[]{charging ? "battery-charging" : b <= 20 ? "battery-alert" : "battery", b + " %",
                        String.valueOf(charging ? CHARGING : b <= 20 ? ERROR : dim)});
            }
            text.setTypeface(regular);
            text.setTextSize(fs * 0.8f);
            float all = 0;
            for (String[] ch : chips) all += chipW(ch[1]) + u;
            text.setTypeface(medium);
            text.setTextSize(fs);
            float nameLeast = Math.max(text.measureText("Vacuum"), 14 * u);
            if (!chips.isEmpty() && box.right - tx - all - gap >= nameLeast) {
                float x = box.right - all + u, ch = fs * 1.6f;
                for (String[] chip : chips) {
                    float w = chipW(chip[1]);
                    RectF r = new RectF(x, box.top + (head - ch) / 2, x + w, box.top + (head + ch) / 2);
                    int col = Integer.parseInt(chip[2]);
                    fill.setColor(withAlpha(col, 40));
                    c.drawRoundRect(r, ch / 2, ch / 2, fill);
                    glyphs.setColor(col);
                    glyphs.setTextSize(fs * 0.9f);
                    c.drawText(Icons.sure(context, chip[0]), r.left + fs * 0.85f, r.centerY() + fs * 0.32f, glyphs);
                    text.setTypeface(regular);
                    text.setTextSize(fs * 0.8f);
                    text.setColor(ink);
                    c.drawText(chip[1], r.left + fs * 1.6f, r.centerY() + fs * 0.28f, text);
                    x += w + u;
                }
                right = box.right - all;
                chipsShown = true;
            }
        }
        float textW = right - tx - gap;
        text.setTypeface(medium);
        text.setTextSize(fs);
        text.setColor(ink);
        c.drawText(fit(tile.name, textW), tx, box.top + head / 2 - fs * 0.1f, text);
        String line = VacuumCard.stateWord(tile.state);
        if ("error".equals(tile.state) && d != null && !d.error.isEmpty()) line = d.error;
        if (!chipsShown && !Double.isNaN(battery)) line += " · " + Math.round(battery) + " %";
        text.setTypeface(regular);
        text.setTextSize(fs * 0.85f);
        text.setColor("error".equals(tile.state) ? ERROR : dim);
        c.drawText(fit(line, textW), tx, box.top + head / 2 + fs * 0.95f, text);

        // The frame and what the face shows in it.
        float fr = Math.max(1.2f * u, 2.4f * u - pad);
        fill.setColor(raised);
        c.drawRoundRect(frame, fr, fr, fill);
        RectF inner = new RectF(frame.left + pad, frame.top + pad, frame.right - pad, frame.bottom - pad);
        if (inner.width() <= 0 || inner.height() <= 0) return;
        if (face.equals("map")) {
            map(c, inner);
        } else if (face.equals("rooms")) {
            rooms(c, rooms, inner, 99);
        } else if (face.equals("routines")) {
            routines(c, routines, inner);
        } else if (face.equals("map_rooms")) {
            RectF[] sp = split(rooms, inner);
            map(c, sp[0]);
            rooms(c, rooms, sp[1], sp[2] != null ? 99 : 2);
        }
    }

    private float chipW(String words) {
        text.setTypeface(regular);
        text.setTextSize(fs * 0.8f);
        return fs * 1.6f + text.measureText(words) + fs * 0.6f;
    }

    // ---- the keys and the mode ----

    private void keyAt(Canvas c, final RectF r, final int k, boolean first, int counted) {
        if (k == VacuumCard.NONE) return;
        fill.setColor(!avail ? withAlpha(raised, 120) : first ? ACCENT : withAlpha(ink, 22));
        c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill);
        glyphs.setColor(first ? 0xFFFFFFFF : ink);
        glyphs.setTextSize(r.width() * 0.5f);
        c.drawText(Icons.glyph(VacuumCard.keyIcon(k)), r.centerX(), r.centerY() + r.width() * 0.18f, glyphs);
        if (first && counted > 0 && (k == VacuumCard.K_START || k == VacuumCard.K_RESUME)) {
            // How many rooms Start cleans, on its shoulder.
            float b = r.width() * 0.38f;
            fill.setColor(TEAL);
            c.drawCircle(r.right - b * 0.35f, r.top + b * 0.35f, b / 2, fill);
            text.setTypeface(medium);
            text.setTextSize(b * 0.6f);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.valueOf(counted), r.right - b * 0.35f, r.top + b * 0.35f + b * 0.21f, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        if (!avail) return;
        final boolean withPicks = first && counted > 0 && k == VacuumCard.K_START;
        host.tap(new RectF(r.left - u, r.top - u, r.right + u, r.bottom + u), new Runnable() {
            public void run() {
                if (withPicks && d != null) {
                    String areas = VacuumCard.areasTemplate(d, picks);
                    if (!areas.isEmpty()) {
                        Map<String, String> data = new LinkedHashMap<String, String>(), templates = new LinkedHashMap<String, String>();
                        data.put("entity_id", t.entity);
                        templates.put("cleaning_area_id", areas);
                        host.call("vacuum.clean_area", data, templates);
                        picks.clear();
                        return;
                    }
                }
                Map<String, String> data = new LinkedHashMap<String, String>();
                data.put("entity_id", t.entity);
                host.call(VacuumCard.service(k), data, null);
            }
        });
    }

    private static String roleIcon(char role) {
        switch (role) {
            case 'v': return "robot-vacuum";
            case 'm': return "water";
            case 'a': return "auto-mode";
            default: return "robot-vacuum-variant";
        }
    }

    private void modes(Canvas c, final VacuumCard.Choice mode, RectF r) {
        fill.setColor(withAlpha(ink, 18));
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        int n = mode.values.size();
        float sw = r.width() / n, in = Math.max(3, key / 12);
        for (int i = 0; i < n; i++) {
            RectF s = new RectF(r.left + i * sw, r.top, r.left + (i + 1) * sw, r.bottom);
            boolean on = mode.values.get(i).equals(mode.current);
            if (on) {
                fill.setColor(ACCENT);
                RectF k = new RectF(s.left + in, s.top + in, s.right - in, s.bottom - in);
                c.drawRoundRect(k, k.height() / 2, k.height() / 2, fill);
            }
            char role = i < mode.roles.length() ? mode.roles.charAt(i) : 'b';
            glyphs.setColor(on ? 0xFFFFFFFF : ink);
            glyphs.setTextSize(r.height() * 0.58f);
            c.drawText(Icons.sure(context, roleIcon(role)), s.centerX(), s.centerY() + r.height() * 0.16f, glyphs);
            final String value = mode.values.get(i);
            if (avail && !mode.entity.isEmpty()) host.tap(s, new Runnable() {
                public void run() {
                    Map<String, String> data = new LinkedHashMap<String, String>();
                    data.put("entity_id", mode.entity);
                    data.put("option", value);
                    host.call("select.select_option", data, null);
                }
            });
        }
    }

    // ---- the map ----

    private void map(Canvas c, RectF box) {
        if (d == null || !d.hasMap() || box.width() < 16 || box.height() < 16) {
            glyphs.setColor(withAlpha(dim, 120));
            float s = Math.min(box.width(), box.height()) * 0.4f;
            glyphs.setTextSize(s);
            c.drawText(Icons.glyph(VacuumCard.ICON_ROBOT), box.centerX(), box.centerY() + s * 0.35f, glyphs);
            return;
        }
        Bitmap b = host.map(d.mapEntity, d.mapMark, Math.round(box.width()), Math.round(box.height()), raised, Style.isDark());
        if (b != null) {
            float scale = Math.min(box.width() / b.getWidth(), box.height() / b.getHeight());
            float pw = b.getWidth() * scale, ph = b.getHeight() * scale;
            RectF dst = new RectF(box.centerX() - pw / 2, box.centerY() - ph / 2, box.centerX() + pw / 2, box.centerY() + ph / 2);
            c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), dst, null);
        } else if (!host.mapMissing()) {
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(u * 0.8f);
            ring.setColor(withAlpha(dim, 140));
            float s = 5 * u;
            float spin = (SystemClock.uptimeMillis() % 1200) / 1200f * 360;
            c.drawArc(new RectF(box.centerX() - s / 2, box.centerY() - s / 2, box.centerX() + s / 2, box.centerY() + s / 2), spin, 270, false, ring);
        }
    }

    // ---- rooms as icon tiles (vacuum_tile.h room_grid) ----

    private float roomNeedW(List<String> rooms) {
        text.setTypeface(regular);
        text.setTextSize(fs * 0.8f);
        float w = 0;
        for (String r : rooms) w = Math.max(w, text.measureText(r));
        return Math.max(w + 2 * pad, key);
    }

    private float roomNeedH() { return Math.max(fs * 3.4f, key); }

    /** {cols, rows, w, h, shown} of the rooms in `r`, or null when not even two fit. */
    private float[] grid(List<String> rooms, RectF r, int maxRows) {
        float g = u, needW = roomNeedW(rooms), needH = roomNeedH();
        int count = rooms.size();
        int colsFit = (int) Math.max(0, (r.width() + g) / (needW + g)), rowsFit = Math.min(maxRows, (int) ((r.height() + g) / (needH + g)));
        if (colsFit == 0 || rowsFit <= 0 || count == 0) return null;
        float[] best = null;
        double score = 1e9;
        for (int rows = 1; rows <= rowsFit; rows++) {
            int cols = (count + rows - 1) / rows;
            if (cols > colsFit || cols * (rows - 1) >= count) continue;
            float w = (r.width() - (cols - 1) * g) / cols, h = Math.min((r.height() - (rows - 1) * g) / rows, 22 * u * 0.75f);
            double a = w / h, off = a > 1.2 ? a / 1.2 : 1.2 / a;
            if (off < score) {
                score = off;
                best = new float[]{cols, rows, w, h, count};
            }
        }
        if (best == null) {
            int cap = colsFit * rowsFit;
            if (cap < 2) return null;
            best = new float[]{colsFit, rowsFit, (r.width() - (colsFit - 1) * g) / colsFit,
                    Math.min((r.height() - (rowsFit - 1) * g) / rowsFit, 22 * u * 0.75f), cap - 1};
        }
        return best;
    }

    private boolean contentOk(String face, RectF hero, float mapLeast, List<String> rooms, List<String> routines) {
        RectF in = new RectF(hero.left + pad, hero.top + pad, hero.right - pad, hero.bottom - pad);
        if (in.width() <= 0 || in.height() <= 0) return false;
        int places = Math.min(4, rooms.size());
        if (face.equals("map")) return in.height() >= mapLeast;
        if (face.equals("rooms")) return reach(rooms, in, 99) >= places;
        if (face.equals("routines")) return routinesReach(routines, in) >= Math.min(3, routines.size());
        if (face.equals("map_rooms")) {
            RectF[] sp = split(rooms, in);
            return sp[0].height() >= mapLeast && reach(rooms, sp[1], sp[2] != null ? 99 : 2) >= places;
        }
        return false;
    }

    private int reach(List<String> rooms, RectF r, int maxRows) {
        float[] g = grid(rooms, r, maxRows);
        return g == null ? 0 : (int) g[4];
    }

    /** {map, rooms, side or null}: the rooms in a column beside the map on a frame lying down, else a strip under it. */
    private RectF[] split(List<String> rooms, RectF r) {
        float g = 1.4f * u;
        if (rooms.isEmpty()) return new RectF[]{r, new RectF(r.left, r.bottom, r.right, r.bottom), null};
        if (r.width() * 5 >= r.height() * 8) {
            float rw = Math.max(roomNeedW(rooms) * 2 + u, r.width() * 2 / 5);
            return new RectF[]{new RectF(r.left, r.top, r.right - rw - g, r.bottom), new RectF(r.right - rw, r.top, r.right, r.bottom), r};
        }
        float needH = roomNeedH();
        int rows = reach(rooms, new RectF(0, 0, r.width(), needH), 1) >= rooms.size() ? 1 : 2;
        float rh = rows * needH + (rows - 1) * u;
        return new RectF[]{new RectF(r.left, r.top, r.right, r.bottom - rh - g), new RectF(r.left, r.bottom - rh, r.right, r.bottom), null};
    }

    private void rooms(Canvas c, List<String> rooms, RectF r, int maxRows) {
        float[] gr = grid(rooms, r, maxRows);
        if (gr == null || d == null) return;
        int cols = (int) gr[0], rows = (int) gr[1], shown = (int) gr[4];
        float w = gr[2], h = gr[3], g = u;
        float used = rows * h + (rows - 1) * g, y0 = r.top + (r.height() - used) / 2;
        boolean big = h >= fs * 4.4f;
        float rad = Math.max(u, 1.6f * u);
        for (int i = 0; i < shown + (shown < rooms.size() ? 1 : 0); i++) {
            RectF p = new RectF(r.left + (i % cols) * (w + g), y0 + (i / cols) * (h + g), 0, 0);
            p.right = p.left + w;
            p.bottom = p.top + h;
            if (i == shown) {
                // "+N": the rest, on the card.
                fill.setColor(Style.isDark() ? 0xFF22262E : 0xFFFFFFFF);
                c.drawRoundRect(p, rad, rad, fill);
                text.setTypeface(medium);
                text.setTextSize(fs);
                text.setColor(dim);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText("+" + (rooms.size() - shown), p.centerX(), p.centerY() + fs * 0.35f, text);
                text.setTextAlign(Paint.Align.LEFT);
                host.tap(p, new Runnable() {
                    public void run() { host.openCard(); }
                });
                break;
            }
            final int index = i;
            int at = picks.indexOf(i);
            boolean picked = at >= 0;
            fill.setColor(picked ? ACCENT : Style.isDark() ? 0xFF22262E : 0xFFFFFFFF);
            c.drawRoundRect(p, rad, rad, fill);
            VacuumCard.Room room = d.rooms.get(i);
            String g1 = room.icon > 0 ? Icons.glyph(Integer.toHexString(room.icon).toUpperCase()) : null;
            if (g1 == null) g1 = Icons.glyph(VacuumCard.ICON_ROOMS);
            float is = big ? fs * 1.9f : fs * 1.25f;
            float block = is + fs * 1.1f;
            float top = p.centerY() - block / 2;
            glyphs.setColor(picked ? 0xFFFFFFFF : dim);
            glyphs.setTextSize(is);
            c.drawText(g1, p.centerX(), top + is * 0.85f, glyphs);
            text.setTypeface(regular);
            text.setTextSize(fs * 0.8f);
            text.setColor(picked ? 0xFFFFFFFF : ink);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(fit(room.name, p.width() - 2 * pad), p.centerX(), top + is + fs * 0.9f, text);
            if (picked) {
                // Its place in the order.
                text.setTypeface(medium);
                text.setTextSize(fs * 0.75f);
                c.drawText(String.valueOf(at + 1), p.right - fs * 0.6f, p.top + fs * 0.95f, text);
            }
            text.setTextAlign(Paint.Align.LEFT);
            if (avail) host.tap(p, new Runnable() {
                public void run() {
                    int k = picks.indexOf(index);
                    if (k >= 0) picks.remove(k);
                    else if (picks.size() < VacuumCard.MAX_ROOMS) picks.add(index);
                }
            });
        }
    }

    // ---- routines as a list ----

    private int routineCols(List<String> names, RectF r) {
        text.setTypeface(regular);
        text.setTextSize(fs * 0.85f);
        float widest = 0;
        for (String n : names) widest = Math.max(widest, text.measureText(n));
        return r.width() >= 2 * (widest + row + 3.5f * u) + u ? 2 : 1;
    }

    private int routinesReach(List<String> names, RectF r) {
        float g = u;
        int cap = (int) ((r.height() + g) / (row + g)) * routineCols(names, r), count = names.size();
        return cap >= count ? count : cap == 1 ? 1 : Math.max(0, cap - 1);
    }

    private void routines(Canvas c, List<String> names, RectF r) {
        if (d == null || names.isEmpty()) return;
        float g = u;
        int count = names.size(), cols = routineCols(names, r), rowsFit = (int) ((r.height() + g) / (row + g));
        int rows = Math.min(rowsFit, (count + cols - 1) / cols), cap = rows * cols;
        if (cap < 1) return;
        int shown = count > cap ? Math.max(1, cap - 1) : count;
        float cw = (r.width() - (cols - 1) * g) / cols;
        float used = rows * row + (rows - 1) * g, y0 = r.top + (r.height() - used) / 2;
        float rad = 1.6f * u;
        for (int i = 0; i < shown + (shown < count && shown < cap ? 1 : 0); i++) {
            RectF p = new RectF(r.left + (i % cols) * (cw + g), y0 + (i / cols) * (row + g), 0, 0);
            p.right = p.left + cw;
            p.bottom = p.top + row;
            fill.setColor(Style.isDark() ? 0xFF22262E : 0xFFFFFFFF);
            c.drawRoundRect(p, rad, rad, fill);
            if (i == shown) {
                text.setTypeface(medium);
                text.setTextSize(fs * 0.85f);
                text.setColor(dim);
                c.drawText("+" + (count - shown), p.left + 2 * u, p.centerY() + fs * 0.3f, text);
                host.tap(p, new Runnable() {
                    public void run() { host.openCard(); }
                });
                break;
            }
            float disc = row * 0.62f, e = (row - disc) / 2;
            fill.setColor(withAlpha(TEAL, 46));
            c.drawCircle(p.left + e + disc / 2, p.centerY(), disc / 2, fill);
            glyphs.setColor(TEAL);
            glyphs.setTextSize(disc * 0.6f);
            c.drawText(Icons.glyph(VacuumCard.ICON_PLAY), p.left + e + disc / 2, p.centerY() + disc * 0.21f, glyphs);
            text.setTypeface(regular);
            text.setTextSize(fs * 0.85f);
            text.setColor(ink);
            c.drawText(fit(names.get(i), cw - 2 * e - disc - 2 * u), p.left + e + disc + 1.6f * u, p.centerY() + fs * 0.3f, text);
            final String button = d.routines.get(i).entity;
            if (avail) host.tap(p, new Runnable() {
                public void run() {
                    Map<String, String> data = new LinkedHashMap<String, String>();
                    data.put("entity_id", button);
                    host.call(button.startsWith("script.") ? "script.turn_on" : "button.press", data, null);
                }
            });
        }
    }

    private String fit(String s, float width) {
        if (s == null || width <= 0) return "";
        return TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
    }

    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }
}
