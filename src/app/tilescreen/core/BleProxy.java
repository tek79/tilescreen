package app.tilescreen.core;

import java.util.ArrayList;
import java.util.List;

/**
 * ESPHome's Bluetooth proxy on the tablet (Tessera's bt_proxy plugin 0.2.0: `bluetooth_proxy: active: true`). Home
 * Assistant subscribes to the advertisements the tablet hears and gets them raw, in batches, as from an ESP32 proxy
 * (BluetoothLERawAdvertisementsResponse); it may also connect to a device through the tablet, up to three at a time, and
 * read, write and subscribe to its characteristics. This class is the protocol (bluetooth_proxy.cpp's rules: the slots,
 * connections free, the scanner's state, the answers Home Assistant waits for) and nothing of Android: the app hands it
 * a Radio, and the Radio tells it what the air said.
 */
public final class BleProxy {
    // api.proto message types.
    public static final int SUBSCRIBE_ADS = 66, DEVICE_REQUEST = 68, CONNECTION = 69, GET_SERVICES = 70, SERVICES = 71,
            SERVICES_DONE = 72, READ = 73, READ_RESPONSE = 74, WRITE = 75, READ_DESCRIPTOR = 76, WRITE_DESCRIPTOR = 77,
            NOTIFY = 78, NOTIFY_DATA = 79, SUBSCRIBE_FREE = 80, FREE = 81, GATT_ERROR = 82, WRITE_RESPONSE = 83,
            NOTIFY_RESPONSE = 84, PAIRING = 85, UNPAIRING = 86, UNSUBSCRIBE_ADS = 87, CLEAR_CACHE = 88, RAW_ADS = 93,
            SCANNER_STATE = 126, SCANNER_SET_MODE = 127;

    // BluetoothProxyFeature.
    static final int PASSIVE_SCAN = 1, ACTIVE_CONNECTIONS = 2, REMOTE_CACHING = 4, PAIRING_FEATURE = 8, CACHE_CLEARING = 16,
            RAW_ADVERTISEMENTS = 32, STATE_AND_MODE = 64;
    /** What this proxy does: scanning with raw advertisements and its state, connections with pairing and cache clearing. */
    public static final int FEATURES = PASSIVE_SCAN | RAW_ADVERTISEMENTS | STATE_AND_MODE | ACTIVE_CONNECTIONS
            | REMOTE_CACHING | PAIRING_FEATURE | CACHE_CLEARING;

    // BluetoothDeviceRequestType.
    static final int CONNECT = 0, DISCONNECT = 1, PAIR = 2, UNPAIR = 3, CONNECT_WITH_CACHE = 4, CONNECT_WITHOUT_CACHE = 5,
            CLEAR = 6;
    // BluetoothScannerState and mode.
    public static final int IDLE = 0, STARTING = 1, RUNNING = 2, FAILED = 3, STOPPING = 4, STOPPED = 5;
    static final int MODE_ACTIVE = 1;

    /** As many connections as Tessera's proxy (ESPHome's default of three). */
    public static final int LIMIT = 3;
    /** Advertisements per message, and how often a part-filled batch goes (bluetooth_proxy.cpp's 100 ms tick). */
    static final int BATCH = 16;
    static final long FLUSH_MS = 100;
    /** GATT's generic error (0x85, ESP_GATT_ERROR and Android's GATT_ERROR alike): a device the tablet isn't linked to. */
    public static final int NOT_CONNECTED = 0x85;

    /** Where a message goes: the subscribed Home Assistant connection (EspDevice), in order, from any thread. */
    public interface Sender { void send(int type, Pb.Out m); }

    /** The tablet's Bluetooth (the app's Android code): every call returns at once and answers through the proxy. */
    public interface Radio {
        /** Starts hearing advertisements; false when it can't (Bluetooth off, no Location, no Bluetooth LE). */
        boolean startScan();
        void stopScan();
        /** Why it can't, for the log and the status: empty when nothing is wrong. */
        String problem();
        void connect(long address, int addressType);
        void disconnect(long address);
        /** The services found when it connected: null when it isn't connected. */
        List<Service> services(long address);
        void read(long address, int handle);
        void write(long address, int handle, byte[] data, boolean response);
        void readDescriptor(long address, int handle);
        void writeDescriptor(long address, int handle, byte[] data);
        void notify(long address, int handle, boolean on);
        void pair(long address);
        void unpair(long address);
        void clearCache(long address);
    }

