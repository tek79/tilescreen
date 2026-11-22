import app.tilescreen.core.Sensing;
import java.util.Random;

/** Sensing on made-up frames: a still room with sensor noise, a person crossing, a light switched on, a slow fade. */
public class SensingMain {
    static final int W = 640, H = 480;
    static long now = 0;
    static StringBuilder log = new StringBuilder();

    public static void main(String[] a) {
        Sensing s = new Sensing(new Sensing.Out() {
            public void motion(boolean on) { log.append(String.format("%6.1fs motion %s%n", now / 1000.0, on ? "on" : "off")); }
            public void moved() {}
            public void lightChanged() { log.append(String.format("%6.1fs light changed%n", now / 1000.0)); }
        });
        Random r = new Random(1);
        int base = 70;
        for (int f = 0; f < 5 * 60; f++) { // 60 s at 5 frames a second
            now = f * 200L;
            double t = now / 1000.0;
            int level = base;
            if (t >= 30) level = 160;                       // a light switched on at 30 s
            if (t >= 45) level = 160 - (int) ((t - 45) * 2); // a slow fade after 45 s
            byte[] y = new byte[W * H * 3 / 2];
            int px = -1;
            if (t >= 5 && t < 8) px = (int) ((t - 5) / 3 * W); // someone crosses from 5 s to 8 s
            for (int i = 0; i < H; i++) for (int j = 0; j < W; j++) {
                int v = level + (j * 40 / W) + (int) (r.nextGaussian() * 6);
                if (px >= 0 && j >= px && j < px + 80 && i > 100) v = 30 + (int) (r.nextGaussian() * 6);
                y[i * W + j] = (byte) Math.max(0, Math.min(255, v));
            }
            s.frame(y, W, H, W, now, 10000);
            if (f % 50 == 0) log.append(String.format("%6.1fs brightness %d, lux guess %.1f%n", t, s.mean(), Sensing.luxFromBrightness(s.mean())));
        }
        System.out.print(log);
    }
}
