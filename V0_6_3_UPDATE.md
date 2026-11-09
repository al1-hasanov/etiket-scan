# Apply 0.6.3 OCR diagnostics

This is a backend-only changed-files overlay on Etiket 0.6.2. Keep Android 0.6.2 installed. The source ZIP paths begin at the root containing `settings.gradle`, `backend` and `app`. It adds no database migration, new OCR provider or external API key.

Save `Etiket_v0.6.3_Changed_Files.zip` in Downloads, start with a clean working tree, then run in Windows PowerShell:

```powershell
cd "$env:USERPROFILE\Downloads\EtiketScanner_v0.2"
git switch dev
git pull --ff-only origin dev
git switch -c feature/ocr-diagnostics-0.6.3
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\Etiket_v0.6.3_Changed_Files.zip" -DestinationPath . -Force
.\gradlew.bat --no-daemon -PbackendOnly :core:test :backend:test :backend:bootJar
```

After the build succeeds:

```powershell
git diff --stat
git add .
git commit -m "Add correlated OCR diagnostics for 0.6.3"
git push -u origin feature/ocr-diagnostics-0.6.3
```

Create and merge the pull request into `dev`. Merge into Railway's configured deployment branch too if it watches another branch. Existing Backend CI enables the native engine tests with the pinned language models. The native tests are skipped on local runs unless `ETIKET_OCR_ENGINE_TEST=true` and the engine/models are installed.

In Railway's application-service Variables add:

```text
OCR_DIAGNOSTICS=true
```

Apply the variable change and wait for successful deployment. Check:

```powershell
Invoke-RestMethod "https://etiket-scan-production.up.railway.app/api/v1/health"
```

Expected: `UP`, `0.6.3`.

Keep the phone API address as `https://etiket-scan-production.up.railway.app`. Select the photo again in nutrition section 4 to create a fresh OCR job, then read it. Search Railway deployment logs for `OCR_DIAGNOSTIC` and follow the new job ID. `OCR_DIAGNOSTICS.md` explains the messages and which lines to copy for diagnosis.
