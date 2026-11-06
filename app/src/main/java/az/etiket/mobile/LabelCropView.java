package az.etiket.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Moves and resizes an axis-aligned box in the photo's pixel coordinates. */
final class LabelCropView extends View {
    static final int MIN_WIDTH = 60;
    static final int MIN_HEIGHT = 40;
    private static final int GREEN = 0xff21694f;
    private static final int LEFT = 1, TOP = 2, RIGHT = 4, BOTTOM = 8, MOVE = 16;
    private static final int[] CORNERS = {LEFT | TOP, RIGHT | TOP, RIGHT | BOTTOM, LEFT | BOTTOM};
    private static final int[] EDGES = {LEFT, TOP, RIGHT, BOTTOM};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path shade = new Path();
    private final RectF area = new RectF();
    private final RectF window = new RectF();
    private final RectF displayed = new RectF();
    private final RectF visible = new RectF();
    private final RectF frame = new RectF();
    private final RectF dragStart = new RectF();
    private Bitmap photo;
    private boolean square;
    private float scale;
    private int active;
    private int pointer = -1;
    private int focusedCorner;
    private float downX;
    private float downY;

    LabelCropView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void reset(Bitmap photo) {
        this.photo = photo;
        fullWindow();
        area.set(window);
        if (square) fitSquare();
        scale = 0;
        finishDrag();
    }

    boolean isSquare() { return square; }

    void setSquare(boolean square) {
        this.square = square;
        if (square && photo != null) fitSquare();
        finishDrag();
    }

    private void fitSquare() {
        float minimum = Math.min(MIN_WIDTH, Math.min(photo.getWidth(), photo.getHeight()));
        if (Math.min(window.width(), window.height()) < minimum) {
            fullWindow();
            scale = 0;
        }
        float side = clamp(Math.min(area.width(), area.height()), minimum, Math.min(window.width(), window.height()));
        float left = clamp(area.centerX() - side / 2, window.left, window.right - side);
        float top = clamp(area.centerY() - side / 2, window.top, window.bottom - side);
        area.set(left, top, left + side, top + side);
    }

    Rect cropBounds() {
        int left = Math.round(area.left);
        int top = Math.round(area.top);
        int width = Math.min(Math.round(area.width()), photo.getWidth() - left);
        int height = Math.min(Math.round(area.height()), photo.getHeight() - top);
        if (square) width = height = Math.min(width, height);
        return new Rect(left, top, left + width, top + height);
    }

    // Keep the saved corner format so an open editor from an older build can be restored.
    float[] corners() {
        float left = area.left / photo.getWidth();
        float top = area.top / photo.getHeight();
        float right = area.right / photo.getWidth();
        float bottom = area.bottom / photo.getHeight();
        return new float[]{left, top, right, top, right, bottom, left, bottom};
    }

    RectF window() {
        return new RectF(window.left / photo.getWidth(), window.top / photo.getHeight(),
            window.right / photo.getWidth(), window.bottom / photo.getHeight());
    }

    void restore(float[] savedCorners, RectF savedWindow) {
        if (savedCorners != null && savedCorners.length == 8) {
            RectF bounds = new RectF(1, 1, 0, 0);
            for (int i = 0; i < savedCorners.length; i += 2) {
                bounds.left = Math.min(bounds.left, savedCorners[i]);
                bounds.top = Math.min(bounds.top, savedCorners[i + 1]);
                bounds.right = Math.max(bounds.right, savedCorners[i]);
                bounds.bottom = Math.max(bounds.bottom, savedCorners[i + 1]);
            }
            if (normalized(bounds)) area.set(toPixels(bounds));
        }
        if (normalized(savedWindow)) {
            RectF pixels = toPixels(savedWindow);
            if (pixels.contains(area)) window.set(pixels);
        }
        if (square) fitSquare();
        scale = 0;
        finishDrag();
    }

