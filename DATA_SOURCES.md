# Data sources and dependency notices

## Product data

### Bazarstore starter sample (0.2)

The primary test catalogue is `catalogue/source.json`, captured from public Bazarstore product pages on 4 November 2026. It contains 77 sampled products, 10 ingredient-text candidates and 67 without a retailer ingredient list. Individual source URLs and capture times are retained. This is not a complete catalogue or a licensed production feed. Production reuse terms/permission have not been established. No product photos are packaged; image URLs in the audit source are references only. See `catalogue/SOURCE_README.md` for collection details and known source conflicts.

Every record remains `WEB_SOURCED_UNVERIFIED`. Raw ingredient sections and cleaned text candidates are both retained. No declared allergen statement has been inferred from the product name or an ingredient keyword. Nutrition and scores are unknown in this seed.

The separately attached foreign-market croissant ingredient candidate is retained in the audit record, with its review flag and pack-size conflict. It is not promoted into the app's primary ingredients, allergens or traces. The app only indicates that an unreviewed candidate exists.

### Optional Open Food Facts adapter

The following provider is disabled by default in 0.2. Enabling it does not alter the starter catalogue's evidence status or fill its missing recipes automatically.

- Provider: Open Food Facts and its contributors, https://world.openfoodfacts.org/
- API documentation: https://openfoodfacts.github.io/openfoodfacts-server/api/
- Schema changes: https://openfoodfacts.github.io/openfoodfacts-server/api/ref-api-and-product-schema-change-log/
- API contract used: read-only `GET /api/v3.4/product/{barcode}.json`, selected fields only. Newer API versions change the nutrition structure.
- Data licences: Open Database License 1.0 (database), Database Contents License 1.0 (individual contents). Attribution and share-alike obligations apply to reuse of the database; see the provider's published terms: https://openfoodfacts.github.io/openfoodfacts-server/api/tutorials/license-be-on-the-legal-side/
- Local cache: separate `product_cache` table storing attributed provider records. No product images are bundled. If images are introduced later, OFF lists CC BY-SA licensing for them.
- The source's Nutri-Score, if present, is displayed as its own nutrition indicator. No Yuka scoring method, proprietary data, logo or artwork is copied.
- Source ingredient text is preserved. No AI-generated ingredient list or invented nutrient values are substituted for missing records.

## Fictional fixtures

The three products from `DemoCatalog` and their nutrition values are generated examples for UI/engineering tests. They are conspicuously labelled fictional in both languages. They must not be presented as a real brand's composition or an independently validated nutrition assessment.

## Barcode scanning

Google Code Scanner SDK 16.1.0, through Google Play services:
https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner
Scanning occurs through Google's on-device UI. Initial module download requires connectivity. Manual entry remains available.

## Build/runtime dependencies

Spring Boot / Spring Framework, Android Gradle Plugin, H2, PostgreSQL JDBC driver, Jackson, JUnit, and Robolectric retain their respective upstream licences. Dependency coordinates and versions are recorded in the Gradle files. The Gradle wrapper retains the upstream copyright/licence header. Consult dependency licence notices before redistribution or a public release.
