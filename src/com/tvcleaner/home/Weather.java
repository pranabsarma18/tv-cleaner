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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Weather for one fixed place from Open-Meteo (free, no key), keeping the last good answer on disk.
 * Off until install.sh passes --es weather "lat,lon,Name"; while off nothing is fetched.
 */
final class Weather {
    private static final String PREFS = "weather";
    private static final long REFRESH_MS = 30L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "weather");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    interface Listener {
        void onData(Data data);
        void onUnavailable();
    }

    static final class Day {
        final String date; // yyyy-MM-dd, the place's own date
        final int code, max, min, rain; // rain: highest chance in %, -1 if unknown
        final String sunrise, sunset;    // HH:mm, the place's own time
        Day(String date, int code, int max, int min, int rain, String sunrise, String sunset) {
            this.date = date; this.code = code; this.max = max; this.min = min; this.rain = rain;
            this.sunrise = sunrise; this.sunset = sunset;
        }
    }

    static final class Data {
        final String place;
        final int temp, feels, humidity, code;
        final boolean day;
        final Day[] days;
        final long fetchedAt;
        Data(String place, int temp, int feels, int humidity, int code, boolean day, Day[] days, long fetchedAt) {
            this.place = place; this.temp = temp; this.feels = feels; this.humidity = humidity;
            this.code = code; this.day = day; this.days = days; this.fetchedAt = fetchedAt;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    Weather(Context context) { this.context = context.getApplicationContext(); }

    private SharedPreferences prefs() { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    boolean enabled() { return !prefs().getString("place", "").isEmpty(); }

    /** Sets the place from "lat,lon,Name" (blank or "none" turns it off). Returns false if it can't be read. */
    static boolean setPlace(Context context, String value) {
        value = value == null ? "" : value.trim();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (value.isEmpty() || value.equalsIgnoreCase("none")) { p.edit().clear().apply(); return true; }
        String[] v = value.split(",", 3);
        if (v.length < 2) return false;
        double lat, lon;
        try { lat = Double.parseDouble(v[0].trim()); lon = Double.parseDouble(v[1].trim()); }
        catch (NumberFormatException e) { return false; }
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return false;
        String name = v.length > 2 && !v[2].trim().isEmpty() ? v[2].trim() : String.format(Locale.US, "%.2f, %.2f", lat, lon);
        // A new place must never show the old place's weather: drop the cache with it.
        p.edit().clear().putString("place", name)
                .putString("lat", Double.toString(lat)).putString("lon", Double.toString(lon)).apply();
        return true;
    }

    /** Shows the cache at once, then refreshes it if it's older than 30 minutes. Does nothing while off. */
    void loadAndRefresh(Listener listener) {
        if (!enabled()) return;
        SharedPreferences p = prefs();
        Data cached = null;
        String raw = p.getString("json", null);
        if (raw != null) try { cached = parse(raw, p.getString("place", ""), p.getLong("fetched", 0)); } catch (Exception ignored) {}
        if (cached != null) listener.onData(cached);
        if (cached == null || System.currentTimeMillis() - p.getLong("fetched", 0) >= REFRESH_MS) refresh(listener, cached == null);
    }

    private void refresh(Listener listener, boolean reportFailure) {
        if (!BUSY.compareAndSet(false, true)) return;
        final SharedPreferences p = prefs();
        final String place = p.getString("place", ""), lat = p.getString("lat", ""), lon = p.getString("lon", "");
        EXECUTOR.execute(() -> {
            Data data = null;
            try {
                String json = get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=5");
                long now = System.currentTimeMillis();
                data = parse(json, place, now);
                // Only keep it if the place wasn't changed while this was loading.
                if (place.equals(p.getString("place", ""))) p.edit().putString("json", json).putLong("fetched", now).apply();
                else data = null;
            } catch (Exception ignored) { /* Keep the last good cache. */ }
            final Data result = data;
            if (result != null) main.post(() -> listener.onData(result));
            else if (reportFailure) main.post(listener::onUnavailable);
            BUSY.set(false);
        });
    }

    static Data parse(String json, String place, long fetchedAt) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject cur = root.getJSONObject("current");
        JSONObject daily = root.getJSONObject("daily");
        JSONArray time = daily.getJSONArray("time");
        JSONArray rain = daily.optJSONArray("precipitation_probability_max");
        Day[] days = new Day[time.length()];
        for (int i = 0; i < days.length; i++) {
            days[i] = new Day(time.getString(i), daily.getJSONArray("weather_code").getInt(i),
                    (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)),
                    (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)),
                    rain == null || rain.isNull(i) ? -1 : rain.getInt(i),
                    clockPart(daily.getJSONArray("sunrise").getString(i)),
                    clockPart(daily.getJSONArray("sunset").getString(i)));
        }
        if (days.length == 0) throw new Exception("No forecast");
        return new Data(place, (int) Math.round(cur.getDouble("temperature_2m")),
                (int) Math.round(cur.getDouble("apparent_temperature")), cur.getInt("relative_humidity_2m"),
                cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1, days, fetchedAt);
    }

    /** "2026-10-24T05:32" → "05:32". */
    static String clockPart(String isoLocal) {
        int t = isoLocal.indexOf('T');
        return t >= 0 && isoLocal.length() >= t + 6 ? isoLocal.substring(t + 1, t + 6) : "";
    }

    /** "05:32" → "5:32 AM", or "05:32" with a 24-hour clock. */
    static String formatClock(String hhmm, boolean h24) {
        if (hhmm.length() != 5) return hhmm;
        if (h24) return hhmm;
        int h = Integer.parseInt(hhmm.substring(0, 2));
        return (h % 12 == 0 ? 12 : h % 12) + hhmm.substring(2) + (h < 12 ? " AM" : " PM");
    }

    /** What the WMO weather code means, in words. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "TVCleaner-Weather/1.0");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + c.getResponseCode());
            StringBuilder body = new StringBuilder();
            try (InputStream in = c.getInputStream(); BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    if (body.length() + n > 64 * 1024) throw new Exception("Weather response too large");
                    body.append(buf, 0, n);
                }
            }
            return body.toString();
        } finally { c.disconnect(); }
    }
}
