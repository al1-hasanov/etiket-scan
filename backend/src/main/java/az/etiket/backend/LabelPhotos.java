package az.etiket.backend;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Decode only bounded raster images and re-encode without EXIF/location or other source metadata. */
@Component
final class LabelPhotos {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    record Stored(byte[] bytes, int width, int height, String sha256) { }

    Stored sanitize(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new SubmissionException(400, "PHOTOS_REQUIRED");
        if (file.getSize() > MAX_BYTES) throw new SubmissionException(413, "PHOTO_TOO_LARGE");
        try (InputStream raw = file.getInputStream(); ImageInputStream input = ImageIO.createImageInputStream(raw)) {
            if (input == null) throw new SubmissionException(400, "PHOTO_INVALID");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new SubmissionException(400, "PHOTO_INVALID");
            ImageReader reader = readers.next();
            BufferedImage original;
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("jpeg", "jpg", "png").contains(format)) throw new SubmissionException(400, "PHOTO_INVALID");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 10000 || height > 10000 || (long) width * height > 20_000_000)
                    throw new SubmissionException(413, "PHOTO_DIMENSIONS_TOO_LARGE");
                int sample = 1;
                while ((long) ((width + sample - 1) / sample) * ((height + sample - 1) / sample) > 6_000_000
                        || Math.max(width, height) / sample > 4096) sample *= 2;
                var options = reader.getDefaultReadParam(); options.setSourceSubsampling(sample, sample, 0, 0);
                original = reader.read(0, options);
            } finally { reader.dispose(); }
            double scale = Math.min(1, 2048d / Math.max(original.getWidth(), original.getHeight()));
            int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
            BufferedImage clean = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = clean.createGraphics();
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(original, 0, 0, width, height, null); graphics.dispose(); original.flush();
            byte[] jpeg = encode(clean, .9f);
            if (jpeg.length > MAX_BYTES) jpeg = encode(clean, .75f);
            clean.flush();
            if (jpeg.length > MAX_BYTES) throw new SubmissionException(413, "PHOTO_TOO_LARGE");
            return new Stored(jpeg, width, height, sha256(jpeg));
        } catch (SubmissionException e) { throw e;
        } catch (IOException | RuntimeException e) { throw new SubmissionException(400, "PHOTO_INVALID"); }
    }

    private byte[] encode(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            var options = writer.getDefaultWriteParam();
            options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); options.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), options);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
