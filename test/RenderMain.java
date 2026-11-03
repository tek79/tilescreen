import app.tilescreen.App;
import app.tilescreen.core.*;
import app.tilescreen.ui.TileView;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

/** Runs the real TileView against the real core, rendering frames and replaying touches on command. */
public class RenderMain {
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
                    view[0].onTouchEvent(new android.view.MotionEvent(0, x, y));
                    if (p[0].equals("hold")) {
                        java.lang.reflect.Field f = TileView.class.getDeclaredField("longPress");
                        f.setAccessible(true);
                        ((Runnable) f.get(view[0])).run();
                    }
                    float ux = p[0].equals("drag") ? Float.parseFloat(p[3]) : x;
                    if (p[0].equals("drag")) view[0].onTouchEvent(new android.view.MotionEvent(2, ux, y));
                    view[0].onTouchEvent(new android.view.MotionEvent(1, ux, y));
                } else if (p[0].equals("down") || p[0].equals("move") || p[0].equals("up")) {
                    // A finger that stays: down, then moves, rendered in between, then up.
                    float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]);
                    if (p[0].equals("down")) render(view[0], dir.resolve("_scratch.png"), view[0].getWidth(), view[0].getHeight());
                    view[0].onTouchEvent(new android.view.MotionEvent(p[0].equals("down") ? 0 : p[0].equals("move") ? 2 : 1, x, y));
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
