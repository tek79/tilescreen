package app.tilescreen.ui;

import android.graphics.Bitmap;

/**
 * A live stream's media from the add-on (docs/CALLS.md, "Media"; docs/DOORBELL.md): pictures as JPEG and sound as 16 kHz
 * PCM, the stream a live view reads. The app sets `TileView.callMedia` for calls (the voice path) and `TileView.ringMedia`
 * for the doorbell's view (the camera's sound on the media stream); each runs only while its view shows.
 */
public interface CallMedia {
    /** Open the stream at `link`; `changed` runs on the stream's thread when a picture came. */
    void start(String link, Runnable changed);
    void stop();
    /** The other screen's newest picture, null before one came or in a call of sound alone. */
    Bitmap picture();
    /** Whether the other's voice is coming now (for the call's words). */
    boolean hearing();
    /** Its sound off (the doorbell's Sound key, or while the person at the screen talks) or on again. */
    void mute(boolean on);
    /** Whether the stream still runs: false once it ended or never came. */
    boolean running();
}
