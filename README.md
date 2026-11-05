# Etiket 0.3 — Azerbaijani ingredient explanations

This increment adds a shared, exact-alias ingredient dictionary to the native Android Java app and Java 25 / Spring Boot API. Scan or enter a barcode, read the original ingredient list, then read **Tərkibin sadə izahı** underneath it. The dictionary works offline and with the API. Its Azerbaijani text is an editorial draft, separate from the product's evidence status.

**77 products · 10 web ingredient lists · 67 missing ingredient lists · 0 package-label checks.** These are sampled records, not Bazarstore's full inventory. Every real record is `WEB_SOURCED_UNVERIFIED`. A valid barcode check digit does not confirm the formulation or GS1 ownership.

## New in 0.3

- Recognised ingredient names receive a short Azerbaijani description. A count shows how many entries have descriptions.
- Known additive groups are split without treating their internal commas as new top-level ingredients. E1422, E1442, pectin and xanthan gum are recognised inside thickener groups.
- Unrecognised text stays visible with **Bu hissə üçün lüğətdə izah yoxdur.** Ambiguous words such as *maya* keep their ambiguity.
- Empty lists stay empty. The croissant's foreign candidate is still not used as a recipe. Other source languages are not silently translated.
- Original ingredient text, declared allergens, source URLs and review status are preserved. These descriptions do not calculate a health score or certify suitability.
- The API adds `ingredientAnalysis` to the existing product JSON. Existing 0.2 clients ignore the new field. The 0.3 Android client derives descriptions from its bundled shared dictionary, so it also works against the 0.2 API and older saved products.
- No database migration or additional paid service is needed. Dictionary changes require rebuilding the backend and Android app.

See [INGREDIENT_DICTIONARY.md](INGREDIENT_DICTIONARY.md) for the contract, reference sources and editing rules, and [V0_3_UPDATE.md](V0_3_UPDATE.md) for applying this update to the existing Git repository.

## 1. Try the app without a server

1. Install the separately supplied **Etiket_Demo_v0.3.apk**, or build `:app:assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`, on Android 8.0 or newer. It uses the same application ID and development signing key as 0.2, so it can update that demo. The old `dist/` binaries are historical 0.2 builds.
2. Open **Etiket Demo**. The default mode is **Offline test catalogue**. Existing installations retain their previous API/language settings; turn live lookup off in Settings if you want the bundled snapshot.
3. Open **Kataloq / Catalogue**. Search by product name, brand or barcode.
4. Enter or scan the following codes. Manual entry avoids needing the scanner module's first download.

| Barcode | Expected result |
| --- | --- |
| `4760177560085` | N1 şor: Azerbaijani web ingredient text, source link, needs-review notice |
| `8076809576130` | Barilla Tortiglioni: Azerbaijani web ingredient text, needs-review notice |
| `5201360553457` | 7 Days croissant: product found, ingredients missing; foreign candidate is not used as its ingredient list |
| `3017620422003` | Outside this starter catalogue; offline mode explains the limitation |
| `4760177560086` | Invalid check digit; rejected before lookup |

You can save products and reopen them from **Seçilmişlər / Saved**. Evidence flags and source links survive saving/restarting. The ingredient text stays in the source language; changing the UI to English does not translate it. The home screen also links to the old, clearly fictional engineering samples.

The camera uses Google Code Scanner through Google Play services. The first scanner-module download requires internet; manual entry is available. Physical camera behavior still needs testing on your phone. No package-photo upload or OCR workflow is implemented yet.

## 2. Start the Java API on Windows

