# Etiket 0.4 — apply, deploy and test

This update is based on your uploaded `etiket-scan-dev(1).zip`, matching GitHub `dev` at `18da96b824d0dc1c67d87c700427f6b8dc3ad6fa`. The existing GitHub Backend CI workflow and starter catalogue are preserved.

## 1. Apply the changed files

Stay on `feature/label-submissions` if you already created it. Otherwise:

```powershell
git switch dev
git pull --ff-only origin dev
git switch -c feature/label-submissions
```

Extract the **contents** of `Etiket_v0.4_Changed_Files.zip` into your existing project root, next to `settings.gradle`. Merge folders and replace files. Do not create a nested project or replace `.git`.

Run the backend checks:

```powershell
.\gradlew.bat --no-daemon -PbackendOnly :core:test :backend:test :backend:bootJar
```

Flyway migration **V2** adds submission, photo and reviewed-product tables. It does not reset PostgreSQL, edit migration V1, alter the captured source JSON, or delete existing products.

## 2. Configure the reviewer in Railway

In your existing project **diplomatic-flow**, open the **etiket-scan application service → Variables**. Add:

| Variable | Value |
| --- | --- |
| `REVIEW_ADMIN_PASSWORD` | Your own strong password, at least 12 characters and no more than 72 UTF-8 bytes. Use a unique password of 16 or more ASCII characters for this demo. |
| `REVIEW_ADMIN_USERNAME` | Optional. Default: `reviewer`. |

Keep this password in Railway Variables. Do not put it in Android Settings, the APK, source files, screenshots, Git commits or the PostgreSQL service variables. No real password is included in this update. Test fixture credentials are for isolated automated tests only.

Keep the existing PostgreSQL variables and `SPRING_PROFILES_ACTIVE=postgres`. **No new database, bucket, volume or external API is required for 0.4.** The three sanitised photos are stored in PostgreSQL as bounded binary records. A future larger catalogue can move photo bytes to object storage.

A missing reviewer password intentionally leaves normal product lookup working but returns `REVIEW_NOT_CONFIGURED` for new submissions and the review page. A configured password shorter than the stated minimum prevents startup so an accidentally weak account is not enabled.

## 3. Push and merge

After reviewing `git status` and `git diff`:

```powershell
git add .
git commit -m "feat: add label photo submissions and review"
git push -u origin feature/label-submissions
```

Create a pull request with **base `dev`** and **compare `feature/label-submissions`**. Wait for **Backend tests**, review the changes, then merge. Railway uses the existing deployment workflow and Wait for CI setting.

