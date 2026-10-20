package com.gathin.tvhome;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The focus highlight that springs between tiles, behind the grid. It is laid out around the focused tile with
 * {@link #INSET_DP} of room on every side.
 *
 * config.json "focus_style":
 *   "shadow" (default) tvOS look: no frame, just a soft shadow (and at night a faint light) that lifts
 *            the zoomed tile. The view sits behind the grid, so tiles and their names stay on top; the
 *            light sheen on the tile itself is the tile's foreground (AppAdapter).
 *   "glass"  the original translucent glass slab with a white rim.
 */
public class GlassView extends View {

    /** Room around the tile for the shadow; MainActivity sizes the view with it. */
    static final float INSET_DP = 24f;

    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lift = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF tmp = new RectF();
    private final Path tile = new Path();
    private final float density;
    private final boolean glassStyle;
    /** Corner radius of the zoomed tile. */
    private float radius;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 16f * density;
        glassStyle = "glass".equalsIgnoreCase(Extras.str(context, "focus_style", "shadow"));
        if (!glassStyle) {
            // BlurMaskFilter needs a software layer; the view is only tile-sized, so this stays cheap.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            shadow.setMaskFilter(new BlurMaskFilter(16f * density, BlurMaskFilter.Blur.NORMAL));
            lift.setMaskFilter(new BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL));
        }
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
    }

    static int insetPx(Context c) {
        return Math.round(INSET_DP * c.getResources().getDisplayMetrics().density);
    }

    /** Corner radius of the zoomed tile (px). */
    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float in = INSET_DP * density;
        rect.set(in, in, getWidth() - in, getHeight() - in);   // the zoomed tile
        if (rect.width() <= 0 || rect.height() <= 0) return;
        if (glassStyle) {
            drawGlass(canvas);
            return;
        }

        tile.reset();
        tile.addRoundRect(rect, radius, radius, Path.Direction.CW);

        // 1. Shadow and lift, everywhere except on the tile itself.
        canvas.save();
        canvas.clipOutPath(tile);
        // Ambient lift: a very faint light so the tile still reads as raised on pure OLED black.
        if (!Theme.day) {
            lift.setColor(0x1AFFFFFF);
            canvas.drawRoundRect(rect, radius, radius, lift);
        }
        // Drop shadow, pushed down so the tile seems to float above the screen.
        shadow.setColor(Theme.day ? 0x59000000 : 0xB3000000);
        float dy = 10f * density;
        tmp.set(rect.left + 4 * density, rect.top + dy, rect.right - 4 * density, rect.bottom + dy);
        canvas.drawRoundRect(tmp, radius, radius, shadow);
        canvas.restore();
    }

    /** The original style: translucent slab, top specular, white rim and outer glow, 6dp around the tile. */
    private void drawGlass(Canvas canvas) {
        float o6 = 6f * density;
        rect.inset(-o6, -o6);
        float r = radius + o6;
        for (int i = 1; i <= 3; i++) {
            glow.setStrokeWidth(i * 2f * density);
            glow.setColor((0x30 / i) << 24 | 0xFFFFFF);
            float o = i * density;
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, r + o, r + o, glow);
        }
        canvas.drawRoundRect(rect, r, r, fill);
        tile.reset();
        tile.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(tile);
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f, specular);
        canvas.restore();
        canvas.drawRoundRect(rect, r, r, rim);
    }
}
