package az.etiket.backend;

import static az.etiket.backend.SubmissionModels.*;
import az.etiket.core.NutritionLabel;
import az.etiket.core.NutritionSource;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP multipart -> private queue -> authenticated, CSRF-protected review -> published product. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:submissions;DB_CLOSE_DELAY=-1", "off.enabled=false",
    "review.username=reviewer", "review.password=only-for-local-tests", "submissions.max-pending=3"})
class SubmissionApiTest {
    private static final String CATALOGUE_IMPORT = "/api/v1/review/catalogue-import";
    private static final String CSV_HEADER = "barcode,name,pack_size,source_url\n";
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired CatalogueStore catalogue;
    @Autowired SubmissionService service;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    String token;
    String auth = "Basic " + Base64.getEncoder().encodeToString("reviewer:only-for-local-tests".getBytes(StandardCharsets.UTF_8));

    @BeforeEach void reset() {
        db.update("DELETE FROM imported_catalogue_products");
        db.update("DELETE FROM nutrition_reports");
        db.update("DELETE FROM reviewed_products"); db.update("DELETE FROM submission_photos"); db.update("DELETE FROM label_submissions"); token=null;
    }
    HttpResponse<String> get(String path, boolean admin) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(admin)request.header("Authorization",auth);
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path,Object body,boolean csrf) throws Exception {
        return post(path, json.writeValueAsString(body), "application/json", csrf);
    }
    private HttpResponse<String> post(String path, String body, String contentType, boolean csrf) throws Exception {
        if(csrf&&token==null)token=json.readTree(get("/api/v1/review/csrf",true).body()).path("token").asString();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization",auth).header("Content-Type",contentType);
        if(csrf)request.header("X-CSRF-TOKEN",token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> upload(Draft draft, byte[] photo) throws Exception {
        String boundary="etiket-test-"+UUID.randomUUID(); ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n"+json.writeValueAsString(draft)+"\r\n").getBytes(StandardCharsets.UTF_8));
        for(String role:SubmissionService.ROLES){out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+role+"\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(photo);out.write("\r\n".getBytes(StandardCharsets.UTF_8));}
        out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/submissions"))
            .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    Draft submit(String code) throws Exception {
        Draft draft=SubmissionFixtures.draft(code);assertEquals(201,upload(draft,SubmissionFixtures.photo()).statusCode());return draft;
    }
    @Test void pendingUploadsArePrivateAndDoNotChangeTheCatalogue() throws Exception {
        Draft draft=submit("5201360553457");String id=draft.clientRequestId();
        assertEquals("PENDING",json.readTree(get("/api/v1/submissions/"+id+"/status",false).body()).path("status").asString());
        assertEquals(401,get("/api/v1/review/submissions/"+id,false).statusCode());
        assertEquals(401,get("/api/v1/review/submissions/"+id+"/photos/front",false).statusCode());
        assertEquals(404,get("/api/v1/labels/"+id,false).statusCode());
        assertEquals(404,get("/api/v1/labels/"+id+"/photos/front",false).statusCode());
        assertEquals("",json.readTree(get("/api/v1/products/5201360553457",false).body()).path("ingredients").asString());
        assertEquals(77,catalogue.summary().get("products"));assertEquals(0,catalogue.summary().get("labelVerified"));
    }
    @Test void reviewerNeedsAuthenticationAndCsrfForDecisions() throws Exception {
        Draft draft=submit("5201360553457");String path="/api/v1/review/submissions/"+draft.clientRequestId()+"/approve";
        assertEquals(403,post(path,SubmissionFixtures.approval(0,""),false).statusCode());
        assertEquals(200,get("/review",true).statusCode());
        var response=post(path,SubmissionFixtures.approval(0,""),true);assertEquals(200,response.statusCode(),response.body());
    }

    @Test void reviewedCsvPreviewIsReadOnlyAndPublishingIsIdempotent() throws Exception {
        String csv = "\uFEFFbarcode,name,pack_size,source_url,ingredients,ingredients_language,nutrition_basis,fat_g,salt_g\r\n"
            + "012345678905,\"Sınaq, qida\",400 g,https://example.test/labels/food,\"Buğda unu, \"\"duz\"\"\r\nsu\",az,PER_100_G,\"2,5\",0\r\n"
            + "4000000000013,Sınaq içkisi,500 ml,https://example.test/labels/drink,,,,,\r\n";
        assertEquals(401, get("/review/catalogue-template.csv", false).statusCode());
        assertTrue(get("/review/catalogue-template.csv", true).body().startsWith("barcode,name,pack_size,source_url"));
        assertEquals(403, post(CATALOGUE_IMPORT, csv, "text/csv", false).statusCode());
        JsonNode preview = catalogueCsv("", csv);
        String digest = preview.path("digest").asString();
        assertFalse(preview.path("published").asBoolean());
        assertEquals(2, preview.path("ready").asInt());
        assertEquals("0012345678905", preview.at("/rows/0/barcode").asString());
        assertEquals(404, get("/api/v1/products/0012345678905", false).statusCode());
        assertEquals(400, post(CATALOGUE_IMPORT + "?dryRun=false&digest=" + digest, csv, "text/csv", true).statusCode());
        assertEquals(409, post(CATALOGUE_IMPORT + publishCatalogue(digest), csv + "\r\n", "text/csv", true).statusCode());

        JsonNode published = catalogueCsv(publishCatalogue(digest), csv);
        assertTrue(published.path("published").asBoolean());
        assertEquals(2, published.path("added").asInt());
        JsonNode food = json.readTree(get("/api/v1/products/0012345678905", false).body());
        assertEquals("Sınaq, qida", food.path("name").asString());
        assertEquals("Buğda unu, \"duz\"\r\nsu", food.path("ingredients").asString());
        assertEquals("https://example.test/labels/food", food.path("sourceUrl").asString());
        assertEquals("REVIEWED_LABEL", food.path("source").asString());
        assertTrue(food.at("/evidence/labelPhotoChecked").asBoolean());
        assertEquals("PACKAGE_CHECKED", food.at("/nutritionSource/kind").asString());
        assertEquals(2.5, food.at("/nutrition/values/fat").asDouble());
        assertEquals(0, food.at("/nutrition/values/salt").asDouble(-1));
        assertFalse(food.at("/nutrition/values").has("fiber"));
        assertFalse(food.has("reviewer"));
        JsonNode drink = json.readTree(get("/api/v1/products/4000000000013", false).body());
        assertEquals("MISSING", drink.at("/evidence/ingredientsStatus").asString());
        assertTrue(drink.at("/nutrition/values").isEmpty());
        catalogue.importBundledCatalogue();
        JsonNode repeat = catalogueCsv(publishCatalogue(digest), csv);
        assertEquals(0, repeat.path("added").asInt());
        assertEquals(2, repeat.path("unchanged").asInt());
        assertEquals(79, catalogue.summary().get("products"));
        assertEquals(2, catalogue.summary().get("labelVerified"));
        JsonNode items = json.readTree(get("/api/v1/catalogue/products?size=100", false).body()).path("items");
        assertEquals(79, items.size());
    }

    @Test void invalidRowsAndExistingBarcodesBlockTheWholeCsv() throws Exception {
        String csv = "barcode,name,pack_size,source_url,nutrition_basis,carbohydrates_g,sugars_g\n"
            + "4000000000013,Valid row,250 g,https://example.test/valid,,,\n"
            + "4760177560085,Conflicting starter row,400 g,https://example.test/starter,,,\n"
            + "012345678905,Duplicate UPC,500 g,https://example.test/upc,,,\n"
            + "0012345678905,Duplicate EAN,500 g,https://example.test/ean,,,\n"
            + "4000000000020,Invalid nutrition,500 g,https://example.test/nutrition,PER_100_G,2,5\n"
            + "4000000000037,Invalid source,100 g,javascript:alert(1),,,\n";
        JsonNode preview = catalogueCsv("", csv);
        assertEquals(1, preview.path("ready").asInt());
        assertEquals(5, preview.path("blocked").asInt());
        assertEquals("BARCODE_ALREADY_EXISTS", preview.at("/rows/1/reason").asString());
        assertEquals("DUPLICATE_BARCODE", preview.at("/rows/2/reason").asString());
        assertEquals("DUPLICATE_BARCODE", preview.at("/rows/3/reason").asString());
        assertEquals("NUTRITION_VALUES_INCONSISTENT", preview.at("/rows/4/reason").asString());
        assertEquals("SOURCE_URL_INVALID", preview.at("/rows/5/reason").asString());
        assertFalse(catalogueCsv(publishCatalogue(preview.path("digest").asString()), csv).path("published").asBoolean());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM imported_catalogue_products", Integer.class));
        assertEquals(77, catalogue.summary().get("products"));
    }

    @Test void csvCannotReplaceAnApprovedPackagePhoto() throws Exception {
        String code = "4000000000013";
        String csv = CSV_HEADER + code + ",CSV entry,400 g,https://example.test/label\n";
        JsonNode preview = catalogueCsv("", csv);
        Draft draft = submit(code);
        String id = draft.clientRequestId();
        assertEquals(200, post("/api/v1/review/submissions/" + id + "/approve", SubmissionFixtures.approval(0, ""), true).statusCode());
        JsonNode blocked = catalogueCsv(publishCatalogue(preview.path("digest").asString()), csv);
        assertFalse(blocked.path("published").asBoolean());
        assertEquals("BARCODE_ALREADY_EXISTS", blocked.at("/rows/0/reason").asString());
        JsonNode product = json.readTree(get("/api/v1/products/" + code, false).body());
        assertEquals("/sources/labels/" + id, product.path("sourceUrl").asString());
    }

    @Test void malformedAndOversizedCsvReturnErrorsWithoutImporting() throws Exception {
        assertEquals(400, post(CATALOGUE_IMPORT, CSV_HEADER + "4000000000013,\"Unclosed", "text/csv", true).statusCode());
        assertEquals(400, post(CATALOGUE_IMPORT, "barcode,name,pack_size,source_url,unknown\n", "text/csv", true).statusCode());
        assertEquals(413, post(CATALOGUE_IMPORT, "x".repeat(CatalogueCsvImport.MAX_BYTES + 1), "text/csv", true).statusCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM imported_catalogue_products", Integer.class));
    }

    private static String publishCatalogue(String digest) {
        return "?dryRun=false&confirmed=true&digest=" + digest;
    }

    private JsonNode catalogueCsv(String query, String csv) throws Exception {
        var response = post(CATALOGUE_IMPORT + query, csv, "text/csv", true);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    @Test void previewsSevenRetailerCandidatesAndKeepsConflictsOutOfTheScanResult() throws Exception {
        String path = "/api/v1/review/nutrition-import";
        var feed = new NutritionFeeds.Feed("retailer-fixture", NutritionSource.Kind.RETAILER_REPORTED, "Test retailer",
            "2025-01-01T00:00:00Z", List.of(
                nutritionReport("4760177560085", "400 g", Map.of("energyKcal", 224.8d, "fat", 18d, "protein", 13d)),
                nutritionReport("4760095105863", "1 L", Map.of("energyKcal", 19.3d, "sugars", 2.3d, "salt", 0d)),
                nutritionReport("4760018505107", "400 g", Map.of("energyKcal", 260d, "sugars", 60d)),
                nutritionReport("4760065703150", "720 g", Map.of("energyKcal", 85.5d, "sugars", 15d)),
                nutritionReport("4760068002243", "640 g", Map.of("energyKcal", 90d, "sugars", 6d)),
                nutritionReport("4760065708957", "640 g", Map.of("energyKcal", 36d, "sugars", 5d)),
                nutritionReport("4760065708018", "660 g", Map.of("energyKcal", 70d, "sugars", 3.6d))));
        assertEquals(403, post(path + "?dryRun=false", feed, false).statusCode());
        var preview = post(path, feed, true);
        assertEquals(200, preview.statusCode(), preview.body());
        JsonNode result = json.readTree(preview.body());
        assertFalse(result.path("applied").asBoolean());
        assertEquals(7, result.path("records").asInt());
        assertEquals("PACK_SIZE_MISMATCH", result.at("/conflicts/0/reason").asString());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM nutrition_reports", Integer.class));
        assertEquals(200, post(path + "?dryRun=false", feed, true).statusCode());
        assertEquals(200, post(path + "?dryRun=false", feed, true).statusCode());
        assertEquals(7, db.queryForObject("SELECT COUNT(*) FROM nutrition_reports", Integer.class));
        JsonNode n1 = json.readTree(get("/api/v1/products/4760177560085", false).body());
        assertEquals("RETAILER_REPORTED", n1.at("/nutritionSource/kind").asString());
        assertEquals("Test retailer", n1.at("/nutritionSource/name").asString());
        assertEquals(224.8d, n1.at("/nutrition/values/energyKcal").asDouble(-1));
        assertFalse(n1.at("/nutrition/values").has("sugars"));
        assertFalse(n1.at("/evidence/labelPhotoChecked").asBoolean());
        assertTrue(n1.path("ingredients").asString().contains("inək südü"));
        JsonNode kefir = json.readTree(get("/api/v1/products/4760095105863", false).body());
        assertEquals("PER_100_G", kefir.at("/nutrition/basis").asString());
        assertEquals(0d, kefir.at("/nutrition/values/salt").asDouble(-1));
        assertEquals("CONFLICT", json.readTree(get("/api/v1/products/4760065708018", false).body())
            .at("/nutritionSource/kind").asString());
    }
    @Test void invalidBatchDoesNotLeavePartialRows() throws Exception {
        var feed = new NutritionFeeds.Feed("retailer-fixture", NutritionSource.Kind.RETAILER_REPORTED, "Test retailer",
            "2025-01-01T00:00:00Z", List.of(
                nutritionReport("4760177560085", "400 g", Map.of("energyKcal", 224.8d)),
                nutritionReport("3017620422003", "400 g", Map.of("energyKcal", 539d))));
        var response = post("/api/v1/review/nutrition-import?dryRun=false", feed, true);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("NUTRITION_IMPORT_UNKNOWN_BARCODE", json.readTree(response.body()).path("code").asString());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM nutrition_reports", Integer.class));
    }
    private static NutritionFeeds.Input nutritionReport(String barcode, String packSize, Map<String, Double> values) {
        return new NutritionFeeds.Input(barcode, packSize, "https://example.test/products/" + barcode,
            new NutritionLabel("PER_100_G", values));
    }
    @Test void approvalPublishesPhotosAndIngredientsWithoutInventingNutrition() throws Exception {
        Draft draft=submit("5201360553457");String id=draft.clientRequestId();
        assertEquals(200,post("/api/v1/review/submissions/"+id+"/approve",SubmissionFixtures.approval(0,""),true).statusCode());
        JsonNode product=json.readTree(get("/api/v1/products/5201360553457",false).body());
        assertEquals("REVIEWED_LABEL",product.path("source").asString());assertEquals("buğda unu, su, duz",product.path("ingredients").asString());
        assertTrue(product.at("/evidence/labelPhotoChecked").asBoolean());assertTrue(product.path("nutrients").isEmpty());assertTrue(product.path("allergens").isEmpty());
        assertEquals("Süd izləri ola bilər.",product.at("/evidence/declaredAllergenStatement").asString());
        assertEquals(3,product.at("/ingredientAnalysis/recognizedCount").asInt());
        assertEquals(200,get("/sources/labels/"+id,false).statusCode());
        assertEquals(200,get("/api/v1/labels/"+id+"/photos/ingredients",false).statusCode());
        assertEquals(77,catalogue.summary().get("products"));assertEquals(11,catalogue.summary().get("withIngredients"));assertEquals(1,catalogue.summary().get("labelVerified"));
        assertEquals(77,db.queryForObject("SELECT COUNT(*) FROM catalogue_products",Integer.class));
        catalogue.importBundledCatalogue();assertEquals("REVIEWED_LABEL",service.findReviewed(draft.barcode()).orElseThrow().source());
    }
    @Test void rejectedSubmissionsNeverBecomePublicProducts() throws Exception {
        Draft draft=submit("3017620422003");String id=draft.clientRequestId();
        assertEquals(200,post("/api/v1/review/submissions/"+id+"/reject",new Rejection(0,"Ingredient photo is unreadable."),true).statusCode());
        assertEquals(404,get("/api/v1/products/3017620422003",false).statusCode());assertEquals(404,get("/api/v1/labels/"+id+"/photos/front",false).statusCode());
        assertEquals("REJECTED",json.readTree(get("/api/v1/submissions/"+id+"/status",false).body()).path("status").asString());
    }
    @Test void retriesAreIdempotentButCannotChangeAnExistingRequest() throws Exception {
        Draft draft=submit("5201360553457");assertEquals(201,upload(draft,SubmissionFixtures.photo()).statusCode());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM label_submissions",Integer.class));
        Draft changed=new Draft(draft.clientRequestId(),draft.barcode(),"Changed name",draft.brand(),draft.quantity(),"","az","",true);
        assertEquals(409,upload(changed,SubmissionFixtures.photo()).statusCode());
    }
    @Test void staleDecisionsAndStalePublicationReplacementAreRejected() throws Exception {
        Draft first=submit("5201360553457"),second=submit("5201360553457");
        String firstPath="/api/v1/review/submissions/"+first.clientRequestId()+"/approve";
        assertEquals(200,post(firstPath,SubmissionFixtures.approval(0,""),true).statusCode());
        assertEquals(409,post(firstPath,SubmissionFixtures.approval(0,""),true).statusCode());
        String secondPath="/api/v1/review/submissions/"+second.clientRequestId()+"/approve";
        assertEquals(409,post(secondPath,SubmissionFixtures.approval(0,""),true).statusCode());
        assertEquals("PENDING",service.status(second.clientRequestId()).status());
        assertEquals(200,post(secondPath,SubmissionFixtures.approval(0,first.clientRequestId()),true).statusCode());
        assertEquals(200,get("/api/v1/labels/"+first.clientRequestId(),false).statusCode());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM reviewed_products",Integer.class));
    }
    @Test void invalidPhotosConsentAndOversizedFilesAreRejected() throws Exception {
        Draft draft=SubmissionFixtures.draft("5201360553457");
        assertEquals(400,upload(draft,"<svg onload=alert(1)>not a photo</svg>".getBytes(StandardCharsets.UTF_8)).statusCode());
        assertEquals(413,upload(draft,new byte[LabelPhotos.MAX_BYTES+1]).statusCode());
        Draft noConsent=new Draft(draft.clientRequestId(),draft.barcode(),draft.name(),draft.brand(),draft.quantity(),"","az","",false);
        assertEquals(400,upload(noConsent,SubmissionFixtures.photo()).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM label_submissions",Integer.class));
    }
    @Test void queueIsBoundedAndMissingPhotosCannotBeApproved() throws Exception {
        submit("5201360553457");submit("5201360553457");submit("5201360553457");
        assertEquals(429,upload(SubmissionFixtures.draft("5201360553457"),SubmissionFixtures.photo()).statusCode());
        assertEquals(400,get("/api/v1/review/submissions?page=-1",true).statusCode());
        assertEquals(400,get("/api/v1/review/submissions?status=ALL",true).statusCode());
    }
    @Test void newBarcodesAppearOnceInThePublishedCatalogue() throws Exception {
        Draft draft=submit("3017620422003");
        assertEquals(200,post("/api/v1/review/submissions/"+draft.clientRequestId()+"/approve",SubmissionFixtures.approval(0,""),true).statusCode());
        assertEquals(78,catalogue.summary().get("products"));
        JsonNode page=json.readTree(get("/api/v1/catalogue/products?size=100",false).body());
        assertEquals(78,page.path("items").size());
        Set<String> barcodes=new HashSet<>();for(JsonNode item:page.path("items"))assertTrue(barcodes.add(item.path("barcode").asString()));
    }
    @Test void reviewersMustConfirmPhotosAndCannotPublishEmptyIngredients() throws Exception {
        Draft draft=submit("5201360553457");String path="/api/v1/review/submissions/"+draft.clientRequestId()+"/approve";
        Approval base=SubmissionFixtures.approval(0,"");
        assertEquals(400,post(path,new Approval(0,base.name(),base.brand(),base.quantity(),"","az","","",true),true).statusCode());
        assertEquals(400,post(path,new Approval(0,base.name(),base.brand(),base.quantity(),base.ingredients(),"az","","",false),true).statusCode());
        assertEquals("PENDING",service.status(draft.clientRequestId()).status());
    }
    @Test void simultaneousApprovalsForTheSameBarcodePublishOnlyOneVersion() throws Exception {
        Draft first=submit("5201360553457"),second=submit("5201360553457");
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Integer>> results=new ArrayList<>();
            for(Draft draft:List.of(first,second))results.add(workers.submit(()->{
                start.await();
                try{service.approve(draft.clientRequestId(),SubmissionFixtures.approval(0,""),"concurrent-test");return 200;}
                catch(SubmissionException error){return error.status;}
            }));
            start.countDown();List<Integer> codes=new ArrayList<>();for(var result:results)codes.add(result.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,codes.stream().filter(code->code==200).count());assertEquals(1,codes.stream().filter(code->code==409).count());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM reviewed_products",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM label_submissions WHERE status='APPROVED'",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM label_submissions WHERE status='PENDING'",Integer.class));
        } finally { workers.shutdownNow(); }
    }
}
