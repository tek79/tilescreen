package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import app.tilescreen.App;
import app.tilescreen.core.Assist;
import app.tilescreen.core.ScreenSettings;
import app.tilescreen.core.WakeWord;
import app.tilescreen.core.Tessera;

/**
 * Voice assistant (tessera-plugins/plugins/voice_assist 0.4.0), push to talk: the tablet listens for no wake word, a tap
 * asks. The tile ("assist": the assistant's icon, what it does now, and under it what it heard or answered; a tap asks or
 * stops), the top bar item ("busy": while it listens, thinks or answers) and the popup over the screen (TileView
 * drawAssistPopup). Where a question is, is core.Assist's; this draws it. The tablet names it in its hello only when it
 * has a microphone that gives sound (Tessera.hasMicrophone), so Tessera offers it there only.
 */
final class VoiceAssistPlugin extends PluginKit.Plugin {
    VoiceAssistPlugin() { super("voice_assist", "0.4.0"); }

    // Icons of the plugin (voice_assist.cpp): the assistant, listening, thinking, answering, the assistant that could not help.
    static final String ROBOT = "F06A9", LISTENING = "F036C", THINKING = "F01D8", ANSWERING = "F057E", FAILED = "F169F";

    @Override
    String[] tiles() { return new String[]{"assist"}; }

    static Assist assist() {
        try {
            return app.tilescreen.App.get().screen().assist;
        } catch (Throwable t) {
            return null;
        }
    }

    static String icon(Assist a) {
        if (a == null) return ROBOT;
        switch (a.phase()) {
            case Assist.LISTENING: return LISTENING;
            case Assist.THINKING: return THINKING;
            case Assist.ANSWERING: return ANSWERING;
            case Assist.FAILED: return a.showingFailure() ? FAILED : ROBOT;
            default: return ROBOT;
        }
    }

    /** The tone of where it is: listening and answering the accent, thinking busy, a fresh failure the alert. */
    static int tone(Assist a, int normal) {
        if (a == null) return normal;
        switch (a.phase()) {
            case Assist.LISTENING:
            case Assist.ANSWERING: return Style.BLUE;
            case Assist.THINKING: return Style.AMBER;
            case Assist.FAILED: return a.showingFailure() ? Style.RED : normal;
            default: return normal;
        }
    }

    static String idleLine(Assist a) {
        if (a == null) return "No microphone";
        if (!a.ready()) return "Home Assistant is away";
        // voice_assist idle_line: the phrase to say while the wake word listens.
        ScreenSettings s = App.get().screen().screenSettings;
        if (s.assistWakeWord()) return "Say \u201C" + WakeWord.PHRASES[s.assistWakePhrase()] + "\u201D";
        return "Tap to ask";
    }

    static String word(Assist a) {
        if (a == null) return idleLine(null);
        if (a.phase() == Assist.FAILED && !a.replyFresh()) return idleLine(a);
        switch (a.phase()) {
            case Assist.LISTENING: return "Listening…";
            case Assist.THINKING: return "Thinking…";
            case Assist.ANSWERING: return "Answering";
            case Assist.FAILED: return "That did not work";
            default: return idleLine(a);
        }
    }

    // ---- the tile ----

