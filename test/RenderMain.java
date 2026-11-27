import app.tilescreen.App;
import app.tilescreen.core.Assist;
import app.tilescreen.core.Tessera;
import app.tilescreen.core.*;
import app.tilescreen.ui.TileView;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

/** Runs the real TileView against the real core, rendering frames and replaying touches on command. */
public class RenderMain {
    /**
     * The wake word as WakeListener runs it, on a desktop: a file plays as the microphone, in real time and over again,
     * while Wake word is on and Home Assistant takes the voice assistant; WakeWord hears it, and a question uses the same
     * sound (the test's microphone sink).
     */
    static volatile Assist.Sink wakeSink;

    static void wake(final Screen screen, final Assist assist, final String file) throws Exception {
        final byte[] pcm = Files.readAllBytes(Paths.get(file));
        final WakeWord word = new WakeWord(new WakeWord.Models() {
            public byte[] load(String id) throws IOException { return Files.readAllBytes(Paths.get("assets/wake/" + id + ".tflite")); }
        });
        assist.microphone = new Assist.Microphone() {
            public boolean start(Assist.Sink sink) { wakeSink = sink; System.out.println("[mic] the wake word's recording"); return true; }
            public void stop() { wakeSink = null; System.out.println("[mic] stopped"); }
        };
        Thread t = new Thread(new Runnable() {
            public void run() {
                byte[] part = new byte[640];
                int at = 0;
                boolean listening = false;
                try {
                    while (true) {
                        boolean want = screen.screenSettings.assistWakeWord() && screen.device.voiceReady();
                        if (!want) {
                            if (listening) System.out.println("[wake] resting");
                            listening = false;
                            Thread.sleep(100);
                            continue;
                        }
                        if (!listening) {
                            word.choose(screen.screenSettings.assistWakePhrase());
                            word.reset();
                            at = 0;
                            listening = true;
                            System.out.println("[wake] listening for " + word.phrase());
                        }
                        if (!word.phrase().equals(WakeWord.PHRASES[screen.screenSettings.assistWakePhrase()])) {
                            word.choose(screen.screenSettings.assistWakePhrase());
                            System.out.println("[wake] listening for " + word.phrase());
                        }
                        if (at + 640 > pcm.length) at = 0;
                        System.arraycopy(pcm, at, part, 0, 640);
                        at += 640;
                        Assist.Sink s = wakeSink;
                        if (s != null) s.part(part.clone());
                        String heard = word.hear(part, 640);
                        if (heard != null) {
                            System.out.println("[wake] heard " + heard + " (" + word.lastAverage + "/" + word.lastMost + ")");
                            assist.wake(heard);
                        }
                        Thread.sleep(20);
                    }
                } catch (Exception e) {
                    System.out.println("[wake] " + e);
                }
            }
        }, "wake");
        t.setDaemon(true);
        t.start();
    }

