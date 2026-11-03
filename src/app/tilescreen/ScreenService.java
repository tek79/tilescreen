package app.tilescreen;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the screen's server running while the activity is in the background. */
public final class ScreenService extends Service {
    private PowerManager.WakeLock cpu;

    @Override
    public void onCreate() {
        super.onCreate();
        App.get().screen();
        // The processor stays awake while Android has the glass off (Use Android screensaver), so Home Assistant can
        // still reach the screen and Wake it. The app always kept the screen on before, which did the same.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tilescreen:server");
        cpu.setReferenceCounted(false);
        cpu.acquire();
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, 0);
        Notification n = new Notification.Builder(this)
                .setContentTitle("Tile Screen")
                .setContentText("Serving " + App.get().friendly() + " to Home Assistant")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(tap)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        // The next alarm, for Home Assistant: now, and again whenever an alarm app changes it.
        alarms = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) { reportAlarm(); }
        };
        IntentFilter f = new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(alarms, f);
        reportAlarm();
        // On the charger or on battery (Screen off in standby on battery, Android brightness in standby).
        power = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) { reportPower(intent); }
        };
        IntentFilter pf = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        pf.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(power, pf);
        reportPower(registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
    }

    private BroadcastReceiver alarms, power;

    private void reportPower(Intent intent) {
        boolean on;
        if (intent != null && Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) on = true;
        else if (intent != null && Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) on = false;
        else on = intent == null || intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 1) != 0;
        App.get().screen().screenSettings.charging(on);
    }

    private void reportAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            AlarmManager.AlarmClockInfo next = am.getNextAlarmClock();
            String app = null;
            if (next != null && next.getShowIntent() != null) app = next.getShowIntent().getCreatorPackage();
            App.get().screen().nextAlarm(next == null ? 0 : next.getTriggerTime(), app);
            App.get().log("next alarm: " + (next == null ? "none" : new java.util.Date(next.getTriggerTime()) + (app == null ? "" : " (" + app + ")")));
        } catch (Exception e) {
            App.get().log("next alarm: couldn't read it: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        if (alarms != null) unregisterReceiver(alarms);
        if (power != null) unregisterReceiver(power);
        if (cpu != null) cpu.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
