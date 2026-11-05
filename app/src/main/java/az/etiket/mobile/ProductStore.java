package az.etiket.mobile;

import android.content.SharedPreferences;
import az.etiket.core.Product;
import java.util.*;
import org.json.*;

final class ProductStore {
    private final SharedPreferences prefs;
    ProductStore(SharedPreferences prefs) { this.prefs = prefs; }
    List<Product> list(String key) {
        List<Product> result = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < rows.length(); i++) {
                try { result.add(ProductJson.read(rows.getJSONObject(i))); } catch (JSONException ignored) { }
            }
        } catch (JSONException ignored) { }
        return result;
    }
    boolean contains(String key, Product product) {
        for (Product p : list(key)) if (p.barcode().equals(product.barcode())) return true;
        return false;
    }
    void remember(Product product) { prepend("history", product, 40); }
    void toggleSaved(Product product) {
        List<Product> rows = list("saved");
        boolean existed = rows.removeIf(p -> p.barcode().equals(product.barcode()));
        if (!existed) rows.add(0, product);
        save("saved", rows);
    }
    void prepend(String key, Product product, int limit) {
        List<Product> rows = list(key);
        rows.removeIf(p -> p.barcode().equals(product.barcode())); rows.add(0, product);
        save(key, rows.subList(0, Math.min(limit, rows.size())));
    }
    void clear() { prefs.edit().remove("history").remove("saved").apply(); }
    private void save(String key, List<Product> rows) {
        JSONArray array = new JSONArray();
        try {
            for (Product p : rows) array.put(ProductJson.write(p));
            prefs.edit().putString(key, array.toString()).apply();
        } catch (JSONException e) { throw new IllegalStateException("Cannot save product", e); }
    }
}
