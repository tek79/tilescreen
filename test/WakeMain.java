package app.tilescreen.core;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Test entry for the wake word (test/wake_test.py). Reads 16 kHz s16le mono from a file and prints, one line per step:
 *   features <file>              the raw 40 front-end values of each 10 ms window
 *   probs <model.tflite> <file>  the model's uint8 output at each inference
 *   detect <dir> <file>...       WakeWord's detections with the models and VAD in <dir>
 *   speed <dir> <seconds>        how long the whole wake word takes for that much audio
 */
public final class WakeMain {
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "features": {
                short[] pcm = read(a[1]);
                MicroFrontend f = new MicroFrontend();
                byte[] feat = new byte[MicroFrontend.CHANNELS];
                StringBuilder out = new StringBuilder();
                for (int at = 0; at < pcm.length; ) {
                    at += f.take(pcm, at, pcm.length - at, feat);
                    if (!f.ready) continue;
                    int[] raw = f.raw();
                    for (int i = 0; i < raw.length; i++) out.append(raw[i]).append(i + 1 < raw.length ? ' ' : '\n');
                }
                System.out.print(out);
                break;
            }
            case "tables": {
                MicroFrontend f = new MicroFrontend();
                StringBuilder out = new StringBuilder();
                out.append("window"); for (short v : f.coefficients) out.append(' ').append(v); out.append('\n');
                out.append("super"); for (short v : f.superTwiddles) out.append(' ').append(v); out.append('\n');
                out.append("bank ").append(f.startIndex).append(' ').append(f.endIndex);
                for (int i = 0; i <= MicroFrontend.CHANNELS; i++)
                    out.append(' ').append(f.channelFrequencyStarts[i]).append(' ').append(f.channelWeightStarts[i]).append(' ').append(f.channelWidths[i]);
                out.append('\n');
                out.append("weights"); for (short v : f.weights) out.append(' ').append(v); out.append('\n');
                out.append("unweights"); for (short v : f.unweights) out.append(' ').append(v); out.append('\n');
                out.append("lut"); for (int i = 0; i < 4 * 32 - 3; i++) out.append(' ').append(f.gainLut[i]); out.append('\n');
                System.out.print(out);
                break;
            }
            default:
                Extra.run(a);
        }
    }

    static short[] read(String file) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(file));
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
        return s;
    }

    static byte[] bytes(String file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    static final class Extra {
        static void run(String[] a) throws Exception {
            switch (a[0]) {
                case "probs": {
                    // Features as the C prints them (raw), one row per line, through the model with ESPHome's scaling.
                    MicroModel m = new MicroModel(bytes(a[1]));
                    java.util.List<String> lines = Files.readAllLines(Paths.get(a[2]));
                    byte[] in = m.input();
                    int step = 0;
                    StringBuilder out = new StringBuilder();
                    for (String line : lines) {
                        if (line.trim().isEmpty()) continue;
                        String[] v = line.trim().split(" ");
                        for (int i = 0; i < v.length; i++) {
                            int x = ((Integer.parseInt(v[i]) * 256) + 333) / 666 - 128;
                            in[step * m.features + i] = (byte) Math.max(-128, Math.min(127, x));
                        }
                        if (++step == m.stride) { step = 0; out.append(m.invoke()).append('\n'); }
                    }
                    System.out.print(out);
                    break;
                }
                case "detect": {
                    // Each file through each phrase: what it heard and when.
                    final String dir = a[1];
                    WakeWord w = new WakeWord(new WakeWord.Models() {
                        public byte[] load(String id) throws java.io.IOException {
                            try { return bytes(dir + "/" + id + ".tflite"); } catch (Exception e) { throw new java.io.IOException(e); }
                        }
                    });
                    StringBuilder out = new StringBuilder();
                    for (int i = 2; i < a.length; i++) {
                        byte[] pcm = bytes(a[i]);
                        out.append(Paths.get(a[i]).getFileName());
                        for (int k = 0; k < WakeWord.IDS.length; k++) {
                            w.choose(k);
                            w.reset();
                            w.blocked = null;
                            byte[] part = new byte[640];
                            for (int at = 0; at + 640 <= pcm.length; at += 640) {
                                System.arraycopy(pcm, at, part, 0, 640);
                                String heard = w.hear(part, 640);
                                if (heard != null) out.append(" | ").append(WakeWord.IDS[k]).append('@').append(at / 32).append("ms avg ").append(w.lastAverage).append(" most ").append(w.lastMost);
                            }
                            if (w.blocked != null) out.append(" | ").append(WakeWord.IDS[k]).append(" blocked by vad");
                        }
                        out.append('\n');
                    }
                    System.out.print(out);
                    break;
                }
                case "speed": {
                    final String dir = a[1];
                    WakeWord w = new WakeWord(new WakeWord.Models() {
                        public byte[] load(String id) throws java.io.IOException {
                            try { return bytes(dir + "/" + id + ".tflite"); } catch (Exception e) { throw new java.io.IOException(e); }
                        }
                    });
                    w.choose(0);
                    int seconds = Integer.parseInt(a[2]);
                    java.util.Random r = new java.util.Random(1);
                    byte[] part = new byte[640];
                    long t0 = System.nanoTime();
                    for (int i = 0; i < seconds * 50; i++) { r.nextBytes(part); w.hear(part, 640); }
                    long ms = (System.nanoTime() - t0) / 1000000;
                    System.out.println(seconds + " s of sound in " + ms + " ms");
                    break;
                }
                default: throw new IllegalArgumentException(a[0]);
            }
        }
    }
}
