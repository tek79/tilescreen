package app.tilescreen.core;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A plaintext ESPHome native API server: enough of the protocol for Home Assistant to adopt the
 * device, list its entities and actions, read and set states, call its actions with responses,
 * and let the device perform Home Assistant actions and fire events.
 */
public final class EspDevice {

    // Message ids from esphome's api.proto.
    static final int HELLO_REQ = 1, HELLO_RESP = 2, AUTH_REQ = 3, AUTH_RESP = 4, DISCONNECT_REQ = 5,
            DISCONNECT_RESP = 6, PING_REQ = 7, PING_RESP = 8, DEVICE_INFO_REQ = 9, DEVICE_INFO_RESP = 10,
            LIST_ENTITIES_REQ = 11, LIST_SENSOR = 16, SENSOR_STATE = 25, LIST_TEXT_SENSOR = 18, LIST_DONE = 19, SUBSCRIBE_STATES = 20,
            TEXT_SENSOR_STATE = 27, SUBSCRIBE_LOGS = 28, SUBSCRIBE_HA_SERVICES = 34, HA_ACTION_REQ = 35,
            GET_TIME_REQ = 36, GET_TIME_RESP = 37, SUBSCRIBE_HA_STATES = 38, HA_STATE = 40,
            LIST_SERVICES = 41, EXECUTE_SERVICE = 42, LIST_NUMBER = 49, NUMBER_STATE = 50, NUMBER_CMD = 51,
            LIST_SWITCH = 17, SWITCH_STATE = 26, SWITCH_CMD = 33,
            LIST_TEXT = 97, TEXT_STATE = 98, TEXT_CMD = 99, HA_ACTION_RESP = 130, EXECUTE_SERVICE_RESP = 131,
            SET_KEY_REQ = 124, SET_KEY_RESP = 125,
            LIST_SELECT = 52, SELECT_STATE = 53, SELECT_CMD = 54, LIST_BUTTON = 61, BUTTON_CMD = 62,
            LIST_TIME = 103, TIME_STATE = 104, TIME_CMD = 105;

    public static final int API_MAJOR = 1, API_MINOR = 12;
    public static final String ESPHOME_VERSION = "2026.5.1";

    public static final int CATEGORY_NONE = 0, CATEGORY_CONFIG = 1, CATEGORY_DIAGNOSTIC = 2;
    public static final int ARG_BOOL = 0, ARG_INT = 1, ARG_FLOAT = 2, ARG_STRING = 3;

    public interface Logger { void log(String line); }

    public static final class Info {
        public String name = "tablet";
        public String friendlyName = "Tablet";
        public String mac = "02:00:00:00:00:01";
        public String model = "Android tablet";
        public String manufacturer = "Android";
        public String projectName = "tilescreen.android";
        public String projectVersion = "0.1.0";
        public String suggestedArea = "";
    }

    // ----- entities -----

    public abstract static class Entity {
        public final String objectId, name;
        public String icon = "";
        public int category = CATEGORY_NONE;
        EspDevice owner;

        Entity(String objectId, String name) {
            this.objectId = objectId;
            this.name = name;
        }

        public final int key() {
            int h = 0x811C9DC5;
            for (byte b : objectId.getBytes(Pb.UTF8)) {
                h *= 16777619;
                h ^= (b & 0xff);
            }
            return h;
        }

        abstract int listType();
        abstract Pb.Out describe();
        abstract int stateType();
        abstract Pb.Out state();
        void command(List<Pb.Field> f) {}

        protected final void publish() {
            EspDevice d = owner;
            if (d != null) d.broadcastState(this);
        }
    }

    public static final class Text extends Entity {
        public int maxLength = 255;
        private volatile String value = "";
        private volatile boolean known;
        public interface OnCommand { void command(String value); }
        public OnCommand onCommand;

        public Text(String objectId, String name) { super(objectId, name); }

        public void set(String v) {
            value = v == null ? "" : v;
            known = true;
            publish();
        }

        public String get() { return value; }

        int listType() { return LIST_TEXT; }

        Pb.Out describe() {
            return new Pb.Out().string(1, objectId).key(2, key()).string(3, name).string(5, icon)
                    .varint(7, category).varint(9, maxLength);
        }

        int stateType() { return TEXT_STATE; }

        Pb.Out state() { return new Pb.Out().key(1, key()).string(2, value).bool(3, !known); }

