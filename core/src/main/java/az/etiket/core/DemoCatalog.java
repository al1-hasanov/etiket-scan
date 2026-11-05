package az.etiket.core;

import java.util.List;
import java.util.Map;

/** Fictional fixtures with restricted-circulation example codes, not retail product claims. */
public final class DemoCatalog {
    private DemoCatalog() { }
    public static List<Product> products() {
        return List.of(
            sample("2000000000015", "Sadə yulaf", "Plain oats", "NÜMUNƏ / SAMPLE", "500 g",
                "Tam dənli yulaf (100%).", "a",
                Map.of("energyKcal", 370d, "fat", 7d, "saturatedFat", 1.3d, "carbohydrates", 60d,
                    "sugars", 1d, "fiber", 10d, "protein", 13d, "salt", 0.01d),
                List.of("en:gluten")),
            sample("2000000000022", "Kakaolu biskvit", "Cocoa biscuits", "NÜMUNƏ / SAMPLE", "180 g",
                "Buğda unu, şəkər, bitki yağı, kakao, süd tozu, duz.", "e",
                Map.of("energyKcal", 480d, "fat", 21d, "saturatedFat", 9d, "carbohydrates", 66d,
                    "sugars", 28d, "protein", 6d, "salt", 0.65d),
                List.of("en:gluten", "en:milk")),
            sample("2000000000039", "Məlumatı natamam içki", "Drink with incomplete data", "NÜMUNƏ / SAMPLE", "330 ml",
                "", "", Map.of("energyKcal", 32d, "sugars", 7.5d), List.of())
        );
    }
    private static Product sample(String code, String az, String en, String brand, String quantity,
            String ingredients, String grade, Map<String, Double> nutrition, List<String> allergens) {
        return new Product(code, az, brand, quantity, ingredients, "az", grade, nutrition,
            allergens, List.of(), "DEMO", "", "", "", true);
    }
    public static String englishName(String barcode) {
        return switch (barcode) {
            case "2000000000015" -> "Plain oats";
            case "2000000000022" -> "Cocoa biscuits";
            case "2000000000039" -> "Drink with incomplete data";
            default -> "Sample product";
        };
    }
}
