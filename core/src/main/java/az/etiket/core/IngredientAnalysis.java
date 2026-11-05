package az.etiket.core;

import java.util.List;

/** General dictionary descriptions, never evidence about a product's formulation. */
public record IngredientAnalysis(String status, String language, String dictionaryVersion,
        String contentStatus, int recognizedCount, List<Item> items) {
    public IngredientAnalysis { items = List.copyOf(items); }

    public record Item(String reportedText, String group, boolean recognized, String nameAz,
            String explanationAz, List<Reference> references) {
        public Item { references = List.copyOf(references); }
    }

    public record Reference(String title, String url) { }
}