        void command(List<Pb.Field> f) {
            Pb.Field s = Pb.first(f, 2);
            String v = s == null ? "" : s.text();
            if (onCommand != null) onCommand.command(v); else set(v);
        }
    }

    public static final class TextSensor extends Entity {
        /** Home Assistant's sensor device class: "timestamp" makes the value a moment (ISO 8601). */
        public String deviceClass = "";
        private volatile String value = "";
        private volatile boolean known;

        public TextSensor(String objectId, String name) { super(objectId, name); }

        public void set(String v) {
            if (v == null) v = "";
            if (known && v.equals(value)) return;
            value = v;
            known = true;
            publish();
        }

        /** No value: Home Assistant shows the sensor as unknown. */
        public void clear() {
            if (!known && value.isEmpty()) return;
            value = "";
            known = false;
            publish();
        }

        public String get() { return value; }

        int listType() { return LIST_TEXT_SENSOR; }

        Pb.Out describe() {
            return new Pb.Out().string(1, objectId).key(2, key()).string(3, name).string(5, icon).varint(7, category)
                    .string(8, deviceClass);
        }

        int stateType() { return TEXT_SENSOR_STATE; }

        Pb.Out state() { return new Pb.Out().key(1, key()).string(2, value).bool(3, !known); }
    }

    /** A sensor with a number (ListEntitiesSensorResponse, SensorStateResponse): its unit, device class and decimals. */
    public static final class Sensor extends Entity {
        public String unit = "", deviceClass = "";
        public int decimals = 0;
        /** Home Assistant's state class: 1 measurement (kept in long-term statistics), 0 none. */
        public int stateClass = 1;
        private volatile float value;
        private volatile boolean known;

        public Sensor(String objectId, String name) { super(objectId, name); }

        public void set(float v) {
            if (known && v == value) return;
            value = v;
            known = true;
            publish();
        }

        int listType() { return LIST_SENSOR; }

        Pb.Out describe() {
            return new Pb.Out().string(1, objectId).key(2, key()).string(3, name).string(5, icon).string(6, unit)
                    .varint(7, decimals).string(9, deviceClass).varint(10, stateClass).varint(13, category);
        }

        int stateType() { return SENSOR_STATE; }

        Pb.Out state() { return new Pb.Out().key(1, key()).flt(2, value).bool(3, !known); }
    }

    public static final class Number extends Entity {
        public float min = 0, max = 100, step = 1;
        public String unit = "";
        public int mode = 0; // auto
        private volatile float value;
        public interface OnSet { void set(float value); }
        public OnSet onSet;

        public Number(String objectId, String name) { super(objectId, name); }

        public void set(float v) {
            value = v;
            publish();
        }

        public float get() { return value; }

        int listType() { return LIST_NUMBER; }

        Pb.Out describe() {
            return new Pb.Out().string(1, objectId).key(2, key()).string(3, name).string(5, icon)
                    .flt(6, min).flt(7, max).flt(8, step).varint(10, category).string(11, unit).varint(12, mode);
        }

        int stateType() { return NUMBER_STATE; }

        Pb.Out state() { return new Pb.Out().key(1, key()).flt(2, value); }

        void command(List<Pb.Field> f) {
            Pb.Field s = Pb.first(f, 2);
            float v = s == null ? 0f : s.flt();
            v = Math.max(min, Math.min(max, v));
            if (onSet != null) onSet.set(v); else set(v);
        }
    }

    public static final class Switch extends Entity {
        private volatile boolean value;
        public interface OnSet { void set(boolean value); }
        public OnSet onSet;

        public Switch(String objectId, String name) { super(objectId, name); }

        public void set(boolean v) {
            value = v;
            publish();
        }

        public boolean get() { return value; }

        int listType() { return LIST_SWITCH; }

        Pb.Out describe() {
            return new Pb.Out().string(1, objectId).key(2, key()).string(3, name).string(5, icon).varint(8, category);
        }

        int stateType() { return SWITCH_STATE; }

        Pb.Out state() { return new Pb.Out().key(1, key()).bool(2, value); }

        void command(List<Pb.Field> f) {
            Pb.Field s = Pb.first(f, 2);
            boolean v = s != null && s.varint != 0;
            if (onSet != null) onSet.set(v); else set(v);
        }
    }

    public static final class Select extends Entity {
        public final String[] options;
        private volatile String value = "";
        public interface OnSet { void set(String option); }
        public OnSet onSet;