    private static boolean normalized(RectF rect) {
        return rect != null && rect.left >= 0 && rect.top >= 0 && rect.right <= 1 && rect.bottom <= 1
            && rect.width() > 0 && rect.height() > 0;
    }

    private RectF toPixels(RectF rect) {
        return new RectF(rect.left * photo.getWidth(), rect.top * photo.getHeight(),
            rect.right * photo.getWidth(), rect.bottom * photo.getHeight());
    }

    private void fullWindow() { window.set(0, 0, photo.getWidth(), photo.getHeight()); }

    boolean isZoomed() { return window.width() < photo.getWidth() || window.height() < photo.getHeight(); }

    boolean toggleZoom() {
        if (isZoomed()) {
            fullWindow();
        } else {
            float padX = area.width() * .20f;
            float padY = area.height() * .20f;
            window.set(Math.max(0, area.left - padX), Math.max(0, area.top - padY),
                Math.min(photo.getWidth(), area.right + padX), Math.min(photo.getHeight(), area.bottom + padY));
        }
        scale = 0;
        finishDrag();
        return isZoomed();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff13251e);
        if (photo == null) return;
        float padding = dp(14);
        scale = Math.min(Math.max(1, getWidth() - 2 * padding) / window.width(),
            Math.max(1, getHeight() - 2 * padding) / window.height());
        float width = window.width() * scale;
        float height = window.height() * scale;
        float left = (getWidth() - width) / 2;
        float top = (getHeight() - height) / 2;
        visible.set(left, top, left + width, top + height);
        left -= window.left * scale;
        top -= window.top * scale;
        displayed.set(left, top, left + photo.getWidth() * scale, top + photo.getHeight() * scale);
        updateFrame();

        int saved = canvas.save();
        canvas.clipRect(visible);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(photo, null, displayed, paint);
        canvas.restoreToCount(saved);

