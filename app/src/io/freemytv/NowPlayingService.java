package io.freemytv;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;

public class NowPlayingService extends NotificationListenerService {
    private static final String TAG = "NowPlayingService";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager mediaSessionManager;
    private MediaController currentController;
    private ComponentName listenerComponent;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    Log.i(TAG, "onActiveSessionsChanged, count=" + (controllers != null ? controllers.size() : 0));
                    evaluateControllers(controllers);
                }
            };

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateFromCurrentController();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateFromCurrentController();
        }

        @Override
        public void onSessionDestroyed() {
            detachCurrentController();
            pollActiveSessions();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        listenerComponent = new ComponentName(this, NowPlayingService.class);
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        Log.i(TAG, "NowPlayingService created in process :home");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "NowPlayingService connected to notification/session framework");
        try {
            if (mediaSessionManager != null) {
                mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding sessions changed listener", e);
        }
        pollActiveSessions();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.i(TAG, "NowPlayingService disconnected");
        detachCurrentController();
        try {
            if (mediaSessionManager != null) {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null || sbn.getNotification() == null) return;
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        if (extras != null) {
            Parcelable p = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
            if (p instanceof MediaSession.Token) {
                try {
                    MediaController ctrl = new MediaController(this, (MediaSession.Token) p);
                    Log.i(TAG, "Found MediaController from notification: " + ctrl.getPackageName());
                    attachController(ctrl);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Failed creating controller from token", e);
                }
            }
        }
        pollActiveSessions();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        pollActiveSessions();
    }

    private void pollActiveSessions() {
        if (mediaSessionManager == null || listenerComponent == null) return;
        try {
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(listenerComponent);
            Log.i(TAG, "pollActiveSessions found " + (controllers != null ? controllers.size() : 0) + " controllers");
            evaluateControllers(controllers);
        } catch (Exception e) {
            Log.w(TAG, "Failed to get active sessions: " + e.getMessage());
        }
    }

    private void evaluateControllers(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            detachCurrentController();
            NowPlaying.clear();
            return;
        }

        MediaController best = null;
        for (MediaController c : controllers) {
            if (c == null) continue;
            PlaybackState ps = c.getPlaybackState();
            if (ps != null && (ps.getState() == PlaybackState.STATE_PLAYING || ps.getState() == PlaybackState.STATE_BUFFERING)) {
                best = c;
                break;
            }
        }

        if (best == null) {
            for (MediaController c : controllers) {
                if (c == null) continue;
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PAUSED) {
                    best = c;
                    break;
                }
            }
        }

        if (best != null) {
            attachController(best);
        } else {
            detachCurrentController();
            NowPlaying.clear();
        }
    }

    private void attachController(MediaController c) {
        if (currentController == c) {
            updateFromCurrentController();
            return;
        }
        detachCurrentController();
        currentController = c;
        try {
            currentController.registerCallback(controllerCallback, handler);
        } catch (Exception ignored) {}
        updateFromCurrentController();
    }

    private void detachCurrentController() {
        if (currentController != null) {
            try {
                currentController.unregisterCallback(controllerCallback);
            } catch (Exception ignored) {}
            currentController = null;
        }
    }

    private void updateFromCurrentController() {
        if (currentController == null) {
            NowPlaying.clear();
            return;
        }

        MediaMetadata meta = currentController.getMetadata();
        PlaybackState ps = currentController.getPlaybackState();

        if (meta == null || ps == null) {
            NowPlaying.clear();
            return;
        }

        int state = ps.getState();
        boolean isPlaying = (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING);
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR) {
            NowPlaying.clear();
            return;
        }

        String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.isEmpty()) {
            CharSequence displayTitle = meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (displayTitle != null) title = displayTitle.toString();
        }

        String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null || artist.isEmpty()) {
            CharSequence displaySubtitle = meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (displaySubtitle != null) artist = displaySubtitle.toString();
        }

        Bitmap cover = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null) cover = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);

        if (title != null && !title.isEmpty()) {
            Log.i(TAG, "NowPlaying update: " + title + " by " + artist + " (playing=" + isPlaying + ")");
            NowPlaying.update(currentController, title, artist != null ? artist : "", cover, isPlaying);
        } else {
            NowPlaying.clear();
        }
    }
}
