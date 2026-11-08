package az.etiket.backend;

import az.etiket.core.NutritionLabel;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;
import static az.etiket.backend.SubmissionModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real image, sanitisation, native OCR, correction, review and barcode lookup in an isolated database. */
@EnabledIfEnvironmentVariable(named = "ETIKET_OCR_ENGINE_TEST", matches = "true")
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:real-nutrition;DB_CLOSE_DELAY=-1",
    "review.password=only-for-local-tests", "ocr.worker-enabled=false", "off.enabled=false"})
class RealNutritionFlowTest {
    @Autowired LabelAnalysis analysis;
    @Autowired SubmissionService submissions;
    @Autowired ProductService products;

    @Test void realPurpleTableKeepsUncertainCellsUnknownAndPublishesOnlyTheReviewedCorrection() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream("/ocr/nutrition-purple-de.jpg")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        MockMultipartFile photo = new MockMultipartFile("nutrition", "nutrition.jpg", "image/jpeg", bytes);
        String id = UUID.randomUUID().toString(), token = "private-test-capability-" + UUID.randomUUID();
        var request = new LabelAnalysis.Request(id, token, "nutrition", "de");
        analysis.create(request, photo);
        analysis.runNext();
        var job = analysis.get(id, token);
        assertEquals("COMPLETED", job.status(), job.toString());
        assertEquals("PER_100_G", job.result().nutrition().basis());
        assertEquals(3.3d, job.result().nutrition().values().get("saturatedFat"));
        assertEquals(.29d, job.result().nutrition().values().get("salt"));
        Map<String, Double> printed = Map.of("energyKcal", 378d, "fat", 9.8d, "saturatedFat", 3.3d,
            "carbohydrates", 66d, "sugars", 27d, "fiber", 2.7d, "protein", 5.2d, "salt", .29d);
        job.result().nutrition().values().forEach((key, value) -> assertEquals(printed.get(key), value, key));
        assertFalse(job.result().nutrition().values().containsKey("fat"), "9,89 without a gram unit stays unknown");

        String code = "3017620422003";
        var old = SubmissionFixtures.draft(code);
        NutritionLabel corrected = new NutritionLabel("PER_100_G", printed);
        Draft draft = new Draft(old.clientRequestId(), code, "Synthetic workflow test", "Test fixture", "132 g",
            "Synthetic ingredient statement", "de", "", true, corrected, Map.of("nutrition", new LabelAnalysis.Link(id, token)));
        var photos = new LinkedHashMap<>(SubmissionFixtures.photos());
        photos.put("nutrition", photo);
        assertEquals("PENDING", submissions.create(draft, photos).status());
        assertEquals(404, assertThrows(LookupException.class, () -> products.lookup(code)).status);
        assertEquals(job.result().rawText(), submissions.detail(draft.clientRequestId()).analysis().get("nutrition").rawText());
        Approval approval = new Approval(0, draft.name(), draft.brand(), draft.quantity(), draft.ingredients(), "de", "", "",
            true, corrected, false);
        assertEquals(400, assertThrows(SubmissionException.class,
            () -> submissions.approve(draft.clientRequestId(), approval, "test-reviewer")).status);
        submissions.approve(draft.clientRequestId(), new Approval(0, draft.name(), draft.brand(), draft.quantity(),
            draft.ingredients(), "de", "", "", true, corrected, true), "test-reviewer");
        var product = products.lookup(code);
        assertEquals(corrected, product.nutrition());
        assertEquals(4, submissions.publicLabel(draft.clientRequestId()).photos().size());
        String publicJson = JsonMapper.builder().build().writeValueAsString(product);
        assertFalse(publicJson.contains(token));
        assertFalse(publicJson.contains("rawText"));
    }
}
