package io.freemytv;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

public final class AdzanAlert {
    private static final String TAG = "AdzanAlert";
    private static final String CHANNEL_ID = "adzan_notification_channel";
    private static MediaPlayer player;
    private static AudioManager audioManager;
    private static AudioFocusRequest audioFocusRequest;
    private static String activePrayerName = "";
    private static long alertStartTime = 0;
    private static long lastTriggeredMinute = -1;

    public interface Listener {
        void onAdzanStateChanged();
    }
    private static Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    private AdzanAlert() {}

    public static void scheduleNext(Context context) {
        if (!Config.PRAYER_TIMES) return;
        try {
            double lat = Double.parseDouble(Prefs.latitude());
            double lon = Double.parseDouble(Prefs.longitude());
            long now = System.currentTimeMillis();
            long[] next = PrayerTimes.next(lat, lon, now);
            int prayerIndex = (int) next[0];
            long triggerAt = next[1];
            String prayerName = PrayerTimes.name(prayerIndex);

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, AdzanReceiver.class);
            intent.setAction("io.freemytv.ADZAN_ALARM");
            intent.putExtra("prayer_index", prayerIndex);
            intent.putExtra("prayer_name", prayerName);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;

            PendingIntent pi = PendingIntent.getBroadcast(context, 1001, intent, flags);

            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            Log.i(TAG, "Scheduled next Adzan: " + prayerName + " at " + triggerAt);
        } catch (Exception e) {
            Log.e(TAG, "scheduleNext failed", e);
        }
    }

    public static void triggerAdzan(Context context, String prayerName) {
        triggerAdzan(context, prayerName, false);
    }

    public static synchronized void triggerAdzan(Context context, String prayerName, boolean force) {
        long currentMinute = System.currentTimeMillis() / 60_000L;
        if (!force && currentMinute == lastTriggeredMinute) return;
        lastTriggeredMinute = currentMinute;

        // Schedule next prayer regardless of screen state, so future alerts are never lost
        scheduleNext(context);

        // Check if the TV screen is active / interactive
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = powerManager != null && powerManager.isInteractive();
        if (!isScreenOn) {
            Log.i(TAG, "Screen is off / not interactive (TV sleep/standby). Suppressing Adzan alert, audio, and UI to prevent waking TV.");
            return;
        }

        activePrayerName = prayerName != null && !prayerName.isEmpty() ? prayerName : "Sholat";
        alertStartTime = System.currentTimeMillis();
        Log.i(TAG, "triggerAdzan: " + activePrayerName + " (force=" + force + ")");

        String city = Prefs.city().isEmpty() ? Config.CITY : Prefs.city();
        if (city.isEmpty()) city = "Batam";

        // 1. Android TV Notification (no full screen intent, default importance, won't wake screen)
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    NotificationChannel chan = new NotificationChannel(
                        CHANNEL_ID,
                        "Pengingat Adzan",
                        NotificationManager.IMPORTANCE_DEFAULT
                    );
                    chan.setDescription("Pemberitahuan waktu adzan tiba untuk Kota Batam");
                    chan.setBypassDnd(false);
                    chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
                    nm.createNotificationChannel(chan);
                }

                Intent openApp = new Intent(context, HomeActivity.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pi = PendingIntent.getActivity(context, 0, openApp, flags);

                Notification.Builder b = Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(context, CHANNEL_ID)
                        : new Notification.Builder(context);

                b.setSmallIcon(R.drawable.icon)
                 .setContentTitle("Waktu Adzan " + activePrayerName + " Telah Tiba")
                 .setContentText("Wilayah " + city + " dan sekitarnya")
                 .setContentIntent(pi)
                 .setAutoCancel(true)
                 .setCategory(Notification.CATEGORY_EVENT)
                 .setPriority(Notification.PRIORITY_DEFAULT);

                nm.notify(777, b.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Notification failed", e);
        }

        // 2. Start Foreground Service to display overlay over other apps and play audio
        AdzanService.start(context, activePrayerName);

        // 3. Notify UI listener
        if (listener != null) {
            try {
                listener.onAdzanStateChanged();
            } catch (Exception e) {
                Log.e(TAG, "listener error", e);
            }
        }
    }

    public static synchronized boolean isAudioPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized void requestAudioFocus(Context context) {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;

            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes playbackAttrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                                stopAudio();
                            }
                        }
                    })
                    .build();
                int res = audioManager.requestAudioFocus(audioFocusRequest);
                Log.i(TAG, "Audio focus requested (API 26+): " + res);
            } else {
                int res = audioManager.requestAudioFocus(
                    new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                                stopAudio();
                            }
                        }
                    },
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                );
                Log.i(TAG, "Audio focus requested (legacy): " + res);
            }
        } catch (Exception e) {
            Log.e(TAG, "requestAudioFocus failed", e);
        }
    }

    private static synchronized void abandonAudioFocus() {
        try {
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
                Log.i(TAG, "Audio focus abandoned (API 26+)");
            } else {
                audioManager.abandonAudioFocus(null);
                Log.i(TAG, "Audio focus abandoned (legacy)");
            }
        } catch (Exception e) {
            Log.e(TAG, "abandonAudioFocus failed", e);
        }
    }

    private static void releasePlayer() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        abandonAudioFocus();
    }

    public static synchronized void playAudio(Context context) {
        if (!Prefs.adzanSound()) {
            Log.i(TAG, "playAudio skipped: adzanSound is disabled in Prefs");
            return;
        }
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            Log.i(TAG, "playAudio skipped: Screen is not interactive");
            return;
        }
        try {
            releasePlayer();
            requestAudioFocus(context);

            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build());
            AssetFileDescriptor afd = context.getAssets().openFd("adzan.ogg");
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopAudio();
                }
            });
            player.prepare();
            player.start();
            Log.i(TAG, "playAudio started successfully");
        } catch (Exception e) {
            Log.e(TAG, "playAudio failed", e);
            abandonAudioFocus();
        }
    }

    public static synchronized void stopAudio() {
        releasePlayer();
        AdzanOverlay.dismiss();
        AdzanService.stop(null);
        activePrayerName = "";
        alertStartTime = 0;
        if (listener != null) {
            try {
                listener.onAdzanStateChanged();
            } catch (Exception ignored) {}
        }
    }

    public static synchronized boolean isAlertActive() {
        return (player != null && player.isPlaying()) ||
               (alertStartTime > 0 && (System.currentTimeMillis() - alertStartTime < 60_000L));
    }

    public static synchronized String getActivePrayerName() {
        return activePrayerName;
    }
}
