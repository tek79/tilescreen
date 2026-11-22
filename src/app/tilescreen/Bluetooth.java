package app.tilescreen;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.tilescreen.core.BleProxy;

/**
 * The tablet's Bluetooth LE for the proxy (BleProxy.Radio): scanning, and GATT connections Home Assistant asks for.
 * Android 4.3 (API 18) and later; the app makes none of this on an older tablet.
 *
 * Android differs from an ESP32 in a few ways, handled here:
 * - Scans: Android's balanced mode (a quarter of the time) unless Bluetooth scan says Low power or Fast. From Android 6 the scan hears nothing while Location is off.
 *   A scan without filters is restarted every 20 minutes, as Android 7 and later turn a long one into one that only
 *   hears what other apps' scans find.
 * - Addresses: Android doesn't say whether an address is public or random before Android 14; it connects by address
 *   either way, so the advertisements say public then.
 * - Handles: Home Assistant names characteristics and descriptors by their attribute handle. Android 5 and later give
 *   those (the instance ids); where they don't look like handles (Android 4.3 and 4.4), the proxy numbers them itself,
 *   in the order Android found them, which stays the same from one connection to the next.
 * - One operation at a time per device: Android's GATT takes one request and answers it before the next, so requests
 *   queue per device.
 */
@TargetApi(21)
final class Bluetooth implements BleProxy.Radio {
    static final long RESCAN_MS = 20 * 60 * 1000L;
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final App app;
    private volatile BleProxy proxy;
    private final BluetoothAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean scanning;
    /** Bluetooth scan: 0 low power (a tenth of the time), 1 balanced (a quarter), 2 fast (all the time). */
    private volatile int mode = 1;
    private volatile String problem = "";
    private Object scanCallback;
    private final Map<Long, Link> links = new HashMap<Long, Link>();
    private final Map<Long, Integer> pairing = new HashMap<Long, Integer>();

