package app.tilescreen.core;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Tessera screen's own settings (docs/SETTINGS.md, firmware 0.2.49+): the screen keeps them and offers each as an
 * entity of its ESPHome device, which Tessera's Screen settings panel and Home Assistant both change. Ranges and
 * clamping follow settings_screen::set; standby, Sleep, Wake, night and Back to page 1 follow packages/core.yaml.
 */
public final class ScreenSettings {

    public interface Store {
        String load();
        void save(String json);
    }

    /** What the screen shows and how bright, decided here and drawn by the app. */
    public interface Ui {
        /**
         * Dimmed or lit, the backlight level, whether the screensaver stands over the tiles, and whether Android's own
         * brightness goes instead of `level` (Android brightness: awake, and in standby on the charger).
         */
        void power(boolean dimmed, int level, boolean saver, boolean android);
        /** The settings page and every card close, and the home page shows. */
        void goHome();
        /** The settings page and every card close. */
        void closeCards();
        /** A setting that changes how things are drawn: dark mode, page buttons, the home button, rotation. */
        void looks();
        /** With Use Android screensaver: Sleep hands the screen to Android's screensaver (its daydream). */
        void systemSleep();
        /** With Use Android screensaver: Wake or an alert turns the glass on and ends Android's screensaver. */
        void systemWake();
    }

    // The values, with Tessera's defaults.
    private int brightness = 100, standbyEnabled = 1, standbySeconds = 600, standbyBrightness = 20, nightEnabled = 1,
            nightStart = 1320, nightEnd = 420, nightBrightness = 10, homeOnStandby = 0, swipePages = 0, rotation = 0,
            autoHome = 1, autoHomeSeconds = 120, darkMode = 0, pageButtons = 1, homeButton = 1, loopPages = 1,
            callRingtone = 1, callSwitching = 1, doNotDisturb = 0,
            assistWakeSound = 1, assistThinkingSound = 1, assistShowPopup = 1;
    /**
     * The wake word (voice_assist's Wake word and Wake phrase). Tessera's is on by default; here it starts off, so an
     * update never leaves a wall of tablets listening that nobody switched on.
     */
    private int assistWakeWord = 0, assistWakePhrase = 0;
    /** Tessera's bt_proxy switch (on by default there); off here until switched on, as the wake word. */
    private int bluetoothProxy = 0;
    /** The app's own: how much of the time the proxy listens (Low power, Balanced, Fast: Android's scan modes). */
    private int bluetoothScan = 1;
    public static final String[] SCAN_MODES = {"Low power", "Balanced", "Fast"};
    // This app's own, not Tessera's: Android's screen timeout and screensaver instead of Tessera's standby.
    private int androidSaver = 0;
    /** The app's own too: a tile's icon toggles it and a tap anywhere else opens its card. */
    private int iconToggles = 0;
    /** The app's own too: Android's brightness (auto, if the tablet has a light sensor) while awake; standby still dims. */
    private int systemBrightness = 0;
    /** The app's own too: picking the tablet up (its accelerometer) wakes it from standby. */
    private int liftWake = 0;
    /** The app's own too: standby on battery is a dark screen, whatever the brightness settings (a portable tablet). */
    private int batteryDark = 0;
    /** Plugins' settings (plugin.yaml's entities, docs/PLUGINS.md "A plugin's settings"): Tap sound, Waste collection. */
    private int tapSound = 1, wasteInBar = 1, wasteDaysAhead = 1;
    /** Whether the tablet is on its charger now (ScreenService reads Android's battery). */
    private boolean charging = true;

    private final Store store;
    private final EspDevice.Logger log;
    private volatile Ui ui;
    /** Sleep also closes an alert that is up. */
    public volatile Runnable onSleep;

    private final EspDevice.Number eBrightness, eStandbyBrightness, eNightBrightness, eStandbySeconds, eAutoHomeSeconds;
    private final EspDevice.Switch eStandby, eNight, eDark, eAutoHome, eHomeOnStandby, eSwipe, ePageButtons, eHomeButton, eLoopPages, eCallRingtone, eCallSwitching, eDoNotDisturb, eAndroidSaver, eIconToggles, eSystemBrightness, eLiftWake, eBatteryDark, eTapSound, eWasteInBar;
    private final EspDevice.Number eWasteDays;
    private final EspDevice.Time eNightStart, eNightEnd;
    private final EspDevice.Select eRotation;

