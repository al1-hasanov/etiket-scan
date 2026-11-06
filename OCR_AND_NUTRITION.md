# OCR and nutrition contract — 0.5.3

## Private analysis

`POST /api/v1/analysis-jobs` accepts multipart `metadata` JSON and one JPEG/PNG `photo`. The client creates a random UUID `clientRequestId`, an independent random URL-safe `token` of 32–100 characters, `role` (`ingredients` or `nutrition`), and `language` (`az`, `tr`, `ru`, `en`, `de`, `und`). It receives HTTP 202 with `{id,status,result,errorCode}`. Repeating the same ID/capability/photo/language/role reuses the job; different content returns 409. Do not place the capability in URLs or logs.

`GET /api/v1/analysis-jobs/{id}` needs `Authorization: Bearer TOKEN`. A missing/wrong token or expired job returns 404. Responses use `Cache-Control: no-store`. No public endpoint lists these jobs or exposes their source photos. Reviewer credentials are never part of the Android client.

States: `QUEUED` → `PROCESSING` → `COMPLETED`, `RETAKE` or `FAILED`. One scheduled worker per replica claims a job transactionally. A two-minute lease recovers interruptions; at most two processing attempts are allowed. Retries are bounded and reuse the stored job. Jobs expire after 24 hours; cleanup runs with worker activity/creation. Source image bytes are released after a terminal result. Defaults cap retained jobs at 200 and queued/processing jobs at 8; this is a bounded pilot, not a large public OCR service.

A result snapshot contains the engine name, selected language, `rawText`, engine confidence, uncertain words, attempted image rotation, conservative nutrition suggestions and warnings. Engine confidence is not a calibrated probability of correctness. Text remains data. It is never an instruction to call another tool, alter access or publish.

Image limits are 2 MiB per file and 20 million source pixels, sanitised to a maximum 2048-pixel side. Android lets the user crop, rotate and straighten the label before uploading it. A new crop clears the previous analysis reference. The submission uses the same photo as the new analysis.

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

Suggestions require an explicit 100 g or 100 ml heading and one unambiguous number with its unit per nutrient row. A detected serving column prevents suggestions for the entire table because some rows may have lost a value during recognition. Multiple columns, serving headers, percentages, inequalities, ranges, missing units and ambiguous reference amounts are left for manual entry. No kJ→kcal, sodium→salt, serving-size or density conversion is inferred. The app keeps the original OCR draft and prompts for manual entry when there are no suggestions to copy.

Submitted values need a nutrition photo. Approval adds `nutrition` plus `nutritionPhotoChecked=true`; values require the reviewer's separate confirmation and the stored nutrition photo. Old approvals with no nutrition remain supported. A photo is public only after the existing approval transaction.

Product adds the explicit `nutrition` object. Existing per-100-g `nutrients` remains available for older clients. For a per-100-ml record it is empty, so an older client cannot silently label those values per 100 g. Updated Android serialization, API-origin resolution, catalogue caching and saved records preserve the explicit reference amount. No score is computed.

## References

- [Tesseract command-line use](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)
- [Tesseract installation and language data](https://tesseract-ocr.github.io/tessdoc/Installation.html)
- [Tesseract language models](https://github.com/tesseract-ocr/tessdata_best)
