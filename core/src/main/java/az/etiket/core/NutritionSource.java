package az.etiket.core;

/** Provenance of the nutrition panel, independent of product and ingredient provenance. */
public record NutritionSource(Kind kind, String name, String url, String observedAt, String packSize) {
    public enum Kind { PACKAGE_CHECKED, MANUFACTURER_REPORTED, RETAILER_REPORTED, OPEN_FOOD_FACTS, CONFLICT }

    public static NutritionSource conflict() {
        return new NutritionSource(Kind.CONFLICT, "", "", "", "");
    }
}
