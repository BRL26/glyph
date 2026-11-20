package dev.brl.glyph;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Rear view of the Nothing Phone (3a), matching the stock preview layout:
 * two-camera pill inside the circular camera island, with three separate
 * short arcs outside it.
 *
 * A = right arc (11 LEDs), B = lower-left arc (5), C = upper-left arc (20).
 * Tapping anywhere on a strip, not just its letter, opens that zone.
 */
public final class GlyphPhoneView extends View {

    public interface OnZoneClickListener {
        void onZoneClick(int zone);
    }

    private static final float PHONE_ASPECT = 2.117f;
    private static final int GLYPH_ON = 0xFFFFF7F0;
    private static final int GLYPH_OFF = 0xFF2C2732;

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint islandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cameraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint brandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF phone = new RectF();
    private final RectF camera = new RectF();
    private final Path bodyPath = new Path();
    private final Path[] stripPaths = {new Path(), new Path(), new Path()};
    private final float[][] stripPoints = new float[3][];
    private final float[][] labelPoints = new float[3][];
    private final boolean[] enabled = {true, true, true};
    private final float[] levels = {1f, 1f, 1f};
    private final String[] names = {"A", "B", "C"};

    private boolean clickableZones;
    private OnZoneClickListener listener;
    private int selected = -1;

    public GlyphPhoneView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Nothing Phone (3a) glyph diagram");
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(dp(1.1f));
        outlinePaint.setColor(0xFF3B3542);
        detailPaint.setStyle(Paint.Style.STROKE);
        detailPaint.setStrokeWidth(dp(0.9f));
        detailPaint.setColor(0xFF211E27);
        cameraPaint.setColor(0xFF08070A);
        lensPaint.setColor(0xFF05060A);
        lensRingPaint.setStyle(Paint.Style.STROKE);
        lensRingPaint.setStrokeWidth(dp(2.2f));
        lensRingPaint.setColor(0xFF37303F);
        highlightPaint.setColor(0x554E6FA8);
        stripBasePaint.setStyle(Paint.Style.STROKE);
        stripBasePaint.setStrokeCap(Paint.Cap.ROUND);
        stripBasePaint.setStrokeWidth(dp(7));
        stripBasePaint.setColor(GLYPH_OFF);
        stripPaint.setStyle(Paint.Style.STROKE);
        stripPaint.setStrokeCap(Paint.Cap.ROUND);
        stripPaint.setStrokeWidth(dp(6));
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeWidth(dp(8));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setTextSize(dp(9));
        labelBgPaint.setColor(0xFF332E3B);
        selectPaint.setStyle(Paint.Style.STROKE);
        selectPaint.setStrokeWidth(dp(1.5f));
        selectPaint.setColor(0xFFD0BCFF);
        brandPaint.setColor(0xFF2D2834);
        brandPaint.setTextSize(dp(8));
        brandPaint.setLetterSpacing(0.24f);
        brandPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public void setValues(boolean[] enabled, float[] levels) {
        for (int i = 0; i < 3; i++) {
            this.enabled[i] = enabled[i];
            this.levels[i] = Math.max(0f, Math.min(1f, levels[i]));
        }
        invalidate();
    }

    public void setSelectedZone(int zone) {
        selected = zone;
        invalidate();
    }

    public void setClickableZones(boolean clickable) {
        clickableZones = clickable;
        setClickable(clickable);
    }