    @Override
    boolean drawTile(String tile, PluginKit.Ctx c) {
        if (!tile.equals("assist")) return false;
        Assist a = assist();
        int phase = a == null ? Assist.IDLE : a.phase();
        boolean busy = a != null && (phase == Assist.LISTENING || phase == Assist.THINKING || phase == Assist.ANSWERING);
        boolean failed = a != null && a.showingFailure();
        String status = busy || failed ? word(a) : idleLine(a);
        String detail = "";
        if (a != null && phase == Assist.THINKING) detail = a.heard();
        else if (a != null && (phase == Assist.ANSWERING || a.replyFresh())) detail = a.reply();
        RectF b = c.box;
        boolean wide = b.width() > b.height() * 1.5f;
        float iconSize = Math.min(b.height() * 0.42f, c.fs * 2.6f);
        int colour = tone(a, c.dim);
        float statusSize = c.fs * 1.15f;
        while (statusSize > c.fs * 0.8f && c.width(status, statusSize, c.medium) > (wide ? b.width() - iconSize * 1.4f : b.width())) statusSize -= 1;
        if (wide) {
            float ix = b.left + iconSize / 2, cy = b.centerY();
            c.glyphs.setColor(colour);
            c.glyphs.setTextSize(iconSize);
            c.c.drawText(Icons.glyph(icon(a)), ix, cy + iconSize * 0.36f, c.glyphs);
            float tx = b.left + iconSize * 1.4f, tw = b.right - tx;
            int lines = detail.isEmpty() ? 0 : Math.max(1, Math.min(3, (int) ((b.height() - statusSize * 1.4f) / (c.fs * 1.25f))));
            float top = cy - (statusSize * 1.3f + lines * c.fs * 1.25f) / 2;
            c.words(c.fit(status, tw, c.text), tx, top + statusSize, statusSize, c.ink, c.medium, Paint.Align.LEFT);
            drawLines(c, detail, tx, top + statusSize * 1.3f, tw, lines, Paint.Align.LEFT);
        } else {
            float room = b.height() - iconSize - statusSize * 1.3f;
            int lines = detail.isEmpty() || room < c.fs * 1.25f ? 0 : Math.min(3, (int) (room / (c.fs * 1.25f)));
            float top = b.top + Math.max(0, (b.height() - iconSize - statusSize * 1.3f - lines * c.fs * 1.25f) / 2);
            c.glyphs.setColor(colour);
            c.glyphs.setTextSize(iconSize);
            c.c.drawText(Icons.glyph(icon(a)), b.centerX(), top + iconSize * 0.86f, c.glyphs);
            c.words(c.fit(status, b.width(), c.text), b.centerX(), top + iconSize + statusSize, statusSize, c.ink, c.medium, Paint.Align.CENTER);
            drawLines(c, detail, b.centerX(), top + iconSize + statusSize * 1.3f, b.width(), lines, Paint.Align.CENTER);
        }
        if (busy) c.host.redraw(250);
        return true;
    }

    /** `s` over at most `lines` lines of `width`, the last one cut with an ellipsis. */
    private static void drawLines(PluginKit.Ctx c, String s, float x, float top, float width, int lines, Paint.Align align) {
        if (s.isEmpty() || lines <= 0) return;
        float size = c.fs * 0.9f, step = c.fs * 1.25f;
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        int at = 0, n = 0;
        while (n < lines && at <= words.length) {
            boolean last = n == lines - 1;
            line.setLength(0);
            while (at < words.length) {
                String next = line.length() == 0 ? words[at] : line + " " + words[at];
                if (c.width(next, size, c.regular) > width && line.length() > 0) break;
                line.setLength(0);
                line.append(next);
                at++;
            }
            String shown = line.toString();
            if (last && at < words.length) shown = c.fit(shown + " " + words[at] + "…", width, c.text);
            c.words(shown, x, top + step * n + size, size, c.dim, c.regular, align);
            n++;
            if (at >= words.length) break;
        }
    }

    @Override
    void tapTile(String tile, PluginKit.Ctx c) {
        Assist a = assist();
        if (a == null) {
            c.host.say("This tablet has no microphone that works");
            return;
        }
        a.ask();
    }

    // ---- the top bar ----

    @Override
    PluginKit.Bar bar(String item, Tessera.Layout layout, PluginKit.Host h) {
        PluginKit.Bar out = new PluginKit.Bar();
        if (!item.equals("busy")) return out;
        Assist a = assist();
        if (a == null) return out;
        int phase = a.phase();
        if (a.busy() || phase == Assist.LISTENING || phase == Assist.THINKING || phase == Assist.ANSWERING || a.showingFailure()) {
            out.shown = true;
            out.icon = icon(a);
            out.words = word(a);
            out.color = tone(a, 0);
        }
        return out;
    }
}
