package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.tilescreen.core.Json;

/**
 * The template tile (`screen.text`, docs/TEXT_TILE.md, module_text_card): Home Assistant renders the template, the add-on
 * turns the result into rows (`x.md`: {"v": 1, "r": [row, ...]} or {"v": 1, "e": why}), and the screen draws them. A row is
 * [kind, runs], kind "h" a heading, "p" text, "li" a bullet, or ["t"|"th", [runs, ...]] a table row; runs are
 * [[tone, text], ...], tone 0 plain, 1 bold, 2 good, 3 alert, 4 accent.
 */
final class TextCard {
    static final int MOST_ROWS = 24, MOST_COLUMNS = 4;

    static final class Row {
        String kind;
        /** One list of runs, or one per cell for a table row. */
        final List<List<Object[]>> cells = new ArrayList<List<Object[]>>();
    }

    /** The rows, or null for none to draw; `error` gets Home Assistant's message when it refused the template. */
    static List<Row> rows(Map<String, Object> extra, String[] error) {
        Map<String, Object> md = Json.obj(extra.get("md"));
        if (md == null) return null;
        String e = Json.str(md, "e");
        if (e != null) {
            error[0] = e;
            return new ArrayList<Row>();
        }
        List<Object> list = Json.arr(md.get("r"));
        List<Row> out = new ArrayList<Row>();
        if (list == null) return out;
        for (Object o : list) {
            List<Object> r = Json.arr(o);
            if (r == null || r.size() < 2 || !(r.get(0) instanceof String)) continue;
            Row row = new Row();
            row.kind = (String) r.get(0);
            if (row.kind.equals("t") || row.kind.equals("th")) {
                List<Object> cells = Json.arr(r.get(1));
                if (cells == null) continue;
                for (Object cell : cells) {
                    if (row.cells.size() == MOST_COLUMNS) break;
                    row.cells.add(runs(cell));
                }
            } else {
                row.cells.add(runs(r.get(1)));
            }
            out.add(row);
            if (out.size() == MOST_ROWS) break;
        }
        return out;
    }

    private static List<Object[]> runs(Object o) {
        List<Object[]> out = new ArrayList<Object[]>();
        List<Object> list = Json.arr(o);
        if (list == null) return out;
        for (Object r : list) {
            List<Object> run = Json.arr(r);
            if (run == null || run.size() < 2) continue;
            int tone = run.get(0) instanceof Double ? ((Double) run.get(0)).intValue() : 0;
            out.add(new Object[]{tone, String.valueOf(run.get(1))});
        }
        return out;
    }

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular, bold;

    TextCard(Typeface regular, Typeface bold) {
        this.regular = regular;
        this.bold = bold;
    }

    /** One placed piece of text. */
    private static final class Piece {
        float x;
        String text;
        int color;
        boolean strong;
    }

    private int ink, dim;

    private int colour(int tone, int plain) {
        switch (tone) {
            case 1: return ink;
            case 2: return Style.GREEN;
            case 3: return Style.RED;
            case 4: return Style.BLUE;
            default: return plain;
        }
    }

    void draw(Canvas c, List<Row> rows, String error, RectF box, float fs, int ink, int dim) {
        this.ink = ink;
        this.dim = dim;
        float y = box.top;
        float lineH = fs * 1.35f;
        if (error != null) {
            paint.setTypeface(regular);
            paint.setTextSize(fs * 0.85f);
            List<List<Piece>> lines = wrap(single(new Object[]{3, error}), box.width(), fs * 0.85f, dim, false);
            for (List<Piece> l : lines) {
                if (y + lineH > box.bottom + 1) break;
                paintLine(c, l, box.left, y + fs, fs * 0.85f);
                y += lineH;
            }
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean last = i == rows.size() - 1;
            if (row.kind.equals("t") || row.kind.equals("th")) {
                if (y + lineH > box.bottom + 1) return;
                drawTableRow(c, row, box, y, fs);
                y += lineH;
                continue;
            }
            boolean heading = row.kind.equals("h");
            boolean bullet = row.kind.equals("li");
            float size = heading ? fs * 1.12f : fs * 0.92f;
            float h = heading ? fs * 1.5f : lineH;
            float indent = bullet ? fs * 1.1f : 0;
            List<List<Piece>> lines = wrap(row.cells.isEmpty() ? new ArrayList<Object[]>() : row.cells.get(0), box.width() - indent, size,
                    heading ? ink : dim, heading);
            for (int k = 0; k < lines.size(); k++) {
                boolean fits = y + h * 2 <= box.bottom + 1;
                if (y + h > box.bottom + 1) return;
                List<Piece> l = lines.get(k);
                boolean more = k < lines.size() - 1 || !last;
                if (!fits && more) {
                    // The last line that fits ends in "…".
                    l = ellipsize(l, box.width() - indent, size);
                }
                if (bullet && k == 0) {
                    paint.setTypeface(regular);
                    paint.setTextSize(size);
                    paint.setColor(dim);
                    c.drawText("•", box.left + fs * 0.2f, y + size, paint);
                }
                paintLine(c, l, box.left + indent, y + size, size);
                y += h;
                if (!fits && more) return;
            }
        }
    }

