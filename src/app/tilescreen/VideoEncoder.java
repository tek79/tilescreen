package app.tilescreen;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;

import java.nio.ByteBuffer;

import app.tilescreen.core.Rtsp;

/**
 * The camera's frames as H.264 for the video (Tessera's Screen camera, part Video: 15 frames a second, a key frame every
 * second, about a megabit a second at 1280 x 720), with the tablet's own encoder (Android's MediaCodec). Frames go in as
 * NV21 (the camera's preview); each access unit goes to the RTSP server, a key frame with its SPS and PPS in front.
 */
@SuppressWarnings("deprecation")
final class VideoEncoder {
    static final String AVC = "video/avc";
    static final int FPS = 15;
    private static final int FLEXIBLE = 0x7F420888, SEMI_PLANAR = 21, PLANAR = 19;

    final int width, height;
    private final App app;
    private final Rtsp rtsp;
    private final MediaCodec codec;
    private final int colorFormat;
    private final Thread drainer;
    private volatile boolean running = true;
    private ByteBuffer[] inputs;
    private byte[] planar;
    /** SPS and PPS (Annex B) to go before every key frame, and each without its start code for the SDP. */
    private volatile byte[] config;
    static volatile byte[][] parameterSets;
    private int refusals;
    private long frames, encodedBytes, startedAt = System.currentTimeMillis(), putNanos;

    VideoEncoder(App app, Rtsp rtsp, int width, int height) throws Exception {
        this.app = app;
        this.rtsp = rtsp;
        this.width = width;
        this.height = height;
        String name = null;
        int chosenFormat = 0;
        // The first hardware encoder for H.264 (Android's own software one, OMX.google, only when there is none).
        for (int pass = 0; pass < 2 && name == null; pass++) {
            for (int i = 0; i < MediaCodecList.getCodecCount() && name == null; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (!info.isEncoder()) continue;
                boolean soft = info.getName().startsWith("OMX.google.") || info.getName().startsWith("c2.android.");
                if (pass == 0 && soft) continue;
                for (String t : info.getSupportedTypes()) {
                    if (!t.equalsIgnoreCase(AVC)) continue;
                    int f;
                    try {
                        f = pickFormat(info.getCapabilitiesForType(AVC).colorFormats);
                    } catch (Exception e) {
                        continue; // an encoder that can't say what it takes
                    }
                    if (f != 0) {
                        name = info.getName();
                        chosenFormat = f;
                    }
                }
            }
        }
        if (name == null) throw new IllegalStateException("no H.264 encoder takes the camera's frames");
        colorFormat = chosenFormat;
        MediaCodec c = MediaCodec.createByCodecName(name);
        try {
            configure(c, true);
        } catch (Exception e) {
            c.release();
            c = MediaCodec.createByCodecName(name);
            configure(c, false); // without asking for Baseline
        }
        codec = c;
        codec.start();
        if (Build.VERSION.SDK_INT < 21) inputs = codec.getInputBuffers();
        app.log("video: " + name + ", " + width + " x " + height + " at " + FPS + " fps, colour format " + colorFormat);
        drainer = new Thread(new Runnable() {
            public void run() { drain(); }
        }, "video-encoder");
        drainer.start();
    }

    private static int pickFormat(int[] formats) {
        boolean flexible = false, semi = false, planar = false;
        for (int f : formats) {
            if (f == FLEXIBLE) flexible = true;
            if (f == SEMI_PLANAR) semi = true;
            if (f == PLANAR) planar = true;
        }
        if (Build.VERSION.SDK_INT >= 21 && flexible) return FLEXIBLE;
        return semi ? SEMI_PLANAR : planar ? PLANAR : 0;
    }

