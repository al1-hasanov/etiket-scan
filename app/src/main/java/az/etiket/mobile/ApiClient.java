package az.etiket.mobile;

import az.etiket.core.Product;
import az.etiket.core.ProductEvidence;
import az.etiket.core.Barcode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import java.util.*;

final class ApiClient {
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static String validateBase(String input) {
        String base = input.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(base);
            String scheme = uri.getScheme(), host = uri.getHost();
            if (host == null || !("http".equals(scheme) || "https".equals(scheme)) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getPath().isEmpty()
                || uri.getPort() > 65535) throw new IllegalArgumentException();
            if ("http".equals(scheme) && !localHost(host)) throw new IllegalArgumentException();
            return base;
        } catch (RuntimeException e) { throw new IllegalArgumentException("BASE_URL_INVALID"); }
    }
    private static boolean localHost(String host) {
        if (host.equals("localhost") || host.equals("[::1]")) return true;
        if (!host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        String[] parts = host.split("\\."); int[] ip = new int[4];
        for (int i = 0; i < 4; i++) { ip[i] = Integer.parseInt(parts[i]); if (ip[i] > 255) return false; }
        return ip[0] == 127 || ip[0] == 10 || (ip[0] == 192 && ip[1] == 168)
            || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31);
    }
    static Product lookup(String base, String barcode) throws Failure {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(validateBase(base) + "/api/v1/products/" + barcode).toURL().openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == 404) throw new Failure("PRODUCT_NOT_FOUND");
            if (status == 429) throw new Failure("LOOKUP_RATE_LIMITED");
            if (status == 400) throw new Failure("BARCODE_INVALID");
            if (status != 200) throw new Failure("SOURCE_UNAVAILABLE");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int read;
                while ((read = in.read(buffer)) != -1) {
                    if (bytes.size() + read > 1_000_000) throw new Failure("SOURCE_UNAVAILABLE");
                    bytes.write(buffer, 0, read);
                }
                Product product = readProduct(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())), base);
                if (!product.barcode().equals(barcode)) throw new Failure("SOURCE_UNAVAILABLE");
                return product;
            }
        } catch (Failure e) { throw e;
        } catch (org.json.JSONException e) { throw new Failure("SOURCE_UNAVAILABLE");
        } catch (Exception e) { throw new Failure("CONNECTION_FAILED");
        } finally { if (connection != null) connection.disconnect(); }
    }
    static List<Product> catalogue(String base) throws Failure {
        List<Product> result = new ArrayList<>(); Set<String> codes = new HashSet<>();
        for (int page = 0; page < 20; page++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(validateBase(base) + "/api/v1/catalogue/products?size=100&page=" + page).toURL().openConnection();
                connection.setConnectTimeout(5000); connection.setReadTimeout(12000); connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                if (connection.getResponseCode() != 200) throw new Failure("SOURCE_UNAVAILABLE");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = in.read(buffer)) != -1) { if (bytes.size() + n > 3_000_000) throw new Failure("SOURCE_UNAVAILABLE"); bytes.write(buffer, 0, n); }
                }
                JSONObject data = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                JSONArray items = data.getJSONArray("items"); int total = data.getInt("total");
                if (total < 0 || total > 2000 || items.length() > 100 || data.optInt("page", -1) != page) throw new Failure("SOURCE_UNAVAILABLE");
                for (int i = 0; i < items.length(); i++) {
                    Product product = readProduct(items.getJSONObject(i), base);
                    if (!product.barcode().equals(Barcode.normalize(product.barcode())) || !codes.add(product.barcode())) throw new Failure("SOURCE_UNAVAILABLE");
                    result.add(product);
                }
                if (result.size() == total) return List.copyOf(result);
                if (items.length() == 0 || result.size() > total) throw new Failure("SOURCE_UNAVAILABLE");
            } catch (Failure e) { throw e;
            } catch (Exception e) { throw new Failure("CONNECTION_FAILED");
            } finally { if (connection != null) connection.disconnect(); }
        }
        throw new Failure("SOURCE_UNAVAILABLE");
    }
    private static Product readProduct(JSONObject data, String base) throws JSONException {
        Product p = ProductJson.read(data);
        if (!"REVIEWED_LABEL".equals(p.source())) return p;
        ProductEvidence e = p.evidence();
        var evidence = new ProductEvidence(e.reviewStatus(), e.ingredientsStatus(), e.labelPhotoChecked(), resolveSource(base, e.ingredientsSourceUrl()),
            e.ingredientSectionAsReported(), e.declaredAllergenStatement(), e.notes(), e.externalCandidateAvailable());
        return new Product(p.barcode(), p.name(), p.brand(), p.quantity(), p.ingredients(), p.ingredientsLanguage(), p.nutriScore(),
            p.nutrients(), p.allergens(), p.traces(), p.source(), resolveSource(base, p.sourceUrl()), p.retrievedAt(), p.sourceUpdatedAt(), p.demo(), evidence);
    }
    static String resolveSource(String base, String source) {
        if (source == null || source.isEmpty()) return "";
        if (source.startsWith("/") && !source.startsWith("//")) return validateBase(base) + source;
        URI uri = URI.create(source);
        if (uri.getUserInfo() != null || uri.getHost() == null) throw new IllegalArgumentException("SOURCE_URL_INVALID");
        if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && localHost(uri.getHost())) return source;
        throw new IllegalArgumentException("SOURCE_URL_INVALID");
    }
}