    public void setOnZoneClickListener(OnZoneClickListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize((int) dp(320), widthMeasureSpec);
        int height = resolveSize((int) dp(420), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float pad = dp(10);
        float availableWidth = Math.max(1f, getWidth() - pad * 2f);
        float availableHeight = Math.max(1f, getHeight() - pad * 2f);
        float phoneWidth = Math.min(availableWidth, availableHeight / PHONE_ASPECT);
        float phoneHeight = phoneWidth * PHONE_ASPECT;
        float left = (getWidth() - phoneWidth) / 2f;
        float top = (getHeight() - phoneHeight) / 2f;
        phone.set(left, top, left + phoneWidth, top + phoneHeight);

        drawBody(canvas);
        drawCameraIsland(canvas);
        buildStripPaths();
        drawStrip(canvas, 0);
        drawStrip(canvas, 1);
        drawStrip(canvas, 2);
        drawBrand(canvas);
    }

    private void drawBody(Canvas canvas) {
        float radius = phone.width() * 0.132f;
        bodyPath.reset();
        bodyPath.addRoundRect(phone, radius, radius, Path.Direction.CW);

        bodyPaint.setShader(new LinearGradient(phone.left, phone.top, phone.right, phone.bottom,
                0xFF19161E, 0xFF0C0A0F, Shader.TileMode.CLAMP));
        bodyPaint.setShadowLayer(dp(16), 0, dp(8), 0x77000000);
        canvas.drawPath(bodyPath, bodyPaint);
        bodyPaint.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        canvas.drawPath(bodyPath, outlinePaint);

        drawSideButtons(canvas);

        canvas.save();
        canvas.clipPath(bodyPath);
        drawRearTexture(canvas);
        canvas.restore();
    }

    private void drawSideButtons(Canvas canvas) {
        Paint button = new Paint(Paint.ANTI_ALIAS_FLAG);
        button.setColor(0xFF16131A);
        float w = phone.width();
        float right = phone.right;
        float left = phone.left;
        float y = phone.top + phone.height() * 0.31f;
        canvas.drawRoundRect(new RectF(right - dp(0.7f), y, right + dp(2.2f), y + w * 0.12f),
                dp(2), dp(2), button);
        canvas.drawRoundRect(new RectF(right - dp(0.7f), y + w * 0.16f,
                right + dp(2.2f), y + w * 0.25f), dp(2), dp(2), button);
        float vy = phone.top + phone.height() * 0.33f;
        canvas.drawRoundRect(new RectF(left - dp(2.2f), vy, left + dp(0.7f), vy + w * 0.17f),
                dp(2), dp(2), button);
    }

    private void drawRearTexture(Canvas canvas) {
        float w = phone.width();
        float h = phone.height();
        float panelTop = phone.top + h * 0.40f;

        detailPaint.setColor(0xFF201D25);
        detailPaint.setStrokeWidth(dp(0.7f));
        Path traces = new Path();
        traces.moveTo(phone.left + w * 0.08f, panelTop + h * 0.02f);
        traces.lineTo(phone.left + w * 0.08f, phone.bottom - h * 0.16f);
        traces.lineTo(phone.left + w * 0.22f, phone.bottom - h * 0.08f);
        traces.lineTo(phone.left + w * 0.66f, phone.bottom - h * 0.08f);
        traces.lineTo(phone.left + w * 0.78f, phone.bottom - h * 0.16f);
        traces.lineTo(phone.left + w * 0.78f, panelTop + h * 0.16f);
        traces.moveTo(phone.left + w * 0.50f, panelTop + h * 0.06f);
        traces.lineTo(phone.left + w * 0.50f, phone.bottom - h * 0.18f);
        traces.lineTo(phone.left + w * 0.66f, phone.bottom - h * 0.10f);
        canvas.drawPath(traces, detailPaint);

        detailPaint.setColor(0xFF2A2630);
        detailPaint.setStrokeWidth(dp(1));
        canvas.drawLine(phone.left + w * 0.10f, phone.bottom - h * 0.24f,
                phone.right - w * 0.10f, phone.bottom - h * 0.24f, detailPaint);
        for (int i = 0; i < 4; i++) {
            float yy = phone.bottom - h * (0.20f + i * 0.036f);
            canvas.drawLine(phone.right - w * 0.20f, yy, phone.right - w * 0.10f, yy,
                    detailPaint);
        }

        Paint screw = new Paint(Paint.ANTI_ALIAS_FLAG);
        screw.setStyle(Paint.Style.STROKE);
        screw.setStrokeWidth(dp(0.7f));
        screw.setColor(0xFF28242D);
        float r = w * 0.016f;
        canvas.drawCircle(phone.left + w * 0.09f, phone.top + h * 0.44f, r, screw);
        canvas.drawCircle(phone.left + w * 0.09f, phone.bottom - h * 0.065f, r, screw);
        canvas.drawCircle(phone.right - w * 0.09f, phone.bottom - h * 0.065f, r, screw);
        canvas.drawCircle(phone.right - w * 0.09f, phone.top + h * 0.44f, r, screw);
    }

    private void drawCameraIsland(Canvas canvas) {
        float w = phone.width();
        float cx = phone.left + w * 0.50f;
        float cy = phone.top + phone.height() * 0.214f;
        float radius = w * 0.385f;

        islandPaint.setShader(new RadialGradient(cx, cy - radius * 0.25f, radius * 1.15f,
                0xFF1A171F, 0xFF0B090D, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, islandPaint);
        islandPaint.setShader(null);
        outlinePaint.setColor(0xFF2F2A36);
        canvas.drawCircle(cx, cy, radius, outlinePaint);

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFF25212A);
        ring.setStrokeWidth(dp(0.8f));
        canvas.drawCircle(cx, cy, radius * 0.91f, ring);
        canvas.drawCircle(cx, cy, radius * 0.82f, ring);

        camera.set(phone.left + w * 0.204f, phone.top + phone.height() * 0.153f,
                phone.left + w * 0.796f, phone.top + phone.height() * 0.269f);
        canvas.drawRoundRect(camera, w * 0.055f, w * 0.055f, cameraPaint);
        Paint cameraRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        cameraRing.setStyle(Paint.Style.STROKE);
        cameraRing.setStrokeWidth(dp(1));
        cameraRing.setColor(0xFF3A3442);
        canvas.drawRoundRect(camera, w * 0.055f, w * 0.055f, cameraRing);

        float lensY = camera.centerY();
        drawLens(canvas, phone.left + w * 0.335f, lensY, w * 0.092f);
        drawLens(canvas, phone.left + w * 0.665f, lensY, w * 0.092f);

        float flashY = phone.top + phone.height() * 0.114f;
        canvas.drawCircle(cx, flashY, w * 0.026f, cameraPaint);
        canvas.drawCircle(cx, flashY, w * 0.017f, highlightPaint);
        canvas.drawCircle(cx, flashY, w * 0.026f, cameraRing);

        Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
        red.setColor(0xFF8E2B24);
        canvas.drawCircle(phone.left + w * 0.790f, phone.top + phone.height() * 0.290f,
                w * 0.011f, red);
    }

    private void drawLens(Canvas canvas, float cx, float cy, float radius) {
        canvas.drawCircle(cx, cy, radius, lensPaint);
        canvas.drawCircle(cx, cy, radius * 0.76f, lensRingPaint);
        canvas.drawCircle(cx, cy, radius * 0.42f, lensPaint);
        canvas.drawCircle(cx - radius * 0.25f, cy - radius * 0.28f, radius * 0.10f,
                highlightPaint);
    }

    private void buildStripPaths() {
        float w = phone.width();
        float cx = phone.left + w * 0.50f;
        float cy = phone.top + phone.height() * 0.214f;
        float radius = w * 0.440f;
        RectF arc = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);

        // Stock Phone (3a) preview geometry: three disjoint arcs around the
        // camera island, sitting outside the island ring and reaching toward
        // the phone edges. The previous version wrapped C 165 degrees over the
        // top, so C and A overlapped and the diagram did not match the phone.
        stripPaths[0].reset();
        stripPaths[0].addArc(arc, 330f, 55f);
        stripPaths[1].reset();
        stripPaths[1].addArc(arc, 126f, 22f);
        stripPaths[2].reset();
        stripPaths[2].addArc(arc, 162f, 82f);

        for (int i = 0; i < 3; i++) {
            stripPoints[i] = sample(stripPaths[i], 16);
        }


    }

