package app.tilescreen;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.view.Surface;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.util.List;

import app.tilescreen.core.EspDevice;
import app.tilescreen.core.Screen;
import app.tilescreen.core.ScreenSettings;
import app.tilescreen.core.Rtsp;
import app.tilescreen.core.Sensing;
import app.tilescreen.core.Tessera;
import app.tilescreen.ui.Pictures;

/**
 * The tablet's own camera as Tessera's Screen camera (screen_camera 0.7.3): pictures for Home Assistant while it asks for
 * them, and the room's light level and motion while that setting is on. The front camera, through Android's first
 * camera interface (every Android this app runs on has it), its preview frames only: no shutter sound, and nothing is
 * kept. The camera runs only while something uses it, and the dot in the top right corner says so: solid while Home
 * Assistant watches, a ring while the camera only senses.
 */
@SuppressWarnings("deprecation")
final class ScreenCamera implements android.hardware.Camera.PreviewCallback, Sensing.Out {

    /** A picture keeps the camera warm this long: Home Assistant's dashboards ask for one every ten seconds. */
    static final long PICTURE_MS = 12000, STREAM_QUIET_MS = 3000;
    /** At most five pictures a second, as the plugin's camera (max_framerate 5); sensing looks five times a second. */
    static final long PICTURE_GAP_MS = 200, SENSE_GAP_MS = 200;
    /** A camera just started adjusts its exposure: its first pictures wait this long. */
    static final long WARM_MS = 900;
    static final int QUALITY = 80;

    /** The dot's state for the activity: 0 none, 1 a ring (sensing), 2 solid (watched). */
    static volatile int dot;
    static volatile Runnable onDot;

    private final App app;
    private final Screen screen;
    private final ScreenSettings settings;
    private final EspDevice device;
    private final EspDevice.Camera entity;
    private final Sensing sensing = new Sensing(this);
    private final HandlerThread thread = new HandlerThread("camera");
    private final Handler handler;
    // The camera chosen (Camera side): its number, whether it faces the room, how it is mounted.
    private volatile int cameraId, sensorTurn;
    private volatile boolean front;

    // On the camera's thread only.
    private android.hardware.Camera camera;
    private SurfaceTexture texture;
    private int width, height;
    private long openedAt, lastPicture, lastSense, lastLight, lastStream, wantedUntil, retryAt, lastWake;
    private boolean closed, sensed;
    // The video (part Video): its server, encoder and sound; the encoder made for the size, turn and field it runs at.
    private final Rtsp rtsp;
    private final VideoSound sound;
    private VideoEncoder encoder;
    private volatile boolean videoPlaying;
    private long warmUntil, lastVideoFrame, encoderRetryAt;
    private int encoderTurn = -1, encoderField = -1;
    private byte[] videoFrame;
    // The tablet's own camera on a tile of its own: until when a tile wants it, its shape, the newest picture for it.
    private volatile long ownUntil;
    private volatile int ownW, ownH, ownRadius, ownGround;
    private volatile boolean ownContain;
    private volatile android.graphics.Bitmap ownPicture;
    private long lastOwn;
    private float shownLux = -1;
    // The tablet's own light sensor, when it has one: the light level from it, in real lux.
    private final SensorManager sensors;
    private final Sensor lightSensor;
    private volatile float sensorLux = -1;
    private boolean listening;

