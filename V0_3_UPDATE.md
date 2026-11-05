# Apply Etiket 0.3 to your existing repository

This update is based on the uploaded `etiket-scan-dev.zip`, which matches GitHub `dev` at `af3c4ce632a2cf7324a43c5c742c6e2808f7123e`. Your Backend CI workflow is preserved. There is no database reset or schema change.

1. Keep your IntelliJ project on `feature/ingredient-explanations`.
2. Extract the **contents** of `Etiket_v0.3_Changed_Files.zip` into the existing project root, where `settings.gradle` is located. Merge folders and replace the listed files. Do not create another nested project directory. The ZIP contains only changed and added source/documentation files; it contains no Git metadata or credentials.
3. In the IntelliJ terminal, review `git status` and `git diff`. Run the same backend checks used in CI:

```powershell
.\gradlew.bat --no-daemon -PbackendOnly :core:test :backend:test :backend:bootJar
```

4. Install the supplied **Etiket_Demo_v0.3.apk** on your phone. It updates the existing demo and retains preferences, saved products and history. A new installation starts in offline mode. To use Railway, enable live lookup in Settings with `https://etiket-scan-production.up.railway.app` as the base URL, without `/api/v1`.
5. Test these barcodes. Explanations work in the 0.3 APK even while Railway is still running 0.2:

| Barcode | Expected result |
| --- | --- |
| `4760177560085` | Original N1 ingredient list, five Azerbaijani descriptions, existing unverified-source notice. |
| `4606779682407` | Drinking yoghurt: individual descriptions for grouped E1422, xanthan gum, pectin and acidity regulators. |
| `4606779682100` | Berry yoghurt: descriptions plus clearly marked unknown source fragments. |
| `5201360553457` | Product found; no ingredient list and no invented descriptions. |

6. Commit and push the feature branch:

```powershell
git add .
git commit -m "feat: add Azerbaijani ingredient explanations"
git push -u origin feature/ingredient-explanations
```

7. On GitHub create a pull request with **base: dev** and **compare: feature/ingredient-explanations**. Review the diff, wait for **Backend tests** to pass, then merge. Railway deploys the merged branch subject to the existing Wait for CI setting.
8. Check `/api/v1/health` returns version `0.3.0`. On `/api/v1/products/4760177560085`, check `ingredientAnalysis.recognizedCount` is 5 and `evidence.labelPhotoChecked` remains false. Catalogue counts remain 77 / 10 / 67 / 0.

For your own Android build, install Android SDK Platform 36 and Build Tools 35.0.0, then run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The new APK is `app/build/outputs/apk/debug/app-debug.apk`. The old files under `dist/` remain historical 0.2 binaries; use the supplied 0.3 APK or the new build output. `run-backend.bat` now runs the newly built 0.3 JAR under `backend/build/libs/`.
