# Validation — Etiket 0.5

Validated 5 November 2026 against the source ZIP uploaded for this iteration. Runtime: Microsoft OpenJDK 25.0.5.0.2, Gradle 9.1.0, Spring Boot 4.1.2, Android Gradle Plugin 8.13.2, Android SDK 36 / Build Tools 35.0.0. OCR: Tesseract 5.5.0 with upstream fast language models.

## Automated results

**102 tests passed, zero failures, errors or skipped tests. Android lint: zero issues. Backend executable JAR and signed debug APK built successfully.**

- **26 shared/core tests:** the existing barcode/dictionary checks plus nutrition reference amounts, unknown versus zero, invalid/inconsistent values, explicit units, ambiguous/serving/multiple-column abstention, AZ/TR/RU parsing and compatibility for per-100-ml data.
- **47 backend tests:** retained catalogue/provider, legacy migration, multipart, photo validation, review authentication/CSRF, approval/rejection and conflict checks; new private OCR capability/HTTP tests, job idempotence/capacity, bounded retry and expired-lease recovery, expiration, photo/result binding, immutable raw audit, nutrition-photo requirements and reviewer confirmation.
- **29 Android JVM tests:** retained scan/history/settings/submission/cache/orientation checks plus private analysis HTTP requests, an editable OCR draft across recreation, preserving manual edits, server-origin scoping and nutrition reference/zero/missing data in rotation and saved-product serialization.

Three backend checks use an actual temporary PostgreSQL 17.11 process, including migration V3. Other HTTP checks use an actual embedded server with isolated H2 databases. Real OCR tests were enabled with `ETIKET_OCR_ENGINE_TEST=true`; generated English, Azerbaijani, Turkish and Russian labels, a quarter-turn label and a blank image were processed by the executable, not a fake provider. Separate deterministic engine fixtures cover repeatable state/failure tests.

The delayed-upload Android test waits for both persisted receipt and visible success dialog, avoiding a race between asynchronous persistence and main-thread UI delivery. It still asserts exactly one upload, one confirmed receipt and the retained ViewModel.

## Packaged application checks

The actual executable 0.5 JAR was run with an isolated persistent H2 file and a temporary random reviewer password. Actual ingredient/nutrition photos generated specifically as synthetic QA fixtures were uploaded through HTTP, processed by the durable Tesseract worker, submitted with private analysis references, and approved through the actual browser review JavaScript with its CSRF token.

- Anonymous analysis polling and pending public photos were denied.
- Raw OCR and corrected fields remained separate in review. Seven unambiguous per-100-ml nutrition values were suggested; a misread unit in the saturated-fat row left that field blank. No missing number was invented.
- The browser confirmed nutrition and package photos and published a product with the correct 100-ml basis. The legacy per-100-g map stayed empty.
- Desktop (1440 px) and mobile (390 px) review/source layouts were checked for horizontal overflow, working image decoding and JavaScript errors. Screenshots were inspected locally.
- The service was stopped and restarted against the same H2 database. Approved ingredients, explicit nutrition, exact sanitised photo bytes and completed private analysis results persisted.

These fixtures are confined to temporary local databases. They are not in the delivered starter assets or the existing Railway service.

## Packaging and compatibility

- Application ID `az.etiket.scanner.demo`, versionCode **5**, versionName **0.5.0-ocr**, minimum Android API 26, target API 36.
- APK signature verified (v2). Certificate SHA-256 `70cb443a22c0b25867d07bb627458a3c8d877cca17a592ea717498b661afe8b5` matches the earlier demo key.
- Catalogue source, both generated starter assets, migrations V1/V2 and the development signing key match the uploaded baseline byte-for-byte.
- The packaged JAR contains unchanged seed data and migration V3; the APK contains the unchanged seed. `scripts/prepare_catalogue.py --check` passes. `git diff --check` and both changed browser JavaScript syntax checks pass.
- No source records, nutrients, scores or allergens were manufactured to enlarge the catalogue.

## Reproduce

```text
gradlew.bat --no-daemon -PbackendOnly :core:test :backend:test :backend:bootJar
gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python scripts/prepare_catalogue.py --check
```

For real-engine tests, install Tesseract and the language packs, then set `ETIKET_OCR_ENGINE_TEST=true`. The updated GitHub workflow does this on Ubuntu. Normal tests remain runnable on Windows without a native OCR installation; those explicitly marked real-engine checks require the flag and executable.

## Practical limits

Generated-image tests verify integration, not accuracy on actual curved/glossy labels. A physical phone/camera and scanner-provider check was not possible here. Complete the real-package phone checks in `V0_5_UPDATE.md`, including unclear and interrupted uploads, before expanding the pilot.

Docker is unavailable in this environment, so the Docker image/Compose build was not executed locally. The same Tesseract process integration, the executable JAR and PostgreSQL migrations were tested directly. The source has not been pushed, merged or deployed by this task. No paid OCR, translation, scoring or subscription service is configured.