1. Extract this entire ZIP into a normal folder. The root contains `settings.gradle`, `backend/`, `app/`, `dist/` and `run-backend.bat`.
2. Install JDK 25. Check `java -version`, or configure `JAVA_HOME` to that JDK.
3. Run `gradlew.bat -PbackendOnly :backend:bootJar`, then double-click **run-backend.bat**. The script runs the newly built JAR from `backend/build/libs/`.
4. Open [health](http://127.0.0.1:8080/api/v1/health). Expect `{"status":"UP","version":"0.3.0"}`; field order can differ.
5. Open [catalogue summary](http://127.0.0.1:8080/api/v1/catalogue/summary). Expect 77 products, 10 with ingredients, 67 missing, 0 label-verified.
6. Open [N1 lookup](http://127.0.0.1:8080/api/v1/products/4760177560085) and [croissant lookup](http://127.0.0.1:8080/api/v1/products/5201360553457).

Linux/macOS: run `sh run-backend.sh`. Stop with Ctrl+C. `PORT` changes the default port 8080.

The default database is a persistent H2 file at `data/etiket`. Flyway creates/migrates the schema, then an atomic importer inserts the seed. Restarting does not duplicate the catalogue or change its import timestamp when its content is unchanged. The captured source record, original ingredient section and foreign candidate remain in the database for review. No runtime scraping occurs.

The first migration can adopt the earlier 0.1 cache schema using baseline version 0. The starter importer only maintains `catalogue_products`; it does not overwrite the separate provider cache. Future reviewed submissions should use separate storage and an explicit promotion workflow.

## 3. Connect your phone to the API

**Android Studio emulator:** enable **Live product lookup** in Settings and use `http://10.0.2.2:8080`.

**USB phone:** enable USB debugging, connect the phone, then run `adb reverse tcp:8080 tcp:8080`. Enable live lookup with `http://127.0.0.1:8080`.

**Private Wi-Fi:** in Windows PowerShell, run `$env:SERVER_ADDRESS='0.0.0.0'`, then `./run-backend.bat`. Permit the port on your private-network firewall. Set the app's API address to your PC's LAN address, such as `http://192.168.1.20:8080`. A public endpoint needs HTTPS.

In live mode, opening a real catalogue card or scanning a real code calls the API, even for a bundled product. Connection failures remain visible; the app does not silently replace a failed API request with the offline snapshot. Previously saved records remain available locally. The catalogue browser itself lists the bundled snapshot in both modes; it is not a live inventory browser.

Repeat the N1 and croissant checks in live mode, stop the server and check that live lookup reports a connection problem, then disable live lookup and confirm the same products work offline.

## 4. Run with PostgreSQL

Docker Compose supplies PostgreSQL 17 and the API:

```sh
docker compose up --build
```

The API is at `http://127.0.0.1:8080`. Database and API ports bind to the local PC. The database uses the `etiket_postgres` Docker volume. `docker compose down` retains that volume. The default password `etiket_local_only` is for this local setup; `.env.example` shows how to override it.

If PostgreSQL is already installed, create a database/user and start the JAR with these PowerShell environment variables instead:

```powershell
$env:SPRING_PROFILES_ACTIVE='postgres'
$env:DB_URL='jdbc:postgresql://localhost:5432/etiket'
$env:DB_USERNAME='etiket'
$env:DB_PASSWORD='YOUR_LOCAL_DATABASE_PASSWORD'
./run-backend.bat
```

Use a JDBC URL, not a bare `postgres://` URL. The same migrations/importer run on PostgreSQL and H2. PostgreSQL integration tests start a real temporary database process without Docker; the first test run downloads its test binary. See `VALIDATION.md` for the actual tested versions and results.

The Dockerfile provides a portable Java 25 container for Railway/AWS. It builds the 0.3 backend from source and works with the existing Railway PostgreSQL variables. Applying local files does not deploy them: the configured Railway branch must receive the change. Use PostgreSQL rather than the container's local H2 file for persistent hosted data.

## 5. Open and build the source

- **IntelliJ IDEA:** open the folder containing `settings.gradle`. Choose JDK 25 for the project and Gradle JVM. The shared **Etiket API** run configuration launches the backend.
- **Android Studio:** open the same project, select JDK 25 as Gradle JDK, and install Android SDK Platform 36 / Build Tools 35.0.0.
- Gradle wrapper: 9.1.0. Spring Boot: 4.1.2. Android Gradle Plugin: 8.13.2. Backend: Java 25. Android/shared bytecode: Java 17 with Android desugaring.

```text
gradlew.bat -PbackendOnly :core:test :backend:test :backend:bootJar
gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Use `./gradlew` on Linux/macOS. `-PbackendOnly` omits Android, so API work needs no Android SDK. First builds need internet. A local build puts `etiket-backend-0.3.0.jar` under `backend/build/libs/`; the start scripts run that file. After changing backend code, rebuild it or run `:backend:bootRun`.

## 6. Understand the API and data

| Method / path | Behavior |
| --- | --- |
| `GET /api/v1/health` | Version and API availability |
| `GET /api/v1/catalogue/summary` | Dataset counts and review status |
| `GET /api/v1/catalogue/products?page=0&size=20` | Stable pagination; maximum size 100 |
| `GET /api/v1/products/{barcode}` | Barcode validation, starter-catalogue lookup, optional provider fallback |
| `GET /api/v1/products/demo` | Three clearly fictional legacy fixtures |

Products include `evidence.reviewStatus`, `ingredientsStatus`, `labelPhotoChecked`, source URLs, original ingredient section, notes and `externalCandidateAvailable`. Empty ingredients/nutrition/allergen fields mean **unknown**, not zero or allergen-free. No health score is calculated. An empty `quantity` means no structured size was captured; size text in product names is retained as reported.

- Found product with missing ingredients: **200**, `ingredientsStatus=MISSING`.
- Unknown in this test catalogue: **404**, `PRODUCT_NOT_IN_CATALOGUE`.
- Invalid barcode/check digit: **400**, `BARCODE_INVALID`.
- Invalid catalogue pagination: **400**, `CATALOGUE_PAGE_INVALID`.
- If the optional provider is enabled: missing there is 404, local quota 429, provider/network failure 503.

`catalogue/source.json` is our captured 77-record source. `scripts/prepare_catalogue.py` generates identical backend/Android seed assets, keeping foreign candidates separate from display ingredients. Python is only needed to update the catalogue:

```sh
python scripts/prepare_catalogue.py
python scripts/prepare_catalogue.py --check
```

Do not edit the generated assets independently. Update captured facts with source evidence, regenerate, then rebuild both applications. Each changed bundled seed is imported atomically on the next backend start. Removed seed products are removed from the seed table. This mechanism is not a production editorial system.

## 7. Optional external source

Open Food Facts is **disabled by default** (`OFF_ENABLED=false`). The 77-product tests therefore work without a third-party service. A known product with missing ingredients is returned as-is: the API does not merge an external recipe into it.

The retained experimental adapter can be enabled using `OFF_ENABLED=true` and a real contact-bearing `OFF_USER_AGENT`. It only handles codes outside the starter catalogue. Its v3.4 nutrition contract is pinned; migrate it with provider fixtures before relying on newer response structures. Positive provider results use a separate 24-hour cache and a process-local five-second lookup interval. Multiple deployed API instances need shared quota control. Provider data has its own attribution/reuse conditions. See `DATA_SOURCES.md`.

## What comes after this increment

Use the five barcode checks above on your actual phone. Then collect current package-label photos for a small set of these products and implement a reviewed submission workflow: capture → extract → compare → approve → publish. OCR and Azerbaijani explanations should use that evidence. They should not invent a missing recipe from a product name.
