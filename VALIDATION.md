# Etiket validation

## 0.6.3 OCR diagnostics

All **100 core/backend tests passed**: 31 core and 69 backend, with native Tesseract tests enabled and the pinned production language models. Android source and APK remain at 0.6.2 and were not rebuilt for this backend-only update.

New checks exercise reason-code logging for missing units and numeric agreement, unchanged extraction results, diagnostic disablement, nested pass/job context cleanup, the detail cap and terminal summary, missing-executable diagnostics, job reuse, retries and exclusion of raw text, filenames, tokens and exception messages. The native photo check records table/pass decisions while preserving the original purple label's two reliable values.

The packaged JAR was started locally with `OCR_DIAGNOSTICS=true`. Health reported 0.6.3. Actual multipart requests produced correlated queue, pass and result events for the original purple photo and the blue biscuit preview. Repeating a completed request produced `job_reused`; unauthorized polling returned 404. Captured output contained no private capabilities or raw label text.

| Photo | Suggestions | Logged observations |
| --- | --- | --- |
| Original purple table | Saturated fat 3.3 g and salt 0.29 g per 100 g | Missing/unsupported units, some misassigned cells and insufficient agreement for other fields |
| Blue biscuit screenshot preview | None | No usable reference heading; flat parsing requested reference confirmation |

These are local checks. This update adds observability to the existing reader; phone/Railway logs are needed to diagnose the exact newly uploaded photo. See `OCR_DIAGNOSTICS.md` and `V0_6_3_UPDATE.md`.

## 0.6.2 nutrition extraction and photo review

All **131 tests passed**: 31 core, 62 backend and 38 Android. Native engine checks were enabled with Tesseract 5.5.0 and the same pinned five language models used by Docker/CI. Android lint reported no issues. The APK is `0.6.2-ocr`, version code 10, with the same demo signing certificate as 0.6.1.

The native real-photo regression covers image sanitisation, a private OCR job, manual correction, reviewer confirmation and lookup of the approved barcode in an isolated H2 database. The existing PostgreSQL, private polling, retries, stored-image matching and legacy-record checks also passed. Android tests cover photo review, preserved manual entries, decimal commas, unknown versus zero, reference changes, cancellation and correction state across rotation.

Local checks of the packaged 0.6.2 JAR covered actual multipart upload and private HTTP polling. Health reported version 0.6.2. Polling without the private capability returned 404 for every image.

| Real photo | Previous reader | 0.6.2 packaged result |
| --- | --- | --- |
| Original purple 100 g / 22 g table | No suggestions | Saturated fat 3.3 g and salt 0.29 g per 100 g; six fields remain unknown |
| Blue biscuit preview from a phone screenshot | No suggestions | Readable raw draft; nutrition requires manual entry |
| Lipton nutrition crop with 100 ml, serving and percentage columns | No suggestions | Readable raw draft; nutrition requires manual entry |

The purple request finished in about 9–11 seconds locally; the smaller previews took about 2–4 seconds. These timings describe this local run. The weak-heading native regression still recovers the clear synthetic table's energy value after the added heading re-read.

This release retains Tesseract and changes preprocessing/extraction and the correction UI. Three real package images, including one reduced screenshot preview, are not a representative benchmark or evidence that every label is readable. A larger separately transcribed photo set remains necessary before selecting another engine. Physical-device capture and Railway redeployment are follow-up checks in `V0_6_2_UPDATE.md`.

## 0.6.0 full nutrition tables

All 120 tests passed across core, backend and Android. The backend tests ran with the pinned Tesseract models. Android lint reported zero issues, the debug APK built with version code 8, and the signing certificate matched the prior demo.

The packaged backend reported version 0.6.0 and returned 404 for a private OCR job polled without its capability. Local upload checks covered the full image sanitisation and OCR job flow.

| Nutrition photo | Suggestions |
| --- | --- |
| Clear single-column German table | All eight expected values per 100 g |
| Dark two-column German fixture | 378 kcal, 9.8 g fat and 0.29 g salt per 100 g |
| Light two-column German fixture | 378 kcal, 66 g carbohydrates and 5.2 g protein per 100 g |
| Serving column first and a missing reference fat cell | 3.3 g saturated fat and 5.2 g protein per 100 ml. Fat stayed blank. |
| Two crops from the purple package screenshots | One returned 378 kcal per 100 g. The other required manual entry. |
| Blue biscuit preview and Lipton table crop | Manual entry |

The sample PP-OCRv4 comparison on the package crops also contained digit and unit errors. Version 0.6.0 uses Tesseract with layout-based extraction and agreement between two reads. Test the original full-resolution label on the phone after deployment to check the capture flow and how many cells it can read.

## 0.5.3 nutrition suggestions

All 111 tests passed across core, backend and Android. The tests cover German row names, a wrapped saturated fat label, separate printed kJ and kcal values, a serving column with missing cells, and Android hiding the copy action when the result has no suggestions. The native Tesseract tests ran with the production language models. Android lint passed.

The packaged backend returned all eight expected nutrition values for a clear synthetic German table with a single 100 g column. The synthetic two-column table returned a text draft and no suggested values. The crop from the user's purple label screenshot also returned a draft with no suggested values. Some digits and units in that screenshot remain inaccurate and require manual entry. The APK signature matches the previous demo.

## 0.5.2 backend

All 77 automated core and backend tests passed with the native OCR engine enabled. The new regression test uses light print on a dark blue nutrition table. The old reader rejects that fixture, and the updated reader returns a draft containing the reference amount, energy and sugar.

We also ran both packaged backends locally against the biscuit preview extracted from the submitted screenshot. Version 0.5.1 returned RETAKE. Version 0.5.2 returned COMPLETED with a draft in both photo roles. OCR still misread some numbers and units in this preview. The nutrition parser returned no suggested values. The earlier Lipton ingredient crop produced a draft, and the full bottle still returned RETAKE.

Deploy this backend over 0.5.1 and keep the existing Android APK. Verify version 0.5.2 at the health endpoint. Select the biscuit photo again in the nutrition section for a fresh result.

## 0.5.1 Android and backend

The backend JAR and signed demo APK were built from the supplied 0.5 source. Java 25, Gradle 9.1.0, Android SDK 36 and Tesseract 5.5.0 were used.

| Suite | Passed |
| --- | ---: |
| Core | 28 |
| Backend | 48 |
| Android | 30 |
| Total | 106 |

Backend checks exercised PostgreSQL 17.11, the review flow, private OCR jobs and photo matching. Tesseract processed generated labels in Azerbaijani, Turkish, Russian, English and German, plus a rotated label and a blank photo. The Android test exercised first read, crop selection across rotation, use of the selected language and rejection of an incomplete result without replacing edited text.

The packaged backend was started locally against a separate H2 database. It reported version 0.5.1 and returned 404 when the private OCR job was polled without its token.

| Supplied Lipton photo | Result | Observation |
| --- | --- | --- |
| Full bottle | RETAKE | The reader detected incomplete coverage. |
| Perspective crop of the ingredients | COMPLETED | It read Wasser, Zucker, Fruktose and most of the paragraph. Several words still need correction against the package. |

The local crop request finished in 3.65 seconds including polling. This check used one real package photo. Camera capture and the Railway Docker build still need to be exercised on deployment.

Android lint found no errors. The sole notice concerns the existing Android Gradle Plugin version. The APK has application ID `az.etiket.scanner.demo`, version code 6, version name `0.5.1-ocr` and the same demo signing certificate as 0.5.

APK SHA-256 `7eb28f815f34dd825c10c1d80b44cf844e1c55a0a44654de0ebd7256e331a959`.