    // Standby (core.yaml): last_touch_ms counts the standby time, last_use_ms Back to page 1.
    private long lastTouch = System.currentTimeMillis(), lastUse = System.currentTimeMillis();
    private boolean dimmed, sleepRequested, alertActive, ringActive, callActive, touchDown;

    public ScreenSettings(EspDevice device, Store store, EspDevice.Logger log, Map<String, Integer> defaults) {
        this.store = store;
        this.log = log;
        if (defaults != null) for (Map.Entry<String, Integer> d : defaults.entrySet()) put(d.getKey(), d.getValue());
        load();

        eBrightness = number(device, "normal_brightness", "Normal brightness", 5, 100, 1, "%", null, "brightness", 0);
        eStandbyBrightness = number(device, "standby_brightness", "Standby brightness", 0, 100, 1, "%", null, "standby_brightness", 0);
        eNightBrightness = number(device, "night_brightness", "Night brightness", 0, 100, 1, "%", null, "night_brightness", 0);
        eStandbySeconds = number(device, "standby_after", "Standby after", 60, 86400, 60, "s", null, "standby_seconds", 0);
        eAutoHomeSeconds = number(device, "back_to_page_1_after", "Back to page 1 after", 30, 3600, 30, "s",
                "mdi:timer-outline", "auto_home_seconds", 1);
        eStandby = toggle(device, "auto_standby", "Auto standby", "mdi:sleep", "standby_enabled");
        eNight = toggle(device, "night_mode", "Night mode", "mdi:weather-night", "night_enabled");
        eDark = toggle(device, "dark_mode", "Dark mode", "mdi:theme-light-dark", "dark_mode");
        eAutoHome = toggle(device, "back_to_page_1", "Back to page 1", "mdi:home-import-outline", "auto_home");
        eHomeOnStandby = toggle(device, "back_to_page_1_on_standby", "Back to page 1 on standby", "mdi:home-import-outline", "home_on_standby");
        eSwipe = toggle(device, "swipe_between_pages", "Swipe between pages", "mdi:gesture-swipe-horizontal", "swipe_pages");
        ePageButtons = toggle(device, "page_buttons", "Page buttons", "mdi:page-next-outline", "page_buttons");
        eHomeButton = toggle(device, "show_home_button", "Show home button", "mdi:home", "home_button");
        // Tessera's Loop pages (firmware 0.61.0, on by default): past the last page comes the first, by swipe and by arrow.
        eLoopPages = toggle(device, "loop_pages", "Loop pages", "mdi:sync", "loop_pages");
        // Calls between screens (docs/CALLS.md): the add-on reads Do not disturb by its name; the others are the screen's.
        eCallRingtone = toggle(device, "call_ringtone", "Call ringtone", "mdi:phone-ring", "call_ringtone");
        eCallSwitching = toggle(device, "call_voice_switching", "Call voice switching", "mdi:account-voice", "call_voice_switching");
        eDoNotDisturb = toggle(device, "do_not_disturb", "Do not disturb", "mdi:minus-circle-outline", "do_not_disturb");
        // Not one of Tessera's settings (its panel doesn't list it); a name of its own so it never clashes with one.
        eAndroidSaver = toggle(device, "use_android_screensaver", "Use Android screensaver", "mdi:android", "android_saver");
        eIconToggles = toggle(device, "icon_toggles_tap_opens", "Icon toggles, tap opens controls", "mdi:gesture-tap-button", "icon_toggles");
        eSystemBrightness = toggle(device, "android_brightness_while_awake", "Android brightness", "mdi:brightness-auto", "system_brightness");
        eBatteryDark = toggle(device, "screen_off_in_standby_on_battery", "Screen off in standby on battery", "mdi:battery-off-outline", "battery_dark");
        // The plugins' own settings, named as their plugin.yaml names them so Tessera's editor finds them by name.
        eTapSound = toggle(device, "tap_sound", "Tap sound", "mdi:gesture-tap-button", "tap_sound");
        eWasteInBar = toggle(device, "waste_in_top_bar", "Waste in top bar", "mdi:recycle", "waste_in_bar");
        eWasteDays = number(device, "waste_days_ahead", "Waste days ahead", 0, 3, 1, "", "mdi:calendar-clock", "waste_days_ahead", 1);
        eLiftWake = toggle(device, "lift_to_wake", "Lift to wake", "mdi:cellphone-arrow-down", "lift_wake");
        eNightStart = time(device, "night_starts", "Night starts", "mdi:weather-sunset-down", "night_start");
        eNightEnd = time(device, "night_ends", "Night ends", "mdi:weather-sunset-up", "night_end");
        // A screen that isn't square turns by half turns only.
        eRotation = new EspDevice.Select("rotation", "Rotation", new String[]{"0°", "180°"});
        eRotation.icon = "mdi:screen-rotation";
        eRotation.category = EspDevice.CATEGORY_CONFIG;
        eRotation.onSet = new EspDevice.Select.OnSet() {
            public void set(String option) {
                try {
                    ScreenSettings.this.set("rotation", Integer.parseInt(option.replace("°", "").trim()));
                } catch (NumberFormatException e) {
                    publish();
                }
            }
        };
        device.add(eRotation);

        // Wake and Sleep for automations (0.2.45+): controls, not settings.
        EspDevice.Button wake = new EspDevice.Button("wake", "Wake");
        wake.icon = "mdi:gesture-tap";
        wake.onPress = new Runnable() {
            public void run() { wakeButton(); }
        };
        device.add(wake);
        EspDevice.Button sleep = new EspDevice.Button("sleep", "Sleep");
        sleep.icon = "mdi:power-sleep";
        sleep.onPress = new Runnable() {
            public void run() { sleepButton(); }
        };
        device.add(sleep);
        publish();
    }

