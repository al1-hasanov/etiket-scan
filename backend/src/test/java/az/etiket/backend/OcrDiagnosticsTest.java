package az.etiket.backend;

import az.etiket.backend.NutritionTableReader.Word;
import az.etiket.core.NutritionLabel;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class OcrDiagnosticsTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(OcrDiagnostics.class);
    private ListAppender<ILoggingEvent> captured;
    @BeforeEach void capture() { captured = new ListAppender<>(); captured.start(); logger.addAppender(captured); }
    @AfterEach void stop() { logger.detachAppender(captured); captured.stop(); }
    private String messages() { return String.join("\n", captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList()); }

    @Test void explainsRejectedUnitsAndConsensusWithoutLoggingTheLabelOrChangingTheResult() {
        String id = UUID.randomUUID().toString(), rawMarker = "PRIVATE_RAW_LABEL_MARKER";
        List<Word> words = new ArrayList<>();
        row(words, 30, "Nährwerte", "in 100 g", "in 22 g");
        row(words, 54, "Fett", "9,89", "2,1 g");
        row(words, 78, "Salz", "0,29 g", "0,06 g");
        row(words, 102, rawMarker, "4 g", "1 g");
        var expected = NutritionTableReader.agree(NutritionTableReader.read(words), NutritionTableReader.read(words));
        try (var scope = new OcrDiagnostics(true).job(id, "nutrition", "de", 1)) {
            var reading = NutritionTableReader.read(words);
            assertEquals(expected, NutritionTableReader.agree(reading, reading));
        }
        assertEquals(new NutritionLabel("PER_100_G", Map.of("salt", .29d)), expected.nutrition());
        String log = messages();
        assertTrue(log.contains("jobId=" + id));
        assertTrue(log.contains("event=table_heading"));
        assertTrue(log.contains("field=fat reason=MISSING_OR_UNSUPPORTED_UNIT"));
        assertTrue(log.contains("field=fat reason=NO_VALID_CELL"));
        assertFalse(log.contains(rawMarker));
        assertFalse(log.contains("9,89"));
        assertFalse(log.contains("Nährwerte"));
    }

    @Test void disabledDiagnosticsStaySilentAndNestedPassesRestoreTheirParentAndJobContext() {
        try (var scope = new OcrDiagnostics(false).job("disabled-job", "nutrition", "de", 1)) {
            OcrDiagnostics.event("job_started");
        }
        assertFalse(messages().contains("jobId=disabled-job"));
        try (var job = new OcrDiagnostics(true).job("outer-job", "nutrition", "de", 1)) {
            try (var pass = OcrDiagnostics.pass("GRAYSCALE", 6, 2, 0, 300, 200)) {
                try (var child = OcrDiagnostics.pass("HEADING_ONLY", 7, 0, 0, 200, 30)) {
                    OcrDiagnostics.event("inside_heading");
                }
                OcrDiagnostics.event("inside_parent");
            }
        }
        int before = captured.list.size();
        OcrDiagnostics.event("after_job");
        assertEquals(before, captured.list.size());
        assertTrue(messages().contains("pass=p2 event=inside_heading"));
        assertTrue(messages().contains("pass=p1 event=inside_parent"));
        assertTrue(messages().contains("event=pass_finished"));
    }

    @Test void aMissingExecutableReportsTheStageAndExceptionTypeWithoutTheExceptionMessage() {
        try (var job = new OcrDiagnostics(true).job("missing-engine-job", "ingredients", "de", 1)) {
            assertThrows(java.io.IOException.class, () -> new TesseractOcr("private-executable-marker-does-not-exist", 1)
                .recognize(SubmissionFixtures.photo(), "de"));
        }
        assertTrue(messages().contains("event=pass_failed"));
        assertTrue(messages().contains("exceptionType=IOException"));
        assertFalse(messages().contains("private-executable-marker"));
    }

    @Test void boundsDetailedEventsButAlwaysKeepsTheTerminalSummary() {
        try (var job = new OcrDiagnostics(true).job("bounded-job", "nutrition", "de", 1)) {
            for (int i = 0; i < 500; i++) OcrDiagnostics.event("row_rejected", "reason", "UNKNOWN_NUTRIENT_LABEL");
            OcrDiagnostics.event("job_result", "status", "COMPLETED", "suggestionCount", 0);
        }
        assertEquals(250, captured.list.stream().filter(event -> event.getFormattedMessage().contains("event=row_rejected")).count());
        assertEquals(1, captured.list.stream().filter(event -> event.getFormattedMessage().contains("event=detail_limit")).count());
        assertTrue(messages().contains("event=job_result"));
    }

    @Test @EnabledIfEnvironmentVariable(named = "ETIKET_OCR_ENGINE_TEST", matches = "true")
    void nativeScanLogsPassesAndCellRejectionsForTheOriginalPackagePhoto() throws Exception {
        byte[] photo;
        try (var input = getClass().getResourceAsStream("/ocr/nutrition-purple-de.jpg")) { photo = input.readAllBytes(); }
        try (var job = new OcrDiagnostics(true).job("native-photo-job", "nutrition", "de", 1)) {
            var text = new TesseractOcr("tesseract", 35).recognizeNutrition(photo, "de");
            assertEquals(.29d, text.tableNutrition().nutrition().values().get("salt"));
            assertEquals(3.3d, text.tableNutrition().nutrition().values().get("saturatedFat"));
        }
        assertTrue(messages().contains("event=pass_started"));
        assertTrue(messages().contains("event=heading_check_result"));
        assertTrue(messages().contains("reason=MISSING_OR_UNSUPPORTED_UNIT"));
        assertFalse(messages().contains("Brennwert"));
    }

    private static void row(List<Word> words, int top, String label, String first, String second) {
        words.add(new Word("row" + top, label, 20, top, 20 + label.length() * 8, top + 20, 94));
        words.add(new Word("row" + top, first, 420 - first.length() * 8, top, 420, top + 20, 94));
        words.add(new Word("row" + top, second, 610 - second.length() * 8, top, 610, top + 20, 94));
    }
}
