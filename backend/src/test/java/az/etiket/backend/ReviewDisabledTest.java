package az.etiket.backend;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:review-disabled;DB_CLOSE_DELAY=-1","review.password=","off.enabled=false"})
class ReviewDisabledTest {
    @LocalServerPort int port;
    @Test void missingReviewerPasswordDisablesWritesWithoutBreakingProductLookups() throws Exception {
        HttpClient http=HttpClient.newHttpClient();String base="http://127.0.0.1:"+port;
        var review=http.send(HttpRequest.newBuilder(URI.create(base+"/review")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(503,review.statusCode());assertTrue(review.body().contains("REVIEW_NOT_CONFIGURED"));
        var submit=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/submissions")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(503,submit.statusCode());
        assertEquals(200,http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/products/4760177560085")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
    }
}
