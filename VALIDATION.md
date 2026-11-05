# Etiket 0.5.1 validation

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