    public static final class Descriptor {
        public final long uuidHigh, uuidLow;
        public final int handle;

        public Descriptor(long uuidHigh, long uuidLow, int handle) {
            this.uuidHigh = uuidHigh;
            this.uuidLow = uuidLow;
            this.handle = handle;
        }
    }

    public static final class Characteristic {
        public final long uuidHigh, uuidLow;
        public final int handle, properties;
        public final List<Descriptor> descriptors = new ArrayList<Descriptor>();

        public Characteristic(long uuidHigh, long uuidLow, int handle, int properties) {
            this.uuidHigh = uuidHigh;
            this.uuidLow = uuidLow;
            this.handle = handle;
            this.properties = properties;
        }
    }

    public static final class Service {
        public final long uuidHigh, uuidLow;
        public final int handle;
        public final List<Characteristic> characteristics = new ArrayList<Characteristic>();

        public Service(long uuidHigh, long uuidLow, int handle) {
            this.uuidHigh = uuidHigh;
            this.uuidLow = uuidLow;
            this.handle = handle;
        }
    }

    private final EspDevice.Logger log;
    private volatile Radio radio;
    private volatile Sender subscriber, freeSubscriber;
    private volatile boolean enabled;
    private volatile int state = IDLE;
    /** The three slots: a device's address, 0 when free; `linked` once it is connected. */
    private final long[] slots = new long[LIMIT];
    private final boolean[] linked = new boolean[LIMIT];
    private final List<byte[]> batch = new ArrayList<byte[]>();
    private Thread flusher;
    /** Advertisements heard and sent, for the log. */
    private volatile long heard;

    public BleProxy(EspDevice.Logger log) { this.log = log; }

    public void setRadio(Radio r) { radio = r; }

    /** The Bluetooth proxy switch: on scans while Home Assistant listens; off stops and lets every device go. */
    public void enable(boolean on) {
        enabled = on;
        if (on) {
            if (subscriber != null) startScan();
        } else {
            stopScan();
            dropAll();
        }
        sendState();
    }

    public boolean enabled() { return enabled; }

    /** The scanner's state (BluetoothScannerState), for the status. */
    public int state() { return state; }

    public long heard() { return heard; }

    // ----- Home Assistant -----

    /** A message for the proxy from `from` (EspDevice's connection to Home Assistant). */
    public void message(int type, List<Pb.Field> f, Sender from) {
        switch (type) {
            case SUBSCRIBE_ADS: {
                // The newest subscriber wins, as on an ESP32 (a stale connection may still hold the slot).
                if (subscriber != from) {
                    if (subscriber != null) log.log("bluetooth: a new subscriber replaces the old one");
                    subscriber = from;
                    dropAll();
                }
                log.log("bluetooth: Home Assistant listens");
                if (enabled) startScan();
                sendState();
                break;
            }
            case UNSUBSCRIBE_ADS:
                if (subscriber == from) {
                    subscriber = null;
                    stopScan();
                    log.log("bluetooth: Home Assistant stopped listening");
                }
                break;
            case SUBSCRIBE_FREE:
                freeSubscriber = from;
                sendFree();
                break;
            case SCANNER_SET_MODE:
                // Android scans actively only; say so.
                sendState();
                break;
            case DEVICE_REQUEST: deviceRequest(f, from); break;
            case GET_SERVICES: services(address(f), from); break;
            case READ: {
                long a = address(f);
                int h = (int) num(f, 2);
                if (!check(a, h, from)) break;
                radio.read(a, h);
                break;
            }
            case WRITE: {
                long a = address(f);
                int h = (int) num(f, 2);
                if (!check(a, h, from)) break;
                radio.write(a, h, data(f, 4), num(f, 3) != 0);
                break;
            }
            case READ_DESCRIPTOR: {
                long a = address(f);
                int h = (int) num(f, 2);
                if (!check(a, h, from)) break;
                radio.readDescriptor(a, h);
                break;
            }
            case WRITE_DESCRIPTOR: {
                long a = address(f);
                int h = (int) num(f, 2);
                if (!check(a, h, from)) break;
                radio.writeDescriptor(a, h, data(f, 3));
                break;
            }
            case NOTIFY: {
                long a = address(f);
                int h = (int) num(f, 2);
                if (!check(a, h, from)) break;
                radio.notify(a, h, num(f, 3) != 0);
                break;
            }
            default:
                break;
        }
    }