    private EspDevice.Number number(EspDevice d, String id, String name, float min, float max, float step, String unit,
                                    String icon, final String key, int mode) {
        EspDevice.Number n = new EspDevice.Number(id, name);
        n.min = min;
        n.max = max;
        n.step = step;
        n.unit = unit;
        n.mode = mode;
        if (icon != null) n.icon = icon;
        n.category = EspDevice.CATEGORY_CONFIG;
        n.onSet = new EspDevice.Number.OnSet() {
            public void set(float value) { ScreenSettings.this.set(key, Math.round(value)); }
        };
        d.add(n);
        return n;
    }

    private EspDevice.Switch eBluetooth;
    private EspDevice.TextSensor eBluetoothStatus;
    private EspDevice.Select eBluetoothScan;
    /** The Bluetooth proxy switch changed. */
    public volatile Runnable onBluetooth, onBluetoothScan;

    /**
     * The Bluetooth proxy's switch and status (Tessera's bt_proxy plugin, named as its plugin.yaml names them): only on a
     * tablet with Bluetooth LE.
     */
    public void bluetoothToggles(EspDevice d) {
        if (eBluetooth != null) return;
        eBluetooth = toggle(d, "bluetooth_proxy", "Bluetooth proxy", "mdi:bluetooth", "bluetooth_proxy");
        eBluetoothScan = new EspDevice.Select("bluetooth_scan", "Bluetooth scan", SCAN_MODES);
        eBluetoothScan.icon = "mdi:bluetooth-audio";
        eBluetoothScan.category = EspDevice.CATEGORY_CONFIG;
        eBluetoothScan.onSet = new EspDevice.Select.OnSet() {
            public void set(String option) {
                int i = java.util.Arrays.asList(SCAN_MODES).indexOf(option);
                if (i >= 0) ScreenSettings.this.set("bluetooth_scan", i);
                else publish();
            }
        };
        d.add(eBluetoothScan);
        eBluetoothStatus = new EspDevice.TextSensor("bluetooth_status", "Bluetooth status");
        eBluetoothStatus.icon = "mdi:bluetooth-settings";
        eBluetoothStatus.category = EspDevice.CATEGORY_DIAGNOSTIC;
        d.add(eBluetoothStatus);
        publish();
    }

    public boolean hasBluetooth() { return eBluetooth != null; }
    public synchronized boolean bluetoothProxy() { return bluetoothProxy != 0; }
    public synchronized int bluetoothScan() { return bluetoothScan; }

    /** The proxy's status in Tessera's words: Listening, Off, Starting, or why it can't. */
    public void bluetoothStatus(String text) {
        EspDevice.TextSensor t = eBluetoothStatus;
        if (t != null) t.set(text);
    }

    public String bluetoothStatusText() {
        EspDevice.TextSensor t = eBluetoothStatus;
        return t == null ? "" : t.get();
    }

    private EspDevice.Switch eWakeWord, eWakeSound, eThinkingSound, eShowPopup;
    private EspDevice.Select eWakePhrase;
    /** The wake word or its phrase changed (the app starts or stops listening). */
    public volatile Runnable onWake;

