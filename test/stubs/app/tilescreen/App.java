package app.tilescreen;
import app.tilescreen.core.*;
/** The desktop stand-in for the Android App: the same methods TileView calls. */
public final class App {
    public static final int API_PORT = 6053;
    static App self;
    public Screen screen;
    public int w = 1024, h = 600, cols = 4, rows = 3;
    public static App get() { return self; }
    public static void set(App a) { self = a; }
    public Screen screen() { return screen; }
    public void log(String l) { System.out.println("[log] " + l); }
    public boolean widgetPage() { return true; }
    public String friendly() { return "Kitchen Fire"; }
    public String node() { return "kitchen-fire"; }
    public String ip() { return "192.168.1.42"; }
    public String wifiText() { return "Home IoT · good (-61 dBm)"; }
    public String version() { return "0.5.9"; }
    public void tick(boolean soft) { System.out.println("[sound] " + (soft ? "step" : "tap")); }
    public void restart() { System.out.println("[app] restart"); }
    public void setCanvas(int w, int h) { this.w = w; this.h = h; if (screen != null) screen.regrid(); }
    public Tessera.Shape shape() {
        return new Tessera.Shape() {
            public int width() { return w; } public int height() { return h; }
            public int columns() { return h > w ? rows : cols; } public int rows() { return h > w ? cols : rows; }
            public int[] kept(boolean upright) { return upright ? new int[]{rows, cols} : new int[]{cols, rows}; }
            public void choose(boolean upright, int c, int r) { System.out.println("[app] grid " + c + "x" + r + (upright ? " standing" : " lying")); if (!upright) { cols = c; rows = r; } if (screen != null) screen.regrid(); }
        };
    }
}
