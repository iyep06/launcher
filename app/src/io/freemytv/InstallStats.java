package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class InstallStats {
    private static final String TAG = "InstallStats";
    private static final String PREF_NAME = "launcher_stats";
    private static final String KEY_FIRST_RUN = "first_install_sent";
    private static boolean running = false;

    private InstallStats() {}

    public static void recordFirstInstall(Context context) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        SharedPreferences prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_FIRST_RUN, false)) return;

        synchronized (InstallStats.class) {
            if (running) return;
            running = true;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String endpoint = Config.FIREBASE_STATS_URL;
                if (endpoint == null || endpoint.trim().isEmpty()) {
                    return;
                }

                if (incrementCounter(endpoint.trim())) {
                    prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply();
                    Log.i(TAG, "First install recorded successfully");
                }
            } catch (Exception e) {
                Log.w(TAG, "Install counter update skipped: " + e.getMessage());
            } finally {
                synchronized (InstallStats.class) {
                    running = false;
                }
            }
        });
    }

    private static boolean incrementCounter(String endpoint) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            long currentCount = 0;
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                String res = sb.toString().trim();
                if (!res.equals("null") && !res.isEmpty()) {
                    JSONObject json = new JSONObject(res);
                    currentCount = json.optLong("total_installs", 0);
                }
            }
            conn.disconnect();

            long nextCount = currentCount + 1;
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PATCH");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            String body = "{\"total_installs\":" + nextCount + "}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int patchCode = conn.getResponseCode();
            return patchCode == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            Log.e(TAG, "Failed to increment counter: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

