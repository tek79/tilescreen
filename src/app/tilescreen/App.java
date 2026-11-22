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
    private Player player;
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
        // Boards (0.12.0): the old list of every board gave way to one full-feature board per shape. Automatic (0.12.0 to
        // 0.13.0) is now found once and kept (boardChoice), so a tablet on it keeps the board it had; a shape picked is kept.
        if (prefs.getInt("board_scheme", 0) < 3) {
            SharedPreferences.Editor e = prefs.edit();
            if (!prefs.contains("board_scheme") || "auto".equals(prefs.getString("board_choice", ""))) e.remove("board_choice");
            e.putInt("board_scheme", 3).commit();
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
            {"1.0", "Square, 720 x 720 (1:1)", "wavesharep4"},
            {"1.6", "1280 x 800 (16:10)", "jc8012p4a1"},
            {"1.67", "800 x 480 (5:3)", "jc4880p443"},
            {"1.71", "1024 x 600 (1.71)", "jc1060p470v2"},
            {"1.78", "1280 x 720 (16:9)", "tab5"}};

    /**
     * The shape picked (a BOARDS choice): found from the glass once, on the first start, then kept, so the board Tessera
     * knows never changes under a layout by itself; the settings change it.
     */
    public String boardChoice() {
        String c = prefs.getString("board_choice", "");
        for (String[] b : BOARDS) if (b[0].equals(c)) return c;
        c = detectedChoice();
        SharedPreferences.Editor e = prefs.edit().putString("board_choice", c);
        // A square glass starts on the grid of Tessera's square boards.
        if (c.equals("1.0") && !prefs.contains("columns")) e.putInt("columns", 2).putInt("rows", 3);
        e.commit();
        log("board: this tablet's shape is " + c + ", " + boardOf(c));
        return c;
    }

    /** The board id reported to Tessera: the one of the shape picked. */
    public String board() { return boardOf(boardChoice()); }

    static String boardOf(String choice) {
        for (String[] b : BOARDS) if (b[0].equals(choice)) return b[2];
        return "jc1060p470v2";
    }

    /**
     * The shape nearest this tablet's glass (its long side over its short one); a tablet wider than 16:9, such as the
     * Echo Show 5 (2:1), takes the 1024 x 600 board.
     */
    public String detectedChoice() {
        double r = glassRatio();
        if (r > 1.8) return "1.71";
        String best = "1.71";
        double gap = 9;
        for (String[] b : BOARDS) {
            double d = Math.abs(Double.parseDouble(b[0]) - r);
            if (d < gap) { gap = d; best = b[0]; }
        }
        return best;
    }

    /** Whether the whole display stands now (taller than wide by more than a tenth); a square one never does. */
    public boolean glassUpright() {
        try {
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
            return m.heightPixels > m.widthPixels * 1.1;
        } catch (Exception e) {
            return height > width;
        }
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
            public boolean upright() { return glassUpright(); }
            public boolean square() { return glassRatio() < 1.1; }
            public int columns() { return kept(upright())[0]; }
            public int rows() { return kept(upright())[1]; }
            public int[] kept(boolean upright) {
                // A square glass keeps one grid, the lying one, whichever way it hangs.
                return upright && !square() ? new int[]{portraitColumns(), portraitRows()} : new int[]{App.this.columns(), App.this.rows()};
            }
            public void choose(boolean upright, int columns, int rows) {
                if (square()) upright = false;
                SharedPreferences.Editor e = prefs.edit();
                e.putInt(upright ? "p_columns" : "columns", columns).putInt(upright ? "p_rows" : "rows", rows);
                // Tessera stood the screen up or laid it down: that way from now on.
                if (upright != glassUpright()) e.putString("orientation", upright ? "portrait" : "landscape");
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

    private volatile Bluetooth bluetooth;
    private Runnable bluetoothStatus;

    private void startBluetooth(final Screen made) {
        boolean usable;
        try {
            usable = Bluetooth.usable(this);
        } catch (Throwable t) {
            usable = false;
        }
        app.tilescreen.core.Tessera.hasBluetooth = usable;
        if (!usable) {
            log("bluetooth: this tablet has no Bluetooth LE for the proxy");
            return;
        }
        final app.tilescreen.core.ScreenSettings settings = made.screenSettings;
        settings.bluetoothToggles(made.device);
        final app.tilescreen.core.BleProxy proxy = new app.tilescreen.core.BleProxy(new app.tilescreen.core.EspDevice.Logger() {
            public void log(String line) { App.this.log(line); }
        });
        // One radio for the app's life (it listens to Android's Bluetooth broadcasts); a restarted screen takes it over.
        final Bluetooth radio = bluetooth != null ? bluetooth : new Bluetooth(this);
        bluetooth = radio;
        radio.stopScan();
        radio.setProxy(proxy);
        proxy.setRadio(radio);
        made.device.bluetooth = proxy;
        made.device.bluetoothMac = radio.mac();
        radio.mode(settings.bluetoothScan());
        settings.onBluetoothScan = new Runnable() {
            public void run() { radio.mode(settings.bluetoothScan()); }
        };
        applyBluetooth(made, proxy, false);
        settings.onBluetooth = new Runnable() {
            public void run() { applyBluetooth(made, proxy, true); }
        };
        // Its status, in Tessera's words, every few seconds.
        if (bluetoothStatus != null) main.removeCallbacks(bluetoothStatus);
        bluetoothStatus = new Runnable() {
            public void run() {
                if (screen != made) return;
                String text;
                if (!settings.bluetoothProxy()) text = "Off";
                else if (proxy.state() == app.tilescreen.core.BleProxy.RUNNING) text = "Listening";
                else if (!radio.problem().isEmpty()) text = radio.problem();
                else if (proxy.state() == app.tilescreen.core.BleProxy.STARTING) text = "Starting";
                else text = "Waiting for Home Assistant";
                settings.bluetoothStatus(text);
                main.postDelayed(this, 5000);
            }
        };
        main.post(bluetoothStatus);
    }

    /** The switch: the features Home Assistant reads, the proxy on or off, and (when it changed) a reconnect to read them. */
    private void applyBluetooth(Screen made, app.tilescreen.core.BleProxy proxy, boolean changed) {
        boolean on = made.screenSettings.bluetoothProxy();
        made.device.bluetoothFeatures = on ? app.tilescreen.core.BleProxy.FEATURES : 0;
        proxy.enable(on);
        if (changed) {
            log("bluetooth: the proxy is " + (on ? "on" : "off") + "; Home Assistant connects again to see it");
            made.device.reconnectClients();
        }
    }

    private volatile WakeListener wake;
    private volatile boolean visible = true;

    /** A call or the doorbell's Talk takes the microphone from the wake word, and gives it back. */
    public void takeMicrophone(boolean on) {
        WakeListener w = wake;
        if (w != null) w.take(on);
    }

    /** The app is on the glass, or another app (or Android's screensaver) is in front: the wake word rests then. */
    public void visible(boolean on) {
        visible = on;
        WakeListener w = wake;
        if (w != null) w.visible(on);
    }

    /** A call or the doorbell's voice takes the speaker from the music, and gives it back (Player.hold). */
    public void holdSpeaker(boolean on) {
        Player p = player;
        if (p != null) p.hold(on);
    }

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
        // The tablet's speaker for Home Assistant: plays what it is given (Player).
        player = new Player(this, made.mediaPlayer, prefs);
        // Its microphone, for the doorbell's Talk and calls (Mic): only where the tablet has one it may record with.
        boolean mic = getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
                && checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && Mic.usable();
        app.tilescreen.core.Tessera.hasMicrophone = mic;
        app.tilescreen.core.Tessera.echoCancel = mic && Mic.echoCanceller();
        app.tilescreen.core.Voice.uplink = mic ? new Mic() : null;
        // Home Assistant's Assist, push to talk, where there is a microphone (Tessera's voice_assist plugin).
        if (mic) {
            app.tilescreen.core.Assist assist = made.enableVoice();
            assist.microphone = new AssistMicrophone();
            assist.sounds = new app.tilescreen.core.Assist.Sounds() {
                public void wake() { assistSound(true); }
                public void thinking() { assistSound(false); }
            };
            loadAssistSounds();
            // The wake word on the tablet (Wake word, Wake phrase): listens while it is on.
            final WakeListener w = new WakeListener(this, made.device, made.screenSettings, assist);
            w.visible(visible);
            wake = w;
            made.onWakeChanged = new Runnable() {
                public void run() { w.update(); }
            };
            w.update();
        }
        // The Bluetooth proxy (Tessera's bt_proxy), on a tablet with Bluetooth LE: off until its switch is on.
        startBluetooth(made);
        log("sound: speaker" + (mic ? ", microphone" + (app.tilescreen.core.Tessera.echoCancel ? " with echo cancelling" : "") : ", no microphone"));
        // The battery's sensors before Home Assistant lists the entities.
        battery(registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)));
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
        WakeListener w = wake;
        wake = null;
        if (w != null) w.close();
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

    // ----- export and import of the tablet's settings -----

    /**
     * Everything this tablet keeps of its own, as one JSON text: the app's settings (name, grid, shape, live tiles, the
     * widget page, the encryption key and MAC, and the rest of its preferences) and the screen's Tessera settings
     * (screen-settings.json: brightness, standby, night, the assistant, the Bluetooth proxy). Not the layout: Tessera
     * sends that again by itself.
     */
    String exportSettings() {
        java.util.Map<String, Object> prefsOut = new java.util.TreeMap<String, Object>();
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Object v = e.getValue();
            java.util.Map<String, Object> one = new java.util.LinkedHashMap<String, Object>();
            if (v instanceof Boolean) { one.put("type", "bool"); one.put("value", v); }
            else if (v instanceof Integer) { one.put("type", "int"); one.put("value", ((Integer) v).doubleValue()); }
            else if (v instanceof Long) { one.put("type", "long"); one.put("value", String.valueOf(v)); }
            else if (v instanceof Float) { one.put("type", "float"); one.put("value", ((Float) v).doubleValue()); }
            else if (v instanceof String) { one.put("type", "string"); one.put("value", v); }
            else continue; // string sets: none of the app's
            prefsOut.put(e.getKey(), one);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        out.put("app", "tilescreen");
        out.put("version", version());
        out.put("node", node());
        out.put("exported", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date()));
        out.put("note", "Holds this tablet's encryption key: keep it as you would a password.");
        out.put("preferences", prefsOut);
        Object screenSettings = app.tilescreen.core.Json.tryParse(read(new File(getFilesDir(), "screen-settings.json")));
        if (screenSettings != null) out.put("screen_settings", screenSettings);
        return app.tilescreen.core.Json.write(out);
    }

    /** What an export holds, for the question before an import: its node and date; null when it isn't one. */
    static String describeExport(String text) {
        java.util.Map<String, Object> m = app.tilescreen.core.Json.obj(app.tilescreen.core.Json.tryParse(text));
        if (m == null || !"tilescreen".equals(app.tilescreen.core.Json.str(m, "app"))) return null;
        return app.tilescreen.core.Json.str(m, "node") + ", exported " + app.tilescreen.core.Json.str(m, "exported")
                + " from Tile Screen " + app.tilescreen.core.Json.str(m, "version");
    }

    /** Puts an export back: every preference as it was, the screen's settings, then the screen starts again with them. */
    boolean importSettings(String text) {
        java.util.Map<String, Object> m = app.tilescreen.core.Json.obj(app.tilescreen.core.Json.tryParse(text));
        if (m == null || !"tilescreen".equals(app.tilescreen.core.Json.str(m, "app"))) return false;
        java.util.Map<String, Object> in = app.tilescreen.core.Json.obj(m.get("preferences"));
        if (in == null) return false;
        SharedPreferences.Editor e = prefs.edit().clear();
        for (java.util.Map.Entry<String, Object> entry : in.entrySet()) {
            java.util.Map<String, Object> one = app.tilescreen.core.Json.obj(entry.getValue());
            if (one == null) continue;
            String type = app.tilescreen.core.Json.str(one, "type");
            Object v = one.get("value");
            try {
                if ("bool".equals(type)) e.putBoolean(entry.getKey(), Boolean.TRUE.equals(v));
                else if ("int".equals(type)) e.putInt(entry.getKey(), ((Number) v).intValue());
                else if ("long".equals(type)) e.putLong(entry.getKey(), Long.parseLong(String.valueOf(v)));
                else if ("float".equals(type)) e.putFloat(entry.getKey(), ((Number) v).floatValue());
                else if ("string".equals(type)) e.putString(entry.getKey(), String.valueOf(v));
            } catch (Exception bad) {
                log("import: left out " + entry.getKey() + " (" + bad + ")");
            }
        }
        e.commit();
        Object screenSettings = m.get("screen_settings");
        if (screenSettings != null) write(new File(getFilesDir(), "screen-settings.json"), app.tilescreen.core.Json.write(screenSettings));
        log("import: settings of " + app.tilescreen.core.Json.str(m, "node") + " put back; the screen starts again");
        restart();
        return true;
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

    // ----- the battery (Android's ACTION_BATTERY_CHANGED), for its sensors and the hello -----

    /** Reports a battery intent to the screen: level, state and charger, as the Home Assistant app names them. */
    public void battery(android.content.Intent i) {
        Screen s = screen;
        if (i == null || s == null) return;
        if (!i.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, true)) return;
        int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
        String state = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                : status == android.os.BatteryManager.BATTERY_STATUS_FULL ? "full"
                : status == android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING ? "not_charging"
                : status == android.os.BatteryManager.BATTERY_STATUS_DISCHARGING ? "discharging" : "unknown";
        int plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
        String charger = plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC ? "ac"
                : plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB ? "usb"
                : plugged == android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless" : "none";
        Tessera.hasBattery = true;
        Tessera.batteryLevel = Math.round(level * 100f / scale);
        s.battery(Math.round(level * 100f / scale), state, charger);
    }

    // ----- the voice assistant's microphone and sounds (Assist) -----

    /**
     * The microphone for a question: the wake word's recording while it listens (one recording for both, as on a
     * Tessera screen), else the input Mic finds; 20 ms parts at 16 kHz, on a thread of its own.
     */
    private final class AssistMicrophone implements app.tilescreen.core.Assist.Microphone {
        private volatile boolean on;
        private Thread worker;
        private boolean shared;

        public synchronized boolean start(final app.tilescreen.core.Assist.Sink sink) {
            stop();
            WakeListener w = wake;
            if (w != null && w.attach(sink)) {
                shared = true;
                holdSpeaker(true);
                return true;
            }
            on = true;
            final Mic.Input[] opened = new Mic.Input[1];
            final Object ready = new Object();
            final boolean[] tried = {false};
            worker = new Thread(new Runnable() {
                public void run() {
                    Mic.Input in = Mic.open();
                    synchronized (ready) {
                        opened[0] = in;
                        tried[0] = true;
                        ready.notifyAll();
                    }
                    if (in == null) return;
                    holdSpeaker(true);
                    try {
                        byte[] part = new byte[Mic.PART];
                        while (on && in.read(part)) sink.part(part.clone());
                    } finally {
                        in.close();
                        holdSpeaker(false);
                    }
                }
            }, "assist-mic");
            worker.start();
            synchronized (ready) {
                long until = System.currentTimeMillis() + 12000;
                while (!tried[0] && System.currentTimeMillis() < until) {
                    try { ready.wait(200); } catch (InterruptedException e) { break; }
                }
            }
            return opened[0] != null;
        }

        public synchronized void stop() {
            on = false;
            worker = null;
            if (shared) {
                shared = false;
                WakeListener w = wake;
                if (w != null) w.detach();
                holdSpeaker(false);
            }
        }
    }

    private android.media.SoundPool assistSounds;
    private int wakeSound, thinkingSound;
    /** Which of the two have loaded; a sound asked for before it loaded plays once it has. */
    private final java.util.Set<Integer> loadedSounds = new java.util.HashSet<Integer>();
    private int pendingSound;

    /**
     * Loads the two sounds ahead (SoundPool loads in the background, and a sound played before it loaded is silently
     * dropped: the first wake sound after a start was never heard).
     */
    synchronized void loadAssistSounds() {
        if (assistSounds != null) return;
        try {
            assistSounds = new android.media.SoundPool(2, android.media.AudioManager.STREAM_MUSIC, 0);
            assistSounds.setOnLoadCompleteListener(new android.media.SoundPool.OnLoadCompleteListener() {
                public void onLoadComplete(android.media.SoundPool pool, int id, int status) {
                    synchronized (App.this) {
                        if (status != 0) {
                            log("assist: a sound didn't load (" + status + ")");
                            return;
                        }
                        loadedSounds.add(id);
                        if (pendingSound == id) {
                            pendingSound = 0;
                            pool.play(id, 0.8f, 0.8f, 1, 0, 1f);
                        }
                    }
                }
            });
            wakeSound = assistSounds.load(getAssets().openFd("assist_wake.ogg"), 1);
            thinkingSound = assistSounds.load(getAssets().openFd("assist_thinking.ogg"), 1);
        } catch (Exception e) {
            log("assist: sound: " + e);
        }
    }

    /** The voice assistant's wake sound, or its thinking sound, on the media stream (the answer's volume). */
    synchronized void assistSound(boolean wake) {
        try {
            loadAssistSounds();
            if (assistSounds == null) return;
            int id = wake ? wakeSound : thinkingSound;
            if (!loadedSounds.contains(id)) {
                pendingSound = id;
                return;
            }
            if (assistSounds.play(id, 0.8f, 0.8f, 1, 0, 1f) == 0) log("assist: Android didn't play the " + (wake ? "wake" : "thinking") + " sound");
        } catch (Exception e) {
            log("assist: sound: " + e);
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
