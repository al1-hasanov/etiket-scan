# Etiket Scan v0.7.0 — deploy and phone verification

The update is source code, **not a compiled APK or AAB**. The old 0.6.4 APK will continue to work with the compatible v0.7.0 backend, but install the new APK to get the device-side offline lookup cache.

## 1. Apply the update to your dev branch (PowerShell)

Open PowerShell **inside your existing Etiket Git repository** (the directory with `settings.gradle`). Make sure you do not have uncommitted edits you wish to keep before extracting the changed-files archive.

```powershell
git switch dev
git pull origin dev
git switch -c feature/barcode-cache-0.7.0
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\Etiket_v0.7.0_Changed_Files.zip" -DestinationPath . -Force
git status
```

If you instead extract `Etiket_v0.7.0_Full_Source.zip`, open its `etiket-scan-dev` subdirectory as a complete standalone project. **Do not copy the old `dist/` JAR/APK as 0.7.0**; those are historical demo artifacts.

## 2. Build/test (JDK 25 required)

Set your IntelliJ/Android Studio Gradle JDK to **JDK 25** and install Android SDK Platform 36. Use the Gradle wrapper shipped in the repository.

```powershell
java -version
.\gradlew.bat --no-daemon -PbackendOnly :core:test :backend:test :backend:bootJar
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Expected local artifacts **after a successful build**:

- `backend/build/libs/etiket-backend-0.7.0.jar`
- `app/build/outputs/apk/debug/app-debug.apk`

On your connected Android phone (USB debugging enabled, `adb devices` lists it), install the debug app as an update:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

If Android rejects the update for an incompatible signing certificate, check the old package signature. Uninstalling the existing app will remove its local saved items; do that only after exporting or accepting this data loss. The project includes the existing development debug keystore; it is not a production signing key.

## 3. Commit and deploy dev

```powershell
git add .
git commit -m "Etiket v0.7.0: reliable barcode lookup and positive/negative caching"
git push -u origin feature/barcode-cache-0.7.0
```

Open a merge request into `dev`. After your backend checks pass, merge it and let Railway deploy the configured branch. Confirm the Railway project is using **PostgreSQL**, keeping `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and reviewer variables unchanged. Flyway V5 adds `product_lookup_misses` automatically and does **not** delete existing product cache, catalogue or reviewed labels.

Railway service variables to verify:

```text
OFF_ENABLED=true
OFF_USER_AGENT=Etiket/0.7.0 (https://github.com/al1-hasanov/etiket-scan)
OFF_CACHE_HOURS=24
OFF_MISS_CACHE_HOURS=6
OFF_STALE_CACHE_DAYS=7
OFF_COOLDOWN_SECONDS=15
OFF_MIN_INTERVAL_MILLIS=5000
```

Keep your existing `OFF_BASE_URL` or use the default official HTTPS endpoint. Use a real working project/contact URL in `OFF_USER_AGENT`. Do not include a password. Keep **one Railway API replica** for now; the conservative OFF quota guard is process-local, not distributed.

## 4. Verify backend and phone

```powershell
$base = "https://etiket-scan-production.up.railway.app"
Invoke-RestMethod "$base/api/v1/health"
Invoke-RestMethod "$base/api/v1/products/3017620422003"
```

Health should report `0.7.0`. The second endpoint may retrieve a genuine OFF record (if enabled), with an OFF source URL and any available nutrition/ingredient fields. It does not guarantee all nutrients exist. Repeat the same barcode: its product should come from the database cache, without a second OFF request. **Wait at least five seconds before testing a different uncached barcode** to respect the default quota guard.

On the v0.7.0 phone app, keep **Live product lookup** enabled, scan an externally known product, view its source and retrieved date, then temporarily disconnect the phone or make the configured API unavailable. Scan the **same** barcode: if the device cached it in the last seven days, you should see the cached result and a stale-data warning. A new, never-seen barcode must not be invented. Verify that changing the backend base URL does not reuse a different server's cached result.

For backend missing-cache testing, choose a valid barcode genuinely absent from OFF. Two requests within six hours should not cause two provider calls (inspect backend logging/metrics or use the stub integration test). After the TTL expires, it is eligible to be checked again. Provider 404 must remain distinct from provider outages.

## 5. Rollback notes

Do not delete the new cache table or reset PostgreSQL. You can redeploy the prior backend code if necessary; Flyway V5 remains safely present and the old code does not depend on the new table. The v0.7.0 Android app has the same package ID and preserves existing local data when installed as an update with the same signing certificate.

## Limitations

This does **not** add a new commercial product database, automatic photo verification, a distributed rate limiter or full multi-source search. V0.7.1 is the next phase for verified Azerbaijan-market catalogue imports.
