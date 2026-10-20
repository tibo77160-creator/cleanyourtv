package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Day and night looks. config.json "theme": "auto" (default: light from sunrise to sunset at
 * "latitude"/"longitude", Melun by default), "jour"/"day" (always light) or "nuit"/"night" (always
 * the black OLED look). Applied from MainActivity on resume and every minute; only repaints when the
 * look actually changes.
 */
final class Theme {

    private Theme() {
    }

    /** Read by the views that draw themselves (backdrop scrim, dock band, weather drawer). */
    static volatile boolean day;
    private static Boolean applied;

    static final int INK = 0xFF1D1D1F;
    static final int PAPER = 0xFFF5F5F7;

    static boolean computeDay(Context context) {
        String mode = Extras.str(context, "theme", "auto").toLowerCase(Locale.ROOT);
        if (mode.equals("jour") || mode.equals("day") || mode.equals("light")) return true;
        if (mode.equals("nuit") || mode.equals("night") || mode.equals("dark")) return false;
        double lat = Extras.num(context, "latitude", 48.5397);
        double lon = Extras.num(context, "longitude", 2.6603);
        long now = System.currentTimeMillis();
        long rise = sun(lat, lon, now, true);
        long set = sun(lat, lon, now, false);
        if (rise < 0 || set < 0) return false;
        return now >= rise && now < set;
    }

    static void apply(Activity a) {
        boolean d = !Extras.safeMode(a) && computeDay(a);
        day = d;
        if (applied != null && applied == d) return;
        applied = d;
        float density = a.getResources().getDisplayMetrics().density;

        View root = a.findViewById(R.id.root);
        if (root != null) root.setBackgroundColor(d ? PAPER : 0xFF000000);
        color(a, R.id.greeting, d ? 0x8C1D1D1F : 0x8CFFFFFF);
        color(a, R.id.clock, d ? INK : 0xFFFFFFFF);
        color(a, R.id.ampm, d ? 0xB31D1D1F : 0xB3FFFFFF);
        color(a, R.id.date, d ? 0xA61D1D1F : 0xA6FFFFFF);
        color(a, R.id.shamsiDate, d ? 0xE61D1D1F : 0xE6FFFFFF);
        color(a, R.id.heroName, d ? INK : 0xFFFFFFFF);

        // The thin divider between the clock and the date.
        View clock = a.findViewById(R.id.clock);
        if (clock != null && clock.getParent() instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) clock.getParent();
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child.getClass() == View.class) child.setBackgroundColor(d ? 0x331D1D1F : 0x33FFFFFF);
            }
        }

        View buttons = a.findViewById(R.id.topButtons);
        if (buttons instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) buttons;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i) instanceof TextView) pill((TextView) g.getChildAt(i), d, density);
            }
        }

        View gridView = a.findViewById(R.id.grid);
        if (gridView instanceof RecyclerView) {
            RecyclerView grid = (RecyclerView) gridView;
            if (grid.getTag(R.id.grid) == null) {
                grid.setTag(R.id.grid, Boolean.TRUE);
                grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override
                    public void onChildViewAttachedToWindow(View view) {
                        tileName(view);
                    }

                    @Override
                    public void onChildViewDetachedFromWindow(View view) {
                    }
                });
            }
            for (int i = 0; i < grid.getChildCount(); i++) tileName(grid.getChildAt(i));
            grid.invalidateItemDecorations();
            grid.invalidate();
        }
    }

    private static void color(Activity a, int id, int color) {
        View v = a.findViewById(id);
        if (v instanceof TextView) ((TextView) v).setTextColor(color);
    }

    private static void tileName(View item) {
        View n = item.findViewById(R.id.name);
        if (n instanceof TextView) ((TextView) n).setTextColor(day ? 0xF21D1D1F : 0xF2FFFFFF);
    }

    /** Top-bar pills: frosted white on black at night; a quiet grey outline with an ink focus by day. */
    private static void pill(TextView tv, boolean d, float density) {
        if (tv.getTag(R.id.topButtons) == null) {
            // Keep the original look to come back to at night.
            tv.setTag(R.id.topButtons, tv.getBackground());
            tv.setTag(R.id.leftBlock, tv.getTextColors());
        }
        if (d) {
            GradientDrawable on = new GradientDrawable();
            on.setColor(INK);
            on.setCornerRadius(21 * density);
            GradientDrawable off = new GradientDrawable();
            off.setColor(0x0F000000);
            off.setStroke(Math.max(1, Math.round(density)), 0x26000000);
            off.setCornerRadius(21 * density);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, on);
            bg.addState(new int[0], off);
            tv.setBackground(bg);
            ColorStateList text = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                    new int[]{0xFFFFFFFF, INK});
            tv.setTextColor(text);
            tv.setCompoundDrawableTintList(text);
        } else {
            Object bg = tv.getTag(R.id.topButtons);
            Object text = tv.getTag(R.id.leftBlock);
            if (bg instanceof Drawable) tv.setBackground((Drawable) bg);
            if (text instanceof ColorStateList) {
                tv.setTextColor((ColorStateList) text);
                tv.setCompoundDrawableTintList((ColorStateList) text);
            }
        }
    }

    /**
     * Sunrise or sunset today (local date) in epoch ms, from the classic almanac algorithm
     * (zenith 90.833 degrees). -1 when the sun does not rise or set that day.
     */
    static long sun(double lat, double lon, long nowMs, boolean rising) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(nowMs);
        int n = local.get(Calendar.DAY_OF_YEAR);
        double lngHour = lon / 15.0;
        double t = n + ((rising ? 6 : 18) - lngHour) / 24.0;
        double m = 0.9856 * t - 3.289;
        double l = norm360(m + 1.916 * Math.sin(Math.toRadians(m))
                + 0.020 * Math.sin(Math.toRadians(2 * m)) + 282.634);
        double ra = norm360(Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(l)))));
        ra = (ra + (Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90)) / 15.0;
        double sinDec = 0.39782 * Math.sin(Math.toRadians(l));
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (Math.cos(Math.toRadians(90.833)) - sinDec * Math.sin(Math.toRadians(lat)))
                / (cosDec * Math.cos(Math.toRadians(lat)));
        if (cosH > 1 || cosH < -1) return -1;
        double h = Math.toDegrees(Math.acos(cosH));
        if (rising) h = 360 - h;
        h /= 15.0;
        double ut = (h + ra - 0.06571 * t - 6.622 - lngHour) % 24;
        if (ut < 0) ut += 24;
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        return utc.getTimeInMillis() + Math.round(ut * 3_600_000L);
    }

    private static double norm360(double x) {
        x %= 360;
        return x < 0 ? x + 360 : x;
    }
}
