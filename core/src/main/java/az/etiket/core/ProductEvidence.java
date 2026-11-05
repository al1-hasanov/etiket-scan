package az.etiket.core;

import java.util.List;

/** Evidence about a source, kept separate from the reported ingredients. */
public record ProductEvidence(String reviewStatus, String ingredientsStatus,
        boolean labelPhotoChecked, String ingredientsSourceUrl, String ingredientSectionAsReported,
        String declaredAllergenStatement, List<String> notes, boolean externalCandidateAvailable) {
    public ProductEvidence {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static ProductEvidence unreviewed(String source, String ingredients, boolean demo) {
        return new ProductEvidence(demo ? "FICTIONAL_SAMPLE" : "COMMUNITY_UNVERIFIED",
            ingredients == null || ingredients.isBlank() ? "MISSING" : "SOURCE_TEXT_UNVERIFIED",
            false, "", "", "", List.of(), false);
    }
}
