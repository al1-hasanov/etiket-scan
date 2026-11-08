package az.etiket.backend;

import az.etiket.core.NutritionLabel;
import az.etiket.core.NutritionTextParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Uses the positions of complete OCR words to separate a reference column from a serving column. */
final class NutritionTableReader {
    private static final String NUMBER = "[0-9]{1,4}(?:[.,][0-9]{1,3})?";
    private static final Pattern HEADING = Pattern.compile(
        "(?iu)(?<![\\p{L}\\d.,])(?:(?:(?<prefix>in|pro|per|je|pour|на|her|für)\\h*)?"
            + "(?<amount>" + NUMBER + ")\\h*(?<unit>ml|мл|g|q|г)"
            + "|(?:per\\h+serving|(?:pro|je)\\h+portion)(?:\\h*\\(\\h*" + NUMBER + "\\h*(?:ml|мл|g|q|г)\\h*\\))?)"
            + "(?![\\p{L}\\d.,])");
    private static final Pattern TITLE = Pattern.compile("(?iu)\\b(?:n[äa]hrwert|nutrition|typical values|besin|qida|пищев)");
    private static final Pattern GRAMS = Pattern.compile("(?iu)(" + NUMBER + ")\\h*(?:g|q|г)");
    private static final String KCAL = "(?<kcal>" + NUMBER + ")\\h*(?:kcal|ккал)";
    private static final Pattern ENERGY = Pattern.compile("(?iu)(?:" + NUMBER + "\\h*(?:kj|кдж)\\h*/?\\h*)?"
        + "(?<open>\\()?\\h*" + KCAL + "\\h*(?<close>\\))?");
    private static final Pattern KILOJOULES = Pattern.compile("(?iu)" + NUMBER + "\\h*(?:kj|кдж)");
    private static final double MIN_AGREEMENT_CONFIDENCE = 45;
    private static final double MIN_CLEAR_CONFIDENCE = 70;

    record Word(String line, String text, int left, int top, int right, int bottom, double confidence) { }
    private record Heading(String basis, String amount, boolean explicit, int left, int right, int height,
                           double confidence) { }
    private record Cell(double value, double confidence) { }
    private record Row(String name, String value, int top, int bottom, double confidence) {
        boolean follows(Row previous) {
            return top > previous.top && top - previous.bottom <= previous.bottom - previous.top;
        }
    }
    record Crop(int left, int top, int right, int bottom) { }
    record Reading(String basis, boolean referenceFirst, String otherAmount, Map<String, Cell> values,
                   double headingConfidence, Crop headingCrop) {
        boolean confirmed() { return !basis.isEmpty() && headingConfidence >= MIN_AGREEMENT_CONFIDENCE; }
    }

    private NutritionTableReader() { }

