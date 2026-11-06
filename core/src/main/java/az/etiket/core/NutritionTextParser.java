package az.etiket.core;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Conservative suggestions for tables with one explicit reference column. */
public final class NutritionTextParser {
    public record Result(NutritionLabel nutrition, List<String> warnings) { }
    private static final Pattern REFERENCE = Pattern.compile(
        "(?m)(?:^\\h*|(?<!\\p{L})(?:per|pro|in|je|pour|на|her|fur)\\h*)100\\h*(ml|мл|g|q|г)(?!\\p{L})");
    private static final Pattern PORTION = Pattern.compile(
        "per\\s+serving|(?<!\\p{L})portion(?:en|sgro(?:ß|ss)e)?(?!\\p{L})|porsiyon|porsiya|на порцию|на порции");
    private static final Pattern OTHER_REFERENCE = Pattern.compile(
        "(?<!\\p{L})(?:per|pro|in|je|pour)\\h*(?!100\\h*(?:ml|мл|g|q|г)(?!\\p{L}))[0-9]+(?:[.,][0-9]+)?\\h*(?:ml|мл|g|q|г)(?!\\p{L})");
    private static final Pattern HEADING_AMOUNT = Pattern.compile(
        "(?<![\\p{L}\\d.,])(?:(?:per|pro|in|je|pour|на|her|fur)\\h*)?[0-9]+(?:[.,][0-9]+)?\\h*(?:ml|мл|g|q|г)(?![\\p{L}\\d.,])");
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:[.,][0-9]+)?");
    private static final Pattern ENERGY_KJ = Pattern.compile("[0-9]+(?:[.,][0-9]+)?\\h*(?:kj|кдж)");
    private static final Pattern ENERGY_KCAL_CONTINUATION = Pattern.compile("\\(\\h*[0-9]+(?:[.,][0-9]+)?\\h*(?:kcal|ккал)\\h*\\)");
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("energyKcal", "energy|energie|brennwert|enerji|enerji degeri|energeticeskaya cennost|энергетическая ценность|энергия");
        LABELS.put("saturatedFat", "saturated fat|saturates|of which saturates|davon gesattigte fettsauren|gesattigte fettsauren|doymus yag|doymus yaglar|насыщенные жиры|насыщенных жиров|из них насыщенные жиры");
        LABELS.put("fat", "fat|total fat|fett|yag|yaglar|жиры|жиров");
        LABELS.put("sugars", "sugars|sugar|of which sugars|zucker|davon zucker|sekerler|seker|səkərlər|səkər|səkərlərdən|из них сахара|сахара|сахар");
        LABELS.put("carbohydrates", "carbohydrates|carbohydrate|kohlenhydrate|karbohidrat|karbonhidrat|углеводы|углеводов");
        LABELS.put("fiber", "fibre|fiber|dietary fibre|ballaststoffe|lif|qida lifi|пищевые волокна|клетчатка");
        LABELS.put("protein", "protein|eiweiß|eiweiss|zulal|zulallar|белки|белков");
        LABELS.put("salt", "salt|salz|duz|tuz|соль|соли");
    }
    private NutritionTextParser() { }

    /** Matches the name cell of an OCR row using the same aliases as the text parser. */
    public static String nutrientKey(String name) {
        String label = normalize(name.split("/", 2)[0]).strip()
            .replaceFirst("^[-–—]\\h*", "").replaceFirst(":$", "").strip();
        for (var entry : LABELS.entrySet())
            if (label.matches(entry.getValue())) return entry.getKey();
        return "";
    }

    public static Result parse(String raw) {
        String text = normalize(raw == null ? "" : raw)
            .replaceAll("(?m)^\\h*davon gesattigte\\h*\\R\\h*fettsauren(?=\\h|:|$)", "davon gesattigte fettsauren");
        Matcher reference = REFERENCE.matcher(text);
        boolean grams = false;
        boolean ml = false;
        int references = 0;
        while (reference.find()) {
            if (++references > 1) return empty("MULTIPLE_NUTRITION_COLUMNS");
            if (Set.of("ml", "мл").contains(reference.group(1))) ml = true;
            else grams = true;
        }
        if (grams == ml) return empty("CONFIRM_REFERENCE_AMOUNT_AND_ENTER_VALUES");
        if (PORTION.matcher(text).find() || OTHER_REFERENCE.matcher(text).find())
            return empty("MULTIPLE_NUTRITION_COLUMNS");
        String[] lines = text.split("\\R");
        for (String line : lines)
            if (REFERENCE.matcher(line).find() && HEADING_AMOUNT.matcher(line).results().limit(2).count() > 1)
                return empty("MULTIPLE_NUTRITION_COLUMNS");
        Map<String, Double> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (var label : LABELS.entrySet()) {
            List<Double> found = new ArrayList<>();
            Pattern prefix = Pattern.compile("^\\s*(?:" + label.getValue() + ")(?=\\s|:|/|$)\\s*:?(.*)$");
            for (int i = 0; i < lines.length; i++) {
                Matcher match = prefix.matcher(lines[i]);
                if (!match.matches()) continue;
                String tail = match.group(1).strip();
                int lastLine = i;
                if (tail.isEmpty() && lastLine + 1 < lines.length) tail = lines[++lastLine].strip();
                boolean energy = label.getKey().equals("energyKcal");
                if (energy && ENERGY_KJ.matcher(tail).matches() && lastLine + 1 < lines.length) {
                    String next = lines[lastLine + 1].strip();
                    if (ENERGY_KCAL_CONTINUATION.matcher(next).matches()) tail += " " + next;
                }
                if (tail.contains("<") || tail.contains(">") || tail.contains("≤") || tail.contains("≥") || tail.contains("%") || tail.matches(".*-\\s*[0-9].*")) continue;
                long numericTokens = NUMBER.matcher(tail).results().count();
                boolean energyPair = energy && numericTokens == 2
                    && tail.matches(".*(?:kj|кдж).*") && tail.matches(".*(?:kcal|ккал).*");
                // Another column anywhere in the table makes even single-value rows ambiguous.
                if (numericTokens > 1 && !energyPair) return empty("MULTIPLE_NUTRITION_COLUMNS");
                String unit = energy ? "(?:kcal|ккал)" : "(?:g|q|г)";
                Matcher number = Pattern.compile("(?<![0-9.,-])([0-9]+(?:[.,][0-9]+)?)\\s*" + unit + "(?![a-zа-я])").matcher(tail);
                List<Double> row = new ArrayList<>();
                while (number.find()) row.add(Double.parseDouble(number.group(1).replace(',', '.')));
                if (row.size() == 1 && (numericTokens == 1 || energyPair)) found.add(row.get(0));
            }
            if (found.size() == 1 && found.get(0) <= (label.getKey().equals("energyKcal") ? (ml ? 2000 : 1000) : (ml ? 200 : 100))) values.put(label.getKey(), found.get(0));
            else if (!found.isEmpty()) warnings.add("CHECK_" + label.getKey());
        }
        try { return new Result(new NutritionLabel(grams ? "PER_100_G" : "PER_100_ML", values), List.copyOf(warnings)); }
        catch (IllegalArgumentException e) { return new Result(NutritionLabel.empty(), List.of("CHECK_INCONSISTENT_VALUES")); }
    }
    private static Result empty(String warning) {
        return new Result(NutritionLabel.empty(), List.of(warning));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('ı', 'i').replace('ə', 'e'), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    }
}