    /** The Home Assistant connection `from` went: its subscription and its devices go with it. */
    public void gone(Sender from) {
        if (freeSubscriber == from) freeSubscriber = null;
        if (subscriber == from) {
            subscriber = null;
            stopScan();
            dropAll();
        }
    }

    private void deviceRequest(List<Pb.Field> f, Sender from) {
        long a = address(f);
        int kind = (int) num(f, 2);
        Radio r = radio;
        switch (kind) {
            case CONNECT:
            case CONNECT_WITH_CACHE:
            case CONNECT_WITHOUT_CACHE: {
                if (!enabled || r == null) {
                    reply(from, CONNECTION, connection(a, false, 0, NOT_CONNECTED));
                    return;
                }
                int before = slot(a, false);
                if (before >= 0 && !linked[before]) return; // a repeat while it connects: ESPHome ignores it too
                int slot = slot(a, true);
                if (slot < 0) {
                    log.log("bluetooth: no free connection for " + text(a));
                    reply(from, CONNECTION, connection(a, false, 0, 0));
                    sendFree();
                    return;
                }
                if (linked[slot]) {
                    reply(from, CONNECTION, connection(a, true, 0, 0));
                    sendFree();
                    return;
                }
                log.log("bluetooth: connecting to " + text(a));
                r.connect(a, (int) num(f, 4));
                sendFree();
                return;
            }
            case DISCONNECT: {
                int slot = slot(a, false);
                if (slot < 0 || r == null) {
                    reply(from, CONNECTION, connection(a, false, 0, 0));
                    sendFree();
                    return;
                }
                r.disconnect(a);
                return;
            }
            case PAIR:
                if (slot(a, false) < 0 || r == null) reply(from, PAIRING, result(a, false, NOT_CONNECTED));
                else r.pair(a);
                return;
            case UNPAIR:
                if (r == null) reply(from, UNPAIRING, result(a, false, NOT_CONNECTED));
                else r.unpair(a);
                return;
            case CLEAR:
                if (r == null) reply(from, CLEAR_CACHE, result(a, false, NOT_CONNECTED));
                else r.clearCache(a);
                return;
            default:
                return;
        }
    }

    private void services(long a, Sender from) {
        Radio r = radio;
        List<Service> list = r == null || slot(a, false) < 0 ? null : r.services(a);
        if (list == null) {
            reply(from, GATT_ERROR, new Pb.Out().varint(1, a).varint(3, NOT_CONNECTED));
            return;
        }
        // One service a message, as ESPHome sends them, then done.
        for (Service s : list) {
            Pb.Out sm = new Pb.Out().force(1, s.uuidHigh).force(1, s.uuidLow).varint(2, s.handle);
            for (Characteristic c : s.characteristics) {
                Pb.Out cm = new Pb.Out().force(1, c.uuidHigh).force(1, c.uuidLow).varint(2, c.handle).varint(3, c.properties);
                for (Descriptor d : c.descriptors)
                    cm.message(4, new Pb.Out().force(1, d.uuidHigh).force(1, d.uuidLow).varint(2, d.handle));
                sm.message(3, cm);
            }
            reply(from, SERVICES, new Pb.Out().varint(1, a).message(2, sm));
        }
        reply(from, SERVICES_DONE, new Pb.Out().varint(1, a));
    }

