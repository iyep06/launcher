package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class OtaUpdate {
    private static final String TAG = "OtaUpdate";
    private static final String GITHUB_REPO = "ssemyy-sds/arsyan-tv-launcher";
    private static final String RELEASES_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";

    public enum State {
        IDLE, CHECKING, AVAILABLE, UP_TO_DATE, DOWNLOADING, READY, ERROR
    }

    public static class Info {
        public State state = State.IDLE;
        public String latestVersion = "";
        public String currentVersion = "";
        public String downloadUrl = "";
        public String changelog = "";
        public int progress = 0;
        public String message = "";
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final Info CURRENT_INFO = new Info();
    public static String currentChangelog = "";
    private static boolean checking = false;
    private static boolean downloading = false;

    public interface Listener {
        void onUpdateState(Info info);
    }

    public static Info getInfo() {
        return CURRENT_INFO;
    }

    public static void check(Context context, Listener listener) {
        if (checking || downloading) {
            if (listener != null) listener.onUpdateState(CURRENT_INFO);
            return;
        }
        checking = true;
        CURRENT_INFO.state = State.CHECKING;
        CURRENT_INFO.message = "Memeriksa pembaruan…";
        CURRENT_INFO.currentVersion = getAppVersion(context);
        if (listener != null) listener.onUpdateState(CURRENT_INFO);

        EXECUTOR.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "ArsyanTV-Launcher");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                int code = conn.getResponseCode();
                if (code == 200) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream in = conn.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                    }
                    String jsonStr = baos.toString(StandardCharsets.UTF_8.name());
                    JSONObject release = new JSONObject(jsonStr);
                    String tag = release.optString("tag_name", "");
                    String body = release.optString("body", "");
                    String apkUrl = "";

                    JSONArray assets = release.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a != null) {
                                String name = a.optString("name", "");
                                if (name.endsWith(".apk")) {
                                    apkUrl = a.optString("browser_download_url", "");
                                    break;
                                }
                            }
                        }
                    }

                    CURRENT_INFO.latestVersion = tag;
                    CURRENT_INFO.changelog = body;
                    CURRENT_INFO.downloadUrl = apkUrl;

                    if (isNewer(tag, CURRENT_INFO.currentVersion) && !apkUrl.isEmpty()) {
                        CURRENT_INFO.state = State.AVAILABLE;
                        CURRENT_INFO.message = "Versi " + tag + " tersedia";
                    } else {
                        CURRENT_INFO.state = State.UP_TO_DATE;
                        CURRENT_INFO.message = "ArsyanTV sudah versi terbaru (" + CURRENT_INFO.currentVersion + ")";
                    }
                } else if (code == 404) {
                    CURRENT_INFO.state = State.UP_TO_DATE;
                    CURRENT_INFO.message = "Belum ada rilis baru di GitHub";
                } else {
                    CURRENT_INFO.state = State.ERROR;
                    CURRENT_INFO.message = "Gagal memeriksa (HTTP " + code + ")";
                }
            } catch (Exception e) {
                CURRENT_INFO.state = State.ERROR;
                CURRENT_INFO.message = "Koneksi terputus: " + e.getMessage();
                Log.w(TAG, "check failed", e);
            } finally {
                checking = false;
                MAIN_HANDLER.post(() -> {
                    if (listener != null) listener.onUpdateState(CURRENT_INFO);
                });
            }
        });
    }

    public static void startDownloadAndInstall(Context context, Listener listener) {
        if (downloading || CURRENT_INFO.downloadUrl.isEmpty()) return;
        downloading = true;
        CURRENT_INFO.state = State.DOWNLOADING;
        CURRENT_INFO.progress = 0;
        CURRENT_INFO.message = "Mengunduh pembaruan (0%)…";
        if (listener != null) listener.onUpdateState(CURRENT_INFO);

        EXECUTOR.execute(() -> {
            File outFile = new File(context.getCacheDir(), "ArsyanTV-update.apk");
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(CURRENT_INFO.downloadUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "ArsyanTV-Launcher");

                int code = conn.getResponseCode();
                // Follow redirects manually if needed (GitHub asset URLs redirect to AWS S3)
                if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == 307 || code == 308) {
                    String redirected = conn.getHeaderField("Location");
                    if (redirected != null) {
                        conn.disconnect();
                        conn = (HttpURLConnection) new URL(redirected).openConnection();
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(15000);
                    }
                }

                long totalBytes = conn.getContentLength();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    long readBytes = 0;
                    long lastPost = 0;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                        readBytes += len;
                        if (totalBytes > 0) {
                            int pct = (int) (readBytes * 100 / totalBytes);
                            long now = System.currentTimeMillis();
                            if (pct != CURRENT_INFO.progress && now - lastPost > 200) {
                                lastPost = now;
                                CURRENT_INFO.progress = pct;
                                CURRENT_INFO.message = "Mengunduh pembaruan (" + pct + "%)…";
                                MAIN_HANDLER.post(() -> {
                                    if (listener != null) listener.onUpdateState(CURRENT_INFO);
                                });
                            }
                        }
                    }
                    out.flush();
                }

                CURRENT_INFO.state = State.READY;
                CURRENT_INFO.progress = 100;
                CURRENT_INFO.message = "Siap memasang pembaruan";
                MAIN_HANDLER.post(() -> {
                    if (listener != null) listener.onUpdateState(CURRENT_INFO);
                    installApk(context, outFile);
                });
            } catch (Exception e) {
                CURRENT_INFO.state = State.ERROR;
                CURRENT_INFO.message = "Gagal mengunduh: " + e.getMessage();
                Log.w(TAG, "download failed", e);
                MAIN_HANDLER.post(() -> {
                    if (listener != null) listener.onUpdateState(CURRENT_INFO);
                });
            } finally {
                downloading = false;
            }
        });
    }

    public static void installApk(Context context, File file) {
        if (!file.exists()) return;

        // Coba install senyap jika ADB loopback lokal terbuka
        try (Adb adb = Adb.connect(context, 1000, null)) {
            String res = adb.shell("pm install -r " + file.getAbsolutePath());
            if (res.contains("Success")) {
                adb.shell("am start -n io.freemytv/.HomeActivity");
                return;
            }
        } catch (Exception ignored) {
            // Abaikan error adb dan lanjutkan ke installer standar Android
        }

        // Buka installer sistem standar Android
        try {
            Uri apkUri = Uri.parse("content://" + UpdateProvider.AUTHORITY + "/update.apk");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch package installer", e);
        }
    }

    public static String getCurrentChangelog(Context context) {
        if (currentChangelog != null && !currentChangelog.isEmpty()) {
            return cleanChangelog(currentChangelog);
        }
        if (CURRENT_INFO.changelog != null && !CURRENT_INFO.changelog.isEmpty()) {
            return cleanChangelog(CURRENT_INFO.changelog);
        }
        return "• Penambahan pilihan tema sepakbola (Soccer Theme) pada wallpaper dan tampilan launcher.";
    }

    public static String cleanChangelog(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw;
        int fcIdx = s.indexOf("**Full Changelog**");
        if (fcIdx >= 0) s = s.substring(0, fcIdx);
        s = s.replaceAll("###\\s*[^\n]*\n", "");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        s = s.replaceAll("(?m)^\\s*\\*\\s*", "• ");
        s = s.replaceAll("(?m)^\\s*-\\s*", "  - ");
        return s.trim();
    }

    private static String getAppVersion(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName != null ? p.versionName : "2.7";
        } catch (Exception e) {
            return "2.7";
        }
    }

    static boolean isNewer(String remote, String current) {
        if (remote == null || remote.isEmpty()) return false;
        String r = remote.trim().toLowerCase(java.util.Locale.ROOT);
        if (r.startsWith("v")) r = r.substring(1);
        String c = current.trim().toLowerCase(java.util.Locale.ROOT);
        if (c.startsWith("v")) c = c.substring(1);

        String[] rParts = r.split("\\.");
        String[] cParts = c.split("\\.");
        int max = Math.max(rParts.length, cParts.length);
        for (int i = 0; i < max; i++) {
            int rv = i < rParts.length ? parse(rParts[i]) : 0;
            int cv = i < cParts.length ? parse(cParts[i]) : 0;
            if (rv > cv) return true;
            if (rv < cv) return false;
        }
        return false;
    }

    private static int parse(String s) {
        try {
            // Hanya ambil digit angka pertama
            String num = s.replaceAll("[^0-9]", "");
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (Exception e) {
            return 0;
        }
    }
}

