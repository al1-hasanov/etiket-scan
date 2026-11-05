package az.etiket.backend;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runs a bounded local executable, with fixed language/CLI arguments and no shell. */
@Component
final class TesseractOcr implements OcrEngine {
    private final String executable;
    private final int timeout;
    static final Map<String, String> LANGUAGES = Map.of("az", "aze+eng", "tr", "tur+eng", "ru", "rus+eng", "en", "eng", "de", "deu+eng", "und", "aze+tur+rus+eng");
    TesseractOcr(@Value("${ocr.command:tesseract}") String executable, @Value("${ocr.timeout-seconds:35}") int timeout) {
        this.executable = executable; this.timeout = Math.max(1, Math.min(timeout, 60));
    }
    @Override public Text recognize(byte[] image, String language) throws Exception {
        if (!LANGUAGES.containsKey(language)) throw new SubmissionException(400, "OCR_LANGUAGE_UNSUPPORTED");
        Path directory = Files.createTempDirectory("etiket-ocr-");
        long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
        try {
            BufferedImage original = ImageIO.read(new java.io.ByteArrayInputStream(image));
            Text best = new Text("", 0, List.of(), 0);
            for (int angle : new int[]{0, 90, 180, 270}) {
                if (System.nanoTime() >= deadline) { if (!best.text().isBlank()) break; throw new java.util.concurrent.TimeoutException(); }
                BufferedImage prepared = rotate(original, angle);
                Path input = directory.resolve("input.png"), output = directory.resolve("result.tsv"), error = directory.resolve("error.log");
                ImageIO.write(prepared, "png", input.toFile()); if (prepared != original) prepared.flush();
                ProcessBuilder builder = new ProcessBuilder(executable, input.toString(), "stdout", "-l", LANGUAGES.get(language), "--psm", "6", "-c", "tessedit_create_tsv=1");
                builder.environment().put("OMP_THREAD_LIMIT", "1"); builder.redirectOutput(output.toFile()); builder.redirectError(error.toFile());
                Process process = builder.start();
                try {
                    if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) throw new java.util.concurrent.TimeoutException();
                    if (process.exitValue() != 0) throw new IllegalStateException("OCR_ENGINE_UNAVAILABLE");
                } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); } }
                if (Files.size(output) > 2_000_000) throw new IllegalStateException("OCR_OUTPUT_TOO_LARGE");
                Text result = parse(Files.readString(output), angle);
                if (quality(result) > quality(best)) best = result;
                if (best.confidence() >= 80 && best.text().length() >= 12) break;
            }
            original.flush(); return best;
        } finally {
            try (var files = Files.walk(directory)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static double quality(Text result) { return result.confidence() * Math.min(1, result.text().length() / 20d); }
    static Text parse(String tsv, int rotation) {
        StringBuilder text = new StringBuilder(); List<String> uncertain = new ArrayList<>();
        String previous = ""; double confidence = 0; int count = 0;
        for (String line : tsv.split("\\R")) {
            String[] parts = line.split("\\t", 12);
            if (parts.length != 12 || !parts[0].equals("5") || parts[11].isBlank()) continue;
            double value;
            try { value = Double.parseDouble(parts[10]); } catch (NumberFormatException e) { continue; }
            if (value < 0) continue;
            String group = String.join(":", parts[1], parts[2], parts[3], parts[4]);
            if (!text.isEmpty()) text.append(previous.equals(group) ? ' ' : '\n');
            text.append(parts[11]); previous = group; confidence += value; count++;
            if (value < 70 && uncertain.size() < 50) uncertain.add(parts[11].substring(0,Math.min(80,parts[11].length())));
            if (text.length() > 10000) break;
        }
        return new Text(text.substring(0, Math.min(10000, text.length())), count == 0 ? 0 : Math.round(confidence / count * 10) / 10d, List.copyOf(uncertain), rotation);
    }
    private static BufferedImage rotate(BufferedImage image, int angle) {
        if (angle == 0) return image;
        boolean sideways = angle == 90 || angle == 270;
        BufferedImage result = new BufferedImage(sideways ? image.getHeight() : image.getWidth(), sideways ? image.getWidth() : image.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = result.createGraphics(); g.translate(result.getWidth() / 2d, result.getHeight() / 2d);
        g.rotate(Math.toRadians(angle)); g.drawImage(image, -image.getWidth() / 2, -image.getHeight() / 2, null); g.dispose(); return result;
    }
}