        public Select(String objectId, String name, String[] options) {
            super(objectId, name);
            this.options = options;
        }

        public void set(String v) {
            value = v;
            publish();
        }

        public String get() { return value; }

        int listType() { return LIST_SELECT; }

        Pb.Out describe() {
            Pb.Out m = new Pb.Out().string(1, objectId).key(2, key()).string(3, name).string(5, icon);
            for (String o : options) m.string(6, o);
            return m.varint(8, category);
        }

        int stateType() { return SELECT_STATE; }

        Pb.Out state() { return new Pb.Out().key(1, key()).string(2, value).bool(3, value.isEmpty()); }

        void command(List<Pb.Field> f) {
            Pb.Field s = Pb.first(f, 2);
            String v = s == null ? "" : s.text();
            if (onSet != null) onSet.set(v); else set(v);
        }
    }

    public static final class Button extends Entity {
        public Runnable onPress;

        public Button(String objectId, String name) { super(objectId, name); }

        int listType() { return LIST_BUTTON; }

        Pb.Out describe() {
            return new Pb.Out().string(1, objectId).key(2, key()).string(3, name).string(5, icon).varint(7, category);
        }

        int stateType() { return 0; }

        Pb.Out state() { return null; }

        void command(List<Pb.Field> f) {
            if (onPress != null) onPress.run();
        }
    }

    /** A time of day (ESPHome's datetime platform, type time). */
    public static final class Time extends Entity {
        private volatile int minutes = -1;
        public interface OnSet { void set(int minutesOfDay); }
        public OnSet onSet;

        public Time(String objectId, String name) { super(objectId, name); }

        public void set(int minutesOfDay) {
            minutes = minutesOfDay;
            publish();
        }

        int listType() { return LIST_TIME; }

        Pb.Out describe() {
            return new Pb.Out().string(1, objectId).key(2, key()).string(3, name).string(5, icon).varint(7, category);
        }

        int stateType() { return TIME_STATE; }

        Pb.Out state() {
            int m = minutes;
            return new Pb.Out().key(1, key()).bool(2, m < 0).varint(3, Math.max(0, m) / 60).varint(4, Math.max(0, m) % 60);
        }

        void command(List<Pb.Field> f) {
            Pb.Field h = Pb.first(f, 2), mi = Pb.first(f, 3);
            int v = (int) ((h == null ? 0 : h.varint) * 60 + (mi == null ? 0 : mi.varint));
            if (onSet != null) onSet.set(v); else set(v);
        }
    }

    // ----- actions (ESPHome "user-defined actions", formerly services) -----

    public interface Handler {
        /** Returns the JSON-able response for a caller that asked for one, or null. */
        Object run(Map<String, Object> args) throws Exception;
    }

    public static final class Action {
        final String name;
        final String[] argNames;
        final int[] argTypes;
        final boolean answers;
        final Handler handler;

        public Action(String name, String[] argNames, int[] argTypes, boolean answers, Handler handler) {
            this.name = name;
            this.argNames = argNames;
            this.argTypes = argTypes;
            this.answers = answers;
            this.handler = handler;
        }

        int key() {
            int h = 0x811C9DC5;
            for (byte b : ("action:" + name).getBytes(Pb.UTF8)) {
                h *= 16777619;
                h ^= (b & 0xff);
            }
            return h;
        }
    }

    // ----- the server -----

    private final Info info;
    private final Logger log;
    private final List<Entity> entities = new ArrayList<Entity>();
    private final List<Action> actions = new ArrayList<Action>();
    private final List<Conn> conns = new CopyOnWriteArrayList<Conn>();
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile Runnable onClients;
    /** The Noise key Home Assistant gave this device, or null while it has none (plaintext and zero-key Noise both work). */
    private volatile byte[] key;

    public interface KeyStore { void save(byte[] key); }
    private volatile KeyStore keyStore;
    /** Writes that start on other threads (Android's main thread may not touch sockets) go out here, in order. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "esphome-writer");
            t.setDaemon(true);
            return t;
        }
    });

    public EspDevice(Info info, Logger log) {
        this.info = info;
        this.log = log;
    }

    public synchronized void add(Entity e) {
        e.owner = this;
        entities.add(e);
    }

    public synchronized void add(Action a) { actions.add(a); }

    public void onClientsChanged(Runnable r) { onClients = r; }

    /** The key in use (null: none yet), and where a key Home Assistant pushes is kept. */
    public void setKey(byte[] k, KeyStore store) {
        key = k;
        keyStore = store;
    }

