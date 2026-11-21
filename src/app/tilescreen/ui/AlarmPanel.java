package app.tilescreen.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/**
 * An alarm panel's card and its keypad, and a lock's keypad (docs/ALARM_PANEL.md, alarm_panel.h), as Home Assistant's
 * own dialogs do it: a key per mode the panel supports, a code asked for only where Home Assistant asks for one, the
 * code sent as the action's `code` and forgotten at once, and the keypad first, on red, when someone came in or the
 * alarm goes off.
 *
 * Pure logic, free of drawing: TileView draws it.
 */
final class AlarmPanel {
    private AlarmPanel() {}

    /** One mode the card offers: the state it leads to, the action that sets it, its icon, its feature bit (0: disarm). */
    static final class Mode {
        final String state, service, icon, label;
        final int feature;
        Mode(String state, String service, String icon, String label, int feature) {
            this.state = state; this.service = service; this.icon = icon; this.label = label; this.feature = feature;
        }
    }

    /** Home Assistant's order (ALARM_MODES); trigger is left out, as its dialogs leave it out. Disarm is last. */
    static final Mode[] MODES = {
            new Mode("armed_home", "alarm_control_panel.alarm_arm_home", "shield-home", "Home", 1),
            new Mode("armed_away", "alarm_control_panel.alarm_arm_away", "shield-lock", "Away", 2),
            new Mode("armed_night", "alarm_control_panel.alarm_arm_night", "shield-moon", "Night", 4),
            new Mode("armed_vacation", "alarm_control_panel.alarm_arm_vacation", "shield-airplane", "Vacation", 32),
            new Mode("armed_custom_bypass", "alarm_control_panel.alarm_arm_custom_bypass", "security", "Custom", 16),
            new Mode("disarmed", "alarm_control_panel.alarm_disarm", "shield-off", "Disarm", 0),
    };
    static final Mode DISARM = MODES[MODES.length - 1];

    /** A lock's: lock, unlock, and open where it supports it (LockEntityFeature.OPEN). */
    static final Mode LOCK = new Mode("locked", "lock.lock", "lock", "Lock", 0),
            UNLOCK = new Mode("unlocked", "lock.unlock", "lock-open-variant", "Unlock", 0),
            OPEN = new Mode("open", "lock.open", "door-open", "Open", 1);

    static boolean lock(Tessera.Tile t) { return t.domain().equals("lock"); }

    static List<Mode> modes(Tessera.Tile t) {
        int f = t.number("supported_features") == null ? 0 : t.number("supported_features").intValue();
        List<Mode> out = new ArrayList<Mode>();
        if (lock(t)) {
            out.add(LOCK);
            out.add(UNLOCK);
            if ((f & 1) != 0) out.add(OPEN);
            return out;
        }
        for (Mode m : MODES) if (m.feature == 0 || (f & m.feature) != 0) out.add(m);
        return out;
    }

    static Mode mode(String service) {
        for (Mode m : MODES) if (m.service.equals(service)) return m;
        if (LOCK.service.equals(service)) return LOCK;
        if (UNLOCK.service.equals(service)) return UNLOCK;
        if (OPEN.service.equals(service)) return OPEN;
        return null;
    }

    /** Home Assistant's colour for the state: armed green, the moments in between orange, going off red. */
    static int colour(String state) {
        if ("triggered".equals(state)) return Style.RED;
        if ("arming".equals(state) || "pending".equals(state) || "disarming".equals(state)) return Style.ORANGE;
        return Style.GREEN;
    }

    /** Home Assistant's state icon (icons.json of alarm_control_panel). */
    static String icon(String state) {
        if ("disarmed".equals(state)) return "shield-off";
        if ("armed_home".equals(state)) return "shield-home";
        if ("armed_away".equals(state)) return "shield-lock";
        if ("armed_night".equals(state)) return "shield-moon";
        if ("armed_vacation".equals(state)) return "shield-airplane";
        if ("armed_custom_bypass".equals(state)) return "security";
        if ("pending".equals(state)) return "shield-outline";
        if ("triggered".equals(state)) return "bell-ring";
        return "shield";
    }

    /** The exit delay, the entry delay and the alarm going off: Home Assistant's dialog shows one Disarm key then. */
    static boolean urgent(String state) { return "arming".equals(state) || "pending".equals(state) || "triggered".equals(state); }

    /** Someone came in or the alarm goes off: the screen wakes with the keypad. */
    static boolean callsForAttention(String state) { return "pending".equals(state) || "triggered".equals(state); }

