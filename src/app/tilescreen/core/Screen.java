package app.tilescreen.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Tessera screen as Home Assistant sees it: the ESPHome device, its Tessera entities and
 * actions, its own settings (Tessera's Screen settings: brightness, standby, night, ...), and the hooks the drawing
 * side uses (performing actions, alerts).
 */
public final class Screen {

    public static final class Alert {
        public String title, subtitle, icon, color, button, button2, buttonColor, button2Color;
        public int timeoutSeconds;
        public boolean flash;
    }

    public interface Ui {
        void alert(Alert a);
        void dismissAlert();
        void openSettings();
        void changed();
        /** What the glass shows: TILES (Tessera's pages), WIDGETS (the Android page) or ANDROID (the launcher). */
        void show(String what);
        /** The doorbell (docs/DOORBELL.md): a ring, its close from elsewhere, its lock's new state. */
        void ring(Tessera.Ring r);
        void closeRing(String key);
        void ringDoorState(String key, String state);
    }

    public static final String TILES = "Tiles", WIDGETS = "Widgets", ANDROID = "Android";

    public static final class Settings {
        public String node = "tablet";
        public String friendly = "Tablet";
        public String board = "jc1060p470v2";
        public String mac = "02:00:00:00:00:01";
        public String model = "Android tablet";
        public String manufacturer = "Android";
        public String language = "en";
    }

    public final EspDevice device;
    public final Tessera tessera;
    private final EspDevice.Text inbox;
    private final EspDevice.TextSensor shape, ip;
    /** The screen's own settings, standby and night (Tessera's Screen settings). */
    public final ScreenSettings screenSettings;
    private final Settings settings;
    private final EspDevice.Logger log;
    private volatile Ui ui;

    public Screen(final Settings s, Tessera.Shape shapeSource, Tessera.Store store, ScreenSettings.Store settingsStore,
                  Map<String, Integer> defaults, EspDevice.Logger log) {
        this.settings = s;
        this.log = log;
        EspDevice.Info info = new EspDevice.Info();
        info.name = s.node;
        info.friendlyName = s.friendly;
        info.mac = s.mac;
        info.model = s.model;
        info.manufacturer = s.manufacturer;
        info.projectName = "tilescreen.android";
        info.projectVersion = Tessera.FIRMWARE;
        device = new EspDevice(info, log);
        tessera = new Tessera(shapeSource, store, log);

        inbox = new EspDevice.Text("tessera_dashboard_tile_settings", "Tile settings");
        inbox.icon = "mdi:view-grid";
        inbox.onCommand = new EspDevice.Text.OnCommand() {
            public void command(String value) { inbox.set("Error: incomplete message"); }
        };
        inbox.set(Tessera.READY);
        device.add(inbox);

        shape = diagnostic("tessera_dashboard_screen_layout", "Screen layout", tessera.shapeText());
        diagnostic("tessera_dashboard_screen_firmware", "Screen firmware", Tessera.FIRMWARE);
        diagnostic("tessera_dashboard_screen_board", "Screen board", s.board);
        diagnostic("tessera_dashboard_device_name", "Device name", s.node);
        diagnostic("tessera_dashboard_screen_language", "Screen language", s.language);
        diagnostic("tessera_dashboard_screen_features", "Screen features", "dimmable standby");
        diagnostic("tessera_dashboard_screen_flash", "Screen flash", "other");
        ip = diagnostic("ip_address", "IP address", "");

        // The tablet's next alarm, as the Home Assistant app's next_alarm sensor gives it: the moment, and the app that set it.
        nextAlarm = new EspDevice.TextSensor("next_alarm", "Next alarm");
        nextAlarm.icon = "mdi:alarm";
        nextAlarm.deviceClass = "timestamp";
        device.add(nextAlarm);
        nextAlarmApp = diagnostic("next_alarm_app", "Next alarm app", "");
        nextAlarmApp.icon = "mdi:alarm-check";

        // What the glass shows: Tessera's tiles, the page of Android widgets, or Android's own launcher and apps.
        showing = new EspDevice.Select("showing", "Showing", new String[]{TILES, WIDGETS, ANDROID});
        showing.icon = "mdi:monitor-dashboard";
        showing.onSet = new EspDevice.Select.OnSet() {
            public void set(String option) {
                Ui u = ui;
                if (u != null) u.show(option);
                else showing.set(showing.get());
            }
        };
        showing.set(TILES);
        device.add(showing);

        screenSettings = new ScreenSettings(device, settingsStore, log, defaults);
        screenSettings.onSleep = new Runnable() {
            public void run() {
                Ui u = ui;
                if (u != null) u.dismissAlert();
            }
        };

        device.add(new EspDevice.Action("screen_message", new String[]{"message"},
                new int[]{EspDevice.ARG_STRING}, true, new EspDevice.Handler() {
            public Object run(Map<String, Object> args) {
                Map<String, Object> answer = tessera.receive(String.valueOf(args.get("message")));
                inbox.set(String.valueOf(answer.get("status")));
                return answer;
            }
        }));
        device.add(new EspDevice.Action("show_page", new String[]{"page"},
                new int[]{EspDevice.ARG_INT}, false, new EspDevice.Handler() {
            public Object run(Map<String, Object> args) {
                tessera.showPage(((Integer) args.get("page")));
                Ui u = ui;
                if (u != null && !TILES.equals(showing.get())) u.show(TILES);
                return null;
            }
        }));
        String[] alertArgs = {"title", "subtitle", "icon", "color", "button_text", "timeout", "flash"};
        int[] alertTypes = {EspDevice.ARG_STRING, EspDevice.ARG_STRING, EspDevice.ARG_STRING, EspDevice.ARG_STRING,
                EspDevice.ARG_STRING, EspDevice.ARG_INT, EspDevice.ARG_BOOL};
        device.add(new EspDevice.Action("show_alert", alertArgs, alertTypes, false, new EspDevice.Handler() {
            public Object run(Map<String, Object> args) {
                alert(args, false);
                return null;
            }
        }));
        String[] choiceArgs = {"title", "subtitle", "icon", "color", "button_text", "timeout", "flash",
                "button_color", "button2_text", "button2_color"};
        int[] choiceTypes = {EspDevice.ARG_STRING, EspDevice.ARG_STRING, EspDevice.ARG_STRING, EspDevice.ARG_STRING,
                EspDevice.ARG_STRING, EspDevice.ARG_INT, EspDevice.ARG_BOOL, EspDevice.ARG_STRING,
                EspDevice.ARG_STRING, EspDevice.ARG_STRING};
        device.add(new EspDevice.Action("show_alert_choice", choiceArgs, choiceTypes, false, new EspDevice.Handler() {
            public Object run(Map<String, Object> args) {
                alert(args, true);
                return null;
            }
        }));
        device.add(new EspDevice.Action("open_settings", new String[0], new int[0], false, new EspDevice.Handler() {
            public Object run(Map<String, Object> args) {
                screenSettings.wakeButton();
                Ui u = ui;
                if (u != null) u.openSettings();
                return null;
            }
        }));
        device.add(new EspDevice.Action("dismiss_alert", new String[0], new int[0], false, new EspDevice.Handler() {
            public Object run(Map<String, Object> args) {
                Ui u = ui;
                if (u != null) u.dismissAlert();
                return null;
            }
        }));

        tessera.setTeller(new Tessera.Teller() {
            public void tell(String event, Map<String, String> data) {
                device.callHomeAssistant("esphome." + event, data, null, true);
            }
        });
        tessera.setDoorbell(new Tessera.Doorbell() {
            public void ring(Tessera.Ring r) {
                Ui u = ui;
                if (u != null) u.ring(r);
            }
            public void close(String key) {
                Ui u = ui;
                if (u != null) u.closeRing(key);
            }
            public void doorState(String key, String state) {
                Ui u = ui;
                if (u != null) u.ringDoorState(key, state);
            }
        });
        tessera.listen(new Tessera.Listener() {
            public void changed() {
                Ui u = ui;
                if (u != null) u.changed();
            }
        });
        device.onClientsChanged(new Runnable() {
            public void run() {
                Ui u = ui;
                if (u != null) u.changed();
            }
        });
    }

    private EspDevice.TextSensor nextAlarm, nextAlarmApp;
    private final EspDevice.Select showing;

    /** What the glass shows now, for Home Assistant's Showing select. */
    public void showing(String what) {
        if (!what.equals(showing.get())) showing.set(what);
    }

    public String showing() { return showing.get(); }

    /**
     * The next alarm Android knows of (AlarmManager.getNextAlarmClock): its moment in milliseconds, or 0 for none, and
     * the package of the app that set it.
     */
    public void nextAlarm(long at, String app) {
        if (at <= 0) {
            nextAlarm.clear();
            nextAlarmApp.set("");
            return;
        }
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", java.util.Locale.ROOT);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        nextAlarm.set(iso.format(new java.util.Date(at)));
        nextAlarmApp.set(app == null ? "" : app);
    }

    // ----- the battery, as the Home Assistant app reports it: level, state and charger -----

    private EspDevice.Sensor batteryLevel;
    private EspDevice.TextSensor batteryState, chargerType;

    /**
     * The tablet's battery (ScreenService, from Android's ACTION_BATTERY_CHANGED): its level in percent, its state
     * (charging, discharging, full, not_charging) and its charger (ac, usb, wireless, none). The sensors appear with the
     * first report, so a tablet without a battery has none.
     */
    public synchronized void battery(int level, String state, String charger) {
        if (batteryLevel == null) {
            batteryLevel = new EspDevice.Sensor("battery_level", "Battery level");
            batteryLevel.unit = "%";
            batteryLevel.deviceClass = "battery";
            batteryLevel.icon = "mdi:battery";
            batteryLevel.category = EspDevice.CATEGORY_DIAGNOSTIC;
            device.add(batteryLevel);
            batteryState = new EspDevice.TextSensor("battery_state", "Battery state");
            batteryState.icon = "mdi:battery-minus";
            batteryState.category = EspDevice.CATEGORY_DIAGNOSTIC;
            device.add(batteryState);
            chargerType = new EspDevice.TextSensor("charger_type", "Charger type");
            chargerType.icon = "mdi:battery-charging";
            chargerType.category = EspDevice.CATEGORY_DIAGNOSTIC;
            device.add(chargerType);
        }
        batteryLevel.set(level);
        batteryState.set(state);
        chargerType.set(charger);
    }

    private EspDevice.TextSensor diagnostic(String id, String name, String value) {
        EspDevice.TextSensor t = new EspDevice.TextSensor(id, name);
        t.category = EspDevice.CATEGORY_DIAGNOSTIC;
        t.set(value);
        device.add(t);
        return t;
    }

    private static String cut(Object v, int most) {
        String s = v == null ? "" : String.valueOf(v).trim();
        return s.length() <= most ? s : s.substring(0, most - 3) + "...";
    }

    private void alert(Map<String, Object> args, boolean choice) {
        Ui u = ui;
        if (u == null) return;
        Alert a = new Alert();
        a.title = cut(args.get("title"), 64);
        if (a.title.isEmpty()) a.title = "Notification";
        a.subtitle = cut(args.get("subtitle"), 240);
        a.icon = String.valueOf(args.get("icon"));
        a.color = String.valueOf(args.get("color")).trim().toLowerCase();
        a.button = cut(args.get("button_text"), 16);
        if (a.button.isEmpty()) a.button = "OK";
        Object t = args.get("timeout");
        a.timeoutSeconds = t instanceof Integer ? Math.max(0, Math.min(86400, (Integer) t)) : 0;
        a.flash = Boolean.TRUE.equals(args.get("flash"));
        if (choice) {
            a.buttonColor = String.valueOf(args.get("button_color")).trim().toLowerCase();
            a.button2 = cut(args.get("button2_text"), 16);
            a.button2Color = String.valueOf(args.get("button2_color")).trim().toLowerCase();
        }
        screenSettings.alert(true);
        u.alert(a);
    }

    /** Reports how an alert ended: ok, button2, timeout, replaced or remote. */
    public void alertEnded(Alert a, String action) {
        screenSettings.alert(false);
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("action", action);
        data.put("title", a.title);
        data.put("screen", settings.node);
        device.callHomeAssistant("esphome.screen_alert", data, null, true);
    }

    /**
     * How a ring ended here, or the door key's second tap (esphome.screen_doorbell, packages/core.yaml doorbell_report):
     * answered, closed, missed, seen or door.
     */
    public void doorbellReport(String action, String key) {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("action", action);
        data.put("key", key);
        data.put("screen", settings.node);
        log.log("doorbell: " + action);
        device.callHomeAssistant("esphome.screen_doorbell", data, null, true);
    }

    public void setUi(Ui u) { ui = u; }

    private volatile boolean ticking;

    public void start(int port) throws java.io.IOException {
        device.start(port);
        ticking = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                int lastMinute = -1;
                while (ticking) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    screenSettings.tick();
                    int m = ScreenSettings.minuteOfDay();
                    if (m != lastMinute) {
                        lastMinute = m;
                        screenSettings.minute();
                    }
                }
            }
        }, "standby");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        ticking = false;
        device.stop();
    }

    /** The canvas or grid changed: tells Tessera the new shape. */
    public void regrid() {
        String s = tessera.shapeText();
        if (!s.equals(shape.get())) {
            shape.set(s);
            log.log("screen layout " + s);
        }
    }

    public void setIp(String address) { ip.set(address == null ? "" : address); }


    public boolean perform(String service, Map<String, String> data, Map<String, String> templates) {
        return device.callHomeAssistant(service, data, templates, false);
    }

    public Settings settings() { return settings; }
}
