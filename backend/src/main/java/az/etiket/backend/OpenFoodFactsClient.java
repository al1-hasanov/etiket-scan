package az.etiket.backend;

import az.etiket.core.Product;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
final class OpenFoodFactsClient {
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String baseUrl, userAgent;
    private final Duration timeout;
    private final long intervalNanos;
    // Serializes upstream calls and caps them below OFF's 15 reads/minute/IP limit.
    private long nextAllowedAt;
    OpenFoodFactsClient(@Value("${off.base-url}") String baseUrl,
            @Value("${off.user-agent}") String userAgent,
            @Value("${off.timeout-seconds}") long timeout,
            @Value("${off.min-interval-millis:5000}") long intervalMillis) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.userAgent = userAgent;
        this.timeout = Duration.ofSeconds(timeout);
        this.intervalNanos = Duration.ofMillis(intervalMillis).toNanos();
        http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }
    synchronized Product fetch(String barcode) {
        long now = System.nanoTime();
        if (nextAllowedAt != 0 && now - nextAllowedAt < 0) throw new LookupException(429, "LOOKUP_RATE_LIMITED");
        nextAllowedAt = now + intervalNanos;
        String fields = "code,product_name,product_name_en,brands,quantity,ingredients_text,lang,"
            + "nutriscore_grade,nutriments,allergens_tags,traces_tags,last_modified_t";
        // Pin the v3.4 nutrition contract; v3.5+ restructures nutrition and is still evolving.
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v3.4/product/" + barcode
            + ".json?fields=" + fields)).timeout(timeout).header("User-Agent", userAgent).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw new LookupException(404, "PRODUCT_NOT_FOUND");
            if (response.statusCode() == 429) throw new LookupException(503, "SOURCE_RATE_LIMITED");
            if (response.statusCode() != 200) throw new LookupException(503, "SOURCE_UNAVAILABLE");
            JsonNode root = json.readTree(response.body());
            JsonNode product = root.path("product");
            if (!product.isObject()) throw new LookupException(404, "PRODUCT_NOT_FOUND");
            if (!barcode.equals(az.etiket.core.Barcode.normalize(text(product, "code"))))
                throw new LookupException(503, "SOURCE_UNAVAILABLE");
            Map<String, Double> nutrients = new LinkedHashMap<>();
            JsonNode raw = product.path("nutriments");
            Map<String, String> keys = Map.of("energyKcal", "energy-kcal_100g", "fat", "fat_100g",
                "saturatedFat", "saturated-fat_100g", "carbohydrates", "carbohydrates_100g",
                "sugars", "sugars_100g", "fiber", "fiber_100g", "protein", "proteins_100g", "salt", "salt_100g");
            keys.forEach((key, offKey) -> {
                JsonNode node = raw.path(offKey);
                if (node.isNumber()) {
                    double value = node.doubleValue();
                    if (Double.isFinite(value) && value >= 0) nutrients.put(key, value);
                }
            });
            String grade = text(product, "nutriscore_grade").toLowerCase(Locale.ROOT);
            if (!grade.matches("[a-e]")) grade = "";
            String name = text(product, "product_name");
            if (name.isBlank()) name = text(product, "product_name_en");
            if (name.isBlank()) name = barcode;
            long modified = product.path("last_modified_t").asLong(0);
            return new Product(barcode, name, text(product, "brands"), text(product, "quantity"),
                text(product, "ingredients_text"), text(product, "lang"), grade, nutrients,
                tags(product.path("allergens_tags")), tags(product.path("traces_tags")),
                "OPEN_FOOD_FACTS", "https://world.openfoodfacts.org/product/" + barcode,
                Instant.now().toString(), modified > 0 ? Instant.ofEpochSecond(modified).toString() : "", false);
        } catch (LookupException e) { throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new LookupException(503, "SOURCE_UNAVAILABLE");
        } catch (Exception e) { throw new LookupException(503, "SOURCE_UNAVAILABLE"); }
    }
    private static String text(JsonNode node, String key) { return node.path(key).asString(""); }
    private static List<String> tags(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> { if (value.isString()) result.add(value.asString()); });
        return result;
    }
}
