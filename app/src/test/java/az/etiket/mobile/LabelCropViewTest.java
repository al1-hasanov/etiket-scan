package az.etiket.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LabelCropViewTest {
    @Test public void resizesEdgesAndMovesTheRectangleWithinThePhoto() {
        LabelCropView view = cropView();
        assertEquals(new Rect(0, 0, 800, 400), view.cropBounds());

        drag(view, 814, 214, 614, 214);
        drag(view, 314, 414, 314, 314);
        assertEquals(new Rect(0, 0, 600, 300), view.cropBounds());

        drag(view, 314, 164, 414, 204);
        assertEquals(new Rect(100, 40, 700, 340), view.cropBounds());
        drag(view, 414, 204, 800, 420);
        assertEquals(new Rect(200, 100, 800, 400), view.cropBounds());

        drag(view, 214, 114, 800, 420);
        assertEquals(new Rect(740, 360, 800, 400), view.cropBounds());
    }

    @Test public void squareUsesPhotoPixelsForCornerAndEdgeDrags() {
        LabelCropView view = cropView();
        view.setSquare(true);
        assertEquals(new Rect(200, 0, 600, 400), view.cropBounds());

        drag(view, 614, 414, 514, 364);
        assertEquals(new Rect(200, 0, 525, 325), view.cropBounds());
        drag(view, 539, 176.5f, 489, 176.5f);
        assertEquals(new Rect(200, 25, 475, 300), view.cropBounds());

        view.setSquare(false);
        drag(view, 489, 176.5f, 589, 176.5f);
        assertEquals(new Rect(200, 25, 575, 300), view.cropBounds());
    }

    private static LabelCropView cropView() {
        LabelCropView view = new LabelCropView(RuntimeEnvironment.getApplication());
        view.reset(Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888));
        // The image is displayed at 1:1 with a 14 px margin at mdpi.
        view.layout(0, 0, 828, 428);
        Bitmap drawing = Bitmap.createBitmap(828, 428, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(drawing));
        drawing.recycle();
        return view;
    }

    private static void drag(LabelCropView view, float fromX, float fromY, float toX, float toY) {
        send(view, MotionEvent.ACTION_DOWN, fromX, fromY);
        send(view, MotionEvent.ACTION_MOVE, toX, toY);
        send(view, MotionEvent.ACTION_UP, toX, toY);
    }

    private static void send(LabelCropView view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        try {
            assertTrue(view.onTouchEvent(event));
        } finally {
            event.recycle();
        }
    }
}
