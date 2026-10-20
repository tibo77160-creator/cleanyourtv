package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Top-bar weather pill ("☀ 15°"). OK opens a drawer at the bottom of the screen with the next hours,
 * sunrise and sunset. Data from Open-Meteo (free, no key, no account), refreshed every 30 minutes.
 *
 * config.json: "city", "latitude", "longitude" (Melun by default); "weather": false hides the pill.
 */
public class WeatherView extends TextView {

    private static final long REFRESH_MS = 30 * 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refresh;
    private final boolean enabled;
    private JSONObject data;
    private long fetchedAt;
    private PopupWindow drawer;

    public WeatherView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Extras.installWatchdog(context);
        enabled = !Extras.safeMode(context) && Extras.bool(context, "weather", true);
        if (!enabled) {
            setVisibility(GONE);
            return;
        }
        setText("—°");
        setOnClickListener(v -> openDrawer());
        // Same rule as the other pills: Up stays in the top bar.
        setOnKeyListener((v, keyCode, event) ->
                event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (enabled) main.post(refresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        main.removeCallbacks(refresh);
        if (drawer != null) drawer.dismiss();
    }

    private void refresh() {
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, REFRESH_MS);
        if (data != null && System.currentTimeMillis() - fetchedAt < REFRESH_MS - 5000) return;
        final Context c = getContext();
        io.execute(() -> {
            try {
                String url = String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code,is_day"
                                + "&hourly=temperature_2m,weather_code,is_day"
                                + "&daily=sunrise,sunset&timezone=auto&forecast_days=2",
                        Extras.num(c, "latitude", 48.5397), Extras.num(c, "longitude", 2.6603));
                String body = Extras.get(url, null);
                if (body == null) return;
                JSONObject json = new JSONObject(body);
                main.post(() -> {
                    data = json;
                    fetchedAt = System.currentTimeMillis();
                    showCurrent();
                });
            } catch (Exception ignored) {
                // Offline: keep the last reading; the next refresh will try again.
            }
        });
    }

    private void showCurrent() {
        JSONObject cur = data.optJSONObject("current");
        if (cur == null) return;
        int temp = (int) Math.round(cur.optDouble("temperature_2m", 0));
        String sym = symbol(cur.optInt("weather_code", 3), cur.optInt("is_day", 1) == 1);
        setText(sym + "  " + temp + "°");
    }

    // ---- drawer --------------------------------------------------------------------------------------

    private void openDrawer() {
        if (data == null) {
            refresh();
            return;
        }
        Context c = getContext();
        float dp = getResources().getDisplayMetrics().density;
        boolean light = Theme.day;
        int ink = light ? Theme.INK : 0xFFFFFFFF;
        int soft = (ink & 0x00FFFFFF) | 0x99000000;
        Typeface font = getTypeface();

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(px(32, dp), px(20, dp), px(32, dp), px(20, dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(light ? 0xF7FFFFFF : 0xF2141416);
        bg.setStroke(Math.max(1, Math.round(dp)), light ? 0x1A000000 : 0x26FFFFFF);
        bg.setCornerRadius(26 * dp);
        box.setBackground(bg);

        // Now: city, temperature, condition.
        JSONObject cur = data.optJSONObject("current");
        LinearLayout now = column(c);
        now.addView(text(c, Extras.str(c, "city", "Melun"), 13, soft, font));
        now.addView(text(c, (cur == null ? "—" : Math.round(cur.optDouble("temperature_2m", 0))) + "°", 40, ink, font));
        now.addView(text(c, cur == null ? "" : label(cur.optInt("weather_code", 3)), 13, soft, font));
        box.addView(now, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(divider(c, dp, light));

        // The next eight hours.
        JSONObject hourly = data.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.optJSONArray("time");
            JSONArray temps = hourly.optJSONArray("temperature_2m");
            JSONArray codes = hourly.optJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            if (times != null && temps != null && codes != null) {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.ROOT);
                f.setTimeZone(TimeZone.getTimeZone(data.optString("timezone", TimeZone.getDefault().getID())));
                String nowKey = f.format(new Date());
                int start = 0;
                while (start < times.length() && times.optString(start).compareTo(nowKey) < 0) start++;
                for (int i = start; i < Math.min(times.length(), start + 8); i++) {
                    LinearLayout col = column(c);
                    String hour = times.optString(i);
                    String h = hour.length() >= 13 ? hour.substring(11, 13) + "h" : "";
                    col.addView(text(c, i == start ? (Extras.french() ? "Maint." : "Now") : h, 12, soft, font));
                    boolean isDay = days == null || days.optInt(i, 1) == 1;
                    col.addView(text(c, symbol(codes.optInt(i, 3), isDay), 22, ink, font));
                    col.addView(text(c, Math.round(temps.optDouble(i, 0)) + "°", 16, ink, font));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(58, dp), ViewGroup.LayoutParams.WRAP_CONTENT);
                    box.addView(col, lp);
                }
            }
        }
        box.addView(divider(c, dp, light));

        // Sunrise and sunset.
        JSONObject daily = data.optJSONObject("daily");
        LinearLayout sun = column(c);
        if (daily != null) {
            String rise = hm(daily.optJSONArray("sunrise"));
            String set = hm(daily.optJSONArray("sunset"));
            sun.addView(text(c, (Extras.french() ? "Lever  " : "Sunrise  ") + rise, 14, ink, font));
            sun.addView(text(c, (Extras.french() ? "Coucher  " : "Sunset  ") + set, 14, ink, font));
        }
        box.addView(sun);

        final PopupWindow p = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        p.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        p.setOutsideTouchable(true);
        box.setFocusable(true);
        box.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    p.dismiss();
                    return true;
                default:
                    return false;
            }
        });
        drawer = p;
        p.showAtLocation(getRootView(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, px(44, dp));
        box.post(box::requestFocus);
    }

    private static String hm(JSONArray a) {
        String s = a == null ? "" : a.optString(0, "");
        return s.length() >= 16 ? s.substring(11, 16) : "—";
    }

    private static int px(float v, float dp) {
        return Math.round(v * dp);
    }

    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        return l;
    }

    private static View divider(Context c, float dp, boolean light) {
        View v = new View(c);
        v.setBackgroundColor(light ? 0x1F000000 : 0x26FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, Math.round(dp)), px(56, dp));
        lp.setMargins(px(22, dp), 0, px(22, dp), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private static TextView text(Context c, String s, float sp, int color, Typeface font) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (font != null) t.setTypeface(font);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setIncludeFontPadding(false);
        t.setPadding(0, Math.round(sp * 0.18f), 0, Math.round(sp * 0.18f));
        return t;
    }

    /** WMO weather code to a symbol the TV's fonts have. */
    static String symbol(int code, boolean isDay) {
        if (code == 0) return isDay ? "☀" : "☾";
        if (code <= 2) return isDay ? "⛅" : "☁";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "≋";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "☂";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄";
        if (code >= 95) return "⚡";
        return "☁";
    }

    private static String label(int code) {
        boolean fr = Extras.french();
        if (code == 0) return fr ? "Ciel dégagé" : "Clear";
        if (code <= 2) return fr ? "Peu nuageux" : "Partly cloudy";
        if (code == 3) return fr ? "Couvert" : "Overcast";
        if (code == 45 || code == 48) return fr ? "Brouillard" : "Fog";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return fr ? "Pluie" : "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return fr ? "Neige" : "Snow";
        if (code >= 95) return fr ? "Orage" : "Thunderstorm";
        return fr ? "Nuageux" : "Cloudy";
    }
}
