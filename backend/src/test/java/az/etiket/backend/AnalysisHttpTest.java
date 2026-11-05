package az.etiket.backend;

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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:analysis-http;DB_CLOSE_DELAY=-1","review.password=only-for-local-tests","ocr.worker-enabled=false"})
@Import(AnalysisApiTest.Config.class)
class AnalysisHttpTest {
    @LocalServerPort int port;
    @Autowired LabelAnalysis analysis;
    @Autowired JdbcTemplate db;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeEach void reset(){db.update("DELETE FROM label_analysis_jobs");AnalysisApiTest.failures.set(0);}
    @Test void publicUploadNeedsNoReviewerSecretAndPrivatePollingNeedsTheCapability()throws Exception{
        String id=UUID.randomUUID().toString(),token="privatecapability".repeat(4),boundary="test-"+UUID.randomUUID();
        var metadata=new LabelAnalysis.Request(id,token,"nutrition","en");ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n"+json.writeValueAsString(metadata)+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"label.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(SubmissionFixtures.photo());out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create(base()+"/api/v1/analysis-jobs")).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(202,response.statusCode(),response.body());assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));assertFalse(response.body().contains(token));
        String path=base()+"/api/v1/analysis-jobs/"+id;
        assertEquals(404,http.send(HttpRequest.newBuilder(URI.create(path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        analysis.runNext();response=http.send(HttpRequest.newBuilder(URI.create(path)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());assertEquals("COMPLETED",json.readTree(response.body()).path("status").asString());assertFalse(response.body().contains(token));
    }
    private String base(){return "http://127.0.0.1:"+port;}
}