    /**
     * The voice assistant's settings (Tessera's voice_assist plugin, named as its plugin.yaml names them): only on a
     * tablet with a microphone, so Home Assistant shows none where there is no assistant.
     */
    public void voiceToggles(EspDevice d) {
        if (eWakeSound != null) return;
        eWakeWord = toggle(d, "wake_word", "Wake word", "mdi:account-voice", "assist_wake_word");
        eWakeSound = toggle(d, "wake_sound", "Wake sound", "mdi:bell-ring", "assist_wake_sound");
        eThinkingSound = toggle(d, "thinking_sound", "Thinking sound", "mdi:timer-sand", "assist_thinking_sound");
        eShowPopup = toggle(d, "show_popup", "Show popup", "mdi:message-text", "assist_show_popup");
        eWakePhrase = new EspDevice.Select("wake_phrase", "Wake phrase", WakeWord.PHRASES);
        eWakePhrase.icon = "mdi:chat-processing";
        eWakePhrase.category = EspDevice.CATEGORY_CONFIG;
        eWakePhrase.onSet = new EspDevice.Select.OnSet() {
            public void set(String option) {
                int i = java.util.Arrays.asList(WakeWord.PHRASES).indexOf(option);
                if (i >= 0) ScreenSettings.this.set("assist_wake_phrase", i);
                else publish();
            }
        };
        d.add(eWakePhrase);
        publish();
    }

    public synchronized boolean assistWakeWord() { return assistWakeWord != 0; }
    public synchronized int assistWakePhrase() { return assistWakePhrase; }

    public synchronized boolean assistWakeSound() { return assistWakeSound != 0; }
    public synchronized boolean assistThinkingSound() { return assistThinkingSound != 0; }
    public synchronized boolean assistShowPopup() { return assistShowPopup != 0; }
    public boolean hasVoice() { return eWakeSound != null; }

    private EspDevice.Switch toggle(EspDevice d, String id, String name, String icon, final String key) {
        EspDevice.Switch s = new EspDevice.Switch(id, name);
        s.icon = icon;
        s.category = EspDevice.CATEGORY_CONFIG;
        s.onSet = new EspDevice.Switch.OnSet() {
            public void set(boolean on) {
                if (key.equals("standby_enabled") && on) {
                    synchronized (ScreenSettings.this) { lastTouch = System.currentTimeMillis(); }
                }
                ScreenSettings.this.set(key, on ? 1 : 0);
            }
        };
        d.add(s);
        return s;
    }

    private EspDevice.Time time(EspDevice d, String id, String name, String icon, final String key) {
        EspDevice.Time t = new EspDevice.Time(id, name);
        t.icon = icon;
        t.category = EspDevice.CATEGORY_CONFIG;
        t.onSet = new EspDevice.Time.OnSet() {
            public void set(int minutes) { ScreenSettings.this.set(key, minutes); }
        };
        d.add(t);
        return t;
    }

    public void setUi(Ui u) {
        ui = u;
        apply();
    }

    // ----- the one way a setting changes (settings_screen::set) -----

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int flag(int v) { return v != 0 ? 1 : 0; }

    /** Changes one setting; returns false for a key it doesn't know. A value it already has changes nothing. */
    public boolean set(String key, int value) {
        String before;
        synchronized (this) {
            before = json();
            if (!put(key, value)) return false;
            if (key.equals("standby_enabled") && dimmed && standbyEnabled == 0 && !sleepRequested) dimmed = false;
        }
        String after = json();
        if (after.equals(before)) {
            publish(); // Home Assistant may show a value the screen clamped back
            return true;
        }
        log.log("setting " + key + " = " + value);
        if (store != null) store.save(after);
        publish();
        apply();
        if (key.equals("bluetooth_scan")) {
            Runnable b = onBluetoothScan;
            if (b != null) b.run();
        }
        if (key.equals("bluetooth_proxy")) {
            Runnable b = onBluetooth;
            if (b != null) b.run();
        }
        if (key.equals("assist_wake_word") || key.equals("assist_wake_phrase")) {
            Runnable w = onWake;
            if (w != null) w.run();
        }
        Ui u = ui;
        if (u != null) u.looks();
        return true;
    }

