# Task 5: Update MainActivity to Send Diff Repairs

## Requirements

Modify the `sendRepair()` function in `MainActivity.kt` to handle both single-line and diff patch types, constructing the appropriate JSON payload based on `DiagnosticResult.patchType`.

## Files to Modify

- Modify: `app/src/main/java/com/devdeck/app/ui/MainActivity.kt`

## Interface Contract

**Consumes (from Task 4):**
- `DiagnosticResult` with `patchType: PatchType` field
- `DiagnosticResult` with `diffText: String?` field (for diff mode)
- `DiagnosticResult` with `repairCode: String?` field (for single-line mode)

**Produces:**
- JSON payload with `patch_type: "single_line"` for single-line repairs
  - Fields: type, patch_type, file, line, code
- JSON payload with `patch_type: "diff"` for diff repairs
  - Fields: type, patch_type, file, diff_text

## Global Constraints

- WebSocket Protocol: ws://localhost:8765
- JSON payload must match what relay_server.py PatchManager expects
- Backward compatibility: existing single-line repairs must continue working

## Implementation Details

**Locate sendRepair() function:**
- Currently around line 456-475 in MainActivity.kt
- Currently constructs a single JSON structure for repairs

**Replace with when() expression:**
```kotlin
val json = when (result.patchType) {
    com.devdeck.app.model.PatchType.SINGLE_LINE -> JSONObject().apply {
        put("type", "repair")
        put("patch_type", "single_line")
        put("file", result.repairFile)
        put("line", result.repairLine)
        put("code", result.repairCode)
    }
    com.devdeck.app.model.PatchType.DIFF -> JSONObject().apply {
        put("type", "repair")
        put("patch_type", "diff")
        put("file", result.repairFile)
        put("diff_text", result.diffText)
    }
}
```

**Terminal output update:**
- Change `appendToTerminal("Sending repair payload...")` to include patch type
- Use: `appendToTerminal("Sending ${result.patchType} repair to laptop...", "sys")`

**No import changes needed:**
- PatchType enum already accessible via com.devdeck.app.model package
- JSONObject already imported

## Testing

**Gradle build verification:**
- Run: `./gradlew assembleDebug`
- Expected: BUILD SUCCESSFUL
- No syntax errors, when() expression handles all PatchType cases

## Success Criteria

1. Gradle build succeeds
2. Code compiles without errors
3. when() expression exhaustively handles SINGLE_LINE and DIFF cases
4. JSON payloads match relay_server.py expectations
5. Terminal output correctly shows patch type being sent
