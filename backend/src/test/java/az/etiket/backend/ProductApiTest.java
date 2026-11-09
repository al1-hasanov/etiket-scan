package az.etiket.backend;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import az.etiket.core.NutritionLabel;
import az.etiket.core.NutritionSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP -> controller -> cache -> stub provider. No calls to production OFF. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiTest {
    static final AtomicInteger calls = new AtomicInteger();
    static volatile int sourceStatus = 200;
    static volatile String sourceBody;
    static final HttpServer source;
    static {
        try {
            source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            source.createContext("/", exchange -> {
                calls.incrementAndGet();
                byte[] bytes = sourceBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(sourceStatus, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            }); source.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("off.base-url", () -> "http://127.0.0.1:" + source.getAddress().getPort());
        r.add("off.min-interval-millis", () -> "0");
        r.add("off.enabled", () -> "true");
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:apitest;DB_CLOSE_DELAY=-1");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired NutritionFeeds feeds;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    @BeforeEach void reset() {
        db.update("DELETE FROM product_cache");
        db.update("DELETE FROM nutrition_reports");
        calls.set(0); sourceStatus = 200;
        sourceBody = """
            {"product":{"code":"3017620422003","product_name":"Test food","brands":"Fixture",
            "quantity":"100 g","lang":"en","ingredients_text":"Oats, salt", "nutriscore_grade":"b",
            "nutrition":{"input_sets":[{"per":"100g","per_quantity":100,"per_unit":"g","preparation":"as_sold","source":"packaging",
            "nutrients":{"energy-kcal":{"value":321,"unit":"kcal"},"salt":{"value":0,"unit":"g"},"proteins":{"value":5.5,"unit":"g"}}}]},
            "allergens_tags":["en:gluten"],"traces_tags":["en:milk"],"last_modified_t":1735689600}}
            """;
    }
    @AfterAll static void stopProvider() { source.stop(0); }
    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/" + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }
    @Test void healthAndDemoDoNotContactProvider() throws Exception {
        assertEquals(200, get("health").statusCode());
        assertEquals(3, json.readTree(get("products/demo").body()).size());
        assertTrue(json.readTree(get("products/2000000000015").body()).path("demo").asBoolean());
        assertEquals(0, calls.get());
    }
    @Test void invalidCodeRejectedBeforeProvider() throws Exception {
        assertEquals(400, get("products/3017620422004").statusCode()); assertEquals(0, calls.get());
    }
    @Test void starterCatalogueSurvivesAnUnavailableProvider() throws Exception {
        sourceStatus = 503;
        JsonNode p = json.readTree(get("products/4760177560085").body());
        assertEquals("BAZARSTORE", p.path("source").asString());
        assertTrue(p.at("/nutrition/values").isEmpty());
        assertEquals(1, calls.get());
    }
    @Test void knownBarcodeUsesMatchedPanelAndKeepsLocalProductAndBasis() throws Exception {
        sourceBody = providerPanel("4760095105863", "1000 ml", "azerbaijan", "ml");
        var response = get("products/4760095105863");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode p = json.readTree(response.body());
        assertEquals("BAZARSTORE", p.path("source").asString());
        assertTrue(p.path("name").asString().contains("KEFİR"));
        assertEquals("OPEN_FOOD_FACTS", p.at("/nutritionSource/kind").asString());
        assertEquals("PER_100_ML", p.at("/nutrition/basis").asString());
        assertEquals(0d, p.at("/nutrition/values/salt").asDouble(-1));
        assertTrue(p.path("nutrients").isEmpty());
        assertFalse(p.at("/evidence/labelPhotoChecked").asBoolean());
        sourceStatus = 503;
        assertEquals(p, json.readTree(get("products/4760095105863").body()));
        assertEquals(1, calls.get());
    }
    @Test void foreignMarketAndDifferentPackDoNotFillLocalNutrition() throws Exception {
        sourceBody = providerPanel("4760177560085", "400 g", "germany", "g");
        assertTrue(json.readTree(get("products/4760177560085").body()).at("/nutrition/values").isEmpty());
        db.update("DELETE FROM product_cache");
        sourceBody = providerPanel("4760177560085", "350 g", "azerbaijan", "g");
        assertTrue(json.readTree(get("products/4760177560085").body()).at("/nutrition/values").isEmpty());
        assertEquals(2, calls.get());
    }
    @Test void cachedProviderDisagreementRemainsFlaggedAfterCacheExpiry() throws Exception {
        String barcode = "4760018505107";
        sourceBody = providerPanel(barcode, "400 g", "germany", "g");
        assertEquals(200, get("products/" + barcode).statusCode());
        var imported = new NutritionFeeds.Feed("retailer-test", NutritionSource.Kind.RETAILER_REPORTED,
            "Test retailer", "2025-01-01T00:00:00Z", List.of(new NutritionFeeds.Input(barcode, "400 g", "https://example.test/jam",
            new NutritionLabel("PER_100_G", Map.of("sugars", 60d)))));
        assertEquals("NUTRITION_DISAGREEMENT", feeds.importFeed(imported, false).conflicts().getFirst().reason());
        db.update("UPDATE product_cache SET stored_at=0");
        var product = json.readTree(get("products/" + barcode).body());
        assertEquals("CONFLICT", product.at("/nutritionSource/kind").asString());
        assertTrue(product.at("/nutrition/values").isEmpty());
        assertEquals(1, calls.get());
    }
    @Test void realLookupPreservesProvenanceAndZero() throws Exception {
        var response = get("products/3017620422003"); assertEquals(200, response.statusCode());
        JsonNode product = json.readTree(response.body());
        assertEquals(321d, product.path("nutrients").path("energyKcal").doubleValue());
        assertEquals(0d, product.path("nutrients").path("salt").doubleValue());
        assertFalse(product.path("nutrients").has("sugars"));
        assertEquals("OPEN_FOOD_FACTS", product.path("source").asString());
        assertFalse(product.path("demo").asBoolean());
        assertEquals("en:milk", product.path("traces").get(0).asString());
        assertTrue(product.path("sourceUrl").asString().endsWith("3017620422003"));
    }
    @Test void repeatLookupUsesPersistentCache() throws Exception {
        assertEquals(200, get("products/3017620422003").statusCode());
        sourceStatus = 500;
        assertEquals(200, get("products/3017620422003").statusCode());
        assertEquals(1, calls.get());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM product_cache", Integer.class));
    }
    @Test void expiredCacheIsRefreshed() throws Exception {
        get("products/3017620422003");
        db.update("UPDATE product_cache SET stored_at=0");
        assertEquals(200, get("products/3017620422003").statusCode()); assertEquals(2, calls.get());
    }
    @Test void missingProductIsNotFabricatedOrCached() throws Exception {
        sourceStatus = 404; sourceBody = "{\"status\":\"failure\"}";
        assertEquals(404, get("products/3017620422003").statusCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM product_cache", Integer.class));
    }
    @Test void providerFailureDoesNotBecomeMissingProduct() throws Exception {
        sourceStatus = 503;
        assertEquals(503, get("products/3017620422003").statusCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM product_cache", Integer.class));
    }
    @Test void malformedJsonAndMismatchedProductAreRejected() throws Exception {
        sourceBody = "not-json";
        assertEquals(503, get("products/3017620422003").statusCode());
        sourceBody = "{\"product\":{\"code\":\"2000000000015\"}}";
        assertEquals(503, get("products/3017620422003").statusCode());
    }
    @Test void incompleteDataStaysUnknown() throws Exception {
        sourceBody = """
            {"product":{"code":"3017620422003","nutriscore_grade":"unknown",
             "nutrition":{"input_sets":[
                {"per":"100g","per_quantity":100,"per_unit":"g","preparation":"as_sold","source":"estimate",
                 "nutrients":{"salt":{"value":0,"unit":"g"}}},
                {"per":"serving","per_quantity":100,"per_unit":"g","preparation":"as_sold","source":"packaging",
                 "nutrients":{"sugars":{"value":6,"unit":"g"}}},
                {"per":"100g","per_quantity":100,"per_unit":"g","preparation":"as_sold","source":"packaging",
                 "nutrients":{"salt":{"value_computed":0,"unit":"g"},"sugars":{"value":1,"modifier":"<","unit":"g"}}}
             ]}}}
            """;
        JsonNode p = json.readTree(get("products/3017620422003").body());
        assertTrue(p.path("nutrients").isEmpty()); assertEquals("", p.path("nutriScore").asString());
        assertEquals("", p.path("ingredients").asString());
    }
    @Test void providerQuotaIsEnforcedBeforeNetwork() {
        OpenFoodFactsClient limited = new OpenFoodFactsClient("http://127.0.0.1:" + source.getAddress().getPort(), "EtiketTest/0.1", 3, 5000);
        limited.fetch("3017620422003");
        LookupException e = assertThrows(LookupException.class, () -> limited.fetch("3017620422003"));
        assertEquals(429, e.status); assertEquals(1, calls.get());
    }
    private static String providerPanel(String code, String pack, String market, String unit) {
        return """
            {"product":{"code":"%s","product_name":"External example","quantity":"%s","countries_tags":["en:%s"],
            "nutrition":{"input_sets":[{"per":"100%s","per_quantity":100,"per_unit":"%s","preparation":"as_sold","source":"manufacturer",
             "nutrients":{"energy-kcal":{"value":272,"unit":"kcal"},"salt":{"value":0,"unit":"g"},"sugars":{"value":68,"unit":"g"}}}]}}}
            """.formatted(code, pack, market, unit, unit);
    }
}