    static boolean usable(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA) && !pm.hasSystemFeature("android.hardware.camera.front")) {
                return false;
            }
            if (c.checkCallingOrSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            return android.hardware.Camera.getNumberOfCameras() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    ScreenCamera(App app, Screen screen) {
        this.app = app;
        this.screen = screen;
        this.settings = screen.screenSettings;
        this.device = screen.device;
        int fronts = 0, backs = 0, n = android.hardware.Camera.getNumberOfCameras();
        android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        for (int i = 0; i < n; i++) {
            android.hardware.Camera.getCameraInfo(i, info);
            if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) fronts++; else backs++;
        }
        this.entity = screen.enableCamera(fronts > 0 && backs > 0);
        choose();
        sensors = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        thread.start();
        handler = new Handler(thread.getLooper());
        app.log("camera: " + n + " camera" + (n == 1 ? "" : "s") + (lightSensor != null
                ? ", light level from the light sensor" : ", light level from the picture"));
        entity.source = new EspDevice.Camera.Source() {
            public void wanted(final boolean stream) {
                handler.post(new Runnable() {
                    public void run() {
                        long now = System.currentTimeMillis();
                        wantedUntil = now + PICTURE_MS;
                        if (stream) lastStream = now;
                        update();
                    }
                });
            }
        };
        settings.onCamera = new Runnable() {
            public void run() {
                handler.post(new Runnable() {
                    public void run() {
                        int was = cameraId;
                        choose();
                        if (was != cameraId) stop(); // the other side's camera from the next update
                        sensing.reset();
                        update();
                    }
                });
            }
        };
        rtsp = new Rtsp(new Rtsp.Source() {
            public void playing(final boolean anyone) {
                handler.post(new Runnable() {
                    public void run() {
                        videoPlaying = anyone;
                        update();
                    }
                });
            }

            public void listening(boolean anyone) {
                // The microphone only for a viewer that takes the sound (Home Assistant's live view does; a call's
                // picture, read by the add-on, doesn't: the call's voice goes its own way).
                if (sound != null) {
                    if (anyone) sound.start(); else sound.stop();
                }
            }

            public void warm() {
                handler.post(new Runnable() {
                    public void run() {
                        warmUntil = System.currentTimeMillis() + 15000;
                        update();
                    }
                });
            }

            public void keyFrame() {
                VideoEncoder e = encoder;
                if (e != null) e.keyFrame();
            }

            public byte[][] parameterSets() { return encoder != null ? VideoEncoder.parameterSets : null; }

            public boolean sound() { return Tessera.hasMicrophone; }

            public String key() { return ScreenCamera.this.app.keyText(); }
        }, new EspDevice.Logger() {
            public void log(String line) { ScreenCamera.this.app.log(line); }
        });
        sound = Tessera.hasMicrophone ? new VideoSound(app, rtsp) : null;
        try {
            rtsp.start(Rtsp.PORT);
        } catch (java.io.IOException e) {
            app.log("video: couldn't open port " + Rtsp.PORT + " (" + e.getMessage() + ")");
        }
        Pictures.own = new Pictures.Own() {
            public android.graphics.Bitmap frame(int w, int h, int radius, int ground, boolean contain) {
                boolean was = System.currentTimeMillis() < ownUntil;
                ownW = Math.max(1, Math.min(1280, w));
                ownH = Math.max(1, Math.min(1280, h));
                ownRadius = radius;
                ownGround = ground;
                ownContain = contain;
                ownUntil = System.currentTimeMillis() + 1500;
                if (!was) {
                    handler.post(new Runnable() {
                        public void run() { update(); }
                    });
                }
                android.graphics.Bitmap b = ownPicture;
                return b != null && b.getWidth() == ownW && b.getHeight() == ownH ? b : null;
            }
        };
        handler.post(tick);
    }

    /** The video's microphone, which a question can share. */
    VideoSound sound() { return sound; }

    /** The camera on the side Camera side asks for (the front to start), else the first. */
    private void choose() {
        boolean wantFront = settings.cameraSide() == 0;
        android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        int chosen = -1, turn = 0;
        boolean f = false;
        for (int i = 0; i < android.hardware.Camera.getNumberOfCameras(); i++) {
            android.hardware.Camera.getCameraInfo(i, info);
            boolean isFront = info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT;
            if (chosen < 0 || isFront == wantFront && f != wantFront) {
                chosen = i;
                turn = info.orientation;
                f = isFront;
            }
        }
        if (chosen != cameraId || turn != sensorTurn || f != front || !chosenOnce) {
            app.log("camera: the " + (f ? "front" : "back") + " camera (number " + chosen + ", mounted at " + turn + "°)");
        }
        chosenOnce = true;
        cameraId = Math.max(0, chosen);
        sensorTurn = turn;
        front = f;
    }

    private boolean chosenOnce;

    void close() {
        Pictures.own = null;
        entity.source = null;
        settings.onCamera = null;
        rtsp.stop(); // the port is free at once for the screen that starts next
        handler.post(new Runnable() {
            public void run() {
                closed = true;
                if (sound != null) sound.stop();
                closeEncoder();
                stop();
                listenLight(false);
                setDot(0);
                thread.quit();
            }
        });
    }

    /** Once a second: the camera on or off as it is needed, Motion's timeout, the light level, the dot. */
    private final Runnable tick = new Runnable() {
        public void run() {
            if (closed) return;
            update();
            long now = System.currentTimeMillis();
            if (settings.cameraSensing()) {
                sensing.tick(now, settings.cameraTimeout() * 1000L);
                if (now - lastLight >= 10000) publishLight(now);
            }
            handler.postDelayed(this, 1000);
        }
    };

    private void update() {
        if (closed) return;
        long now = System.currentTimeMillis();
        boolean sense = settings.cameraSensing();
        if (sensed && !sense) {
            // Off: Light level reads unknown in Home Assistant, not the last value it had, and Motion goes off.
            EspDevice.Sensor l = screen.lightLevel;
            if (l != null) l.clear();
            EspDevice.BinarySensor m = screen.motion;
            if (m != null) m.set(false);
            shownLux = -1;
        }
        sensed = sense;
        boolean video = videoPlaying || now < warmUntil, own = now < ownUntil;
        boolean wanted = now < wantedUntil || device.pictureWanted() || video || own;
        listenLight(sense);
        if (sense || wanted) {
            if (camera == null && now >= retryAt) start();
        } else if (camera != null) {
            stop();
        }
        if (!video || camera == null) closeEncoder();
        boolean watched = videoPlaying || own
                || lastPicture > 0 && now - lastPicture < (now - lastStream < 5000 ? STREAM_QUIET_MS : PICTURE_MS);
        setDot(camera == null && !watched ? 0 : watched ? 2 : sense ? 1 : 2);
    }

    private void setDot(int d) {
        if (dot == d) return;
        dot = d;
        app.log(d == 0 ? "camera: no longer in use" : d == 1 ? "camera: in use, sensing only (the dot is a ring)"
                : "camera: in use, Home Assistant watches (the dot is solid)");
        Runnable r = onDot;
        if (r != null) r.run();
    }

    private void start() {
        try {
            android.hardware.Camera c = android.hardware.Camera.open(cameraId);
            android.hardware.Camera.Parameters p = c.getParameters();
            android.hardware.Camera.Size best = null;
            // About 1280 x 720: enough for a dashboard, light to encode; the largest preview size at most that big.
            for (android.hardware.Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width * s.height > 1280 * 960) continue;
                if (best == null || s.width * s.height > best.width * best.height) best = s;
            }
            if (best == null) best = p.getPreviewSize();
            p.setPreviewSize(best.width, best.height);
            p.setPreviewFormat(ImageFormat.NV21);
            List<String> focus = p.getSupportedFocusModes();
            if (focus != null && focus.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            try {
                c.setParameters(p);
            } catch (RuntimeException e) {
                p = c.getParameters(); // keep its own
            }
            android.hardware.Camera.Size used = c.getParameters().getPreviewSize();
            width = used.width;
            height = used.height;
            texture = new SurfaceTexture(10);
            c.setPreviewTexture(texture);
            int size = width * height * 3 / 2;
            for (int i = 0; i < 3; i++) c.addCallbackBuffer(new byte[size]);
            c.setPreviewCallbackWithBuffer(this);
            c.setErrorCallback(new android.hardware.Camera.ErrorCallback() {
                public void onError(int error, android.hardware.Camera cam) {
                    app.log("camera: error " + error + "; starting it again");
                    stop();
                    retryAt = System.currentTimeMillis() + 3000;
                }
            });
            c.startPreview();
            camera = c;
            openedAt = System.currentTimeMillis();
            sensing.reset();
            app.log("camera: on, " + width + " x " + height);
        } catch (Exception e) {
            app.log("camera: couldn't start (" + e.getMessage() + "); another app may have it. Trying again in 10 s");
            retryAt = System.currentTimeMillis() + 10000;
            stop();
        }
    }

    private void stop() {
        android.hardware.Camera c = camera;
        camera = null;
        if (c != null) {
            try {
                c.setPreviewCallbackWithBuffer(null);
                c.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                c.release();
            } catch (Exception ignored) {
            }
            app.log("camera: off");
        }
        if (texture != null) {
            texture.release();
            texture = null;
        }
    }

    public void onPreviewFrame(byte[] data, android.hardware.Camera c) {
        if (c != camera || data == null) return;
        long now = System.currentTimeMillis();
        try {
            if (settings.cameraSensing() && now - lastSense >= SENSE_GAP_MS && now - openedAt >= WARM_MS) {
                lastSense = now;
                sensing.frame(data, width, height, width, now, settings.cameraTimeout() * 1000L);
            }
            if ((videoPlaying || now < warmUntil) && now - lastVideoFrame >= 1000 / VideoEncoder.FPS - 4) {
                lastVideoFrame = now;
                videoFrame(data);
            }
            if (now < ownUntil && now - lastOwn >= 200 && now - openedAt >= WARM_MS) {
                lastOwn = now;
                ownPicture = ownFrame(data);
            }
            if (device.pictureWanted() && now - lastPicture >= PICTURE_GAP_MS && now - openedAt >= WARM_MS) {
                byte[] jpeg = jpeg(data);
                if (jpeg != null) {
                    lastPicture = now;
                    entity.picture(jpeg);
                    wantedUntil = Math.max(wantedUntil, now + PICTURE_MS);
                    update();
                }
            }
        } catch (Throwable t) {
            app.log("camera: frame failed: " + t);
        }
        c.addCallbackBuffer(data);
    }

    /**
     * A frame for the video: upright, the middle (Sharp) or all of it (Full) in whole macroblocks of 16 pixels, to the
     * encoder, which is made (again) for the size, turn and field of view it runs at.
     */
    private void videoFrame(byte[] nv21) {
        int turn = turn(), field = settings.cameraField();
        boolean sharp = field == 0;
        int cw = (sharp ? width / 2 : width) & ~15, ch = (sharp ? height / 2 : height) & ~15;
        int cx = ((width - cw) / 2) & ~1, cy = ((height - ch) / 2) & ~1;
        boolean side = turn % 180 != 0;
        int w = side ? ch : cw, h = side ? cw : ch;
        if (encoder != null && (encoder.width != w || encoder.height != h || encoderTurn != turn || encoderField != field)) {
            closeEncoder();
        }
        if (encoder == null) {
            long now = System.currentTimeMillis();
            if (now < encoderRetryAt) return;
            try {
                encoder = new VideoEncoder(app, rtsp, w, h);
                encoderTurn = turn;
                encoderField = field;
            } catch (Throwable t) {
                app.log("video: no encoder (" + t + "); trying again in a minute");
                encoderRetryAt = now + 60000;
                return;
            }
        }
        byte[] frame = nv21;
        if (turn != 0 || cw != width || ch != height) {
            if (videoFrame == null || videoFrame.length != cw * ch * 3 / 2) videoFrame = new byte[cw * ch * 3 / 2];
            frame = turn(nv21, width, height, cx, cy, cw, ch, turn, videoFrame);
        }
        encoder.put(frame, System.nanoTime());
    }

    /**
     * The frame for the tablet's own camera tile: upright, filling the tile (or all of it inside, `contain`), at the
     * tile's size, its corners rounded on the tile's ground; straight from the camera's frame, nothing encoded.
     */
    private android.graphics.Bitmap ownFrame(byte[] nv21) {
        int w = ownW, h = ownH, radius = Math.min(ownRadius, Math.min(w, h) / 2), ground = ownGround | 0xFF000000;
        int t = turn();
        boolean side = t % 180 != 0;
        int sw = side ? height : width, sh = side ? width : height;
        float sc = ownContain ? Math.min(w / (float) sw, h / (float) sh) : Math.max(w / (float) sw, h / (float) sh);
        float dw = sw * sc, dh = sh * sc, ox = (w - dw) / 2, oy = (h - dh) / 2;
        int[] px = new int[w * h];
        int plane = width * height;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = y * w + x;
                int ux = (int) ((x - ox) / sc), uy = (int) ((y - oy) / sc);
                if (ux < 0 || uy < 0 || ux >= sw || uy >= sh || outside(x, y, w, h, radius)) {
                    px[at] = ground;
                    continue;
                }
                int rx, ry;
                if (t == 0) { rx = ux; ry = uy; }
                else if (t == 90) { rx = uy; ry = height - 1 - ux; }
                else if (t == 180) { rx = width - 1 - ux; ry = height - 1 - uy; }
                else { rx = width - 1 - uy; ry = ux; }
                int yy = (nv21[ry * width + rx] & 0xff) - 16;
                int c = plane + (ry >> 1) * width + (rx & ~1);
                int v = (nv21[c] & 0xff) - 128, u = (nv21[c + 1] & 0xff) - 128;
                int y1 = 1192 * Math.max(0, yy);
                int r = (y1 + 1634 * v) >> 10, g = (y1 - 833 * v - 400 * u) >> 10, b = (y1 + 2066 * u) >> 10;
                r = r < 0 ? 0 : r > 255 ? 255 : r;
                g = g < 0 ? 0 : g > 255 ? 255 : g;
                b = b < 0 ? 0 : b > 255 ? 255 : b;
                px[at] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
    }

    /** Whether a pixel lies in a rounded corner's cut-away. */
    private static boolean outside(int x, int y, int w, int h, int r) {
        if (r <= 0) return false;
        int cx = x < r ? r : x >= w - r ? w - r - 1 : -1, cy = y < r ? r : y >= h - r ? h - r - 1 : -1;
        if (cx < 0 || cy < 0) return false;
        int dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy > r * r;
    }

    private void closeEncoder() {
        VideoEncoder e = encoder;
        encoder = null;
        if (e != null) {
            e.close();
            app.log("video: encoder closed");
        }
    }

    /** The frame as Home Assistant should see it: upright, the middle (Sharp) or all (Full), as a JPEG. */
    private byte[] jpeg(byte[] nv21) {
        int turn = turn();
        boolean sharp = settings.cameraField() == 0;
        int cw = sharp ? (width / 2) & ~15 : width, ch = sharp ? (height / 2) & ~15 : height;
        int cx = ((width - cw) / 2) & ~1, cy = ((height - ch) / 2) & ~1;
        ByteArrayOutputStream out = new ByteArrayOutputStream(cw * ch / 4);
        YuvImage img;
        if (turn == 0) {
            img = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            if (!img.compressToJpeg(new Rect(cx, cy, cx + cw, cy + ch), QUALITY, out)) return null;
        } else {
            byte[] turned = turn(nv21, width, height, cx, cy, cw, ch, turn);
            boolean side = turn % 180 != 0;
            int w = side ? ch : cw, h = side ? cw : ch;
            img = new YuvImage(turned, ImageFormat.NV21, w, h, null);
            if (!img.compressToJpeg(new Rect(0, 0, w, h), QUALITY, out)) return null;
        }
        return out.toByteArray();
    }

    /**
     * How far the frame turns clockwise to stand upright: the camera's own mounting and how the tablet hangs now (as
     * Android's Camera.Parameters.setRotation says), and half a turn more with Camera upside down. A front camera's
     * picture is not mirrored: Home Assistant sees the room as a camera on the wall would.
     */
    private int turn() {
        int display = 0;
        try {
            int r = ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            display = r == Surface.ROTATION_90 ? 90 : r == Surface.ROTATION_180 ? 180 : r == Surface.ROTATION_270 ? 270 : 0;
        } catch (Exception ignored) {
        }
        int t = front ? (sensorTurn + display) % 360 : (sensorTurn - display + 360) % 360;
        if (settings.cameraUpsideDown()) t = (t + 180) % 360;
        return t;
    }

    /** A part of an NV21 frame (cx, cy, cw, ch, all even), turned clockwise by `turn`, as a new NV21 frame. */
    static byte[] turn(byte[] src, int w, int h, int cx, int cy, int cw, int ch, int turn) {
        return turn(src, w, h, cx, cy, cw, ch, turn, new byte[cw * ch * 3 / 2]);
    }

    static byte[] turn(byte[] src, int w, int h, int cx, int cy, int cw, int ch, int turn, byte[] dst) {
        boolean side = turn % 180 != 0;
        int ow = side ? ch : cw, oh = side ? cw : ch;
        // Brightness, a byte a pixel.
        for (int y = 0; y < ch; y++) {
            int row = (cy + y) * w + cx;
            for (int x = 0; x < cw; x++) {
                int ox, oy;
                if (turn == 0) { ox = x; oy = y; }
                else if (turn == 90) { ox = ch - 1 - y; oy = x; }
                else if (turn == 180) { ox = cw - 1 - x; oy = ch - 1 - y; }
                else { ox = y; oy = cw - 1 - x; }
                dst[oy * ow + ox] = src[row + x];
            }
        }
        // Colour, a pair (V, U) for every two by two pixels.
        int plane = w * h, oplane = ow * oh, hw = cw / 2, hh = ch / 2, ohw = ow / 2;
        for (int y = 0; y < hh; y++) {
            int row = plane + (cy / 2 + y) * w + cx;
            for (int x = 0; x < hw; x++) {
                int ox, oy;
                if (turn == 0) { ox = x; oy = y; }
                else if (turn == 90) { ox = hh - 1 - y; oy = x; }
                else if (turn == 180) { ox = hw - 1 - x; oy = hh - 1 - y; }
                else { ox = y; oy = hw - 1 - x; }
                int o = oplane + oy * ohw * 2 + ox * 2;
                dst[o] = src[row + x * 2];
                dst[o + 1] = src[row + x * 2 + 1];
            }
        }
        return dst;
    }

    // ----- light level and motion -----

    public void motion(boolean on) {
        EspDevice.BinarySensor m = screen.motion;
        if (m != null) m.set(on);
        app.log("camera: motion " + (on ? "on" : "off"));
    }

    public void moved() {
        if (!settings.cameraWake()) return;
        long now = System.currentTimeMillis();
        if (now - lastWake < 3000) return;
        lastWake = now;
        // With Android's screensaver, motion wakes only a screen that is off or under the screensaver: it never pulls
        // the app in front of another one.
        if (settings.androidSaver()) {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            boolean on = pm == null || pm.isScreenOn();
            if (on && !app.dreaming()) return;
        }
        settings.sensed("motion");
    }

    public void lightChanged() {
        handler.post(new Runnable() {
            public void run() { publishLight(System.currentTimeMillis()); }
        });
    }

    private void publishLight(long now) {
        float lux = lightSensor != null ? sensorLux : Sensing.luxFromBrightness(sensing.mean());
        if (lux < 0) return;
        lastLight = now;
        EspDevice.Sensor l = screen.lightLevel;
        if (l != null) l.set(lux);
        shownLux = lux;
    }

    private final SensorEventListener lightListener = new SensorEventListener() {
        public void onSensorChanged(SensorEvent e) {
            float lux = Math.round(e.values[0] * 10) / 10f;
            sensorLux = lux;
            // At once when a light goes on or off (a third more or less), else every ten seconds.
            float was = shownLux;
            if (was < 0 || Math.abs(lux - was) > Math.max(5, was / 3)) lightChanged();
        }

        public void onAccuracyChanged(Sensor s, int accuracy) {}
    };

    private void listenLight(boolean on) {
        if (lightSensor == null || on == listening) return;
        listening = on;
        if (on) sensors.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
        else {
            sensors.unregisterListener(lightListener);
            sensorLux = -1;
            shownLux = -1;
        }
    }
}