    private boolean put(String key, int value) {
        if (key.equals("standby_enabled")) standbyEnabled = flag(value);
        else if (key.equals("standby_seconds")) standbySeconds = clamp(value, 60, 86400);
        else if (key.equals("brightness")) {
            brightness = clamp(value, 5, 100);
            standbyBrightness = Math.min(standbyBrightness, brightness);
            nightBrightness = Math.min(nightBrightness, brightness);
        } else if (key.equals("standby_brightness")) standbyBrightness = clamp(value, 0, brightness);
        else if (key.equals("night_enabled")) nightEnabled = flag(value);
        else if (key.equals("night_start")) nightStart = clamp(value, 0, 1439);
        else if (key.equals("night_end")) nightEnd = clamp(value, 0, 1439);
        else if (key.equals("night_brightness")) nightBrightness = clamp(value, 0, brightness);
        else if (key.equals("home_on_standby")) homeOnStandby = flag(value);
        else if (key.equals("swipe_pages")) swipePages = flag(value);
        else if (key.equals("rotation")) {
            int turn = clamp(value, 0, 270) / 90 * 90;
            if (turn % 180 != 0) return false; // a quarter turn only on a square screen
            rotation = turn;
        } else if (key.equals("auto_home")) autoHome = flag(value);
        else if (key.equals("auto_home_seconds")) autoHomeSeconds = clamp(value, 30, 3600);
        else if (key.equals("dark_mode")) darkMode = flag(value);
        else if (key.equals("page_buttons")) pageButtons = flag(value);
        else if (key.equals("home_button")) homeButton = flag(value);
        else if (key.equals("loop_pages")) loopPages = flag(value);
        else if (key.equals("call_ringtone")) callRingtone = flag(value);
        else if (key.equals("call_voice_switching")) callSwitching = flag(value);
        else if (key.equals("do_not_disturb")) doNotDisturb = flag(value);
        else if (key.equals("assist_wake_sound")) assistWakeSound = flag(value);
        else if (key.equals("assist_thinking_sound")) assistThinkingSound = flag(value);
        else if (key.equals("assist_show_popup")) assistShowPopup = flag(value);
        else if (key.equals("assist_wake_word")) assistWakeWord = flag(value);
        else if (key.equals("assist_wake_phrase")) assistWakePhrase = clamp(value, 0, WakeWord.PHRASES.length - 1);
        else if (key.equals("bluetooth_proxy")) bluetoothProxy = flag(value);
        else if (key.equals("bluetooth_scan")) bluetoothScan = clamp(value, 0, SCAN_MODES.length - 1);
        else if (key.equals("android_saver")) {
            androidSaver = flag(value);
            if (androidSaver != 0) { dimmed = false; sleepRequested = false; }
            lastTouch = System.currentTimeMillis();
        } else if (key.equals("icon_toggles")) iconToggles = flag(value);
        else if (key.equals("system_brightness")) systemBrightness = flag(value);
        else if (key.equals("lift_wake")) liftWake = flag(value);
        else if (key.equals("battery_dark")) batteryDark = flag(value);
        else if (key.equals("tap_sound")) tapSound = flag(value);
        else if (key.equals("waste_in_bar")) wasteInBar = flag(value);
        else if (key.equals("waste_days_ahead")) wasteDaysAhead = clamp(value, 0, 3);
        else return false;
        return true;
    }