    private boolean check(long a, int handle, Sender from) {
        if (radio != null && slot(a, false) >= 0 && linked[slot(a, false)]) return true;
        reply(from, GATT_ERROR, new Pb.Out().varint(1, a).varint(2, handle).varint(3, NOT_CONNECTED));
        return false;
    }

    // ----- the radio's news -----

    /** An advertisement heard: `data` is its advertising data and scan response, as the air carried them. */
    public void advertisement(long address, int rssi, int addressType, byte[] data) {
        if (subscriber == null || !enabled) return;
        heard++;
        Pb.Out ad = new Pb.Out().varint(1, address).sint(2, rssi).varint(3, addressType).bytes(4, data);
        byte[] one = ad.toBytes();
        synchronized (batch) {
            batch.add(one);
            if (batch.size() >= BATCH) flush();
        }
    }

    public void connected(long a, int mtu) {
        int slot = slot(a, false);
        if (slot < 0) {
            // Home Assistant let it go meanwhile.
            Radio r = radio;
            if (r != null) r.disconnect(a);
            return;
        }
        linked[slot] = true;
        log.log("bluetooth: connected to " + text(a) + " (MTU " + mtu + ")");
        send(CONNECTION, connection(a, true, mtu, 0));
        sendFree();
    }

    /** Not connected (any more): a connection that failed, ended or was let go; `error` 0 for a plain disconnect. */
    public void disconnected(long a, int error) {
        int slot = slot(a, false);
        if (slot >= 0) {
            slots[slot] = 0;
            linked[slot] = false;
        }
        log.log("bluetooth: " + text(a) + (error == 0 ? " disconnected" : " disconnected (error " + error + ")"));
        send(CONNECTION, connection(a, false, 0, error));
        sendFree();
    }

    public void readDone(long a, int handle, byte[] value, int error) {
        if (error != 0) gattError(a, handle, error);
        else send(READ_RESPONSE, new Pb.Out().varint(1, a).varint(2, handle).bytes(3, value));
    }

    public void writeDone(long a, int handle, int error, boolean answer) {
        if (error != 0) gattError(a, handle, error);
        else if (answer) send(WRITE_RESPONSE, new Pb.Out().varint(1, a).varint(2, handle));
    }

    public void notifyDone(long a, int handle, int error) {
        if (error != 0) gattError(a, handle, error);
        else send(NOTIFY_RESPONSE, new Pb.Out().varint(1, a).varint(2, handle));
    }

    public void notification(long a, int handle, byte[] value) {
        send(NOTIFY_DATA, new Pb.Out().varint(1, a).varint(2, handle).bytes(3, value));
    }

    public void paired(long a, boolean ok, int error) { send(PAIRING, result(a, ok, error)); }

    public void unpaired(long a, boolean ok, int error) { send(UNPAIRING, result(a, ok, error)); }

    public void cacheCleared(long a, boolean ok, int error) { send(CLEAR_CACHE, result(a, ok, error)); }

    /** Bluetooth came on (the radio turned it on for the proxy, or someone did): listen again where wanted. */
    public void radioReady() {
        if (enabled && subscriber != null) startScan();
        sendState();
    }

    /** The radio stopped hearing (Bluetooth turned off, the scan failed) or started again. */
    public void scanning(boolean running) {
        state = running ? RUNNING : (enabled && subscriber != null ? FAILED : STOPPED);
        sendState();
    }

    // ----- inside -----

    private void gattError(long a, int handle, int error) {
        send(GATT_ERROR, new Pb.Out().varint(1, a).varint(2, handle).varint(3, error));
    }

    private synchronized int slot(long a, boolean reserve) {
        int free = -1;
        for (int i = 0; i < LIMIT; i++) {
            if (slots[i] == a) return i;
            if (free < 0 && slots[i] == 0) free = i;
        }
        if (!reserve || free < 0) return -1;
        slots[free] = a;
        linked[free] = false;
        return free;
    }

