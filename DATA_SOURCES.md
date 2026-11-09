# Data sources and dependency notices

## Product data

### Bazarstore starter sample (0.2)

The primary test catalogue is `catalogue/source.json`, captured from public Bazarstore product pages on 4 November 2026. It contains 77 sampled products, 10 ingredient-text candidates and 67 without a retailer ingredient list. Individual source URLs and capture times are retained. This is not a complete catalogue or a licensed production feed. Production reuse terms/permission have not been established. No product photos are packaged; image URLs in the audit source are references only. See `catalogue/SOURCE_README.md` for collection details and known source conflicts.

Every record remains `WEB_SOURCED_UNVERIFIED`. Raw ingredient sections and cleaned text candidates are both retained. No declared allergen statement has been inferred from the product name or an ingredient keyword. Nutrition and scores are unknown in this seed.

The separately attached foreign-market croissant ingredient candidate is retained in the audit record, with its review flag and pack-size conflict. It is not promoted into the app's primary ingredients, allergens or traces. The app only indicates that an unreviewed candidate exists.

### Reviewed package photos (0.4)

Submissions are separate from the starter source and provider cache. They become public product evidence only after an authenticated reviewer compares the photos, exact barcode/variant, size and Azerbaijan market version. The submitter explicitly consents to publication of the product details and package photos. Pending/rejected photos remain private to reviewers.

Approved products use `REVIEWED_LABEL`, an immutable approved source snapshot, review date and `LABEL_PHOTO_REVIEWED` evidence. The review concerns the submitted package photos; it does not assert a permanent formulation, GS1 ownership, medical suitability or a health score. Foreign-market recipe candidates are not automatically imported. Source ingredient language and exact declared allergen wording remain separate from general Azerbaijani dictionary descriptions.

Android removes photo metadata and prepares upright JPEGs; the backend validates, bounds and re-encodes images before storing them in the database. Camera/gallery permissions use temporary content URIs. No label photos or synthetic approval test records are included in the shipped starter catalogue.

### Retailer and manufacturer nutrition reports

An authenticated reviewer can import a feed they have permission to reuse. Each report keeps its source, URL, observation time, pack size and reference amount. Reports live in a separate table from the starter snapshot and reviewed label evidence. The seven research examples are exercised as test inputs. Production data is supplied by the operator. See [V0_6_4_UPDATE.md](V0_6_4_UPDATE.md).

### Optional Open Food Facts adapter

The provider is disabled by default. For a known product, an eligible panel is displayed with its own attribution and the original product and ingredient evidence.

- Provider: Open Food Facts and its contributors, https://world.openfoodfacts.org/
- API documentation: https://openfoodfacts.github.io/openfoodfacts-server/api/
- Schema changes: https://openfoodfacts.github.io/openfoodfacts-server/api/ref-api-and-product-schema-change-log/
- The read-only `GET /api/v3.6/product/{barcode}.json` request selects product metadata and `nutrition.input_sets`. It uses explicit packaging or manufacturer values for 100 g or 100 ml as sold. Inputs with an estimate, a qualifier or a serving basis stay out of the displayed panel.
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