    /**
     * A Bluetooth radio on a desktop, for test/ble_test.py: two devices advertise every 200 ms; one of them (a thermometer
     * at AA:BB:CC:00:00:01) takes connections, with the battery service (0x180F: its level 0x2A19 reads 87 and notifies,
     * a CCCD beside it) and a writable characteristic.
     */
    /** A camera that draws its pictures: the time on a grey room, as a JPEG (CAMERA=1). */
    static void fakeCamera(final Screen screen) {
        Tessera.hasCamera = true;
        final EspDevice.Camera cam = screen.enableCamera(true);
        final int[] count = {0};
        cam.source = new EspDevice.Camera.Source() {
            public void wanted(final boolean stream) {
                new Thread(new Runnable() { public void run() {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                    try {
                        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(640, 360, java.awt.image.BufferedImage.TYPE_INT_RGB);
                        java.awt.Graphics2D g = img.createGraphics();
                        g.setColor(new java.awt.Color(90, 90, 100)); g.fillRect(0, 0, 640, 360);
                        g.setColor(java.awt.Color.WHITE); g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 40));
                        g.drawString("picture " + (++count[0]) + (stream ? " (stream)" : ""), 40, 190);
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        javax.imageio.ImageIO.write(img, "jpg", out);
                        // A big one now and then, so the pieces show.
                        byte[] b = out.toByteArray();
                        if (count[0] % 3 == 0) { byte[] big = java.util.Arrays.copyOf(b, b.length + 40000); b = big; }
                        System.out.println("[camera] picture " + count[0] + ", " + b.length + " bytes");
                        cam.picture(b);
                    } catch (Exception e) { e.printStackTrace(); }
                } }).start();
            }
        };
        screen.lightLevel.set(123.4f);
        screen.motion.set(false);
    }

    static void fakeBluetooth(final Screen screen) {
        Tessera.hasBluetooth = true;
        screen.screenSettings.bluetoothToggles(screen.device);
        final BleProxy proxy = new BleProxy(new EspDevice.Logger() { public void log(String l) { System.out.println("[log] " + l); } });
        final long dev = BleProxy.address("AA:BB:CC:00:00:01"), other = BleProxy.address("AA:BB:CC:00:00:02");
        final java.util.Set<Integer> notifying = java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
        final java.util.Set<Long> connected = java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());
        final boolean[] scanning = {false};
        final byte[][] value = {{87}, {}};
        proxy.setRadio(new BleProxy.Radio() {
            public boolean startScan() { scanning[0] = true; System.out.println("[radio] scan"); return true; }
            public void stopScan() { scanning[0] = false; System.out.println("[radio] scan stops"); }
            public String problem() { return ""; }
            public void connect(final long a, int type) {
                System.out.println("[radio] connect " + BleProxy.text(a) + " type " + type);
                new Thread(new Runnable() { public void run() {
                    try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                    if (a == dev) { connected.add(a); proxy.connected(a, 247); } else proxy.disconnected(a, 133);
                } }).start();
            }
            public void disconnect(long a) { connected.remove(a); notifying.clear(); proxy.disconnected(a, 0); }
            public java.util.List<BleProxy.Service> services(long a) {
                if (!connected.contains(a)) return null;
                BleProxy.Service s = new BleProxy.Service(uuid16(0x180F), LOW, 0x10);
                BleProxy.Characteristic level = new BleProxy.Characteristic(uuid16(0x2A19), LOW, 0x12, 2 | 16);
                level.descriptors.add(new BleProxy.Descriptor(uuid16(0x2902), LOW, 0x13));
                s.characteristics.add(level);
                s.characteristics.add(new BleProxy.Characteristic(uuid16(0x2A00), LOW, 0x15, 8));
                return java.util.Collections.singletonList(s);
            }
            public void read(long a, int h) { if (h == 0x12) proxy.readDone(a, h, value[0], 0); else proxy.readDone(a, h, null, 2); }
            public void write(long a, int h, byte[] d, boolean r) { value[1] = d; System.out.println("[radio] write " + h + " " + d.length + " bytes"); proxy.writeDone(a, h, 0, r); }
            public void readDescriptor(long a, int h) { proxy.readDone(a, h, new byte[]{(byte) (notifying.isEmpty() ? 0 : 1), 0}, 0); }
            public void writeDescriptor(long a, int h, byte[] d) {
                System.out.println("[radio] cccd " + (d.length > 0 ? d[0] : -1));
                if (d.length > 0 && d[0] != 0) notifying.add(0x12); else notifying.remove(0x12);
                proxy.writeDone(a, h, 0, true);
            }
            public void notify(long a, int h, boolean on) { proxy.notifyDone(a, h, 0); }
            public void pair(long a) { proxy.paired(a, true, 0); }
            public void unpair(long a) { proxy.unpaired(a, true, 0); }
            public void clearCache(long a) { proxy.cacheCleared(a, true, 0); }
        });
        screen.device.bluetooth = proxy;
        screen.device.bluetoothMac = "11:22:33:44:55:66";
        Runnable apply = new Runnable() {
            public void run() {
                boolean on = screen.screenSettings.bluetoothProxy();
                screen.device.bluetoothFeatures = on ? BleProxy.FEATURES : 0;
                proxy.enable(on);
            }
        };
        apply.run();
        screen.screenSettings.onBluetooth = new Runnable() {
            public void run() {
                boolean on = screen.screenSettings.bluetoothProxy();
                screen.device.bluetoothFeatures = on ? BleProxy.FEATURES : 0;
                proxy.enable(on);
                screen.device.reconnectClients();
            }
        };
        Thread air = new Thread(new Runnable() { public void run() {
            int n = 0;
            while (true) {
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                if (!scanning[0]) continue;
                // Flags, a name "Thermo", and a heart rate service UUID; then the other device's manufacturer data.
                proxy.advertisement(dev, -60 - (n % 5), 0, new byte[]{2, 1, 6, 7, 9, 'T', 'h', 'e', 'r', 'm', 'o', 3, 3, 0x0D, 0x18});
                proxy.advertisement(other, -80, 1, new byte[]{5, (byte) 0xFF, 0x4C, 0x00, 1, 2});
                if (notifying.contains(0x12) && n % 5 == 0) proxy.notification(dev, 0x12, new byte[]{(byte) (80 + n % 10)});
                n++;
            }
        } });
        air.setDaemon(true);
        air.start();
    }

    /** The doorbell view's stream on a desktop: reads the multipart as CallStream does, counts its sound while unmuted. */
    static final class DeskStream implements app.tilescreen.ui.CallMedia {
        volatile android.graphics.Bitmap picture;
        volatile boolean muted, running;
        volatile long heard;
        volatile int played, dropped;
        volatile java.net.Socket socket;
        public void start(final String link, final Runnable changed) {
            System.out.println("[ring-stream] open " + link + (muted ? " (muted)" : ""));
            running = true;
            new Thread(new Runnable() { public void run() {
                try {
                    java.net.URI u = new java.net.URI(link);
                    java.net.Socket s = new java.net.Socket(u.getHost(), u.getPort());
                    socket = s;
                    s.getOutputStream().write(("GET " + u.getRawPath() + "?" + u.getRawQuery() + " HTTP/1.1\r\nHost: x\r\n\r\n").getBytes("US-ASCII"));
                    java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(s.getInputStream()));
                    String line;
                    while ((line = readLine(in)) != null && !line.isEmpty()) {}
                    while (true) {
                        line = readLine(in);
                        if (line == null) break;
                        if (!line.startsWith("--")) continue;
                        String type = ""; int len = 0;
                        while ((line = readLine(in)) != null && !line.isEmpty()) {
                            String l = line.toLowerCase();
                            if (l.startsWith("content-type:")) type = line.substring(13).trim();
                            if (l.startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
                        }
                        byte[] b = new byte[len];
                        in.readFully(b);
                        if (type.startsWith("image/")) { picture = android.graphics.BitmapFactory.decodeByteArray(b, 0, len); changed.run(); }
                        else if (type.startsWith("audio/")) { heard = System.currentTimeMillis(); if (muted) dropped++; else played++; }
                    }
                } catch (Exception e) {
                    // closed
                } finally {
                    running = false;
                    System.out.println("[ring-stream] ended: " + played + " parts played, " + dropped + " muted");
                }
            } }).start();
        }
        static String readLine(java.io.DataInputStream in) throws java.io.IOException {
            StringBuilder b = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) { if (c == '\n') break; if (c != '\r') b.append((char) c); }
            return c < 0 && b.length() == 0 ? null : b.toString();
        }
        public void stop() { try { if (socket != null) socket.close(); } catch (Exception ignored) {} picture = null; }
        public android.graphics.Bitmap picture() { return picture; }
        public boolean hearing() { return System.currentTimeMillis() - heard < 600; }
        public void mute(boolean on) { if (on != muted) System.out.println("[ring-stream] " + (on ? "muted" : "sound on")); muted = on; }
        public boolean running() { return running; }
    }

    static final long LOW = 0x800000805F9B34FBL;
    static long uuid16(int u) { return ((long) u << 32) | 0x1000L; }

    public static void main(String[] a) throws Exception {
        final int port = Integer.parseInt(a[0]);
        int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]);
        final Path dir = Paths.get(a[3]);
        Files.createDirectories(dir);
        final App app = new App();
        App.set(app);
        app.w = w; app.h = h;
        Screen.Settings s = new Screen.Settings();
        s.node = "kitchen-fire"; s.friendly = "Kitchen Fire"; s.mac = "AA:BB:CC:DD:EE:02";
        app.screen = new Screen(s, app.shape(), null, null, null, new EspDevice.Logger() { public void log(String l) { System.out.println("[log] " + l); } });
        if (System.getenv("BATTERY") != null) app.screen.battery(87, "discharging", "none");
        if (System.getenv("VOICE") != null) {
            // A microphone that gives a tone in 20 ms parts, for the voice assistant's tests.
            Tessera.hasMicrophone = true;
            Assist assist = app.screen.enableVoice();
            assist.microphone = new Assist.Microphone() {
                volatile boolean on;
                public boolean start(final Assist.Sink sink) {
                    on = true;
                    new Thread(new Runnable() {
                        public void run() {
                            byte[] part = new byte[640];
                            while (on) {
                                sink.part(part.clone());
                                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                            }
                        }
                    }).start();
                    return true;
                }
                public void stop() { on = false; System.out.println("[mic] stopped"); }
            };
            assist.sounds = new Assist.Sounds() {
                public void wake() { System.out.println("[sound] wake"); }
                public void thinking() { System.out.println("[sound] thinking"); }
            };
            if (System.getenv("WAKE") != null) wake(app.screen, assist, System.getenv("WAKE"));
        }
        if (System.getenv("BLE") != null) fakeBluetooth(app.screen);
        if (System.getenv("CAMERA") != null) fakeCamera(app.screen);
        TileView.ringMedia = new DeskStream();
        final TileView[] view = new TileView[1];
        final app.tilescreen.ui.SaverView[] saverView = new app.tilescreen.ui.SaverView[1];
        app.screen.setUi(new Screen.Ui() {
            public void alert(Screen.Alert al) { view[0].showAlert(al); }
            public void dismissAlert() { view[0].dismissAlert(); }
            public void openSettings() { view[0].openSettingsPage(); }
            public void changed() { view[0].changed(); }
            public void ring(Tessera.Ring r) { System.out.println("[ui] ring " + r.name); view[0].ring(r); }
            public void closeRing(String key) { view[0].closeRing(key); }
            public void ringDoorState(String key, String state) { view[0].ringDoorState(key, state); }
            public void call(app.tilescreen.core.Tessera.Call c) { view[0].call(c); }
            public void contacts(java.util.List<app.tilescreen.core.Tessera.Contact> rows, boolean append, boolean more) { view[0].contacts(rows, append, more); }
            public void show(String what) { System.out.println("[ui] show " + what); app.screen.showing(what); }
        });
        app.screen.screenSettings.setUi(new ScreenSettings.Ui() {
            public void systemSleep() { System.out.println("[power] android screensaver"); }
            public void systemWake() { System.out.println("[power] android wake"); }
            public void power(boolean dimmed, int level, boolean saver, boolean android) { System.out.println("[power] dimmed=" + dimmed + " level=" + level + " saver=" + saver + " android=" + android); }
            public void goHome() { view[0].goHome(); }
            public void closeCards() { view[0].closeCards(); }
            public void looks() { view[0].changed(); }
        });
        app.screen.start(port);
        view[0] = new TileView(new android.content.Context(), new TileView.Host() {
            public void openAppSettings() { System.out.println("[ui] app settings opened"); }
            public void wake() { System.out.println("[ui] wake"); }
            public void openWidgets() { System.out.println("[ui] widget page opened"); }
        });
        view[0].layout(w, h);
        render(view[0], dir.resolve("00-waiting.png"), w, h);
        Path cmd = Paths.get("/tmp/ts-cmd");
        while (true) {
            Thread.sleep(50);
            if (!Files.exists(cmd)) continue;
            String line = new String(Files.readAllBytes(cmd), "UTF-8").trim();
            Files.delete(cmd);
            android.os.Handler.drain();
            String[] p = line.split(" ");
            try {
                if (p[0].equals("render")) {
                    render(view[0], dir.resolve(p[1] + ".png"), view[0].getWidth(), view[0].getHeight());
                } else if (p[0].equals("size")) {
                    view[0].layout(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    render(view[0], dir.resolve("_scratch.png"), view[0].getWidth(), view[0].getHeight());
                } else if (p[0].equals("tap") || p[0].equals("hold") || p[0].equals("drag")) {
                    float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]);
                    render(view[0], dir.resolve("_scratch.png"), view[0].getWidth(), view[0].getHeight()); // hit regions are built while drawing
                    // As MainActivity does: the standby rules hear the finger land and lift (ScreenSettings.touched).
                    boolean swallow = app.screen.screenSettings.dimmed();
                    app.screen.screenSettings.touched(true);
                    if (!swallow) view[0].onTouchEvent(new android.view.MotionEvent(0, x, y));
                    if (p[0].equals("hold") && !swallow) {
                        java.lang.reflect.Field f = TileView.class.getDeclaredField("longPress");
                        f.setAccessible(true);
                        ((Runnable) f.get(view[0])).run();
                    }
                    float ux = p[0].equals("drag") ? Float.parseFloat(p[3]) : x;
                    if (p[0].equals("drag")) view[0].onTouchEvent(new android.view.MotionEvent(2, ux, y));
                    if (!swallow) view[0].onTouchEvent(new android.view.MotionEvent(1, ux, y));
                    app.screen.screenSettings.touched(false);
                } else if (p[0].equals("down") || p[0].equals("move") || p[0].equals("up")) {
                    // A finger that stays: down, then moves, rendered in between, then up.
                    float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]);
                    if (p[0].equals("down")) render(view[0], dir.resolve("_scratch.png"), view[0].getWidth(), view[0].getHeight());
                    view[0].onTouchEvent(new android.view.MotionEvent(p[0].equals("down") ? 0 : p[0].equals("move") ? 2 : 1, x, y));
                } else if (p[0].equals("settings")) {
                    view[0].openSettingsPage();
                } else if (p[0].equals("saverpress")) {
                    // A finger on the screensaver: the key under it, let go after `held` ms.
                    int k = saverView[0].keyAt(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                    System.out.println("[ui] saver key " + k);
                    if (k >= 0) saverView[0].press(k, Long.parseLong(p[3]));
                } else if (p[0].equals("saver")) {
                    if (saverView[0] == null) {
                        saverView[0] = new app.tilescreen.ui.SaverView(new android.content.Context());
                        saverView[0].layout(view[0].getWidth(), view[0].getHeight());
                    }
                    app.tilescreen.ui.SaverView sv = saverView[0];
                    for (int i = 0; i < 2; i++) { renderAny(sv, dir.resolve("_scratch.png"), view[0].getWidth(), view[0].getHeight()); Thread.sleep(1500); }
                    renderAny(sv, dir.resolve(p[1] + ".png"), view[0].getWidth(), view[0].getHeight());
                } else if (p[0].equals("wait")) {
                    for (int i = 0; i < Integer.parseInt(p[1]); i++) { render(view[0], dir.resolve("_scratch.png"), view[0].getWidth(), view[0].getHeight()); Thread.sleep(500); }
                } else if (p[0].equals("draw-time")) {
                    long t0 = System.nanoTime();
                    for (int i = 0; i < 20; i++) render(view[0], dir.resolve("_scratch.png"), view[0].getWidth(), view[0].getHeight());
                    System.out.println("[ui] 20 frames " + (System.nanoTime() - t0) / 1000000 + " ms");
                }
                System.out.println("[ui] done " + line);
            } catch (Throwable t) {
                System.out.println("[ui] FAILED " + line);
                t.printStackTrace(System.out);
            }
        }
    }

    static void renderAny(android.view.View v, Path out, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        v.draw(new android.graphics.Canvas(g));
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

    static void render(TileView v, Path out, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        v.draw(new android.graphics.Canvas(g));
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }
}
