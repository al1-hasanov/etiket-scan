package az.etiket.backend;

import az.etiket.core.Barcode;
import az.etiket.core.NutritionLabel;
import az.etiket.core.NutritionSource;
import az.etiket.core.Product;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final int MAX_PROVIDER_BYTES = 1_500_000;
    private final long intervalNanos, cooldownNanos;
    // Local quota guard. Multi-replica deployments also need a shared egress/IP rate limiter.
    private long nextAllowedAt, cooldownUntil;

    @Autowired
    OpenFoodFactsClient(@Value("${off.base-url}") String baseUrl,
            @Value("${off.user-agent}") String userAgent,
            @Value("${off.timeout-seconds}") long timeout,
            @Value("${off.min-interval-millis:5000}") long intervalMillis,
            @Value("${off.cooldown-seconds:15}") long cooldownSeconds) {
        if (timeout < 1 || intervalMillis < 0 || cooldownSeconds < 0)
            throw new IllegalArgumentException("Invalid OFF connection settings");
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.userAgent = userAgent;
        this.timeout = Duration.ofSeconds(timeout);
        this.intervalNanos = Duration.ofMillis(intervalMillis).toNanos();
        this.cooldownNanos = Duration.ofSeconds(cooldownSeconds).toNanos();
        http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }
    // Keeps direct test fixtures compatible.
    OpenFoodFactsClient(String baseUrl, String userAgent, long timeout, long intervalMillis) {
        this(baseUrl, userAgent, timeout, intervalMillis, 0);
    }
    synchronized Lookup fetch(String barcode) {
        long now = System.nanoTime();
        long cooldownRemaining = cooldownUntil - now;
        if (cooldownUntil != 0 && cooldownRemaining > 0)
            throw new LookupException(503, "SOURCE_RATE_LIMITED", seconds(cooldownRemaining));
        long remaining = nextAllowedAt - now;
        if (nextAllowedAt != 0 && remaining > 0)
            throw new LookupException(429, "LOOKUP_RATE_LIMITED", seconds(remaining));
        nextAllowedAt = now + intervalNanos;
        String fields = "code,product_name,product_name_en,product_name_az,brands,quantity,"
            + "ingredients_text,ingredients_text_az,lang,nutriscore_grade,nutrition,"
            + "allergens_tags,traces_tags,countries_tags,last_modified_t";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v3.6/product/" + barcode
            + ".json?fields=" + fields)).timeout(timeout).header("User-Agent", userAgent)
            .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() == 404) throw new LookupException(404, "PRODUCT_NOT_FOUND");
                if (response.statusCode() == 429) {
                    int wait = retryAfter(response.headers().firstValue("Retry-After").orElse(""));
                    pauseFor(wait);
                    throw new LookupException(503, "SOURCE_RATE_LIMITED", wait);
                }
                if (response.statusCode() >= 500) {
                    pauseFor(0);
                    throw new LookupException(503, "SOURCE_UNAVAILABLE");
                }
                if (response.statusCode() != 200) throw new LookupException(503, "SOURCE_UNAVAILABLE");
                byte[] bytes = in.readNBytes(MAX_PROVIDER_BYTES + 1);
                if (bytes.length > MAX_PROVIDER_BYTES) throw new LookupException(503, "SOURCE_UNAVAILABLE");
                JsonNode root = json.readTree(bytes);
                String status = root.path("status").asString("");
                if (status.equals("failure")) {
                    String id = root.path("result").path("id").asString("");
                    if (id.equals("product_not_found") || id.equals("product_found_with_a_different_product_type"))
                        throw new LookupException(404, "PRODUCT_NOT_FOUND");
                    throw new LookupException(503, "SOURCE_UNAVAILABLE");
                }
                if (!status.isEmpty() && !status.equals("success"))
                    throw new LookupException(503, "SOURCE_UNAVAILABLE");
                JsonNode product = root.path("product");
                if (!product.isObject()) throw new LookupException(503, "SOURCE_UNAVAILABLE");
                if (!barcode.equals(Barcode.normalize(text(product, "code"))))
                    throw new LookupException(503, "SOURCE_UNAVAILABLE");
                var panels = panels(product.path("nutrition").path("input_sets"));
                boolean conflict = panels.stream().anyMatch(left -> panels.stream().anyMatch(left::disagreesWith));
                var nutrition = conflict ? NutritionLabel.empty() : panels.stream()
                    .max(Comparator.comparingInt(panel -> panel.values().size())).orElseGet(NutritionLabel::empty);
                String grade = text(product, "nutriscore_grade").toLowerCase(Locale.ROOT);
                if (!grade.matches("[a-e]")) grade = "";
                String name = text(product, "product_name_az");
                if (name.isBlank()) name = text(product, "product_name");
                if (name.isBlank()) name = text(product, "product_name_en");
                if (name.isBlank()) name = barcode;
                String ingredients = text(product, "ingredients_text_az");
                String lang = "az";
                if (ingredients.isBlank()) { ingredients = text(product, "ingredients_text"); lang = text(product, "lang"); }
                long modified = product.path("last_modified_t").asLong(0);
                var result = new Product(barcode, name, text(product, "brands"), text(product, "quantity"),
                    ingredients, lang, grade, Map.of(),
                    tags(product.path("allergens_tags")), tags(product.path("traces_tags")),
                    "OPEN_FOOD_FACTS", "https://world.openfoodfacts.org/product/" + barcode,
                    Instant.now().toString(), modified > 0 ? Instant.ofEpochSecond(modified).toString() : "", false,
                    null, null, nutrition, conflict ? NutritionSource.conflict() : null);
                return new Lookup(result, tags(product.path("countries_tags")).contains("en:azerbaijan"));
            }
        } catch (LookupException e) { throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new LookupException(503, "SOURCE_UNAVAILABLE");
        } catch (Exception e) { throw new LookupException(503, "SOURCE_UNAVAILABLE"); }
    }

    private static int seconds(long nanos) { return (int) Math.max(1, Math.min(300, (nanos + 999_999_999L) / 1_000_000_000L)); }
    private static int retryAfter(String text) {
        try { return (int) Math.max(1, Math.min(300, Long.parseLong(text.trim()))); }
        catch (NumberFormatException ignored) { return 60; }
    }
    private void pauseFor(int seconds) {
        long pause = Math.max(cooldownNanos, Duration.ofSeconds(seconds).toNanos());
        if (pause > 0) cooldownUntil = System.nanoTime() + pause;
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
