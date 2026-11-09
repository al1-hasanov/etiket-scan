package az.etiket.core;

import java.util.*;

/** Values transcribed from a label. Empty keys are unknown, and the reference amount is explicit. */
public record NutritionLabel(String basis, Map<String, Double> values) {
    public static final List<String> KEYS = List.of("energyKcal", "fat", "saturatedFat", "carbohydrates", "sugars", "fiber", "protein", "salt");
    public NutritionLabel {
        basis = basis == null ? "" : basis;
        values = values == null ? Map.of() : Map.copyOf(values);
        if (!Set.of("", "PER_100_G", "PER_100_ML").contains(basis) || !values.isEmpty() && basis.isEmpty())
            throw new IllegalArgumentException("NUTRITION_BASIS_REQUIRED");
        for (var entry : values.entrySet()) {
            double maximum = entry.getKey().equals("energyKcal") ? (basis.equals("PER_100_ML") ? 2000 : 1000) : (basis.equals("PER_100_ML") ? 200 : 100);
            if (!KEYS.contains(entry.getKey()) || !Double.isFinite(entry.getValue()) || entry.getValue() < 0 || entry.getValue() > maximum)
                throw new IllegalArgumentException("NUTRITION_VALUES_INVALID");
        }
        for (String[] pair : List.of(new String[]{"saturatedFat", "fat"}, new String[]{"sugars", "carbohydrates"}))
            if (values.containsKey(pair[0]) && values.containsKey(pair[1]) && values.get(pair[0]) > values.get(pair[1]))
                throw new IllegalArgumentException("NUTRITION_VALUES_INCONSISTENT");
    }
    public static NutritionLabel empty() { return new NutritionLabel("", Map.of()); }

    /** Compare explicit amounts only. Missing fields are not zero. */
    public boolean disagreesWith(NutritionLabel other) {
        if (values.isEmpty() || other.values().isEmpty()) return false;
        return !basis.equals(other.basis()) || values.entrySet().stream().anyMatch(entry -> {
            Double value = other.values().get(entry.getKey());
            return value != null && value.doubleValue() != entry.getValue();
        });
    }
}
