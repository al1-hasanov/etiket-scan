package az.etiket.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BarcodeTest {
    @Test void recognizesRealEan() { assertEquals("3017620422003", Barcode.normalize("3017620422003")); }
    @Test void preservesLeadingZero() { assertEquals("0036000291452", Barcode.normalize("036000291452")); }
    @Test void equivalentPackagingCode() { assertEquals("3017620422003", Barcode.normalize("03017620422003")); }
    @Test void allowsReadableSpacing() { assertEquals("3017620422003", Barcode.normalize(" 3017-6204 22003 ")); }
    @Test void rejectsBadCheckDigit() { assertThrows(IllegalArgumentException.class, () -> Barcode.normalize("3017620422004")); }
    @Test void rejectsUrlAndNonAsciiDigits() {
        for (String input : new String[]{"https://example.com", "３０１７６２０４２２００３", "123", "", "123456789012345"})
            assertThrows(IllegalArgumentException.class, () -> Barcode.normalize(input));
    }
    @Test void fixturesAreValidAndClearlyFictional() {
        for (Product product : DemoCatalog.products()) {
            assertEquals(product.barcode(), Barcode.normalize(product.barcode()));
            assertTrue(product.demo()); assertEquals("DEMO", product.source());
        }
    }
    @Test void missingNutritionIsNotFabricatedAsZero() {
        Product incomplete = DemoCatalog.products().get(2);
        assertFalse(incomplete.nutrients().containsKey("salt"));
        assertTrue(incomplete.nutriScore().isEmpty());
        assertTrue(incomplete.ingredients().isEmpty());
    }
}
