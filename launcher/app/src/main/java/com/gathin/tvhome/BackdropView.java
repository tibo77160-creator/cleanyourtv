package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen artwork behind the dock, taken from the app you are on: what the app publishes to the
 * TV's "Watch next" row first (the show you are watching), then its home-screen channels (what it
 * recommends). Shown with a slow cross-fade and a slow drift, under a black scrim that keeps the
 * dock and the clock readable. On an OLED panel the black stays black; with Philips Ambilight in
 * "Follow video" the wall takes the colours of the artwork.
 *
 * Needs android.permission.READ_TV_LISTINGS (granted over adb at install) to read other apps'
 * listings. Your own picture wins when there is one: <external files>/backdrops/<package>.jpg.
 * Nothing found: the view stays clear and the coloured ambient glow underneath shows instead.
 *
 * config.json: "backdrops": true/false (default true).
 */
public class BackdropView extends FrameLayout {

    private static final long DWELL_MS = 350;      // wait this long on a tile before loading
    private static final long FADE_MS = 700;
    private static final long DRIFT_MS = 24000;
    private static final int TARGET_W = 1280;      // decode width; the TV upscales smoothly
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final int MAX_URIS = 12;

    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final String[] COLUMNS = {
            "poster_art_uri", "poster_art_aspect_ratio", "thumbnail_uri", "thumbnail_aspect_ratio"};
    // TvContract aspect ratios: 0 = 16:9, 1 = 3:2, 2 = 4:3 (landscape); others portrait or square.
    private static boolean landscape(int ratio) {
        return ratio == 0 || ratio == 1 || ratio == 2;
    }

    private final ImageView[] layers = new ImageView[2];
    private final ObjectAnimator[] drifts = new ObjectAnimator[2];
    private int front = 0;
    private final View scrim;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Map<String, Integer> rotation = new HashMap<>();
    private final boolean enabled;
    private String wantedPkg;
    private int token;
    private final Runnable pending = this::load;

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        enabled = readEnabled(context);
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
        scrim = new View(context);
        scrim.setBackground(new LayerDrawable(new Drawable[]{
                // Bottom: black under the dock, fading out by the middle of the screen.
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{
                        0xFF000000, 0xE0000000, 0x8C000000, 0x26000000, 0x00000000, 0x00000000}),
                // Left: shade behind the app name and the clock.
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                        0xA6000000, 0x4D000000, 0x00000000, 0x00000000}),
                // Top: a light touch for the clock and the buttons, keeping the top edge lit for Ambilight.
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                        0x40000000, 0x00000000, 0x00000000, 0x00000000}),
        }));
        scrim.setAlpha(0f);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private static boolean readEnabled(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            File file = dir != null ? new File(dir, "config.json") : null;
            if (file != null && file.isFile()) {
                JSONObject json = new JSONObject(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                if (json.has("backdrops")) return json.getBoolean("backdrops");
            }
        } catch (Exception ignored) {
            // A missing or broken config keeps the default; it must never break Home.
        }
        return true;
    }

    /** The user is on this app's tile. Loads its artwork after a short dwell. */
    public void show(String pkg) {
        if (!enabled || pkg == null) {
            hide();
            return;
        }
        if (pkg.equals(wantedPkg)) return;
        wantedPkg = pkg;
        main.removeCallbacks(pending);
        main.postDelayed(pending, DWELL_MS);
    }

    /** Focus left the dock: fade the artwork out. */
    public void hide() {
        wantedPkg = null;
        token++;
        main.removeCallbacks(pending);
        fadeOut();
    }

    private void load() {
        final String pkg = wantedPkg;
        if (pkg == null) return;
        final int mine = ++token;
        io.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = fetch(pkg);
            } catch (Throwable ignored) {
                // Artwork is decoration: any failure just leaves the glow.
            }
            final Bitmap result = bitmap;
            main.post(() -> {
                if (mine != token || !pkg.equals(wantedPkg)) return;
                if (result == null) fadeOut();
                else present(result);
            });
        });
    }

    private void present(Bitmap bitmap) {
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
    }

    private void fadeOut() {
        for (ImageView iv : layers) {
            iv.animate().cancel();
            iv.animate().alpha(0f).setDuration(400).start();
        }
        scrim.animate().cancel();
        scrim.animate().alpha(0f).setDuration(400).start();
    }

    // ---- background thread -------------------------------------------------------------------------

    private Bitmap fetch(String pkg) throws Exception {
        File dir = getContext().getExternalFilesDir(null);
        File own = dir != null ? new File(new File(dir, "backdrops"), pkg + ".jpg") : null;
        if (own != null && own.isFile()) return cached("file:" + own.getPath(), Uri.fromFile(own));

        List<String> uris = artwork(pkg);
        if (uris.isEmpty()) return null;
        Integer last = rotation.get(pkg);
        int n = last == null ? 0 : last + 1;
        rotation.put(pkg, n);
        String uri = uris.get(n % uris.size());
        return cached(uri, Uri.parse(uri));
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
        return new ArrayList<>(wide.isEmpty() ? other : wide);
    }

    private Bitmap cached(String key, Uri uri) throws Exception {
        Bitmap hit = memory.get(key);
        if (hit != null) return hit;
        File cacheDir = new File(getContext().getCacheDir(), "backdrops");
        File disk = new File(cacheDir, sha1(key) + ".jpg");
        byte[] bytes;
        if (disk.isFile()) {
            bytes = Files.readAllBytes(disk.toPath());
        } else {
            bytes = download(uri);
            if (bytes == null) return null;
        }
        Bitmap bitmap = decode(bytes);
        if (bitmap == null) return null;
        if (!disk.isFile()) save(cacheDir, disk, bitmap);
        memory.put(key, bitmap);
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
            if (files != null && files.length > 60) {
                Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < files.length - 40; i++) files[i].delete();
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
        for (ObjectAnimator d : drifts) if (d != null) d.cancel();
    }
}
