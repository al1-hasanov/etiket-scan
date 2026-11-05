package az.etiket.backend;

import static az.etiket.backend.SubmissionModels.*;
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
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired CatalogueStore catalogue;
    @Autowired SubmissionService service;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    String token;
    String auth = "Basic " + Base64.getEncoder().encodeToString("reviewer:only-for-local-tests".getBytes(StandardCharsets.UTF_8));

    @BeforeEach void reset() {
        db.update("DELETE FROM reviewed_products"); db.update("DELETE FROM submission_photos"); db.update("DELETE FROM label_submissions"); token=null;
    }
    HttpResponse<String> get(String path, boolean admin) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(admin)request.header("Authorization",auth);
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path,Object body,boolean csrf) throws Exception {
        if(csrf&&token==null)token=json.readTree(get("/api/v1/review/csrf",true).body()).path("token").asString();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization",auth).header("Content-Type","application/json");
        if(csrf)request.header("X-CSRF-TOKEN",token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
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