        shade.rewind();
        shade.setFillType(Path.FillType.EVEN_ODD);
        shade.addRect(visible, Path.Direction.CW);
        shade.addRect(frame, Path.Direction.CW);
        paint.setColor(0xa8000000);
        canvas.drawPath(shade, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.WHITE);
        canvas.drawRect(frame, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int handle : EDGES) drawHandle(canvas, handle, 5);
        for (int handle : CORNERS) drawHandle(canvas, handle, 9);
    }

    private void updateFrame() {
        frame.set(displayed.left + area.left * scale, displayed.top + area.top * scale,
            displayed.left + area.right * scale, displayed.top + area.bottom * scale);
    }

    private void drawHandle(Canvas canvas, int handle, int radius) {
        float x = (handle & LEFT) != 0 ? frame.left : (handle & RIGHT) != 0 ? frame.right : frame.centerX();
        float y = (handle & TOP) != 0 ? frame.top : (handle & BOTTOM) != 0 ? frame.bottom : frame.centerY();
        boolean selected = active == handle || isFocused() && handle == CORNERS[focusedCorner];
        int size = selected ? radius + 2 : radius;
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, dp(size), paint);
        paint.setColor(GREEN);
        canvas.drawCircle(x, y, dp(size - 2), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || scale <= 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                updateFrame();
                active = hitTest(event.getX(), event.getY());
                if (active == 0) return false;
                pointer = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                dragStart.set(area);
                for (int i = 0; i < CORNERS.length; i++) {
                    if (active == CORNERS[i]) focusedCorner = i;
                }
                requestFocus();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                int index = event.findPointerIndex(pointer);
                if (active == 0 || index < 0) return false;
                adjust(dragStart, active, (event.getX(index) - downX) / scale, (event.getY(index) - downY) / scale);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.getActionIndex()) == pointer) finishDrag();
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active == 0) return false;
                finishDrag();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            }
            default -> { return active != 0; }
        }
    }

    private int hitTest(float x, float y) {
        float reachX = Math.min(dp(26), frame.width() / 3);
        float reachY = Math.min(dp(26), frame.height() / 3);
        if (x < frame.left - reachX || x > frame.right + reachX
            || y < frame.top - reachY || y > frame.bottom + reachY) return 0;
        int horizontal = Math.abs(x - frame.left) <= reachX ? LEFT : Math.abs(x - frame.right) <= reachX ? RIGHT : 0;
        int vertical = Math.abs(y - frame.top) <= reachY ? TOP : Math.abs(y - frame.bottom) <= reachY ? BOTTOM : 0;
        return horizontal == 0 && vertical == 0 ? MOVE : horizontal | vertical;
    }

    private void finishDrag() {
        active = 0;
        pointer = -1;
        invalidate();
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isEnabled() || photo == null) return super.onKeyDown(keyCode, event);
        float dx = 0;
        float dy = 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT -> dx = -.01f * photo.getWidth();
            case KeyEvent.KEYCODE_DPAD_RIGHT -> dx = .01f * photo.getWidth();
            case KeyEvent.KEYCODE_DPAD_UP -> dy = -.01f * photo.getHeight();
            case KeyEvent.KEYCODE_DPAD_DOWN -> dy = .01f * photo.getHeight();
            case KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                focusedCorner = (focusedCorner + 1) % CORNERS.length;
                invalidate();
                return true;
            }
            default -> { return super.onKeyDown(keyCode, event); }
        }
        adjust(new RectF(area), CORNERS[focusedCorner], dx, dy);
        return true;
    }

    private void adjust(RectF start, int handle, float dx, float dy) {
        area.set(start);
        if (handle == MOVE) {
            area.offset(clamp(dx, window.left - start.left, window.right - start.right),
                clamp(dy, window.top - start.top, window.bottom - start.bottom));
        } else if (square) {
            resizeSquare(start, direction(handle, LEFT, RIGHT), direction(handle, TOP, BOTTOM), dx, dy);
        } else {
            float minWidth = Math.min(MIN_WIDTH, start.width());
            float minHeight = Math.min(MIN_HEIGHT, start.height());
            if ((handle & LEFT) != 0) area.left = clamp(start.left + dx, window.left, start.right - minWidth);
            if ((handle & RIGHT) != 0) area.right = clamp(start.right + dx, start.left + minWidth, window.right);
            if ((handle & TOP) != 0) area.top = clamp(start.top + dy, window.top, start.bottom - minHeight);
            if ((handle & BOTTOM) != 0) area.bottom = clamp(start.bottom + dy, start.top + minHeight, window.bottom);
        }
        invalidate();
    }

    private void resizeSquare(RectF start, int horizontal, int vertical, float dx, float dy) {
        // A corner keeps its opposite corner fixed. An edge keeps its opposite edge's midpoint fixed.
        float x = anchor(start.left, start.right, horizontal);
        float y = anchor(start.top, start.bottom, vertical);
        float max = Math.min(extent(x, window.left, window.right, horizontal),
            extent(y, window.top, window.bottom, vertical));
        float delta = (horizontal * dx + vertical * dy) / (Math.abs(horizontal) + Math.abs(vertical));
        float side = clamp(start.width() + delta, Math.min(MIN_WIDTH, max), max);
        float left = x - side * (1 - horizontal) / 2;
        float top = y - side * (1 - vertical) / 2;
        area.set(left, top, left + side, top + side);
    }

    private static int direction(int handle, int low, int high) {
        return (handle & low) != 0 ? -1 : (handle & high) != 0 ? 1 : 0;
    }

    private static float anchor(float low, float high, int direction) {
        return direction < 0 ? high : direction > 0 ? low : (low + high) / 2;
    }

    private static float extent(float anchor, float low, float high, int direction) {
        return direction < 0 ? anchor - low : direction > 0 ? high - anchor : 2 * Math.min(anchor - low, high - anchor);
    }

    private float dp(int size) { return size * getResources().getDisplayMetrics().density; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
