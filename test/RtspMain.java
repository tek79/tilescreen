import app.tilescreen.core.EspDevice;
import app.tilescreen.core.Rtsp;
import java.nio.file.*;
import java.util.*;

/** The video server on a desktop: an H.264 file's access units at 15 a second and a tone as the microphone. */
public class RtspMain {
    public static void main(String[] a) throws Exception {
        final byte[] h264 = Files.readAllBytes(Paths.get(a[1]));
        final byte[] tone = Files.readAllBytes(Paths.get(a[2]));
        final String key = a.length > 3 ? a[3] : null;
        // Access units split at each access unit delimiter (type 9).
        final List<byte[]> aus = new ArrayList<byte[]>();
        int start = -1;
        for (int i = 0; i + 4 < h264.length; i++) {
            if (h264[i] == 0 && h264[i + 1] == 0 && h264[i + 2] == 0 && h264[i + 3] == 1 && (h264[i + 4] & 0x1f) == 9) {
                if (start >= 0) aus.add(Arrays.copyOfRange(h264, start, i));
                start = i;
            }
        }
        aus.add(Arrays.copyOfRange(h264, start, h264.length));
        final byte[][] ps = new byte[2][];
        for (int i = 0; i + 4 < aus.get(0).length; i++) {
            byte[] u = aus.get(0);
            if (u[i] == 0 && u[i + 1] == 0 && u[i + 2] == 1) {
                int t = u[i + 3] & 0x1f, s = i + 3, e = s;
                while (e + 3 < u.length && !(u[e] == 0 && u[e + 1] == 0 && (u[e + 2] == 1 || u[e + 2] == 0 && u[e + 3] == 1))) e++;
                if (t == 7) ps[0] = Arrays.copyOfRange(u, s, e);
                if (t == 8) ps[1] = Arrays.copyOfRange(u, s, e);
            }
        }
        final boolean[] playing = {false};
        final Rtsp r = new Rtsp(new Rtsp.Source() {
            public void playing(boolean on) { playing[0] = on; System.out.println("[src] playing " + on); }
            public void listening(boolean on) { System.out.println("[src] listening " + on); }
            public void warm() { System.out.println("[src] warm"); }
            public void keyFrame() { System.out.println("[src] key frame asked"); }
            public byte[][] parameterSets() { return ps; }
            public boolean sound() { return true; }
            public String key() { return key; }
        }, new EspDevice.Logger() { public void log(String l) { System.out.println("[log] " + l); } });
        r.start(Integer.parseInt(a[0]));
        System.out.println("password " + Rtsp.password(key));
        long t0 = System.nanoTime();
        int frame = 0, part = 0;
        while (true) {
            long now = System.nanoTime();
            while ((now - t0) / 1000000 >= part * 20L) {
                int off = (part * 640) % (tone.length - 640);
                r.sound(Arrays.copyOfRange(tone, off, off + 640), t0 + (part + 1) * 20000000L);
                part++;
            }
            if ((now - t0) / 1000000 >= frame * 1000L / 15) {
                byte[] au = aus.get(frame % aus.size());
                boolean key2 = false;
                for (int i = 0; i + 3 < au.length; i++) if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1 && (au[i + 3] & 0x1f) == 5) key2 = true;
                r.video(au, t0 + frame * 1000000000L / 15, key2);
                frame++;
            }
            Thread.sleep(2);
        }
    }
}
