package az.etiket.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IngredientExplainerTest {
    @Test void basicIngredientsAreExplainedInSourceOrder() {
        var result = IngredientExplainer.analyze("inək südü, bitki yağı, maya, duz, qara çörək otu", "az");
        assertEquals("AVAILABLE", result.status()); assertEquals(5, result.recognizedCount());
        assertEquals("İnək südü", result.items().get(0).nameAz());
        assertEquals("bitki yağı", result.items().get(1).reportedText());
        assertTrue(result.items().get(2).explanationAz().contains("dəqiq növü göstərilmir"));
    }
    @Test void groupedAdditivesAreSeparateEntriesWithTheirCategory() {
        var result = IngredientExplainer.analyze("su, qatılaşdırıcılar(E1442,E1422,pektinlər,ksantan kitrəsi),jelatin", "az");
        assertEquals(6, result.items().size()); assertEquals(6, result.recognizedCount());
        assertEquals("E1442", result.items().get(1).reportedText());
        assertEquals("qatılaşdırıcılar", result.items().get(1).group());
        assertTrue(result.items().get(1).references().stream().allMatch(r -> r.url().startsWith("https://")));
        assertEquals("Jelatin", result.items().get(5).nameAz());
    }
    @Test void prefixAndDashDoNotBecomeExtraIngredients() {
        var result = IngredientExplainer.analyze("turşuluq tənzimləyicisi:limon turşusu; boyayıcı – karminlər", "az-AZ");
        assertEquals("AVAILABLE", result.status()); assertEquals(2, result.recognizedCount());
        assertEquals("turşuluq tənzimləyicisi:limon turşusu", result.items().get(0).reportedText());
        assertEquals("Karminlər (E120)", result.items().get(1).nameAz());
    }
    @Test void aMissingListNeverGetsInferredIngredients() {
        for (String value : new String[]{null, "", "  "}) {
            var result = IngredientExplainer.analyze(value, "az");
            assertEquals("MISSING", result.status()); assertTrue(result.items().isEmpty());
        }
    }
    @Test void otherLanguagesAreNotSilentlyTranslated() {
        for (String language : new String[]{"en", "ru", "", null}) {
            var result = IngredientExplainer.analyze("su, duz", language);
            assertEquals("UNSUPPORTED_LANGUAGE", result.status()); assertTrue(result.items().isEmpty());
        }
    }
    @Test void exactMatchingDoesNotConfuseNegationsWithIngredients() {
        var result = IngredientExplainer.analyze("südsüz qarışıq, süd yoxdur, duz", "az");
        assertEquals("PARTIAL", result.status()); assertEquals(1, result.recognizedCount());
        assertFalse(result.items().get(0).recognized()); assertEquals("", result.items().get(0).explanationAz());
        assertEquals("süd yoxdur", result.items().get(1).reportedText());
    }
    @Test void unicodeCaseSpacesAndPercentagesKeepTheReportedText() {
        var result = IngredientExplainer.analyze("İnək\u00a0südü (1,5%), E 1422, Tam dənli yulaf (100%).", "az");
        assertEquals(3, result.recognizedCount()); assertEquals(3, result.items().size());
        assertEquals("İnək\u00a0südü (1,5%)", result.items().get(0).reportedText());
    }
    @Test void malformedGroupsAreRetainedWholeAsUnknown() {
        String raw = "qatılaşdırıcılar (E1422, duz";
        var result = IngredientExplainer.analyze(raw, "az");
        assertEquals("NO_MATCHES", result.status()); assertEquals(1, result.items().size());
        assertEquals(raw, result.items().get(0).reportedText());
    }
    @Test void unknownCompoundIsNotReducedToItsKnownChild() {
        var result = IngredientExplainer.analyze("naməlum qarışıq (su, duz), pomidor", "az");
        assertEquals(2, result.items().size()); assertEquals(1, result.recognizedCount());
        assertFalse(result.items().get(0).recognized());
    }
    @Test void anEmptyFunctionalGroupIsStillShownAsUnknown() {
        var result = IngredientExplainer.analyze("qatılaşdırıcılar ()", "az");
        assertEquals("NO_MATCHES", result.status());
        assertEquals("qatılaşdırıcılar ()", result.items().get(0).reportedText());
        assertFalse(result.items().get(0).recognized());
    }
    @Test void resultsCannotBeMutated() {
        var result = IngredientExplainer.analyze("duz", "az");
        assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.items().get(0).references().clear());
    }
    @Test void foreignOrSavedAnalysisCannotOverrideTheReportedText() {
        Product p = DemoCatalog.products().get(0);
        var fake = new IngredientAnalysis("AVAILABLE", "az", "fake", "VERIFIED", 0, List.of());
        Product loaded = new Product(p.barcode(), p.name(), p.brand(), p.quantity(), "", "az", p.nutriScore(),
            p.nutrients(), p.allergens(), p.traces(), p.source(), p.sourceUrl(), p.retrievedAt(), p.sourceUpdatedAt(),
            p.demo(), p.evidence(), fake);
        assertEquals("MISSING", loaded.ingredientAnalysis().status());
        assertEquals(p.evidence(), loaded.evidence());
        assertEquals("EDITORIAL_DRAFT", loaded.ingredientAnalysis().contentStatus());
    }
}
