package app.tilescreen;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Lift to wake: while the screen is in standby, the accelerometer watches for the tablet being picked up or moved. Its
 * resting gravity is learnt in the first half second; a tilt of more than TILT degrees from it, or a jolt (the force
 * off by more than JOLT m/s²), on two readings in a row wakes the screen. A slow drift (the table, the wall) is
 * followed, so only a real movement counts. Listens only while dimmed, at Android's normal rate (5 a second).
 */
final class Lift implements SensorEventListener {
    interface Moved { void moved(String how); }

    private static final double TILT = 14, JOLT = 2.5;
    private static final int LEARN = 3;

    private final SensorManager sensors;
    private final Sensor accel;
    private final Moved moved;
    private boolean listening;
    private int seen, over;
    private final double[] rest = new double[3], now = new double[3];

    Lift(Context context, Moved moved) {
        this.moved = moved;
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accel = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    boolean available() { return accel != null; }

    /** Listens while `on`; a new start learns the resting position afresh. */
    void listen(boolean on) {
        if (accel == null || on == listening) return;
        listening = on;
        if (on) {
            seen = over = 0;
            sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            sensors.unregisterListener(this);
        }
    }

    public void onSensorChanged(SensorEvent e) {
        if (!listening || e.values.length < 3) return;
        double x = e.values[0], y = e.values[1], z = e.values[2];
        if (seen < LEARN) {
            // The resting position: the mean of the first readings.
            rest[0] = (rest[0] * seen + x) / (seen + 1);
            rest[1] = (rest[1] * seen + y) / (seen + 1);
            rest[2] = (rest[2] * seen + z) / (seen + 1);
            now[0] = rest[0]; now[1] = rest[1]; now[2] = rest[2];
            seen++;
            return;
        }
        now[0] = now[0] * 0.5 + x * 0.5;
        now[1] = now[1] * 0.5 + y * 0.5;
        now[2] = now[2] * 0.5 + z * 0.5;
        double tilt = angle(now, rest), jolt = Math.abs(Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH);
        if (tilt > TILT || jolt > JOLT) {
            if (++over >= 2) {
                listen(false);
                moved.moved(tilt > TILT ? "tilted " + Math.round(tilt) + "°" : "a jolt of " + Math.round(jolt * 10) / 10.0 + " m/s²");
            }
            return;
        }
        over = 0;
        // A slow drift is followed.
        for (int i = 0; i < 3; i++) rest[i] = rest[i] * 0.98 + now[i] * 0.02;
    }

    private static double angle(double[] a, double[] b) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        double la = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), lb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        if (la < 1e-3 || lb < 1e-3) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot / (la * lb)))));
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