    /** Null means that a table with two explicit headings was not found. */
    static Reading read(List<Word> words) {
        Map<String, List<Word>> grouped = new LinkedHashMap<>();
        for (Word word : words) grouped.computeIfAbsent(word.line(), key -> new ArrayList<>()).add(word);
        List<List<Word>> lines = new ArrayList<>(grouped.values());
        for (List<Word> line : lines) line.sort(Comparator.comparingInt(Word::left));

        int headerIndex = -1;
        List<Heading> columns = List.of();
        for (int i = 0; i < lines.size(); i++) {
            List<Word> line = lines.get(i);
            List<Heading> headings = headings(line);
            if (headings.isEmpty()) continue;
            String name = join(line.stream().filter(word -> word.right() <= headings.getFirst().left()).toList());
            if (!NutritionTextParser.nutrientKey(name).isEmpty()) break;
            if (headings.stream().noneMatch(h -> !h.basis().isEmpty())) continue;
            if (!name.isEmpty() && headings.stream().noneMatch(Heading::explicit) && !TITLE.matcher(name).find()) continue;
            if (headings.size() == 1 && headerIndex < 0) continue;
            if (headerIndex >= 0 || headings.size() != 2) {
                OcrDiagnostics.event("table_rejected", "reason", "MULTIPLE_OR_AMBIGUOUS_HEADINGS", "headingCount", headings.size());
                return ambiguous();
            }
            headerIndex = i;
            columns = headings;
        }
        if (headerIndex < 0) {
            OcrDiagnostics.event("table_rejected", "reason", "NO_TWO_COLUMN_REFERENCE_HEADING", "wordCount", words.size(), "lineCount", lines.size());
            return null;
        }
        Heading first = columns.getFirst();
        Heading second = columns.getLast();
        boolean referenceFirst = !first.basis().isEmpty();
        boolean referenceSecond = !second.basis().isEmpty();
        if (referenceFirst == referenceSecond) {
            OcrDiagnostics.event("table_rejected", "reason", "REFERENCE_COLUMN_AMBIGUOUS");
            return ambiguous();
        }
        Heading reference = referenceFirst ? first : second;
        Heading other = referenceFirst ? second : first;
        int gap = second.left() - first.right();
        if (gap < Math.max(4, reference.height() / 3)) {
            OcrDiagnostics.event("table_rejected", "reason", "HEADINGS_TOO_CLOSE", "gap", gap);
            return ambiguous();
        }
        OcrDiagnostics.event("table_heading", "basis", reference.basis(), "referenceSide", referenceFirst ? "LEFT" : "RIGHT",
            "otherAmount", other.amount().replace("мл", "ml").replace("г", "g"),
            "referenceConfidence", reference.confidence(), "otherConfidence", other.confidence(),
            "confirmed", Math.min(reference.confidence(), other.confidence()) >= MIN_AGREEMENT_CONFIDENCE);

        int divider = first.right() + gap / 2;
        // Extend the gap between headings down the table and use the same spacing for the name boundary.
        int namesEnd = Math.min(first.left(), divider - (second.right() - first.right()));
        int valueStart = referenceFirst ? namesEnd : divider;
        int valueEnd = referenceFirst ? divider : second.right() + gap / 2;
        OcrDiagnostics.event("table_columns", "namesEnd", namesEnd, "valueStart", valueStart, "valueEnd", valueEnd, "divider", divider);
        List<Row> rows = new ArrayList<>();
        for (List<Word> line : lines.subList(headerIndex + 1, lines.size())) {
            String name = join(line.stream().filter(word -> word.right() <= namesEnd).toList());
            List<Word> values = line.stream().filter(word -> word.right() > valueStart && word.left() < valueEnd).toList();
            if (!values.isEmpty()) {
                int right = values.stream().mapToInt(Word::right).max().orElseThrow();
                int tolerance = Math.max(reference.height(), (reference.right() - reference.left()) / 2);
                boolean cutName = line.stream().anyMatch(word -> word.left() < namesEnd && word.right() > namesEnd);
                if (cutName || values.getFirst().left() < valueStart || right > valueEnd
                    || Math.abs(right - reference.right()) > tolerance) {
                    String key = NutritionTextParser.nutrientKey(name);
                    OcrDiagnostics.event("cell_geometry_rejected", "field", key.isEmpty() ? "UNKNOWN" : key,
                        "reason", cutName ? "CUT_ROW_LABEL" : values.getFirst().left() < valueStart || right > valueEnd
                            ? "CROSSES_COLUMN_BOUNDARY" : "VALUE_NOT_ALIGNED", "rowTop", line.getFirst().top());
                    values = List.of();
                }
            }
            rows.add(new Row(name, join(values), line.stream().mapToInt(Word::top).min().orElseThrow(),
                line.stream().mapToInt(Word::bottom).max().orElseThrow(), confidence(values)));
        }
        List<Word> header = lines.get(headerIndex).stream()
            .filter(word -> word.right() > first.left() && word.left() < second.right()).toList();
        Crop crop = new Crop(first.left(), header.stream().mapToInt(Word::top).min().orElseThrow(), second.right(),
            header.stream().mapToInt(Word::bottom).max().orElseThrow());
        Map<String, Cell> parsed = values(rows, reference.basis());
        OcrDiagnostics.event("table_result", "rowCount", rows.size(), "validCellCount", parsed.size());
        return new Reading(reference.basis(), referenceFirst, other.amount(), parsed,
            Math.min(reference.confidence(), other.confidence()), crop);
    }

