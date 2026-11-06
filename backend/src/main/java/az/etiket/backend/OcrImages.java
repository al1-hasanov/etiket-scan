package az.etiket.backend;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/** Image preparation for small print and coloured packaging. All work is size bounded. */
final class OcrImages {
    record Prepared(BufferedImage image, int angle) { }

    private static final int BORDER = 20;

    private OcrImages() { }

    static Prepared prepare(BufferedImage source, boolean enhanceColour) {
        double scale = Math.min(3, 1800d / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = scaled.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        int[] rgb = scaled.getRGB(0, 0, width, height, null, 0, width);
        scaled.flush();

        int channel = enhanceColour ? bestChannel(rgb) : -1;
        BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] levels = pixels(gray);
        for (int i = 0; i < rgb.length; i++) {
            int color = rgb[i];
            int luma = (77 * ((color >> 16) & 255) + 150 * ((color >> 8) & 255) + 29 * (color & 255)) >> 8;
            levels[i] = (byte) (channel < 0 ? luma : (color >> channel) & 255);
        }
        normalizePolarity(levels);
        if (enhanceColour) flattenLighting(levels, width, height);
        stretchContrast(levels);

        int angle = deskewAngle(gray);
        BufferedImage straight = rotate(gray, angle);
        BufferedImage padded = new BufferedImage(straight.getWidth() + BORDER * 2,
            straight.getHeight() + BORDER * 2, BufferedImage.TYPE_BYTE_GRAY);
        var frame = padded.createGraphics();
        frame.setColor(Color.WHITE);
        frame.fillRect(0, 0, padded.getWidth(), padded.getHeight());
        frame.drawImage(straight, BORDER, BORDER, null);
        frame.dispose();
        if (straight != gray) straight.flush();
        gray.flush();
        return new Prepared(padded, angle);
    }

    private static int bestChannel(int[] rgb) {
        int best = 16;
        double bestScore = -1;
        for (int shift : new int[]{16, 8, 0}) {
            int[] histogram = new int[256];
            for (int value : rgb) histogram[(value >> shift) & 255]++;
            int low = percentile(histogram, rgb.length, .06);
            int high = percentile(histogram, rgb.length, .85);
            double score = high - low + .35 * high;
            if (score > bestScore) {
                best = shift;
                bestScore = score;
            }
        }
        return best;
    }

    private static void flattenLighting(byte[] gray, int width, int height) {
        int stride = width + 1;
        int[] integral = new int[stride * (height + 1)];
        for (int y = 0; y < height; y++) {
            int row = 0;
            for (int x = 0; x < width; x++) {
                row += gray[y * width + x] & 255;
                integral[(y + 1) * stride + x + 1] = row + integral[y * stride + x + 1];
            }
        }
        int radius = Math.max(18, Math.min(55, width / 32));
        for (int y = 0; y < height; y++) {
            int top = Math.max(0, y - radius) * stride;
            int bottom = Math.min(height, y + radius + 1) * stride;
            for (int x = 0; x < width; x++) {
                int left = Math.max(0, x - radius);
                int right = Math.min(width, x + radius + 1);
                int sum = integral[bottom + right] - integral[bottom + left]
                    - integral[top + right] + integral[top + left];
                int mean = sum / ((bottom - top) / stride * (right - left));
                int index = y * width + x;
                gray[index] = (byte) Math.clamp((gray[index] & 255) - mean + 230, 0, 255);
            }
        }
    }

    private static void normalizePolarity(byte[] levels) {
        int[] histogram = histogram(levels);
        int threshold = inkThreshold(histogram, levels.length);
        int dark = 0;
        for (int level = 0; level <= threshold; level++) dark += histogram[level];
        // A tight crop is mostly the label background. Keep the letter strokes dark.
        if (dark * 3L < levels.length * 2L) return;
        for (int i = 0; i < levels.length; i++) levels[i] = (byte) (255 - (levels[i] & 255));
    }

    private static void stretchContrast(byte[] gray) {
        int[] histogram = histogram(gray);
        int low = percentile(histogram, gray.length, .01);
        int high = percentile(histogram, gray.length, .99);
        if (high - low < 15) return;
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (byte) Math.clamp(((gray[i] & 255) - low) * 255 / (high - low), 0, 255);
        }
    }

    private static int percentile(int[] histogram, int total, double quantile) {
        int target = Math.max(1, (int) Math.ceil(total * quantile));
        int count = 0;
        for (int i = 0; i < histogram.length; i++) {
            count += histogram[i];
            if (count >= target) return i;
        }
        return 255;
    }

    private static int[] histogram(byte[] levels) {
        int[] histogram = new int[256];
        for (byte level : levels) histogram[level & 255]++;
        return histogram;
    }

    static int inkThreshold(byte[] levels) {
        return inkThreshold(histogram(levels), levels.length);
    }

    private static int inkThreshold(int[] histogram, int pixelCount) {
        long total = 0;
        for (int i = 0; i < 256; i++) total += (long) i * histogram[i];
        long darkSum = 0;
        int darkCount = 0;
        int threshold = 127;
        double best = 0;
        for (int i = 0; i < 255; i++) {
            darkCount += histogram[i];
            darkSum += (long) i * histogram[i];
            int lightCount = pixelCount - darkCount;
            if (darkCount == 0 || lightCount == 0) continue;
            double difference = darkSum / (double) darkCount - (total - darkSum) / (double) lightCount;
            double score = (double) darkCount * lightCount * difference * difference;
            if (score > best) {
                best = score;
                threshold = i;
            }
        }
        return Math.clamp(threshold, 30, 220);
    }

    private static int deskewAngle(BufferedImage gray) {
        byte[] levels = pixels(gray);
        int threshold = inkThreshold(levels);
        int width = gray.getWidth();
        int height = gray.getHeight();
        int step = Math.max(2, Math.max(width, height) / 350);
        int bins = height + width;
        long best = 0;
        long upright = 0;
        int bestAngle = 0;
        for (int angle = -7; angle <= 7; angle++) {
            int[] rows = new int[bins];
            double slope = Math.tan(Math.toRadians(angle));
            for (int x = 0; x < width; x += step) {
                int offset = width / 2 + (int) Math.round(x * slope);
                for (int y = 0; y < height; y += step) {
                    if ((levels[y * width + x] & 255) <= threshold) rows[(y + offset) / step]++;
                }
            }
            long score = 0;
            for (int row : rows) score += (long) row * row;
            if (angle == 0) upright = score;
            if (score > best) {
                best = score;
                bestAngle = angle;
            }
        }
        return best > upright * 1.15 ? bestAngle : 0;
    }

    static BufferedImage rotate(BufferedImage image, int degrees) {
        if (degrees == 0) return image;
        double angle = Math.toRadians(degrees);
        double cos = Math.abs(Math.cos(angle));
        double sin = Math.abs(Math.sin(angle));
        int width = (int) Math.ceil(image.getWidth() * cos + image.getHeight() * sin - 1e-8);
        int height = (int) Math.ceil(image.getWidth() * sin + image.getHeight() * cos - 1e-8);
        BufferedImage result = new BufferedImage(width, height, image.getType());
        var graphics = result.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.translate(width / 2d, height / 2d);
        graphics.rotate(angle);
        graphics.translate(-image.getWidth() / 2d, -image.getHeight() / 2d);
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return result;
    }

    static byte[] pixels(BufferedImage grayscale) {
        return ((DataBufferByte) grayscale.getRaster().getDataBuffer()).getData();
    }
}
