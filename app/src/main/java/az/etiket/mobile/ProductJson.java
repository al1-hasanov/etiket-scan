package az.etiket.mobile;

import az.etiket.core.Product;
import az.etiket.core.ProductEvidence;
import az.etiket.core.NutritionLabel;
import az.etiket.core.NutritionSource;
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
        String source = o.optString("source");
        var panelSource = nutritionSource(o.optJSONObject("nutritionSource"));
        var label = "OPEN_FOOD_FACTS".equals(source) && panelSource == null
            ? NutritionLabel.empty() : nutrition(o.optJSONObject("nutrition"));
        return new Product(o.getString("barcode"), o.optString("name"), o.optString("brand"),
            o.optString("quantity"), o.optString("ingredients"), o.optString("ingredientsLanguage"),
            o.optString("nutriScore"), nutrients, list(o.optJSONArray("allergens")), list(o.optJSONArray("traces")),
            source, o.optString("sourceUrl"), o.optString("retrievedAt"), o.optString("sourceUpdatedAt"),
            o.optBoolean("demo"), evidence, null, label, panelSource);
    }
    static JSONObject write(Product p) throws JSONException {
        ProductEvidence e = p.evidence();
        JSONObject evidence = new JSONObject().put("reviewStatus", e.reviewStatus()).put("ingredientsStatus", e.ingredientsStatus())
            .put("labelPhotoChecked", e.labelPhotoChecked()).put("ingredientsSourceUrl", e.ingredientsSourceUrl())
            .put("ingredientSectionAsReported", e.ingredientSectionAsReported()).put("declaredAllergenStatement", e.declaredAllergenStatement())
            .put("notes", new JSONArray(e.notes())).put("externalCandidateAvailable", e.externalCandidateAvailable());
        JSONObject product = new JSONObject().put("barcode", p.barcode()).put("name", p.name()).put("brand", p.brand())
            .put("quantity", p.quantity()).put("ingredients", p.ingredients())
            .put("ingredientsLanguage", p.ingredientsLanguage()).put("nutriScore", p.nutriScore())
            .put("nutrients", new JSONObject(p.nutrients())).put("allergens", new JSONArray(p.allergens()))
            .put("traces", new JSONArray(p.traces())).put("source", p.source()).put("sourceUrl", p.sourceUrl())
            .put("retrievedAt", p.retrievedAt()).put("sourceUpdatedAt", p.sourceUpdatedAt()).put("demo", p.demo())
            .put("evidence", evidence).put("nutrition",new JSONObject().put("basis",p.nutrition().basis()).put("values",new JSONObject(p.nutrition().values())));
        var panel = p.nutritionSource();
        if (panel != null) product.put("nutritionSource", new JSONObject().put("kind", panel.kind().name())
            .put("name", panel.name()).put("url", panel.url()).put("observedAt", panel.observedAt()).put("packSize", panel.packSize()));
        return product;
    }
    private static NutritionSource nutritionSource(JSONObject value) {
        if (value == null) return null;
        try {
            return new NutritionSource(NutritionSource.Kind.valueOf(value.optString("kind")),
                value.optString("name"), value.optString("url"), value.optString("observedAt"), value.optString("packSize"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
    static NutritionLabel nutrition(JSONObject value){
        if(value==null)return null;
        Map<String,Double> numbers=new LinkedHashMap<>();JSONObject values=value.optJSONObject("values");
        if(values!=null)for(String key:NutritionLabel.KEYS)if(values.has(key))numbers.put(key,values.optDouble(key,Double.NaN));
        try{return new NutritionLabel(value.optString("basis"),numbers);}catch(RuntimeException e){return NutritionLabel.empty();}
    }
    private static List<String> list(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return result;
    }
}