    static Reading confirmHeading(Reading original, Reading checked) {
        if (checked == null || !checked.confirmed() || !original.basis().equals(checked.basis())
            || original.referenceFirst() != checked.referenceFirst() || !original.otherAmount().equals(checked.otherAmount()))
            return original;
        return new Reading(original.basis(), original.referenceFirst(), original.otherAmount(), original.values(),
            checked.headingConfidence(), original.headingCrop());
    }

    static NutritionTextParser.Result agree(Reading first, Reading second) {
        List<Reading> readings = new ArrayList<>();
        readings.add(first);
        readings.add(second);
        return agree(readings);
    }

    /** More fallback reads never turn a conflicting, legible cell into a majority vote. */
    static NutritionTextParser.Result agree(List<Reading> input) {
        List<Reading> readings = input.stream().filter(reading -> reading != null && reading.confirmed()).toList();
        if (readings.size() < 2) {
            OcrDiagnostics.event("consensus_rejected", "reason", "INSUFFICIENT_CONFIRMED_READINGS",
                "totalReadings", input.size(), "confirmedReadings", readings.size());
            return unresolved();
        }
        Reading first = readings.getFirst();
        if (readings.stream().anyMatch(reading -> !first.basis().equals(reading.basis())
            || first.referenceFirst() != reading.referenceFirst() || !first.otherAmount().equals(reading.otherAmount()))) {
            OcrDiagnostics.event("consensus_rejected", "reason", "HEADING_DISAGREEMENT", "confirmedReadings", readings.size());
            return unresolved();
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (String key : NutritionLabel.KEYS) {
            List<Cell> cells = readings.stream().map(reading -> reading.values().get(key))
                .filter(cell -> cell != null && cell.confidence() >= MIN_AGREEMENT_CONFIDENCE).toList();
            if (cells.size() >= 2 && cells.stream().anyMatch(cell -> cell.confidence() >= MIN_CLEAR_CONFIDENCE)
                && cells.stream().allMatch(cell -> cell.value() == cells.getFirst().value()))
            {
                values.put(key, cells.getFirst().value());
                OcrDiagnostics.event("field_agreed", "field", key, "matchingReadings", cells.size());
            }
            else {
                long observed = readings.stream().filter(reading -> reading.values().containsKey(key)).count();
                String reason = cells.size() < 2 ? observed > cells.size() ? "LOW_CELL_CONFIDENCE"
                    : cells.isEmpty() ? "NO_VALID_CELL" : "ONLY_ONE_VALID_READING"
                    : cells.stream().anyMatch(cell -> cell.value() != cells.getFirst().value()) ? "VALUE_DISAGREEMENT" : "NO_CLEAR_READING";
                OcrDiagnostics.event("field_rejected", "field", key, "reason", reason, "eligibleReadings", cells.size(), "observedReadings", observed);
            }
        }
        if (values.isEmpty()) return unresolved();
        try {
            return new NutritionTextParser.Result(new NutritionLabel(first.basis(), values), List.of());
        } catch (IllegalArgumentException e) {
            OcrDiagnostics.event("consensus_rejected", "reason", "INCONSISTENT_NUTRIENT_VALUES");
            return new NutritionTextParser.Result(NutritionLabel.empty(), List.of("CHECK_INCONSISTENT_VALUES"));
        }
    }

    static NutritionTextParser.Result unresolved() {
        return new NutritionTextParser.Result(NutritionLabel.empty(), List.of("MULTIPLE_NUTRITION_COLUMNS"));
    }

    private static Reading ambiguous() {
        return new Reading("", false, "", Map.of(), 0, null);
    }

    private static Map<String, Cell> values(List<Row> rows, String basis) {
        Map<String, Cell> values = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            String key = NutritionTextParser.nutrientKey(row.name());
            if (key.isEmpty() && !row.name().isEmpty() && i > 0) {
                Row previous = rows.get(i - 1);
                if (previous.value().isEmpty() && row.follows(previous))
                    key = NutritionTextParser.nutrientKey(previous.name() + " " + row.name());
            }
            if (key.isEmpty()) {
                if (!row.name().isEmpty() && !row.value().isEmpty())
                    OcrDiagnostics.event("row_rejected", "reason", "UNKNOWN_NUTRIENT_LABEL", "rowTop", row.top());
                continue;
            }
            if (!seen.add(key)) {
                values.remove(key);
                OcrDiagnostics.event("cell_rejected", "field", key, "reason", "DUPLICATE_NUTRIENT_ROW");
                continue;
            }

            boolean energy = key.equals("energyKcal");
            Double value = number(row.value(), energy);
            if (energy && value == null && i + 1 < rows.size() && KILOJOULES.matcher(row.value()).matches()) {
                Row next = rows.get(i + 1);
                if (next.name().isEmpty() && next.follows(row) && next.value().startsWith("(")) {
                    row = next;
                    value = number(row.value(), true);
                }
            }
            double maximum = energy ? 1000 : 100;
            if (basis.equals("PER_100_ML")) maximum *= 2;
            if (value != null && value <= maximum) {
                values.put(key, new Cell(value, row.confidence()));
                OcrDiagnostics.event("cell_parsed", "field", key, "confidence", row.confidence());
            } else OcrDiagnostics.event("cell_rejected", "field", key, "reason", value == null ? rejectedCell(row.value(), energy) : "OUT_OF_RANGE",
                "confidence", row.confidence());
        }
        return values;
    }