    /** Home Assistant keeps a default code in the entity's options (`dc`): it fills it in and the screen asks for none. */
    static boolean saved(Tessera.Tile t) { return Json.integer(t.extra, "dc") != null && Json.integer(t.extra, "dc") == 1; }

    /** Whether choosing this mode asks for a code first (setProtectedAlarmControlPanelMode, the lock's dialog). */
    static boolean needsCode(Tessera.Tile t, Mode m) {
        String format = t.text("code_format");
        if (saved(t) || format == null || format.isEmpty()) return false;
        if (lock(t)) return true;
        Object arm = t.attributes.get("code_arm_required");
        boolean armRequired = !(arm instanceof Boolean) || (Boolean) arm;
        return m == DISARM || armRequired;
    }

    /** A code of letters can't be typed here: only digits are offered. */
    static boolean typable(Tessera.Tile t) { return !"text".equals(t.text("code_format")); }

    static final int CODE_MAX = 10, SEND_MIN = 4, SEND_MAX = 8;

    /** The alarm's "send the code after" choice from the Events page (`send_after`), 0 for the OK key. Never a lock's. */
    static int sendAfter(Tessera.Tile t) {
        if (lock(t)) return 0;
        Object o = t.options.get("send_after");
        int n = 0;
        try { n = o instanceof Double ? ((Double) o).intValue() : o == null ? 0 : Integer.parseInt(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { n = 0; }
        return n >= SEND_MIN && n <= SEND_MAX ? n : 0;
    }

    /** The alarm's choice that this screen stays quiet when it goes off (`alarm_wake: "off"`). */
    static boolean quiet(Tessera.Tile t) { return "off".equals(String.valueOf(t.options.get("alarm_wake"))); }

    static int dotsShown(int sendAfter, int typed) { return sendAfter > 0 ? sendAfter : Math.max(4, typed); }

    /** A panel that goes off or counts someone in and asks a code to disarm: its card is the keypad at once, on red. */
    static boolean keypadFirst(Tessera.Tile t) {
        return t.domain().equals("alarm_control_panel") && callsForAttention(t.state) && needsCode(t, DISARM) && typable(t);
    }

    /** Whether a state is where a mode was heading: the mode itself or the step on the way. */
    static boolean reached(String state, Mode m) {
        if (state == null || m == null) return false;
        if (state.equals(m.state)) return true;
        if (m == DISARM) return "disarming".equals(state);
        if (m == LOCK) return "locking".equals(state);
        if (m == UNLOCK) return "unlocking".equals(state) || "open".equals(state) || "opening".equals(state);
        if (m == OPEN) return "opening".equals(state);
        return "arming".equals(state);
    }

    /** Seconds left of an exit or entry delay (`ae`, epoch), 0 when the integration doesn't say. */
    static long secondsLeft(Tessera.Tile t) {
        Object o = t.extra.get("ae");
        if (!(o instanceof Double)) return 0;
        double ends = (Double) o;
        long left = (long) Math.ceil(ends - System.currentTimeMillis() / 1000.0);
        return Math.max(0, left);
    }

    static String clock(long seconds) { return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60); }

    // ---- an attempt with a code, and the keypad's lock after wrong ones, per entity ----

    static final long ATTEMPT_WAIT_MS = 10000;
    static final int FREE_TRIES = 3;
    static final long FIRST_LOCK_S = 30, MAX_LOCK_S = 900;

    static long lockSeconds(int failures) {
        if (failures < FREE_TRIES) return 0;
        long s = FIRST_LOCK_S;
        for (int i = FREE_TRIES; i < failures && s < MAX_LOCK_S; i++) s *= 2;
        return Math.min(s, MAX_LOCK_S);
    }

    static final class Keys {
        int failures;
        long lockedUntil;       // uptime ms
        Mode trying;            // an attempt with a code, until it is settled
        long since;             // uptime ms
        boolean wrong;          // the last attempt failed

        long lockedFor(long now) { return lockedUntil > now ? (lockedUntil - now + 999) / 1000 : 0; }

        void fail(long now) {
            failures++;
            wrong = true;
            long s = lockSeconds(failures);
            lockedUntil = s > 0 ? now + s * 1000 : 0;
        }

        void success() { failures = 0; lockedUntil = 0; wrong = false; }
    }

    private static final Map<String, Keys> KEYS = new HashMap<String, Keys>();

    static synchronized Keys keys(String entity) {
        Keys k = KEYS.get(entity);
        if (k == null) KEYS.put(entity, k = new Keys());
        return k;
    }
}