    private void dropAll() {
        Radio r = radio;
        for (int i = 0; i < LIMIT; i++) {
            long a;
            synchronized (this) {
                a = slots[i];
                slots[i] = 0;
                linked[i] = false;
            }
            if (a != 0 && r != null) r.disconnect(a);
        }
        sendFree();
    }

    private void startScan() {
        Radio r = radio;
        if (r == null) return;
        state = STARTING;
        boolean ok = r.startScan();
        state = ok ? RUNNING : FAILED;
        if (!ok) log.log("bluetooth: can't listen (" + r.problem() + ")");
        startFlusher();
    }

    private void stopScan() {
        Radio r = radio;
        if (r != null) r.stopScan();
        state = enabled ? IDLE : STOPPED;
        synchronized (batch) { batch.clear(); }
    }

    private synchronized void startFlusher() {
        if (flusher != null && flusher.isAlive()) return;
        flusher = new Thread(new Runnable() {
            public void run() {
                while (subscriber != null && enabled) {
                    try { Thread.sleep(FLUSH_MS); } catch (InterruptedException e) { return; }
                    synchronized (batch) { flush(); }
                }
            }
        }, "bluetooth-ads");
        flusher.setDaemon(true);
        flusher.start();
    }

    /** Sends what is batched (the caller holds `batch`). */
    private void flush() {
        if (batch.isEmpty()) return;
        Pb.Out m = new Pb.Out();
        for (byte[] ad : batch) m.bytes(1, ad);
        batch.clear();
        send(RAW_ADS, m);
    }

    private void sendState() {
        Sender s = subscriber;
        if (s == null) return;
        int st = !enabled ? STOPPED : state;
        s.send(SCANNER_STATE, new Pb.Out().varint(1, st).varint(2, MODE_ACTIVE).varint(3, MODE_ACTIVE));
    }

    private void sendFree() {
        Sender s = freeSubscriber;
        if (s == null) return;
        Pb.Out m = new Pb.Out();
        int free = 0;
        synchronized (this) {
            for (int i = 0; i < LIMIT; i++) if (slots[i] == 0) free++;
            m.varint(1, enabled ? free : 0).varint(2, LIMIT);
            for (int i = 0; i < LIMIT; i++) if (slots[i] != 0) m.force(3, slots[i]);
        }
        s.send(FREE, m);
    }

    private void send(int type, Pb.Out m) {
        Sender s = subscriber;
        if (s != null) s.send(type, m);
    }

    private static void reply(Sender to, int type, Pb.Out m) { to.send(type, m); }

    private static Pb.Out connection(long a, boolean connected, int mtu, int error) {
        return new Pb.Out().varint(1, a).bool(2, connected).varint(3, mtu).varint(4, error);
    }

    private static Pb.Out result(long a, boolean ok, int error) {
        return new Pb.Out().varint(1, a).bool(2, ok).varint(3, error);
    }

    private static long address(List<Pb.Field> f) { return num(f, 1); }

    private static long num(List<Pb.Field> f, int n) {
        Pb.Field x = Pb.first(f, n);
        return x == null ? 0 : x.varint;
    }

    private static byte[] data(List<Pb.Field> f, int n) {
        Pb.Field x = Pb.first(f, n);
        return x == null || x.data == null ? new byte[0] : x.data;
    }

    /** "AA:BB:CC:DD:EE:FF" from the 48-bit address ESPHome uses. */
    public static String text(long a) {
        StringBuilder s = new StringBuilder(17);
        for (int i = 5; i >= 0; i--) {
            int b = (int) ((a >>> (8 * i)) & 0xFF);
            s.append(Character.forDigit(b >> 4, 16)).append(Character.forDigit(b & 15, 16));
            if (i > 0) s.append(':');
        }
        return s.toString().toUpperCase(java.util.Locale.US);
    }

    /** The 48-bit address of "AA:BB:CC:DD:EE:FF"; 0 when it isn't one. */
    public static long address(String text) {
        if (text == null) return 0;
        String hex = text.replace(":", "");
        if (hex.length() != 12) return 0;
        try {
            return Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
