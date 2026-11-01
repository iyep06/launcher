package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

public class AdzanReceiver extends BroadcastReceiver {
    private static final String TAG = "AdzanReceiver";

    @Override public void onReceive(Context context, Intent intent) {
        String prayerName = intent != null ? intent.getStringExtra("prayer_name") : null;
        Log.i(TAG, "AdzanReceiver onReceive: " + prayerName);

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "io.freemytv:AdzanReceiver");
            wakeLock.acquire(10_000L);
        }
        try {
            AdzanAlert.triggerAdzan(context.getApplicationContext(), prayerName, true);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception ignored) {}
            }
        }
    }
}
