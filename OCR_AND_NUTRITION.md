# OCR and nutrition contract — 0.6.2

## Private analysis

`POST /api/v1/analysis-jobs` accepts multipart `metadata` JSON and one JPEG/PNG `photo`. The client creates a random UUID `clientRequestId`, an independent random URL-safe `token` of 32–100 characters, `role` (`ingredients` or `nutrition`), and `language` (`az`, `tr`, `ru`, `en`, `de`, `und`). It receives HTTP 202 with `{id,status,result,errorCode}`. Repeating the same ID/capability/photo/language/role reuses the job; different content returns 409. Do not place the capability in URLs or logs.

`GET /api/v1/analysis-jobs/{id}` needs `Authorization: Bearer TOKEN`. A missing/wrong token or expired job returns 404. Responses use `Cache-Control: no-store`. No public endpoint lists these jobs or exposes their source photos. Reviewer credentials are never part of the Android client.

States: `QUEUED` → `PROCESSING` → `COMPLETED`, `RETAKE` or `FAILED`. One scheduled worker per replica claims a job transactionally. A two-minute lease recovers interruptions; at most two processing attempts are allowed. Retries are bounded and reuse the stored job. Jobs expire after 24 hours; cleanup runs with worker activity/creation. Source image bytes are released after a terminal result. Defaults cap retained jobs at 200 and queued/processing jobs at 8; this is a bounded pilot, not a large public OCR service.

A result snapshot contains the engine name, selected language, `rawText`, engine confidence, uncertain words, attempted image rotation, conservative nutrition suggestions and warnings. Engine confidence is not a calibrated probability of correctness. Text remains data. It is never an instruction to call another tool, alter access or publish.

Image limits are 2 MiB per file and 20 million source pixels, sanitised to a maximum 2048-pixel side. Android lets the user crop, rotate and straighten the label before uploading it. A new crop clears the previous analysis reference. The submission uses the same photo as the new analysis.

Nutrition jobs use a metadata-free lossless raster when it fits the 2 MiB limit, with the sanitised JPEG as a fallback. Photo matching uses the stored evidence JPEG digest so analysis and submission continue to link to the same photo.

The local executable has a 35-second total deadline, a bounded output and one CPU thread. It normalizes light print on dark labels before adjusting contrast and measuring coverage. It also tries deskew and quarter turns when needed. It uses the selected language's model. The Docker image installs five pinned models from tessdata_best. Model selection and arguments are fixed server-side.

Short or fragmentary text and incomplete image coverage can yield `RETAKE` with no nutrition suggestions. The private snapshot retains the original reading for diagnosis. Android hides the unusable draft and prompts for a new crop, a close-up or manual entry.

## Final submission and audit

The 0.4 multipart submission remains valid. The optional fourth part is `nutrition`. `metadata` can add:

```json
{
  "nutrition": {
    "basis": "PER_100_ML",
    "values": {"energyKcal": 60, "fat": 3, "protein": 3, "salt": 0}
  },
  "analysis": {
    "nutrition": {"id": "UUID returned for this draft", "token": "private capability"}
  }
}
```

The service verifies that each referenced result belongs to the capability and the exact sanitised image submitted for that role. Raw OCR is copied into a separate `submission_ocr` audit row in the submission transaction. The saved normalised draft has no capability. Review detail adds an `analysis` map of raw audit snapshots; neither it nor the capability is published in Product or PublicLabel. Corrected ingredient/nutrition fields stay separate from that audit.

## Nutrition

`NutritionLabel` has an explicit `basis` (`PER_100_G`, `PER_100_ML`, or empty when unknown) and a partial `values` map. Keys: `energyKcal`, `fat`, `saturatedFat`, `carbohydrates`, `sugars`, `fiber`, `protein`, `salt`. Energy uses kcal; the other fields use grams for the chosen reference amount. Missing keys are unknown; zero is retained. Negative, non-finite, unknown-key and grossly out-of-range values are rejected. Saturated fat cannot exceed total fat; sugars cannot exceed carbohydrates. Per-100-ml upper bounds allow a liquid to weigh more than 100 g.

Suggestions require an explicit 100 g or 100 ml heading. For tables with two separated amount headings, word positions tie values to the printed column and row. At least two eligible reads must identify the same reference column and serving amount and agree on the numeric value with its printed unit. Both headings need confidence of at least 45. If a heading is weak, the worker re-reads only the heading line and accepts the confirmation only when the basis, column order and other amount match exactly. Cells need confidence of at least 45 in both reads and at least 70 in one. A different legible value blocks that field even if two other reads match. Weakly recognised headings do not anchor values. A missing or unclear reference cell remains unknown even if the serving cell is readable. When fewer than four values are available, the upright-table fallback tries global thresholding and a separate image with only long, thin horizontal rules removed. The worker shares the same overall time budget across all passes. Optional reads that time out retain the completed raw draft. The original evidence image and raw text are never changed by numeric extraction.

A flat draft without clear column positions still requires an unambiguous single column. Percentages inside a value cell, inequalities, ranges, missing units and ambiguous references require manual entry. Suggestions use printed values and units without converting kJ, sodium, serving sizes or density. Only isolated vertical border marks are ignored; numbers, decimal separators and units are never guessed or repaired. Android preserves the raw OCR draft behind a toggle and opens a photo review with all eight editable fields. For the same reference, existing entries take precedence and suggestions fill blanks. Changing the review reference clears its entries. Applying replaces the form with the reviewed values; cancelling changes nothing. An unfinished review survives rotation. The same screen supports manual entry after an unreadable or empty OCR result.

Submitted values need a nutrition photo. Approval adds `nutrition` plus `nutritionPhotoChecked=true`; values require the reviewer's separate confirmation and the stored nutrition photo. Old approvals with no nutrition remain supported. A photo is public only after the existing approval transaction.

Product adds the explicit `nutrition` object. Existing per-100-g `nutrients` remains available for older clients. For a per-100-ml record it is empty, so an older client cannot silently label those values per 100 g. Updated Android serialization, API-origin resolution, catalogue caching and saved records preserve the explicit reference amount. No score is computed.

## References

- [Tesseract command-line use](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)
- [Tesseract installation and language data](https://tesseract-ocr.github.io/tessdoc/Installation.html)
- [Tesseract language models](https://github.com/tesseract-ocr/tessdata_best)
