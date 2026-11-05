package az.etiket.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Four independently movable corners, ordered clockwise from the top left. */
final class LabelCropView extends View {
    private static final int GREEN = 0xff21694f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path selection = new Path();
    private final Path shade = new Path();
    private final RectF displayed = new RectF();
    private final RectF window = new RectF(0, 0, 1, 1);
    private Bitmap photo;
    private float[] corners = initialCorners();
    private int active = -1;
    private int focusedCorner;

    LabelCropView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private static float[] initialCorners() {
        return new float[]{.03f, .03f, .97f, .03f, .97f, .97f, .03f, .97f};
    }

    void reset(Bitmap photo) {
        this.photo = photo;
        corners = initialCorners();
        window.set(0, 0, 1, 1);
        invalidate();
    }

    float[] corners() { return corners.clone(); }
    RectF window() { return new RectF(window); }

    void restore(float[] savedCorners, RectF savedWindow) {
        if (valid(savedCorners)) corners = savedCorners.clone();
        if (savedWindow != null && savedWindow.width() > 0 && savedWindow.height() > 0) window.set(savedWindow);
        invalidate();
    }

    boolean toggleZoom() {
        if (window.width() < 1 || window.height() < 1) {
            window.set(0, 0, 1, 1);
            invalidate();
            return false;
        }
        float left = 1, top = 1, right = 0, bottom = 0;
        for (int i = 0; i < corners.length; i += 2) {
            left = Math.min(left, corners[i]);
            right = Math.max(right, corners[i]);
            top = Math.min(top, corners[i + 1]);
            bottom = Math.max(bottom, corners[i + 1]);
        }
        float padX = (right - left) * .20f;
        float padY = (bottom - top) * .20f;
        window.set(unit(left - padX), unit(top - padY), unit(right + padX), unit(bottom + padY));
        invalidate();
        return window.width() < 1 || window.height() < 1;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff13251e);
        if (photo == null) return;
        float padding = dp(14);
        float scale = Math.min(Math.max(1, getWidth() - 2 * padding) / (photo.getWidth() * window.width()),
            Math.max(1, getHeight() - 2 * padding) / (photo.getHeight() * window.height()));
        float width = photo.getWidth() * scale;
        float height = photo.getHeight() * scale;
        float left = (getWidth() - width * window.width()) / 2 - width * window.left;
        float top = (getHeight() - height * window.height()) / 2 - height * window.top;
        displayed.set(left, top, left + width, top + height);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(photo, null, displayed, paint);

        selection.rewind();
        selection.moveTo(x(0), y(0));
        for (int i = 1; i < 4; i++) selection.lineTo(x(i), y(i));
        selection.close();
        shade.set(selection);
        shade.setFillType(Path.FillType.EVEN_ODD);
        shade.addRect(displayed, Path.Direction.CW);
        paint.setColor(0xa8000000);
        canvas.drawPath(shade, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.WHITE);
        canvas.drawPath(selection, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            boolean selected = i == active || (isFocused() && i == focusedCorner);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x(i), y(i), dp(selected ? 11 : 9), paint);
            paint.setColor(GREEN);
            canvas.drawCircle(x(i), y(i), dp(selected ? 8 : 6), paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || photo == null || displayed.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float distance = dp(42);
                for (int i = 0; i < 4; i++) {
                    float current = (float) Math.hypot(event.getX() - x(i), event.getY() - y(i));
                    if (current < distance) {
                        active = i;
                        distance = current;
                    }
                }
                if (active < 0) return false;
                focusedCorner = active;
                requestFocus();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (active < 0) return false;
                moveCorner(active, (event.getX() - displayed.left) / displayed.width(),
                    (event.getY() - displayed.top) / displayed.height());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (active < 0) return false;
                active = -1;
                invalidate();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default:
                return active >= 0;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int i = focusedCorner * 2;
        float x = corners[i];
        float y = corners[i + 1];
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT -> x -= .01f;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> x += .01f;
            case KeyEvent.KEYCODE_DPAD_UP -> y -= .01f;
            case KeyEvent.KEYCODE_DPAD_DOWN -> y += .01f;
            case KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                focusedCorner = (focusedCorner + 1) % 4;
                invalidate();
                return true;
            }
            default -> { return super.onKeyDown(keyCode, event); }
        }
        moveCorner(focusedCorner, x, y);
        return true;
    }

    private void moveCorner(int index, float x, float y) {
        float[] next = corners.clone();
        next[index * 2] = unit(x);
        next[index * 2 + 1] = unit(y);
        if (valid(next)) {
            corners = next;
            invalidate();
        }
    }

    private float x(int corner) { return displayed.left + displayed.width() * corners[corner * 2]; }
    private float y(int corner) { return displayed.top + displayed.height() * corners[corner * 2 + 1]; }
    private float dp(int size) { return size * getResources().getDisplayMetrics().density; }
    private static float unit(float value) { return Math.max(0, Math.min(1, value)); }

    private static boolean valid(float[] quad) {
        if (quad == null || quad.length != 8) return false;
        for (float coordinate : quad) {
            if (!Float.isFinite(coordinate) || coordinate < 0 || coordinate > 1) return false;
        }
        float area = 0;
        for (int i = 0; i < 4; i++) {
            int a = 2 * i, b = (a + 2) % 8, c = (a + 4) % 8;
            float cross = (quad[b] - quad[a]) * (quad[c + 1] - quad[b + 1])
                - (quad[b + 1] - quad[a + 1]) * (quad[c] - quad[b]);
            if (cross <= .00001f) return false;
            area += quad[a] * quad[b + 1] - quad[a + 1] * quad[b];
        }
        return area > .002f;
    }

    static Bitmap straighten(Bitmap source, float[] quad) {
        if (!valid(quad)) throw new IllegalArgumentException("Invalid crop");
        float[] pixels = quad.clone();
        for (int i = 0; i < pixels.length; i += 2) {
            pixels[i] *= source.getWidth();
            pixels[i + 1] *= source.getHeight();
        }
        double width = Math.max(edge(pixels, 0, 1), edge(pixels, 3, 2));
        double height = Math.max(edge(pixels, 0, 3), edge(pixels, 1, 2));
        if (width < 60 || height < 40) throw new IllegalArgumentException("Crop is too small");
        double scale = Math.min(1, 2048 / Math.max(width, height));
        int outputWidth = (int) Math.round(width * scale);
        int outputHeight = (int) Math.round(height * scale);
        float[] rect = {0, 0, outputWidth, 0, outputWidth, outputHeight, 0, outputHeight};
        Matrix transform = new Matrix();
        if (!transform.setPolyToPoly(pixels, 0, rect, 0, 4)) throw new IllegalArgumentException("Invalid crop");
        Bitmap result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    private static double edge(float[] quad, int first, int second) {
        return Math.hypot(quad[2 * first] - quad[2 * second], quad[2 * first + 1] - quad[2 * second + 1]);
    }
}
