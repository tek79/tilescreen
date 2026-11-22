package app.tilescreen.core;

/**
 * Motion and brightness from a camera's frames (Tessera's screen_camera plugin, "Light level and motion"): the picture's
 * brightness in a coarse grid, compared frame to frame a few times a second. Part of the grid changing is motion; most of
 * it brighter or darker at once is a light switched on or off, after which motion is not judged for three seconds while
 * the camera adjusts. Only numbers leave here.
 */
public final class Sensing {

    static final int GX = 16, GY = 12;
    /** A light switched: how long motion is not judged after it. */
    static final long QUIET_MS = 3000;

    private int[] ref;
    private int refMean = -1;
    private long quietUntil, lastMove;
    private boolean motion, settling;
    private int mean = -1;

    public interface Out {
        void motion(boolean on);
        /** Something moved (every frame it does, Motion on or not). */
        void moved();
        /** The brightness settled after a light went on or off: the light level wants a new value now. */
        void lightChanged();
    }

    private final Out out;

    public Sensing(Out out) { this.out = out; }

    /** The picture's mean brightness, 0 to 255, or -1 before a frame. */
    public synchronized int mean() { return mean; }

    public synchronized boolean motion() { return motion; }

    /** Forget the last frame (the camera stopped, or turned): the next one starts afresh. */
    public synchronized void reset() {
        ref = null;
        refMean = -1;
    }

    /**
     * One frame's brightness plane (NV21's Y, or any 8-bit plane), at `now`; Motion stays on `timeoutMs` after the last
     * movement.
     */
    public void frame(byte[] y, int w, int h, int stride, long now, long timeoutMs) {
        int[] g = grid(y, w, h, stride);
        int sum = 0;
        for (int v : g) sum += v;
        int m = sum / g.length;
        boolean changedLight = false, on, off, moving = false;
        synchronized (this) {
            mean = m;
            on = false;
            if (ref != null) {
                int jump = Math.abs(m - refMean), same = 0;
                if (jump > Math.max(12, refMean / 4)) {
                    // A light: most of the picture brighter (or darker) at once, not one part of it.
                    for (int i = 0; i < g.length; i++) {
                        int d = g[i] - ref[i];
                        if (m > refMean ? d > 4 : d < -4) same++;
                    }
                }
                if (same * 10 >= g.length * 7) {
                    quietUntil = now + QUIET_MS;
                    settling = true;
                } else if (now >= quietUntil) {
                    if (settling) {
                        settling = false;
                        changedLight = true;
                    }
                    int shift = m - refMean, moved = 0;
                    for (int i = 0; i < g.length; i++) {
                        int d = Math.abs(g[i] - ref[i] - shift);
                        if (d > Math.max(8, ref[i] / 6)) moved++;
                    }
                    // Two cells of 192: a person across the room covers several, the sensor's noise rarely one.
                    if (moved >= 2) {
                        moving = true;
                        lastMove = now;
                        on = !motion;
                        motion = true;
                    }
                }
            }
            ref = g;
            refMean = m;
            off = motion && !on && now - lastMove > timeoutMs;
            if (off) motion = false;
        }
        if (on) out.motion(true);
        if (moving) out.moved();
        if (off) out.motion(false);
        if (changedLight) out.lightChanged();
    }

    /** Motion ends by its timeout also between frames. */
    public void tick(long now, long timeoutMs) {
        boolean off;
        synchronized (this) {
            off = motion && now - lastMove > timeoutMs;
            if (off) motion = false;
        }
        if (off) out.motion(false);
    }

    /** The plane's brightness in GX x GY cells, every fourth pixel of every fourth row. */
    static int[] grid(byte[] y, int w, int h, int stride) {
        int[] sum = new int[GX * GY], n = new int[GX * GY];
        for (int r = 0; r < h; r += 4) {
            int cy = r * GY / h, row = r * stride;
            for (int c = 0; c < w; c += 4) {
                int cell = cy * GX + c * GX / w;
                sum[cell] += y[row + c] & 0xff;
                n[cell]++;
            }
        }
        for (int i = 0; i < sum.length; i++) sum[i] = n[i] == 0 ? 0 : sum[i] / n[i];
        return sum;
    }

    /**
     * A light level in lux from the picture's brightness, where the camera can't say its exposure: a rough guess that
     * goes up and down with the room's light (the camera's own exposure evens much of it out).
     */
    public static float luxFromBrightness(int mean) {
        if (mean < 0) return 0;
        // Brightness 0..255 onto about 0..1000 lux, steeper at the top where the exposure has run out.
        double x = mean / 255.0;
        return (float) (Math.round(1000 * x * x * 10) / 10.0);
    }
}