    private static List<Object[]> single(Object[] run) {
        List<Object[]> l = new ArrayList<Object[]>();
        l.add(run);
        return l;
    }

    private void drawTableRow(Canvas c, Row row, RectF box, float y, float fs) {
        boolean head = row.kind.equals("th");
        float size = fs * 0.9f;
        int n = row.cells.size();
        if (n == 0) return;
        // The first cell names the row; the others stand right-aligned as numbers do, in equal columns.
        float first = n == 1 ? box.width() : box.width() * (n == 2 ? 0.55f : 0.4f);
        float col = n == 1 ? 0 : (box.width() - first) / (n - 1);
        for (int i = 0; i < n; i++) {
            List<Piece> pieces = flat(row.cells.get(i), size, head ? dim : i == 0 ? dim : ink, false);
            float width = i == 0 ? first - fs * 0.5f : col - fs * 0.4f;
            pieces = ellipsize(pieces, width, size, false);
            float w = widthOf(pieces, size);
            float x = i == 0 ? box.left : box.left + first + col * i - w;
            paintLine(c, pieces, x, y + size, size);
        }
    }

    private List<Piece> flat(List<Object[]> runs, float size, int plain, boolean heading) {
        List<Piece> out = new ArrayList<Piece>();
        float x = 0;
        for (Object[] r : runs) {
            int tone = (Integer) r[0];
            Piece p = new Piece();
            p.text = (String) r[1];
            p.strong = heading || tone == 1;
            p.color = colour(tone, plain);
            p.x = x;
            setFace(p.strong, size);
            x += paint.measureText(p.text);
            out.add(p);
        }
        return out;
    }

    private float widthOf(List<Piece> pieces, float size) {
        if (pieces.isEmpty()) return 0;
        Piece last = pieces.get(pieces.size() - 1);
        setFace(last.strong, size);
        return last.x + paint.measureText(last.text);
    }

    private void setFace(boolean strong, float size) {
        paint.setTypeface(strong ? bold : regular);
        paint.setTextSize(size);
    }

    /** Words of the runs onto lines of `width`, each word keeping its run's colour and face. */
    private List<List<Piece>> wrap(List<Object[]> runs, float width, float size, int plain, boolean heading) {
        List<List<Piece>> lines = new ArrayList<List<Piece>>();
        List<Piece> line = new ArrayList<Piece>();
        float x = 0;
        for (Object[] r : runs) {
            int tone = (Integer) r[0];
            boolean strong = heading || tone == 1;
            int color = colour(tone, plain);
            setFace(strong, size);
            String text = (String) r[1];
            int at = 0;
            while (at < text.length()) {
                int end = text.indexOf(' ', at);
                String word = end < 0 ? text.substring(at) : text.substring(at, end + 1);
                at = end < 0 ? text.length() : end + 1;
                float w = paint.measureText(word);
                float bare = paint.measureText(word.trim());
                if (x + bare > width && x > 0) {
                    lines.add(line);
                    line = new ArrayList<Piece>();
                    x = 0;
                    if (word.trim().isEmpty()) continue;
                }
                // A word longer than the line is cut where it ends.
                while (bare > width && word.length() > 1) {
                    word = word.substring(0, word.length() - 1);
                    bare = paint.measureText(word);
                    w = bare;
                }
                Piece p = new Piece();
                p.text = word;
                p.color = color;
                p.strong = strong;
                p.x = x;
                line.add(p);
                x += w;
            }
        }
        lines.add(line);
        return lines;
    }

    private List<Piece> ellipsize(List<Piece> pieces, float width, float size) { return ellipsize(pieces, width, size, true); }

    /** The pieces cut to `width` with "…" after them; `always` adds it even when they fit (a line that has more after it). */
    private List<Piece> ellipsize(List<Piece> pieces, float width, float size, boolean always) {
        if (!always && widthOf(pieces, size) <= width) return pieces;
        List<Piece> out = new ArrayList<Piece>(pieces);
        setFace(false, size);
        float dots = paint.measureText("…");
        while (!out.isEmpty()) {
            Piece last = out.get(out.size() - 1);
            setFace(last.strong, size);
            String text = last.text;
            while (text.length() > 0 && last.x + paint.measureText(text) + dots > width) text = text.substring(0, text.length() - 1);
            if (text.trim().length() > 0 || last.x == 0) {
                Piece p = new Piece();
                p.x = last.x;
                p.color = last.color;
                p.strong = last.strong;
                p.text = text.replaceAll("\\s+$", "") + "…";
                out.set(out.size() - 1, p);
                return out;
            }
            out.remove(out.size() - 1);
        }
        return out;
    }

    private void paintLine(Canvas c, List<Piece> line, float x, float baseline, float size) {
        for (Piece p : line) {
            setFace(p.strong, size);
            paint.setColor(p.color);
            c.drawText(p.text, x + p.x, baseline, paint);
        }
    }
}
