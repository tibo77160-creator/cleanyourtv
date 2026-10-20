package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The ambient layer behind the dock, plus a band of the focused app's colour along the left, top and
 * right edges of the screen. TVs that light the wall from the picture's edges (Philips Ambilight in
 * "Follow video") pick that colour up, so the room takes the colour of the app you are about to open.
 *
 * The colour is read from the radial glow MainActivity already sets as this view's background, so
 * MainActivity needs no change: every setColors() on that drawable invalidates this view and redraws
 * the bands. Fades in and out with the ambient glow, since they share this view's alpha.
 *
 * Strength: "ambilight_edges" in config.json, 0 (off) to 100. Default 85.
 */
public class EdgeGlowView extends View {

    private static final float BAND = 0.18f;   // band depth, as a fraction of the screen height

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int edgeAlpha;

    public EdgeGlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        edgeAlpha = readStrength(context);
    }

    private static int readStrength(Context context) {
        int pct = 85;
        try {
            File dir = context.getExternalFilesDir(null);
            File file = dir != null ? new File(dir, "config.json") : null;
            if (file != null && file.isFile()) {
                JSONObject json = new JSONObject(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                if (json.has("ambilight_edges")) {
                    pct = Math.max(0, Math.min(100, json.getInt("ambilight_edges")));
                }
            }
        } catch (Exception ignored) {
            // A missing or broken config keeps the default; it must never break Home.
        }
        return Math.round(pct * 2.55f);
    }

    /** The focused app's colour (RGB only), or -1 before the first one is set. */
    private int appColor() {
        Drawable bg = getBackground();
        if (bg instanceof GradientDrawable) {
            int[] colors = ((GradientDrawable) bg).getColors();
            if (colors != null && colors.length > 0) return colors[0] & 0x00FFFFFF;
        }
        return -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int rgb = appColor();
        if (rgb < 0 || edgeAlpha == 0) return;
        int w = getWidth();
        int h = getHeight();
        float band = h * BAND;
        int solid = (edgeAlpha << 24) | rgb;
        int clear = rgb;
        band(canvas, 0, 0, w, band, 0, 0, 0, band, solid, clear);           // top
        band(canvas, 0, 0, band, h, 0, 0, band, 0, solid, clear);           // left
        band(canvas, w - band, 0, w, h, w, 0, w - band, 0, solid, clear);   // right
    }

    private void band(Canvas canvas, float left, float top, float right, float bottom,
                      float x0, float y0, float x1, float y1, int from, int to) {
        paint.setShader(new LinearGradient(x0, y0, x1, y1, from, to, Shader.TileMode.CLAMP));
        canvas.drawRect(left, top, right, bottom, paint);
    }
}
