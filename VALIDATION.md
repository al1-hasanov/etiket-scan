# Validation — Etiket 0.4

Validated on 5 November 2026 with Temurin JDK 25.0.5, Gradle 9.1.0, Spring Boot 4.1.2, Android Gradle Plugin 8.13.2 and Android SDK 36. The uploaded latest dev archive matched GitHub `dev` at `18da96b824d0dc1c67d87c700427f6b8dc3ad6fa` for all 73 files.

## Automated checks

**85 tests passed, zero failed/errors/skipped. Android lint: zero errors and zero warnings.** Backend bootJar and signed debug APK assembly passed.

- **20 core Java tests:** barcode validation, dictionary descriptions, exact/grouped matching, missing and unsupported source text, ambiguous terms, immutable results and preserving evidence.
- **39 backend tests:** original catalogue/provider behavior, legacy upgrades, multipart submissions, review-disabled behavior, authentication and CSRF, private pending/rejected photos, validation and limits, image re-encoding/metadata removal, bounded camera-image decoding, idempotent retries, rejection, approval/publication, stale versions and competing approvals.
- **Three tests use a real temporary PostgreSQL 17.11 process:** migration/import idempotence, Unicode/missing data, plus binary photo storage and approved-product lookup surviving starter re-import. HTTP tests also run through the real server against H2.
- **26 Android JVM tests:** the retained 17 tests plus submission entry, draft restoration, consent/photos, pixel orientation and GPS removal, real HTTP multipart/receipts, retry handling, a delayed upload across activity recreation, live catalogue/cache/source origin, reviewed-product display and scoped FileProvider paths.

Android UI tests use Robolectric Android API 35 with native graphics. The APK supports Android 8.0+ (minSdk 26), targets SDK 36 and keeps Java 17 shared/mobile bytecode. AndroidX Fragment 1.8.9 satisfies Activity Result compatibility; AndroidX EXIF 1.4.2 handles image metadata.

## Browser and packaged-backend checks

The executable 0.4 JAR was started with an isolated persistent H2 file and a temporary random reviewer password. A headless Chromium browser exercised the real review JavaScript and API:

1. Anonymous pending-photo access was denied and review required authentication.
2. The browser compared synthetic package fixtures, sent a CSRF-protected approval and published a product.
3. Approved photos/source pages were accessible without reviewer credentials, including the 390-pixel mobile layout.
4. Rejection left the barcode outside the published catalogue. HTML-like product text remained text and executed no script.
5. Desktop and mobile review layouts had no horizontal overflow or JavaScript errors. Rendered screenshots were visually inspected.
6. The server process was stopped and restarted with the same database. Approved ingredients, rejected status, source links and exact stored JPEG bytes persisted.

These fixtures were confined to temporary test databases. No test approval, synthetic label photo or invented product data was inserted into the distributed starter seed or the existing Railway service.

## Packaging checks

- APK application ID `az.etiket.scanner.demo`, versionCode 4, versionName `0.4.0-labels`.
- APK signature verified. SHA-256 certificate `70cb443a22c0b25867d07bb627458a3c8d877cca17a592ea717498b661afe8b5` matches the 0.3 APK, allowing an update over the earlier demo.
- The catalogue assets inside the JAR and APK match the unchanged generated seed. Catalogue source, migration V1 and GitHub workflow remain unchanged. Migration V2 is packaged in the executable JAR.
- `qa/automated-results.json` contains the machine-readable result. New UI images in `qa/` show synthetic local test data only.

## Reproduce

```text
gradlew.bat -PbackendOnly :core:test :backend:test :backend:bootJar
gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python scripts/prepare_catalogue.py --check
```

Use `./gradlew` on Linux/macOS. PostgreSQL tests download an isolated native test binary and need an ordinary non-root user. Android builds need SDK Platform 36 and Build Tools 35.0.0. Follow `V0_4_UPDATE.md` for the manual phone and Railway checks.

## Limits

No physical phone/camera or Google Play scanner was driven for this new build. Camera-provider permissions, gallery/camera behavior on your phone and real label legibility still need the supplied acceptance test. Automated tests do not establish the truth of package ingredients.

Docker was not available, so the image/Compose configuration was not executed locally. PostgreSQL was tested directly; the packaged-JAR restart check used H2. This update was not pushed to GitHub or deployed to Railway by these checks.

This is a bounded, single-reviewer pilot. Photo bytes are stored in the database (default maximum 100 total submissions and 25 pending). Publication consent, image bounds, review authentication and decision CSRF are implemented; a general user-account/moderation system, OCR, automatic translation and automatic approval are not. The 77 starter records remain unverified web data until a real submitted label is reviewed. No health scores, inferred allergen-free claims or substitute foreign-market recipes are generated.
