package az.etiket.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:diagnostics;DB_CLOSE_DELAY=-1",
    "review.password=only-for-local-tests", "ocr.worker-enabled=false", "ocr.diagnostics=true"})
@Import(AnalysisApiTest.Config.class)
class AnalysisDiagnosticsTest {
    @Autowired LabelAnalysis analysis;
    @Autowired JdbcTemplate db;
    private final Logger logger = (Logger) LoggerFactory.getLogger(OcrDiagnostics.class);
    private ListAppender<ILoggingEvent> captured;
    @BeforeEach void reset() {
        db.update("DELETE FROM label_analysis_jobs");
        AnalysisApiTest.failures.set(0); AnalysisApiTest.incomplete.set(false); AnalysisApiTest.multiColumnNutrition.set(false);
        captured = new ListAppender<>(); captured.start(); logger.addAppender(captured);
    }
    @AfterEach void stop() { logger.detachAppender(captured); captured.stop(); }
    private String messages() { return String.join("\n", captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList()); }
    private LabelAnalysis.Request request() { return new LabelAnalysis.Request(UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", "") + "private-capability-marker", "nutrition", "de"); }
    private MockMultipartFile photo() { return new MockMultipartFile("photo", "private-filename-marker.png", "image/png", SubmissionFixtures.photo()); }

    @Test void correlatesQueueReuseAndFinalResultWithoutLoggingCapabilitiesOrRawText() {
        var request = request(); var photo = photo();
        analysis.create(request, photo);
        analysis.create(request, photo);
        analysis.runNext();
        assertEquals("COMPLETED", analysis.get(request.clientRequestId(), request.token()).status());
        String log = messages();
        for (String event : new String[]{"job_queued", "job_reused", "job_started", "nutrition_parsed", "job_result"})
            assertTrue(log.contains("event=" + event), event);
        assertTrue(log.contains("jobId=" + request.clientRequestId()));
        assertTrue(log.contains("suggestionCount=4"));
        assertFalse(log.contains(request.token()));
        assertFalse(log.contains("private-filename-marker"));
        assertFalse(log.contains("Per 100 ml"));
    }

    @Test void reportsRetryAndAttemptNumberWithoutLoggingTheExceptionMessage() {
        AnalysisApiTest.failures.set(1);
        var request = request();
        analysis.create(request, photo());
        analysis.runNext();
        analysis.runNext();
        assertEquals("COMPLETED", analysis.get(request.clientRequestId(), request.token()).status());
        assertTrue(messages().contains("event=job_failed"));
        assertTrue(messages().contains("nextStatus=QUEUED"));
        assertTrue(messages().contains("attempt=2"));
        assertFalse(messages().contains("Transient test failure"));
    }
}
