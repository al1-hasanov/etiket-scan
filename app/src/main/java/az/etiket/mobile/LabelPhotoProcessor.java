package az.etiket.mobile;

import android.content.Context;
import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import java.io.*;

final class LabelPhotoProcessor {
    static final int MAX_BYTES = 2 * 1024 * 1024;

    private LabelPhotoProcessor() { }
    static void prepare(Context context, Uri uri, File destination) throws IOException {
        File raw = new File(destination.getParentFile(), "selected-original.tmp");
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(raw)) {
                if (in == null) throw new IOException("PHOTO_INVALID");
                byte[] buffer = new byte[8192]; int n, total = 0;
                while ((n = in.read(buffer)) != -1) {
                    total += n; if (total > 20 * 1024 * 1024) throw new IOException("PHOTO_TOO_LARGE"); out.write(buffer, 0, n);
                }
            }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(raw.getPath(), options);
            if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 20000 || options.outHeight > 20000)
                throw new IOException("PHOTO_INVALID");
            options.inSampleSize = 1;
            while ((long) (options.outWidth / options.inSampleSize) * (options.outHeight / options.inSampleSize) > 6_000_000
                    || Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 4096) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(raw.getPath(), options);
            if (bitmap == null) throw new IOException("PHOTO_INVALID");
            Matrix matrix = new Matrix();
            try {
                int orientation = new ExifInterface(raw.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90);
                    case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180);
                    case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270);
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1, 1);
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1, -1);
                    case ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90); matrix.postScale(-1, 1); }
                    case ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270); matrix.postScale(-1, 1); }
                    default -> { }
                }
            } catch (IOException ignored) { }
            Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (oriented != bitmap) bitmap.recycle();
            float ratio = Math.min(1, 2048f / Math.max(oriented.getWidth(), oriented.getHeight()));
            Bitmap resized = Bitmap.createScaledBitmap(oriented, Math.max(1, Math.round(oriented.getWidth() * ratio)), Math.max(1, Math.round(oriented.getHeight() * ratio)), true);
            if (resized != oriented) oriented.recycle();
            Bitmap clean = Bitmap.createBitmap(resized.getWidth(), resized.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(clean); canvas.drawColor(Color.WHITE); canvas.drawBitmap(resized, 0, 0, null); resized.recycle();
            try {
                writeJpeg(clean, destination, 90, 75, 70);
            } finally { clean.recycle(); }
        } finally { raw.delete(); }
    }

    static void writeJpeg(Bitmap bitmap, File destination, int... qualities) throws IOException {
        File candidate = File.createTempFile("label-", ".tmp", destination.getParentFile());
        try {
            for (int quality : qualities) {
                try (OutputStream out = new FileOutputStream(candidate)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw new IOException("PHOTO_INVALID");
                }
                if (candidate.length() <= MAX_BYTES) {
                    java.nio.file.Files.move(candidate.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
            throw new IOException("PHOTO_TOO_LARGE");
        } finally {
            candidate.delete();
        }
    }

    static Bitmap thumbnail(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 900) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }
}
