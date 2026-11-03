import app.tilescreen.core.*;
import java.io.*;
import java.nio.file.*;

/** Runs the screen core on a desktop JVM for protocol tests. */
public class DesktopMain {
    public static void main(String[] a) throws Exception {
        final Path keep = Paths.get(a.length > 1 ? a[1] : "/tmp/tilescreen-layout.json");
        Screen.Settings s = new Screen.Settings();
        s.node = "fire-test"; s.friendly = "Fire Test"; s.mac = "AA:BB:CC:DD:EE:01";
        Tessera.Shape shape = new Tessera.Shape() {
            public int width() { return 1024; } public int height() { return 552; }
            int c = 4, r = 3;
            public int columns() { return c; } public int rows() { return r; }
            public int[] kept(boolean upright) { return upright ? new int[]{3, 4} : new int[]{c, r}; }
            public void choose(boolean upright, int cc, int rr) { System.out.println("[app] grid " + cc + "x" + rr + (upright ? " standing" : " lying")); if (!upright) { c = cc; r = rr; } }
        };
        Tessera.Store store = new Tessera.Store() {
            public String load() { try { return new String(Files.readAllBytes(keep), "UTF-8"); } catch (IOException e) { return null; } }
            public void save(String l) { try { Files.write(keep, l.getBytes("UTF-8")); System.out.println("[kept layout " + l.length() + " bytes]"); } catch (IOException e) { throw new RuntimeException(e); } }
        };
        final Path settingsFile = Paths.get(a.length > 3 ? a[3] : "/tmp/ts-settings.json");
        ScreenSettings.Store settingsStore = new ScreenSettings.Store() {
            public String load() { try { return new String(Files.readAllBytes(settingsFile), "UTF-8"); } catch (IOException e) { return null; } }
            public void save(String j) { try { Files.write(settingsFile, j.getBytes("UTF-8")); } catch (IOException e) { throw new RuntimeException(e); } }
        };
        java.util.Map<String, Integer> defaults = new java.util.LinkedHashMap<String, Integer>();
        defaults.put("dark_mode", 1); defaults.put("swipe_pages", 1);
        final Screen screen = new Screen(s, shape, store, settingsStore, defaults, new EspDevice.Logger() { public void log(String l) { System.out.println("[log] " + l); } });
        screen.setUi(new Screen.Ui() {
            public void show(String what) { System.out.println("[ui] show " + what); screen.showing(what); }
            public void alert(Screen.Alert al) { System.out.println("[ui] alert " + al.title + " / " + al.button); screen.alertEnded(al, "ok"); }
            public void dismissAlert() { System.out.println("[ui] dismiss"); }
            public void openSettings() { System.out.println("[ui] open settings"); }
            public void changed() {}
        });
        final Path keyFile = Paths.get("/tmp/ts-key");
        byte[] k = null;
        try { k = Crypto.unbase64(new String(Files.readAllBytes(keyFile), "UTF-8")); } catch (IOException e) {}
        screen.device.setKey(k, new EspDevice.KeyStore() {
            public void save(byte[] key) {
                try { if (key == null) Files.deleteIfExists(keyFile); else Files.write(keyFile, Crypto.base64(key).getBytes("UTF-8")); }
                catch (IOException e) { throw new RuntimeException(e); }
            }
        });
        screen.screenSettings.setUi(new ScreenSettings.Ui() {
            public void systemSleep() { System.out.println("[power] android screensaver"); }
            public void systemWake() { System.out.println("[power] android wake"); }
            public void power(boolean dimmed, int level, boolean saver, boolean android) { System.out.println("[power] dimmed=" + dimmed + " level=" + level + " saver=" + saver + " android=" + android); }
            public void goHome() { System.out.println("[power] go home"); }
            public void closeCards() { System.out.println("[power] close cards"); }
            public void looks() { System.out.println("[power] looks changed"); }
        });
        screen.start(Integer.parseInt(a[0]));
        screen.nextAlarm(1761649200000L, "com.android.deskclock");
        java.util.Map<String,String> txt = new java.util.LinkedHashMap<String,String>();
        txt.put("friendly_name", "Fire Test"); txt.put("version", EspDevice.ESPHOME_VERSION); txt.put("mac", "aabbccddee01");
        txt.put("platform", "android"); txt.put("board", "waveshare7b"); txt.put("network", "wifi");
        final java.net.Inet4Address me = (java.net.Inet4Address) java.net.InetAddress.getByName(a.length > 2 ? a[2] : "127.0.0.1");
        new Mdns("fire-test", Integer.parseInt(a[0]), txt, new Mdns.Address() { public java.net.Inet4Address get() { return me; } },
            new EspDevice.Logger() { public void log(String l) { System.out.println("[mdns] " + l); } }).start();
        // A tap from the UI, to check actions reach HA: triggered by a file appearing.
        while (true) {
            Thread.sleep(200);
            if (Files.exists(Paths.get("/tmp/tilescreen-noalarm"))) {
                Files.delete(Paths.get("/tmp/tilescreen-noalarm"));
                screen.nextAlarm(0, null);
            }
            if (Files.exists(Paths.get("/tmp/tilescreen-tap"))) {
                Files.delete(Paths.get("/tmp/tilescreen-tap"));
                java.util.Map<String,String> d = new java.util.LinkedHashMap<String,String>();
                d.put("entity_id", "light.desk");
                System.out.println("[ui] tap -> sent=" + screen.perform("light.toggle", d, null));
                Tessera.Layout l = screen.tessera.layout();
                System.out.println("[ui] layout pages=" + l.pages.size() + " tiles=" + l.tiles.size() + " shown=" + screen.tessera.shown());
                for (Tessera.Tile t : l.tiles) System.out.println("[ui]   tile " + t.entity + " slot=" + t.slot + " size=" + t.size + " state=" + t.state + " icon=" + t.icon + " name=" + t.name);
            }
        }
    }
}