    public boolean encrypted() { return key != null; }

    public byte[] key() { return key; }

    public void start(final int port) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        server = s;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }, "esphome-accept");
        t.setDaemon(true);
        t.start();
        log.log("ESPHome API listening on port " + s.getLocalPort());
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? 0 : s.getLocalPort();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        for (Conn c : conns) c.close();
        conns.clear();
        writer.shutdown();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket sock = server.accept();
                sock.setTcpNoDelay(true);
                sock.setKeepAlive(true);
                final Conn c = new Conn(sock);
                conns.add(c);
                Thread t = new Thread(new Runnable() {
                    public void run() { c.run(); }
                }, "esphome-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) log.log("accept failed: " + e);
            }
        }
    }

    /** Connections that finished the handshake. */
    public int clients() {
        int n = 0;
        for (Conn c : conns) if (c.ready) n++;
        return n;
    }

    /** Connections that subscribed to Home Assistant actions, which HA does only when the device may perform them. */
    public int actionSubscribers() {
        int n = 0;
        for (Conn c : conns) if (c.servicesSubscribed) n++;
        return n;
    }

    void broadcastState(Entity e) {
        final int type = e.stateType();
        final Pb.Out m = e.state();
        if (m == null) return;
        for (final Conn c : conns) {
            if (c.statesSubscribed) later(c, type, m);
        }
    }

    private void later(final Conn c, final int type, final Pb.Out m) {
        try {
            writer.execute(new Runnable() {
                public void run() { c.send(type, m); }
            });
        } catch (RuntimeException e) {
            // shutting down
        }
    }

    /**
     * Performs a Home Assistant action (or fires an event when isEvent), on every connection that
     * subscribed. Returns false when no connection could carry it.
     */
    public boolean callHomeAssistant(String service, Map<String, String> data, Map<String, String> templates, boolean isEvent) {
        Pb.Out m = new Pb.Out().string(1, service);
        if (data != null) {
            for (Map.Entry<String, String> e : data.entrySet()) {
                m.message(2, new Pb.Out().string(1, e.getKey()).string(2, e.getValue()));
            }
        }
        if (templates != null) {
            for (Map.Entry<String, String> e : templates.entrySet()) {
                m.message(3, new Pb.Out().string(1, e.getKey()).string(2, e.getValue()));
            }
        }
        m.bool(5, isEvent);
        boolean sent = false;
        for (Conn c : conns) {
            if (c.servicesSubscribed) {
                later(c, HA_ACTION_REQ, m);
                sent = true;
            }
        }
        if (!sent) log.log("no Home Assistant connection takes actions; dropped " + service);
        return sent;
    }

    /**
     * A key Home Assistant pushes (base64 of 32 bytes), or an empty one to clear it. A key is taken only over an
     * encrypted connection when the device already has one; it applies to the connections after this one.
     */
    boolean setKeyFromHomeAssistant(String text, boolean overNoise) {
        byte[] current = key;
        if (text.isEmpty()) {
            if (current == null || !overNoise) return false;
            key = null;
            log.log("Home Assistant removed the encryption key; plaintext works again");
        } else {
            byte[] k = Crypto.unbase64(text);
            boolean zero = true;
            if (k != null) for (byte b : k) if (b != 0) zero = false;
            if (k == null || k.length != 32 || zero) {
                log.log("refused an encryption key that isn't 32 bytes of base64");
                return false;
            }
            if (current != null && !overNoise) return false;
            key = k;
            log.log("Home Assistant set this screen's encryption key; it applies from the next connection");
        }
        KeyStore s = keyStore;
        if (s != null) s.save(key);
        changed();
        return true;
    }

    private void changed() {
        Runnable r = onClients;
        if (r != null) r.run();
    }

    private final class Conn {
        final Socket sock;
        final InputStream in;
        final OutputStream out;
        volatile boolean ready, statesSubscribed, servicesSubscribed;
        volatile Noise noise;
        final String peer;

        Conn(Socket sock) throws IOException {
            this.sock = sock;
            this.in = new BufferedInputStream(sock.getInputStream());
            this.out = sock.getOutputStream();
            this.peer = String.valueOf(sock.getRemoteSocketAddress());
        }

        void run() {
            log.log("client connected: " + peer);
            try {
                int marker = in.read();
                if (marker == 0x01) {
                    byte[] psk = key;
                    boolean zero = psk == null;
                    Noise n = new Noise(in, out);
                    n.handshake(zero ? new byte[32] : psk, info.name);
                    noise = n;
                    log.log(peer + (zero ? " connected encrypted with the provisioning key" : " connected encrypted"));
                    while (running) {
                        Object[] m = n.read();
                        if (m == null || !handle((Integer) m[0], (byte[]) m[1])) break;
                    }
                } else if (marker == 0x00) {
                    if (key != null) {
                        log.log(peer + " tried plaintext, but this device has an encryption key. "
                                + "Give Home Assistant the key, or forget it in this screen's settings.");
                        Noise.refusePlaintext(out);
                        return;
                    }
                    while (running && marker == 0x00) {
                        int length = (int) varint();
                        int type = (int) varint();
                        if (length < 0 || length > 1 << 20) throw new IOException("frame too large");
                        byte[] body = new byte[length];
                        int got = 0;
                        while (got < length) {
                            int r = in.read(body, got, length - got);
                            if (r < 0) throw new EOFException();
                            got += r;
                        }
                        if (!handle(type, body)) break;
                        marker = in.read();
                    }
                }
            } catch (Noise.Refused e) {
                log.log(peer + " handshake refused: " + e.getMessage()
                        + (key != null ? " (Home Assistant has a different key)" : ""));
            } catch (SocketException e) {
                // closed
            } catch (EOFException e) {
                // closed
            } catch (Exception e) {
                log.log(peer + " dropped: " + e);
            } finally {
                close();
                conns.remove(this);
                log.log("client gone: " + peer);
                changed();
            }
        }

        private long varint() throws IOException {
            long v = 0;
            int shift = 0;
            while (true) {
                int b = in.read();
                if (b < 0) throw new EOFException();
                v |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return v;
                shift += 7;
                if (shift > 35) throw new IOException("bad varint");
            }
        }

        boolean handle(int type, byte[] body) {
            List<Pb.Field> f = Pb.parse(body);
            switch (type) {
                case HELLO_REQ: {
                    Pb.Field ci = Pb.first(f, 1);
                    log.log(peer + " hello from " + (ci == null ? "?" : ci.text()));
                    send(HELLO_RESP, new Pb.Out().varint(1, API_MAJOR).varint(2, API_MINOR)
                            .string(3, info.name + " (tilescreen)").string(4, info.name));
                    ready = true;
                    changed();
                    return true;
                }
                case AUTH_REQ:
                    send(AUTH_RESP, new Pb.Out());
                    return true;
                case DISCONNECT_REQ:
                    send(DISCONNECT_RESP, new Pb.Out());
                    return false;
                case DISCONNECT_RESP:
                    return false;
                case PING_REQ:
                    send(PING_RESP, new Pb.Out());
                    return true;
                case PING_RESP:
                    return true;
                case DEVICE_INFO_REQ:
                    send(DEVICE_INFO_RESP, new Pb.Out()
                            .string(2, info.name).string(3, info.mac).string(4, ESPHOME_VERSION)
                            .string(5, "tilescreen").string(6, info.model).string(8, info.projectName)
                            .string(9, info.projectVersion).string(12, info.manufacturer)
                            .string(13, info.friendlyName).string(16, info.suggestedArea)
                            .bool(19, true).bool(26, key == null));
                    return true;
                case LIST_ENTITIES_REQ: {
                    List<Entity> es;
                    List<Action> as;
                    synchronized (EspDevice.this) {
                        es = new ArrayList<Entity>(entities);
                        as = new ArrayList<Action>(actions);
                    }
                    for (Entity e : es) send(e.listType(), e.describe());
                    for (Action a : as) {
                        Pb.Out m = new Pb.Out().string(1, a.name).key(2, a.key());
                        for (int k = 0; k < a.argNames.length; k++) {
                            m.message(3, new Pb.Out().string(1, a.argNames[k]).varint(2, a.argTypes[k]));
                        }
                        m.varint(4, a.answers ? 1 : 0);
                        send(LIST_SERVICES, m);
                    }
                    send(LIST_DONE, new Pb.Out());
                    return true;
                }
                case SUBSCRIBE_STATES: {
                    statesSubscribed = true;
                    List<Entity> es;
                    synchronized (EspDevice.this) { es = new ArrayList<Entity>(entities); }
                    for (Entity e : es) {
                        Pb.Out st = e.state();
                        if (st != null) send(e.stateType(), st);
                    }
                    return true;
                }
                case SUBSCRIBE_HA_SERVICES:
                    servicesSubscribed = true;
                    log.log(peer + " lets this screen perform Home Assistant actions");
                    changed();
                    return true;
                case SUBSCRIBE_LOGS:
                case SUBSCRIBE_HA_STATES:
                case HA_STATE:
                case HA_ACTION_RESP:
                case GET_TIME_RESP:
                    return true;
                case TEXT_CMD:
                case NUMBER_CMD:
                case SWITCH_CMD:
                case SELECT_CMD:
                case BUTTON_CMD:
                case TIME_CMD: {
                    Pb.Field k = Pb.first(f, 1);
                    if (k == null) return true;
                    Entity target = null;
                    synchronized (EspDevice.this) {
                        for (Entity e : entities) if (e.key() == (int) k.varint) target = e;
                    }
                    if (target != null) target.command(f);
                    return true;
                }
                case EXECUTE_SERVICE:
                    execute(f);
                    return true;
                case SET_KEY_REQ: {
                    Pb.Field kf = Pb.first(f, 1);
                    boolean ok = setKeyFromHomeAssistant(kf == null ? "" : kf.text(), noise != null);
                    send(SET_KEY_RESP, new Pb.Out().bool(1, ok));
                    return true;
                }
                default:
                    return true;
            }
        }

        void execute(List<Pb.Field> f) {
            Pb.Field k = Pb.first(f, 1);
            Action action = null;
            synchronized (EspDevice.this) {
                for (Action a : actions) if (k != null && a.key() == (int) k.varint) action = a;
            }
            Pb.Field idf = Pb.first(f, 3), wants = Pb.first(f, 4);
            int callId = idf == null ? 0 : (int) idf.varint;
            boolean wantsData = wants != null && wants.varint != 0;
            // Home Assistant numbers every call of an action that may answer and waits for its status, also when it asked
            // for no data (an automation without response_variable): a numbered call always gets its answer.
            boolean answer = callId != 0 || wantsData;
            if (action == null) {
                if (answer) send(EXECUTE_SERVICE_RESP, new Pb.Out().varint(1, callId).string(3, "unknown action"));
                return;
            }
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            int index = 0;
            for (Pb.Field arg : f) {
                if (arg.number != 2 || index >= action.argNames.length) continue;
                List<Pb.Field> a = Pb.parse(arg.data);
                Object v;
                switch (action.argTypes[index]) {
                    case ARG_BOOL: {
                        Pb.Field x = Pb.first(a, 1);
                        v = x != null && x.varint != 0;
                        break;
                    }
                    case ARG_INT: {
                        Pb.Field x = Pb.first(a, 5), legacy = Pb.first(a, 2);
                        v = x != null ? x.sint32() : legacy != null ? (int) legacy.varint : 0;
                        break;
                    }
                    case ARG_FLOAT: {
                        Pb.Field x = Pb.first(a, 3);
                        v = x == null ? 0f : x.flt();
                        break;
                    }
                    default: {
                        Pb.Field x = Pb.first(a, 4);
                        v = x == null ? "" : x.text();
                    }
                }
                args.put(action.argNames[index++], v);
            }
            try {
                Object result = action.handler.run(args);
                if (answer) {
                    Pb.Out r = new Pb.Out().varint(1, callId).bool(2, true);
                    if (result != null && wantsData) r.bytes(4, Json.write(result).getBytes(Pb.UTF8));
                    send(EXECUTE_SERVICE_RESP, r);
                }
            } catch (Exception e) {
                log.log("action " + action.name + " failed: " + e);
                if (answer) send(EXECUTE_SERVICE_RESP, new Pb.Out().varint(1, callId).string(3, String.valueOf(e.getMessage())));
            }
        }

        synchronized void send(int type, Pb.Out m) {
            byte[] d = m.toBytes();
            Noise secure = noise;
            if (secure != null) {
                try {
                    secure.write(type, d);
                } catch (IOException e) {
                    close();
                }
                return;
            }
            try {
                byte[] head = new byte[12];
                int n = 0;
                head[n++] = 0;
                n = put(head, n, d.length);
                n = put(head, n, type);
                out.write(head, 0, n);
                out.write(d);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        private int put(byte[] b, int n, long v) {
            while ((v & ~0x7FL) != 0) {
                b[n++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            b[n++] = (byte) v;
            return n;
        }

        void close() {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }
}
