# Etiket 0.6.4

This update applies to the 0.6.3 source at `a423b862` from the supplied dev archive. The backend creates the nutrition report table with Flyway V4 on startup. Existing product and review data keep their original records.

## Deploy from PowerShell

Start in the repository with a clean working tree and the downloaded changed-files ZIP in Downloads. Use JDK 25.

```powershell
cd "$env:USERPROFILE\Downloads\EtiketScanner_v0.2"
git status --short
git switch dev
git pull --ff-only origin dev
git switch -c feature/nutrition-enrichment-0.6.4
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\Etiket_v0.6.4_Changed_Files.zip" -DestinationPath . -Force
.\gradlew.bat --no-daemon -PbackendOnly :core:test :backend:test :backend:bootJar
if ($LASTEXITCODE -ne 0) { throw "Backend checks failed" }
git diff --check
git add -A
git diff --cached --stat
git commit -m "Add sourced nutrition lookup and imports in Etiket 0.6.4"
git push -u origin feature/nutrition-enrichment-0.6.4
```

Open a PR into `dev` and wait for CI. Merge into the branch Railway deploys, which can be `dev` if configured that way. Keep the existing PostgreSQL and reviewer variables. For optional barcode enrichment, set `OFF_ENABLED=true` and a contact-bearing `OFF_USER_AGENT` such as `Etiket/0.6.4 (https://github.com/al1-hasanov/etiket-scan)`. The importer works with the provider disabled.

```powershell
Invoke-RestMethod 'https://etiket-scan-production.up.railway.app/api/v1/health'
```

Expect `UP` and version `0.6.4`.

## Import a feed

Open [the reviewer page](https://etiket-scan-production.up.railway.app/review), sign in with the existing reviewer account, and open **Import nutrition reports**. Select a JSON file, choose **Preview import**, inspect any conflicts, and choose **Apply import**.

The example is suitable for preview in a local test environment. Use a permitted feed with its actual source URL and observation date when applying to a public catalogue.

```json
{
  "sourceId": "example-retailer",
  "kind": "RETAILER_REPORTED",
  "name": "Example retailer",
  "observedAt": "2026-11-09T00:00:00Z",
  "records": [
    {
      "barcode": "4760177560085",
      "packSize": "400 g",
      "sourceUrl": "https://example.test/products/n1-sor",
      "nutrition": {
        "basis": "PER_100_G",
        "values": { "energyKcal": 224.8, "fat": 18, "protein": 13 }
      }
    }
  ]
}
```

Use a stable lowercase `sourceId` per feed and either `RETAILER_REPORTED` or `MANUFACTURER_REPORTED`. Each file can contain 1 to 500 distinct known barcodes with an explicit pack size, HTTPS source and nonempty nutrition panel. Set an ISO-8601 observation time with a time zone. Energy is in kcal and the other fields are in grams. The accepted keys are `energyKcal`, `fat`, `saturatedFat`, `carbohydrates`, `sugars`, `fiber`, `protein` and `salt`. Set the basis to `PER_100_G` or `PER_100_ML` and omit unknown values.

The API is `POST /api/v1/review/nutrition-import?dryRun=true` with the file as a JSON request body. Applying requires `dryRun=false`. Both requests use the existing reviewer authentication and CSRF session. A batch applies atomically. Repeat the same file to retry, and use a later observation time for a correction from the same source.

## Check the scan result

- Scan a known barcode from the imported feed. The nutrition card shows its basis and **Retailer reported** or **Manufacturer reported**, with a source link.
- A package-checked panel wins when present. Etiket displays a single source panel and leaves partial fields unknown.
- A pack size mismatch or differing values among stored reports holds the panel for package review. Conflict checks use feeds already imported and the last saved provider report, when available. Preview and apply make local checks.
- With Open Food Facts enabled, an empty local panel can use a provider report with matching barcode, pack size and Azerbaijan market tag. It needs explicit 100 g or 100 ml values as sold. The provider cache retains old identity data during the upgrade and uses the new panel format when refreshed.
- A known product remains available if the external provider has a miss or outage. For a missing or conflicted panel, use **Submit label photos**. A reviewer can compare and publish the nutrition photo using the existing review queue.

```powershell
Invoke-RestMethod 'https://etiket-scan-production.up.railway.app/api/v1/products/4760177560085' |
    Select-Object barcode, nutrition, nutritionSource | ConvertTo-Json -Depth 5
```

## Android test and Play bundle

Fresh installations default to the production Railway URL with live lookup enabled. Existing installations keep saved Settings. The test APK uses the existing development signature. For Play internal testing, build the release bundle and sign with your Play upload key.

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:bundleRelease
jarsigner -keystore "$env:USERPROFILE\etiket-upload.jks" -signedjar "$env:USERPROFILE\Downloads\Etiket_v0.6.4_Play.aab" ".\app\build\outputs\bundle\release\app-release.aab" etiket-upload
jarsigner -verify "$env:USERPROFILE\Downloads\Etiket_v0.6.4_Play.aab"
```

Replace the keystore path and alias with your upload key. The command prompts for the password. You can also use **Generate Signed Bundle / APK** in Android Studio. Upload the signed bundle to the internal testing track for your app in Play Console. [Android build and signing documentation](https://developer.android.com/build/building-cmdline)
