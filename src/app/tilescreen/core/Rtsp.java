package app.tilescreen.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The camera as video for Home Assistant (Tessera's Screen camera, part Video; its components/screen_video
 * rtsp_server.cpp): RTSP on port 8554, RTP over each viewer's own connection (RFC 2326 10.12), H.264 as RFC 6184 (single
 * NAL units, FU-A), the microphone beside it, RTCP sender reports every second on both tracks against one clock. One user,
 * "tessera", with Basic authentication; the password is worked out from the screen's API key as Home Assistant's
 * tessera_video works it out (sha256("tessera-video:" + key), its first 24 hexadecimal digits). Up to three viewers:
 * Home Assistant's go2rtc and its own stream each open one.
 *
 * The sound is G.711 µ-law at 8 kHz (Tessera sends Opus, which Android can make only from version 10): go2rtc passes it
 * on to the browser as it is, as it does Opus.
 */
public final class Rtsp {

    public static final int PORT = 8554;
    public static final int MAX_VIEWERS = 3;
    static final int RTP_PAYLOAD = 1400;
    /** A viewer whose connection holds this much unsent video gets no picture until a key frame (its sound goes on). */
    static final int BEHIND_BYTES = 256 * 1024;

    /** What the server asks of the camera and microphone. */
    public interface Source {
        /** Someone plays, or nobody any more: the camera, its encoder and the microphone start or rest. */
        void playing(boolean anyone);
        /** Someone who plays takes the sound too, or nobody any more: the microphone listens only then. */
        void listening(boolean anyone);
        /** A viewer is about to ask (DESCRIBE): the encoder starts, so its SPS can go into the description. */
        void warm();
        /** A key frame soon, for a newcomer or a viewer left behind. */
        void keyFrame();
        /** The current SPS and PPS (without start codes), or null before the encoder made them. */
        byte[][] parameterSets();
        /** Whether there is a microphone to send. */
        boolean sound();
        /** The screen's API key as Home Assistant keeps it (base64), or null without one. */
        String key();
    }

    private final Source source;
    private final EspDevice.Logger log;
    private final List<Viewer> viewers = new CopyOnWriteArrayList<Viewer>();
    private final int ssrc = (int) (Math.random() * Integer.MAX_VALUE), soundSsrc = ssrc ^ 0x5a5a5a5a;
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile boolean anyone, hearing;
    /** The clock both tracks count from (System.nanoTime), and where the sound's counter stands against it. */
    private final long base = System.nanoTime();
    private long soundTs, soundAt = -1;

    public Rtsp(Source source, EspDevice.Logger log) {
        this.source = source;
        this.log = log;
    }

