# Etiket 0.7.1 verified catalogue import

## Deploy

Open PowerShell in your existing Etiket Git repository with JDK 25 active. Download `Etiket_v0.7.1_Changed_Files.zip` to Downloads.

```powershell
if (git status --porcelain) { throw "Commit or stash your local changes first." }
git switch dev
git pull --ff-only origin dev
git switch -c feature/catalogue-import-0.7.1
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\Etiket_v0.7.1_Changed_Files.zip" -DestinationPath . -Force
.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" --no-daemon -PbackendOnly :core:test :backend:test :backend:bootJar
if ($LASTEXITCODE -ne 0) { throw "Backend checks failed." }
git diff --check
git add -A
git commit -m "Add verified catalogue CSV import in Etiket 0.7.1"
git push -u origin feature/catalogue-import-0.7.1
```

Run the PowerShell lines as separate commands. Open a pull request into `dev`, then merge it after CI passes. Let Railway deploy the branch to the service using your existing PostgreSQL connection. Flyway V6 creates the imported-product table on startup. Check `/api/v1/health` on your Railway HTTPS domain for `UP` and version `0.7.1`.

Use the 0.7.0 Android APK with the same API address and live lookup enabled.

## Review and publish a CSV

1. Sign in at `/review`, expand **Import verified products**, and choose **Download CSV template**.
2. Fill the template with Azerbaijan-market products checked against package photos. Save a comma-separated UTF-8 CSV. Put each barcode in a text column to preserve leading zeroes. Use up to 500 rows and 1 MB per batch.
3. Choose the file and click **Preview products**. Open a row to compare the values with its source. Fix or remove invalid and conflicting rows in the CSV, then preview again.
4. Check the confirmation and click **Publish products**. Rescan a new barcode in Etiket. The product should show **Reviewed label photo**, the verified facts you supplied, and a link to its package evidence.

| CSV field | What to enter |
| --- | --- |
| `barcode` | Required GTIN from the package. Etiket accepts EAN-8, UPC-A, EAN-13 and GTIN-14 and validates the check digit. |
| `name`, `pack_size` | Required product name and net quantity with unit, such as `400 g` or `500 ml`. |
| `source_url` | Required HTTPS link to the label photos you checked and can cite publicly. |
| `brand` | Optional brand as printed on the package. |
| `ingredients`, `declared_allergens` | Optional label transcriptions. Quote values containing commas, newlines or quotation marks. Double any literal quotation marks inside a quoted field. |
| `ingredients_language` | Use the label language code, such as `az`, when you supply ingredients or allergen text. |
| `nutrition_basis` | Use `PER_100_G` or `PER_100_ML` when supplying nutrition. |
| `energy_kcal`, `fat_g`, `saturated_fat_g`, `carbohydrates_g`, `sugars_g`, `fiber_g`, `protein_g`, `salt_g` | Optional values per stated basis. A blank means unknown. Enter zero where the label explicitly states zero. Use a decimal point or quote a decimal comma. |

Publish applies the batch in one transaction. A repeated identical row shows **Already imported**. A changed row for an existing barcode blocks the batch. Use the package-photo review workflow to publish a correction. An approved photo submission has priority over the CSV entry for that barcode.

## Reviewer API

Use `POST /api/v1/review/catalogue-import` with reviewer authentication, the existing CSRF header, and the UTF-8 bytes as a `text/csv` body. The default request previews and returns row states and a digest. To publish, resubmit the same bytes with `dryRun=false&confirmed=true&digest=<preview-digest>`. A result with `published=false` and blocked rows reports conflicts without applying a batch. The endpoint records the reviewer and review time in the stored source record.
