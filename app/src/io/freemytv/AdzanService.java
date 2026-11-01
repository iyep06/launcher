package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

public class AdzanService extends Service {
    private static final String TAG = "AdzanService";
    public static final String ACTION_START = "io.freemytv.action.START_ADZAN";
    public static final String ACTION_STOP = "io.freemytv.action.STOP_ADZAN";
    private static final String FG_CHANNEL_ID = "adzan_service_channel";
    private static final int FG_NOTIF_ID = 778;

    private static volatile AdzanService activeInstance;

    public static void start(Context context, String prayerName) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            Log.i(TAG, "AdzanService start skipped: screen is not interactive");
            return;
        }
        Intent intent = new Intent(context, AdzanService.class);
        intent.setAction(ACTION_START);
        intent.putExtra("prayer_name", prayerName);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "AdzanService start requested for " + prayerName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AdzanService", e);
        }
    }

    public static void stop(Context context) {
        AdzanService s = activeInstance;
        if (s != null) {
            s.stopAdzan();
        } else if (context != null) {
            try {
                Intent intent = new Intent(context, AdzanService.class);
                intent.setAction(ACTION_STOP);
                context.startService(intent);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        activeInstance = this;
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopAdzan();
            return START_NOT_STICKY;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = pm != null && pm.isInteractive();
        if (!isScreenOn) {
            Log.i(TAG, "AdzanService: Screen is not interactive. Stopping service immediately.");
            stopAdzan();
            return START_NOT_STICKY;
        }

        String prayerName = intent != null ? intent.getStringExtra("prayer_name") : null;
        if (prayerName == null || prayerName.isEmpty()) prayerName = "Sholat";
        String city = Prefs.city().isEmpty() ? Config.CITY : Prefs.city();
        if (city.isEmpty()) city = "Batam";

        // 1. Immediately promote to foreground service
        startForegroundWithNotification(prayerName, city);

        // 2. Display overlay over other apps (e.g. YouTube)
        AdzanOverlay.show(this, prayerName, city);

        // 3. Play audio with audio focus
        AdzanAlert.playAudio(this);

        return START_NOT_STICKY;
    }

    private void startForegroundWithNotification(String prayerName, String city) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel chan = new NotificationChannel(
                FG_CHANNEL_ID,
                "Adzan Service",
                NotificationManager.IMPORTANCE_LOW
            );
            chan.setDescription("Layanan pemberitahuan dan audio adzan");
            nm.createNotificationChannel(chan);
        }

        Intent openApp = new Intent(this, HomeActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, FG_CHANNEL_ID)
            : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.icon)
         .setContentTitle("Waktu Adzan " + prayerName)
         .setContentText("Wilayah " + city + " dan sekitarnya")
         .setContentIntent(pi)
         .setOngoing(true);

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(FG_NOTIF_ID, b.build());
            }
            Log.i(TAG, "AdzanService promoted to foreground successfully");
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    public void stopAdzan() {
        Log.i(TAG, "Stopping AdzanService");
        AdzanOverlay.dismiss();
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {}
        stopSelf();
        if (activeInstance == this) activeInstance = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (activeInstance == this) activeInstance = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
