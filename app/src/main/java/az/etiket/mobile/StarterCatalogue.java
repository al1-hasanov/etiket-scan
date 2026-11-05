package az.etiket.mobile;

import android.content.Context;
import az.etiket.core.Product;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Read-only bundled snapshot, generated from exactly the same seed as the backend. */
final class StarterCatalogue {
    static List<Product> load(Context context) {
        try (InputStream in = context.getAssets().open("starter-catalogue.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int read;
            while ((read = in.read(buffer)) != -1) bytes.write(buffer, 0, read);
            JSONObject root = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (root.getInt("schemaVersion") != 1) throw new IOException("Unknown catalogue schema");
            JSONArray records = root.getJSONArray("records");
            List<Product> products = new ArrayList<>();
            for (int i = 0; i < records.length(); i++) products.add(ProductJson.read(records.getJSONObject(i).getJSONObject("product")));
            return List.copyOf(products);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Cannot load bundled test catalogue", e);
        }
    }
}