    private static Double number(String value, boolean energy) {
        var match = (energy ? ENERGY : GRAMS).matcher(NutritionTextParser.withoutTableBorders(value));
        if (!match.matches() || energy && ((match.group("open") == null) != (match.group("close") == null))) return null;
        return Double.parseDouble((energy ? match.group("kcal") : match.group(1)).replace(',', '.'));
    }

    private static String rejectedCell(String text, boolean energy) {
        if (text.isBlank()) return "EMPTY_REFERENCE_CELL";
        if (text.contains("%")) return "PERCENTAGE_IN_VALUE_CELL";
        if (text.matches(".*[<>≤≥].*")) return "INEQUALITY_IN_VALUE_CELL";
        if (!(energy ? Pattern.compile("(?iu)kcal|ккал") : Pattern.compile("(?iu)(?<![\\p{L}])(?:g|q|г)(?![\\p{L}])")).matcher(text).find())
            return "MISSING_OR_UNSUPPORTED_UNIT";
        return "INVALID_VALUE_SHAPE";
    }

    private static List<Heading> headings(List<Word> line) {
        // Keep offsets aligned with the original word boxes, including border tokens.
        var match = HEADING.matcher(String.join(" ", line.stream().map(Word::text).toList()));
        List<Heading> headings = new ArrayList<>();
        while (match.find()) {
            List<Word> cell = new ArrayList<>();
            int offset = 0;
            for (Word word : line) {
                int end = offset + word.text().length();
                if (offset < match.end() && end > match.start()) cell.add(word);
                offset = end + 1;
            }
            String amount = match.group("amount");
            String unit = amount == null ? "" : match.group("unit").toLowerCase(Locale.ROOT);
            String basis = "100".equals(amount) ? Set.of("ml", "мл").contains(unit) ? "PER_100_ML" : "PER_100_G" : "";
            headings.add(new Heading(basis, amount == null ? "serving" : amount.replace(',', '.') + unit,
                amount == null || match.group("prefix") != null,
                cell.getFirst().left(), cell.getLast().right(),
                cell.stream().mapToInt(word -> word.bottom() - word.top()).max().orElseThrow(), confidence(cell)));
        }
        return headings;
    }

    private static double confidence(List<Word> words) {
        return words.stream().mapToDouble(Word::confidence).min().orElse(0);
    }

    private static String join(List<Word> words) {
        return NutritionTextParser.withoutTableBorders(String.join(" ", words.stream().map(Word::text).toList()));
    }
}
