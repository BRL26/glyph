package dev.brl.glyph;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public final class StripView extends View {

    private static final int OFF = 0xFF2B292F;
    private static final int ON = 0xFFE6E1E5;

    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int leds = 11;
    private float level = 1f;
    private boolean enabled = true;

    public StripView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        glow.setStyle(Paint.Style.FILL);
    }

    public void setValues(int leds, float level, boolean enabled) {
        this.leds = Math.max(1, leds);
        this.level = Math.max(0f, Math.min(1f, level));
        this.enabled = enabled;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = resolveSize((int) dp(26), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float padding = dp(2);
        float usable = getWidth() - padding * 2f;
        float gap = leds > 1 ? Math.min(dp(4), usable * 0.08f / (leds - 1)) : 0f;
        float size = (usable - gap * (leds - 1)) / leds;
        size = Math.min(size, getHeight() - padding * 2f);
        if (size <= 0) {
            return;
        }
        float total = size * leds + gap * (leds - 1);
        float start = (getWidth() - total) / 2f + size / 2f;
        float cy = getHeight() / 2f;
        float intensity = enabled ? level : 0f;

        int color = blend(OFF, ON, intensity);
        for (int i = 0; i < leds; i++) {
            float cx = start + i * (size + gap);
            if (intensity > 0.05f) {
                glow.setColor(Color.argb((int) (30 * intensity), 255, 255, 255));
                glow.setShadowLayer(size * 0.9f, 0, 0, Color.argb((int) (90 * intensity), 255, 255, 255));
                canvas.drawCircle(cx, cy, size * 0.62f, glow);
            }
            dot.setColor(color);
            dot.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
            canvas.drawCircle(cx, cy, size * 0.42f, dot);
        }
    }

    private static int blend(int from, int to, float t) {
        return Color.rgb(
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
