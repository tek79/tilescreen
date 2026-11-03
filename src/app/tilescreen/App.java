package app.tilescreen;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.tilescreen.core.EspDevice;
import app.tilescreen.core.Mdns;
import app.tilescreen.core.Screen;
import app.tilescreen.core.Tessera;

/** Holds the one running screen for the whole process, so the service and the activity share it. */
public final class App extends Application {
    public static final int API_PORT = 6053;
    private static final String TAG = "tilescreen";

    private static App self;
    private final ArrayDeque<String> lines = new ArrayDeque<String>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Screen screen;
    private Mdns mdns;
    private WifiManager.MulticastLock multicast;
    private WifiManager.WifiLock wifiLock;
    private volatile int width = 1024, height = 600;
    private volatile String ip = "";

    public static App get() { return self; }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        // Boards, once (0.12.0): the old list of every board becomes Automatic, the full-feature board of this tablet's
        // shape. A board picked from the new list after this is kept.
        if (!prefs.contains("board_scheme")) {
            String before = prefs.getString("board", "waveshare7b");
            prefs.edit().putString("board_choice", "auto").putInt("board_scheme", 2).apply();
            log("board: " + before + " is now Automatic, " + board());
        }
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            final Thread.UncaughtExceptionHandler up = Thread.getDefaultUncaughtExceptionHandler();
            public void uncaughtException(Thread t, Throwable e) {
                Log.e(TAG, "crash in " + t.getName(), e);
                if (up != null) up.uncaughtException(t, e);
            }
        });
    }

    // ----- settings -----

    public SharedPreferences prefs() { return prefs; }

    public String mac() {
        String kept = prefs.getString("mac", null);
        if (kept != null) return kept;
        String mac = hardwareMac();
        if (mac == null) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xFC) | 0x02); // locally administered, unicast
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 6; i++) s.append(i == 0 ? "" : ":").append(String.format(Locale.US, "%02X", b[i]));
            mac = s.toString();
        }
        prefs.edit().putString("mac", mac).apply();
        return mac;
    }

    private static String hardwareMac() {
        try {
            NetworkInterface ni = NetworkInterface.getByName("wlan0");
            byte[] hw = ni == null ? null : ni.getHardwareAddress();
            if (hw == null || hw.length != 6) return null;
            if (hw[0] == 0x02 && hw[1] == 0 && hw[2] == 0 && hw[3] == 0 && hw[4] == 0) return null;
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 6; i++) s.append(i == 0 ? "" : ":").append(String.format(Locale.US, "%02X", hw[i]));
            return s.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** The name given in this app's settings, else the tablet's hostname, else "Tablet" and the end of its MAC. */
    public String friendly() {
        String own = prefs.getString("friendly", null);
        // A name saved before 0.5.2 that was only the old automatic one gives way to the hostname.
        String tail = mac().replace(":", "");
        if (own != null && own.equals("Tablet " + tail.substring(tail.length() - 4)) && hostname() != null) own = null;
        return own != null && !own.trim().isEmpty() ? own : automaticName();
    }

    /** The name the screen carries when none is given in this app: the hostname, as Developer options sets it. */
    public String automaticName() {
        String host = hostname();
        if (host != null) return host;
        String tail = mac().replace(":", "");
        return "Tablet " + tail.substring(tail.length() - 4);
    }

    /**
     * The hostname set under Developer options → Device hostname (LineageOS keeps it in device_hostname and
     * net.hostname). Android's own default, android-<id>, counts as none.
     */
    String hostname() {
        String h = null;
        try {
            h = android.provider.Settings.Secure.getString(getContentResolver(), "device_hostname");
        } catch (Exception ignored) {
        }
        if (h == null || h.trim().isEmpty()) {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                h = (String) sp.getMethod("get", String.class).invoke(null, "net.hostname");
            } catch (Exception ignored) {
            }
        }
        if (h == null) return null;
        h = h.trim();
        if (h.isEmpty() || h.matches("(?i)android-[0-9a-f]+") || h.equalsIgnoreCase("localhost")) return null;
        return h;
    }

    public static String slug(String name) {
        String s = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) s = "tablet";
        if (s.length() > 31) s = s.substring(0, 31).replaceAll("-+$", "");
        return s;
    }

    public String node() { return slug(friendly()); }

    public int columns() { return Math.max(1, Math.min(8, prefs.getInt("columns", 4))); }

    public int rows() { return Math.max(1, Math.min(8, prefs.getInt("rows", 3))); }

    /**
     * The boards a tablet reports to Tessera: one with every feature of Tessera per shape of glass, so Tessera offers
     * everything (light presets, template tiles, the doorbell, plugins that need a speaker). {choice, label, board id}.
     */
    public static final String[][] BOARDS = {
            {"auto", "Automatic (recommended)", ""},
            {"1.71", "1024 x 600 (1.71)", "jc1060p470v2"},
            {"1.67", "800 x 480 (5:3)", "jc4880p443"},
            {"1.6", "1280 x 800 (16:10)", "jc8012p4a1"},
            {"1.78", "1280 x 720 (16:9)", "tab5"}};

    public String boardChoice() { return prefs.getString("board_choice", "auto"); }

    /** The board id reported to Tessera: the one picked, or for Automatic the one of this tablet's shape. */
    public String board() {
        String choice = boardChoice();
        for (String[] b : BOARDS) if (b[0].equals(choice) && !b[2].isEmpty()) return b[2];
        return autoBoard();
    }

    /**
     * The full-feature board nearest this tablet's shape (its long side over its short one); a tablet wider than 16:9,
     * such as the Echo Show 5 (2:1), takes the 1024 x 600 board.
     */
    public String autoBoard() {
        double r = glassRatio();
        if (r > 1.8) return "jc1060p470v2";
        String best = "jc1060p470v2";
        double gap = 9;
        for (String[] b : BOARDS) {
            if (b[2].isEmpty()) continue;
            double d = Math.abs(Double.parseDouble(b[0]) - r);
            if (d < gap) { gap = d; best = b[2]; }
        }
        return best;
    }

    /** The glass's long side over its short one, the whole display (getRealMetrics). */
    public double glassRatio() {
        try {
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
            int a = Math.max(m.widthPixels, m.heightPixels), b = Math.min(m.widthPixels, m.heightPixels);
            return b > 0 ? (double) a / b : 1.71;
        } catch (Exception e) {
            return 1.71;
        }
    }

    /** "auto", "landscape" or "portrait". */
    public String orientation() { return prefs.getString("orientation", "landscape"); }

    /** How many camera tiles stream live at once on this tablet (Tessera's caps.live_tiles); 2 unless chosen. */
    public int liveTiles() { return Math.max(0, Math.min(8, prefs.getInt("live_tiles", 2))); }

    /** Whether a swipe right from the first page opens the page of Android widgets. */
    public boolean widgetPage() { return prefs.getBoolean("widget_page", false); }

    /**
     * Where a screen starts before Tessera or Home Assistant changes anything: Tessera's defaults, except the dark
     * look and swiping between pages, which this app had from the start; and the brightness 0.3 kept, if any.
     */
    private Map<String, Integer> settingDefaults() {
        Map<String, Integer> d = new LinkedHashMap<String, Integer>();
        d.put("dark_mode", 1);
        d.put("swipe_pages", 1);
        if (prefs.contains("brightness")) d.put("brightness", prefs.getInt("brightness", 100));
        if (prefs.contains("auto_home")) {
            int seconds = prefs.getInt("auto_home", 120);
            d.put("auto_home", seconds > 0 ? 1 : 0);
            if (seconds > 0) d.put("auto_home_seconds", seconds);
        }
        return d;
    }

    // ----- the screen -----

    /** The view's size, which is what Tessera lays its grid on. */
    public void setCanvas(int w, int h) {
        if (w <= 0 || h <= 0 || (w == width && h == height)) return;
        width = w;
        height = h;
        Screen s = screen;
        if (s != null) s.regrid();
    }

    public Tessera.Shape shape() {
        return new Tessera.Shape() {
            public int width() { return width; }
            public int height() { return height; }
            public int columns() { return kept(height > width)[0]; }
            public int rows() { return kept(height > width)[1]; }
            public int[] kept(boolean upright) {
                return upright ? new int[]{portraitColumns(), portraitRows()} : new int[]{App.this.columns(), App.this.rows()};
            }
            public void choose(boolean upright, int columns, int rows) {
                SharedPreferences.Editor e = prefs.edit();
                e.putInt(upright ? "p_columns" : "columns", columns).putInt(upright ? "p_rows" : "rows", rows);
                // Tessera stood the screen up or laid it down: that way from now on.
                if (upright != (height > width)) e.putString("orientation", upright ? "portrait" : "landscape");
                e.commit();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        Runnable r = onTurn;
                        if (r != null) r.run();
                        Screen s = screen;
                        if (s != null) s.regrid();
                    }
                });
            }
        };
    }

    /** The activity's: the orientation setting changed (Tessera turned the screen). */
    public volatile Runnable onTurn;

    /** The grid standing up: Tessera's choice, else the landscape grid turned. */
    public int portraitColumns() { return Math.max(1, Math.min(8, prefs.getInt("p_columns", rows()))); }

    public int portraitRows() { return Math.max(1, Math.min(8, prefs.getInt("p_rows", columns()))); }

    public synchronized Screen screen() {
        if (screen == null) start();
        return screen;
    }

    public synchronized void restart() {
        stopScreen();
        start();
    }

    private synchronized void start() {
        Tessera.liveTiles = liveTiles();
        app.tilescreen.ui.TileView.plugins();
        Screen.Settings s = new Screen.Settings();
        s.node = node();
        s.friendly = friendly();
        s.board = board();
        s.mac = mac();
        s.model = Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")";
        s.manufacturer = Build.MANUFACTURER;
        s.language = Locale.getDefault().getLanguage();
        final File keep = new File(getFilesDir(), "tessera-layout.json");
        Tessera.Store store = new Tessera.Store() {
            public String load() { return read(keep); }
            public void save(String layout) { write(keep, layout); }
        };
        EspDevice.Logger log = new EspDevice.Logger() {
            public void log(String line) { App.this.log(line); }
        };
        final File kept = new File(getFilesDir(), "screen-settings.json");
        app.tilescreen.core.ScreenSettings.Store settingsStore = new app.tilescreen.core.ScreenSettings.Store() {
            public String load() { return read(kept); }
            public void save(String json) { write(kept, json); }
        };
        final Screen made = new Screen(s, shape(), store, settingsStore, settingDefaults(), log);
        made.device.setKey(key(), keyStore);
        screen = made;
        acquireLocks();
        final Screen.Settings settings = s;
        // Sockets stay off the main thread, which Android forbids them.
        new Thread(new Runnable() {
            public void run() {
                try {
                    made.start(API_PORT);
                } catch (IOException e) {
                    log("could not open port " + API_PORT + ": " + e);
                }
                synchronized (App.this) {
                    if (screen == made) startMdns(settings);
                }
            }
        }, "screen-start").start();
        refreshIp();
        main.postDelayed(ipCheck, 15000);
    }

    private Map<String, String> txt(Screen.Settings s) {
        Map<String, String> txt = new LinkedHashMap<String, String>();
        txt.put("friendly_name", s.friendly);
        txt.put("version", EspDevice.ESPHOME_VERSION);
        txt.put("mac", s.mac.replace(":", "").toLowerCase(Locale.US));
        txt.put("platform", "android");
        txt.put("board", s.board);
        txt.put("network", "wifi");
        // As go-esphome-device advertises it: provisioned, or ready for Home Assistant to push a key.
        if (key() != null) {
            txt.put("api_encryption", "Noise_NNpsk0_25519_ChaChaPoly_SHA256");
        } else {
            txt.put("api_encryption_supported", "Noise_NNpsk0_25519_ChaChaPoly_SHA256");
            txt.put("api_provisioning", "zero-psk");
        }
        return txt;
    }

    /** The encryption key Home Assistant set, or null. */
    public byte[] key() {
        String k = prefs.getString("api_key", null);
        byte[] b = k == null ? null : app.tilescreen.core.Crypto.unbase64(k);
        return b != null && b.length == 32 ? b : null;
    }

    public String keyText() { return prefs.getString("api_key", null); }

    private void saveKey(byte[] k) {
        if (k == null) prefs.edit().remove("api_key").commit();
        else prefs.edit().putString("api_key", app.tilescreen.core.Crypto.base64(k)).commit();
        Mdns m = mdns;
        Screen sc = screen;
        if (m != null && sc != null) m.setTxt(txt(sc.settings()));
    }

    /** Forgets the key, so a fresh Home Assistant entry can adopt the screen again. */
    public void forgetKey() {
        saveKey(null);
        Screen sc = screen;
        if (sc != null) sc.device.setKey(null, keyStore);
    }

    private final EspDevice.KeyStore keyStore = new EspDevice.KeyStore() {
        public void save(byte[] k) { saveKey(k); }
    };

    private void startMdns(Screen.Settings s) {
        Map<String, String> txt = txt(s);
        try {
            mdns = new Mdns(s.node, API_PORT, txt, new Mdns.Address() {
                public Inet4Address get() { return address(); }
            }, new EspDevice.Logger() {
                public void log(String line) { App.this.log(line); }
            });
            mdns.start();
        } catch (IOException e) {
            mdns = null;
            log("mDNS could not start (" + e + "). Add the screen in Home Assistant by its address instead.");
        }
    }

    private synchronized void stopScreen() {
        main.removeCallbacks(ipCheck);
        if (mdns != null) mdns.stop();
        mdns = null;
        if (screen != null) screen.stop();
        screen = null;
    }

    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (multicast == null) {
                multicast = wm.createMulticastLock("tilescreen-mdns");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tilescreen");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            log("wifi locks: " + e);
        }
    }

    private final Runnable ipCheck = new Runnable() {
        public void run() {
            refreshIp();
            main.postDelayed(this, 15000);
        }
    };

    private void refreshIp() {
        Inet4Address a = address();
        String now = a == null ? "" : a.getHostAddress();
        if (!now.equals(ip)) {
            ip = now;
            log("address " + (now.isEmpty() ? "none" : now));
            Mdns m = mdns;
            if (m != null) m.announce();
        }
        Screen s = screen;
        if (s != null) s.setIp(now);
    }

    public String ip() { return ip; }

    /** The Wi-Fi network the tablet holds and its signal, for the settings page; "" without one. */
    public String wifiText() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return "Not connected";
            String ssid = info.getSSID();
            if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length() - 1);
            int rssi = info.getRssi();
            String quality = rssi >= -55 ? "excellent" : rssi >= -67 ? "good" : rssi >= -75 ? "fair" : "weak";
            return (ssid == null || ssid.contains("unknown") ? "Wi-Fi" : ssid) + " · " + quality + " (" + rssi + " dBm)";
        } catch (Exception e) {
            return "";
        }
    }

    /** This app's own version, as Android lists it. */
    public String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static Inet4Address address() {
        try {
            Inet4Address fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (ni.getName().startsWith("wlan")) return (Inet4Address) a;
                    if (fallback == null) fallback = (Inet4Address) a;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    // ----- logging -----

    public void log(String line) {
        Log.i(TAG, line);
        synchronized (lines) {
            lines.addLast(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()) + "  " + line);
            while (lines.size() > 150) lines.removeFirst();
        }
    }

    public List<String> logLines() {
        synchronized (lines) {
            return new ArrayList<String>(lines);
        }
    }

    static String read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            in.close();
            return new String(b, 0, n, "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    static void write(File f, String text) {
        try {
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(text.getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
        } catch (IOException e) {
            Log.w(TAG, "keeping the layout failed", e);
        }
    }

    // ----- Tap sound's two sounds (assets tap.wav and step.wav, from the plugin), on the system sound stream -----

    private android.media.SoundPool sounds;
    private int tapSound, stepSound;

    /** The tick of a tap, or the softer one of a step (`soft`). */
    public synchronized void tick(boolean soft) {
        try {
            if (sounds == null) {
                sounds = new android.media.SoundPool(4, android.media.AudioManager.STREAM_SYSTEM, 0);
                tapSound = sounds.load(getAssets().openFd("tap.wav"), 1);
                stepSound = sounds.load(getAssets().openFd("step.wav"), 1);
            }
            sounds.play(soft ? stepSound : tapSound, 1f, 1f, 1, 0, 1f);
        } catch (Exception e) {
            log("tap sound: " + e);
        }
    }
}
