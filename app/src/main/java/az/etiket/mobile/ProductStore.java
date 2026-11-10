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
    /** Small, server-scoped fallback for products discovered online, not part of the seed list. */
    void cacheLiveLookup(String base, Product product) {
        try {
            JSONArray saved = base.equals(prefs.getString("liveLookupBase", ""))
                ? new JSONArray(prefs.getString("liveLookups", "[]")) : new JSONArray();
            JSONArray updated = new JSONArray();
            JSONObject newest = new JSONObject();
            newest.put("barcode", product.barcode());
            newest.put("cachedAt", System.currentTimeMillis());
            newest.put("product", ProductJson.write(product));
            updated.put(newest);
            for (int i = 0; i < saved.length() && updated.length() < 40; i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null || product.barcode().equals(item.optString("barcode"))) continue;
                if (System.currentTimeMillis() - item.optLong("cachedAt", 0) < 7L * 86_400_000L)
                    updated.put(item);
            }
            prefs.edit().putString("liveLookupBase", base).putString("liveLookups", updated.toString()).apply();
        } catch (JSONException ignored) {
            // A corrupt local cache must never prevent the live result from opening.
        }
    }
    Optional<Product> cachedLiveLookup(String base, String barcode) {
        if (!base.equals(prefs.getString("liveLookupBase", ""))) return Optional.empty();
        try {
            JSONArray rows = new JSONArray(prefs.getString("liveLookups", "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || !barcode.equals(row.optString("barcode"))) continue;
                long age = now - row.optLong("cachedAt", 0);
                if (age < 0 || age > 7L * 86_400_000L) return Optional.empty();
                Product product = ProductJson.read(row.getJSONObject("product"));
                if (barcode.equals(product.barcode()) && !product.demo()) return Optional.of(product);
            }
        } catch (JSONException ignored) { }
        return Optional.empty();
    }
    List<Product> cachedCatalogue(String base) { return base.equals(prefs.getString("catalogueBase", "")) ? list("catalogue") : List.of(); }
    void cacheCatalogue(String base, List<Product> products) { save("catalogue", products); prefs.edit().putString("catalogueBase", base).apply(); }
    void clear() { prefs.edit().remove("history").remove("saved").remove("catalogue").remove("catalogueBase").remove("submissions").remove("liveLookups").remove("liveLookupBase").apply(); }
    private void save(String key, List<Product> rows) {
        JSONArray array = new JSONArray();
        try {
            for (Product p : rows) array.put(ProductJson.write(p));
            prefs.edit().putString(key, array.toString()).apply();
        } catch (JSONException e) { throw new IllegalStateException("Cannot save product", e); }
    }
}