    private void configure(MediaCodec c, boolean baseline) {
        MediaFormat f = MediaFormat.createVideoFormat(AVC, width, height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        f.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(400000, Math.min(2000000, width * height * 13 / 10)));
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        // Baseline, which every browser plays.
        if (baseline && Build.VERSION.SDK_INT >= 21) {
            f.setInteger("profile", MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            f.setInteger("level", MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        }
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    /** Asks for a key frame soon (Android 4.4 and later; before that one comes every second anyway). */
    void keyFrame() {
        if (Build.VERSION.SDK_INT < 19 || !running) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception ignored) {
        }
    }

    /** A frame (NV21, exactly width x height) taken at `nanos`; dropped when the encoder has no room for it now. */
    void put(byte[] nv21, long nanos) {
        if (!running) return;
        long t0 = System.nanoTime();
        try {
            int index = codec.dequeueInputBuffer(0);
            if (index < 0) return;
            int size = width * height * 3 / 2;
            if (Build.VERSION.SDK_INT >= 21 && colorFormat == FLEXIBLE) {
                android.media.Image img = codec.getInputImage(index);
                if (img == null) throw new IllegalStateException("no input image");
                fill(img, nv21);
            } else {
                ByteBuffer in = Build.VERSION.SDK_INT >= 21 ? codec.getInputBuffer(index) : inputs[index];
                in.clear();
                if (colorFormat == SEMI_PLANAR) {
                    // NV12: NV21 with each colour pair the other way round.
                    if (planar == null) planar = new byte[size];
                    int y = width * height;
                    System.arraycopy(nv21, 0, planar, 0, y);
                    for (int i = y; i + 1 < size; i += 2) {
                        planar[i] = nv21[i + 1];
                        planar[i + 1] = nv21[i];
                    }
                } else {
                    // I420: all of U, then all of V.
                    if (planar == null) planar = new byte[size];
                    int y = width * height, q = y / 4;
                    System.arraycopy(nv21, 0, planar, 0, y);
                    for (int i = 0; i < q; i++) {
                        planar[y + i] = nv21[y + 2 * i + 1];
                        planar[y + q + i] = nv21[y + 2 * i];
                    }
                }
                in.put(planar, 0, size);
            }
            codec.queueInputBuffer(index, 0, size, nanos / 1000, 0);
            putNanos += System.nanoTime() - t0;
        } catch (Exception e) {
            if (refusals++ == 0) app.log("video: the encoder refused a frame: " + e);
        }
    }

    /** NV21 into the encoder's own layout, whatever its strides (Android 5 and later). */
    private void fill(android.media.Image img, byte[] nv21) {
        android.media.Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        int yStride = p[0].getRowStride();
        for (int r = 0; r < height; r++) {
            yb.position(r * yStride);
            yb.put(nv21, r * width, width);
        }
        ByteBuffer ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int uStride = p[1].getRowStride(), vStride = p[2].getRowStride(), up = p[1].getPixelStride(), vp = p[2].getPixelStride();
        int base = width * height, cw = width / 2, ch = height / 2;
        for (int r = 0; r < ch; r++) {
            int src = base + r * width, u = r * uStride, v = r * vStride;
            for (int x = 0; x < cw; x++) {
                ub.put(u + x * up, nv21[src + 2 * x + 1]);
                vb.put(v + x * vp, nv21[src + 2 * x]);
            }
        }
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteBuffer[] outputs = Build.VERSION.SDK_INT < 21 ? codec.getOutputBuffers() : null;
        long loggedAt = System.currentTimeMillis();
        try {
            while (running) {
                int index = codec.dequeueOutputBuffer(info, 20000);
                if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    if (Build.VERSION.SDK_INT < 21) outputs = codec.getOutputBuffers();
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    if (config == null && f.containsKey("csd-0") && f.containsKey("csd-1")) {
                        setConfig(concat(bytes(f.getByteBuffer("csd-0")), bytes(f.getByteBuffer("csd-1"))));
                    }
                    continue;
                }
                if (index < 0) continue;
                ByteBuffer out = Build.VERSION.SDK_INT >= 21 ? codec.getOutputBuffer(index) : outputs[index];
                byte[] data = new byte[info.size];
                out.position(info.offset);
                out.limit(info.offset + info.size);
                out.get(data);
                codec.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    setConfig(data);
                    continue;
                }
                boolean key = (info.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                byte[] c = config;
                if (key && c != null) data = concat(c, data);
                frames++;
                encodedBytes += data.length;
                rtsp.video(data, info.presentationTimeUs * 1000, key);
                long now = System.currentTimeMillis();
                if (now - loggedAt >= 60000) {
                    app.log(String.format(java.util.Locale.US, "video: %.1f fps, %d kbit/s, %.0f ms a frame to hand over",
                            frames * 1000.0 / (now - loggedAt), encodedBytes * 8 / Math.max(1, now - loggedAt),
                            putNanos / 1e6 / Math.max(1, frames)));
                    loggedAt = now;
                    frames = encodedBytes = putNanos = 0;
                }
            }
        } catch (Exception e) {
            if (running) app.log("video: the encoder stopped: " + e);
        }
    }

    private void setConfig(byte[] annexB) {
        config = annexB;
        // The SPS and PPS without their start codes, for the description.
        byte[] sps = null, pps = null;
        java.util.List<int[]> nals = new java.util.ArrayList<int[]>();
        for (int i = 0; i + 3 <= annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                if (!nals.isEmpty()) nals.get(nals.size() - 1)[1] = i > 0 && annexB[i - 1] == 0 ? i - 1 : i;
                nals.add(new int[]{i + 3, annexB.length});
                i += 2;
            }
        }
        for (int[] n : nals) {
            if (n[1] <= n[0]) continue;
            byte[] nal = java.util.Arrays.copyOfRange(annexB, n[0], n[1]);
            if ((nal[0] & 0x1F) == 7) sps = nal;
            if ((nal[0] & 0x1F) == 8) pps = nal;
        }
        if (sps != null && pps != null) parameterSets = new byte[][]{sps, pps};
    }

    private static byte[] bytes(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        d.position(0);
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    void close() {
        running = false;
        try {
            drainer.join(500);
        } catch (InterruptedException ignored) {
        }
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
    }
}