    /** The password for a key: what tessera_video's password() gives. */
    public static String password(String key) {
        byte[] h = Crypto.sha256(("tessera-video:" + (key == null ? "" : key)).getBytes(Pb.UTF8));
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 12; i++) s.append(String.format(Locale.US, "%02x", h[i] & 0xff));
        return s.toString();
    }

    public void start(int port) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        server = s;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() { accept(); }
        }, "rtsp-accept");
        t.setDaemon(true);
        t.start();
        log.log("video: RTSP on port " + s.getLocalPort());
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? 0 : s.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (Viewer v : viewers) v.close();
        viewers.clear();
        update();
    }

    /** How many viewers play now. */
    public int watching() {
        int n = 0;
        for (Viewer v : viewers) if (v.playing) n++;
        return n;
    }

    private void accept() {
        while (running) {
            try {
                Socket sock = server.accept();
                if (viewers.size() >= MAX_VIEWERS) {
                    // The place of a connection that never played goes to the newcomer; only when all play is it refused.
                    Viewer idle = null;
                    for (Viewer v : viewers) if (!v.playing) idle = v;
                    if (idle == null) {
                        log.log("video: a viewer was refused: " + MAX_VIEWERS + " watch already");
                        sock.close();
                        continue;
                    }
                    idle.close();
                }
                sock.setTcpNoDelay(true);
                final Viewer v = new Viewer(sock);
                viewers.add(v);
                Thread t = new Thread(new Runnable() {
                    public void run() { v.read(); }
                }, "rtsp-viewer");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) log.log("video: accept failed: " + e);
            }
        }
    }

    private synchronized void update() {
        boolean listens = false;
        for (Viewer v : viewers) if (v.playing && v.soundChannel >= 0) listens = true;
        if (listens != hearing) {
            hearing = listens;
            source.listening(listens);
        }
        boolean now = watching() > 0;
        if (now == anyone) return;
        anyone = now;
        if (!now) {
            synchronized (this) { soundAt = -1; }
        }
        log.log(now ? "video: someone watches" : "video: nobody watches any more");
        source.playing(now);
    }

    // ----- what the camera and the microphone hand over -----

    /** 90 kHz from the shared clock. */
    long videoClock(long nanos) { return (nanos - base) * 9 / 100000; }

    /**
     * An access unit from the encoder (Annex B: NAL units after 00 00 01 or 00 00 00 01) taken at `nanos`
     * (System.nanoTime); `key` when it holds a key frame, with its SPS and PPS in front.
     */
    public void video(byte[] au, long nanos, boolean key) {
        if (!anyone) return;
        int ts = (int) videoClock(nanos);
        for (Viewer v : viewers) if (v.playing && v.videoChannel >= 0) v.video(au, ts, key);
    }

    /** 20 ms of sound, 16-bit 16 kHz mono (little-endian), taken at `nanos`: it goes as µ-law at 8 kHz. */
    public void sound(byte[] pcm16k, long nanos) {
        if (!anyone) return;
        int samples = pcm16k.length / 4;
        byte[] ulaw = new byte[samples];
        for (int i = 0; i < samples; i++) {
            int a = (short) ((pcm16k[4 * i] & 0xff) | (pcm16k[4 * i + 1] << 8));
            int b = (short) ((pcm16k[4 * i + 2] & 0xff) | (pcm16k[4 * i + 3] << 8));
            ulaw[i] = ulaw((a + b) >> 1);
        }
        int ts;
        synchronized (this) {
            // The sound's own count keeps it smooth; where it drifts more than 60 ms from the clock, the clock wins.
            long want = (nanos - base) * 8 / 1000000 - samples;
            if (soundAt < 0 || Math.abs(soundTs - want) > 480) soundTs = want;
            ts = (int) soundTs;
            soundTs += samples;
            soundAt = nanos;
        }
        for (Viewer v : viewers) if (v.playing && v.soundChannel >= 0) v.sound(ulaw, ts);
    }

    /** G.711 µ-law of a 16-bit sample. */
    static byte ulaw(int sample) {
        int sign = sample < 0 ? 0x80 : 0;
        if (sign != 0) sample = -sample;
        if (sample > 32635) sample = 32635;
        sample += 0x84;
        int exponent = 7;
        for (int mask = 0x4000; (sample & mask) == 0 && exponent > 0; mask >>= 1) exponent--;
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    // ----- a viewer -----

    private final class Viewer {
        final Socket sock;
        final OutputStream out;
        volatile boolean playing, closed;
        volatile int videoChannel = -1, soundChannel = -1;
        int seq = (int) (Math.random() * 65535), soundSeq = (int) (Math.random() * 65535);
        long videoPackets, videoOctets, soundPackets, soundOctets;
        int lastVideoTs;
        boolean behind;
        // What waits to go out: the sound first (small, and a gap in it is heard), then the pictures.
        final ArrayDeque<byte[]> soundOut = new ArrayDeque<byte[]>(), videoOut = new ArrayDeque<byte[]>();
        int videoQueued;
        Thread sender;
        long reportsAt;

        Viewer(Socket sock) throws IOException {
            this.sock = sock;
            this.out = sock.getOutputStream();
        }

        void close() {
            if (closed) return;
            closed = true;
            playing = false;
            try {
                sock.close();
            } catch (IOException ignored) {
            }
            synchronized (this) { notifyAll(); }
            viewers.remove(this);
            update();
        }

        void read() {
            try {
                sock.setSoTimeout(60000);
                InputStream in = sock.getInputStream();
                StringBuilder req = new StringBuilder();
                byte[] head = new byte[4];
                while (!closed) {
                    int c = in.read();
                    if (c < 0) break;
                    if (c == '$' && req.length() == 0) {
                        // RTCP from the viewer over the connection: skipped.
                        readFully(in, head, 0, 3);
                        int len = ((head[1] & 0xff) << 8) | (head[2] & 0xff);
                        byte[] skip = new byte[len];
                        readFully(in, skip, 0, len);
                        continue;
                    }
                    req.append((char) c);
                    if (req.length() > 8192) break;
                    int n = req.length();
                    if (n >= 4 && req.charAt(n - 1) == '\n' && req.charAt(n - 2) == '\r' && req.charAt(n - 3) == '\n'
                            && req.charAt(n - 4) == '\r') {
                        String r = req.toString();
                        req.setLength(0);
                        int length = parseInt(header(r, "Content-Length"));
                        if (length > 0) {
                            byte[] body = new byte[Math.min(length, 65536)];
                            readFully(in, body, 0, body.length);
                        }
                        if (!request(r)) break;
                    }
                }
            } catch (IOException e) {
                // gone
            } finally {
                close();
            }
        }

        private boolean request(String r) throws IOException {
            String first = r.substring(0, r.indexOf("\r\n"));
            String[] words = first.split(" ");
            String method = words[0], uri = words.length > 1 ? words[1] : "";
            String cseq = header(r, "CSeq");
            // Without an encryption key there is no secret to work a password out of: no video then.
            String key = source.key();
            String expected = "Basic " + Crypto.base64(("tessera:" + password(key)).getBytes(Pb.UTF8));
            boolean allowed = key != null && expected.equals(header(r, "Authorization"));
            if (key == null && !method.equals("OPTIONS")) log.log("video: refused; the screen has no encryption key yet");
            StringBuilder reply = new StringBuilder("RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n");
            String body = "";
            boolean teardown = false, play = false;
            if (!method.equals("OPTIONS") && !allowed) {
                reply = new StringBuilder("RTSP/1.0 401 Unauthorized\r\nCSeq: " + cseq
                        + "\r\nWWW-Authenticate: Basic realm=\"Tessera\"\r\n");
                if (key != null && !header(r, "Authorization").isEmpty()) log.log("video: a viewer gave the wrong password");
            } else if (method.equals("OPTIONS")) {
                reply.append("Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n");
            } else if (method.equals("DESCRIBE")) {
                body = describe();
                String base = uri.endsWith("/") ? uri : uri + "/";
                reply.append("Content-Base: ").append(base).append("\r\nContent-Type: application/sdp\r\n");
            } else if (method.equals("SETUP")) {
                String transport = header(r, "Transport");
                if (!transport.contains("interleaved")) {
                    reply = new StringBuilder("RTSP/1.0 461 Unsupported Transport\r\nCSeq: " + cseq + "\r\n");
                } else {
                    boolean sound = uri.contains("track2");
                    int at = transport.indexOf("interleaved=");
                    int channel = at < 0 ? (sound ? 2 : 0) : Math.max(0, Math.min(254, parseInt(transport.substring(at + 12))));
                    if (sound) soundChannel = channel; else videoChannel = channel;
                    reply.append("Transport: RTP/AVP/TCP;unicast;interleaved=").append(channel).append("-")
                            .append(channel + 1).append("\r\nSession: 54455353;timeout=60\r\n");
                }
            } else if (method.equals("PLAY")) {
                reply.append("Session: 54455353\r\nRange: npt=0.000-\r\n");
                play = true;
            } else if (method.equals("TEARDOWN")) {
                reply.append("Session: 54455353\r\n");
                teardown = true;
            } else if (!method.equals("GET_PARAMETER") && !method.equals("SET_PARAMETER")) {
                reply = new StringBuilder("RTSP/1.0 405 Method Not Allowed\r\nCSeq: " + cseq + "\r\n");
            }
            byte[] b = body.getBytes(Pb.UTF8);
            if (b.length > 0) reply.append("Content-Length: ").append(b.length).append("\r\n");
            reply.append("\r\n");
            synchronized (out) {
                out.write(reply.toString().getBytes(Pb.UTF8));
                if (b.length > 0) out.write(b);
                out.flush();
            }
            if (teardown) return false;
            if (play && allowed && !playing) {
                sock.setSoTimeout(0);
                startSender();
                playing = true;
                source.keyFrame();
                update();
                log.log("video: a viewer plays" + (watching() > 1 ? " (" + watching() + " now)" : ""));
            }
            return true;
        }

        private String describe() {
            source.warm();
            byte[][] ps = null;
            for (int i = 0; i < 40 && (ps = source.parameterSets()) == null; i++) {
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
            String ip = sock.getLocalAddress().getHostAddress();
            String fmtp = "packetization-mode=1;profile-level-id=42e01f";
            if (ps != null && ps[0].length >= 4) {
                fmtp = String.format(Locale.US, "packetization-mode=1;profile-level-id=%02x%02x%02x;sprop-parameter-sets=%s,%s",
                        ps[0][1] & 0xff, ps[0][2] & 0xff, ps[0][3] & 0xff, Crypto.base64(ps[0]), Crypto.base64(ps[1]));
            }
            String sdp = "v=0\r\no=- 0 0 IN IP4 " + ip + "\r\ns=Tessera camera\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n"
                    + "a=control:*\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 " + fmtp
                    + "\r\na=control:track1\r\n";
            if (source.sound()) sdp += "m=audio 0 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=control:track2\r\n";
            return sdp;
        }

        // ----- sending -----

        void video(byte[] au, int ts, boolean key) {
            synchronized (this) {
                if (closed) return;
                if (videoQueued > BEHIND_BYTES) {
                    // Its connection is full: no picture until it has room again, and then a key frame first.
                    if (!behind) {
                        behind = true;
                        source.keyFrame();
                    }
                    return;
                }
                if (behind && !key) return;
                behind = false;
                packetize(au, ts);
                lastVideoTs = ts;
                notifyAll();
            }
        }

        void sound(byte[] ulaw, int ts) {
            synchronized (this) {
                if (closed) return;
                byte[] p = new byte[4 + 12 + ulaw.length];
                rtpHeader(p, soundChannel, 12 + ulaw.length, 0, false, soundSeq++, ts, soundSsrc);
                System.arraycopy(ulaw, 0, p, 16, ulaw.length);
                soundPackets++;
                soundOctets += ulaw.length;
                if (soundOut.size() < 50) soundOut.add(p); // a second at most waits; older sound is no use
                notifyAll();
            }
        }

        /** The access unit's NAL units as RTP packets (each with its '$' frame): small ones whole, large ones as FU-A. */
        private void packetize(byte[] d, int ts) {
            int len = d.length, count = 0;
            int[] starts = new int[64], ends = new int[64];
            for (int i = 0; i + 3 <= len && count < 64; i++) {
                if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                    if (count > 0) ends[count - 1] = i > 0 && d[i - 1] == 0 ? i - 1 : i;
                    starts[count++] = i + 3;
                    i += 2;
                }
            }
            if (count == 0) return;
            ends[count - 1] = len;
            for (int k = 0; k < count; k++) {
                int s = starts[k], n = ends[k] - s;
                if (n <= 0) continue;
                boolean last = k == count - 1;
                if (n <= RTP_PAYLOAD) {
                    byte[] p = new byte[16 + n];
                    rtpHeader(p, videoChannel, 12 + n, 96, last, seq++, ts, ssrc);
                    System.arraycopy(d, s, p, 16, n);
                    queueVideo(p, n);
                    continue;
                }
                int indicator = (d[s] & 0xE0) | 28, type = d[s] & 0x1F;
                for (int at = 1; at < n; ) {
                    int piece = Math.min(RTP_PAYLOAD - 2, n - at);
                    boolean firstPiece = at == 1, end = at + piece == n;
                    byte[] p = new byte[18 + piece];
                    rtpHeader(p, videoChannel, 14 + piece, 96, last && end, seq++, ts, ssrc);
                    p[16] = (byte) indicator;
                    p[17] = (byte) ((firstPiece ? 0x80 : 0) | (end ? 0x40 : 0) | type);
                    System.arraycopy(d, s + at, p, 18, piece);
                    queueVideo(p, piece + 2);
                    at += piece;
                }
            }
        }

        private void queueVideo(byte[] p, int payload) {
            videoOut.add(p);
            videoQueued += p.length;
            videoPackets++;
            videoOctets += payload;
        }

        void startSender() {
            sender = new Thread(new Runnable() {
                public void run() { send(); }
            }, "rtsp-send");
            sender.setDaemon(true);
            sender.start();
        }

        private void send() {
            byte[] batch = new byte[64 * 1024];
            try {
                while (!closed) {
                    int n = 0;
                    boolean report = false;
                    synchronized (this) {
                        while (!closed && soundOut.isEmpty() && videoOut.isEmpty()) {
                            try { wait(1000); } catch (InterruptedException e) { return; }
                            if (System.currentTimeMillis() - reportsAt >= 1000) break;
                        }
                        // The sound first, then pictures up to a batch's size; the sound goes between the packets of a
                        // long picture, as the batch ends and the next begins.
                        while (!soundOut.isEmpty() && n + soundOut.peek().length <= batch.length) {
                            byte[] p = soundOut.poll();
                            System.arraycopy(p, 0, batch, n, p.length);
                            n += p.length;
                        }
                        int pictures = 0;
                        while (!videoOut.isEmpty() && pictures < 16 && n + videoOut.peek().length <= batch.length) {
                            byte[] p = videoOut.poll();
                            videoQueued -= p.length;
                            System.arraycopy(p, 0, batch, n, p.length);
                            n += p.length;
                            pictures++;
                        }
                        long now = System.currentTimeMillis();
                        if (now - reportsAt >= 1000) {
                            reportsAt = now;
                            report = true;
                            n = reports(batch, n);
                        }
                    }
                    if (n > 0) {
                        synchronized (out) {
                            out.write(batch, 0, n);
                            out.flush();
                        }
                    }
                }
            } catch (IOException e) {
                // gone
            } finally {
                close();
            }
        }

        /** Sender reports (RFC 3550 6.4.1) on both tracks, against the shared clock. */
        private int reports(byte[] b, int n) {
            long nanos = System.nanoTime();
            long wallMs = System.currentTimeMillis();
            if (videoChannel >= 0 && videoPackets > 0) {
                n = report(b, n, videoChannel + 1, ssrc, wallMs, (int) videoClock(nanos), videoPackets, videoOctets);
            }
            if (soundChannel >= 0 && soundPackets > 0) {
                long ts = (nanos - base) * 8 / 1000000;
                n = report(b, n, soundChannel + 1, soundSsrc, wallMs, (int) ts, soundPackets, soundOctets);
            }
            return n;
        }
    }

    private static int report(byte[] b, int n, int channel, int ssrc, long wallMs, int rtp, long packets, long octets) {
        if (n + 32 > b.length) return n;
        b[n] = '$';
        b[n + 1] = (byte) channel;
        b[n + 2] = 0;
        b[n + 3] = 28;
        b[n + 4] = (byte) 0x80;
        b[n + 5] = (byte) 200;
        b[n + 6] = 0;
        b[n + 7] = 6;
        long ntpS = wallMs / 1000 + 2208988800L, ntpF = ((wallMs % 1000) << 32) / 1000;
        long[] words = {ssrc, ntpS, ntpF, rtp, packets, octets};
        for (int i = 0; i < 6; i++) put32(b, n + 8 + i * 4, (int) words[i]);
        return n + 32;
    }

    private static void rtpHeader(byte[] p, int channel, int rtpLen, int type, boolean marker, int seq, int ts, int ssrc) {
        p[0] = '$';
        p[1] = (byte) channel;
        p[2] = (byte) (rtpLen >> 8);
        p[3] = (byte) rtpLen;
        p[4] = (byte) 0x80;
        p[5] = (byte) ((marker ? 0x80 : 0) | type);
        p[6] = (byte) (seq >> 8);
        p[7] = (byte) seq;
        put32(p, 8, ts);
        put32(p, 12, ssrc);
    }

    private static void put32(byte[] b, int at, int v) {
        b[at] = (byte) (v >> 24);
        b[at + 1] = (byte) (v >> 16);
        b[at + 2] = (byte) (v >> 8);
        b[at + 3] = (byte) v;
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int r = in.read(b, off, len);
            if (r < 0) throw new IOException("closed");
            off += r;
            len -= r;
        }
    }

    private static int parseInt(String s) {
        int v = 0, i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        boolean any = false;
        for (; i < s.length() && Character.isDigit(s.charAt(i)); i++) {
            v = v * 10 + (s.charAt(i) - '0');
            any = true;
        }
        return any ? v : -1;
    }

    /** A header's value, found without regard to case; "" when missing. */
    static String header(String request, String name) {
        String lower = request.toLowerCase(Locale.US), key = "\r\n" + name.toLowerCase(Locale.US) + ":";
        int at = lower.indexOf(key);
        if (at < 0) return "";
        at += key.length();
        int end = request.indexOf("\r\n", at);
        return request.substring(at, end < 0 ? request.length() : end).trim();
    }
}
