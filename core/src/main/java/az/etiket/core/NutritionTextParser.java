package az.etiket.core;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Conservative suggestions only. Ambiguous units, portions and multi-column rows stay unfilled. */
public final class NutritionTextParser {
    public record Result(NutritionLabel nutrition, List<String> warnings) { }
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("energyKcal", "energy|enerji|enerji degeri|energeticeskaya cennost|энергетическая ценность|энергия");
        LABELS.put("saturatedFat", "saturated fat|saturates|of which saturates|doymus yag|doymus yaglar|насыщенные жиры|насыщенных жиров|из них насыщенные жиры");
        LABELS.put("fat", "fat|total fat|yag|yaglar|жиры|жиров");
        LABELS.put("sugars", "sugars|sugar|of which sugars|sekerler|seker|səkərlər|səkər|səkərlərdən|из них сахара|сахара|сахар");
        LABELS.put("carbohydrates", "carbohydrates|carbohydrate|karbohidrat|karbonhidrat|углеводы|углеводов");
        LABELS.put("fiber", "fibre|fiber|dietary fibre|lif|qida lifi|пищевые волокна|клетчатка");
        LABELS.put("protein", "protein|zulal|zulallar|белки|белков");
        LABELS.put("salt", "salt|duz|tuz|соль|соли");
    }
    private NutritionTextParser() { }
    public static Result parse(String raw) {
        String text = normalize(raw == null ? "" : raw);
        boolean grams = Pattern.compile("(?<![0-9])100\\s*(?:g|q|г)(?![a-zа-я])").matcher(text).find();
        boolean ml = Pattern.compile("(?<![0-9])100\\s*(?:ml|мл)(?![a-zа-я])").matcher(text).find();
        if (grams == ml || Pattern.compile("per serving|per portion|porsiyon|porsiya|на порцию|на порции").matcher(text).find())
            return new Result(NutritionLabel.empty(), List.of("CONFIRM_REFERENCE_AMOUNT_AND_ENTER_VALUES"));
        Map<String, Double> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (var label : LABELS.entrySet()) {
            List<Double> found = new ArrayList<>();
            Pattern prefix = Pattern.compile("^\\s*(?:" + label.getValue() + ")\\s*:?(.*)$");
            for (int i = 0; i < lines.length; i++) {
                Matcher match = prefix.matcher(lines[i]);
                if (!match.matches()) continue;
                String tail = match.group(1).strip();
                if (tail.isEmpty() && i + 1 < lines.length) tail = lines[i + 1].strip();
                if (tail.contains("<") || tail.contains(">") || tail.contains("≤") || tail.contains("≥") || tail.contains("%") || tail.matches(".*-\\s*[0-9].*")) continue;
                String unit = label.getKey().equals("energyKcal") ? "(?:kcal|ккал)" : "(?:g|q|г)";
                Matcher number = Pattern.compile("(?<![0-9.,-])([0-9]+(?:[.,][0-9]+)?)\\s*" + unit + "(?![a-zа-я])").matcher(tail);
                List<Double> row = new ArrayList<>();
                while (number.find()) row.add(Double.parseDouble(number.group(1).replace(',', '.')));
                // More than one number usually means another column. Energy may have kJ and kcal.
                long numericTokens = Pattern.compile("[0-9]+(?:[.,][0-9]+)?").matcher(tail).results().count();
                if (row.size() == 1 && (numericTokens == 1 || label.getKey().equals("energyKcal") && numericTokens == 2 && tail.matches(".*(?:kj|кдж).*")))
                    found.add(row.get(0));
            }
            if (found.size() == 1 && found.get(0) <= (label.getKey().equals("energyKcal") ? (ml ? 2000 : 1000) : (ml ? 200 : 100))) values.put(label.getKey(), found.get(0));
            else if (!found.isEmpty()) warnings.add("CHECK_" + label.getKey());
        }
        try { return new Result(new NutritionLabel(grams ? "PER_100_G" : "PER_100_ML", values), List.copyOf(warnings)); }
        catch (IllegalArgumentException e) { return new Result(NutritionLabel.empty(), List.of("CHECK_INCONSISTENT_VALUES")); }
    }
    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('ı', 'i').replace('ə', 'e'), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    }
}
