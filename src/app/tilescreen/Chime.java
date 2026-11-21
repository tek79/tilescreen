package app.tilescreen;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * The doorbell's chime (module_doorbell.cpp make_chime): two notes of a door chime, E5 then C5, a bell's partials dying
 * away, made once at 16 kHz and played on the notification stream, so it follows that volume.
 */
final class Chime {
    private static final int RATE = 16000;
    private static short[] samples;
    private static AudioTrack playing;

    private static short[] make() {
        final float LENGTH = 1.9f, SECOND = 0.55f, DECAY = 0.42f, ATTACK = 0.006f;
        final float[] NOTES = {659.25f, 523.25f};
        final float[][] PARTIALS = {{1.0f, 1.0f}, {2.0f, 0.32f}, {3.0f, 0.1f}};
        short[] out = new short[(int) (LENGTH * RATE)];
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE, sum = 0;
            for (int n = 0; n < 2; n++) {
                float u = t - n * SECOND;
                if (u < 0) continue;
                float envelope = (float) (Math.min(1.0f, u / ATTACK) * Math.exp(-u / DECAY));
                for (float[] p : PARTIALS) sum += p[1] * envelope * (float) Math.sin(2 * Math.PI * NOTES[n] * p[0] * u);
            }
            out[i] = (short) (Math.max(-1.0f, Math.min(1.0f, sum * 0.33f)) * 32767.0f);
        }
        return out;
    }

    private static short[] ring;

    /** A call's ring (module_call.cpp's): two short bursts of a phone's two tones, 440 and 480 Hz. */
    private static short[] makeRing() {
        short[] out = new short[(int) (1.2f * RATE)];
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / RATE;
            boolean on = t < 0.4f || (t >= 0.6f && t < 1.0f);
            if (!on) continue;
            float edge = Math.min(1f, Math.min(t % 0.6f, 0.4f - t % 0.6f) / 0.01f);
            float s = (float) (Math.sin(2 * Math.PI * 440 * t) + Math.sin(2 * Math.PI * 480 * t)) * 0.3f * edge;
            out[i] = (short) (Math.max(-1f, Math.min(1f, s)) * 32767f);
        }
        return out;
    }

    static synchronized void ringtone() {
        try {
            if (ring == null) ring = makeRing();
            if (playing != null) {
                try { playing.stop(); } catch (Exception ignored) {}
                playing.release();
            }
            AudioTrack t = new AudioTrack(AudioManager.STREAM_RING, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, ring.length * 2, AudioTrack.MODE_STATIC);
            t.write(ring, 0, ring.length);
            t.play();
            playing = t;
        } catch (Exception e) {
            App.get().log("call: the ring did not play: " + e);
        }
    }

    static synchronized void play() {
        try {
            if (samples == null) samples = make();
            if (playing != null) {
                try { playing.stop(); } catch (Exception ignored) {}
                playing.release();
            }
            AudioTrack t = new AudioTrack(AudioManager.STREAM_NOTIFICATION, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, samples.length * 2, AudioTrack.MODE_STATIC);
            t.write(samples, 0, samples.length);
            t.play();
            playing = t;
        } catch (Exception e) {
            App.get().log("doorbell: the chime did not play: " + e);
        }
    }
}
