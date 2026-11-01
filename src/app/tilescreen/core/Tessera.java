package app.tilescreen.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The screen side of Tessera's screen protocol, version 2: the Tessera add-on hands this screen
 * its pages and tiles through the screen_message action, then keeps their states fresh.
 *
 * Ported from libcountertop's pkg/tessera (AGPL-3.0, github.com/ygelfand/libcountertop), which
 * follows the firmware of github.com/MaxGramser/homeassistant_espscreen.
 */
public final class Tessera {
    public static final String FIRMWARE = "0.58.0";
    static final int PROTOCOL = 2;
    public static final String READY = "Ready for tile configuration";
    static final int MAX_PAGES = 32, MAX_TILES = 1024, BAR_ITEMS = 16, WIRE_ITEMS = 6, MOST_PAYLOAD = 4096;
    static final int REFRESH_DEFAULT = 15, REFRESH_LEAST = 5, REFRESH_MOST = 3600;
    static final String[] FEATURES = {"climate_range", "bar_status", "weather_week", "media_rows", "media_library",
            "screensaver", "saver_items", "saver_photos", "clock_faces", "saver_keys",
            "vacuum_card", "pictures", "live", "doorbell", "light_presets", "text_card", "calendar_detail", "vacuum_tile"};

    /**
     * How many camera tiles this screen streams at once (the hello's caps.live_tiles): the tablet's own setting. The add-on
     * refuses one more, and every screen together gets eight streams at most (live_stream.MAX_STREAMS).
     */
    public static volatile int liveTiles = 2;

    // ----- the layout -----

    public static final class BarItem {
        public String kind = "", icon = "", text = "", color = "";
        public long epoch;
        public int weak;
        /** A missed ring's item (`b`): a tap opens that ring again. Until when it stands (`x`, unix time), 0 for always. */
        public boolean doorbell;
        public long until;

        /** Whether it still stands now: a missed ring kept for a while goes by the screen's own clock. */
        public boolean standing() { return until == 0 || System.currentTimeMillis() / 1000 < until; }

        static BarItem from(Map<String, Object> m) {
            if (m == null) return null;
            BarItem b = new BarItem();
            b.kind = nz(Json.str(m, "k"));
            b.icon = nz(Json.str(m, "i"));
            b.text = nz(Json.str(m, "t"));
            b.color = nz(Json.str(m, "c"));
            Double e = Json.dbl(m, "e");
            b.epoch = e == null ? 0 : e.longValue();
            Integer a = Json.integer(m, "a");
            b.weak = a == null ? 0 : a;
            Integer bell = Json.integer(m, "b");
            b.doorbell = b.kind.equals("ago") && bell != null && bell == 1;
            Double x = Json.dbl(m, "x");
            b.until = x == null || x < 0 ? 0 : x.longValue();
            return b;
        }

        boolean valid() {
            boolean known = kind.equals("clock") || kind.equals("analog") || kind.equals("date") || kind.equals("text")
                    || kind.equals("ago") || kind.equals("wifi") || kind.equals("link") || kind.equals("battery");
            return known && (!kind.equals("ago") || epoch != 0);
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("k", kind);
            if (!icon.isEmpty()) m.put("i", icon);
            if (!text.isEmpty()) m.put("t", text);
            if (epoch != 0) m.put("e", (double) epoch);
            if (!color.isEmpty()) m.put("c", color);
            if (weak != 0) m.put("a", (double) weak);
            if (doorbell) m.put("b", 1.0);
            if (until != 0) m.put("x", (double) until);
            return m;
        }
    }

    public static final class Page {
        public String id = "", title = "";
        public boolean homeControl, excluded;
        public List<BarItem> bar = new ArrayList<BarItem>();

        Page copy() {
            Page p = new Page();
            p.id = id;
            p.title = title;
            p.homeControl = homeControl;
            p.excluded = excluded;
            p.bar = new ArrayList<BarItem>(bar);
            return p;
        }
    }

    public static final class Tile {
        public String entity = "", size = "", display = "", tap = "", sub = "", controls = "", inline = "",
                guard = "", flow = "", name = "", state = "", icon = "", background = "";
        public int refresh = REFRESH_DEFAULT, slot, parent = -1, key = -1;
        public Map<String, Object> options = new LinkedHashMap<String, Object>();
        public Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        public Map<String, Object> extra = new LinkedHashMap<String, Object>();
        public Object history;
        public boolean received;

        public String domain() {
            int dot = entity.indexOf('.');
            return dot < 0 ? entity : entity.substring(0, dot);
        }

        public String text(String key) { return Json.str(attributes, key); }

        public Double number(String key) { return Json.dbl(attributes, key); }

        public List<Object> list(String key) { return Json.arr(attributes.get(key)); }

        /** The tile's own action, set in Tessera as "Perform action". */
        public String actionService() {
            Map<String, Object> act = Json.obj(options.get("act"));
            return act == null ? null : Json.str(act, "s");
        }

