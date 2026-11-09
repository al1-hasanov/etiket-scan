package az.etiket.backend;

import az.etiket.core.Barcode;
import az.etiket.core.NutritionLabel;
import az.etiket.core.NutritionSource;
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
    record Lookup(Product product, boolean azerbaijanMarket) { }

    private static final Map<String, String> NUTRIENT_KEYS = Map.of(
        "energyKcal", "energy-kcal", "fat", "fat", "saturatedFat", "saturated-fat",
        "carbohydrates", "carbohydrates", "sugars", "sugars", "fiber", "fiber",
        "protein", "proteins", "salt", "salt");

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
    synchronized Lookup fetch(String barcode) {
        long now = System.nanoTime();
        if (nextAllowedAt != 0 && now - nextAllowedAt < 0) throw new LookupException(429, "LOOKUP_RATE_LIMITED");
        nextAllowedAt = now + intervalNanos;
        String fields = "code,product_name,product_name_en,brands,quantity,ingredients_text,lang,"
            + "nutriscore_grade,nutrition,allergens_tags,traces_tags,countries_tags,last_modified_t";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v3.6/product/" + barcode
            + ".json?fields=" + fields)).timeout(timeout).header("User-Agent", userAgent).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw new LookupException(404, "PRODUCT_NOT_FOUND");
            if (response.statusCode() == 429) throw new LookupException(503, "SOURCE_RATE_LIMITED");
            if (response.statusCode() != 200) throw new LookupException(503, "SOURCE_UNAVAILABLE");
            JsonNode root = json.readTree(response.body());
            JsonNode product = root.path("product");
            if (!product.isObject()) throw new LookupException(404, "PRODUCT_NOT_FOUND");
            if (!barcode.equals(Barcode.normalize(text(product, "code"))))
                throw new LookupException(503, "SOURCE_UNAVAILABLE");
            var panels = panels(product.path("nutrition").path("input_sets"));
            boolean conflict = panels.stream().anyMatch(left -> panels.stream().anyMatch(left::disagreesWith));
            var nutrition = conflict ? NutritionLabel.empty() : panels.stream()
                .max(Comparator.comparingInt(panel -> panel.values().size())).orElseGet(NutritionLabel::empty);
            String grade = text(product, "nutriscore_grade").toLowerCase(Locale.ROOT);
            if (!grade.matches("[a-e]")) grade = "";
            String name = text(product, "product_name");
            if (name.isBlank()) name = text(product, "product_name_en");
            if (name.isBlank()) name = barcode;
            long modified = product.path("last_modified_t").asLong(0);
            var result = new Product(barcode, name, text(product, "brands"), text(product, "quantity"),
                text(product, "ingredients_text"), text(product, "lang"), grade, Map.of(),
                tags(product.path("allergens_tags")), tags(product.path("traces_tags")),
                "OPEN_FOOD_FACTS", "https://world.openfoodfacts.org/product/" + barcode,
                Instant.now().toString(), modified > 0 ? Instant.ofEpochSecond(modified).toString() : "", false,
                null, null, nutrition, conflict ? NutritionSource.conflict() : null);
            return new Lookup(result, tags(product.path("countries_tags")).contains("en:azerbaijan"));
        } catch (LookupException e) { throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new LookupException(503, "SOURCE_UNAVAILABLE");
        } catch (Exception e) { throw new LookupException(503, "SOURCE_UNAVAILABLE"); }
    }

    private static List<NutritionLabel> panels(JsonNode inputs) {
        List<NutritionLabel> result = new ArrayList<>();
        if (!inputs.isArray()) return result;
        for (JsonNode input : inputs) {
            String source = text(input, "source");
            String per = text(input, "per");
            String unit = text(input, "per_unit");
            if ((!source.equals("packaging") && !source.equals("manufacturer"))
                    || !"as_sold".equals(text(input, "preparation"))
                    || input.path("per_quantity").asDouble(-1) != 100
                    || !per.equals("100" + unit) || !Set.of("g", "ml").contains(unit)) continue;
            Map<String, Double> values = new LinkedHashMap<>();
            JsonNode nutrients = input.path("nutrients");
            for (var entry : NUTRIENT_KEYS.entrySet()) {
                String key = entry.getKey();
                JsonNode nutrient = nutrients.path(entry.getValue());
                JsonNode amount = nutrient.path("value");
                if (!amount.isNumber() || !text(nutrient, "modifier").isEmpty()) continue;
                double number = amount.doubleValue();
                String reportedUnit = text(nutrient, "unit");
                if (key.equals("energyKcal") ? reportedUnit.equals("kcal") : reportedUnit.equals("g"))
                    values.put(key, number);
                else if (!key.equals("energyKcal") && reportedUnit.equals("mg"))
                    values.put(key, number / 1000);
            }
            if (values.isEmpty()) continue;
            try {
                result.add(new NutritionLabel(unit.equals("g") ? "PER_100_G" : "PER_100_ML", values));
            } catch (IllegalArgumentException ignored) {
                // Keep other input panels available when a source panel fails validation.
            }
        }
        return result;
    }

    private static String text(JsonNode node, String key) { return node.path(key).asString(""); }
    private static List<String> tags(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> { if (value.isString()) result.add(value.asString()); });
        return result;
    }
}
