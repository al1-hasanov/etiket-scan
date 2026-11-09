package az.etiket.mobile;

import az.etiket.core.Product;
import az.etiket.core.NutritionLabel;
import az.etiket.core.NutritionSource;
import java.net.InetAddress;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

/** Actual HTTP and Android JSON parsing against a controlled API response. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ApiClientTest {
    MockWebServer server;
    int status = 200;
    String body;
    Product expected;
    String base;

    @Before public void startApi() throws Exception {
        expected = StarterCatalogue.load(RuntimeEnvironment.getApplication()).stream()
            .filter(p -> p.barcode().equals("4760177560085")).findFirst().orElseThrow();
        body = ProductJson.write(expected).toString();
        server = new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0); base = server.url("/").toString();
    }
    @After public void stopApi() throws Exception { if (server != null) server.shutdown(); }
    @Test public void liveLookupPreservesAzerbaijaniAndReviewEvidence() throws Exception {
        assertEquals(expected, lookup());
    }
    @Test public void nutritionSourceSurvivesWireAndDeviceCache() throws Exception {
        var source = new NutritionSource(NutritionSource.Kind.RETAILER_REPORTED, "Retailer",
            "https://example.test/report", "2025-01-01T00:00:00Z", "400 g");
        var panel = expected.withNutrition(new NutritionLabel("PER_100_ML", Map.of("salt", 0d, "energyKcal", 22d)), source);
        body = ProductJson.write(panel).toString();
        Product loaded = lookup();
        assertEquals(panel, loaded);
        assertEquals(panel, ProductJson.read(ProductJson.write(loaded)));
        assertTrue(loaded.nutrients().isEmpty());
        assertFalse(loaded.evidence().labelPhotoChecked());
    }
    @Test public void anOlderApiWithoutAnalysisStillGetsOfflineDictionaryDescriptions() throws Exception {
        assertFalse(body.contains("ingredientAnalysis"));
        Product loaded = lookup();
        assertEquals("AVAILABLE", loaded.ingredientAnalysis().status());
        assertEquals(5, loaded.ingredientAnalysis().recognizedCount());
        assertFalse(loaded.evidence().labelPhotoChecked());
    }
    @Test public void missingAndUnavailableAreDifferentFailures() throws Exception {
        status = 404; assertFailure("PRODUCT_NOT_FOUND");
        status = 503; assertFailure("SOURCE_UNAVAILABLE");
        status = 429; assertFailure("LOOKUP_RATE_LIMITED");
    }
    @Test public void malformedOrMismatchedResponseIsASourceFailure() throws Exception {
        body = "not-json"; assertFailure("SOURCE_UNAVAILABLE");
        body = ProductJson.write(expected).put("barcode", "5201360553457").toString();
        assertFailure("SOURCE_UNAVAILABLE");
    }
    private void assertFailure(String code) throws Exception {
        try { lookup(); fail("Expected " + code); }
        catch (ApiClient.Failure e) { assertEquals(code, e.code); }
    }
    private Product lookup() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body));
        return ApiClient.lookup(base, expected.barcode());
    }
}
