# Task 3: Android DiagnosticResult Data Model Update

## Requirements

Add `PatchType` enum and `diffText` field to the `DiagnosticResult` data class to support both single-line and multi-line diff repairs.

## Files to Modify

- Modify: `app/src/main/java/com/devdeck/app/model/DiagnosticResult.kt`

## Interface Contract

**Produces:**
- `PatchType` enum with values: SINGLE_LINE, DIFF
- `DiagnosticResult.diffText: String?` - Contains unified diff text when patchType is DIFF
- `DiagnosticResult.patchType: PatchType` - Defaults to SINGLE_LINE for backward compatibility

## Global Constraints

- Android Target SDK: 34
- Android Min SDK: 26
- Must maintain backward compatibility with existing single-line repair code

## Implementation Details

**New enum:**
```kotlin
enum class PatchType {
    SINGLE_LINE,
    DIFF
}
```

**New fields in DiagnosticResult:**
- `diffText: String? = null` - Optional unified diff content
- `patchType: PatchType = PatchType.SINGLE_LINE` - Default to single-line for existing code

**Existing fields remain unchanged:**
- All current fields (rootCause, location, fix, repairFile, repairLine, repairCode, etc.) stay as-is
- This is purely additive - no breaking changes

## Testing

**Gradle sync/build verification:**
- Run: `./gradlew build`
- Expected: BUILD SUCCESSFUL
- No runtime tests needed (data class change only)

## Success Criteria

1. Gradle build succeeds
2. PatchType enum is accessible from other Kotlin files
3. DiagnosticResult can be constructed with new fields
4. Backward compatibility maintained (defaults allow existing code to work)
