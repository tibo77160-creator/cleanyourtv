package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared helpers for the add-ons (backdrops and highlights, weather, day/night theme): the pushed
 * config.json, a small HTTP GET, and a crash watchdog.
 *
 * Watchdog: three crashes within five minutes put the add-ons in safe mode for 30 minutes, so Home
 * falls back to the plain launcher instead of crash-looping.
 */
final class Extras {

    private Extras() {
    }

    private static JSONObject cached;
    private static long cachedAt;

    static synchronized JSONObject config(Context context) {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < 60_000L) return cached;
        JSONObject json = new JSONObject();
        try {
            File dir = context.getExternalFilesDir(null);
            File file = dir != null ? new File(dir, "config.json") : null;
            if (file != null && file.isFile()) {
                json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // A broken config keeps the defaults; it must never break Home.
        }
        cached = json;
        cachedAt = now;
        return json;
    }

    static String str(Context context, String key, String def) {
        String v = config(context).optString(key, def);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    static double num(Context context, String key, double def) {
        return config(context).optDouble(key, def);
    }

    static boolean bool(Context context, String key, boolean def) {
        return config(context).optBoolean(key, def);
    }

    static boolean french() {
        return "fr".equals(Locale.getDefault().getLanguage());
    }

    /** GET a URL as text; null on any non-200 answer. Call off the main thread. */
    static String get(String url, String bearer) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        try {
            http.setConnectTimeout(5000);
            http.setReadTimeout(8000);
            http.setRequestProperty("Accept", "application/json");
            if (bearer != null) http.setRequestProperty("Authorization", "Bearer " + bearer);
            if (http.getResponseCode() != 200) return null;
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 2 * 1024 * 1024) return null;
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            http.disconnect();
        }
    }

    // ---- crash watchdog ------------------------------------------------------------------------------

    private static final String PREFS = "extras_watchdog";
    private static final long WINDOW_MS = 5 * 60_000L;
    private static final long SAFE_MS = 30 * 60_000L;
    private static boolean installed;

    static synchronized void installWatchdog(Context context) {
        if (installed) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                List<Long> times = crashes(p.getString("crashes", ""));
                times.add(System.currentTimeMillis());
                StringBuilder b = new StringBuilder();
                for (int i = Math.max(0, times.size() - 10); i < times.size(); i++) b.append(times.get(i)).append(',');
                p.edit().putString("crashes", b.toString()).commit();
            } catch (Throwable ignored) {
                // Never get in the way of the real crash report.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static boolean safeMode(Context context) {
        try {
            List<Long> times = crashes(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("crashes", ""));
            if (times.size() < 3) return false;
            long last = times.get(times.size() - 1);
            if (System.currentTimeMillis() - last > SAFE_MS) return false;
            return last - times.get(times.size() - 3) <= WINDOW_MS;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Long> crashes(String s) {
        List<Long> out = new ArrayList<>();
        for (String part : s.split(",")) {
            try {
                if (!part.isEmpty()) out.add(Long.parseLong(part));
            } catch (NumberFormatException ignored) {
                // Skip a corrupt entry.
            }
        }
        return out;
    }
}
