package app.tilescreen.core;

/**
 * The tablet's microphone going up to the add-on, one way for the doorbell's Talk and for a call (docs/CALLS.md, "The
 * voice", talk_uplink.h): 16 kHz 16-bit mono in 20 ms parts, a chunked HTTP POST to /camera/<token>/talk, no codec. The
 * app sets `uplink` when the tablet has a microphone; the screen's code only asks it to start and stop.
 */
public final class Voice {
    private Voice() {}

    public static final int OFF = 0, CONNECTING = 1, LIVE = 2, BUSY = 3, FAILED = 4;

    public interface Uplink {
        /** Start sending to `url`; false when it can't (one is already going, no microphone). */
        boolean start(String url);
        void stop();
        /** OFF, CONNECTING, LIVE, BUSY (someone else talks) or FAILED. */
        int state();
        /** The voice's level for a key's bar, 0 to 1. */
        float level();
        /** When a voice was last heard (uptime ms), for Talk's minute without one. */
        long lastVoice();
        /** Silence goes up instead of the microphone (a call's microphone key). */
        void mute(boolean on);
    }

    public static volatile Uplink uplink;
}
