package az.etiket.core;

import java.util.List;
import java.util.Map;

/** Missing nutrient keys mean unknown, never zero. Nutri-Score is supplied by the source. */
public record Product(String barcode, String name, String brand, String quantity,
        String ingredients, String ingredientsLanguage, String nutriScore,
        Map<String, Double> nutrients, List<String> allergens, List<String> traces,
        String source, String sourceUrl, String retrievedAt, String sourceUpdatedAt,
        boolean demo, ProductEvidence evidence, IngredientAnalysis ingredientAnalysis, NutritionLabel nutrition) {
    public Product {
        nutrition = nutrition == null ? (nutrients.isEmpty() ? NutritionLabel.empty() : new NutritionLabel("PER_100_G", nutrients)) : nutrition;
        // Older clients label this legacy map per 100 g. Never expose per-100-ml values in it.
        nutrients = nutrition.basis().equals("PER_100_G") ? nutrition.values() : Map.of();
        allergens = List.copyOf(allergens);
        traces = List.copyOf(traces);
        if (evidence == null) evidence = ProductEvidence.unreviewed(source, ingredients, demo);
        // Always derive descriptions from the reported text and this dictionary version.
        // Stored payloads and older APIs do not need an ingredientAnalysis field.
        ingredientAnalysis = IngredientExplainer.analyze(ingredients, ingredientsLanguage);
    }

    public Product(String barcode, String name, String brand, String quantity,
            String ingredients, String ingredientsLanguage, String nutriScore,
            Map<String, Double> nutrients, List<String> allergens, List<String> traces,
            String source, String sourceUrl, String retrievedAt, String sourceUpdatedAt,
            boolean demo, ProductEvidence evidence, IngredientAnalysis ingredientAnalysis) {
        this(barcode, name, brand, quantity, ingredients, ingredientsLanguage, nutriScore,
            nutrients, allergens, traces, source, sourceUrl, retrievedAt, sourceUpdatedAt, demo, evidence, ingredientAnalysis, null);
    }

    public Product(String barcode, String name, String brand, String quantity,
            String ingredients, String ingredientsLanguage, String nutriScore,
            Map<String, Double> nutrients, List<String> allergens, List<String> traces,
            String source, String sourceUrl, String retrievedAt, String sourceUpdatedAt,
            boolean demo, ProductEvidence evidence) {
        this(barcode, name, brand, quantity, ingredients, ingredientsLanguage, nutriScore,
            nutrients, allergens, traces, source, sourceUrl, retrievedAt, sourceUpdatedAt, demo, evidence, null);
    }

    /** Keeps 0.1 fixtures, provider responses and saved products compatible. */
    public Product(String barcode, String name, String brand, String quantity,
            String ingredients, String ingredientsLanguage, String nutriScore,
            Map<String, Double> nutrients, List<String> allergens, List<String> traces,
            String source, String sourceUrl, String retrievedAt, String sourceUpdatedAt, boolean demo) {
        this(barcode, name, brand, quantity, ingredients, ingredientsLanguage, nutriScore,
            nutrients, allergens, traces, source, sourceUrl, retrievedAt, sourceUpdatedAt,
            demo, ProductEvidence.unreviewed(source, ingredients, demo));
    }
}