Check [health](https://etiket-scan-production.up.railway.app/api/v1/health) reports `0.4.0` and Railway logs show successful migration V2. Before any real approval, the catalogue is still 77 / 10 with ingredients / 67 missing / 0 label-photo-reviewed.

## 4. Install and connect Android

Install **Etiket_Demo_v0.4.apk** over your existing demo. The application ID and development signing key are unchanged, with versionCode increased to 4. Preferences, history and saved products remain available.

In Settings use this API base address, without `/api/v1`:

```text
https://etiket-scan-production.up.railway.app
```

Enable **Canlı məhsul axtarışı / Live product lookup**. The new upload and review flow needs the 0.4 backend; installing the APK by itself does not update Railway. Use the 0.4 APK for the new reviewed-label source type.

## 5. Submit one real package

1. Choose an actual product version offered in Azerbaijan. Open a product and tap **Etiket fotosu göndər / Submit label photos**, or use **Məhsul məlumatı əlavə et / Add product information** from Home. An unknown barcode also offers this flow.
2. Check the barcode, name, brand and pack size. Select the language actually printed on the ingredient label. Other languages are kept as original text; 0.4 does not translate them.
3. Take or choose three photos: **front**, **barcode**, **ingredient label**. A clear back photo containing both barcode and ingredients may be selected for both roles. Photograph the whole relevant text sharply. Avoid faces, receipts and personal details.
4. Optionally expand **Add label text** and type an exact transcription. This is a convenience for the reviewer; it does not publish automatically. You may leave these text fields blank and let the reviewer transcribe the photos.
5. Select the consent to publish the product information and package photos after approval, then tap **Yoxlamaya göndər / Submit for review**.
6. Open **Göndərdiklərim / My submissions**. The confirmed status is **Awaiting review**. If a connection was lost before confirmation, **Upload not yet confirmed** is distinct from a confirmed pending submission; refresh its status or retry the same draft.

Photos are limited to 2 MiB each after processing. Android corrects camera orientation and removes source metadata when preparing JPEGs. The backend validates, bounds and re-encodes the images again before storing them.

## 6. Review in your computer browser

Open:

```text
https://etiket-scan-production.up.railway.app/review
```

The browser asks for your username (`reviewer` by default) and the password you set in Railway.

Select the pending submission. Open the photos at full size and compare the barcode, exact variant, size and Azerbaijan market version. A similarly named foreign-market recipe is not evidence for the local product. If the images do not establish the match, reject the submission and explain what needs correction.

Enter the product name, pack size, **original ingredient text and language**, and the exact allergen statement if shown. Leave unknown allergen statements blank. Confirm the photo comparison and click **Approve and publish**.

The review screen uses HTTP Basic authentication and a CSRF token for decisions. The app has no reviewer password or approval capability. An existing approved version requires explicit comparison with that active record; a stale reviewer tab cannot silently overwrite a newer approval.

## 7. Confirm the result

- Refresh the catalogue in the app or scan the barcode again. The new source should say **Etiket fotosu ilə yoxlanıb / Checked against label photos**.
- Open **Open reviewed label photos**. Approved source photos are visible publicly; pending and rejected photos remain private to the reviewer.
- Azerbaijani descriptions still come from the shared dictionary. Unknown words remain unknown. Foreign-language label text is not translated, and no nutrient values or health scores are invented.
- In **My submissions**, refresh and confirm **Approved**. A rejected submission shows the reviewer's reason and does not change the live product.
- Save the approved product and reopen it without internet. The product text, evidence and explanations remain available. Viewing the remote photo page needs internet.
- Restart/redeploy the application service and scan it again. Reviewed products and images remain in PostgreSQL and are separate from the replaceable starter import.

If you approve a missing-ingredient product already in the starter set, total products stays 77, ingredient lists becomes 11, missing becomes 66 and label-photo-reviewed becomes 1. Approving a new barcode also increases the total product count. Exact totals depend on your actual approvals.

## Local development

After building the JAR, set a temporary local reviewer password in the same PowerShell terminal, then start it:

```powershell
$env:REVIEW_ADMIN_PASSWORD='REPLACE_WITH_YOUR_OWN_LOCAL_PASSWORD'
.\run-backend.bat
```

The review page is `http://127.0.0.1:8080/review`. Local HTTP is supported for development. Public use should be through the existing Railway HTTPS address. For Compose, copy `.env.example` to your ignored `.env`, choose passwords there, then run `docker compose up --build`.

The start scripts now run `backend/build/libs/etiket-backend-0.4.0.jar`. Old `dist/` files remain historical 0.2 binaries.

To build Android yourself, use JDK 25, Android SDK Platform 36 and Build Tools 35.0.0:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Pilot limits

This is a single-reviewer pilot. Defaults accept at most **100 total submissions** and **25 pending** (`SUBMISSIONS_MAX_TOTAL` and `SUBMISSIONS_MAX_PENDING`). Rejected submissions remain in the audit history and count toward the total. Images are stored in the database, so include them in your database backup/storage planning. Move photo storage and strengthen public-upload controls before a large public rollout.

No OCR, automatic translation, automatic approval, user-account system or production editorial team is included. The first target remains ten genuinely reviewed packages; synthetic test fixtures are not inserted into the shipped catalogue or your production database.
