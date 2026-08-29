# Task 4: Update DiagnosticAgent for Diff Generation

## Requirements

Modify `DiagnosticAgent.kt` to generate and parse unified diff format instead of only single-line fixes. The AI will output diffs between `<<<DIFF>>>` and `<<<END>>>` markers, which we validate for grounding and line count limits.

## Files to Modify

- Modify: `app/src/main/java/com/devdeck/app/ai/DiagnosticAgent.kt`

## Interface Contract

**Consumes (from Task 3):**
- `DiagnosticResult` with new `patchType` and `diffText` fields
- `PatchType` enum

**Produces:**
- `DiagnosticResult` with `patchType = DIFF` and populated `diffText` when multi-line fix is generated
- `DiagnosticResult` with `patchType = SINGLE_LINE` for backward-compatible single-line repairs
- Falls back to HeuristicDiagnosticEngine when AI output fails validation

## Global Constraints

- Maximum 20 lines changed per diff (safety limit from spec)
- Semantic grounding: diff cannot introduce identifiers not in original code
- Must handle both diff and single-line (<<<FIX>>>) response formats
- All fallback paths must use HeuristicDiagnosticEngine

## Implementation Details

**Prompt update (around line 79-118):**
- Change output format from `<<<FIX>>>` single line to `<<<DIFF>>>` unified diff
- Include few-shot examples showing unified diff format with @@ markers
- Include context lines requirement (1-2 lines around changes)
- Maximum 20 lines changed constraint
- Grounding rule: only use existing identifiers

**Parsing update in `parseResponse()` (around line 182-256):**
1. Try diff parsing first with `<<<DIFF>>>` regex
2. Validate diff starts with `@@` marker
3. Extract added lines (those starting with `+`)
4. Run semantic grounding check on added identifiers
5. Count changed lines, reject if > 20
6. If diff validation passes: return DiagnosticResult with patchType=DIFF
7. If diff fails: try single-line `<<<FIX>>>` parsing (backward compatibility)
8. If both fail: call `fallbackHeuristic()` helper

**New helper function:**
- `fallbackHeuristic()` - Wrapper that calls HeuristicDiagnosticEngine.diagnose() and copies TPS/memory stats

**Import addition:**
```kotlin
import com.devdeck.app.model.PatchType
```

## Testing

**Gradle build verification:**
- Run: `./gradlew assembleDebug`
- Expected: BUILD SUCCESSFUL
- No syntax errors, all imports resolve

## Success Criteria

1. Gradle build succeeds
2. Code compiles without errors
3. Parsing logic handles both diff and single-line formats
4. Semantic grounding prevents hallucinated identifiers in diffs
5. Line count limit enforced (max 20 lines changed)
6. Fallback to heuristic engine on validation failure
