package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen artwork behind the dock for the app you are on, in this order:
 *  1. your own picture, <external files>/backdrops/<package>.jpg;
 *  2. "À la une": what is popular now on that service in your country, from TMDB (free key in
 *     config.json "tmdb_key"), with the title, kind, genre and year, rotating every 8 seconds;
 *  3. what the app publishes to the TV's home screen ("Watch next", then its channels);
 *  4. nothing: the coloured ambient glow underneath shows instead.
 * Cross-fade, slow drift, and a scrim (black at night, light by day) that keeps the dock readable.
 * With Philips Ambilight in "Follow video" the wall takes the colours of the artwork.
 *
 * config.json: "backdrops" (default true), "tmdb_key", "region" (default FR),
 * "tmdb_providers": {"package": providerId} to add or override services.
 */
public class BackdropView extends FrameLayout {

    private static final long DWELL_MS = 350;
    private static final long FADE_MS = 700;
    private static final long DRIFT_MS = 24000;
    private static final long ROTATE_MS = 8000;
    private static final long TMDB_TTL_MS = 3 * 3_600_000L;
    private static final int TARGET_W = 1280;
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final int MAX_URIS = 12;
    private static final int MAX_HIGHLIGHTS = 6;
    private static final long YT_TTL_MS = 30 * 60_000L;

    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final String[] COLUMNS = {
            "poster_art_uri", "poster_art_aspect_ratio", "thumbnail_uri", "thumbnail_aspect_ratio"};

    /** TMDB watch-provider ids of the apps people have on a French TV. */
    private static final Map<String, Integer> PROVIDERS = new HashMap<>();

    static {
        PROVIDERS.put("com.netflix.ninja", 8);
        PROVIDERS.put("com.amazon.amazonvideo.livingroom", 119);
        PROVIDERS.put("com.disney.disneyplus", 337);
        PROVIDERS.put("com.apple.atve.androidtv.appletv", 350);
        PROVIDERS.put("com.crunchyroll.crunchyroid", 283);
        PROVIDERS.put("com.canal.android.canal", 381);
    }

    private static boolean landscape(int ratio) {
        return ratio == 0 || ratio == 1 || ratio == 2;   // 16:9, 3:2, 4:3
    }

    /** One picture to show, with an optional caption. */
    private static final class Slide {
        final String key;
        final Uri image;
        final String kicker, title, meta;

        Slide(String key, Uri image, String kicker, String title, String meta) {
            this.key = key;
            this.image = image;
            this.kicker = kicker;
            this.title = title;
            this.meta = meta;
        }
    }

    private static final class Highlight {
        final String image, title, meta;

        Highlight(String image, String title, String meta) {
            this.image = image;
            this.title = title;
            this.meta = meta;
        }
    }

    private static final class Cached {
        final long at;
        final List<Highlight> items;

        Cached(long at, List<Highlight> items) {
            this.at = at;
            this.items = items;
        }
    }