    private synchronized String json() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("brightness", (double) brightness);
        m.put("standby_enabled", (double) standbyEnabled);
        m.put("standby_seconds", (double) standbySeconds);
        m.put("standby_brightness", (double) standbyBrightness);
        m.put("night_enabled", (double) nightEnabled);
        m.put("night_start", (double) nightStart);
        m.put("night_end", (double) nightEnd);
        m.put("night_brightness", (double) nightBrightness);
        m.put("home_on_standby", (double) homeOnStandby);
        m.put("swipe_pages", (double) swipePages);
        m.put("rotation", (double) rotation);
        m.put("auto_home", (double) autoHome);
        m.put("auto_home_seconds", (double) autoHomeSeconds);
        m.put("dark_mode", (double) darkMode);
        m.put("page_buttons", (double) pageButtons);
        m.put("home_button", (double) homeButton);
        m.put("loop_pages", (double) loopPages);
        m.put("call_ringtone", (double) callRingtone);
        m.put("call_voice_switching", (double) callSwitching);
        m.put("do_not_disturb", (double) doNotDisturb);
        m.put("assist_wake_sound", (double) assistWakeSound);
        m.put("assist_thinking_sound", (double) assistThinkingSound);
        m.put("assist_show_popup", (double) assistShowPopup);
        m.put("assist_wake_word", (double) assistWakeWord);
        m.put("assist_wake_phrase", (double) assistWakePhrase);
        m.put("bluetooth_proxy", (double) bluetoothProxy);
        m.put("bluetooth_scan", (double) bluetoothScan);
        m.put("android_saver", (double) androidSaver);
        m.put("icon_toggles", (double) iconToggles);
        m.put("system_brightness", (double) systemBrightness);
        m.put("lift_wake", (double) liftWake);
        m.put("battery_dark", (double) batteryDark);
        m.put("tap_sound", (double) tapSound);
        m.put("waste_in_bar", (double) wasteInBar);
        m.put("waste_days_ahead", (double) wasteDaysAhead);
        return Json.write(m);
    }

    private void load() {
        String kept = store == null ? null : store.load();
        Map<String, Object> m = kept == null ? null : Json.obj(Json.tryParse(kept));
        if (m == null) return;
        // Brightness first, so the dim levels it caps come after it.
        Integer b = Json.integer(m, "brightness");
        if (b != null) put("brightness", b);
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Integer v = Json.integer(m, e.getKey());
            if (v != null) put(e.getKey(), v);
        }
    }

    private void publish() {
        int b, sb, nb, ss, ah, st, ne, nst, nen, dk, ahm, hos, sw, pb, hb, lp, cr, cs, dnd, aw, at, ap, ww, wp, bt, bs, rot, as, it, sys, lw, bd, ts, wb, wd;
        synchronized (this) {
            b = brightness; sb = standbyBrightness; nb = nightBrightness; ss = standbySeconds; ah = autoHomeSeconds;
            st = standbyEnabled; ne = nightEnabled; nst = nightStart; nen = nightEnd; dk = darkMode; ahm = autoHome;
            hos = homeOnStandby; sw = swipePages; pb = pageButtons; hb = homeButton; lp = loopPages; cr = callRingtone; cs = callSwitching; dnd = doNotDisturb; aw = assistWakeSound; at = assistThinkingSound; ap = assistShowPopup; ww = assistWakeWord; wp = assistWakePhrase; bt = bluetoothProxy; bs = bluetoothScan; rot = rotation; as = androidSaver; it = iconToggles;
            sys = systemBrightness; lw = liftWake; bd = batteryDark;
            ts = tapSound; wb = wasteInBar; wd = wasteDaysAhead;
        }
        eBrightness.set(b);
        eStandbyBrightness.set(sb);
        eNightBrightness.set(nb);
        eStandbySeconds.set(ss);
        eAutoHomeSeconds.set(ah);
        eStandby.set(st != 0);
        eNight.set(ne != 0);
        eDark.set(dk != 0);
        eAutoHome.set(ahm != 0);
        eHomeOnStandby.set(hos != 0);
        eSwipe.set(sw != 0);
        ePageButtons.set(pb != 0);
        eHomeButton.set(hb != 0);
        eLoopPages.set(lp != 0);
        eCallRingtone.set(cr != 0);
        eCallSwitching.set(cs != 0);
        eDoNotDisturb.set(dnd != 0);
        if (eWakeSound != null) {
            eWakeWord.set(ww != 0);
            eWakePhrase.set(WakeWord.PHRASES[wp]);
            eWakeSound.set(aw != 0);
            eThinkingSound.set(at != 0);
            eShowPopup.set(ap != 0);
        }
        if (eBluetooth != null) {
            eBluetooth.set(bt != 0);
            eBluetoothScan.set(SCAN_MODES[bs]);
        }
        eNightStart.set(nst);
        eNightEnd.set(nen);
        eRotation.set(rot + "°");
        eAndroidSaver.set(as != 0);
        eIconToggles.set(it != 0);
        eSystemBrightness.set(sys != 0);
        eLiftWake.set(lw != 0);
        eBatteryDark.set(bd != 0);
        eTapSound.set(ts != 0);
        eWasteInBar.set(wb != 0);
        eWasteDays.set(wd);
    }

    // ----- what the drawing side reads -----

    /** A setting's value by its key, as set() takes it; -1 for a key it doesn't know. */
    public synchronized int get(String key) {
        if (key.equals("brightness")) return brightness;
        if (key.equals("standby_enabled")) return standbyEnabled;
        if (key.equals("standby_seconds")) return standbySeconds;
        if (key.equals("standby_brightness")) return standbyBrightness;
        if (key.equals("night_enabled")) return nightEnabled;
        if (key.equals("night_start")) return nightStart;
        if (key.equals("night_end")) return nightEnd;
        if (key.equals("night_brightness")) return nightBrightness;
        if (key.equals("home_on_standby")) return homeOnStandby;
        if (key.equals("swipe_pages")) return swipePages;
        if (key.equals("rotation")) return rotation;
        if (key.equals("auto_home")) return autoHome;
        if (key.equals("auto_home_seconds")) return autoHomeSeconds;
        if (key.equals("dark_mode")) return darkMode;
        if (key.equals("page_buttons")) return pageButtons;
        if (key.equals("home_button")) return homeButton;
        if (key.equals("loop_pages")) return loopPages;
        if (key.equals("call_ringtone")) return callRingtone;
        if (key.equals("call_voice_switching")) return callSwitching;
        if (key.equals("do_not_disturb")) return doNotDisturb;
        if (key.equals("assist_wake_sound")) return assistWakeSound;
        if (key.equals("assist_thinking_sound")) return assistThinkingSound;
        if (key.equals("assist_show_popup")) return assistShowPopup;
        if (key.equals("assist_wake_word")) return assistWakeWord;
        if (key.equals("assist_wake_phrase")) return assistWakePhrase;
        if (key.equals("bluetooth_proxy")) return bluetoothProxy;
        if (key.equals("bluetooth_scan")) return bluetoothScan;
        if (key.equals("android_saver")) return androidSaver;
        if (key.equals("icon_toggles")) return iconToggles;
        if (key.equals("system_brightness")) return systemBrightness;
        if (key.equals("lift_wake")) return liftWake;
        if (key.equals("battery_dark")) return batteryDark;
        if (key.equals("tap_sound")) return tapSound;
        if (key.equals("waste_in_bar")) return wasteInBar;
        if (key.equals("waste_days_ahead")) return wasteDaysAhead;
        return -1;
    }

    public synchronized boolean dark() { return darkMode != 0; }

    public synchronized boolean swipe() { return swipePages != 0; }

    public synchronized boolean pageButtons() { return pageButtons != 0; }

    public synchronized boolean homeButton() { return homeButton != 0; }

    public synchronized boolean loopPages() { return loopPages != 0; }

    public synchronized boolean callRingtone() { return callRingtone != 0; }

    public synchronized boolean callSwitching() { return callSwitching != 0; }

    public synchronized int rotation() { return rotation; }

    public synchronized int brightness() { return brightness; }

    public synchronized boolean dimmed() { return dimmed; }

    /** Whether Android's screen timeout and screensaver stand in for Tessera's standby. */
    public synchronized boolean androidSaver() { return androidSaver != 0; }

    public synchronized boolean iconToggles() { return iconToggles != 0; }

    /** Whether Android sets the brightness: awake always, and in standby while the tablet charges. */
    public synchronized boolean systemBrightness() { return systemBrightness != 0 && androidSaver == 0; }

    /** On the charger or not (ACTION_POWER_CONNECTED and DISCONNECTED): standby looks again. */
    public void charging(boolean on) {
        synchronized (this) {
            if (charging == on) return;
            charging = on;
        }
        log.log(on ? "power: on the charger" : "power: on battery");
        apply();
    }

    /** Whether picking the tablet up wakes it: only from Tessera's standby (Android's screensaver has its own). */
    public synchronized boolean liftWake() { return liftWake != 0 && androidSaver == 0; }

    /** The tablet was picked up or moved while dimmed (MainActivity's accelerometer): wake as a touch would. */
    public void lifted() {
        boolean was;
        synchronized (this) {
            was = dimmed && liftWake != 0;
            if (was) wakeLocked();
        }
        if (was) {
            log.log("wake: the tablet was picked up");
            apply();
        }
    }

    // ----- standby (core.yaml: dim_display, wake_display, the 1 s interval, Wake and Sleep) -----

    public static int minuteOfDay() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private boolean night(int minute) {
        if (nightEnabled == 0 || nightStart == nightEnd) return false;
        return nightStart < nightEnd ? minute >= nightStart && minute < nightEnd : minute >= nightStart || minute < nightEnd;
    }

    /** The level standby dims to now: night brightness in the night hours, else standby brightness. */
    public synchronized int dimLevel() { return night(minuteOfDay()) ? nightBrightness : standbyBrightness; }

    /** Every second: standby when its time ran out. */
    public void tick() {
        boolean due;
        synchronized (this) {
            due = standbyEnabled != 0 && androidSaver == 0 && !dimmed && !touchDown && !alertActive && !ringActive && !callActive
                    && System.currentTimeMillis() - lastTouch > standbySeconds * 1000L;
        }
        if (due) dim();
    }

    /** Every minute: the night hours may have begun or ended. */
    public void minute() { apply(); }

    /** Whether Back to page 1 is due: it's on, the screen is lit, and nobody touched it for long enough. */
    public synchronized boolean homeDue() {
        return autoHome != 0 && !dimmed && !touchDown && !alertActive && !ringActive && !callActive
                && System.currentTimeMillis() - lastUse > autoHomeSeconds * 1000L;
    }

    /**
     * A finger on the glass. Returns true when the touch only woke the screen, so nothing under it acts.
     */
    public boolean touched(boolean down) {
        boolean woke = false;
        synchronized (this) {
            touchDown = down;
            lastTouch = lastUse = System.currentTimeMillis();
            if (dimmed) {
                woke = true;
                if (!down) wakeLocked();
            }
        }
        if (woke && !down) apply();
        return woke;
    }

    private void dim() {
        Ui u = ui;
        boolean home;
        synchronized (this) {
            dimmed = true;
            home = homeOnStandby != 0;
        }
        log.log("standby");
        if (u != null) {
            if (home) u.goHome(); else u.closeCards();
        }
        apply();
    }

    private void wakeLocked() {
        dimmed = false;
        sleepRequested = false;
        lastTouch = System.currentTimeMillis();
    }

    /** Wake from Home Assistant: lights a dimmed screen; on a lit one, the standby time counts from now. */
    public void wakeButton() { wakeBy("Home Assistant"); }

    /** The same for `who` (Home Assistant's Wake, the voice assistant's wake word or question). */
    public void wakeBy(String who) {
        boolean was;
        if (androidSaver()) {
            synchronized (this) { lastTouch = System.currentTimeMillis(); }
            log.log("wake from " + who + " (Android screensaver)");
            Ui u = ui;
            if (u != null) u.systemWake();
            return;
        }
        synchronized (this) {
            was = dimmed;
            if (was) wakeLocked(); else lastTouch = System.currentTimeMillis();
        }
        if (was) {
            log.log("wake from " + who);
            apply();
        }
    }

    /** Sleep from Home Assistant: standby now, without the screensaver, also with Auto standby off. */
    public void sleepButton() {
        if (androidSaver()) {
            log.log("sleep from Home Assistant (Android screensaver)");
            Runnable r = onSleep;
            if (r != null) r.run();
            Ui u = ui;
            if (u != null) u.systemSleep();
            return;
        }
        synchronized (this) { sleepRequested = true; }
        log.log("sleep from Home Assistant");
        Runnable r = onSleep;
        if (r != null) r.run();
        dim();
    }

    /** A ring's view (the doorbell) wakes the screen and holds standby and Back to page 1 off while it shows. */
    public void ringing(boolean up) {
        synchronized (this) {
            ringActive = up;
            if (up) wakeLocked(); else lastTouch = lastUse = System.currentTimeMillis();
        }
        Ui u = ui;
        if (up && u != null && androidSaver()) u.systemWake();
        apply();
    }

    /** A call (docs/CALLS.md) wakes the screen and holds standby and Back to page 1 off while it is up. */
    public void calling(boolean up) {
        synchronized (this) {
            if (callActive == up) return;
            callActive = up;
            if (up) wakeLocked(); else lastTouch = lastUse = System.currentTimeMillis();
        }
        Ui u = ui;
        if (up && u != null && androidSaver()) u.systemWake();
        apply();
    }

    /** An alert wakes the screen and holds standby off while it is up. */
    public void alert(boolean up) {
        synchronized (this) {
            alertActive = up;
            if (up) wakeLocked(); else lastTouch = System.currentTimeMillis();
        }
        Ui u = ui;
        if (up && u != null && androidSaver()) u.systemWake();
        apply();
    }

    private void apply() {
        Ui u = ui;
        if (u == null) return;
        boolean d, saver, android;
        int level;
        synchronized (this) {
            d = dimmed;
            boolean sys = systemBrightness != 0 && androidSaver == 0;
            if (d && batteryDark != 0 && !charging) {
                // Off the charger, standby is dark: no screensaver, the backlight off.
                level = 0;
                saver = false;
                android = false;
            } else if (d) {
                level = night(minuteOfDay()) ? nightBrightness : standbyBrightness;
                // On the charger with Android brightness, standby is Android's level (auto dims it in the dark).
                android = sys && charging && !sleepRequested;
                saver = !sleepRequested && (android || level > 0);
            } else {
                level = brightness;
                saver = false;
                android = sys;
            }
        }
        u.power(d, level, saver, android);
    }
}
