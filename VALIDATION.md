# Validation — Etiket 0.2

Validated on 4 November 2026 with Temurin JDK 25.0.5, Gradle 9.1.0, Spring Boot 4.1.2, Android Gradle Plugin 8.13.2 and Android SDK 36.

## Automated results

**43 checks passed, 0 failed, 0 skipped.** Debug APK assembly passed. Android lint passed with 0 errors and 0 warnings; details are summarized in `qa/automated-results.json`.

- **8 shared Java checks:** GTIN checksum, supported lengths, leading-zero normalization, invalid/non-ASCII input, fictional fixtures and unknown nutrient values.
- **22 backend checks:** real HTTP/controller/database flows on H2, 77/10/67 counts, Azerbaijani text, unverified provenance, missing ingredients vs missing product, foreign-candidate separation, source-record retention, pagination bounds, idempotent import, legacy 0.1 database/cache migration and optional provider caching/error/quota behavior against a controlled stub.
- These include **2 checks against a real temporary PostgreSQL 17.11 process**: Flyway migration, catalogue import/reimport, Unicode lookup and partial-product round trip. PostgreSQL Unix sockets are disabled for portability; tests use TCP loopback. No external database is needed.
- **13 Android JVM checks:** 77-record asset loading, search by exact barcode/no match, manual entry without an API, web/missing ingredient screens, source/review labels, language and saved-product persistence, offline saved records in live mode, API-address validation and actual HTTP/JSON client parsing/error handling against a controlled test server. UI checks run with Robolectric Android API 35; the APK targets API 36 and supports Android 8.0+.

## Packaged application checks

Started the executable Java 25 JAR with a persistent H2 file, then stopped and restarted it using the same database. Both runs returned health version 0.2.0 and counts of 77 total / 10 ingredient lists / 67 missing / 0 label-verified. Known and partial products returned 200, an unknown test-catalogue product 404 and an invalid check digit 400. Seed content inside the executable JAR and APK matches the checked-in generated seed.

The Android APK signature was verified. The signing certificate matches the earlier 0.1 demo. Development signing is retained; this is not a store-signed release.

The rendered Azerbaijani/English home, N1 ingredients and croissant missing-data screens were visually inspected. Screenshots and the test summary are in `qa/`.

## Reproduce

```text
gradlew.bat :core:test :backend:test :backend:bootJar :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python scripts/prepare_catalogue.py --check
```

Use `./gradlew` on Linux/macOS. API-only work can use `-PbackendOnly :core:test :backend:test :backend:bootJar`. Backend integration tests download a temporary PostgreSQL binary and need an ordinary non-root user. First builds/tests need internet for dependencies.

## Limits

A physical Android device/Google Play emulator was not available: camera capture, first-use Google scanner download and phone-to-PC networking still need your phone check. Robolectric is not a substitute for those device checks.

Docker/Compose definitions are included but were not executed here because Docker is unavailable. PostgreSQL was tested directly through a temporary native process. No Railway/AWS service was deployed.

The 77 real records are web-sourced and unverified against current package labels. There are no invented nutrient values, health scores or inferred allergy-free claims. Foreign candidates stay in audit data and are not promoted to primary ingredients. No package-photo upload, OCR, automatic ingredient translation, reviewed-submission editor, Flutter or iOS client is implemented in this increment. External live-provider availability was not used as a condition for passing these tests; its adapter is tested against controlled responses and disabled by default.
