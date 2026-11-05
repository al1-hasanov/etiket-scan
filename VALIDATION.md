# Validation — Etiket 0.3

Validated on 5 November 2026 with Temurin JDK 25.0.5, Gradle 9.1.0, Spring Boot 4.1.2, Android Gradle Plugin 8.13.2 and Android SDK 36. The uploaded latest dev ZIP matched GitHub `dev` at `af3c4ce632a2cf7324a43c5c742c6e2808f7123e` byte-for-byte for all 67 files.

## Automated results

**60 tests passed, 0 failed, 0 skipped.** Debug APK assembly and backend bootJar passed. Android lint passed with 0 errors and one dependency-version availability warning for the existing test-only MockWebServer dependency. Details are in `qa/automated-results.json`.

- **20 shared Java tests:** the original eight barcode/data tests plus exact ingredient matching, grouped additives, functional prefixes, language handling, unknown/negated phrases, Unicode and percentages, malformed/empty groups, immutable output and rejecting stale/injected derived analysis.
- **23 backend tests:** real HTTP/controller/database flows on H2, 77/10/67 counts, Azerbaijani text, unverified provenance, missing ingredients vs missing product, foreign-candidate separation, source-record retention, pagination bounds, idempotent import, legacy 0.1 database/cache migration and optional provider caching/error/quota behavior against a controlled stub. All ten populated ingredient lists receive descriptions while keeping their original text and evidence.
- These include **2 checks against a real temporary PostgreSQL 17.11 process**: Flyway migration, catalogue import/reimport, Unicode lookup and partial-product round trip. PostgreSQL Unix sockets are disabled for portability; tests use TCP loopback. No external database is needed.
- **17 Android JVM tests:** the original 13 checks plus the offline explanation screen, saved-product descriptions, missing-list behavior, unknown fragments in the English UI and compatibility with 0.2 API responses. UI checks run with Robolectric Android API 35; the APK targets API 36 and supports Android 8.0+.

## Packaged application checks

Started the executable Java 25 JAR with a persistent H2 file, then stopped and restarted it using the same database. Both runs returned health version 0.3.0 and counts of 77 total / 10 ingredient lists / 67 missing / 0 label-verified. Known and partial products returned 200, an unknown test-catalogue product 404 and an invalid check digit 400. Seed content inside the executable JAR and APK matches the unchanged checked-in generated seed.

The ten source lists contain 65 parsed entries: 63 have dictionary descriptions. Nine lists have all parsed entries recognised; berry yoghurt remains `PARTIAL` with the two unclear source fragments `konsentrasiyalı yerkökü` and `üzüm vəaroniya şirələri` shown as unknown. Matching coverage does not verify the ingredient list itself.

The Android APK signature was verified. The signing certificate matches the earlier 0.2 demo, and the application ID is unchanged with versionCode increased to 3. Development signing is retained; this is not a store-signed release.

The rendered Azerbaijani N1 ingredient screen and the complete new explanation card were visually inspected. The card image is `qa/ingredients-az.png`. Automated UI assertions also cover the English interface and missing/partial lists.

## Reproduce

```text
gradlew.bat :core:test :backend:test :backend:bootJar :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python scripts/prepare_catalogue.py --check
```

Use `./gradlew` on Linux/macOS. API-only work can use `-PbackendOnly :core:test :backend:test :backend:bootJar`. Backend integration tests download a temporary PostgreSQL binary and need an ordinary non-root user. First builds/tests need internet for dependencies.

## Limits

A physical Android device/Google Play emulator was not available: camera capture, first-use Google scanner download and phone-to-PC networking still need your phone check. Robolectric is not a substitute for those device checks.

Docker/Compose definitions are included but were not executed here because Docker is unavailable. PostgreSQL was tested directly through a temporary native process. This 0.3 update has not been pushed to GitHub or deployed to Railway; follow `V0_3_UPDATE.md`.

The 77 real records are web-sourced and unverified against current package labels. There are no invented nutrient values, health scores or inferred allergy-free claims. Foreign candidates stay in audit data and are not promoted to primary ingredients. No package-photo upload, OCR, automatic ingredient translation, reviewed-submission editor, Flutter or iOS client is implemented in this increment. External live-provider availability was not used as a condition for passing these tests; its adapter is tested against controlled responses and disabled by default.
