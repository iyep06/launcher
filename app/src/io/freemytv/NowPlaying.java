package io.freemytv;

import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;

public final class NowPlaying {
    public interface Listener {
        void onNowPlayingChanged();
    }

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Listener listener;

    private static boolean active = false;
    private static boolean playing = false;
    private static String title = "";
    private static String artist = "";
    private static Bitmap cover = null;
    private static MediaController controller = null;

    private NowPlaying() {}

    public static synchronized void setListener(Listener l) {
        listener = l;
    }

    public static synchronized boolean hasActiveMedia() {
        return active && (title != null && !title.isEmpty());
    }

    public static synchronized boolean isPlaying() {
        return playing;
    }

    public static synchronized String getTitle() {
        return title != null ? title : "";
    }

    public static synchronized String getArtist() {
        return artist != null ? artist : "";
    }

    public static synchronized Bitmap getCover() {
        return cover;
    }

    public static synchronized void update(MediaController ctrl, String t, String a, Bitmap c, boolean isPlay) {
        controller = ctrl;
        title = t;
        artist = a;
        cover = c;
        playing = isPlay;
        active = (title != null && !title.isEmpty());

        handler.post(() -> {
            if (listener != null) listener.onNowPlayingChanged();
        });
    }

    public static synchronized void clear() {
        active = false;
        playing = false;
        title = "";
        artist = "";
        cover = null;
        controller = null;

        handler.post(() -> {
            if (listener != null) listener.onNowPlayingChanged();
        });
    }

    public static synchronized void togglePlayPause() {
        if (controller == null) return;
        try {
            MediaController.TransportControls tc = controller.getTransportControls();
            if (tc != null) {
                if (playing) {
                    tc.pause();
                } else {
                    tc.play();
                }
            }
        } catch (Exception ignored) {}
    }
}

