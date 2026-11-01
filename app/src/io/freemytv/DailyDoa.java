package io.freemytv;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class DailyDoa {
    private static final String TAG = "DailyDoa";
    private static final String GITHUB_RAW_URL = "https://raw.githubusercontent.com/iherbs/quran-json/main/doa_harian.json";
    private static final String CACHE_FILE = "doa_cache.json";
    private static final long STALE_MS = 7 * 24 * 60 * 60_000L; // 7 hari

    static final class Entry {
        final String title;
        final String arabic;
        final String translation;

        Entry(String title, String arabic, String translation) {
            this.title = title;
            this.arabic = arabic;
            this.translation = translation;
        }
    }

    private static final Entry[] FALLBACK_ENTRIES = {
        new Entry(
            "DOA HARIAN · QS. AL-BAQARAH: 45",
            "وَاسْتَعِينُوا بِالصَّبْرِ وَالصَّلَاةِ ۚ وَإِنَّهَا لَكَبِيرَةٌ إِلَّا عَلَى الْخَاشِعِينَ",
            "Dan mohonlah pertolongan (kepada Allah) dengan sabar dan salat. Dan (salat) itu sungguh berat, kecuali bagi orang-orang yang khusyuk."
        ),
        new Entry(
            "DOA HARIAN · QS. AL-BAQARAH: 201",
            "رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً وَفِي الْآخِرَةِ حَسَنَةً وَقِنَا عَذَابَ النَّارِ",
            "Ya Tuhan kami, berilah kami kebaikan di dunia dan kebaikan di akhirat, dan lindungilah kami dari azab neraka."
        ),
        new Entry(
            "DOA HARIAN · QS. ALI 'IMRAN: 8",
            "رَبَّنَا لَا تُزِغْ قُلُوبَنَا بَعْدَ إِذْ هَدَيْتَنَا وَهَبْ لَنَا مِنْ لَدُنْكَ رَحْمَةً ۚ إِنَّكَ أَنْتَ الْوَهَّابُ",
            "Ya Tuhan kami, janganlah Engkau condongkan hati kami kepada kesesatan setelah Engkau beri petunjuk, dan karuniakanlah rahmat dari sisi-Mu."
        ),
        new Entry(
            "DOA HARIAN · QS. AL-ISRA: 24",
            "رَبِّ ارْحَمْهُمَا كَمَا رَبَّيَانِي صَغِيرًا",
            "Wahai Tuhanku, sayangilah kedua orang tuaku sebagaimana mereka berdua telah mendidik aku pada waktu kecil."
        ),
        new Entry(
            "DOA HARIAN · QS. THAHA: 25-28",
            "رَبِّ اشْرَحْ لِي صَدْرِي وَيَسِّرْ لِي أَمْرِي وَاحْلُلْ عُقْدَةً مِنْ لِسَانِي يَفْقَهُوا قَوْلِي",
            "Ya Tuhanku, lapangkanlah dadaku, mudahkanlah urusanku, dan lepaskanlah kekakuan lidahku, agar mereka mengerti perkataanku."
        ),
        new Entry(
            "DOA HARIAN · QS. AL-ANBIYA: 87",
            "لَا إِلَٰهَ إِلَّا أَنْتَ سُبْحَانَكَ إِنِّي كُنْتُ مِنَ الظَّالِمِينَ",
            "Tidak ada tuhan selain Engkau, Mahasuci Engkau. Sungguh, aku termasuk orang-orang yang zalim."
        ),
        new Entry(
            "DOA HARIAN · QS. AL-FURQAN: 74",
            "رَبَّنَا هَبْ لَنَا مِنْ أَزْوَاجِنَا وَذُرِّيَّاتِنَا قُرَّةَ أَعْيُنٍ وَاجْعَلْنَا لِلْمُتَّقِينَ إِمَامًا",
            "Ya Tuhan kami, anugerahkanlah kami pasangan dan keturunan sebagai penyejuk hati, dan jadikanlah kami pemimpin orang bertakwa."
        ),
        new Entry(
            "DOA HARIAN · QS. AL-MU'MINUN: 118",
            "رَبِّ اغْفِرْ وَارْحَمْ وَأَنْتَ خَيْرُ الرَّاحِمِينَ",
            "Ya Tuhanku, berilah ampunan dan berilah rahmat, Engkaulah sebaik-baik pemberi rahmat."
        ),
        new Entry(
            "DOA HARIAN · QS. AL-KAHFI: 10",
            "رَبَّنَا آتِنَا مِنْ لَدُنْكَ رَحْمَةً وَهَيِّئْ لَنَا مِنْ أَمْرِنَا رَشَدًا",
            "Ya Tuhan kami, berikanlah rahmat kepada kami dari sisi-Mu dan sempurnakanlah petunjuk yang lurus bagi kami dalam urusan kami."
        ),
        new Entry(
            "DOA HARIAN · QS. IBRAHIM: 40",
            "رَبِّ اجْعَلْنِي مُقِيمَ الصَّلَاةِ وَمِنْ ذُرِّيَّتِي ۚ رَبَّنَا وَتَقَبَّلْ دُعَاءِ",
            "Ya Tuhanku, jadikanlah aku dan anak cucuku orang yang tetap melaksanakan salat, ya Tuhan kami, perkenankanlah doaku."
        ),
        new Entry(
            "DOA PAGI · HR. IBNU MAJAH",
            "اللَّهُمَّ إِنِّي أَسْأَلُكَ عِلْمًا نَافِعًا وَرِزْقًا طَيِّبًا وَعَمَلًا مُتَقَبَّلًا",
            "Ya Allah, sesungguhnya aku memohon kepada-Mu ilmu yang bermanfaat, rezeki yang baik, dan amal yang diterima."
        ),
        new Entry(
            "DOA HARIAN · HR. BUKHARI",
            "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْهَمِّ وَالْحَزَنِ وَالْعَجْزِ وَالْكَسَلِ وَالْجُبْنِ وَالْبُخْلِ",
            "Ya Allah, aku berlindung kepada-Mu dari rasa gundah dan sedih, dari kelemahan dan kemalasan, serta sifat pengecut dan kikir."
        ),
        new Entry(
            "DOA HARIAN · HR. MUSLIM",
            "اللَّهُمَّ إِنِّي أَسْأَلُكَ الْهُدَى وَالتُّقَى وَالْعَفَافَ وَالْغِنَى",
            "Ya Allah, sesungguhnya aku memohon kepada-Mu petunjuk, ketakwaan, kesucian diri, dan kecukupan."
        ),
        new Entry(
            "DOA HARIAN · HR. IBNU HIBBAN",
            "اللَّهُمَّ لَا سَهْلَ إِلَّا مَا جَعَلْتَهُ سَهْلًا وَأَنْتَ تَجْعَلُ الْحَزْنَ إِذَا شِئْتَ سَهْلًا",
            "Ya Allah, tidak ada kemudahan kecuali apa yang Engkau jadikan mudah, dan Engkau jadikan kesulitan mudah bila Engkau kehendaki."
        ),
        new Entry(
            "DOA HARIAN · QS. AN-NAML: 19",
            "رَبِّ أَوْزِعْنِي أَنْ أَشْكُرَ نِعْمَتَكَ الَّتِي أَنْعَمْتَ عَلَيَّ وَعَلَىٰ وَالِدَيَّ",
            "Ya Tuhanku, anugerahkanlah aku ilham untuk tetap mensyukuri nikmat-Mu yang telah Engkau limpahkan kepadaku dan kedua orang tuaku."
        )
    };

    private static List<Entry> cachedList = null;
    private static boolean refreshing = false;
    private static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static synchronized List<Entry> getEntries(Context context) {
        if (cachedList != null && !cachedList.isEmpty()) {
            return cachedList;
        }

        List<Entry> diskList = loadFromDisk(context);
        if (diskList != null && !diskList.isEmpty()) {
            cachedList = diskList;
            return cachedList;
        }

        List<Entry> fallback = new ArrayList<>();
        Collections.addAll(fallback, FALLBACK_ENTRIES);
        cachedList = fallback;
        return cachedList;
    }

    /** Picks today's entry based on day of year (local TV time). */
    static Entry today(Context context) {
        List<Entry> list = getEntries(context);
        Calendar cal = Calendar.getInstance();
        int day = cal.get(Calendar.DAY_OF_YEAR);
        return list.get(Math.abs(day) % list.size());
    }

    static void refreshIfStale(Context context) {
        if (refreshing || context == null) return;
        File f = new File(context.getFilesDir(), CACHE_FILE);
        long age = System.currentTimeMillis() - f.lastModified();
        if (f.exists() && age < STALE_MS) return;

        refreshing = true;
        EXECUTOR.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_RAW_URL).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "ArsyanTV-Launcher");

                if (conn.getResponseCode() == 200) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream in = conn.getInputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                    }
                    String json = baos.toString(StandardCharsets.UTF_8.name());
                    List<Entry> parsed = parseJson(json);
                    if (!parsed.isEmpty()) {
                        File tmp = new File(context.getFilesDir(), CACHE_FILE + ".tmp");
                        try (FileOutputStream fos = new FileOutputStream(tmp)) {
                            fos.write(json.getBytes(StandardCharsets.UTF_8));
                        }
                        tmp.renameTo(f);
                        synchronized (DailyDoa.class) {
                            cachedList = parsed;
                        }
                        Log.i(TAG, "Doa harian loaded from GitHub Raw: " + parsed.size() + " items");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to refresh doa harian from GitHub: " + e.getMessage());
            } finally {
                refreshing = false;
            }
        });
    }

    private static List<Entry> loadFromDisk(Context context) {
        if (context == null) return null;
        File f = new File(context.getFilesDir(), CACHE_FILE);
        if (!f.exists()) return null;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            fis.read(b);
            return parseJson(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Entry> parseJson(String json) {
        List<Entry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "Doa Harian").trim();
                String arabic = obj.optString("text_ayah", "").trim();
                String translation = obj.optString("text_id", "").trim();
                if (!arabic.isEmpty() && !translation.isEmpty()) {
                    String title = "DOA HARIAN · " + name.toUpperCase(Locale.ROOT);
                    list.add(new Entry(title, arabic, translation));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parse error", e);
        }
        return list;
    }
}
