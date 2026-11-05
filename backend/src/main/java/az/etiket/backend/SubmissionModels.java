package az.etiket.backend;

import az.etiket.core.Product;
import az.etiket.core.NutritionLabel;
import java.util.List;
import java.util.Map;

final class SubmissionModels {
    private SubmissionModels() { }
    record Draft(String clientRequestId, String barcode, String name, String brand, String quantity,
        String ingredients, String ingredientsLanguage, String declaredAllergenStatement, boolean consentToPublish,
        NutritionLabel nutrition, Map<String, LabelAnalysis.Link> analysis) {
        Draft(String clientRequestId, String barcode, String name, String brand, String quantity,
                String ingredients, String ingredientsLanguage, String declaredAllergenStatement, boolean consentToPublish) {
            this(clientRequestId,barcode,name,brand,quantity,ingredients,ingredientsLanguage,declaredAllergenStatement,consentToPublish,null,Map.of());
        }
    }
    record Receipt(String id, String barcode, String status, String submittedAt, String reviewedAt, String note) { }
    record Photo(String role, String url, int width, int height, String sha256) { }
    record Detail(Receipt submission, int version, Draft submitted, List<Photo> photos,
        String currentPublishedSubmissionId, Product reviewedProduct, Map<String, LabelAnalysis.Snapshot> analysis) { }
    record Approval(int version, String name, String brand, String quantity, String ingredients,
        String ingredientsLanguage, String declaredAllergenStatement, String expectedPublishedSubmissionId,
        boolean photosChecked, NutritionLabel nutrition, boolean nutritionPhotoChecked) {
        Approval(int version, String name, String brand, String quantity, String ingredients,
                String ingredientsLanguage, String declaredAllergenStatement, String expectedPublishedSubmissionId, boolean photosChecked) {
            this(version,name,brand,quantity,ingredients,ingredientsLanguage,declaredAllergenStatement,expectedPublishedSubmissionId,photosChecked,null,false);
        }
    }
    record Rejection(int version, String note) { }
    record PublicLabel(Product product, String reviewedAt, List<Photo> photos) { }
}

final class SubmissionException extends RuntimeException {
    final int status;
    final String code;
    SubmissionException(int status, String code) { super(code); this.status = status; this.code = code; }
}
