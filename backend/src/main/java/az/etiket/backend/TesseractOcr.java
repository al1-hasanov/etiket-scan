package az.etiket.backend;

import az.etiket.core.NutritionTextParser;
import az.etiket.core.OcrQuality;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runs a bounded local executable with fixed language and image options. */
@Component
final class TesseractOcr implements OcrEngine {
    static final Map<String, String> LANGUAGES = Map.of(
        "az", "aze", "tr", "tur", "ru", "rus", "en", "eng", "de", "deu", "und", "aze+tur+rus+eng");
    private static final double MIN_COVERAGE = .60;
    private final String executable;
    private final int timeout;

    TesseractOcr(@Value("${ocr.command:tesseract}") String executable, @Value("${ocr.timeout-seconds:35}") int timeout) {
        this.executable = executable;
        this.timeout = Math.clamp(timeout, 1, 60);
    }

    @Override
    public Text recognize(byte[] image, String language) throws Exception {
        return recognize(image, language, false);
    }

    @Override
    public Text recognizeNutrition(byte[] image, String language) throws Exception {
        return recognize(image, language, true);
    }

    private Text recognize(byte[] image, String language, boolean nutrition) throws Exception {
        String model = LANGUAGES.get(language);
        if (model == null) throw new SubmissionException(400, "OCR_LANGUAGE_UNSUPPORTED");

        BufferedImage original = ImageIO.read(new ByteArrayInputStream(image));
        if (original == null) throw new IOException("PHOTO_INVALID");
        Path directory = Files.createTempDirectory("etiket-ocr-");
        long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
        try {
            Scan scan = recognizeText(original, directory, model, deadline, nutrition);
            Candidate best = scan.draft();
            Text text = best.result();
            if (!nutrition || !best.usable()) return text;

            var first = scan.grayscale().table();
            var second = scan.enhanced() == null ? null : scan.enhanced().table();
            if (first == null && second == null && best.table() == null) return text;
            int turn = (text.rotation() + 45) / 90 * 90 % 360;
            NutritionTextParser.Result checked;
            if (turn != 0 || scan.enhanced() == null) {
                checked = verifyTable(original, directory, model, turn, best.table(), deadline);
            } else {
                checked = NutritionTableReader.agree(first, second);
            }
            return new Text(text.text(), text.confidence(), text.uncertainWords(), text.rotation(), text.warnings(), checked);
        } finally {
            original.flush();
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private record Scan(Candidate draft, Candidate grayscale, Candidate enhanced) { }

    private Scan recognizeText(BufferedImage original, Path directory, String model, long deadline,
                               boolean nutrition) throws Exception {
        Candidate best = null;
        Candidate first = null;
        Candidate second = null;
        try {
            var grayscale = OcrImages.prepare(original, false);
            try {
                first = read(directory, grayscale.image(), model, 6, 2, grayscale.angle(), deadline, nutrition);
                best = first;
                if (best.strong()) return new Scan(best, first, null);
            } finally {
                grayscale.image().flush();
            }

            var enhanced = OcrImages.prepare(original, true);
            try {
                second = read(directory, enhanced.image(), model, 6, 2, enhanced.angle(), deadline, nutrition);
                best = better(best, second);
                if (best.strong()) return new Scan(best, first, second);
                best = better(best, read(directory, enhanced.image(), model, 3, 2, enhanced.angle(), deadline, nutrition));
                if (best.usable()) return new Scan(best, first, second);

                for (int angle : new int[]{90, 270, 180}) {
                    BufferedImage rotated = OcrImages.rotate(enhanced.image(), angle);
                    try {
                        best = better(best, read(directory, rotated, model, 6, 2, enhanced.angle() + angle, deadline, nutrition));
                        if (best.strong()) break;
                    } finally {
                        rotated.flush();
                    }
                }
            } finally {
                enhanced.image().flush();
            }
            return new Scan(best, first, second);
        } catch (TimeoutException e) {
            if (best != null && best.usable()) return new Scan(best, first, second);
            throw e;
        }
    }

    private NutritionTextParser.Result verifyTable(BufferedImage original, Path directory, String model,
            int turn, NutritionTableReader.Reading first, long deadline) throws Exception {
        if (first == null || first.basis().isEmpty() || System.nanoTime() >= deadline)
            return NutritionTableReader.unresolved();
        var prepared = OcrImages.prepare(original, turn == 0);
        BufferedImage rotated = OcrImages.rotate(prepared.image(), turn);
        try {
            // Check the layout using the other preprocessing variant in the same orientation.
            Candidate checked = read(directory, rotated, model, 6, 2, prepared.angle() + turn, deadline, true);
            return NutritionTableReader.agree(first, checked.table());
        } catch (TimeoutException | IOException | IllegalStateException e) {
            return NutritionTableReader.unresolved();
        } finally {
            if (rotated != prepared.image()) rotated.flush();
            prepared.image().flush();
        }
    }

    private Candidate read(Path directory, BufferedImage image, String model, int psm, int threshold,
                           int rotation, long deadline, boolean nutrition) throws Exception {
        if (System.nanoTime() >= deadline) throw new TimeoutException();
        Path input = directory.resolve("input.png");
        Path output = directory.resolve("result.tsv");
        ImageIO.write(image, "png", input.toFile());
        if (System.nanoTime() >= deadline) throw new TimeoutException();
        ProcessBuilder builder = new ProcessBuilder(executable, input.toString(), "stdout",
            "-l", model, "--oem", "1", "--psm", String.valueOf(psm),
            "-c", "thresholding_method=" + threshold, "-c", "tessedit_create_tsv=1");
        builder.environment().put("OMP_THREAD_LIMIT", "1");
        builder.redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                throw new TimeoutException();
            if (process.exitValue() != 0) throw new IllegalStateException("OCR_ENGINE_UNAVAILABLE");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
        if (Files.size(output) > 2_000_000) throw new IllegalStateException("OCR_OUTPUT_TOO_LARGE");
        return parseCandidate(Files.readString(output), Math.floorMod(rotation, 360), image, nutrition);
    }

    private record Candidate(Text text, OcrQuality.Assessment assessment, double coverage,
                             NutritionTableReader.Reading table) {
        boolean usable() { return assessment.readable() && coverage >= MIN_COVERAGE; }
        boolean strong() { return usable() && coverage >= .85 && text.confidence() >= 88; }

        double score() {
            return (usable() ? 200 : 0) + (assessment.readable() ? 30 : 0)
                + text.confidence() * .35 + Math.min(30, assessment.words()) * 1.5 + coverage * 35;
        }

        Text result() {
            String warning = !assessment.readable() ? "RETAKE_UNREADABLE_TEXT"
                : coverage < MIN_COVERAGE ? "RETAKE_INCOMPLETE_TEXT" : "";
            return warning.isEmpty() ? text
                : new Text(text.text(), text.confidence(), text.uncertainWords(), text.rotation(), List.of(warning));
        }
    }

    private static Candidate better(Candidate first, Candidate second) {
        return second.score() > first.score() ? second : first;
    }

    static Text parse(String tsv, int rotation) {
        return parseCandidate(tsv, rotation, null, false).text();
    }

    private static Candidate parseCandidate(String tsv, int rotation, BufferedImage image, boolean nutrition) {
        StringBuilder text = new StringBuilder();
        List<String> uncertain = new ArrayList<>();
        List<NutritionTableReader.Word> words = nutrition ? new ArrayList<>() : null;
        BitSet recognized = new BitSet();
        String previous = "";
        double confidence = 0;
        int characters = 0;
        for (String line : tsv.split("\\R")) {
            String[] parts = line.split("\\t", 12);
            if (parts.length != 12 || !parts[0].equals("5") || parts[11].isBlank()) continue;
            double value;
            try {
                value = Double.parseDouble(parts[10]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (!Double.isFinite(value) || value < 0 || value > 100) continue;
            String word = parts[11];
            String group = String.join(":", parts[1], parts[2], parts[3], parts[4]);
            if (!text.isEmpty()) text.append(previous.equals(group) ? ' ' : '\n');
            text.append(word);
            previous = group;
            int weight = Math.max(1, (int) word.codePoints().filter(Character::isLetterOrDigit).count());
            confidence += value * weight;
            characters += weight;
            if (value < 70 && uncertain.size() < 50) uncertain.add(word.substring(0, Math.min(80, word.length())));
            if (image != null) markWord(recognized, parts, image.getWidth(), image.getHeight(), words, group, value);
            if (text.length() >= 10_000) break;
        }
        String raw = text.substring(0, Math.min(10_000, text.length()));
        double average = characters == 0 ? 0 : Math.round(confidence / characters * 10) / 10d;
        Text result = new Text(raw, average, List.copyOf(uncertain), rotation);
        return new Candidate(result, OcrQuality.assess(raw, average), image == null ? 1 : coverage(image, recognized),
            words == null ? null : NutritionTableReader.read(words));
    }

    private static void markWord(BitSet recognized, String[] columns, int width, int height,
                                 List<NutritionTableReader.Word> words, String group, double confidence) {
        try {
            long left = Integer.parseInt(columns[6]);
            long top = Integer.parseInt(columns[7]);
            int x = (int) Math.clamp(left, 0, width);
            int y = (int) Math.clamp(top, 0, height);
            int right = (int) Math.clamp(left + Integer.parseInt(columns[8]), x, width);
            int bottom = (int) Math.clamp(top + Integer.parseInt(columns[9]), y, height);
            for (int row = y; row < bottom; row++) recognized.set(row * width + x, row * width + right);
            if (words != null && right > x && bottom > y)
                words.add(new NutritionTableReader.Word(group, columns[11], x, y, right, bottom, confidence));
        } catch (NumberFormatException ignored) {
            // Invalid boxes contribute no coverage.
        }
    }

    private static double coverage(BufferedImage image, BitSet recognized) {
        byte[] pixels = OcrImages.pixels(image);
        int threshold = OcrImages.inkThreshold(pixels);
        int ink = 0;
        int covered = 0;
        for (int i = 0; i < pixels.length; i++) {
            if ((pixels[i] & 255) > threshold) continue;
            ink++;
            if (recognized.get(i)) covered++;
        }
        return ink == 0 ? 0 : covered / (double) ink;
    }
}
