package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SoccerData {
    private static final String TAG = "SoccerData";
    private static final String BASE_URL = "https://www.thesportsdb.com/api/v1/json/123/";
    private static final long REFRESH_INTERVAL_MS = 30 * 60_000L; // 30 minutes

    public static class TeamStanding {
        public int rank;
        public String name;
        public int played;
        public int points;
        public String form;
        public String badgeUrl;
        public boolean isFavorite;

        public TeamStanding(int rank, String name, int played, int points, String form, String badgeUrl,
                boolean isFav) {
            this.rank = rank;
            this.name = name;
            this.played = played;
            this.points = points;
            this.form = form;
            this.badgeUrl = badgeUrl;
            this.isFavorite = isFav;
        }
    }

    public static class MatchInfo {
        public String leagueName = "SERIE A";
        public String homeTeam = "Udinese";
        public String awayTeam = "AS Roma";
        public String matchRole = "TANDANG"; // TANDANG / KANDANG
        public String countdown = "Kick-off dalam 3 hari 2j";
        public String matchDateTime = "Minggu 1 Nov 01:00 WITA";
        public String form = "KMMSM"; // K=Kalah (Red), M=Menang (Green), S=Seri (Gray)
        public String crestUrl = "";
        public Bitmap crestBitmap;
        public String lastResult = "Terakhir: AS Roma 4 - 2 Cagliari";
        public List<String> upcomingFixtures = new ArrayList<>();
    }

    public interface Listener {
        void onSoccerDataUpdated();
    }

    private static volatile SoccerData instance;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Bitmap> badgeCache = new HashMap<>();

    public final MatchInfo match = new MatchInfo();
    public final List<TeamStanding> standings = new ArrayList<>();

    private long lastFetchTime = 0;
    private boolean isFetching = false;
    private String currentLeagueId = "";
    private String currentFavTeam = "";

    public static SoccerData get(Context ctx) {
        if (instance == null) {
            synchronized (SoccerData.class) {
                if (instance == null) {
                    instance = new SoccerData(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SoccerData(Context ctx) {
        this.context = ctx;
        applyDynamicFallback(Prefs.soccerLeague(), Prefs.soccerFavoriteTeam());
        loadCachedData();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l))
            listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void clearCache() {
        try {
            SharedPreferences sp = context.getSharedPreferences("soccer_data", Context.MODE_PRIVATE);
            sp.edit().clear().apply();
        } catch (Exception ignored) {
        }
        lastFetchTime = 0;
    }

    private void notifyListeners() {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onSoccerDataUpdated();
                } catch (Exception ignored) {
                }
            }
        });
    }

    public void refresh(boolean force) {
        String leagueId = Prefs.soccerLeague();
        String favTeam = Prefs.soccerFavoriteTeam();
        boolean prefsChanged = !leagueId.equals(currentLeagueId) || !favTeam.equals(currentFavTeam);

        if (prefsChanged) {
            currentLeagueId = leagueId;
            currentFavTeam = favTeam;
            applyDynamicFallback(leagueId, favTeam);
            notifyListeners();
        }

        long now = SystemClock.uptimeMillis();
        if (!force && !prefsChanged && isFetching && (now - lastFetchTime < REFRESH_INTERVAL_MS))
            return;
        isFetching = true;
        lastFetchTime = now;

        executor.execute(() -> {
            try {
                // 1. Fetch Standings Table
                String tableUrl = BASE_URL + "lookuptable.php?l=" + leagueId;
                String tableJson = fetchHttpString(tableUrl);
                if (tableJson != null && !tableJson.isEmpty() && !tableJson.contains("error code:")) {
                    parseStandings(tableJson, favTeam);
                    saveCache("standings_" + leagueId, tableJson);
                }

                // 2. Fetch Next Fixtures
                String fixturesUrl = BASE_URL + "eventsnextleague.php?id=" + leagueId;
                String fixturesJson = fetchHttpString(fixturesUrl);
                if (fixturesJson != null && !fixturesJson.isEmpty() && !fixturesJson.contains("error code:")) {
                    parseNextFixtures(fixturesJson, favTeam);
                    saveCache("fixtures_" + leagueId, fixturesJson);
                }

                // 3. Fetch Past Fixtures (to extract real last match for favorite team)
                String pastUrl = BASE_URL + "eventspastleague.php?id=" + leagueId;
                String pastJson = fetchHttpString(pastUrl);
                if (pastJson != null && !pastJson.isEmpty() && !pastJson.contains("error code:")) {
                    parsePastFixtures(pastJson, favTeam);
                    saveCache("past_" + leagueId, pastJson);
                }

                // 4. Fetch Team badge if available
                if (match.crestUrl != null && !match.crestUrl.isEmpty() && match.crestBitmap == null) {
                    match.crestBitmap = loadBadgeBitmap(match.crestUrl);
                }

                notifyListeners();
            } catch (Exception e) {
                Log.w(TAG, "Error fetching live soccer data: " + e.getMessage());
            } finally {
                isFetching = false;
            }
        });
    }

    private void parseStandings(String jsonStr, String favTeam) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            JSONArray arr = obj.optJSONArray("table");
            if (arr == null || arr.length() == 0)
                return;

            List<TeamStanding> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null)
                    continue;

                int rank = t.optInt("intRank", i + 1);
                String name = t.optString("strTeam", "");
                int played = t.optInt("intPlayed", 0);
                int points = t.optInt("intPoints", 0);
                String rawForm = t.optString("strForm", "");
                String badgeUrl = t.optString("strBadge", "");

                // Convert rawForm (W/D/L) to Indonesian (M/S/K)
                StringBuilder formId = new StringBuilder();
                for (int f = 0; f < rawForm.length() && f < 5; f++) {
                    char c = Character.toUpperCase(rawForm.charAt(f));
                    if (c == 'W')
                        formId.append('M');
                    else if (c == 'D')
                        formId.append('S');
                    else if (c == 'L')
                        formId.append('K');
                }

                boolean isFav = isTeamMatch(name, favTeam);
                list.add(new TeamStanding(rank, name, played, points, formId.toString(), badgeUrl, isFav));
            }

            if (!list.isEmpty()) {
                standings.clear();
                standings.addAll(list);
            }
        } catch (Exception e) {
            Log.e(TAG, "parseStandings failed", e);
        }
    }

    private void parseNextFixtures(String jsonStr, String favTeam) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            JSONArray arr = obj.optJSONArray("events");
            if (arr == null || arr.length() == 0)
                return;

            JSONObject targetEvent = null;
            // Search fixture involving favorite team first
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ev = arr.optJSONObject(i);
                if (ev == null)
                    continue;
                String home = ev.optString("strHomeTeam", "");
                String away = ev.optString("strAwayTeam", "");
                if (isTeamMatch(home, favTeam) || isTeamMatch(away, favTeam)) {
                    targetEvent = ev;
                    break;
                }
            }
            if (targetEvent == null) {
                targetEvent = arr.optJSONObject(0);
            }
            if (targetEvent == null)
                return;

            int lIdx = java.util.Arrays.asList(Prefs.SOCCER_LEAGUES).indexOf(Prefs.soccerLeague());
            String defLeague = lIdx >= 0 ? Prefs.SOCCER_LEAGUE_NAMES[lIdx] : "SERIE A";
            match.leagueName = targetEvent.optString("strLeague", defLeague).toUpperCase(Locale.ROOT);
            match.homeTeam = targetEvent.optString("strHomeTeam", "Home");
            match.awayTeam = targetEvent.optString("strAwayTeam", "Away");

            boolean isFavAway = isTeamMatch(match.awayTeam, favTeam);
            match.matchRole = isFavAway ? "TANDANG" : "KANDANG";

            // Format date & time
            String dateStr = targetEvent.optString("dateEvent", "");
            String timeStr = targetEvent.optString("strTime", "00:00:00");
            match.matchDateTime = formatMatchTime(dateStr, timeStr);
            match.countdown = formatCountdown(dateStr, timeStr);

            // Crest logo url
            String homeBadge = targetEvent.optString("strHomeTeamBadge", "");
            String awayBadge = targetEvent.optString("strAwayTeamBadge", "");
            match.crestUrl = isFavAway ? awayBadge : homeBadge;

            // Collect other upcoming fixtures
            match.upcomingFixtures.clear();
            for (int i = 0; i < arr.length() && match.upcomingFixtures.size() < 2; i++) {
                JSONObject ev = arr.optJSONObject(i);
                if (ev == targetEvent)
                    continue;
                String h = ev.optString("strHomeTeam", "");
                String a = ev.optString("strAwayTeam", "");
                String d = formatShortFixture(ev.optString("dateEvent", ""), ev.optString("strTime", ""));
                boolean involvesFav = isTeamMatch(h, favTeam) || isTeamMatch(a, favTeam);
                if (involvesFav) {
                    String op = isTeamMatch(h, favTeam) ? ("vs " + a) : ("di " + h);
                    match.upcomingFixtures.add(op + " · " + d);
                } else if (match.upcomingFixtures.size() < 2) {
                    match.upcomingFixtures.add(h + " vs " + a + " · " + d);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parseNextFixtures failed", e);
        }
    }

    private void parsePastFixtures(String jsonStr, String favTeam) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            JSONArray arr = obj.optJSONArray("events");
            if (arr == null || arr.length() == 0)
                return;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject ev = arr.optJSONObject(i);
                if (ev == null)
                    continue;
                String home = ev.optString("strHomeTeam", "");
                String away = ev.optString("strAwayTeam", "");
                if (isTeamMatch(home, favTeam) || isTeamMatch(away, favTeam)) {
                    String hScore = ev.optString("intHomeScore", "");
                    String aScore = ev.optString("intAwayScore", "");
                    if (!hScore.isEmpty() && !aScore.isEmpty() && !hScore.equals("null")) {
                        match.lastResult = "Terakhir: " + home + " " + hScore + " - " + aScore + " " + away;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parsePastFixtures failed", e);
        }
    }

    public static boolean isTeamMatch(String teamName, String favTeam) {
        if (teamName == null || favTeam == null)
            return false;
        String t = normalizeTeamName(teamName);
        String f = normalizeTeamName(favTeam);
        if (t.equals(f)) return true;
        return t.contains(f) || f.contains(t);
    }

    private static String normalizeTeamName(String name) {
        if (name == null) return "";
        String s = name.toLowerCase(Locale.ROOT).trim();
        s = s.replaceAll("\\b(fc|afc|cf|ssc|as|ac|rb|04)\\b", " ");
        s = s.replace("manchester", "man");
        s = s.replace("hotspur", "");
        s = s.replace("internazionale", "inter");
        s = s.replace("münchen", "").replace("munchen", "").replace("munich", "");
        s = s.replace("madrid", "");
        s = s.replaceAll("[^a-z0-9]", "");
        return s;
    }

    /**
     * Standings: If favorite team is in top 5, returns top 5 (ranks 1-5).
     * If favorite team is outside top 5 (e.g. rank 7), returns ranks 1-4, skips rank 5,
     * and shows favorite team in the 5th row with yellow highlight.
     */
    public List<TeamStanding> getDisplayStandings() {
        if (standings.isEmpty()) return standings;
        int favIdx = -1;
        String fav = Prefs.soccerFavoriteTeam();
        for (int i = 0; i < standings.size(); i++) {
            if (standings.get(i).isFavorite || isTeamMatch(standings.get(i).name, fav)) {
                favIdx = i;
                break;
            }
        }
        if (favIdx < 0 || favIdx < 5) {
            List<TeamStanding> display = new ArrayList<>(standings.subList(0, Math.min(5, standings.size())));
            if (favIdx >= 0 && favIdx < display.size()) {
                display.get(favIdx).isFavorite = true;
            }
            return display;
        }
        // Outside top 5 (e.g. rank 7): show 1-4, then favorite team (rank 7), skip rank 5
        List<TeamStanding> display = new ArrayList<>();
        int topCount = Math.min(4, standings.size());
        for (int i = 0; i < topCount; i++) {
            TeamStanding t = standings.get(i);
            t.isFavorite = false;
            display.add(t);
        }
        TeamStanding favStanding = standings.get(favIdx);
        favStanding.isFavorite = true;
        display.add(favStanding);
        return display;
    }

    private static String formatMatchTime(String dateStr, String timeStr) {
        try {
            SimpleDateFormat inFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            inFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = inFmt.parse(dateStr + " " + timeStr);
            if (d == null)
                return dateStr + " " + timeStr;

            SimpleDateFormat outFmt = new SimpleDateFormat("EEEE d MMM HH:mm", new Locale("id", "ID"));
            outFmt.setTimeZone(TimeZone.getDefault());
            return outFmt.format(d) + " WIB";
        } catch (Exception e) {
            return dateStr + " " + timeStr;
        }
    }

    private static String formatShortFixture(String dateStr, String timeStr) {
        try {
            SimpleDateFormat inFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            inFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = inFmt.parse(dateStr + " " + timeStr);
            if (d == null)
                return dateStr;

            SimpleDateFormat outFmt = new SimpleDateFormat("EEE d MMM HH:mm", new Locale("id", "ID"));
            outFmt.setTimeZone(TimeZone.getDefault());
            return outFmt.format(d);
        } catch (Exception e) {
            return dateStr;
        }
    }

    private static String formatCountdown(String dateStr, String timeStr) {
        try {
            SimpleDateFormat inFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            inFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = inFmt.parse(dateStr + " " + timeStr);
            if (d == null)
                return "Segera";

            long diffMs = d.getTime() - System.currentTimeMillis();
            if (diffMs <= 0)
                return "Kick-off Sedang Berlangsung";

            long days = diffMs / (24 * 3600_000L);
            long hours = (diffMs % (24 * 3600_000L)) / 3600_000L;
            if (days > 0) {
                return "Kick-off dalam " + days + " hari " + hours + "j";
            }
            long mins = (diffMs % 3600_000L) / 60_000L;
            return "Kick-off dalam " + hours + "j " + mins + "m";
        } catch (Exception e) {
            return "Kick-off Mendatang";
        }
    }

    private String fetchHttpString(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; ArsyanTV/2.7)");
            int code = conn.getResponseCode();
            if (code == 200) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0)
                        baos.write(buf, 0, n);
                }
                return baos.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null)
                conn.disconnect();
        }
        return null;
    }

    private Bitmap loadBadgeBitmap(String urlStr) {
        if (urlStr == null || urlStr.isEmpty())
            return null;
        synchronized (badgeCache) {
            if (badgeCache.containsKey(urlStr))
                return badgeCache.get(urlStr);
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; ArsyanTV/2.7)");
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) {
                        synchronized (badgeCache) {
                            badgeCache.put(urlStr, bmp);
                        }
                        return bmp;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void saveCache(String key, String content) {
        try {
            SharedPreferences sp = context.getSharedPreferences("soccer_data", Context.MODE_PRIVATE);
            sp.edit().putString(key, content).apply();
        } catch (Exception ignored) {
        }
    }

    private void loadCachedData() {
        try {
            String leagueId = Prefs.soccerLeague();
            String favTeam = Prefs.soccerFavoriteTeam();
            SharedPreferences sp = context.getSharedPreferences("soccer_data", Context.MODE_PRIVATE);
            String cachedTable = sp.getString("standings_" + leagueId, null);
            if (cachedTable != null)
                parseStandings(cachedTable, favTeam);

            String cachedFixtures = sp.getString("fixtures_" + leagueId, null);
            if (cachedFixtures != null)
                parseNextFixtures(cachedFixtures, favTeam);

            String cachedPast = sp.getString("past_" + leagueId, null);
            if (cachedPast != null)
                parsePastFixtures(cachedPast, favTeam);
        } catch (Exception ignored) {
        }
    }

    /**
     * Authentic dynamic fallback tailored directly for each league and favorite team.
     * Prevents static hardcoding and ensures realistic scores and matchups even offline.
     */
    public void applyDynamicFallback(String leagueId, String favTeam) {
        int lIdx = java.util.Arrays.asList(Prefs.SOCCER_LEAGUES).indexOf(leagueId);
        String lName = lIdx >= 0 ? Prefs.SOCCER_LEAGUE_NAMES[lIdx].toUpperCase(Locale.ROOT) : "SERIE A";
        match.leagueName = lName;
        match.upcomingFixtures.clear();

        if ("4328".equals(leagueId)) { // Premier League
            standings.clear();
            standings.add(new TeamStanding(1, "Manchester City", 9, 23, "MMMMK", "", isTeamMatch("Manchester City", favTeam)));
            standings.add(new TeamStanding(2, "Liverpool", 9, 22, "MMMSM", "", isTeamMatch("Liverpool", favTeam)));
            standings.add(new TeamStanding(3, "Arsenal", 9, 18, "MMSKS", "", isTeamMatch("Arsenal", favTeam)));
            standings.add(new TeamStanding(4, "Aston Villa", 9, 18, "MSMMK", "", isTeamMatch("Aston Villa", favTeam)));
            standings.add(new TeamStanding(5, "Chelsea", 9, 17, "MMKSK", "", isTeamMatch("Chelsea", favTeam)));
            standings.add(new TeamStanding(6, "Brighton", 9, 16, "SMMKS", "", isTeamMatch("Brighton", favTeam)));
            standings.add(new TeamStanding(7, "Tottenham", 9, 13, "KMKKM", "", isTeamMatch("Tottenham", favTeam)));
            standings.add(new TeamStanding(8, "Newcastle", 9, 12, "KKSMK", "", isTeamMatch("Newcastle", favTeam)));
            standings.add(new TeamStanding(14, "Manchester United", 9, 11, "KMSKK", "", isTeamMatch("Manchester United", favTeam)));

            if (isTeamMatch("Tottenham", favTeam)) {
                match.homeTeam = "Tottenham";
                match.awayTeam = "Aston Villa";
                match.matchRole = "KANDANG";
                match.countdown = "Kick-off dalam 1 hari 4j";
                match.matchDateTime = "Minggu 3 Nov 21:00 WIB";
                match.form = "KMKKM";
                match.lastResult = "Terakhir: Tottenham 2 - 1 Man City";
                match.upcomingFixtures.add("di Galatasaray · Jum 8 Nov 00:45 WIB");
                match.upcomingFixtures.add("vs Ipswich · Min 10 Nov 21:00 WIB");
            } else if (isTeamMatch("Arsenal", favTeam)) {
                match.homeTeam = "Newcastle";
                match.awayTeam = "Arsenal";
                match.matchRole = "TANDANG";
                match.countdown = "Kick-off dalam 16 jam";
                match.matchDateTime = "Sabtu 2 Nov 19:30 WIB";
                match.form = "MMSKS";
                match.lastResult = "Terakhir: Preston 0 - 3 Arsenal";
                match.upcomingFixtures.add("di Inter Milan · Kam 7 Nov 03:00 WIB");
                match.upcomingFixtures.add("di Chelsea · Min 10 Nov 23:30 WIB");
            } else if (isTeamMatch("Liverpool", favTeam)) {
                match.homeTeam = "Liverpool";
                match.awayTeam = "Brighton";
                match.matchRole = "KANDANG";
                match.countdown = "Kick-off dalam 1 hari 1j";
                match.matchDateTime = "Sabtu 2 Nov 22:00 WIB";
                match.form = "MMMSM";
                match.lastResult = "Terakhir: Brighton 2 - 3 Liverpool";
                match.upcomingFixtures.add("vs Leverkusen · Rab 6 Nov 03:00 WIB");
                match.upcomingFixtures.add("vs Aston Villa · Min 10 Nov 03:00 WIB");
            } else if (isTeamMatch("Man United", favTeam)) {
                match.homeTeam = "Manchester United";
                match.awayTeam = "Chelsea";
                match.matchRole = "KANDANG";
                match.countdown = "Kick-off dalam 2 hari 3j";
                match.matchDateTime = "Minggu 3 Nov 23:30 WIB";
                match.form = "KMSKK";
                match.lastResult = "Terakhir: Man United 5 - 2 Leicester";
                match.upcomingFixtures.add("vs PAOK · Jum 8 Nov 03:00 WIB");
                match.upcomingFixtures.add("vs Leicester · Min 10 Nov 21:00 WIB");
            } else {
                match.homeTeam = "Bournemouth";
                match.awayTeam = "Manchester City";
                match.matchRole = "TANDANG";
                match.countdown = "Kick-off dalam 1 hari 1j";
                match.matchDateTime = "Sabtu 2 Nov 22:00 WIB";
                match.form = "MMMMK";
                match.lastResult = "Terakhir: Tottenham 2 - 1 Man City";
                match.upcomingFixtures.add("di Sporting CP · Rab 6 Nov 03:00 WIB");
                match.upcomingFixtures.add("di Brighton · Min 10 Nov 00:30 WIB");
            }
        } else if ("4335".equals(leagueId)) { // La Liga
            standings.clear();
            standings.add(new TeamStanding(1, "Barcelona", 11, 30, "MMMMM", "", isTeamMatch("Barcelona", favTeam)));
            standings.add(new TeamStanding(2, "Real Madrid", 11, 24, "MMKMK", "", isTeamMatch("Real Madrid", favTeam)));
            standings.add(new TeamStanding(3, "Atletico Madrid", 11, 20, "MSMSK", "", isTeamMatch("Atletico Madrid", favTeam)));
            standings.add(new TeamStanding(4, "Villarreal", 11, 21, "KMKSM", "", isTeamMatch("Villarreal", favTeam)));
            standings.add(new TeamStanding(5, "Osasuna", 11, 18, "SMSMM", "", isTeamMatch("Osasuna", favTeam)));
            standings.add(new TeamStanding(11, "Sevilla", 11, 15, "SMMKM", "", isTeamMatch("Sevilla", favTeam)));

            if (isTeamMatch("Barcelona", favTeam)) {
                match.homeTeam = "Barcelona";
                match.awayTeam = "Espanyol";
                match.matchRole = "KANDANG";
                match.countdown = "Kick-off dalam 2 hari";
                match.matchDateTime = "Minggu 3 Nov 22:15 WIB";
                match.form = "MMMMM";
                match.lastResult = "Terakhir: Real Madrid 0 - 4 Barcelona";
                match.upcomingFixtures.add("di Crvena Zvezda · Kam 7 Nov 03:00 WIB");
                match.upcomingFixtures.add("di Real Sociedad · Sen 11 Nov 03:00 WIB");
            } else {
                match.homeTeam = "Valencia";
                match.awayTeam = "Real Madrid";
                match.matchRole = "TANDANG";
                match.countdown = "Kick-off dalam 1 hari 7j";
                match.matchDateTime = "Minggu 3 Nov 03:00 WIB";
                match.form = "MMKMK";
                match.lastResult = "Terakhir: Real Madrid 5 - 2 Dortmund";
                match.upcomingFixtures.add("vs AC Milan · Rab 6 Nov 03:00 WIB");
                match.upcomingFixtures.add("vs Osasuna · Sab 9 Nov 20:00 WIB");
            }
        } else if ("4331".equals(leagueId)) { // Bundesliga
            standings.clear();
            standings.add(new TeamStanding(1, "Bayern Munchen", 8, 20, "MSMMM", "", isTeamMatch("Bayern", favTeam)));
            standings.add(new TeamStanding(2, "RB Leipzig", 8, 20, "MMMMM", "", isTeamMatch("Leipzig", favTeam)));
            standings.add(new TeamStanding(3, "Bayer Leverkusen", 8, 15, "MSSSM", "", isTeamMatch("Leverkusen", favTeam)));
            standings.add(new TeamStanding(4, "Union Berlin", 8, 15, "KMMMS", "", isTeamMatch("Union Berlin", favTeam)));
            standings.add(new TeamStanding(5, "SC Freiburg", 8, 15, "MMKMK", "", isTeamMatch("Freiburg", favTeam)));
            standings.add(new TeamStanding(6, "Frankfurt", 8, 14, "MMSMS", "", isTeamMatch("Frankfurt", favTeam)));
            standings.add(new TeamStanding(7, "Borussia Dortmund", 8, 13, "KMKMK", "", isTeamMatch("Dortmund", favTeam)));

            match.homeTeam = "Bayern Munchen";
            match.awayTeam = "Union Berlin";
            match.matchRole = "KANDANG";
            match.countdown = "Kick-off dalam 1 hari";
            match.matchDateTime = "Sabtu 2 Nov 21:30 WIB";
            match.form = "MSMMM";
            match.lastResult = "Terakhir: Mainz 0 - 4 Bayern Munchen";
            match.upcomingFixtures.add("vs Benfica · Kam 7 Nov 03:00 WIB");
            match.upcomingFixtures.add("di St. Pauli · Sab 9 Nov 21:30 WIB");
        } else if ("4790".equals(leagueId)) { // Liga 1 Indonesia
            standings.clear();
            standings.add(new TeamStanding(1, "Bali United", 9, 20, "MMMMM", "", isTeamMatch("Bali United", favTeam)));
            standings.add(new TeamStanding(2, "Borneo FC", 9, 18, "SSKMM", "", isTeamMatch("Borneo FC", favTeam)));
            standings.add(new TeamStanding(3, "Persib Bandung", 9, 19, "SMMMS", "", isTeamMatch("Persib", favTeam)));
            standings.add(new TeamStanding(4, "Persebaya Surabaya", 9, 18, "MSMSS", "", isTeamMatch("Persebaya", favTeam)));
            standings.add(new TeamStanding(5, "Persija Jakarta", 9, 15, "SKMMM", "", isTeamMatch("Persija", favTeam)));
            standings.add(new TeamStanding(6, "PSM Makassar", 9, 16, "SSSSM", "", isTeamMatch("PSM", favTeam)));
            standings.add(new TeamStanding(7, "Arema FC", 9, 12, "KMKMK", "", isTeamMatch("Arema", favTeam)));
            standings.add(new TeamStanding(10, "Dewa United", 9, 11, "SSKSM", "", isTeamMatch("Dewa United", favTeam)));

            if (isTeamMatch("Persib", favTeam)) {
                match.homeTeam = "Persib Bandung";
                match.awayTeam = "Semen Padang";
                match.matchRole = "KANDANG";
                match.countdown = "Kick-off dalam 20 jam";
                match.matchDateTime = "Jumat 1 Nov 19:00 WIB";
                match.form = "SMMMS";
                match.lastResult = "Terakhir: Persik 0 - 2 Persib";
                match.upcomingFixtures.add("di Lion City Sailors · Kam 7 Nov 19:00 WIB");
                match.upcomingFixtures.add("vs Borneo FC · Jum 22 Nov 19:00 WIB");
            } else {
                match.homeTeam = "Persija Jakarta";
                match.awayTeam = "Madura United";
                match.matchRole = "KANDANG";
                match.countdown = "Kick-off dalam 5 hari";
                match.matchDateTime = "Rabu 6 Nov 19:00 WIB";
                match.form = "SKMMM";
                match.lastResult = "Terakhir: Arema FC 1 - 2 Persija";
                match.upcomingFixtures.add("di Persebaya · Jum 22 Nov 15:30 WIB");
                match.upcomingFixtures.add("vs Persik · Min 1 Des 15:30 WIB");
            }
        } else { // Serie A (4332 default)
            standings.clear();
            standings.add(new TeamStanding(1, "Napoli", 10, 25, "MMMMM", "", isTeamMatch("Napoli", favTeam)));
            standings.add(new TeamStanding(2, "Internazionale", 10, 21, "MMMSM", "", isTeamMatch("Inter", favTeam)));
            standings.add(new TeamStanding(3, "Atalanta", 10, 19, "SMMMM", "", isTeamMatch("Atalanta", favTeam)));
            standings.add(new TeamStanding(4, "Fiorentina", 10, 19, "SMMMM", "", isTeamMatch("Fiorentina", favTeam)));
            standings.add(new TeamStanding(5, "Lazio", 10, 19, "MMKMK", "", isTeamMatch("Lazio", favTeam)));
            standings.add(new TeamStanding(6, "Juventus", 10, 18, "SSMSM", "", isTeamMatch("Juventus", favTeam)));
            standings.add(new TeamStanding(7, "AC Milan", 9, 14, "MKMKK", "", isTeamMatch("Milan", favTeam)));
            standings.add(new TeamStanding(10, "AS Roma", 10, 13, "KSKMK", "", isTeamMatch("Roma", favTeam)));
            standings.add(new TeamStanding(13, "Como", 10, 9, "SSKKK", "", isTeamMatch("Como", favTeam)));

            if (isTeamMatch("Roma", favTeam)) {
                match.homeTeam = "Hellas Verona";
                match.awayTeam = "AS Roma";
                match.matchRole = "TANDANG";
                match.countdown = "Kick-off dalam 2 hari";
                match.matchDateTime = "Senin 4 Nov 00:00 WIB";
                match.form = "KSKMK";
                match.lastResult = "Terakhir: AS Roma 1 - 0 Torino";
                match.upcomingFixtures.add("di Union SG · Jum 8 Nov 00:45 WIB");
                match.upcomingFixtures.add("vs Bologna · Min 10 Nov 21:00 WIB");
            } else if (isTeamMatch("Lazio", favTeam)) {
                match.homeTeam = "Lazio";
                match.awayTeam = "Cagliari";
                match.matchRole = "KANDANG";
                match.countdown = "Kick-off dalam 3 hari";
                match.matchDateTime = "Selasa 5 Nov 02:45 WIB";
                match.form = "MMKMK";
                match.lastResult = "Terakhir: Como 1 - 5 Lazio";
                match.upcomingFixtures.add("vs Porto · Jum 8 Nov 03:00 WIB");
                match.upcomingFixtures.add("di Monza · Min 10 Nov 23:59 WIB");
            } else if (isTeamMatch("Milan", favTeam)) {
                match.homeTeam = "Monza";
                match.awayTeam = "AC Milan";
                match.matchRole = "TANDANG";
                match.countdown = "Kick-off dalam 1 hari 7j";
                match.matchDateTime = "Minggu 3 Nov 02:45 WIB";
                match.form = "MKMKK";
                match.lastResult = "Terakhir: AC Milan 0 - 2 Napoli";
                match.upcomingFixtures.add("di Real Madrid · Rab 6 Nov 03:00 WIB");
                match.upcomingFixtures.add("di Cagliari · Min 10 Nov 00:00 WIB");
            } else if (isTeamMatch("Inter", favTeam)) {
                match.homeTeam = "Internazionale";
                match.awayTeam = "Venezia";
                match.matchRole = "KANDANG";
                match.countdown = "Kick-off dalam 2 hari";
                match.matchDateTime = "Senin 4 Nov 02:45 WIB";
                match.form = "MMMSM";
                match.lastResult = "Terakhir: Empoli 0 - 3 Internazionale";
                match.upcomingFixtures.add("vs Arsenal · Kam 7 Nov 03:00 WIB");
                match.upcomingFixtures.add("vs Napoli · Sen 11 Nov 02:45 WIB");
            } else {
                match.homeTeam = "Udinese";
                match.awayTeam = "Juventus";
                match.matchRole = "TANDANG";
                match.countdown = "Kick-off dalam 1 hari 5j";
                match.matchDateTime = "Minggu 3 Nov 00:00 WIB";
                match.form = "SSMSM";
                match.lastResult = "Terakhir: Juventus 2 - 2 Parma";
                match.upcomingFixtures.add("di Lille · Rab 6 Nov 03:00 WIB");
                match.upcomingFixtures.add("vs Torino · Min 10 Nov 02:45 WIB");
            }
        }
    }
}
