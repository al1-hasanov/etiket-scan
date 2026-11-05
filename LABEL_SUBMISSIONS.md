# Label submission API and storage

## Public routes

| Method / route | Contract |
| --- | --- |
| `POST /api/v1/submissions` | Multipart `metadata` JSON plus files `front`, `barcode`, `ingredients`. Returns 201 with a receipt. |
| `GET /api/v1/submissions/{id}/status` | Opaque receipt ID; returns status, barcode, dates and rejection note. It does not expose pending ingredients or photos. |
| `GET /api/v1/labels/{id}` | Approved snapshot and its photo URLs only; pending/rejected IDs return 404. |
| `GET /api/v1/labels/{id}/photos/{role}` | Approved sanitised JPEG only. |
| `GET /sources/labels/{id}` | Public approved source page linked from Product.sourceUrl. |

Example metadata (use a fresh random UUID for each draft):

```json
{
  "clientRequestId": "22ac4838-b4e4-4f60-8905-807ef16d4b40",
  "barcode": "5201360553457",
  "name": "Name copied from the actual package",
  "brand": "",
  "quantity": "65 g",
  "ingredients": "",
  "ingredientsLanguage": "az",
  "declaredAllergenStatement": "",
  "consentToPublish": true
}
```

The metadata part must have `Content-Type: application/json`; photo files may be JPEG or PNG. The Android client converts supported camera/gallery inputs to upright JPEG first. Other API clients should also correct pixel orientation before uploading: the server discards source metadata instead of relying on EXIF orientation at display time.

## Review routes

`/review`, its JavaScript and all `/api/v1/review/**` routes require the configured reviewer account. Browser approval/rejection also requires the CSRF token and corresponding session cookie.

1. `GET /api/v1/review/csrf` returns `{token, headerName}`. Retain the session cookie and pass the token in that header for decisions.
2. `GET /api/v1/review/submissions?status=PENDING&page=0` lists 25 receipts. Status may also be `APPROVED` or `REJECTED`.
3. `GET /api/v1/review/submissions/{id}` returns the original submitted fields, version, private photo URLs, current published submission ID and any reviewed snapshot.
4. `POST /api/v1/review/submissions/{id}/approve` accepts:

```json
{
  "version": 0,
  "name": "Exact package name",
  "brand": "Brand as shown",
  "quantity": "65 g",
  "ingredients": "Exact transcription from the submitted label",
  "ingredientsLanguage": "az",
  "declaredAllergenStatement": "",
  "expectedPublishedSubmissionId": "",
  "photosChecked": true
}
```

`photosChecked` is the trusted reviewer's explicit comparison of the barcode, size, variant and Azerbaijan market version. It is not an image-recognition result. If an approved record already exists for that barcode, copy its current `currentPublishedSubmissionId` into `expectedPublishedSubmissionId`; approval is rejected if it changed after the reviewer loaded the page. The web page handles this automatically.

5. `POST /api/v1/review/submissions/{id}/reject` accepts `{ "version": 0, "note": "What needs correction" }`.

A pending item can be approved or rejected once. Re-review or stale versions return 409. Corrections to approved products go through a new submission; previous approved source snapshots remain available so saved products retain their historical evidence link.

## Consistency and persistence

- Migration V2 adds `label_submissions`, `submission_photos`, `reviewed_products` and `submission_guard`. It leaves V1 and the starter source records unchanged.
- Photo bytes and metadata are committed in the same transaction as the submission. A receipt is returned only after commit.
- A repeated request ID with the same normalised metadata/photo fingerprint returns its existing receipt. Reusing that ID for different data returns 409. This makes retrying a draft after a lost connection safe.
- A database guard serialises capacity checks. The server permits two image-processing requests at a time, 2 MiB per source file, a 7 MiB multipart request and at most 20 million decoded source pixels. Stored photos are JPEG, at most 2048 pixels on the longest side, with source metadata discarded.
- Approval and publication are atomic. Row locks plus the unique published barcode prevent conflicting first approvals; the losing request stays pending.
- Lookup priority is fictional demo fixture, reviewed product, starter sample, then the optional provider/cache. The submission endpoint rejects fictional fixture barcodes.
- Catalogue pages/counts merge reviewed records over the starter set without duplicate barcodes. Counts are calculated in one SQL statement. Seed re-import does not touch reviewed records or photos.
- Reviewed products use source `REVIEWED_LABEL`, `LABEL_PHOTO_REVIEWED` evidence and `labelPhotoChecked=true`. Nutrients, grade and structured allergen lists remain unknown. The reviewer's exact allergen wording is kept separately.
- Public source links are relative to the API origin. Android resolves them against the receiving server before caching/saving the product, preserving the source origin even if Settings later changes.

## Expected errors

| HTTP | Examples |
| --- | --- |
| 400 | Invalid barcode/fields/language, no publication consent, missing or invalid raster photo, unconfirmed photo review. |
| 401 | Reviewer authentication required. |
| 403 | Missing/invalid CSRF token on a reviewer decision. |
| 404 | Unknown receipt, unpublished source or unavailable photo. |
| 409 | Conflicting request ID, already reviewed item or changed published version. |
| 413 | File/request size or image-dimension limit exceeded. |
| 429 | Pilot queue/storage capacity or image-processing concurrency limit. |
| 503 | Reviewer password has not been configured. Product lookup remains available. |

Only product and package information is requested. No contact account is required for this pilot. Reviewer identity is recorded privately with the decision; the public source page shows the review date and package evidence. This version has a bounded anonymous submission queue and one server-configured reviewer, not a general multi-user moderation system.

Implementation references: [Spring multipart requests](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [Android TakePicture](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture), [Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider), [AndroidX EXIF](https://developer.android.com/jetpack/androidx/releases/exifinterface).
