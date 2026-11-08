# Apply and deploy Etiket 0.6.2

This changed-files archive is an overlay for the uploaded `etiket-scan` dev snapshot `372a99365bc6dc100f659be83718112d2715bb3f` (Android 0.6.1 / backend 0.6.0). Its paths begin at the project root. It contains source, tests, package-photo fixtures and documentation. The existing Gradle wrapper and demo keystore stay in your checkout.

## Windows PowerShell

Save the ZIP in Downloads. From the project root, first confirm that `git status` is clean, then run:

```powershell
cd "$env:USERPROFILE\Downloads\EtiketScanner_v0.2"
git switch dev
git pull --ff-only origin dev
git switch -c feature/ocr-review-0.6.2
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\Etiket_v0.6.2_Changed_Files.zip" -DestinationPath . -Force

.\gradlew.bat --no-daemon -PbackendOnly :core:test :backend:test :backend:bootJar
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

git diff --stat
git add .
git commit -m "Improve nutrition OCR and photo review for 0.6.2"
git push -u origin feature/ocr-review-0.6.2
```

Create a pull request from `feature/ocr-review-0.6.2` into `dev`. Merge after the Backend CI check passes. If Railway's application service watches `dev`, this starts deployment. If it watches another branch, merge the tested update into that branch through your usual pull request. Check the configured branch in Railway's application service → Settings → Source. PostgreSQL settings and `REVIEW_ADMIN_PASSWORD` stay in place; this release adds no migration or external API key.

Backend tests on Windows run without the optional native engine checks by default. GitHub CI sets `ETIKET_OCR_ENGINE_TEST=true`, installs Tesseract and the pinned five language packs, and includes the original-photo regression. To enable that test locally, install Tesseract, run `scripts/install-ocr-models.sh` in a compatible shell and point `TESSDATA_PREFIX` at its model directory before setting the environment flag.

## Confirm the deployment

```powershell
Invoke-RestMethod "https://etiket-scan-production.up.railway.app/api/v1/health"
```

Expected: `status=UP` and `version=0.6.2`. Railway builds `etiket-backend-0.6.2.jar` through the updated Dockerfile. Local scripts use that same JAR filename.

Install `Etiket_Demo_v0.6.2.apk` over the existing demo, or install your locally built `app\build\outputs\apk\debug\app-debug.apk`. Version code is 10, version name is `0.6.2-ocr`, and the demo application ID and signing key are retained.

Use `https://etiket-scan-production.up.railway.app` as the app's API address. No endpoint suffix is needed.

## Phone checks

1. Start a package draft. Choose **Deutsch** for this German package and put its nutrition photo under **4. Qida dəyərləri fotosu / Nutrition table photo**. Keep the heading, row names and values in the crop.
2. Select the photo again for a fresh OCR job. Completed jobs from a previous deployment retain their saved output.
3. Tap **Fotodan mətni oxu / Read text from photo**, then **Review suggested nutrition values**. If no reliable suggestions exist, use **Enter nutrition values from photo**.
4. Compare the photo with the 100 g or 100 ml entries. Zoom with the buttons or gestures. Correct numbers and leave unknown fields blank. Existing entries with the same reference take precedence over OCR suggestions.
5. Rotate while editing. Corrections should remain. Cancel once and check that the form did not change. Open again and apply the reviewed values.
6. Confirm that changing the review's reference clears its numbers, zero remains zero, and a saturated-fat value larger than total fat cannot be applied.
7. Open **Show recognised text** if you need the original draft; the long text starts collapsed.
8. For one genuine catalogue contribution, submit the corrected product, review its photos at `/review`, confirm nutrition separately and approve it. Scan that barcode in live mode to verify the approved values. Use a local/test deployment for synthetic workflow tests.

The purple photo is still a partial read. Manual corrections remain part of this release. See `VALIDATION.md` for the measured results and limits.
