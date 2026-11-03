package app.tilescreen.ui;

import app.tilescreen.App;

/**
 * Tap sound (tessera-plugins/plugins/tap_sound 0.2.1): a short, soft tick the moment a finger lands on something that
 * takes a tap, a softer one for every step of a dragged slider or a held key, and the full tick where a value reaches
 * its end. Its own two sounds (tap.wav, step.wav), on Android's system sound volume, and its setting **Tap sound** (on
 * the screen's settings page and as a switch in Home Assistant, named as the plugin names it).
 *
 * Tessera offers it only to a screen whose board has a speaker: every board the tablet reports (App.BOARDS) has one.
 */
final class TapSoundPlugin extends PluginKit.Plugin {
    TapSoundPlugin() { super("tap_sound", "0.2.1"); }

    private long lastStep;

    @Override
    void onPress(PluginKit.Host h) {
        if (h.settings().get("tap_sound") == 0) return;
        App.get().tick(false);
    }

    @Override
    void onStep(boolean edge, PluginKit.Host h) {
        if (h.settings().get("tap_sound") == 0) return;
        // Steps of a quick drag 30 ms apart at the least, as SoundPlayer::add spaces them.
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastStep < 30 && !edge) return;
        lastStep = now;
        App.get().tick(!edge);
    }
}