    /** Whether this tablet can be a proxy: Android 4.3 or later with Bluetooth LE. */
    static boolean usable(Context c) {
        return Build.VERSION.SDK_INT >= 18
                && c.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)
                && ((BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter() != null;
    }

    Bluetooth(App app) {
        this.app = app;
        this.adapter = ((BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        app.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(i.getAction())) adapterState(i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0));
                else bond(i);
            }
        }, f);
    }

    /** Bluetooth scan changed: a scan that runs starts again in the new mode (Android 5 and later have modes). */
    void mode(int m) {
        if (m == mode) return;
        mode = m;
        boolean was;
        synchronized (this) { was = scanning; }
        if (!was) return;
        app.log("bluetooth: scanning " + (m == 0 ? "in low power" : m == 2 ? "fast" : "balanced"));
        stopScan();
        BleProxy p = proxy;
        if (startScan()) p.scanning(true); else p.scanning(false);
    }

    /** The proxy it answers (the screen's; a restarted screen brings a new one). */
    void setProxy(BleProxy p) { proxy = p; }

    /** The radio's own address, where Android still tells it (before Android 6, or in Settings until 8); else empty. */
    String mac() {
        try {
            String a = adapter.getAddress();
            if (a != null && !a.equals("02:00:00:00:00:00")) return a.toUpperCase(java.util.Locale.US);
            a = Settings.Secure.getString(app.getContentResolver(), "bluetooth_address");
            if (a != null && a.length() == 17) return a.toUpperCase(java.util.Locale.US);
        } catch (Throwable ignored) {
            // not allowed on this Android
        }
        return "";
    }

    // ----- scanning -----

    public synchronized boolean startScan() {
        if (scanning) return true;
        if (!adapter.isEnabled()) {
            // The proxy is on: Bluetooth comes on with it, as the switch turns Bluetooth on on an ESP32 screen.
            app.log("bluetooth: turning Bluetooth on");
            try { adapter.enable(); } catch (Throwable t) { app.log("bluetooth: can't turn it on (" + t + ")"); }
            problem = "Bluetooth is off";
            return false; // adapterState starts the scan once it is on
        }
        if (Build.VERSION.SDK_INT >= 23 && !locationOn()) {
            if (!turnLocationOn()) {
                problem = "Location is off";
                return false;
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                BluetoothLeScanner s = adapter.getBluetoothLeScanner();
                if (s == null) { problem = "Bluetooth is off"; return false; }
                int mode = this.mode == 0 ? ScanSettings.SCAN_MODE_LOW_POWER : this.mode == 2 ? ScanSettings.SCAN_MODE_LOW_LATENCY
                        : ScanSettings.SCAN_MODE_BALANCED;
                ScanSettings.Builder b = new ScanSettings.Builder().setScanMode(mode).setReportDelay(0);
                if (Build.VERSION.SDK_INT >= 23) b.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
                ScanCallback cb = new ScanCallback() {
                    public void onScanResult(int type, ScanResult r) { heard(r); }
                    public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) heard(r); }
                    public void onScanFailed(int code) {
                        app.log("bluetooth: the scan failed (" + code + ")");
                        synchronized (Bluetooth.this) { scanning = false; problem = "The scan failed (" + code + ")"; }
                        proxy.scanning(false);
                    }
                };
                s.startScan(null, b.build(), cb);
                scanCallback = cb;
            } else {
                BluetoothAdapter.LeScanCallback cb = new BluetoothAdapter.LeScanCallback() {
                    public void onLeScan(BluetoothDevice d, int rssi, byte[] record) {
                        proxy.advertisement(BleProxy.address(d.getAddress()), rssi, 0, trim(record));
                    }
                };
                if (!adapter.startLeScan(cb)) { problem = "The scan didn't start"; return false; }
                scanCallback = cb;
            }
        } catch (Throwable t) {
            problem = "The scan didn't start (" + t.getClass().getSimpleName() + ")";
            app.log("bluetooth: " + problem + ": " + t);
            return false;
        }
        scanning = true;
        problem = "";
        app.log("bluetooth: listening (" + (mode == 0 ? "low power" : mode == 2 ? "fast" : "balanced") + ")");
        main.removeCallbacks(rescan);
        main.postDelayed(rescan, RESCAN_MS);
        return true;
    }

    private final Runnable rescan = new Runnable() {
        public void run() {
            synchronized (Bluetooth.this) {
                if (!scanning) return;
                stopScan();
            }
            if (startScan()) proxy.scanning(true); else proxy.scanning(false);
        }
    };

    @SuppressWarnings("deprecation")
    public synchronized void stopScan() {
        main.removeCallbacks(rescan);
        if (!scanning) return;
        scanning = false;
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                BluetoothLeScanner s = adapter.getBluetoothLeScanner();
                if (s != null && scanCallback != null) s.stopScan((ScanCallback) scanCallback);
            } else if (scanCallback != null) {
                adapter.stopLeScan((BluetoothAdapter.LeScanCallback) scanCallback);
            }
        } catch (Throwable ignored) {
            // Bluetooth already off
        }
        scanCallback = null;
    }

    public String problem() { return problem; }

    boolean scanning() { return scanning; }

    private void heard(ScanResult r) {
        ScanRecord rec = r.getScanRecord();
        byte[] data = rec == null ? new byte[0] : trim(rec.getBytes());
        proxy.advertisement(BleProxy.address(r.getDevice().getAddress()), r.getRssi(), addressType(r.getDevice()), data);
    }

    /** The advertisement and scan response as they came: Android pads them with zeros to 62 bytes. */
    static byte[] trim(byte[] raw) {
        if (raw == null) return new byte[0];
        int at = 0;
        while (at < raw.length) {
            int len = raw[at] & 0xFF;
            if (len == 0 || at + 1 + len > raw.length) break;
            at += 1 + len;
        }
        byte[] out = new byte[at];
        System.arraycopy(raw, 0, out, 0, at);
        return out;
    }

    /** Public 0 or random 1, where Android 14 and later say (BluetoothDevice.getAddressType); else public. */
    private static int addressType(BluetoothDevice d) {
        if (Build.VERSION.SDK_INT < 34) return 0;
        try {
            Object t = BluetoothDevice.class.getMethod("getAddressType").invoke(d);
            return ((Integer) t) == 1 ? 1 : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    private boolean locationOn() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                android.location.LocationManager lm = (android.location.LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
                return (Boolean) lm.getClass().getMethod("isLocationEnabled").invoke(lm);
            }
            return Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** With root, Location comes on (battery saving: no GPS), which Android requires for Bluetooth scans. */
    private boolean turnLocationOn() {
        if (!Root.available()) {
            app.log("bluetooth: Android hears no Bluetooth devices while Location is off; turn it on in Android's settings");
            return false;
        }
        boolean ok = Build.VERSION.SDK_INT >= 29 ? Root.run("cmd location set-location-enabled true")
                : Root.run("settings put secure location_mode 2");
        app.log("bluetooth: " + (ok && locationOn() ? "turned Location on with root, for the scan" : "couldn't turn Location on"));
        return locationOn();
    }

    private void adapterState(int state) {
        if (state == BluetoothAdapter.STATE_ON) {
            app.log("bluetooth: Bluetooth is on");
            proxy.radioReady();
        } else if (state == BluetoothAdapter.STATE_OFF) {
            synchronized (this) { scanning = false; scanCallback = null; problem = "Bluetooth is off"; }
            app.log("bluetooth: Bluetooth is off");
            List<Link> gone;
            synchronized (links) { gone = new ArrayList<Link>(links.values()); links.clear(); }
            for (Link l : gone) {
                l.close();
                proxy.disconnected(l.address, BleProxy.NOT_CONNECTED);
            }
            proxy.scanning(false);
        }
    }

    // ----- connections -----

    public void connect(long address, int addressType) {
        Link l = new Link(address);
        synchronized (links) {
            Link old = links.put(address, l);
            if (old != null) old.close();
        }
        l.open();
    }

    public void disconnect(long address) {
        Link l;
        synchronized (links) { l = links.get(address); }
        if (l == null) {
            proxy.disconnected(address, 0);
            return;
        }
        l.leave();
    }

    public List<BleProxy.Service> services(long address) {
        Link l = link(address);
        return l == null ? null : l.services;
    }

    public void read(final long a, final int handle) {
        final Link l = link(a);
        if (l == null) { proxy.readDone(a, handle, null, BleProxy.NOT_CONNECTED); return; }
        l.queue(new Op(handle) {
            boolean start() {
                BluetoothGattCharacteristic c = l.chars.get(handle);
                return c != null && l.gatt.readCharacteristic(c);
            }
            void failed() { proxy.readDone(a, handle, null, BleProxy.NOT_CONNECTED); }
        });
    }

    public void write(final long a, final int handle, final byte[] data, final boolean response) {
        final Link l = link(a);
        if (l == null) { proxy.writeDone(a, handle, BleProxy.NOT_CONNECTED, response); return; }
        l.queue(new Op(handle) {
            boolean start() {
                BluetoothGattCharacteristic c = l.chars.get(handle);
                if (c == null) return false;
                c.setWriteType(response ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                c.setValue(data);
                answer = response;
                return l.gatt.writeCharacteristic(c);
            }
            void failed() { proxy.writeDone(a, handle, BleProxy.NOT_CONNECTED, response); }
        });
    }

    public void readDescriptor(final long a, final int handle) {
        final Link l = link(a);
        if (l == null) { proxy.readDone(a, handle, null, BleProxy.NOT_CONNECTED); return; }
        l.queue(new Op(handle) {
            boolean start() {
                BluetoothGattDescriptor d = l.descriptors.get(handle);
                return d != null && l.gatt.readDescriptor(d);
            }
            void failed() { proxy.readDone(a, handle, null, BleProxy.NOT_CONNECTED); }
        });
    }

    public void writeDescriptor(final long a, final int handle, final byte[] data) {
        final Link l = link(a);
        if (l == null) { proxy.writeDone(a, handle, BleProxy.NOT_CONNECTED, true); return; }
        l.queue(new Op(handle) {
            boolean start() {
                BluetoothGattDescriptor d = l.descriptors.get(handle);
                if (d == null) return false;
                // The configuration descriptor: Android also has to know, or it drops what the device sends.
                if (CCCD.equals(d.getUuid())) {
                    boolean on = data.length > 0 && (data[0] & 3) != 0;
                    l.gatt.setCharacteristicNotification(d.getCharacteristic(), on);
                }
                d.setValue(data);
                answer = true;
                return l.gatt.writeDescriptor(d);
            }
            void failed() { proxy.writeDone(a, handle, BleProxy.NOT_CONNECTED, true); }
        });
    }

    public void notify(long a, int handle, boolean on) {
        // Home Assistant writes the configuration descriptor itself afterwards (connections v3, remote caching): here
        // Android only has to pass the device's notifications on.
        Link l = link(a);
        BluetoothGattCharacteristic c = l == null ? null : l.chars.get(handle);
        if (c == null || !l.gatt.setCharacteristicNotification(c, on)) {
            proxy.notifyDone(a, handle, BleProxy.NOT_CONNECTED);
            return;
        }
        proxy.notifyDone(a, handle, 0);
    }

    public void pair(long a) {
        BluetoothDevice d = adapter.getRemoteDevice(BleProxy.text(a));
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
            proxy.paired(a, true, 0);
            return;
        }
        boolean started = Build.VERSION.SDK_INT >= 19 && d.createBond();
        if (!started) proxy.paired(a, false, BleProxy.NOT_CONNECTED);
        else synchronized (pairing) { pairing.put(a, 1); }
    }

    public void unpair(long a) {
        BluetoothDevice d = adapter.getRemoteDevice(BleProxy.text(a));
        boolean ok = d.getBondState() == BluetoothDevice.BOND_NONE;
        if (!ok) {
            try {
                ok = (Boolean) d.getClass().getMethod("removeBond").invoke(d);
            } catch (Throwable t) {
                ok = false;
            }
        }
        proxy.unpaired(a, ok, ok ? 0 : BleProxy.NOT_CONNECTED);
    }

    public void clearCache(long a) {
        // Android keeps a device's services per bond; with a connection open, its hidden refresh() forgets them.
        Link l = link(a);
        boolean ok = true;
        if (l != null) {
            try {
                ok = (Boolean) l.gatt.getClass().getMethod("refresh").invoke(l.gatt);
            } catch (Throwable t) {
                ok = false;
            }
        }
        proxy.cacheCleared(a, ok, ok ? 0 : BleProxy.NOT_CONNECTED);
    }

    private void bond(Intent i) {
        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (d == null) return;
        long a = BleProxy.address(d.getAddress());
        synchronized (pairing) { if (pairing.get(a) == null) return; }
        int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
        if (state == BluetoothDevice.BOND_BONDING) return;
        synchronized (pairing) { pairing.remove(a); }
        proxy.paired(a, state == BluetoothDevice.BOND_BONDED, state == BluetoothDevice.BOND_BONDED ? 0 : BleProxy.NOT_CONNECTED);
    }

    private Link link(long a) {
        synchronized (links) {
            Link l = links.get(a);
            return l != null && l.ready ? l : null;
        }
    }

    /** One request to a device, answered by a GATT callback. */
    private abstract static class Op {
        final int handle;
        boolean answer;

        Op(int handle) { this.handle = handle; }

        abstract boolean start();
        abstract void failed();
    }

    /** One device Home Assistant connected to through the tablet. */
    private final class Link extends BluetoothGattCallback {
        final long address;
        BluetoothGatt gatt;
        volatile boolean ready, leaving, closed;
        int mtu = 23;
        List<BleProxy.Service> services;
        final Map<Integer, BluetoothGattCharacteristic> chars = new HashMap<Integer, BluetoothGattCharacteristic>();
        final Map<Integer, BluetoothGattDescriptor> descriptors = new HashMap<Integer, BluetoothGattDescriptor>();
        final Map<BluetoothGattCharacteristic, Integer> charHandles = new HashMap<BluetoothGattCharacteristic, Integer>();
        final Map<BluetoothGattDescriptor, Integer> descriptorHandles = new HashMap<BluetoothGattDescriptor, Integer>();
        private final ArrayDeque<Op> ops = new ArrayDeque<Op>();
        private Op current;

        Link(long address) { this.address = address; }

        void open() {
            final BluetoothDevice d = adapter.getRemoteDevice(BleProxy.text(address));
            // On the main thread: some Android versions' GATT wants that for connecting.
            main.post(new Runnable() {
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 23) gatt = d.connectGatt(app, false, Link.this, BluetoothDevice.TRANSPORT_LE);
                        else if (Build.VERSION.SDK_INT >= 21) gatt = connectLe(d);
                        else gatt = d.connectGatt(app, false, Link.this);
                    } catch (Throwable t) {
                        app.log("bluetooth: connecting failed (" + t + ")");
                        gatt = null;
                    }
                    if (gatt == null) end(BleProxy.NOT_CONNECTED);
                }
            });
        }

        /** Android 5's connectGatt with the LE transport, hidden until Android 6. */
        private BluetoothGatt connectLe(BluetoothDevice d) {
            try {
                Method m = d.getClass().getMethod("connectGatt", Context.class, boolean.class, BluetoothGattCallback.class, int.class);
                return (BluetoothGatt) m.invoke(d, app, false, this, 2);
            } catch (Throwable t) {
                return d.connectGatt(app, false, this);
            }
        }

        void leave() {
            leaving = true;
            BluetoothGatt g = gatt;
            if (g == null || !ready) {
                end(0);
                return;
            }
            g.disconnect();
            // Some stacks never answer a disconnect: give it three seconds.
            main.postDelayed(new Runnable() {
                public void run() { end(0); }
            }, 3000);
        }

        void close() {
            closed = true;
            BluetoothGatt g = gatt;
            if (g != null) {
                try { g.close(); } catch (Throwable ignored) {}
            }
        }

        /** Gone: closed, out of the list, and Home Assistant told (once). */
        void end(int error) {
            synchronized (this) {
                if (closed) return;
                closed = true;
                ready = false;
            }
            BluetoothGatt g = gatt;
            if (g != null) {
                try { g.close(); } catch (Throwable ignored) {}
            }
            synchronized (links) { if (links.get(address) == this) links.remove(address); }
            synchronized (this) {
                for (Op op : ops) op.failed();
                ops.clear();
                if (current != null) current.failed();
                current = null;
            }
            proxy.disconnected(address, error);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (closed) return;
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (leaving) { g.disconnect(); return; }
                if (Build.VERSION.SDK_INT >= 21 && g.requestMtu(517)) return; // then onMtuChanged
                if (!g.discoverServices()) end(BleProxy.NOT_CONNECTED);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                end(leaving ? 0 : status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int m, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m;
            if (!ready && !g.discoverServices()) end(BleProxy.NOT_CONNECTED);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                end(status);
                return;
            }
            services = describe(g.getServices());
            ready = true;
            proxy.connected(address, mtu);
        }

        /** The services as Home Assistant names them: 128-bit UUIDs and attribute handles. */
        private List<BleProxy.Service> describe(List<BluetoothGattService> found) {
            boolean real = Build.VERSION.SDK_INT >= 21;
            java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
            List<BleProxy.Service> out = build(found, real, seen);
            if (real && seen.contains(-1)) {
                app.log("bluetooth: Android gave no usable handles here; numbering them");
                out = build(found, false, new java.util.HashSet<Integer>());
            }
            return out;
        }

        private List<BleProxy.Service> build(List<BluetoothGattService> found, boolean real, java.util.Set<Integer> seen) {
            chars.clear();
            descriptors.clear();
            charHandles.clear();
            descriptorHandles.clear();
            List<BleProxy.Service> out = new ArrayList<BleProxy.Service>();
            int next = 1;
            for (BluetoothGattService s : found) {
                int sh = real ? s.getInstanceId() : next++;
                BleProxy.Service bs = new BleProxy.Service(s.getUuid().getMostSignificantBits(), s.getUuid().getLeastSignificantBits(), sh);
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int ch = real ? c.getInstanceId() : next++;
                    if (real && (ch <= 0 || !seen.add(ch))) seen.add(-1);
                    chars.put(ch, c);
                    charHandles.put(c, ch);
                    BleProxy.Characteristic bc = new BleProxy.Characteristic(c.getUuid().getMostSignificantBits(),
                            c.getUuid().getLeastSignificantBits(), ch, c.getProperties());
                    int k = 0;
                    for (BluetoothGattDescriptor d : c.getDescriptors()) {
                        int dh = real ? descriptorHandle(d, ch + 1 + k) : next++;
                        k++;
                        if (real && (dh <= 0 || !seen.add(dh))) seen.add(-1);
                        descriptors.put(dh, d);
                        descriptorHandles.put(d, dh);
                        bc.descriptors.add(new BleProxy.Descriptor(d.getUuid().getMostSignificantBits(), d.getUuid().getLeastSignificantBits(), dh));
                    }
                    bs.characteristics.add(bc);
                }
                out.add(bs);
            }
            return out;
        }

        /** A descriptor's handle: Android's hidden getInstanceId where it has one, else the one after its characteristic's. */
        private int descriptorHandle(BluetoothGattDescriptor d, int guess) {
            try {
                return (Integer) d.getClass().getMethod("getInstanceId").invoke(d);
            } catch (Throwable t) {
                return guess;
            }
        }

        synchronized void queue(Op op) {
            ops.add(op);
            if (current == null) next();
        }

        private synchronized void next() {
            while (current == null && !ops.isEmpty()) {
                Op op = ops.poll();
                boolean started;
                try {
                    started = ready && op.start();
                } catch (Throwable t) {
                    started = false;
                }
                if (started) current = op;
                else op.failed();
            }
        }

        private synchronized Op done() {
            Op op = current;
            current = null;
            return op;
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Op op = done();
            Integer h = charHandles.get(c);
            proxy.readDone(address, h == null ? (op == null ? 0 : op.handle) : h, c.getValue(), status);
            next();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Op op = done();
            Integer h = charHandles.get(c);
            proxy.writeDone(address, h == null ? (op == null ? 0 : op.handle) : h, status, op != null && op.answer);
            next();
        }

        @Override
        public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Op op = done();
            Integer h = descriptorHandles.get(d);
            proxy.readDone(address, h == null ? (op == null ? 0 : op.handle) : h, d.getValue(), status);
            next();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Op op = done();
            Integer h = descriptorHandles.get(d);
            proxy.writeDone(address, h == null ? (op == null ? 0 : op.handle) : h, status, true);
            next();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            Integer h = charHandles.get(c);
            if (h != null) proxy.notification(address, h, c.getValue());
        }
    }
}
