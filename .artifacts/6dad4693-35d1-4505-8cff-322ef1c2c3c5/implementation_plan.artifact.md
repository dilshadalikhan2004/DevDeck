# Prepare for Demo & Future SDK Integration

Since the iQOO/Office Kit SDK is not yet available, this plan focuses on making the project **demo-ready** using the offline "Heuristic" engine and preparing the "Cloud" engine for testing.

## User Review Required

> [!IMPORTANT]
> To use the **Cloud Flagging Engine** later, you will need to add `GEMINI_API_KEY=your_key` to your `local.properties` file.

> [!NOTE]
> I will be enabling `buildConfig` in your Gradle setup to allow the app to safely read the API key and toggle between engines.

## Proposed Changes

---

### [Flagging & AI]

#### [MODIFY] [HeuristicFlaggingEngine.kt](file:///C:/Users/LENOVO/Downloads/receipts-android/app/src/main/java/com/receipts/app/ai/HeuristicFlaggingEngine.kt)
*   Expand pattern matching to include more "risky" financial terms, percentages, and strong absolute language (e.g., "100%", "guaranteed", "no risk").
*   Improve citation detection for common academic and news source patterns.

#### [MODIFY] [FlaggingEngine.kt](file:///C:/Users/LENOVO/Downloads/receipts-android/app/src/main/java/com/receipts/app/ai/FlaggingEngine.kt)
*   Update `FlaggingEngineFactory` to use `BuildConfig.GEMINI_API_KEY`.
*   If a key is present and not "null", use `CloudFlaggingEngine` as an option.

---

### [Build & Environment]

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/LENOVO/Downloads/receipts-android/app/build.gradle.kts)
*   Enable `buildConfig = true` in the `buildFeatures` block.
*   Add a logic to read `GEMINI_API_KEY` from `local.properties` and inject it as a `buildConfigField`.

---

### [Sync & Demo Visibility]

#### [MODIFY] [OfficeKitExporter.kt](file:///C:/Users/LENOVO/Downloads/receipts-android/app/src/main/java/com/receipts/app/sync/OfficeKitExporter.kt)
*   Enhance the stub to log the JSON payload to Logcat with a clear "DEMO SYNC" tag.
*   Prepare the structure for a "Demo Callback" so the UI can show progress even without the real SDK.

---

### [Testing]

#### [NEW] [HeuristicFlaggingEngineTest.kt](file:///C:/Users/LENOVO/Downloads/receipts-android/app/src/test/java/com/receipts/app/ai/HeuristicFlaggingEngineTest.kt)
*   Add unit tests to ensure numbers, dates, and claims are correctly identified and overlapping spans are deduplicated.

## Verification Plan

### Automated Tests
*   Run `./gradlew :app:testDebugUnitTest` to verify the new unit tests pass.

### Manual Verification
*   Confirm the project still builds with `./gradlew :app:assembleDebug`.
*   Verify that `BuildConfig.java` contains the `GEMINI_API_KEY` field (even if empty).