        public Map<String, String> actionPairs(String field) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            Map<String, Object> act = Json.obj(options.get("act"));
            List<Object> pairs = act == null ? null : Json.arr(act.get(field));
            if (pairs != null) {
                for (Object p : pairs) {
                    List<Object> kv = Json.arr(p);
                    if (kv != null && kv.size() == 2 && kv.get(0) instanceof String && kv.get(1) instanceof String) {
                        out.put((String) kv.get(0), (String) kv.get(1));
                    }
                }
            }
            return out;
        }
    }

    public static final class Layout {
        public String revision = "", title = "", inbox = "";
        public int home, keepalive = 60;
        public boolean clock24 = true;
        public int numberStyle;
        public List<Page> pages = new ArrayList<Page>();
        public List<Tile> tiles = new ArrayList<Tile>();
        public Object saver;

        /** A shallow copy safe to read while the protocol thread keeps changing the original. */
        public Layout copy() {
            Layout l = new Layout();
            l.revision = revision;
            l.title = title;
            l.inbox = inbox;
            l.home = home;
            l.keepalive = keepalive;
            l.clock24 = clock24;
            l.numberStyle = numberStyle;
            for (Page p : pages) l.pages.add(p.copy());
            l.tiles = new ArrayList<Tile>(tiles);
            l.saver = saver;
            return l;
        }
    }

    // ----- the session (session.go) -----

    static final class Session {
        long lease, hello, revision;
        long sequence, digest;
        long pages;
        Map<Integer, Boolean> tiles = new HashMap<Integer, Boolean>();
        int expectedPages, expectedTiles;
        boolean granted, begun, active;

        Session copy() {
            Session s = new Session();
            s.lease = lease; s.hello = hello; s.revision = revision; s.sequence = sequence; s.digest = digest;
            s.pages = pages; s.tiles = new HashMap<Integer, Boolean>(tiles);
            s.expectedPages = expectedPages; s.expectedTiles = expectedTiles;
            s.granted = granted; s.begun = begun; s.active = active;
            return s;
        }

        long grant(long request, long random) {
            if (granted && request == hello) return lease;
            hello = request;
            lease = random;
            granted = true;
            begun = false;
            sequence = 0;
            digest = 0;
            return lease;
        }

        static final int REJECT = 0, DUPLICATE = 1, ACCEPT = 2;

        int packet(long l, long next, long hash) {
            if (!granted || l != lease || next == 0 || next < sequence) return REJECT;
            if (next == sequence) return hash == digest ? DUPLICATE : REJECT;
            return ACCEPT;
        }

        void accepted(long next, long hash) {
            sequence = next;
            digest = hash;
        }

        static final int REFUSED = 0, UNCHANGED = 1, REPLACED = 2;

        int begin(long rev, int p, int t) {
            if (!granted || p == 0 || p > MAX_PAGES || t > MAX_TILES || begun) return REFUSED;
            begun = true;
            if (active && revision == rev && expectedPages == p && expectedTiles == t) return UNCHANGED;
            active = false;
            revision = rev;
            expectedPages = p;
            expectedTiles = t;
            pages = 0;
            tiles = new HashMap<Integer, Boolean>();
            return REPLACED;
        }

        boolean matches(long rev) { return begun && revision == rev; }

        boolean page(int index) {
            if (!begun || active || index < 0 || index >= expectedPages || receivedPage(index)) return false;
            pages |= 1L << index;
            return true;
        }

        boolean tile(int index) {
            if (!begun || active || index < 0 || index >= expectedTiles || tiles.containsKey(index)) return false;
            tiles.put(index, true);
            return true;
        }

        boolean receivedPage(int index) { return index >= 0 && index < 32 && (pages & (1L << index)) != 0; }

        boolean complete() {
            long want = (1L << expectedPages) - 1;
            return begun && pages == want && tiles.size() == expectedTiles;
        }

        boolean commit() {
            if (!complete()) return false;
            active = true;
            return true;
        }
    }

    // ----- the screen -----

    public interface Shape {
        int width();
        int height();
        int columns();
        int rows();
        /** The grid kept for one way of the glass: {columns, rows}. */
        int[] kept(boolean upright);
        /** Tessera gave a grid for one way of the glass (and that way to hang): keep it, and turn the screen if need be. */
        void choose(boolean upright, int columns, int rows);
    }

    /** The grids the app takes either way, as a board's GRID_RANGES. */
    static final int GRID_LEAST = 1, GRID_MOST = 8;

    public interface Store {
        String load();
        void save(String layout);
    }

    public interface Listener {
        void changed();
    }

    /**
     * A ring (the op `doorbell`, docs/DOORBELL.md): an automation fired esp_screens_doorbell and the add-on names the
     * doorbell's camera, its name, the ring's key, when it rang, how long it stays untouched, its door (1 a lock in state
     * `doorState`, 2 an action), whether its view opens quiet and whether it chimes.
     */
    public static final class Ring {
        public String camera = "", name = "", key = "", doorState = "", link = "";
        public long rang;
        public int timeout = 60, door;
        public boolean quiet, chime = true;
    }

    public interface Doorbell {
        void ring(Ring r);
        /** Ring `key` was answered on another screen, or dismissed. */
        void close(String key);
        /** The new state of ring `key`'s lock. */
        void doorState(String key, String state);
    }

    private volatile Doorbell doorbell;

    public void setDoorbell(Doorbell d) { doorbell = d; }

    /** Fires a Home Assistant event, esphome.<name>. */
    public interface Teller {
        void tell(String event, Map<String, String> data);
    }

    private final Shape shape;
    private final Store store;
    private final EspDevice.Logger log;
    private final SecureRandom rng = new SecureRandom();
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private volatile Teller teller;

    private Session session = new Session();
    private Layout layout = new Layout();
    private boolean restored, heard, dirty;
    private long fed;
    private int wantedPage = -1;
    private long wantedAsk;

    public Tessera(Shape shape, Store store, EspDevice.Logger log) {
        this.shape = shape;
        this.store = store;
        this.log = log;
        restore();
    }

    public void listen(Listener l) { listeners.add(l); }

    public void setTeller(Teller t) { teller = t; }

    private void fire() {
        for (Listener l : listeners) l.changed();
    }

    public synchronized Layout layout() { return layout.copy(); }

    /** Whether there is a layout to show: a committed one, or the one kept from the last run. */
    public synchronized boolean shown() { return session.active || restored; }

    public synchronized boolean heard() { return heard; }

    /** True while the add-on keeps pinging; false when it has gone quiet for more than two keepalives. */
    public synchronized boolean fresh() {
        long lapse = (long) layout.keepalive * 2000L + 60000L;
        return session.active && System.currentTimeMillis() - fed < lapse;
    }

    public synchronized long wantedAsk() { return wantedAsk; }

    public synchronized int wantedPage() { return wantedPage; }

    /** show_page from Home Assistant, counted from one. */
    public void showPage(int page) {
        synchronized (this) {
            int n = layout.pages.size();
            if (n == 0) return;
            wantedPage = Math.min(Math.max(page, 1), n) - 1;
            wantedAsk++;
        }
        fire();
    }

    public String shapeText() {
        return shape.width() + "x" + shape.height() + " " + shape.columns() + "x" + shape.rows();
    }

    /** Handles one screen_message; returns the answer the add-on reads. */
    public Map<String, Object> receive(String payload) {
        Map<String, Object> answer;
        String kept = null;
        synchronized (this) {
            String status = receiveLocked(payload);
            if (status.startsWith("Error") || status.equals("Resend needed")) {
                log.log("tessera refused a message: " + status + " " + clip(payload));
            }
            answer = answer(status);
            if (dirty && session.active) {
                kept = save(layout);
                dirty = false;
            }
        }
        if (kept != null && store != null) store.save(kept);
        fire();
        return answer;
    }

    private String receiveLocked(String payload) {
        if (payload.length() > MOST_PAYLOAD) return "Error: message too large";
        Map<String, Object> m = Json.obj(Json.tryParse(payload));
        if (m == null) return "Error: invalid message";
        Object v = m.get("v");
        if (v instanceof Double && (Double) v == 1.0) return "Configuration problem. Update add-on.";
        if (!(v instanceof Double) || (Double) v != 2.0) return "Error: protocol version";
        String op = nz(Json.str(m, "op"));
        if (op.equals("hello")) {
            Long request = key(Json.str(m, "request"));
            if (request == null) return "Error: invalid message";
            heard = true;
            return "Session:" + keyText(session.grant(request, rng.nextLong()));
        }
        Long lease = key(Json.str(m, "session")), revision = key(Json.str(m, "rev"));
        Double seqD = Json.dbl(m, "seq");
        if (lease == null || revision == null || seqD == null || seqD < 0 || seqD > 0xFFFFFFFFL) {
            return "Error: invalid message";
        }
        long seq = seqD.longValue();
        long hash = fnv1a(payload);
        switch (session.packet(lease, seq, hash)) {
            case Session.REJECT: return "Error: obsolete message";
            case Session.DUPLICATE: return loading();
            default: break;
        }
        String[] status = {""};
        boolean ok = apply(op, m, revision, status);
        if (ok) {
            session.accepted(seq, hash);
            fed = System.currentTimeMillis();
        }
        if (!ok && status[0].isEmpty()) status[0] = "Error: invalid message";
        return status[0];
    }

    private String loading() { return session.active ? "Synced" : "Loading tiles"; }

    private boolean apply(String op, Map<String, Object> m, long revision, String[] status) {
        if (op.equals("begin")) return begin(m, revision, status);
        if (op.equals("appearance")) return appearance(m, revision, status);
        if (!session.matches(revision)) {
            status[0] = "Resend needed";
            return false;
        }
        if (op.equals("ping")) {
            status[0] = session.active ? "Synced" : "Resend needed";
            return true;
        }
        if (op.equals("bar_value")) return barValue(m, status);
        if (op.equals("page") || op.equals("bar")) return page(op, m, status);
        if (op.equals("commit")) {
            if (session.active) {
                status[0] = "Synced";
                return true;
            }
            if (!session.commit()) {
                status[0] = "Error: incomplete layout";
                return false;
            }
            dirty = true;
            restored = false;
            log.log("tessera layout applied: " + layout.pages.size() + " pages, " + layout.tiles.size() + " tiles");
            status[0] = "Synced";
            return true;
        }
        if (op.equals("tile")) return tile(m, true, status);
        if (!session.active) {
            status[0] = "Resend needed";
            return false;
        }
        if (op.equals("state")) return tile(m, false, status);
        if (op.equals("saver")) {
            layout.saver = m;
            status[0] = "Synced";
            return true;
        }
        if (op.equals("camera")) return answerPicture(m, status);
        if (op.equals("doorbell")) return doorbell(m, status);
        if (op.equals("calendar_event")) return calendarEvent(m, status);
        if (op.equals("options")) return answerOptions(m, status);
        if (op.equals("history")) return answerHistory(m, status);
        if (op.equals("browse")) return answerBrowse(m, status);
        return false;
    }

    /** Whole events the app answered a tap on a short one with (op `calendar_event`), by "calendar:start:end". */
    private final Map<String, String[]> calendarDetails = new LinkedHashMap<String, String[]>();

    public synchronized String[] calendarDetail(String key) { return calendarDetails.get(key); }

    /** A tap on a short calendar event asks for the rest (esphome.screen_calendar, calendar_feed.detail). */
    public void askCalendarEvent(String entity, int index, int cal, int start, int end, String day) {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity", entity);
        data.put("i", String.valueOf(index));
        data.put("c", String.valueOf(cal));
        data.put("s", String.valueOf(start));
        data.put("e", String.valueOf(end));
        data.put("day", day);
        tell("screen_calendar", data);
    }

    private boolean calendarEvent(Map<String, Object> m, String[] status) {
        Integer c = Json.integer(m, "c"), s = Json.integer(m, "s"), e = Json.integer(m, "e");
        if (c == null || s == null || e == null) return false;
        Integer gone = Json.integer(m, "x");
        String[] found = gone != null && gone == 1 ? new String[]{"", "", ""}
                : new String[]{cut(nz(Json.str(m, "t")), 120), cut(nz(Json.str(m, "p")), 96), cut(nz(Json.str(m, "n")), 480)};
        synchronized (this) {
            calendarDetails.put(c + ":" + s + ":" + e, found);
            while (calendarDetails.size() > 32) calendarDetails.remove(calendarDetails.keySet().iterator().next());
        }
        fire();
        status[0] = "Synced";
        return true;
    }

    /** The op `doorbell` (page_receiver.cpp): a ring, its close (`c`), or its lock's new state (`ds` alone). */
    private boolean doorbell(Map<String, Object> m, String[] status) {
        String key = cut(nz(Json.str(m, "k")), 16);
        if (key.isEmpty()) return false;
        Doorbell d = doorbell;
        Integer c = Json.integer(m, "c");
        String camera = nz(Json.str(m, "e"));
        if (c != null && c == 1) {
            if (d != null) d.close(key);
        } else if (camera.isEmpty() && Json.str(m, "ds") != null) {
            if (d != null) d.doorState(key, cut(nz(Json.str(m, "ds")), 16));
        } else {
            if (!camera.startsWith("camera.") || camera.length() > 120) return false;
            Ring r = new Ring();
            r.camera = camera;
            r.name = cut(nz(Json.str(m, "n")), 60);
            if (r.name.isEmpty()) r.name = camera;
            r.key = key;
            Double rang = Json.dbl(m, "r");
            r.rang = rang == null ? 0 : rang.longValue();
            Integer t = Json.integer(m, "t");
            r.timeout = Math.max(10, Math.min(600, t == null || t == 0 ? 60 : t));
            Integer door = Json.integer(m, "d");
            r.door = door == null || door < 0 || door > 2 ? 0 : door;
            r.doorState = cut(nz(Json.str(m, "ds")), 16);
            Integer quiet = Json.integer(m, "m"), nc = Json.integer(m, "nc");
            r.quiet = quiet != null && quiet == 1;
            r.chime = !(nc != null && nc == 1);
            r.link = cut(nz(Json.str(m, "l")), 200);
            log.log("doorbell: " + r.name + " rang (" + camera + ")");
            if (d != null) d.ring(r);
        }
        status[0] = "Synced";
        return true;
    }

    private static String cut(String s, int most) { return s.length() <= most ? s : s.substring(0, most); }

    private boolean begin(Map<String, Object> m, long revision, String[] status) {
        Integer tiles = Json.integer(m, "tiles"), pages = Json.integer(m, "pages"), home = Json.integer(m, "home"),
                keepalive = Json.integer(m, "keepalive");
        String title = Json.str(m, "title");
        if (tiles == null || pages == null || tiles < 0 || pages <= 0 || home == null || title == null
                || title.length() > 96 || pages > MAX_PAGES || tiles > MAX_TILES || home < 0 || home >= pages
                || keepalive == null || keepalive < 5 || keepalive > 3600) {
            return false;
        }
        // The way the glass is to hang and the grid the layout is counted on (firmware 0.53.0+, the hello's "grids"): none
        // means the way and the grid it has. A grid that way can't take is refused whole, before anything changes.
        boolean uprightNow = shape.height() > shape.width();
        Boolean upright = Json.bool(m, "upright");
        boolean way = upright == null ? uprightNow : upright;
        int[] target = shape.kept(way);
        if (m.get("grid") != null) {
            List<Object> cells = Json.arr(m.get("grid"));
            Integer gc = cells != null && cells.size() == 2 && cells.get(0) instanceof Double ? ((Double) cells.get(0)).intValue() : null;
            Integer gr = cells != null && cells.size() == 2 && cells.get(1) instanceof Double ? ((Double) cells.get(1)).intValue() : null;
            if (gc == null || gr == null || gc < GRID_LEAST || gc > GRID_MOST || gr < GRID_LEAST || gr > GRID_MOST) {
                status[0] = "Error: grid";
                return false;
            }
            target = new int[]{gc, gr};
        }
        int[] had = shape.kept(way);
        boolean regridded = way != uprightNow || target[0] != had[0] || target[1] != had[1];
        if (regridded) {
            log.log("tessera: grid " + target[0] + "x" + target[1] + (way ? " standing" : " lying") + (way != uprightNow ? ", turning the screen" : ""));
            shape.choose(way, target[0], target[1]);
        }
        Session candidate = session.copy();
        int r = candidate.begin(revision, pages, tiles);
        if (r == Session.REFUSED) return false;
        session = candidate;
        if (r == Session.UNCHANGED && !regridded) {
            status[0] = "Synced";
            return true;
        }
        restored = false;
        Layout l = new Layout();
        l.revision = keyText(revision);
        l.title = title;
        l.home = home;
        l.inbox = nz(Json.str(m, "inbox"));
        l.keepalive = keepalive;
        l.clock24 = layout.clock24;
        Boolean c24 = Json.bool(m, "clock_24h");
        if (c24 != null) l.clock24 = c24;
        String numbers = Json.str(m, "numbers");
        l.numberStyle = "comma".equals(numbers) || "space".equals(numbers) ? 2 : layout.numberStyle;
        for (int k = 0; k < pages; k++) l.pages.add(new Page());
        for (int k = 0; k < tiles; k++) l.tiles.add(new Tile());
        layout = l;
        status[0] = "Loading tiles";
        return true;
    }

    private boolean appearance(Map<String, Object> m, long revision, String[] status) {
        Long base = key(Json.str(m, "base"));
        String title = Json.str(m, "title");
        if (base == null || !session.active || !session.matches(base) || title == null) return false;
        List<Object> pages = Json.arr(m.get("pages")), tiles = Json.arr(m.get("tiles"));
        if (pages == null || tiles == null) return false;
        for (Object o : pages) {
            Integer p = Json.integer(Json.obj(o), "p");
            if (p == null || p < 0 || p >= layout.pages.size()) return false;
        }
        for (Object o : tiles) {
            Integer t = Json.integer(Json.obj(o), "i");
            if (t == null || t < 0 || t >= layout.tiles.size()) return false;
        }
        layout.title = title;
        for (Object o : pages) {
            Map<String, Object> p = Json.obj(o);
            layout.pages.get(Json.integer(p, "p")).title = nz(Json.str(p, "title"));
        }
        for (Object o : tiles) {
            Map<String, Object> t = Json.obj(o);
            int i = Json.integer(t, "i");
            Tile copy = copyTile(layout.tiles.get(i));
            copy.name = nz(Json.str(t, "name"));
            copy.background = nz(Json.str(t, "background"));
            layout.tiles.set(i, copy);
        }
        session.revision = revision;
        layout.revision = keyText(revision);
        dirty = true;
        status[0] = "Synced";
        return true;
    }

    private static List<BarItem> bar(Object raw) {
        List<Object> items = Json.arr(raw);
        if (items == null || items.size() > BAR_ITEMS) return null;
        List<BarItem> out = new ArrayList<BarItem>();
        for (Object o : items) {
            BarItem b = BarItem.from(Json.obj(o));
            if (b == null || !b.valid()) return null;
            out.add(b);
        }
        return out;
    }

    private boolean barValue(Map<String, Object> m, String[] status) {
        List<Object> targets = Json.arr(m.get("targets"));
        if (!session.active || targets == null || targets.isEmpty()) return false;
        int stride = WIRE_ITEMS;
        if (m.containsKey("w")) {
            Integer w = Json.integer(m, "w");
            if (w == null) return false;
            stride = w;
        }
        if (stride < 1) return false;
        BarItem item = BarItem.from(Json.obj(m.get("item")));
        if (item == null || !item.valid()) return false;
        List<Integer> ts = new ArrayList<Integer>();
        for (Object o : targets) {
            if (!(o instanceof Double)) return false;
            int t = ((Double) o).intValue();
            if (t < 0 || t / stride >= layout.pages.size() || t % stride >= layout.pages.get(t / stride).bar.size()) {
                return false;
            }
            ts.add(t);
        }
        for (int t : ts) layout.pages.get(t / stride).bar.set(t % stride, item);
        status[0] = "Synced";
        return true;
    }

    private boolean page(String op, Map<String, Object> m, String[] status) {
        Integer p = Json.integer(m, "p");
        if (p == null || p < 0 || p >= layout.pages.size()) return false;
        List<BarItem> items = bar(m.get("items"));
        if (items == null) return false;
        if (op.equals("bar")) {
            if (!session.active) return false;
            layout.pages.get(p).bar = items;
            dirty = true;
            status[0] = "Synced";
            return true;
        }
        Boolean control = Json.bool(m, "home_control"), excluded = Json.bool(m, "excluded");
        String title = Json.str(m, "title"), id = Json.str(m, "id");
        if (session.active || session.receivedPage(p) || control == null || excluded == null || title == null
                || title.length() > 96 || key(id) == null) {
            return false;
        }
        for (int k = 0; k < layout.pages.size(); k++) {
            if (session.receivedPage(k) && layout.pages.get(k).id.equals(id)) return false;
        }
        if (!session.page(p)) return false;
        Page page = new Page();
        page.id = id;
        page.title = title;
        page.homeControl = control;
        page.excluded = excluded;
        page.bar = items;
        layout.pages.set(p, page);
        status[0] = loading();
        return true;
    }

    private boolean tile(Map<String, Object> m, boolean initial, String[] status) {
        Integer index = Json.integer(m, "i");
        String state = Json.str(m, "state"), name = Json.str(m, "name");
        Map<String, Object> a = Json.obj(m.get("a"));
        if (index == null || state == null || name == null || a == null) return false;
        if (index < 0 || index >= layout.tiles.size()) return false;
        String entity = nz(Json.str(m, "entity"));
        Tile old = layout.tiles.get(index);
        Tile t;
        if (initial) {
            Integer slot = Json.integer(m, "slot"), in = Json.integer(m, "in"), k = Json.integer(m, "k");
            boolean placed = slot != null || (in != null && k != null && k >= 0);
            Map<String, Object> o = Json.obj(m.get("o"));
            if (session.active || old.received || entity.isEmpty() || !placed || o == null || !session.tile(index)) {
                return false;
            }
            t = new Tile();
            t.entity = entity;
            t.size = nz(Json.str(o, "size"));
            t.display = nz(Json.str(o, "display"));
            t.tap = nz(Json.str(o, "tap"));
            t.background = nz(Json.str(o, "background"));
            t.icon = nz(Json.str(o, "icon"));
            t.sub = nz(Json.str(o, "sub"));
            t.controls = nz(Json.str(o, "controls"));
            t.inline = nz(Json.str(o, "inline"));
            t.guard = nz(Json.str(o, "guard"));
            t.flow = nz(Json.str(o, "flow"));
            Integer refresh = Json.integer(o, "refresh");
            // 0 is Live: the tile streams where the screen can (live_stream.live_pace).
            if (refresh != null && (refresh == 0 || refresh >= REFRESH_LEAST && refresh <= REFRESH_MOST)) t.refresh = refresh;
            t.options = o;
            t.received = true;
            if (slot != null) {
                t.slot = slot;
            } else {
                t.parent = in;
                t.key = k;
            }
        } else {
            if (m.containsKey("o") || m.containsKey("slot") || !old.received || !old.entity.equals(entity)) {
                status[0] = "Error: outdated tile or configuration in state";
                return false;
            }
            t = copyTile(old);
            String icon = Json.str(m, "icon");
            if (icon != null && !icon.isEmpty()) t.icon = icon;
        }
        t.name = name;
        t.state = state;
        t.attributes = a;
        Map<String, Object> x = Json.obj(m.get("x"));
        t.extra = x == null ? new LinkedHashMap<String, Object>() : x;
        t.history = m.get("history");
        // Tiles are replaced, never changed in place, so a copy of the layout stays consistent.
        layout.tiles.set(index, t);
        status[0] = loading();
        return true;
    }

    private static Tile copyTile(Tile o) {
        Tile t = new Tile();
        t.entity = o.entity; t.size = o.size; t.display = o.display; t.tap = o.tap; t.sub = o.sub;
        t.controls = o.controls; t.inline = o.inline; t.guard = o.guard; t.flow = o.flow; t.name = o.name;
        t.state = o.state; t.icon = o.icon; t.background = o.background; t.refresh = o.refresh; t.slot = o.slot;
        t.parent = o.parent; t.key = o.key; t.options = o.options; t.attributes = o.attributes; t.extra = o.extra;
        t.history = o.history; t.received = o.received;
        return t;
    }

    private Map<String, Object> answer(String status) {
        int columns = shape.columns(), rows = shape.rows();
        List<Object> sizes = new ArrayList<Object>();
        sizes.add("single");
        sizes.add("wide");
        sizes.add("full");
        if (rows >= 2) sizes.add("tall");
        if (columns >= 2 && rows >= 2) sizes.add("square");
        for (int c = 1; c <= columns; c++) {
            for (int r = 1; r <= rows; r++) {
                if ((c > 2 || r > 2) && (c != columns || r != rows)) sizes.add(c + "x" + r);
            }
        }
        int page = 0, home = 0, shown = 0;
        if (session.active) {
            home = layout.home + 1;
            page = home;
            for (Page p : layout.pages) if (!p.excluded) shown++;
        }
        List<Object> features = new ArrayList<Object>();
        for (String f : FEATURES) features.add(f);
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("status", status);
        a.put("rev", layout.revision);
        a.put("protocol", (double) PROTOCOL);
        a.put("bar_values", 1.0);
        a.put("appearance_updates", 1.0);
        a.put("tile_keys", 1.0);
        a.put("tile_repeats", 1.0);
        a.put("free_pages", 1.0);
        // The board's, not the grid's (firmware 0.53.0+): Tessera may give it another grid and counts that one itself.
        a.put("max_tiles", (double) MAX_TILES);
        a.put("max_pages", (double) MAX_PAGES);
        a.put("max_bar_items", (double) BAR_ITEMS);
        // The grids this screen takes each way its glass hangs, the one it keeps for each, and which way it hangs now
        // (firmware 0.53.0+): the editor's Grid card then chooses this screen's grid, and the begin of a layout brings it.
        Map<String, Object> grids = new LinkedHashMap<String, Object>();
        grids.put("upright", shape.height() > shape.width());
        for (int i = 0; i < 2; i++) {
            int[] kept = shape.kept(i == 1);
            Map<String, Object> way = new LinkedHashMap<String, Object>();
            way.put("columns", (double) kept[0]);
            way.put("rows", (double) kept[1]);
            List<Object> least = new ArrayList<Object>(), most = new ArrayList<Object>();
            least.add((double) GRID_LEAST);
            least.add((double) GRID_LEAST);
            most.add((double) GRID_MOST);
            most.add((double) GRID_MOST);
            way.put("min", least);
            way.put("max", most);
            grids.put(i == 1 ? "portrait" : "landscape", way);
        }
        a.put("grids", grids);
        a.put("features", features);
        // What this screen can do (firmware hw.h, page_delivery.caps_of): it streams live cameras, so many tiles at once.
        Map<String, Object> caps = new LinkedHashMap<String, Object>();
        caps.put("live", true);
        caps.put("live_tiles", (double) Math.max(0, Math.min(8, liveTiles)));
        // Template tiles (docs/TEXT_TILE.md, "Limits"): as many as a P4, and a P4's bytes of rows.
        caps.put("text_tiles", 8.0);
        caps.put("text_bytes", 3584.0);
        a.put("caps", caps);
        a.put("tile_sizes", sizes);
        a.put("request", keyText(session.hello));
        a.put("session", keyText(session.lease));
        a.put("seq", (double) session.sequence);
        a.put("applied", session.active);
        a.put("page", (double) page);
        a.put("home", (double) home);
        a.put("pagination_count", (double) shown);
        return a;
    }

    /** Fires esphome.<event> with the fields the add-on matches a screen by. */
    public void tell(String event, Map<String, String> data) {
        Teller t = teller;
        if (t == null) return;
        Map<String, String> fields = new LinkedHashMap<String, String>();
        synchronized (this) {
            if (layout.inbox.isEmpty()) return;
            fields.put("inbox", layout.inbox);
            fields.put("session", keyText(session.lease));
            fields.put("rev", layout.revision);
        }
        if (data != null) fields.putAll(data);
        t.tell(event, fields);
    }

    // ----- pictures (pkg/tessera/picture.go) -----

    /** What the add-on said a picture of one kind is: where to fetch it, for the request numbered `view`. */
    public static final class Picture {
        public final String entity, url;
        public final long view;

        Picture(String entity, String url, long view) {
            this.entity = entity;
            this.url = url;
            this.view = view;
        }
    }

    private final Map<String, Long> views = new HashMap<String, Long>();
    /** Which kind each question was asked for, by its number, newest last. */
    private final Map<Long, String> asked = new LinkedHashMap<Long, String>();
    private long questions;
    private final Map<String, Picture> pictures = new HashMap<String, Picture>();

    /**
     * Asks the add-on for a picture: "live" (a page's camera and cover tiles, one image), "full" (a camera over the
     * glass), "cover" or "saver". It answers with a link through screen_message.
     */
    public void askPicture(String kind, Map<String, String> fields) {
        long view;
        synchronized (this) {
            // One counter for every question, so an answer's number names the question it answers, whatever its kind
            // (a tile's own picture is "live:<index>" here and "live" on the wire).
            view = ++questions;
            views.put(kind, view);
            asked.put(view, kind);
            if (asked.size() > 64) asked.remove(asked.keySet().iterator().next());
            pictures.remove(kind);
        }
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("view", Long.toString(view));
        data.putAll(fields);
        String what = fields.containsKey("tiles") ? fields.get("tiles") : fields.get("entity");
        log.log("pictures: asked Tessera for " + kind + " (" + what + "), request " + view);
        tell("screen_camera", data);
    }

    /** Whether the add-on's newest answer for a kind said it has no picture to give. */
    public synchronized boolean answeredEmpty(String kind) {
        Picture p = pictures.get(kind);
        return p != null && p.url.isEmpty();
    }

    /**
     * Asks the add-on a question for a picture in its new shape (docs/PICTURES.md, "The question", the feature `pictures`):
     * `q` its compact JSON, with the question's number `n`. The answer comes as camera `t` "q" with that number, and is kept
     * under `kind` as every picture is.
     */
    public void askQuestion(String kind, Map<String, Object> q) {
        long number;
        synchronized (this) {
            number = ++questions;
            views.put(kind, number);
            asked.put(number, kind);
            if (asked.size() > 64) asked.remove(asked.keySet().iterator().next());
            pictures.remove(kind);
        }
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("n", Long.toString(number));
        data.put("view", Long.toString(number));
        data.put("q", Json.write(q));
        log.log("pictures: asked Tessera for " + kind + " (" + Json.str(q, "o") + " " + q.get("src") + "), question " + number);
        tell("screen_camera", data);
    }

    public synchronized Picture picture(String kind) {
        Picture p = pictures.get(kind);
        return p == null || p.url.isEmpty() ? null : p;
    }

    private boolean answerPicture(Map<String, Object> m, String[] status) {
        String kind = nz(Json.str(m, "t")), e = nz(Json.str(m, "e")), url = nz(Json.str(m, "u"));
        if (kind.length() > 8) kind = kind.substring(0, 8);
        // The answer to a question in the new shape: `t` "q" and the question's number `n`.
        if (kind.equals("q")) {
            Double n = Json.dbl(m, "n");
            String of = n == null ? null : asked.get(n.longValue());
            if (of == null) {
                status[0] = "Synced";
                return true;
            }
            if (url.length() > 240 || (!url.isEmpty() && !url.startsWith("http://"))) return false;
            Long latest = views.get(of);
            status[0] = "Synced";
            if (latest == null || latest != n.longValue()) {
                log.log("pictures: Tessera answered an older question for " + of + "; waiting for the newest");
                return true;
            }
            log.log(url.isEmpty() ? "pictures: Tessera has no picture for " + of + " right now"
                    : "pictures: Tessera's link for " + of + ": " + url);
            pictures.put(of, new Picture(e, url, latest));
            return true;
        }
        if (!(kind.equals("live") || kind.equals("full") || kind.equals("cover") || kind.equals("saver")
                || kind.equals("alert") || kind.equals("lib") || kind.equals("vmap"))) {
            return false;
        }
        if (e.length() > 400 || url.length() > 240 || (!url.isEmpty() && !url.startsWith("http://"))) return false;
        Double view = Json.dbl(m, "view");
        status[0] = "Synced";
        // The answer finds its question by its number: a tile's own picture comes as "live" for "live:<index>".
        if (view != null && kind.equals("live")) {
            String of = asked.get(view.longValue());
            if (of != null && of.startsWith("live:")) kind = of;
        }
        Long latest = views.get(kind);
        // An answer to an older request, or one without its number, changes nothing.
        if (latest == null || view == null || view.longValue() != latest) {
            log.log("pictures: Tessera answered an older " + kind + " request; waiting for the newest");
            return true;
        }
        log.log(url.isEmpty() ? "pictures: Tessera has no " + kind + " picture of " + e + " right now"
                : "pictures: Tessera's link for " + kind + ": " + url);
        pictures.put(kind, new Picture(e, url, latest));
        return true;
    }

    // ----- a player's library (media_library.h, media_library.py): a folder asked for by its number, page by page -----

    /** One folder of a player's library as it comes in: its items are {token, title, flags, icon codepoint}. */
    public static final class Folder {
        public String entity = "", title = "";
        public long token;
        public final List<Object[]> items = new ArrayList<Object[]>();
        public int next, pages;
        public boolean complete, failed;
    }

    public static final int CAN_PLAY = 1, CAN_EXPAND = 2, PICTURED = 4, PLAYING = 8;
    private long libraryView;
    private Folder folder;

    /** Asks for a folder of a player's library: 0 is its top. library() has it as its pages come. */
    public void askFolder(String entity, long token) {
        Folder f = new Folder();
        f.entity = entity;
        f.token = token;
        synchronized (this) { folder = f; }
        askFolderPage(entity, token, 0);
    }

    private void askFolderPage(String entity, long token, int page) {
        long view;
        synchronized (this) { view = ++libraryView; }
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity", entity);
        data.put("folder", Long.toString(token));
        data.put("page", Integer.toString(page));
        data.put("view", Long.toString(view));
        log.log("library: asked Tessera for folder " + token + " of " + entity + ", page " + page);
        tell("screen_browse", data);
    }

    /** The folder asked for last, as far as it has come, or null. */
    public synchronized Folder library() { return folder; }

    /** Plays an item of the library by its number, on `source` or where the player plays now. */
    public void play(String entity, long token, String source) {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity", entity);
        data.put("item", Long.toString(token));
        data.put("source", source == null ? "" : source);
        log.log("library: play " + token + " on " + entity);
        tell("screen_play", data);
    }

    /** Plays a favourite's tile by its index: what it plays and its speaker are the tile's own. */
    public void playFavorite(String entity, int index) { playFavoriteOn(entity, index, ""); }

    /** A favourite on a speaker chosen for it, when it would play nowhere (media_library.cpp). */
    public void playFavoriteOn(String entity, int index, String source) {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity", entity);
        data.put("tile", Integer.toString(index));
        if (source != null && !source.isEmpty()) data.put("source", source);
        log.log("library: play favourite " + index + " of " + entity + (source == null || source.isEmpty() ? "" : " on " + source));
        tell("screen_play", data);
    }

    private boolean answerBrowse(Map<String, Object> m, String[] status) {
        status[0] = "Synced";
        Double view = Json.dbl(m, "view"), token = Json.dbl(m, "f");
        String e = nz(Json.str(m, "e"));
        Integer page = Json.integer(m, "i"), pages = Json.integer(m, "n");
        List<Object> items = Json.arr(m.get("k"));
        int next = -1;
        Folder f;
        synchronized (this) {
            f = folder;
            if (view == null || view.longValue() != libraryView || f == null || !e.equals(f.entity) || token == null
                    || token.longValue() != f.token || page == null || page != f.next || f.complete) return true;
            if (m.get("x") != null) {
                f.failed = f.complete = true;
            } else {
                if (page == 0) f.title = nz(Json.str(m, "t"));
                if (items != null) for (Object o : items) {
                    List<Object> it = Json.arr(o);
                    if (it == null || it.size() < 3 || f.items.size() >= 48) continue;
                    long tk = (long) num(it.get(0));
                    int flags = (int) num(it.get(2));
                    String icon = it.size() > 3 && it.get(3) != null ? String.valueOf(it.get(3)) : "";
                    f.items.add(new Object[]{tk, String.valueOf(it.get(1)), flags, icon});
                }
                f.pages = Math.max(1, pages == null ? 1 : pages);
                f.next = page + 1;
                f.complete = f.next >= f.pages || f.items.size() >= 48;
                if (!f.complete) next = f.next;
            }
        }
        if (next >= 0) askFolderPage(f.entity, f.token, next);
        else {
            log.log("library: " + (f.failed ? "the folder did not open" : f.items.size() + " items in " + f.title));
            fire();
        }
        return true;
    }

    // ----- a card's history (history_view.h, history_card.py): asked for when the card opens, for 1, 24 or 168 hours -----

    /** One history as the add-on sends it: a line of 24 averages, or a timeline of runs of states. */
    public static final class History {
        public String entity = "", unit = "";
        public int hours, offset, decimals = 1, slots = 96, active = -1, began;
        public long start, end, highAt, lowAt;
        public boolean line = true;
        public final double[] values = new double[24];
        public final boolean[] has = new boolean[24];
        public double bottom, top = 1, high = Double.NaN, low = Double.NaN;
        public final List<long[]> times = new ArrayList<long[]>();      // {epoch}
        public final List<Object[]> ticks = new ArrayList<Object[]>();   // {Double value, String label}
        public final List<Object[]> states = new ArrayList<Object[]>();  // {String label, Integer rgb, Long seconds}
        public final List<long[]> runs = new ArrayList<long[]>();        // {slot, state, begin, end, seconds}
        public final Map<String, String> words = new HashMap<String, String>();
        public long received;
    }

    private long historyView;
    private String historyEntity = "";
    private int historyHours;
    private History history;

    public void askHistory(String entity, int hours) {
        long view;
        synchronized (this) {
            view = ++historyView;
            historyEntity = entity;
            historyHours = hours;
        }
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity", entity);
        data.put("hours", Integer.toString(hours));
        data.put("view", Long.toString(view));
        log.log("history: asked Tessera for " + hours + " h of " + entity);
        tell("screen_history", data);
    }

    /** The history last received for `entity` over `hours`, or null. */
    public synchronized History history(String entity, int hours) {
        return history != null && history.entity.equals(entity) && history.hours == hours ? history : null;
    }

    /** What was last asked for: entity and hours, so a card knows whether to ask again. */
    public synchronized String askedHistory() { return historyEntity + "|" + historyHours; }

    private static double num(Object o) { return o instanceof Double ? (Double) o : Double.NaN; }

    private boolean answerHistory(Map<String, Object> m, String[] status) {
        status[0] = "Synced";
        Double view = Json.dbl(m, "view");
        History h = new History();
        h.entity = nz(Json.str(m, "entity"));
        Integer hours = Json.integer(m, "hours");
        h.hours = hours == null ? 0 : hours;
        if (h.entity.isEmpty() || h.entity.length() > 120 || (h.hours != 1 && h.hours != 24 && h.hours != 168)) return false;
        synchronized (this) {
            if (view == null || view.longValue() != historyView || !h.entity.equals(historyEntity) || h.hours != historyHours) return true;
        }
        Double start = Json.dbl(m, "start"), end = Json.dbl(m, "end"), off = Json.dbl(m, "off");
        h.start = start == null ? 0 : start.longValue();
        h.end = end == null ? 0 : end.longValue();
        if (h.end <= h.start) return false;
        h.offset = off == null ? 0 : (int) Math.max(-14 * 3600, Math.min(14 * 3600, off));
        h.line = !"timeline".equals(Json.str(m, "kind"));
        List<Object> xt = Json.arr(m.get("xt"));
        if (xt != null) for (Object o : xt) if (o instanceof Double && h.times.size() < 8) h.times.add(new long[]{((Double) o).longValue()});
        if (h.line) {
            List<Object> values = Json.arr(m.get("values"));
            if (values != null) for (int i = 0; i < Math.min(24, values.size()); i++) {
                double v = num(values.get(i));
                h.has[i] = !Double.isNaN(v) && !Double.isInfinite(v);
                h.values[i] = h.has[i] ? v : 0;
            }
            List<Object> dom = Json.arr(m.get("dom"));
            h.bottom = dom != null && dom.size() > 0 && !Double.isNaN(num(dom.get(0))) ? num(dom.get(0)) : 0;
            h.top = dom != null && dom.size() > 1 && !Double.isNaN(num(dom.get(1))) ? num(dom.get(1)) : 1;
            if (!(h.top > h.bottom)) h.top = h.bottom + 1;
            List<Object> yt = Json.arr(m.get("yt"));
            if (yt != null) for (Object o : yt) {
                List<Object> tick = Json.arr(o);
                if (tick == null || tick.size() < 2 || h.ticks.size() >= 6 || Double.isNaN(num(tick.get(0)))) continue;
                h.ticks.add(new Object[]{num(tick.get(0)), String.valueOf(tick.get(1))});
            }
            List<Object> hi = Json.arr(m.get("hi")), lo = Json.arr(m.get("lo"));
            if (hi != null && hi.size() >= 2) { h.high = num(hi.get(0)); h.highAt = (long) (num(hi.get(1)) > 0 ? num(hi.get(1)) : 0); }
            if (lo != null && lo.size() >= 2) { h.low = num(lo.get(0)); h.lowAt = (long) (num(lo.get(1)) > 0 ? num(lo.get(1)) : 0); }
            Integer dec = Json.integer(m, "dec");
            h.decimals = dec == null ? 1 : Math.max(0, Math.min(4, dec));
            h.unit = nz(Json.str(m, "unit"));
        } else {
            Integer slots = Json.integer(m, "slots");
            h.slots = slots == null ? 96 : Math.max(1, Math.min(96, slots));
            List<Object> states = Json.arr(m.get("states"));
            if (states != null) for (Object o : states) {
                List<Object> st = Json.arr(o);
                if (st == null || st.size() < 3 || h.states.size() >= 7) continue;
                int rgb;
                try { rgb = Integer.parseInt(String.valueOf(st.get(1)), 16); } catch (NumberFormatException e) { rgb = 0x808080; }
                h.states.add(new Object[]{String.valueOf(st.get(0)), rgb, (long) Math.max(0, num(st.get(2)))});
            }
            List<Object> seg = Json.arr(m.get("seg"));
            if (seg != null) for (Object o : seg) {
                List<Object> r = Json.arr(o);
                if (r == null || r.size() < 5 || h.runs.size() >= 96) continue;
                long slot = (long) num(r.get(0)), state = (long) num(r.get(1));
                if (slot < 0 || slot >= h.slots || state >= h.states.size() || state < -1) return false;
                long begin = (long) num(r.get(2)), finish = Math.max(begin, (long) num(r.get(3)));
                h.runs.add(new long[]{slot, state, begin, finish, (long) num(r.get(4))});
            }
            List<Object> words = Json.arr(m.get("words"));
            if (words != null) for (Object o : words) {
                List<Object> pair = Json.arr(o);
                if (pair != null && pair.size() >= 2 && h.words.size() < 12) h.words.put(String.valueOf(pair.get(0)), String.valueOf(pair.get(1)));
            }
            Integer began = Json.integer(m, "began"), active = Json.integer(m, "active");
            h.began = began == null ? 0 : began;
            h.active = active == null || active >= h.states.size() ? -1 : active;
        }
        h.received = System.currentTimeMillis();
        synchronized (this) { history = h; }
        log.log("history: " + (h.line ? "a line" : h.states.size() + " states") + " for " + h.entity + " over " + h.hours + " h");
        fire();
        return true;
    }

    // ----- a picker's names (effects_page.h, light_effects.py): asked for when it opens, one page per message -----

    private long optionsView;
    private String optionsEntity = "";
    private final List<String> optionsGot = new ArrayList<String>();
    private List<String> optionsDone;

    /** Asks for every name a light's effects or a select of its device lists; options() has them once all came. */
    public void askOptions(String entity) {
        synchronized (this) {
            optionsEntity = entity;
            optionsGot.clear();
            optionsDone = null;
        }
        askOptionsPage(entity, 0);
    }

    private void askOptionsPage(String entity, int page) {
        long view;
        synchronized (this) { view = ++optionsView; }
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity", entity);
        data.put("page", Integer.toString(page));
        data.put("view", Long.toString(view));
        log.log("effects: asked Tessera for the names of " + entity + ", page " + page);
        tell("screen_options", data);
    }

    /** The names of `entity`, all pages of them, or null while they are on their way. */
    public synchronized List<String> options(String entity) {
        return entity.equals(optionsEntity) && optionsDone != null ? new ArrayList<String>(optionsDone) : null;
    }

    private boolean answerOptions(Map<String, Object> m, String[] status) {
        status[0] = "Synced";
        Double view = Json.dbl(m, "view");
        String e = nz(Json.str(m, "e"));
        List<Object> names = Json.arr(m.get("o"));
        Integer page = Json.integer(m, "i"), pages = Json.integer(m, "n");
        if (names == null || e.isEmpty() || e.length() > 120) return false;
        int next = -1;
        synchronized (this) {
            // An answer to an older question changes nothing.
            if (view == null || view.longValue() != optionsView || !e.equals(optionsEntity)) return true;
            for (Object o : names) {
                if (o instanceof String && !((String) o).isEmpty() && optionsGot.size() < 400) optionsGot.add((String) o);
            }
            int at = page == null ? 0 : page, of = pages == null ? 1 : Math.max(1, pages);
            if (at + 1 < of && optionsGot.size() < 400) next = at + 1;
            else optionsDone = new ArrayList<String>(optionsGot);
        }
        if (next >= 0) askOptionsPage(e, next);
        else {
            log.log("effects: " + optionsGot.size() + " names of " + e);
            fire();
        }
        return true;
    }

    /** The screensaver Tessera chose for now (layout.saver), or null for none. */
    public synchronized Map<String, Object> saver() {
        return Json.obj(layout.saver);
    }

    // ----- keeping the layout across restarts -----

    private void restore() {
        if (store == null) return;
        String kept = store.load();
        if (kept == null || kept.isEmpty()) return;
        try {
            Layout l = load(Json.obj(Json.parse(kept)));
            if (l != null) {
                layout = l;
                restored = true;
            }
        } catch (RuntimeException e) {
            log.log("tessera: the kept layout is unreadable: " + e);
        }
    }

    private static String save(Layout l) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("revision", l.revision);
        m.put("title", l.title);
        m.put("inbox", l.inbox);
        m.put("home", (double) l.home);
        m.put("keepalive", (double) l.keepalive);
        m.put("clock24", l.clock24);
        List<Object> pages = new ArrayList<Object>();
        for (Page p : l.pages) {
            Map<String, Object> pm = new LinkedHashMap<String, Object>();
            pm.put("id", p.id);
            pm.put("title", p.title);
            pm.put("home_control", p.homeControl);
            pm.put("excluded", p.excluded);
            List<Object> bar = new ArrayList<Object>();
            for (BarItem b : p.bar) bar.add(b.json());
            pm.put("bar", bar);
            pages.add(pm);
        }
        m.put("pages", pages);
        List<Object> tiles = new ArrayList<Object>();
        for (Tile t : l.tiles) {
            Map<String, Object> tm = new LinkedHashMap<String, Object>();
            tm.put("entity", t.entity);
            tm.put("slot", (double) t.slot);
            tm.put("parent", (double) t.parent);
            tm.put("key", (double) t.key);
            tm.put("name", t.name);
            tm.put("state", t.state);
            tm.put("icon", t.icon);
            tm.put("o", t.options);
            tm.put("a", t.attributes);
            tm.put("x", t.extra);
            tiles.add(tm);
        }
        m.put("tiles", tiles);
        return Json.write(m);
    }

    private static Layout load(Map<String, Object> m) {
        if (m == null) return null;
        Layout l = new Layout();
        l.revision = nz(Json.str(m, "revision"));
        l.title = nz(Json.str(m, "title"));
        l.inbox = nz(Json.str(m, "inbox"));
        Integer home = Json.integer(m, "home"), keep = Json.integer(m, "keepalive");
        l.home = home == null ? 0 : home;
        l.keepalive = keep == null ? 60 : keep;
        Boolean c24 = Json.bool(m, "clock24");
        l.clock24 = c24 == null || c24;
        List<Object> pages = Json.arr(m.get("pages")), tiles = Json.arr(m.get("tiles"));
        if (pages == null || tiles == null || pages.isEmpty()) return null;
        for (Object o : pages) {
            Map<String, Object> pm = Json.obj(o);
            Page p = new Page();
            p.id = nz(Json.str(pm, "id"));
            p.title = nz(Json.str(pm, "title"));
            p.homeControl = Boolean.TRUE.equals(Json.bool(pm, "home_control"));
            p.excluded = Boolean.TRUE.equals(Json.bool(pm, "excluded"));
            List<BarItem> bar = bar(pm == null ? null : pm.get("bar"));
            if (bar != null) p.bar = bar;
            l.pages.add(p);
        }
        for (Object o : tiles) {
            Map<String, Object> tm = Json.obj(o);
            Tile t = new Tile();
            t.entity = nz(Json.str(tm, "entity"));
            Integer slot = Json.integer(tm, "slot"), parent = Json.integer(tm, "parent"), k = Json.integer(tm, "key");
            t.slot = slot == null ? 0 : slot;
            t.parent = parent == null ? -1 : parent;
            t.key = k == null ? -1 : k;
            t.name = nz(Json.str(tm, "name"));
            t.state = nz(Json.str(tm, "state"));
            t.icon = nz(Json.str(tm, "icon"));
            Map<String, Object> opt = Json.obj(tm.get("o"));
            if (opt != null) {
                t.options = opt;
                t.size = nz(Json.str(opt, "size"));
                t.display = nz(Json.str(opt, "display"));
                t.tap = nz(Json.str(opt, "tap"));
                t.background = nz(Json.str(opt, "background"));
                t.sub = nz(Json.str(opt, "sub"));
                t.controls = nz(Json.str(opt, "controls"));
                t.guard = nz(Json.str(opt, "guard"));
            }
            Map<String, Object> a = Json.obj(tm.get("a")), x = Json.obj(tm.get("x"));
            if (a != null) t.attributes = a;
            if (x != null) t.extra = x;
            t.received = true;
            l.tiles.add(t);
        }
        return l;
    }

    // ----- helpers -----

    static Long key(String text) {
        if (text == null || text.length() != 16) return null;
        long v = 0;
        for (int k = 0; k < 16; k++) {
            char c = text.charAt(k);
            int d;
            if (c >= '0' && c <= '9') d = c - '0';
            else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
            else return null;
            v = (v << 4) | d;
        }
        return v;
    }

    static String keyText(long v) {
        String h = Long.toHexString(v);
        StringBuilder b = new StringBuilder();
        for (int k = h.length(); k < 16; k++) b.append('0');
        return b.append(h).toString();
    }

    static long fnv1a(String s) {
        int h = 0x811C9DC5;
        for (byte b : s.getBytes(Pb.UTF8)) {
            h ^= (b & 0xff);
            h *= 16777619;
        }
        return h & 0xFFFFFFFFL;
    }

    static String nz(String s) { return s == null ? "" : s; }

    private static String clip(String s) { return s.length() > 300 ? s.substring(0, 300) + "…" : s; }
}