    private final ImageView[] layers = new ImageView[2];
    private final ObjectAnimator[] drifts = new ObjectAnimator[2];
    private int front = 0;
    private final View scrim;
    private final LayerDrawable scrimNight, scrimDay;
    private final LinearLayout caption;
    private final TextView kicker, title, meta;
    private final float dp;
    private View hero;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Map<String, Integer> rotation = new HashMap<>();
    private final Map<Integer, Cached> tmdbCache = new HashMap<>();
    private final Map<String, String> genres = new HashMap<>();
    private List<Slide> ytCache;
    private long ytAt;
    private final boolean enabled;
    private String wantedPkg;
    private int token;
    private List<Slide> slides;
    private int slideIndex;
    private final Runnable pending = this::load;
    private final Runnable rotate = this::nextSlide;

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Extras.installWatchdog(context);
        enabled = !Extras.safeMode(context) && Extras.bool(context, "backdrops", true);
        dp = getResources().getDisplayMetrics().density;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(context);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            iv.setFocusable(false);
            addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            layers[i] = iv;
        }
        scrimNight = scrim(0xFF000000, 0xA6000000);
        scrimDay = scrim(Theme.PAPER, 0xB3F5F5F7);
        scrim = new View(context);
        scrim.setBackground(scrimNight);
        scrim.setAlpha(0f);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        Typeface font = null;
        try {
            font = getResources().getFont(R.font.outfit);
        } catch (Exception ignored) {
            // System font then.
        }
        caption = new LinearLayout(context);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setAlpha(0f);
        kicker = label(context, 12, font, "'wght' 500");
        kicker.setLetterSpacing(0.22f);
        kicker.setAllCaps(true);
        title = label(context, 44, font, "'wght' 300");
        title.setLetterSpacing(-0.02f);
        meta = label(context, 15, font, "'wght' 400");
        caption.addView(kicker);
        caption.addView(title);
        caption.addView(meta);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(68 * dp);
        addView(caption, lp);
    }

    private static LayerDrawable scrim(int solid, int side) {
        int c = solid & 0x00FFFFFF;
        int s = side & 0x00FFFFFF;
        int sa = side >>> 24;
        return new LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{
                        0xFF000000 | c, 0xE0000000 | c, 0x8C000000 | c, 0x26000000 | c, c, c}),
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                        (sa << 24) | s, ((sa / 2) << 24) | s, s, s}),
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                        0x40000000 | c, c, c, c}),
        });
    }

    private TextView label(Context context, float sp, Typeface font, String weight) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (font != null) t.setTypeface(font);
        t.setFontVariationSettings(weight);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setPadding(0, Math.round(3 * dp), 0, Math.round(3 * dp));
        return t;
    }

    // ---- main thread -----------------------------------------------------------------------------------

    /** The user is on this app's tile. Loads its artwork after a short dwell. */
    public void show(String pkg) {
        if (!enabled || pkg == null) {
            hide();
            return;
        }
        if (pkg.equals(wantedPkg)) return;
        wantedPkg = pkg;
        main.removeCallbacks(pending);
        main.removeCallbacks(rotate);
        main.postDelayed(pending, DWELL_MS);
    }

    /** Focus left the dock: fade the artwork out. */
    public void hide() {
        wantedPkg = null;
        slides = null;
        token++;
        main.removeCallbacks(pending);
        main.removeCallbacks(rotate);
        fadeOut();
    }

    private void load() {
        final String pkg = wantedPkg;
        if (pkg == null) return;
        final int mine = ++token;
        io.execute(() -> {
            List<Slide> found = null;
            Bitmap bitmap = null;
            try {
                found = slides(pkg);
                if (!found.isEmpty()) bitmap = bitmap(found.get(0));
            } catch (Throwable ignored) {
                // Artwork is decoration: any failure just leaves the glow.
            }
            final List<Slide> s = found;
            final Bitmap b = bitmap;
            main.post(() -> {
                if (mine != token || !pkg.equals(wantedPkg)) return;
                if (b == null) {
                    fadeOut();
                    return;
                }
                slides = s;
                slideIndex = 0;
                present(b, s.get(0));
                if (s.size() > 1) main.postDelayed(rotate, ROTATE_MS);
            });
        });
    }

    private void nextSlide() {
        final List<Slide> s = slides;
        final String pkg = wantedPkg;
        if (s == null || pkg == null || s.size() < 2) return;
        final int mine = token;
        final int index = (slideIndex + 1) % s.size();
        io.execute(() -> {
            Bitmap b = null;
            try {
                b = bitmap(s.get(index));
            } catch (Throwable ignored) {
                // Skip this one.
            }
            final Bitmap result = b;
            main.post(() -> {
                if (mine != token || !pkg.equals(wantedPkg)) return;
                slideIndex = index;
                if (result != null) present(result, s.get(index));
                main.postDelayed(rotate, ROTATE_MS);
            });
        });
    }

    private void present(Bitmap bitmap, Slide slide) {
        scrim.setBackground(Theme.day ? scrimDay : scrimNight);
        int back = 1 - front;
        ImageView next = layers[back];
        ImageView old = layers[front];
        next.animate().cancel();
        if (drifts[back] != null) drifts[back].cancel();
        next.setImageBitmap(bitmap);
        next.setAlpha(0f);
        next.setScaleX(1.02f);
        next.setScaleY(1.02f);
        next.animate().alpha(1f).setDuration(FADE_MS).start();
        drifts[back] = ObjectAnimator.ofPropertyValuesHolder(next,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.02f, 1.09f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.02f, 1.09f));
        drifts[back].setDuration(DRIFT_MS);
        drifts[back].start();
        old.animate().cancel();
        old.animate().alpha(0f).setDuration(FADE_MS).start();
        scrim.animate().cancel();
        scrim.animate().alpha(1f).setDuration(FADE_MS).start();
        front = back;
        showCaption(slide);
    }

    private void showCaption(Slide slide) {
        View h = hero();
        if (slide.title == null) {
            caption.animate().cancel();
            caption.animate().alpha(0f).setDuration(200).start();
            if (h != null) h.setVisibility(VISIBLE);
            return;
        }
        if (h != null) h.setVisibility(INVISIBLE);
        int ink = Theme.day ? Theme.INK : 0xFFFFFFFF;
        caption.animate().cancel();
        caption.animate().alpha(0f).setDuration(caption.getAlpha() > 0 ? 160 : 0).withEndAction(() -> {
            kicker.setText(slide.kicker);
            kicker.setTextColor((ink & 0x00FFFFFF) | 0xB3000000);
            title.setText(slide.title);
            title.setTextColor(ink);
            meta.setText(slide.meta);
            meta.setTextColor((ink & 0x00FFFFFF) | 0xA6000000);
            int maxW = Math.max(1, Math.round(getWidth() * 0.6f));
            title.setMaxWidth(maxW);
            caption.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            float bottom = (h != null && h.getHeight() > 0)
                    ? h.getY() + h.getHeight() : getHeight() * 0.62f;
            caption.setY(bottom - caption.getMeasuredHeight());
            caption.setTranslationX(12 * dp);
            caption.animate().alpha(1f).translationX(0f).setDuration(320).start();
        }).start();
    }

    private void fadeOut() {
        for (ImageView iv : layers) {
            iv.animate().cancel();
            iv.animate().alpha(0f).setDuration(400).start();
        }
        scrim.animate().cancel();
        scrim.animate().alpha(0f).setDuration(400).start();
        caption.animate().cancel();
        caption.animate().alpha(0f).setDuration(200).start();
        View h = hero();
        if (h != null) h.setVisibility(VISIBLE);
    }

    /** The app name MainActivity shows above the dock; hidden while a title is on screen. */
    private View hero() {
        if (hero == null && getParent() instanceof View) hero = ((View) getParent()).findViewById(R.id.heroName);
        return hero;
    }

    // ---- background thread -----------------------------------------------------------------------------

    private List<Slide> slides(String pkg) {
        List<Slide> out = new ArrayList<>();
        File dir = getContext().getExternalFilesDir(null);
        File own = dir != null ? new File(new File(dir, "backdrops"), pkg + ".jpg") : null;
        if (own != null && own.isFile()) {
            out.add(new Slide("file:" + own.getPath(), Uri.fromFile(own), null, null, null));
            return out;
        }

        // YouTube: the latest videos of your channels (config "youtube_channels"), no key needed.
        if (pkg.equals("com.google.android.youtube.tv")) {
            List<Slide> yt = youtube();
            if (!yt.isEmpty()) return yt;
        }

        Integer provider = provider(pkg);
        String key = Extras.str(getContext(), "tmdb_key", "");
        if (provider != null && !key.isEmpty()) {
            List<Highlight> items = highlights(provider, key);
            if (!items.isEmpty()) {
                String app = appLabel(pkg);
                String k = (Extras.french() ? "À la une sur " : "Trending on ") + app;
                for (Highlight hl : items) {
                    out.add(new Slide(hl.image, Uri.parse(hl.image), k, hl.title, hl.meta));
                }
                return out;
            }
        }

        List<String> uris = artwork(pkg);
        if (!uris.isEmpty()) {
            Integer last = rotation.get(pkg);
            int n = last == null ? 0 : last + 1;
            rotation.put(pkg, n);
            String uri = uris.get(n % uris.size());
            out.add(new Slide(uri, Uri.parse(uri), null, null, null));
        }
        return out;
    }

    /**
     * Latest long-form videos of the channels in config.json "youtube_channels" (UC... ids), newest
     * first, from YouTube's public feeds. Shorts are skipped: their thumbnails are portrait.
     */
    private List<Slide> youtube() {
        JSONArray ids = Extras.config(getContext()).optJSONArray("youtube_channels");
        if (ids == null || ids.length() == 0) return Collections.emptyList();
        long now = System.currentTimeMillis();
        if (ytCache != null && now - ytAt < YT_TTL_MS) return ytCache;
        List<String[]> videos = new ArrayList<>();   // published, id, title, channel
        for (int i = 0; i < Math.min(ids.length(), 20); i++) {
            String id = ids.optString(i, "");
            if (!id.startsWith("UC")) continue;
            try {
                String body = Extras.get("https://www.youtube.com/feeds/videos.xml?channel_id=" + id, null);
                if (body == null) continue;
                String[] entries = body.split("<entry>");
                int kept = 0;
                for (int e = 1; e < entries.length && kept < 3; e++) {
                    String entry = entries[e];
                    if (entry.contains("/shorts/")) continue;
                    String vid = between(entry, "<yt:videoId>", "</yt:videoId>");
                    String title = between(entry, "<title>", "</title>");
                    String published = between(entry, "<published>", "</published>");
                    String author = between(between(entry, "<author>", "</author>"), "<name>", "</name>");
                    if (vid.isEmpty() || title.isEmpty()) continue;
                    videos.add(new String[]{published, vid, unescape(title), unescape(author)});
                    kept++;
                }
            } catch (Exception ignored) {
                // Skip this channel.
            }
        }
        Collections.sort(videos, (a, b) -> b[0].compareTo(a[0]));
        List<Slide> out = new ArrayList<>();
        for (int i = 0; i < Math.min(videos.size(), MAX_HIGHLIGHTS); i++) {
            String[] v = videos.get(i);
            out.add(new Slide("yt:" + v[1], Uri.parse("https://i.ytimg.com/vi/" + v[1] + "/maxresdefault.jpg"),
                    v[3], v[2], "YouTube \u00b7 " + ago(v[0])));
        }
        if (!out.isEmpty() || ytCache == null) {
            ytCache = out;
            ytAt = now;
        }
        return out.isEmpty() && ytCache != null ? ytCache : out;
    }

    private static String between(String s, String start, String end) {
        int a = s.indexOf(start);
        if (a < 0) return "";
        a += start.length();
        int b = s.indexOf(end, a);
        return b < 0 ? "" : s.substring(a, b).trim();
    }

    private static String unescape(String s) {
        return Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
    }

    /** "il y a 3 h", "hier", "il y a 4 jours" from an ISO date. */
    private static String ago(String iso) {
        boolean fr = Extras.french();
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(iso);
            long min = Math.max(0, (System.currentTimeMillis() - d.getTime()) / 60_000L);
            if (min < 60) return fr ? "il y a " + Math.max(1, min) + " min" : min + " min ago";
            long h = min / 60;
            if (h < 24) return fr ? "il y a " + h + " h" : h + " h ago";
            long days = h / 24;
            if (days == 1) return fr ? "hier" : "yesterday";
            if (days < 30) return fr ? "il y a " + days + " jours" : days + " days ago";
            return fr ? "il y a " + (days / 30) + " mois" : (days / 30) + " months ago";
        } catch (Exception e) {
            return fr ? "nouvelle vid\u00e9o" : "new video";
        }
    }

    private Integer provider(String pkg) {
        JSONObject custom = Extras.config(getContext()).optJSONObject("tmdb_providers");
        if (custom != null && custom.has(pkg)) {
            int id = custom.optInt(pkg, 0);
            return id > 0 ? id : null;
        }
        return PROVIDERS.get(pkg);
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Popular titles of the last year on this service, series and films alternating. */
    private List<Highlight> highlights(int provider, String key) {
        Cached hit = tmdbCache.get(provider);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at < TMDB_TTL_MS) return hit.items;
        List<Highlight> tv = discover("tv", provider, key);
        List<Highlight> movie = discover("movie", provider, key);
        List<Highlight> mixed = new ArrayList<>();
        for (int i = 0; mixed.size() < MAX_HIGHLIGHTS && (i < tv.size() || i < movie.size()); i++) {
            if (i < tv.size()) mixed.add(tv.get(i));
            if (i < movie.size() && mixed.size() < MAX_HIGHLIGHTS) mixed.add(movie.get(i));
        }
        if (!mixed.isEmpty() || hit == null) tmdbCache.put(provider, new Cached(now, mixed));
        return mixed.isEmpty() && hit != null ? hit.items : mixed;
    }

    private List<Highlight> discover(String type, int provider, String key) {
        List<Highlight> out = new ArrayList<>();
        try {
            boolean tv = type.equals("tv");
            boolean fr = Extras.french();
            String lang = fr ? "fr-FR" : "en-US";
            String region = Extras.str(getContext(), "region", "FR");
            String since = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                    .format(new Date(System.currentTimeMillis() - 365L * 86_400_000L));
            loadGenres(type, lang, key);
            String url = "https://api.themoviedb.org/3/discover/" + type
                    + "?language=" + lang + "&watch_region=" + region
                    + "&with_watch_providers=" + provider
                    + "&with_watch_monetization_types=flatrate&include_adult=false&sort_by=popularity.desc"
                    + "&" + (tv ? "first_air_date.gte" : "primary_release_date.gte") + "=" + since
                    + auth(key);
            String body = Extras.get(url, bearer(key));
            if (body == null) return out;
            JSONArray results = new JSONObject(body).optJSONArray("results");
            if (results == null) return out;
            for (int i = 0; i < results.length() && out.size() < MAX_HIGHLIGHTS; i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                String backdrop = r.optString("backdrop_path", "");
                String name = r.optString(tv ? "name" : "title", "");
                if (backdrop.isEmpty() || "null".equals(backdrop) || name.isEmpty()) continue;
                String date = r.optString(tv ? "first_air_date" : "release_date", "");
                StringBuilder m = new StringBuilder(tv ? (fr ? "Série" : "Series") : (fr ? "Film" : "Film"));
                JSONArray g = r.optJSONArray("genre_ids");
                if (g != null && g.length() > 0) {
                    String gn = genres.get(type + ":" + g.optInt(0));
                    if (gn != null) m.append(" · ").append(gn);
                }
                if (date.length() >= 4) m.append(" · ").append(date, 0, 4);
                out.add(new Highlight("https://image.tmdb.org/t/p/w1280" + backdrop, name, m.toString()));
            }
        } catch (Exception ignored) {
            // No highlights then; the next source takes over.
        }
        return out;
    }

    private void loadGenres(String type, String lang, String key) {
        if (genres.containsKey(type + ":loaded")) return;
        try {
            String body = Extras.get("https://api.themoviedb.org/3/genre/" + type + "/list?language=" + lang
                    + auth(key), bearer(key));
            if (body == null) return;
            JSONArray list = new JSONObject(body).optJSONArray("genres");
            if (list == null) return;
            for (int i = 0; i < list.length(); i++) {
                JSONObject g = list.optJSONObject(i);
                if (g != null) genres.put(type + ":" + g.optInt("id"), g.optString("name"));
            }
            genres.put(type + ":loaded", "1");
        } catch (Exception ignored) {
            // Titles show without a genre.
        }
    }

    /** A v4 read token (starts with "eyJ") goes in a header; a v3 key goes in the URL. */
    private static String bearer(String key) {
        return key.startsWith("eyJ") ? key : null;
    }

    private static String auth(String key) {
        return key.startsWith("eyJ") ? "" : "&api_key=" + Uri.encode(key);
    }

    /** Landscape artwork the app published, "Watch next" first; portrait only if there is nothing else. */
    private List<String> artwork(String pkg) {
        Set<String> wide = new LinkedHashSet<>();
        Set<String> other = new LinkedHashSet<>();
        for (Uri table : Arrays.asList(WATCH_NEXT, PREVIEW)) {
            try (Cursor c = getContext().getContentResolver().query(
                    table, COLUMNS, "package_name=?", new String[]{pkg}, null)) {
                if (c == null) continue;
                while (c.moveToNext() && wide.size() < MAX_URIS) {
                    String poster = c.isNull(0) ? null : c.getString(0);
                    Integer posterRatio = c.isNull(1) ? null : c.getInt(1);
                    String thumb = c.isNull(2) ? null : c.getString(2);
                    Integer thumbRatio = c.isNull(3) ? null : c.getInt(3);
                    if (thumb != null && thumbRatio != null && landscape(thumbRatio)) wide.add(thumb);
                    else if (poster != null && posterRatio != null && landscape(posterRatio)) wide.add(poster);
                    else if (thumb != null) other.add(thumb);
                    else if (poster != null) other.add(poster);
                }
            } catch (Exception ignored) {
                // No permission, or the table doesn't exist on this TV: try the next one.
            }
        }
        List<String> list = new ArrayList<>(wide.isEmpty() ? other : wide);
        return Collections.unmodifiableList(list);
    }

    private Bitmap bitmap(Slide slide) throws Exception {
        Bitmap hit = memory.get(slide.key);
        if (hit != null) return hit;
        File cacheDir = new File(getContext().getCacheDir(), "backdrops");
        File disk = new File(cacheDir, sha1(slide.key) + ".jpg");
        byte[] bytes;
        if (disk.isFile()) {
            bytes = Files.readAllBytes(disk.toPath());
        } else {
            bytes = download(slide.image);
            if (bytes == null && slide.key.startsWith("yt:")) {
                // Not every video has a full-HD thumbnail: try the 720p one, then the standard one.
                String id = slide.key.substring(3);
                bytes = download(Uri.parse("https://i.ytimg.com/vi/" + id + "/hq720.jpg"));
                if (bytes == null) bytes = download(Uri.parse("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"));
            }
            if (bytes == null) return null;
        }
        Bitmap bitmap = decode(bytes);
        if (bitmap == null) return null;
        if (!disk.isFile()) save(cacheDir, disk, bitmap);
        memory.put(slide.key, bitmap);
        return bitmap;
    }

    private byte[] download(Uri uri) throws Exception {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        InputStream in;
        HttpURLConnection http = null;
        if (scheme.equals("http") || scheme.equals("https")) {
            http = (HttpURLConnection) new URL(uri.toString()).openConnection();
            http.setConnectTimeout(4000);
            http.setReadTimeout(6000);
            http.setInstanceFollowRedirects(true);
            if (http.getResponseCode() != 200) {
                http.disconnect();
                return null;
            }
            in = http.getInputStream();
        } else {
            in = getContext().getContentResolver().openInputStream(uri);
        }
        if (in == null) return null;
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = s.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > MAX_BYTES) return null;
            }
            return out.toByteArray();
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= TARGET_W) opts.inSampleSize *= 2;
        Bitmap full = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (full == null || full.getWidth() <= TARGET_W) return full;
        int h = Math.round(full.getHeight() * (TARGET_W / (float) full.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(full, TARGET_W, h, true);
        if (scaled != full) full.recycle();
        return scaled;
    }

    private static void save(File cacheDir, File disk, Bitmap bitmap) {
        try {
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return;
            File[] files = cacheDir.listFiles();
            if (files != null && files.length > 80) {
                Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < files.length - 60; i++) files[i].delete();
            }
            try (FileOutputStream out = new FileOutputStream(disk)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
        } catch (Exception ignored) {
            // The cache is an optimisation only.
        }
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        main.removeCallbacks(pending);
        main.removeCallbacks(rotate);
        for (ObjectAnimator d : drifts) if (d != null) d.cancel();
    }
}
