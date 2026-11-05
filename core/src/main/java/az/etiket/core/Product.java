package az.etiket.core;

import java.util.List;
import java.util.Map;

/** Missing nutrient keys mean unknown, never zero. Nutri-Score is supplied by the source. */
public record Product(String barcode, String name, String brand, String quantity,
        String ingredients, String ingredientsLanguage, String nutriScore,
        Map<String, Double> nutrients, List<String> allergens, List<String> traces,
        String source, String sourceUrl, String retrievedAt, String sourceUpdatedAt,
        boolean demo, ProductEvidence evidence) {
    public Product {
        nutrients = Map.copyOf(nutrients);
        allergens = List.copyOf(allergens);
        traces = List.copyOf(traces);
        if (evidence == null) evidence = ProductEvidence.unreviewed(source, ingredients, demo);
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
