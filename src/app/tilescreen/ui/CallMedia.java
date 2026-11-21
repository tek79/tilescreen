package app.tilescreen.ui;

import android.graphics.Bitmap;

/**
 * A live call's media from the add-on (docs/CALLS.md, "Media"): one stream of the other screen's pictures as JPEG and its
 * voice as 16 kHz PCM, the stream a live view reads. The app sets `TileView.callMedia`; it runs only while a call is live.
 */
public interface CallMedia {
    /** Open the stream at `link`; `changed` runs on the stream's thread when a picture came. */
    void start(String link, Runnable changed);
    void stop();
    /** The other screen's newest picture, null before one came or in a call of sound alone. */
    Bitmap picture();
    /** Whether the other's voice is coming now (for the call's words). */
    boolean hearing();
}
