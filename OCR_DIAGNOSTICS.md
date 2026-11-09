# Diagnose a scan in 0.6.3

This release adds logs to backend 0.6.2's OCR pipeline. Keep the 0.6.2 Android APK. Recognition thresholds and extraction behavior are unchanged.

## Enable on Railway

1. Deploy the changed files and confirm `/api/v1/health` reports `0.6.3`.
2. On the application service, add `OCR_DIAGNOSTICS=true` under Variables and apply/redeploy the change. Startup prints `OCR_DIAGNOSTICS enabled=true`.
3. If you configured a higher logging threshold, set `LOGGING_LEVEL_AZ_ETIKET_BACKEND=INFO` too.
4. On the phone, select the nutrition photo again in section 4, select Deutsch, crop with the reference heading and row names, then tap Read text from photo. Selecting the photo again clears the old analysis ID so the backend performs a fresh scan.
5. Open the application service's deployment logs. Search for `OCR_DIAGNOSTIC`. Find the latest `event=job_queued` whose `role` field is `nutrition`. Follow that `jobId` through `job_started`, `pass_started`, rejection decisions and `job_result` or `job_failed`.
6. Copy the lines for that job, including the terminal summary, to investigate the failed read. After the investigation, set `OCR_DIAGNOSTICS=false` to disable detailed events.

A saved result produces `event=job_reused` and can retain an older reader's result. It will not generate new native-pass events. Select the photo again to obtain a new analysis ID.

The log fields use job IDs, processing attempts and pass IDs (`p1`, `p2`, ...). Nested heading checks have their own pass IDs. `elapsedMs` measures time since that upload/worker context began; each `pass_finished` adds `durationMs` for the pass, including its nested heading verification. Confidence is an OCR engine score, not a probability of correctness. `coverage` is the fraction of detected ink covered by recognized word boxes.

## Read the result

| Event or reason | Meaning |
| --- | --- |
| `image_decoded width=... height=...` | Dimensions of the sanitized image sent to OCR. A tiny crop may explain lost characters. |
| `pass_started variant=... psm=... threshold=...` | The preprocessing/read variant and native engine settings. |
| `best_read usable=false` | The best draft failed readability or ink-coverage checks. |
| `nutrition_mode reason=INGREDIENTS_ROLE` | This job used the ingredients photo flow. |
| `nutrition_mode reason=NO_TABLE_LAYOUT fallback=FLAT_TEXT` | The table layout was unavailable. The conservative single-column text parser will be used. |
| `NO_TWO_COLUMN_REFERENCE_HEADING` | A pair of reference/serving headings was not found in this pass. A clear single-column table can still use flat parsing. |
| `table_heading ... confirmed=false` | At least one amount heading scored below the required confidence; a focused heading re-read may follow. |
| `HEADING_DISAGREEMENT` | Reads disagree on the reference basis, column order or serving amount. |
| `CUT_ROW_LABEL` / `CROSSES_COLUMN_BOUNDARY` / `VALUE_NOT_ALIGNED` | A reference cell's bounding box could not be assigned safely to its column. |
| `UNKNOWN_NUTRIENT_LABEL` | The row name did not match the supported nutrient aliases. |
| `MISSING_OR_UNSUPPORTED_UNIT` | The cell lacks a recognizable unit; for example, the engine may have mistaken g for 9. |
| `EMPTY_REFERENCE_CELL` | No usable value remained in the reference column. |
| `INVALID_VALUE_SHAPE` | Text, punctuation, multiple numbers or malformed brackets prevented a strict numeric cell match. |
| `OUT_OF_RANGE` | A parsed value exceeded the supported range. |
| `DUPLICATE_NUTRIENT_ROW` | Multiple rows mapped to one nutrient. |
| `INSUFFICIENT_CONFIRMED_READINGS` | Fewer than two eligible table readings had confirmed headings. |
| `NO_VALID_CELL` / `ONLY_ONE_VALID_READING` | The nutrient could not be confirmed across two reads. |
| `LOW_CELL_CONFIDENCE` / `NO_CLEAR_READING` | The numeric confidence checks failed. |
| `VALUE_DISAGREEMENT` | Eligible reads produced different numbers. The value stays unknown. |
| `engine_failed reason=NONZERO_EXIT exitCode=...` | The native engine exited unsuccessfully. Check the deployed engine/models/configuration. |
| `pass_failed reason=TIMEOUT` | That pass exhausted the shared time budget. |
| `job_failed ... nextStatus=QUEUED` | The existing bounded retry will run again. Attempt numbers distinguish retries. |
| `job_result status=COMPLETED suggestionCount=0` | A readable text draft was produced, with no reliable nutrition suggestions. |
| `job_result status=RETAKE` | Text quality/coverage was inadequate; nutrition values were withheld. |
| `detail_limit` | The 250-event detail cap was reached. The terminal job summary is still recorded. |

Example shape (identifiers and timings below are illustrative):

```text
OCR_DIAGNOSTIC jobId=... role=nutrition language=de attempt=1 pass=p1 event=cell_rejected elapsedMs=... field=fat reason=MISSING_OR_UNSUPPORTED_UNIT confidence=...
OCR_DIAGNOSTIC jobId=... role=nutrition language=de attempt=1 pass=none event=field_rejected elapsedMs=... field=fat reason=NO_VALID_CELL eligibleReadings=0 observedReadings=0
OCR_DIAGNOSTIC jobId=... role=nutrition language=de attempt=1 pass=none event=job_result elapsedMs=... status=COMPLETED confidence=... rawCharacters=... uncertainWords=... suggestionCount=0 warningCount=...
```

Raw OCR remains accessible through the existing private job capability and reviewer audit. Operational logs contain short controlled codes, counts, dimensions and confidences. They exclude raw label text, images, filenames, bearer capabilities, photo hashes and exception messages. Log collection adds no public debug endpoint and does not change publication or review rules.
