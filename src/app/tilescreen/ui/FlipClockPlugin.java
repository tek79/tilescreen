package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Calendar;
import java.util.Map;

import app.tilescreen.App;
import app.tilescreen.core.Json;

/**
 * Flip clock (tessera-plugins/plugins/flip_clock 0.1.0): a face of the screensaver's clock. The hours and the minutes as
 * two flip cards in large digits with a hinge across each, a seconds dot between them (option `seconds`), AM or PM at
 * the foot of the minutes on a 12-hour clock, and the date with the outside temperature under them. White on black,
 * moved a millimetre along a slow circle every minute as the screen moves every clock face.
 */
final class FlipClockPlugin extends PluginKit.Plugin {
    FlipClockPlugin() { super("flip_clock", "0.1.0"); }

    @Override
    String[] savers() { return new String[]{"flip"}; }

    private static final int CARD = 0xFF22252B, INK = 0xFFFFFFFF, INK_SOFT = 0xFFD6DAE0, MUTED = 0xFF8A9099;

    @Override
    boolean drawSaver(String saver, Map<String, Object> options, Map<String, Object> contract, Canvas c, int w, int h,
                      float mm, PluginKit.Paints p) {
        if (!saver.equals("flip")) return false;
        boolean seconds = !(options != null && Boolean.FALSE.equals(options.get("seconds")));
        String degrees = contract == null ? "" : String.valueOf(Json.str(contract, "temperature") == null ? "" : Json.str(contract, "temperature"));
        Calendar now = Calendar.getInstance();
        boolean h24 = App.get().screen().tessera.layout().clock24;
        int hour = now.get(Calendar.HOUR_OF_DAY), minute = now.get(Calendar.MINUTE);
        String hours = h24 ? String.format(java.util.Locale.ROOT, "%02d", hour) : String.valueOf(hour % 12 == 0 ? 12 : hour % 12);
        String minutes = String.format(java.util.Locale.ROOT, "%02d", minute);
        String suffix = h24 ? "" : hour < 12 ? "AM" : "PM";

        // The screen's largest digits, as large as the glass allows two cards side by side with the row under them.
        float pad = 3 * mm, gap = 4 * mm;
        float digits = Math.min(h * 0.40f, (w - 2 * gap - 8 * pad) / 2.6f);
        p.text.setTypeface(p.medium);
        p.text.setTextSize(digits);
        float cardW = p.text.measureText("88") + 2 * pad;
        Paint.FontMetrics fm = p.text.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float cardH = lineH + 2 * pad * 0.4f;
        float title = Math.max(4.5f * mm, digits * 0.16f), rowGap = gap;
        float top = (h - cardH - rowGap - title * 1.3f) / 2, left = (w - 2 * cardW - gap) / 2;
        // A millimetre along a slow circle every minute (saver_view::drift).
        double a = (now.get(Calendar.HOUR_OF_DAY) * 60 + minute) * 2 * Math.PI / 16;
        top += (float) Math.sin(a) * mm;
        left += (float) Math.cos(a) * mm;

        card(c, p, left, top, cardW, cardH, hours, digits, fm, pad, mm);
        card(c, p, left + cardW + gap, top, cardW, cardH, minutes, digits, fm, pad, mm);
        // The seconds dot, on in even seconds.
        if (seconds && now.get(Calendar.SECOND) % 2 == 0) {
            p.fill.setColor(INK_SOFT);
            c.drawCircle(left + cardW + gap / 2, top + cardH / 2, mm, p.fill);
        }
        if (!suffix.isEmpty()) {
            p.text.setTypeface(p.regular);
            p.text.setTextSize(title * 0.75f);
            p.text.setColor(MUTED);
            c.drawText(suffix, left + 2 * cardW + gap + 1.5f * mm, top + cardH - mm, p.text);
        }
        // The date, and the temperature beside it.
        String date = new java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(now.getTime());
        p.text.setTypeface(p.regular);
        p.text.setTextSize(title);
        float dw = p.text.measureText(date), tw = degrees.isEmpty() ? 0 : p.text.measureText(degrees);
        float between = tw > 0 ? 3 * mm : 0, x = (w - dw - between - tw) / 2, y = top + cardH + rowGap + title;
        p.text.setColor(INK_SOFT);
        c.drawText(date, x, y, p.text);
        if (tw > 0) {
            p.text.setColor(MUTED);
            c.drawText(degrees, x + dw + between, y, p.text);
        }
        return true;
    }

    /** A flip card: a raised block with the digits in its middle and the black hinge where it folds. */
    private static void card(Canvas c, PluginKit.Paints p, float x, float y, float w, float h, String digits, float size,
                             Paint.FontMetrics fm, float pad, float mm) {
        p.fill.setColor(CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), 2 * mm, 2 * mm, p.fill);
        p.text.setTypeface(p.medium);
        p.text.setTextSize(size);
        p.text.setColor(INK);
        p.text.setTextAlign(Paint.Align.CENTER);
        float baseline = y + h / 2 - (fm.ascent + fm.descent) / 2;
        c.drawText(digits, x + w / 2, baseline, p.text);
        p.text.setTextAlign(Paint.Align.LEFT);
        p.fill.setColor(0xFF000000);
        float line = Math.max(2, mm * 0.4f);
        c.drawRect(x, y + h / 2 - line / 2, x + w, y + h / 2 + line / 2, p.fill);
    }
}