    private float[] sample(Path path, int minimum) {
        PathMeasure measure = new PathMeasure(path, false);
        float length = measure.getLength();
        int count = Math.max(minimum, (int) (length / dp(4)) + 1);
        float[] points = new float[count * 2];
        float[] position = new float[2];
        for (int i = 0; i < count; i++) {
            measure.getPosTan(length * i / (count - 1f), position, null);
            points[i * 2] = position[0];
            points[i * 2 + 1] = position[1];
        }
        return points;
    }

    private void drawStrip(Canvas canvas, int zone) {
        float intensity = enabled[zone] ? levels[zone] : 0f;
        Path path = stripPaths[zone];

        stripBasePaint.setColor(GLYPH_OFF);
        canvas.drawPath(path, stripBasePaint);

        if (intensity > 0.01f) {
            glowPaint.setColor(Color.argb((int) (90 * intensity), 255, 247, 240));
            glowPaint.setShadowLayer(dp(10), 0, 0,
                    Color.argb((int) (170 * intensity), 255, 236, 214));
            canvas.drawPath(path, glowPaint);
            glowPaint.setShadowLayer(0, 0, 0, Color.TRANSPARENT);

            stripPaint.setColor(blend(GLYPH_OFF, GLYPH_ON, intensity));
            canvas.drawPath(path, stripPaint);

            Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
            core.setStyle(Paint.Style.STROKE);
            core.setStrokeCap(Paint.Cap.ROUND);
            core.setStrokeWidth(dp(2.2f));
            core.setColor(Color.argb((int) (210 * intensity), 255, 255, 255));
            canvas.drawPath(path, core);
        }

        if (clickableZones && zone == selected) {
            selectPaint.setAlpha(190);
            canvas.drawPath(path, selectPaint);
        }
    }

    private void drawBrand(Canvas canvas) {
        float w = phone.width();
        canvas.save();
        canvas.rotate(-90, phone.left + w * 0.105f, phone.top + phone.height() * 0.58f);
        canvas.drawText("NOTHING", phone.left + w * 0.105f, phone.top + phone.height() * 0.585f,
                brandPaint);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!clickableZones || listener == null) {
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            int zone = hitZone(event.getX(), event.getY());
            if (zone >= 0) {
                performClick();
                listener.onZoneClick(zone);
                return true;
            }
        }
        return true;
    }

    private int hitZone(float x, float y) {
        // The strip itself is the target: no labels or dead space to aim at.
        float touchRadius = dp(26);
        for (int i = 0; i < 3; i++) {
            float[] points = stripPoints[i];
            if (points == null) {
                continue;
            }
            for (int p = 0; p + 3 < points.length; p += 2) {
                if (distanceToSegment(x, y, points[p], points[p + 1],
                        points[p + 2], points[p + 3]) <= touchRadius) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float distanceToSegment(float px, float py, float x1, float y1,
                                           float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        if (dx == 0f && dy == 0f) {
            return distance(px, py, x1, y1);
        }
        float t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0f, Math.min(1f, t));
        return distance(px, py, x1 + t * dx, y1 + t * dy);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
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
