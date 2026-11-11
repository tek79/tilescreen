package app.tilescreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The page of Android widgets, left of Tessera's page 1: a home screen's widgets (a clock with the next alarm, a
 * calendar, notes) on a grid of the screen's own cells, with keys to the tiles, Android's apps and its launcher. Tile
 * Screen stays the home app the whole time, so Home Assistant can always bring the tiles back (the Showing select,
 * show_page) and Back to page 1 returns here as on the tiles.
 */
final class WidgetPage extends FrameLayout {
    interface Host {
        void showTiles();
        void showWidgets();
        void openLauncher();
        void startForResult(Intent i, int request);
    }

    static final int HOST_ID = 0x7153, REQUEST_BIND = 7301, REQUEST_CONFIGURE = 7302;

    private final Activity activity;
    private final Host host;
    private final AppWidgetManager manager;
    private final AppWidgetHost widgets;
    private final SharedPreferences prefs;
    private final Grid grid;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private int pending = -1;

    /** A widget on the page: its id and how many cells it takes. */
    private static final class Placed {
        int id, cols, rows;
        Placed(int id, int cols, int rows) { this.id = id; this.cols = cols; this.rows = rows; }
    }
    private final List<Placed> placed = new ArrayList<Placed>();

    WidgetPage(Activity a, Host host) {
        super(a);
        this.activity = a;
        this.host = host;
        dp = a.getResources().getDisplayMetrics().density;
        manager = AppWidgetManager.getInstance(a);
        widgets = new AppWidgetHost(a.getApplicationContext(), HOST_ID);
        prefs = App.get().prefs();
        setBackgroundColor(0xFF0F1115);

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(bar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(52 * dp)));
        grid = new Grid(a);
        column.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        load();
    }

    // ---- the bar: back to the tiles, Android's apps and launcher, a widget more ----
    private View bar() {
        LinearLayout b = new LinearLayout(activity);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(8 * dp);
        b.setPadding(pad, 0, pad, 0);
        b.addView(key("‹  Tiles", new Runnable() {
            public void run() { host.showTiles(); }
        }));
        View gap = new View(activity);
        b.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        b.addView(key("Apps", new Runnable() {
            public void run() { pickApp(); }
        }));
        b.addView(key("Launcher", new Runnable() {
            public void run() { host.openLauncher(); }
        }));
        b.addView(key("+  Widget", new Runnable() {
            public void run() { pickWidget(); }
        }));
        return b;
    }

    private TextView key(String words, final Runnable run) {
        TextView t = new TextView(activity);
        t.setText(words);
        t.setTextColor(0xFFF2F4F8);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        int h = Math.round(16 * dp), v = Math.round(8 * dp);
        t.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2C313B);
        bg.setCornerRadius(20 * dp);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(6 * dp);
        t.setLayoutParams(lp);
        t.setOnClickListener(new OnClickListener() {
            public void onClick(View view) { run.run(); }
        });
        return t;
    }

    // ---- widgets kept across restarts: "id:cols:rows,..." ----
    private void load() {
        placed.clear();
        String kept = prefs.getString("widgets", "");
        for (String part : kept.split(",")) {
            String[] f = part.split(":");
            if (f.length != 3) continue;
            try {
                int id = Integer.parseInt(f[0]);
                if (manager.getAppWidgetInfo(id) == null) continue;
                placed.add(new Placed(id, Integer.parseInt(f[1]), Integer.parseInt(f[2])));
            } catch (NumberFormatException ignored) {
            }
        }
        rebuild();
    }

    private void save() {
        StringBuilder b = new StringBuilder();
        for (Placed p : placed) b.append(b.length() == 0 ? "" : ",").append(p.id).append(':').append(p.cols).append(':').append(p.rows);
        prefs.edit().putString("widgets", b.toString()).apply();
    }

    private void rebuild() {
        grid.removeAllViews();
        for (final Placed p : placed) {
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(p.id);
            if (info == null) continue;
            App.get().log("widgets: showing " + p.id + " (" + info.provider.flattenToShortString() + ", " + p.cols + "x" + p.rows + " cells)");
            AppWidgetHostView v = widgets.createView(activity, p.id, info);
            v.setAppWidget(p.id, info);
            Cell c = new Cell(activity, p);
            c.addView(v, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            grid.addView(c);
        }
        if (placed.isEmpty()) {
            TextView hint = new TextView(activity);
            hint.setText("No widgets yet. Tap  + Widget  to add one, such as the clock's.\n"
                    + "Long-press a widget to resize or remove it. Swipe left or tap Tiles to go back.");
            hint.setTextColor(0xFF9AA3B2);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            hint.setGravity(Gravity.CENTER);
            grid.addView(hint);
        }
        grid.requestLayout();
    }

    void start() {
        try { widgets.startListening(); } catch (Exception e) { App.get().log("widgets: couldn't listen: " + e); }
    }

    void stop() {
        try { widgets.stopListening(); } catch (Exception ignored) {}
    }

    // ---- adding one: our own list of the widgets installed, bound with the user's say (or root's) ----
    private void pickWidget() {
        final List<AppWidgetProviderInfo> all = new ArrayList<AppWidgetProviderInfo>(manager.getInstalledProviders());
        final PackageManager pm = activity.getPackageManager();
        Collections.sort(all, new Comparator<AppWidgetProviderInfo>() {
            public int compare(AppWidgetProviderInfo a, AppWidgetProviderInfo b) {
                return label(a, pm).compareToIgnoreCase(label(b, pm));
            }
        });
        if (all.isEmpty()) {
            new AlertDialog.Builder(activity).setMessage("No app on this tablet offers a widget.").setPositiveButton("OK", null).show();
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = label(all.get(i), pm);
        new AlertDialog.Builder(activity).setTitle("Add a widget").setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) { bind(all.get(which)); }
        }).setNegativeButton("Cancel", null).show();
    }

    private static String label(AppWidgetProviderInfo info, PackageManager pm) {
        String app;
        try {
            app = pm.getApplicationLabel(pm.getApplicationInfo(info.provider.getPackageName(), 0)).toString();
        } catch (Exception e) {
            app = info.provider.getPackageName();
        }
        String own = android.os.Build.VERSION.SDK_INT >= 21 ? info.loadLabel(pm) : info.label;
        return own == null || own.equals(app) ? app : app + ": " + own;
    }

    private void bind(final AppWidgetProviderInfo info) {
        final int id = widgets.allocateAppWidgetId();
        setPending(id, false);
        App.get().log("widgets: adding " + info.provider.flattenToShortString() + " as " + id);
        if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) {
            App.get().log("widgets: bound");
            configure(id);
            return;
        }
        // Not allowed yet: with root, the permission is granted for good (as "Always allow" would), else Android asks.
        new Thread(new Runnable() {
            public void run() {
                final boolean granted = Root.available() && Root.exec("appwidget grantbind --package " + activity.getPackageName()
                        + " --user 0 || cmd appwidget grantbind --package " + activity.getPackageName() + " --user 0");
                main.post(new Runnable() {
                    public void run() {
                        if (granted && manager.bindAppWidgetIdIfAllowed(id, info.provider)) {
                            App.get().log("widgets: binding granted with root");
                            configure(id);
                            return;
                        }
                        App.get().log("widgets: asking Android to allow binding" + (granted ? " (root's grant didn't take)" : ""));
                        Intent ask = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
                        host.startForResult(ask, REQUEST_BIND);
                    }
                });
            }
        }, "widget-bind").start();
    }

    /**
     * The widget on its way, kept in the settings too: a tablet short of memory may close the app while Android's
     * dialog or the widget's setup is open, and the answer then comes to a new one.
     */
    private void setPending(int id, boolean configuring) {
        pending = id;
        this.configuring = configuring;
        prefs.edit().putInt("widget_pending", id).putBoolean("widget_configuring", configuring).apply();
    }

    private boolean configuring;

    private void configure(int id) {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info != null && info.configure != null) {
            setPending(id, true);
            App.get().log("widgets: opening its setup " + info.configure.flattenToShortString());
            try {
                // Through the widget host, as a launcher does: a setup screen is seldom exported, and only the host may
                // open it (AppWidgetHost.startAppWidgetConfigureActivityForResult, Android 5+).
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    widgets.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null);
                } else {
                    // Before Android 5 the host opens it itself, as launchers then did.
                    android.content.Intent i = new android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
                    i.setComponent(info.configure);
                    i.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    activity.startActivityForResult(i, REQUEST_CONFIGURE);
                }
                return;
            } catch (Exception e) {
                App.get().log("widgets: its setup couldn't open (" + e + "); adding it as it is");
            }
        }
        add(id);
    }

    /** From MainActivity.onActivityResult. */
    boolean result(int request, int code) {
        if (request != REQUEST_BIND && request != REQUEST_CONFIGURE) return false;
        int id = pending >= 0 ? pending : prefs.getInt("widget_pending", -1);
        App.get().log("widgets: " + (request == REQUEST_BIND ? "Android's dialog" : "the widget's setup") + " answered "
                + (code == Activity.RESULT_OK ? "OK" : "cancel (" + code + ")") + " for " + id);
        if (id < 0) return true;
        // Bound is bound, whatever the dialog answered: some versions answer cancel after "Allow".
        boolean bound = manager.getAppWidgetInfo(id) != null;
        if (request == REQUEST_BIND) {
            // Not bound yet: some versions answer at once, before "Allow" is tapped; resumed() looks again later.
            if (bound) configure(id);
        } else if (code == Activity.RESULT_OK && bound) {
            add(id);
        } else {
            drop(id);
        }
        return true;
    }

    /** Back in front with a widget still on its way and nothing to answer for it: add it if Android bound it. */
    void resumed() {
        int id = pending >= 0 ? pending : prefs.getInt("widget_pending", -1);
        if (id < 0 || prefs.getBoolean("widget_configuring", false)) return;
        for (Placed p : placed) if (p.id == id) { setPending(-1, false); return; }
        if (manager.getAppWidgetInfo(id) != null) {
            App.get().log("widgets: " + id + " was bound while the app was away; adding it");
            configure(id);
        }
    }

    private void drop(int id) {
        widgets.deleteAppWidgetId(id);
        setPending(-1, false);
        App.get().log("widgets: " + id + " not added");
    }

    private void add(int id) {
        setPending(-1, false);
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info == null) return;
        // Its size in cells, from the least it asks for (Android's own rule: 70 dp a cell, less 30).
        int cols = Math.max(1, Math.min(grid.cols(), (int) Math.ceil((info.minWidth / dp + 30) / 70f)));
        int rows = Math.max(1, Math.min(grid.rows(), (int) Math.ceil((info.minHeight / dp + 30) / 70f)));
        cols = Math.max(cols, Math.min(grid.cols(), 2));
        placed.add(new Placed(id, cols, rows));
        save();
        rebuild();
        host.showWidgets();
        App.get().log("widgets: added " + info.provider.flattenToShortString() + " at " + cols + "x" + rows);
    }

    private void edit(final Placed p) {
        String[] items = {"Wider", "Narrower", "Taller", "Shorter", "Move first", "Remove"};
        new AlertDialog.Builder(activity).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which == 0) p.cols = Math.min(grid.cols(), p.cols + 1);
                if (which == 1) p.cols = Math.max(1, p.cols - 1);
                if (which == 2) p.rows = Math.min(grid.rows(), p.rows + 1);
                if (which == 3) p.rows = Math.max(1, p.rows - 1);
                if (which == 4) {
                    placed.remove(p);
                    placed.add(0, p);
                }
                if (which == 5) {
                    placed.remove(p);
                    widgets.deleteAppWidgetId(p.id);
                }
                save();
                rebuild();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    // ---- Android's apps, as a plain list ----
    private void pickApp() {
        final PackageManager pm = activity.getPackageManager();
        Intent launchable = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> apps = new ArrayList<ResolveInfo>(pm.queryIntentActivities(launchable, 0));
        for (int i = apps.size() - 1; i >= 0; i--) if (apps.get(i).activityInfo.packageName.equals(activity.getPackageName())) apps.remove(i);
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).loadLabel(pm).toString();
        new AlertDialog.Builder(activity).setTitle("Open an app").setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                ResolveInfo r = apps.get(which);
                Intent open = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    activity.startActivity(open);
                } catch (Exception e) {
                    App.get().log("apps: couldn't open " + r.activityInfo.packageName + ": " + e);
                }
            }
        }).setNegativeButton("Cancel", null).show();
    }

    // ---- a swipe left over the page goes back to the tiles ----
    private float downX, downY;
    private boolean swiped;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swiped = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (!swiped && dx < -getWidth() * 0.18f && Math.abs(dx) > Math.abs(dy) * 2) {
                    swiped = true;
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Here when no widget took the touch (the empty grid, the bar's gaps) or once a swipe was seen over one.
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swiped = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (!swiped && dx < -getWidth() * 0.18f && Math.abs(dx) > Math.abs(dy) * 2) swiped = true;
                return true;
            case MotionEvent.ACTION_UP:
                if (swiped) host.showTiles();
                swiped = false;
                return true;
        }
        return true;
    }

    // ---- a widget's cell: a long press (that the widget itself doesn't take) opens its menu ----
    private final class Cell extends FrameLayout {
        final Placed p;
        String told = "";
        private float x0, y0;
        private boolean longFired;
        private final Runnable longPress = new Runnable() {
            public void run() {
                longFired = true;
                edit(p);
            }
        };

        Cell(Activity a, Placed p) {
            super(a);
            this.p = p;
            int pad = Math.round(4 * dp);
            setPadding(pad, pad, pad, pad);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = ev.getX();
                    y0 = ev.getY();
                    longFired = false;
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout() + 200);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(ev.getX() - x0) > slop || Math.abs(ev.getY() - y0) > slop) main.removeCallbacks(longPress);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    break;
            }
            return longFired;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) { return longFired; }
    }

    // ---- the grid: the screen's own cells, each widget at the first place it fits ----
    private final class Grid extends ViewGroup {
        Grid(Activity a) { super(a); }

        /** The grid's cells: the tiles' own, columns and rows swapped on standing glass. */
        int cols() { return Math.max(1, getWidth() >= getHeight() ? App.get().columns() : App.get().rows()); }
        int rows() { return Math.max(1, getWidth() >= getHeight() ? App.get().rows() : App.get().columns()); }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
            int cols = cols(), rows = rows();
            float cw = (float) getMeasuredWidth() / cols, ch = (float) getMeasuredHeight() / rows;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v instanceof Cell) {
                    Placed p = ((Cell) v).p;
                    v.measure(MeasureSpec.makeMeasureSpec(Math.round(Math.min(p.cols, cols) * cw), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(Math.round(Math.min(p.rows, rows) * ch), MeasureSpec.EXACTLY));
                } else {
                    v.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
                }
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int cols = cols(), rows = rows();
            float cw = (float) (r - l) / cols, ch = (float) (b - t) / rows;
            boolean[][] used = new boolean[rows][cols];
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (!(v instanceof Cell)) {
                    v.layout(0, 0, r - l, b - t);
                    continue;
                }
                Placed p = ((Cell) v).p;
                int pc = Math.min(p.cols, cols), pr = Math.min(p.rows, rows);
                int[] at = spot(used, pc, pr);
                if (at == null) {
                    v.layout(0, 0, 0, 0); // no room left: it waits until another goes
                    continue;
                }
                for (int y = at[1]; y < at[1] + pr; y++) for (int x = at[0]; x < at[0] + pc; x++) used[y][x] = true;
                int left = Math.round(at[0] * cw), top = Math.round(at[1] * ch);
                v.layout(left, top, left + v.getMeasuredWidth(), top + v.getMeasuredHeight());
                // The widget's size, told to its app whenever it changes: many widgets (the clock's among them) size
                // their text from it and draw nothing until they know it.
                int wdp = Math.round((v.getMeasuredWidth() - v.getPaddingLeft() - v.getPaddingRight()) / dp);
                int hdp = Math.round((v.getMeasuredHeight() - v.getPaddingTop() - v.getPaddingBottom()) / dp);
                String size = wdp + "x" + hdp;
                if (!size.equals(((Cell) v).told)) {
                    ((Cell) v).told = size;
                    App.get().log("widgets: " + p.id + " at cell " + at[0] + "," + at[1] + ", " + size + " dp");
                    android.os.Bundle o = new android.os.Bundle();
                    o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wdp);
                    o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wdp);
                    o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hdp);
                    o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hdp);
                    try {
                        manager.updateAppWidgetOptions(p.id, o);
                    } catch (Exception e) {
                        App.get().log("widgets: telling " + p.id + " its size failed: " + e);
                    }
                }
            }
        }

        private int[] spot(boolean[][] used, int w, int h) {
            for (int y = 0; y + h <= used.length; y++) {
                for (int x = 0; x + w <= used[0].length; x++) {
                    boolean free = true;
                    for (int yy = y; yy < y + h && free; yy++) for (int xx = x; xx < x + w && free; xx++) free = !used[yy][xx];
                    if (free) return new int[]{x, y};
                }
            }
            return null;
        }
    }
}
