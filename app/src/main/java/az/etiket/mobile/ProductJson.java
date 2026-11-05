package az.etiket.mobile;

import az.etiket.core.Product;
import az.etiket.core.ProductEvidence;
import java.util.*;
import org.json.*;

final class ProductJson {
    private ProductJson() { }
    static Product read(JSONObject o) throws JSONException {
        Map<String, Double> nutrients = new LinkedHashMap<>();
        JSONObject n = o.optJSONObject("nutrients");
        if (n != null) for (Iterator<String> it = n.keys(); it.hasNext();) {
            String key = it.next(); double value = n.optDouble(key, Double.NaN);
            if (Double.isFinite(value) && value >= 0) nutrients.put(key, value);
        }
        JSONObject e = o.optJSONObject("evidence");
        ProductEvidence evidence = e == null ? null : new ProductEvidence(e.optString("reviewStatus"),
            e.optString("ingredientsStatus"), e.optBoolean("labelPhotoChecked"), e.optString("ingredientsSourceUrl"),
            e.optString("ingredientSectionAsReported"), e.optString("declaredAllergenStatement"),
            list(e.optJSONArray("notes")), e.optBoolean("externalCandidateAvailable"));
        return new Product(o.getString("barcode"), o.optString("name"), o.optString("brand"),
            o.optString("quantity"), o.optString("ingredients"), o.optString("ingredientsLanguage"),
            o.optString("nutriScore"), nutrients, list(o.optJSONArray("allergens")), list(o.optJSONArray("traces")),
            o.optString("source"), o.optString("sourceUrl"), o.optString("retrievedAt"), o.optString("sourceUpdatedAt"),
            o.optBoolean("demo"), evidence);
    }
    static JSONObject write(Product p) throws JSONException {
        ProductEvidence e = p.evidence();
        JSONObject evidence = new JSONObject().put("reviewStatus", e.reviewStatus()).put("ingredientsStatus", e.ingredientsStatus())
            .put("labelPhotoChecked", e.labelPhotoChecked()).put("ingredientsSourceUrl", e.ingredientsSourceUrl())
            .put("ingredientSectionAsReported", e.ingredientSectionAsReported()).put("declaredAllergenStatement", e.declaredAllergenStatement())
            .put("notes", new JSONArray(e.notes())).put("externalCandidateAvailable", e.externalCandidateAvailable());
        return new JSONObject().put("barcode", p.barcode()).put("name", p.name()).put("brand", p.brand())
            .put("quantity", p.quantity()).put("ingredients", p.ingredients())
            .put("ingredientsLanguage", p.ingredientsLanguage()).put("nutriScore", p.nutriScore())
            .put("nutrients", new JSONObject(p.nutrients())).put("allergens", new JSONArray(p.allergens()))
            .put("traces", new JSONArray(p.traces())).put("source", p.source()).put("sourceUrl", p.sourceUrl())
            .put("retrievedAt", p.retrievedAt()).put("sourceUpdatedAt", p.sourceUpdatedAt()).put("demo", p.demo())
            .put("evidence", evidence);
    }
    private static List<String> list(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return result;
    }
}
