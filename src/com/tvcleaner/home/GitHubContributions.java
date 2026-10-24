package com.tvcleaner.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches the public GitHub contribution calendar, keeping the last good result on disk. */
final class GitHubContributions {
    private static final Pattern USER_NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");
    private static final String PREFS = "github_contributions";
    private static final long REFRESH_MS = 10L * 60L * 1000L;
    private static final int MAX_HTML = 900 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "github-calendar");
        t.setDaemon(true);
        return t;
    });
    private static final Pattern DAY = Pattern.compile("<td\\b[^>]*data-date=\"(\\d{4}-\\d{2}-\\d{2})\"[^>]*data-level=\"([0-4])\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAY_ID = Pattern.compile("id=\"(contribution-day-component-\\d-\\d+)\"");
    private static final Pattern TIP = Pattern.compile("<tool-tip\\b[^>]*for=\"([^\"]+)\"[^>]*>(.*?)</tool-tip>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COUNT = Pattern.compile("([\\d,]+) contribution", Pattern.CASE_INSENSITIVE);
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    interface Listener {
        void onData(Data data, boolean fresh);
        void onUnavailable();
    }

    static final class Day {
        final long date;
        final int count, level;
        Day(long date, int count, int level) { this.date = date; this.count = count; this.level = level; }
    }

    static final class Data {
        final List<Day> days;
        final long fetchedAt;
        final String commitDate;
        final int publicPushCommits;
        final List<String> processedPushIds;
        Data(List<Day> days, long fetchedAt, String commitDate, int publicPushCommits, List<String> processedPushIds) {
            this.days = Collections.unmodifiableList(new ArrayList<>(days));
            this.fetchedAt = fetchedAt;
            this.commitDate = commitDate;
            this.publicPushCommits = publicPushCommits;
            this.processedPushIds = Collections.unmodifiableList(new ArrayList<>(processedPushIds));
        }
        int lastYearTotal() {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            long end = startOfDay(c.getTimeInMillis()) + 24L * 60L * 60L * 1000L;
            long start = end - 365L * 24L * 60L * 60L * 1000L;
            int total = 0;
            for (Day d : days) if (d.date >= start && d.date <= end) total += d.count;
            return total;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    GitHubContributions(Context context) { this.context = context.getApplicationContext(); }

    /** The GitHub user whose graph is shown, or "" when the graph is off (the default: no network use). */
    static String user(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("user", "");
    }

    /** Sets the user (blank or "none" turns the graph off). Returns false for an invalid name. */
    static boolean setUser(Context context, String name) {
        name = name == null ? "" : name.trim();
        if (name.equalsIgnoreCase("none")) name = "";
        if (!name.isEmpty() && !USER_NAME.matcher(name).matches()) return false;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (name.equals(p.getString("user", ""))) return true;
        // Another user's numbers must never show: drop the cache with the old name.
        p.edit().clear().putString("user", name).apply();
        return true;
    }

    boolean enabled() { return !user(context).isEmpty(); }

    /** Shows the cache at once, then refreshes it if it's older than 10 minutes. Does nothing while off. */
    void loadAndRefresh(Listener listener) {
        if (!enabled()) return;
        Data cached = loadCache();
        if (cached != null) listener.onData(cached, false);
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean missingCommitCache = !p.contains("commit_date") || !p.contains("public_push_commits");
        if (cached == null || missingCommitCache || System.currentTimeMillis() - p.getLong("fetched", 0) >= REFRESH_MS) {
            refresh(listener, cached == null);
        }
    }

    private void refresh(Listener listener, boolean reportFailure) {
        if (!BUSY.compareAndSet(false, true)) return;
        EXECUTOR.execute(() -> {
            Data result = null;
            try { result = fetch(); } catch (Exception ignored) { /* Keep the last good cache. */ }
            final Data data = result;
            if (data != null) {
                saveCache(data);
                main.post(() -> listener.onData(data, true));
            } else if (reportFailure) main.post(listener::onUnavailable);
            BUSY.set(false);
        });
    }

    private Data fetch() throws Exception {
        String html = get("https://github.com/users/" + user(context) + "/contributions", "text/html", MAX_HTML);
        long fetchedAt = System.currentTimeMillis();
        Data graph = parse(html, fetchedAt);
        Data old = loadCache();
        String day = localDay(fetchedAt);
        int commits;
        String commitDate;
        List<String> processed;
        try {
            CommitResult result = fetchPublicPushCommits(day, old);
            commits = result.count;
            commitDate = day;
            processed = result.processed;
        } catch (Exception e) {
            commits = old == null ? -1 : old.publicPushCommits;
            commitDate = old == null ? "" : old.commitDate;
            processed = old == null ? Collections.emptyList() : old.processedPushIds;
        }
        return new Data(graph.days, fetchedAt, commitDate, commits, processed);
    }

    private static final class CommitResult {
        final int count;
        final List<String> processed;
        CommitResult(int count, List<String> processed) { this.count = count; this.processed = processed; }
    }

    private CommitResult fetchPublicPushCommits(String today, Data old) throws Exception {
        String json = get("https://api.github.com/users/" + user(context) + "/events/public?per_page=100",
                "application/vnd.github+json", 512 * 1024);
        JSONArray events = new JSONArray(json);
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        int commits = old != null && today.equals(old.commitDate) ? old.publicPushCommits : 0;
        Set<String> processed = new HashSet<>();
        if (old != null && today.equals(old.commitDate)) processed.addAll(old.processedPushIds);
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null || !"PushEvent".equals(event.optString("type"))) continue;
            Date created = utc.parse(event.optString("created_at"));
            if (created == null || !today.equals(localDay(created.getTime()))) continue;
            String id = event.optString("id", "");
            if (id.isEmpty() || processed.contains(id)) continue;
            JSONObject payload = event.optJSONObject("payload");
            int added;
            if (payload != null && payload.has("distinct_size")) added = Math.max(0, payload.optInt("distinct_size"));
            else if (payload != null && payload.has("size")) added = Math.max(0, payload.optInt("size"));
            else added = comparePush(payload, event.optJSONObject("repo"));
            commits += added;
            processed.add(id);
        }
        return new CommitResult(commits, new ArrayList<>(processed));
    }

    private int comparePush(JSONObject payload, JSONObject repo) throws Exception {
        if (payload == null || repo == null) throw new Exception("Push details unavailable");
        String name = repo.optString("name", "");
        String before = payload.optString("before", "");
        String head = payload.optString("head", "");
        if (name.indexOf('/') < 1 || before.isEmpty() || head.isEmpty()) throw new Exception("Push refs unavailable");
        if (head.matches("0+")) return 0; // Branch deletion.
        String json = get("https://api.github.com/repos/" + name + "/compare/" + before + "..." + head,
                "application/vnd.github+json", 512 * 1024);
        return Math.max(0, new JSONObject(json).optInt("ahead_by", 0));
    }

    private String get(String address, String accept, int maxChars) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "TVCleaner-GitHubCalendar/1.0");
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setInstanceFollowRedirects(true);
        try {
            int status = c.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + status);
            StringBuilder body = new StringBuilder(Math.min(maxChars, 160000));
            try (InputStream in = c.getInputStream(); BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    if (body.length() + n > maxChars) throw new Exception("GitHub response too large");
                    body.append(buf, 0, n);
                }
            }
            return body.toString();
        } finally { c.disconnect(); }
    }

    /** Parsing is separate from the connection to keep the HTML format easy to validate. */
    static Data parse(String html, long fetchedAt) throws Exception {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        Matcher tips = TIP.matcher(html);
        while (tips.find()) {
            Matcher count = COUNT.matcher(tips.group(2));
            counts.put(tips.group(1), count.find() ? Integer.parseInt(count.group(1).replace(",", "")) : 0);
        }
        List<Day> days = new ArrayList<>(371);
        Matcher cells = DAY.matcher(html);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        while (cells.find()) {
            String tag = cells.group();
            Matcher id = DAY_ID.matcher(tag);
            if (!id.find()) continue;
            ParsePosition pos = new ParsePosition(0);
            Date date = format.parse(cells.group(1), pos);
            if (date == null || pos.getIndex() != 10) continue;
            int level = Integer.parseInt(cells.group(2));
            days.add(new Day(date.getTime(), counts.containsKey(id.group(1)) ? counts.get(id.group(1)) : 0, level));
        }
        if (days.size() < 350) throw new Exception("Contribution calendar missing or incomplete");
        return new Data(days, fetchedAt, "", -1, Collections.emptyList());
    }

    private Data loadCache() {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString("days", null);
        if (raw == null) return null;
        try {
            List<Day> days = new ArrayList<>();
            for (String row : raw.split(";")) {
                String[] v = row.split(",");
                if (v.length != 3) continue;
                days.add(new Day(Long.parseLong(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2])));
            }
            if (days.size() < 350) return null;
            String rawIds = p.getString("processed_push_ids", "");
            List<String> ids = new ArrayList<>();
            if (!rawIds.isEmpty()) Collections.addAll(ids, rawIds.split(","));
            return new Data(days, p.getLong("fetched", 0), p.getString("commit_date", ""),
                    p.getInt("public_push_commits", -1), ids);
        } catch (Exception e) { return null; }
    }

    private void saveCache(Data data) {
        StringBuilder b = new StringBuilder(data.days.size() * 24);
        for (Day d : data.days) b.append(d.date).append(',').append(d.count).append(',').append(d.level).append(';');
        StringBuilder ids = new StringBuilder();
        for (String id : data.processedPushIds) { if (ids.length() > 0) ids.append(','); ids.append(id); }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("days", b.toString()).putLong("fetched", data.fetchedAt)
                .putString("commit_date", data.commitDate).putInt("public_push_commits", data.publicPushCommits)
                .putString("processed_push_ids", ids.toString()).apply();
    }

    private static String localDay(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    private static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
