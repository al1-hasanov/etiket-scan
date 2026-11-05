package az.etiket.backend;

import az.etiket.core.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:catalogue-api;DB_CLOSE_DELAY=-1", "off.enabled=false",
    "off.base-url=http://127.0.0.1:1"})
class CatalogueApiTest {
    @LocalServerPort int port;
    @Autowired CatalogueStore catalogue;
    @Autowired JdbcTemplate db;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/" + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }
    @Test void catalogueCountsMatchCapturedSample() throws Exception {
        JsonNode summary = json.readTree(get("catalogue/summary").body());
        assertEquals(77, summary.path("products").asInt());
        assertEquals(10, summary.path("withIngredients").asInt());
        assertEquals(67, summary.path("missingIngredients").asInt());
        assertEquals(0, summary.path("labelVerified").asInt());
    }
    @Test void sourcedIngredientsKeepLanguageAndUnverifiedEvidence() throws Exception {
        var result = get("products/4760177560085"); assertEquals(200, result.statusCode());
        JsonNode p = json.readTree(result.body());
        assertEquals("inək südü, bitki yağı, maya, duz, qara çörək otu", p.path("ingredients").asString());
        assertEquals("az", p.path("ingredientsLanguage").asString());
        assertEquals("BAZARSTORE", p.path("source").asString());
        assertEquals("WEB_SOURCED_UNVERIFIED", p.at("/evidence/reviewStatus").asString());
        assertEquals("WEB_TEXT_REQUIRES_REVIEW", p.at("/evidence/ingredientsStatus").asString());
        assertFalse(p.at("/evidence/labelPhotoChecked").asBoolean());
        assertTrue(p.path("nutrients").isEmpty()); assertTrue(p.path("allergens").isEmpty());
        assertEquals("", p.path("nutriScore").asString()); assertFalse(p.path("demo").asBoolean());
        assertEquals("AVAILABLE", p.at("/ingredientAnalysis/status").asString());
        assertEquals(5, p.at("/ingredientAnalysis/recognizedCount").asInt());
        assertEquals("İnək südü", p.at("/ingredientAnalysis/items/0/nameAz").asString());
        assertEquals("EDITORIAL_DRAFT", p.at("/ingredientAnalysis/contentStatus").asString());
    }
    @Test void missingIngredientsRemainAFoundProductAndCandidateStaysSeparate() throws Exception {
        var result = get("products/5201360553457"); assertEquals(200, result.statusCode());
        JsonNode p = json.readTree(result.body());
        assertEquals("", p.path("ingredients").asString());
        assertEquals("MISSING", p.at("/evidence/ingredientsStatus").asString());
        assertTrue(p.at("/evidence/externalCandidateAvailable").asBoolean());
        assertTrue(p.path("allergens").isEmpty()); assertTrue(p.path("traces").isEmpty());
        assertEquals("MISSING", p.at("/ingredientAnalysis/status").asString());
        assertTrue(p.at("/ingredientAnalysis/items").isEmpty());
        assertFalse(result.body().contains("əla növ çörəkbişirmə buğda unu"));
        String raw = db.queryForObject("SELECT source_record FROM catalogue_products WHERE barcode=?", String.class, "5201360553457");
        assertTrue(json.readTree(raw).path("external_ingredient_candidate").isObject());
    }
    @Test void unknownAndInvalidBarcodesHaveDifferentErrorsWithoutExternalCalls() throws Exception {
        var unknown = get("products/3017620422003"); assertEquals(404, unknown.statusCode());
        assertEquals("PRODUCT_NOT_IN_CATALOGUE", json.readTree(unknown.body()).path("code").asString());
        assertEquals(400, get("products/4760177560086").statusCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM product_cache", Integer.class));
    }
    @Test void reimportIsIdempotentAndRetainsImportTimestamp() throws Exception {
        String before = db.queryForObject("SELECT imported_at FROM catalogue_imports WHERE dataset_id=?", String.class, CatalogueStore.DATASET);
        catalogue.importBundledCatalogue();
        assertEquals(before, db.queryForObject("SELECT imported_at FROM catalogue_imports WHERE dataset_id=?", String.class, CatalogueStore.DATASET));
        assertEquals(77, db.queryForObject("SELECT COUNT(*) FROM catalogue_products", Integer.class));
    }
    @Test void paginationHasNoDuplicatesAndRejectsUnboundedRequests() throws Exception {
        JsonNode first = json.readTree(get("catalogue/products?page=0&size=20").body());
        JsonNode second = json.readTree(get("catalogue/products?page=1&size=20").body());
        assertEquals(77, first.path("total").asInt()); assertEquals(20, first.path("items").size());
        assertNotEquals(first.at("/items/0/barcode").asString(), second.at("/items/0/barcode").asString());
        assertEquals(400, get("catalogue/products?size=1000").statusCode());
        assertEquals(400, get("catalogue/products?page=-1").statusCode());
    }
    @Test void everyImportedRecordPreservesItsBarcodeAndEvidence() {
        for (String payload : db.query("SELECT payload FROM catalogue_products", (rs, i) -> rs.getString(1))) {
            Product p = json.readValue(payload, Product.class);
            assertEquals(p.barcode(), Barcode.normalize(p.barcode()));
            assertFalse(p.evidence().labelPhotoChecked());
            assertEquals(p.ingredients().isEmpty() ? "MISSING" : "WEB_TEXT_REQUIRES_REVIEW", p.evidence().ingredientsStatus());
        }
    }
    @Test void everyStarterIngredientListGetsDescriptionsWithoutChangingTheSeed() throws Exception {
        for (String payload : db.query("SELECT payload FROM catalogue_products WHERE has_ingredients=TRUE", (rs, i) -> rs.getString(1))) {
            Product original = json.readValue(payload, Product.class);
            JsonNode response = json.readTree(get("products/" + original.barcode()).body());
            assertEquals(original.ingredients(), response.path("ingredients").asString());
            assertTrue(response.at("/ingredientAnalysis/recognizedCount").asInt() > 0);
            assertEquals("WEB_SOURCED_UNVERIFIED", response.at("/evidence/reviewStatus").asString());
            assertFalse(response.at("/evidence/labelPhotoChecked").asBoolean());
        }
        JsonNode berry = json.readTree(get("products/4606779682100").body());
        assertEquals("PARTIAL", berry.at("/ingredientAnalysis/status").asString());
        assertTrue(berry.path("ingredientAnalysis").path("items").size() > berry.at("/ingredientAnalysis/recognizedCount").asInt());
    }
}
