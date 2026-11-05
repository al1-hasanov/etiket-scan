package az.etiket.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OcrQualityTest {
    @Test void rejectsHighConfidenceFragmentsFromTheBottlePhoto() {
        String fragments = """
            ii
            ie
            N
            3
            ç
            \\
            ; N 7
            3 >
            i
            b/s L f ~ «
            + ç . ce
            5 22 J
            Cyr £ ie om
            : a 4
            a
            = 2
            """;
        assertFalse(OcrQuality.assess(fragments, 88).readable());
    }

    @Test void acceptsReadableDraftsInSupportedScripts() {
        assertTrue(OcrQuality.assess("Zutaten: Wasser, Zucker, Fruktose.", 66).readable());
        assertTrue(OcrQuality.assess("Tərkibi: inək südü, şəkər, duz.", 67).readable());
        assertTrue(OcrQuality.assess("Состав: молоко, сахар, соль.", 68).readable());
        assertTrue(OcrQuality.assess("Per 100 ml\nFat 0 g\nSugars 3 g", 70).readable());
    }
}
