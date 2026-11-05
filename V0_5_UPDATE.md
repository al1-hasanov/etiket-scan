# Etiket 0.5 — OCR drafts and reviewed nutrition

Built from the `etiket-scan-dev (1).zip` supplied for this iteration. Apply this release over that 0.4 project. The source snapshot, starter catalogue and Flyway V1/V2 are preserved.

## What changes for shoppers

On the package submission form, choose an ingredient photo and tap **Fotodan mətni oxu / Read text from photo**. The original recognised text appears below it, with words that need checking. **Use extracted ingredient text** asks before replacing the editable ingredient field. Correct it against the package.

A fourth, optional **nutrition-table photo** has its own reading action. **Use suggested nutrition values** fills only a conservatively recognised draft and asks before replacing existing edits. Choose **100 g** or **100 ml** as printed on the package. Enter remaining values manually or leave them empty. Entering nutrition values requires a nutrition photo; the same clear back-panel image can be selected for ingredients and nutrition.

An unreadable photo produces a retake message; an unavailable engine leaves manual transcription available. This release reads original-language text. It does not translate, approve automatically, assign health scores, recommend alternatives or charge subscriptions. Those remain later increments toward the planned shopper experience.

## 1. Apply and build

In your existing checkout:

```powershell
git switch dev
git pull --ff-only origin dev
git switch -c feature/ocr-nutrition
```

Extract the **contents** of `Etiket_v0.5_Changed_Files.zip` into the project root containing `settings.gradle`. Merge directories and replace files. Do not replace `.git`. Alternatively use the complete `EtiketScanner_v0.5.zip` as a separate source checkout.

Run:

```powershell
.\gradlew.bat --no-daemon -PbackendOnly :core:test :backend:test :backend:bootJar
```

Real-engine tests additionally run when `ETIKET_OCR_ENGINE_TEST=true` and Tesseract/language packs are installed. The updated GitHub workflow installs them and runs those checks. All other core, HTTP, database, review and Android checks remain independent of an OCR installation.

The new executable is `backend/build/libs/etiket-backend-0.5.0.jar`. Start scripts now target that file. Historical `dist/` 0.2 binaries are unchanged; they are not this release.

## 2. Deploy through the existing workflow

Review the diff, commit, push and create a pull request into the branch Railway already deploys (`dev` in the existing workflow). Wait for Backend tests, then merge as before.

Keep existing PostgreSQL variables, `SPRING_PROFILES_ACTIVE=postgres`, and the reviewer account variables. **No new secret or paid OCR account is required.** The Dockerfile installs Tesseract plus Azerbaijani, Turkish, Russian, English and German language packs. It runs one OCR process at a time per application replica with one Tesseract CPU thread.

Migration **V3** adds OCR jobs, immutable OCR audit snapshots and an optional nutrition-photo table. It leaves V1/V2 and existing submissions/products intact. Approved records are still separate from the starter import. Back up the existing database as part of your normal release process.

Optional settings:

| Variable | Default | Purpose |
| --- | --- | --- |
| `OCR_ENABLED` | `true` | Disable new OCR and its worker while retaining manual submissions |
| `OCR_COMMAND` | `tesseract` | Local executable path; useful on Windows |
| `OCR_MAX_PENDING` | `8` | Maximum queued/processing analysis jobs |
| `OCR_MAX_JOBS` | `200` | Maximum retained jobs in their 24-hour window |

A missing reviewer password disables OCR creation as well as the existing submission flow. Job polling requires the private per-draft capability retained by the app, not the reviewer password.

Verify `https://etiket-scan-production.up.railway.app/api/v1/health` returns `0.5.0`, and logs show successful V3. The unchanged seed is still 77 / 10 ingredient lists / 67 missing / 0 photo-reviewed before your real approvals. Existing approvals change those totals.

## 3. Install the APK

Install **Etiket_Demo_v0.5.apk** over the previous demo. Application ID remains `az.etiket.scanner.demo`; versionCode is 5 and the development signing key is retained. Existing settings, saved products and history remain readable.

Enable live lookup and use this base address, without `/api/v1`:

```text
https://etiket-scan-production.up.railway.app
```

The OCR actions need the 0.5 server. A 0.4 API still supports the earlier manual flow but has no analysis endpoint.

## 4. Phone acceptance test

1. Select an actual package sold in Azerbaijan. Enter its barcode, name and size. Choose the ingredient label's language; supported OCR choices are AZ/TR/RU/EN/DE. Other uses a combined AZ/TR/RU/EN model and needs especially careful checking.
2. Add the three required package photos. Tap **Read text from photo** in the ingredient section. Check the unedited OCR preview, including accented letters, ingredient order and E-codes.
3. Tap **Use extracted ingredient text**, confirm replacement and correct the editable draft. Rotating the screen should preserve the draft and pending/completed analysis. A replacement photo clears its old OCR result.
4. Add the nutrition photo, read it and inspect the original text. Apply suggestions only after checking whether the label is per 100 g or per 100 ml. Blank fields mean unknown, and a printed zero can be entered as zero. Per-serving or ambiguous multi-column tables need manual entry from the correct column.
5. Select publication consent, submit, and confirm **Awaiting review** in My submissions.
6. At `/review`, compare all package photos. Expand **Original automatic reading** to compare raw OCR with the submitted corrections. Check the nutrition basis/units/values and tick the separate nutrition-photo confirmation. Approve.
7. Scan again in live mode. Confirm the published ingredients and nutrition, the correct 100 g/100 ml caption, and all approved source photos. Save and reopen offline. The raw OCR audit and private job capability must not appear in the public product/source page.
8. Try a blurred photo, a sideways label, a dropped connection and repeated taps. Retake/manual-entry messages should be understandable. A long job can be checked again using the same read button; it reuses the existing job.
9. Restart/redeploy and verify the approved product, exact photo and nutrition values remain available. Confirm an old saved product and an earlier approved 0.4 label still open.

If an analysis has expired after 24 hours, run it again. If a submission reports expired/changed analysis, its old references are cleared so a subsequent manual submission can keep your corrected fields. A server restart may take up to the two-minute worker lease to recover an interrupted job. Two failed processing attempts end the job; a deliberate retry creates a new job.

## Local Windows OCR

The backend and its normal tests still build without Tesseract. To exercise automatic reading locally, install Tesseract 5 and the `aze`, `tur`, `rus`, `eng`, `deu` language files, then put the executable on PATH or set `OCR_COMMAND` to its full path. Keep its normal tessdata/configuration directory. Check `tesseract --list-langs`. No Python OCR service is required. Docker Compose already installs the engine in the API image.

## Release scope and evidence

Automated OCR checks use generated text images, including AZ/TR/RU/EN, rotated text and a blank image. They validate the engine integration and error paths, not accuracy on curved, glossy, blurred or small real packaging. Use the first ten genuine packages to record exact transcription errors and decide what recognition improvements are needed next. See `VALIDATION.md` for the checks actually run.
