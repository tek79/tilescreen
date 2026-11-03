package app.tilescreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

import android.view.MotionEvent;
import android.widget.FrameLayout;

import app.tilescreen.core.Screen;
import app.tilescreen.core.Tessera;
import app.tilescreen.core.ScreenSettings;
import app.tilescreen.ui.SaverView;
import app.tilescreen.ui.TileView;

public final class MainActivity extends Activity implements Screen.Ui, ScreenSettings.Ui, TileView.Host {
    private static final String[] ORIENTATIONS = {"landscape", "portrait", "auto"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private TileView view;
    private SaverView saver;
    private View dark;
    private App app;
    private WidgetPage widgets;
    private boolean swallowing;
    private Lift lift;
    private boolean lastAndroid, lastDimmed, resumed;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = App.get();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        startService(new Intent(this, ScreenService.class));
        view = new TileView(this, this);
        lift = new Lift(this, new Lift.Moved() {
            public void moved(String how) {
                app.log("lift to wake: " + how);
                app.screen().screenSettings.lifted();
            }
        });
        // Standby, as a Tessera screen has it: the screensaver over the tiles, or the glass dark at a level of 0.
        SaverView.dpi = getResources().getDisplayMetrics().xdpi;
        saver = new SaverView(this);
        saver.setVisibility(View.GONE);
        dark = new View(this);
        dark.setBackgroundColor(0xFF000000);
        dark.setVisibility(View.GONE);
        widgets = new WidgetPage(this, new WidgetPage.Host() {
            public void showTiles() { show(Screen.TILES); }
            public void showWidgets() { if (widgets.getVisibility() != View.VISIBLE) show(Screen.WIDGETS); }
            public void openLauncher() { show(Screen.ANDROID); }
            public void startForResult(Intent i, int request) {
                try {
                    startActivityForResult(i, request);
                } catch (Exception e) {
                    app.log("widgets: couldn't open " + i.getAction() + ": " + e);
                }
            }
        });
        widgets.setVisibility(View.GONE);
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        root.addView(widgets);
        root.addView(saver);
        root.addView(dark);
        setContentView(root);
        applyOrientation();
        keepScreenOn();
        app.onTurn = new Runnable() {
            public void run() {
                applyOrientation();
                view.changed();
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        immersive();
        Screen s = app.screen();
        s.setUi(this);
        s.screenSettings.setUi(this);
        view.changed();
        // Back in front (from the launcher, an app or an alarm): the page it shows, and the backlight as standby wants it.
        s.showing(widgets.getVisibility() == View.VISIBLE ? Screen.WIDGETS : Screen.TILES);
        widgets.resumed();
        if (lastLevel >= 0) {
            shownLevel = -1;
            brightnessFor(lastAndroid, lastLevel);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        widgets.start();
    }

    @Override
    protected void onStop() {
        widgets.stop();
        super.onStop();
        // Out of sight with the glass on and no screensaver of Android's starting: another app is in front.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!isFinishing() && pm.isInteractive() && System.currentTimeMillis() - dreamStarted > 5000) {
            app.screen().showing(Screen.ANDROID);
        }
    }

    private long dreamStarted;

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // Another app or an alarm in front: the backlight as Android has it, so an alarm that rings at night brightness
        // 0 isn't rung on a dark glass. (The window's own brightness leaves with the window; only root's write stays.)
        // Not with Use Android screensaver: its screensaver has just covered the app and sets the panel itself.
        if (shownLevel >= 0 && shownLevel < 100 && !app.screen().screenSettings.androidSaver()) restoreBacklight();
    }

    private void restoreBacklight() {
        shownLevel = -1;
        final int system = android.provider.Settings.System.getInt(getContentResolver(),
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
        new Thread(new Runnable() {
            public void run() {
                if (!Root.available()) return;
                Root.writeBacklight(Math.max(1, Math.round(system / 255f * Root.max())));
                app.log("backlight: another app in front, back to Android's brightness (" + system + "/255)");
            }
        }, "backlight").start();
    }

    @Override
    protected void onActivityResult(int request, int code, Intent data) {
        if (!widgets.result(request, code)) super.onActivityResult(request, code, data);
    }

    // ----- what the glass shows: the tiles, the widget page, or Android's launcher -----

    /** Screen.Ui: the Showing select, show_page, or a key here. */
    public void show(final String what) {
        main.post(new Runnable() {
            public void run() { showNow(what); }
        });
    }

    private void showNow(String what) {
        Screen s = app.screen();
        if (Screen.ANDROID.equals(what)) {
            if (openLauncher()) return;
            what = Screen.WIDGETS;
        }
        boolean wantWidgets = Screen.WIDGETS.equals(what);
        widgets.setVisibility(wantWidgets ? View.VISIBLE : View.GONE);
        if (!wantWidgets) view.changed();
        s.showing(wantWidgets ? Screen.WIDGETS : Screen.TILES);
        // From another app: the screen in front again.
        Intent front = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(front);
        } catch (Exception ignored) {
        }
        immersive();
    }

    /** Android's own launcher (Trebuchet or another home app), opened as an app; Home comes back here. */
    private boolean openLauncher() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<android.content.pm.ResolveInfo> found = pm.queryIntentActivities(home, 0);
        final java.util.List<android.content.pm.ResolveInfo> others = new java.util.ArrayList<android.content.pm.ResolveInfo>();
        for (android.content.pm.ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            if (!pkg.equals(getPackageName()) && !pkg.equals("com.android.settings") && !pkg.contains("fallbackhome")
                    && !pkg.equals("com.android.provision")) others.add(r);
        }
        if (others.isEmpty()) {
            app.log("launcher: no other home app on this tablet");
            return false;
        }
        android.content.pm.ResolveInfo r = others.get(0);
        Intent open = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(open);
            app.log("launcher: opened " + r.activityInfo.packageName);
            app.screen().showing(Screen.ANDROID);
            return true;
        } catch (Exception e) {
            app.log("launcher: couldn't open " + r.activityInfo.packageName + ": " + e);
            return false;
        }
    }

    /** TileView.Host: a swipe right from page 1. */
    public void openWidgets() { show(Screen.WIDGETS); }

    @Override
    protected void onDestroy() {
        lift.listen(false);
        Screen s = app.screen();
        s.setUi(null);
        s.screenSettings.setUi(null);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    @Override
    public void onBackPressed() {
        // A wall screen has nowhere to go back to; the widget page goes back to the tiles.
        if (widgets.getVisibility() == View.VISIBLE) show(Screen.TILES);
    }

    /** Every touch restarts standby's time; one on a dimmed screen only wakes it, and nothing under it acts. */
    private int saverKey = -1;
    private long saverKeyAt;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        ScreenSettings ss = app.screen().screenSettings;
        int a = ev.getActionMasked();
        // A player's key on the screensaver acts and leaves the screen in standby; every other touch wakes it.
        if (a == MotionEvent.ACTION_DOWN && saver.getVisibility() == View.VISIBLE) {
            saverKey = saver.keyAt(ev.getX(), ev.getY());
            saverKeyAt = System.currentTimeMillis();
        }
        if (saverKey >= 0) {
            if (a == MotionEvent.ACTION_UP) {
                if (saver.keyAt(ev.getX(), ev.getY()) == saverKey) saver.press(saverKey, System.currentTimeMillis() - saverKeyAt);
                saverKey = -1;
            } else if (a == MotionEvent.ACTION_CANCEL) {
                saverKey = -1;
            }
            return true;
        }
        if (a == MotionEvent.ACTION_DOWN) swallowing = ss.dimmed();
        boolean up = a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
        if (a == MotionEvent.ACTION_DOWN || up) ss.touched(!up);
        if (swallowing) return true;
        return super.dispatchTouchEvent(ev);
    }

    private void immersive() {
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** Landscape or portrait from the app's settings, turned half a turn by Tessera's Rotation. */
    private void applyOrientation() {
        String o = app.orientation();
        boolean turned = app.screen().screenSettings.rotation() == 180;
        int want;
        // Landscape and portrait follow the tablet either way up while Android's Auto-rotate is on (USER_*), and stay
        // as they are while it is off; Tessera's Rotation 180° turns them over for good.
        if (o.equals("auto")) want = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
        else if (o.equals("portrait")) want = turned ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        else want = turned ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
        if (getRequestedOrientation() != want) setRequestedOrientation(want);
    }

    // ----- Screen.Ui, called from the network threads -----

    public void alert(final Screen.Alert a) {
        view.showAlert(a);
        if (a.flash) blink();
    }

    /**
     * An alert with `flash`: the backlight blinks four times as it arrives, off for 140 ms and on for 180 ms
     * (packages/features/backlight.yaml alert_flash), then stays as standby or awake wants it. "Off" is the window at its
     * least with the black cover over it, so it blinks on a tablet without root too.
     */
    private void blink() {
        final long start = 250;  // after the alert woke the screen
        for (int i = 0; i < 4; i++) {
            final long at = start + i * 320L;
            main.postDelayed(new Runnable() {
                public void run() {
                    setWindowBrightness(0f);
                    dark.setVisibility(View.VISIBLE);
                }
            }, at);
            main.postDelayed(new Runnable() {
                public void run() {
                    dark.setVisibility(View.GONE);
                    int level = Math.max(lastLevel, 5);
                    if (lastAndroid) setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
                    else setWindowBrightness(Math.max(0.004f, perceived(level)));
                }
            }, at + 140);
        }
        // Then the backlight as it should be, the root's panel level with it.
        main.postDelayed(new Runnable() {
            public void run() {
                shownLevel = -1;
                brightnessFor(lastAndroid, lastLevel);
            }
        }, start + 4 * 320L + 20);
    }

    public void dismissAlert() { view.dismissAlert(); }

    /** A ring: the tiles in front (from the widget page or another app), the chime, the doorbell's view. */
    public void ring(final Tessera.Ring r) {
        boolean again = view.ringing();
        show(Screen.TILES);
        if (r.chime) Chime.play();
        view.ring(r);
        if (!again) app.log("doorbell: " + r.name + " on the glass");
    }

    public void closeRing(String key) { view.closeRing(key); }

    public void ringDoorState(String key, String state) { view.ringDoorState(key, state); }

    public void changed() { view.changed(); }

    /** Home Assistant's open_settings: the settings page on the glass, as on a Tessera screen. */
    public void openSettings() { view.openSettingsPage(); }

    /** The app's own settings: name, grid, board, encryption key, log, Android's settings. */
    public void openAppSettings() {
        main.post(new Runnable() {
            public void run() { openSettingsNow(); }
        });
    }

    // ----- ScreenSettings.Ui -----

    public void power(final boolean dimmed, final int level, final boolean showSaver, final boolean android) {
        main.post(new Runnable() {
            public void run() {
                // The screensaver only when Tessera chose one; without, standby shows the tiles dimmed.
                boolean chosen = showSaver && app.screen().tessera.saver() != null;
                saver.setVisibility(chosen ? View.VISIBLE : View.GONE);
                dark.setVisibility(dimmed && level == 0 && !android ? View.VISIBLE : View.GONE);
                lastLevel = level;
                lastAndroid = android;
                lastDimmed = dimmed;
                brightnessFor(android, level);
                // Lift to wake listens only while the screen is dimmed.
                lift.listen(dimmed && app.screen().screenSettings.liftWake());
                if (!dimmed) immersive();
            }
        });
    }

    public void goHome() {
        // Back to page 1 also leaves the widget page.
        if (widgets.getVisibility() == View.VISIBLE) show(Screen.TILES);
        view.goHome();
    }

    public void closeCards() { view.closeCards(); }

    public void looks() {
        main.post(new Runnable() {
            public void run() {
                applyOrientation();
                keepScreenOn();
                view.changed();
            }
        });
    }

    /**
     * Tessera's standby keeps the glass on and dims it itself. With Use Android screensaver the app lets go, and
     * Android's screen timeout and screensaver (Settings → Display → Daydream / Screen saver) take over.
     */
    private void keepScreenOn() {
        boolean system = app.screen().screenSettings.androidSaver();
        if (system) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Every view of the window can hold the screen on as well; none may while Android has it.
        view.setKeepScreenOn(!system);
        saver.setKeepScreenOn(false);
        dark.setKeepScreenOn(false);
        if (system != handedOver) {
            handedOver = system;
            app.log(system ? "standby: Android's screen timeout and screensaver" : "standby: Tessera's (the app keeps the screen on)");
            if (system) checkAndroidSleep();
        }
    }

    /**
     * What else could keep Android from its screensaver, in the log: Stay awake (developer options), the screen
     * timeout, whether the screensaver is on and starts while charging, and, with root, who holds the screen.
     */
    private void checkAndroidSleep() {
        final android.content.ContentResolver cr = getContentResolver();
        new Thread(new Runnable() {
            public void run() {
                try {
                    int stay = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
                    int timeout = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1);
                    String enabled = android.provider.Settings.Secure.getString(cr, "screensaver_enabled");
                    String charging = android.provider.Settings.Secure.getString(cr, "screensaver_activate_on_sleep");
                    String docked = android.provider.Settings.Secure.getString(cr, "screensaver_activate_on_dock");
                    String which = android.provider.Settings.Secure.getString(cr, "screensaver_components");
                    app.log("android sleep: screen timeout " + (timeout < 0 ? "?" : timeout / 1000 + " s")
                            + ", screensaver " + ("1".equals(enabled) ? "on" : "off (" + enabled + ")")
                            + ", starts while charging " + charging + ", when docked " + docked + ", screensaver " + which);
                    if (stay != 0) app.log("android sleep: Stay awake (developer options) is ON, so the screen never times out"
                            + " while plugged in and the screensaver can't start. Turn it off.");
                    if (!"1".equals(enabled)) app.log("android sleep: the screensaver is off in Settings → Display");
                    if (!"1".equals(charging)) app.log("android sleep: the screensaver doesn't start while charging; set When to start to Either or While charging");
                } catch (Exception e) {
                    app.log("android sleep: couldn't read the settings: " + e);
                }
                if (!Root.available()) return;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "dumpsys power"});
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    String line;
                    boolean locks = false;
                    while ((line = r.readLine()) != null) {
                        String t = line.trim();
                        if (t.startsWith("Wake Locks:")) locks = true;
                        else if (t.isEmpty() || t.startsWith("Suspend Blockers:")) locks = false;
                        if (locks || t.startsWith("mWakefulness=") || t.startsWith("mStayOn=") || t.startsWith("mScreenOffTimeoutSetting=")
                                || t.startsWith("mDreamsEnabledSetting=") || t.startsWith("mDreamsActivateOnSleepSetting=")
                                || t.startsWith("mIsPowered=") || t.startsWith("mUserActivitySummary="))
                            app.log("android sleep: " + t);
                    }
                    r.close();
                } catch (Exception e) {
                    app.log("android sleep: dumpsys failed: " + e);
                }
            }
        }, "android-sleep").start();
    }

    private boolean handedOver;

    /** Sleep with Use Android screensaver: start Android's screensaver now, as its "Start now" does. */
    public void systemSleep() {
        main.post(new Runnable() {
            public void run() {
                Intent dream = new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                dreamStarted = System.currentTimeMillis();
                try {
                    startActivity(dream);
                    app.log("screensaver: started Android's screensaver");
                } catch (Exception e) {
                    app.log("screensaver: Android's could not be started (" + e + "), trying with root");
                    new Thread(new Runnable() {
                        public void run() {
                            if (!Root.run("am start -n com.android.systemui/.Somnambulator")) {
                                app.log("screensaver: no root either; turning the screen off instead");
                                Root.run("input keyevent 223");
                            }
                        }
                    }, "dream").start();
                }
            }
        });
    }

    /** Wake or an alert with Use Android screensaver: the glass on, Android's screensaver ended, the tiles in front. */
    public void systemWake() {
        main.post(new Runnable() {
            public void run() {
                try {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    @SuppressWarnings("deprecation")
                    PowerManager.WakeLock w = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "tilescreen:wake");
                    w.acquire(1500);
                    app.log("screensaver: woke the screen");
                } catch (Exception e) {
                    app.log("screensaver: waking failed: " + e);
                }
                Intent front = new Intent(MainActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(front);
                } catch (Exception ignored) {
                }
                immersive();
            }
        });
    }

    private int shownLevel = -1, lastLevel = -1;

    /**
     * The backlight at a Tessera level, as the eye sees it (CIE 1976 lightness, as libcountertop's panel does): a
     * backlight is far brighter at half its drive than it looks dim, so 20 % has to drive it at about 3 %. With root,
     * the backlight is also written directly, and 0 turns it off rather than to Android's lowest level.
     */
    /**
     * The backlight: Android's own (its slider, or auto-brightness on a tablet with a light sensor) where Android
     * brightness has it (awake, and standby on the charger), else the app's level.
     */
    private void brightnessFor(boolean android, int level) {
        if (android) handBack();
        else backlight(level);
    }

    /** The window lets go of the brightness, and Android sets it again from its own setting. */
    private void handBack() {
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        if (shownLevel == -2) return;
        shownLevel = -2;
        final android.content.ContentResolver cr = getContentResolver();
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                int mode = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                int set = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
                // Standby wrote 0 to the panel past Android with root; should Android not write its level again, its
                // own setting goes in.
                if (Root.available() && "0".equals(Root.readBacklight()) && shownLevel == -2) {
                    Root.writeBacklight(Math.max(1, Math.round(set / 255f * Root.max())));
                }
                app.log("brightness: Android's own (" + (mode == 1 ? "auto" : "slider " + Math.round(set / 2.55f) + "%")
                        + "), backlight now " + Root.readBacklight() + "/" + Root.max());
            }
        }, "backlight").start();
    }

    private void backlight(final int level) {
        final float drive = perceived(level);
        setWindowBrightness(level <= 0 ? 0f : Math.max(0.004f, drive));
        final boolean changed = level != shownLevel;
        shownLevel = level;
        // With root the panel's own level goes in too: on a change, and every time in standby, since Android writes its
        // own (its least, for a window brightness of 0) whenever the window's brightness is set again, which undid a
        // standby of 0 after a while. Never while Android has the screen (Use Android screensaver, or another app or its
        // screensaver in front): the panel is Android's then, and a write would light a dimmed screen.
        final boolean ours = resumed && !app.screen().screenSettings.androidSaver() && (changed || lastDimmed);
        new Thread(new Runnable() {
            public void run() {
                boolean rooted = Root.available();
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                if (rooted && ours) Root.writeBacklight(Math.round(drive * Root.max()));
                if (!changed) return;
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                app.log("brightness " + level + "% -> drive " + Math.round(drive * 1000) / 10f + "%, backlight now "
                        + Root.readBacklight() + "/" + Root.max() + (rooted ? " (root)" : ""));
            }
        }, "backlight").start();
    }

    static float perceived(int percent) {
        float l = Math.max(0, Math.min(100, percent));
        if (l == 0) return 0;
        if (l <= 8) return l / 903.3f;
        float f = (l + 16) / 116f;
        return f * f * f;
    }

    private void setWindowBrightness(float level) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = level;
        getWindow().setAttributes(lp);
    }

    // ----- TileView.Host -----

    public void wake() { app.screen().screenSettings.wakeButton(); }

    public void openSettingsNow() {
        final SharedPreferences p = app.prefs();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText name = field(box, "Screen name (as Home Assistant shows it; empty: the tablet's hostname, "
                + app.automaticName() + ")", app.friendly(), false);
        final EditText cols = field(box, "Columns (landscape)", String.valueOf(app.columns()), true);
        final EditText rows = field(box, "Rows (landscape)", String.valueOf(app.rows()), true);
        final EditText live = field(box, "Live camera tiles at once (0 to 8; the rest show a still at their pace)",
                String.valueOf(app.liveTiles()), true);

        label(box, "Orientation");
        final Spinner orient = new Spinner(this);
        orient.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, ORIENTATIONS));
        orient.setSelection(Math.max(0, indexOf(ORIENTATIONS, app.orientation())));
        box.addView(orient);

        label(box, "Screen shape (the board reported to Tessera)");
        final Spinner board = new Spinner(this);
        final String[] boardLabels = new String[App.BOARDS.length], boardChoices = new String[App.BOARDS.length];
        for (int i = 0; i < App.BOARDS.length; i++) {
            boardChoices[i] = App.BOARDS[i][0];
            boardLabels[i] = App.BOARDS[i][1] + " - " + (App.BOARDS[i][2].isEmpty() ? app.autoBoard() : App.BOARDS[i][2]);
        }
        board.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, boardLabels));
        board.setSelection(Math.max(0, indexOf(boardChoices, app.boardChoice())));
        box.addView(board);

        final CheckBox boot = new CheckBox(this);
        boot.setText("Open this screen when the tablet starts");
        boot.setChecked(p.getBoolean("open_on_boot", true));
        box.addView(boot);

        final CheckBox widgetPage = new CheckBox(this);
        widgetPage.setText("Widget page: swipe right from page 1 for Android widgets, apps and the launcher");
        widgetPage.setChecked(app.widgetPage());
        box.addView(widgetPage);
        android.widget.Button openWidgets = new android.widget.Button(this);
        openWidgets.setText("Open the widget page");
        openWidgets.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { show(Screen.WIDGETS); }
        });
        box.addView(openWidgets);

        TextView note = new TextView(this);
        note.setText("Brightness, standby, night mode, Back to page 1, swiping, page buttons, the home button, dark mode "
                + "and rotation are this screen's Tessera settings: change them in Tessera (the screen → Settings) or "
                + "on the screen's device in Home Assistant.");
        note.setPadding(0, pad / 2, 0, 0);
        box.addView(note);

        Screen s = app.screen();
        TextView info = new TextView(this);
        info.setPadding(0, pad / 2, 0, 0);
        info.setText("ESPHome name: " + app.node() + "\nAddress: " + (app.ip().isEmpty() ? "none" : app.ip()) + ":" + App.API_PORT
                + "\nHome Assistant connections: " + s.device.clients()
                + "\nMay perform actions: " + (s.device.actionSubscribers() > 0 ? "yes" : "no")
                + "\nTessera: " + (s.tessera.shown() ? (s.tessera.fresh() ? "showing a layout" : "showing the kept layout")
                : s.tessera.heard() ? "connected, no tiles yet" : "not heard from yet")
                + "\nScreen layout: " + s.tessera.shapeText()
                + "\nEncryption: " + (s.device.encrypted() ? "on (key set by Home Assistant)" : "off until Home Assistant sets a key")

                + "\nMAC: " + app.mac());
        info.setTextIsSelectable(true);
        box.addView(info);

        if (s.device.encrypted()) {
            TextView keyLine = new TextView(this);
            keyLine.setText("Encryption key (for adding the screen by hand):\n" + app.keyText());
            keyLine.setTextIsSelectable(true);
            keyLine.setPadding(0, pad / 2, 0, 0);
            box.addView(keyLine);
            android.widget.Button forget = new android.widget.Button(this);
            forget.setText("Forget encryption key");
            forget.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Forget the encryption key?")
                            .setMessage("Do this after deleting the screen from Home Assistant, so it can be added again. "
                                    + "A Home Assistant entry that still has the key can't connect until you add the screen again.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Forget", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    app.forgetKey();
                                    app.restart();
                                    app.screen().setUi(MainActivity.this);
                                    view.changed();
                                }
                            }).show();
                }
            });
            box.addView(forget);
        }

        android.widget.Button system = new android.widget.Button(this);
        system.setText("Open Android settings");
        system.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
        box.addView(system);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Tile Screen settings")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Log", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { showLog(); }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String oldNode = app.node(), oldBoard = app.board(), oldName = app.friendly();
                        int oldLive = app.liveTiles();
                        SharedPreferences.Editor e = p.edit();
                        String n = name.getText().toString().trim();
                        // A name of its own only when it differs from the hostname's, so the hostname keeps counting.
                        if (n.isEmpty() || n.equals(app.automaticName())) e.remove("friendly");
                        else e.putString("friendly", n);
                        e.putInt("columns", clamp(cols.getText().toString(), 1, 8, app.columns()));
                        e.putInt("rows", clamp(rows.getText().toString(), 1, 8, app.rows()));
                        e.putString("orientation", ORIENTATIONS[orient.getSelectedItemPosition()]);
                        e.putString("board_choice", boardChoices[board.getSelectedItemPosition()]);
                        e.putBoolean("open_on_boot", boot.isChecked());
                        e.putBoolean("widget_page", widgetPage.isChecked());
                        e.putInt("live_tiles", clamp(live.getText().toString(), 0, 8, app.liveTiles()));
                        e.commit();
                        applyOrientation();
                        // A new count of live tiles goes to Tessera with the next hello, which a restart of the server brings.
                        if (!oldNode.equals(app.node()) || !oldBoard.equals(app.board()) || !oldName.equals(app.friendly())
                                || oldLive != app.liveTiles()) {
                            app.restart();
                            app.screen().setUi(MainActivity.this);
                        } else {
                            app.screen().regrid();
                        }
                        view.changed();
                    }
                })
                .show();
    }

    private void showLog() {
        List<String> lines = app.logLines();
        StringBuilder b = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) b.append(lines.get(i)).append('\n');
        TextView t = new TextView(this);
        t.setText(b.toString());
        t.setTextSize(11);
        t.setTextIsSelectable(true);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(t);
        new AlertDialog.Builder(this).setTitle("Log (newest first)").setView(scroll).setPositiveButton("Close", null).show();
    }

    private EditText field(LinearLayout box, String label, String value, boolean number) {
        label(box, label);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(e);
        return e;
    }

    private void label(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(t);
    }

    private static int clamp(String s, int lo, int hi, int fallback) {
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(s.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }
}
