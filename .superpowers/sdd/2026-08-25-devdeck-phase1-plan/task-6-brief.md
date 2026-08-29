# Task 6: Model Manager Data Classes

## Requirements

Create `ModelConfig.kt` and `ModelManager.kt` to provide model metadata, dynamic path switching, and canary verification for on-device AI models.

## Files to Create

- Create: `app/src/main/java/com/devdeck/app/model/ModelConfig.kt`
- Create: `app/src/main/java/com/devdeck/app/model/ModelManager.kt`

## Interface Contract

**Produces:**

**ModelConfig.kt:**
- `ModelTier` enum: FAST, ADVANCED
- `ModelConfig` data class with fields: id, displayName, description, filePath, sizeGB, estimatedTPS, specialty, tier, isActive

**ModelManager.kt:**
- `ModelManager(context: Context)` - Constructor
- `getPredefinedModels() -> List<ModelConfig>` - Returns 4 predefined models (Gemma-2B, Qwen2.5-Coder, Phi-3.5-mini, Custom)
- `getCurrentModelPath() -> String` - Reads from SharedPreferences
- `setModelPath(path: String)` - Writes to SharedPreferences
- `isModelAvailable(path: String) -> Boolean` - File.exists() check
- `suspend verifyModel(path: String) -> Triple<Boolean, Float, String?>` - Canary test: loads model, runs warmup inference, returns (success, TPS, error)

## Global Constraints

- Model Path Default: `/data/local/tmp/gemma-2b-it-gpu.bin`
- SharedPreferences key: `model_path` in `devdeck` prefs
- Verification must restore original model path after canary test
- All model file paths assume device storage (not app-private storage)

## Implementation Details

**ModelConfig.kt:**
- Simple enum + data class
- No logic, just structure

**ModelManager.kt:**
- Uses SharedPreferences for persistent storage
- `getPredefinedModels()` returns 4 hardcoded ModelConfig entries:
  1. Gemma-2B-IT: 1.3GB, ~18 tok/s, FAST tier
  2. Qwen2.5-Coder-1.5B: 0.9GB, ~24 tok/s, FAST tier (code specialist)
  3. Phi-3.5-mini: 2.4GB, ~11 tok/s, ADVANCED tier
  4. Custom: user-provided path
- Each model's `isActive` flag determined by comparing filePath to current path
- `verifyModel()` temporarily switches model, initializes DiagnosticAgent, runs warmup, restores original path

**Canary verification flow:**
1. Save original model path
2. Set model path to test path
3. Create DiagnosticAgent instance
4. Call agent.initModel()
5. Check agent.isEngineReady()
6. Run agent.analyzeError() with dummy input
7. Extract TPS from result
8. Restore original model path
9. Return (success, TPS, error)

## Testing

**Gradle build verification:**
- Run: `./gradlew build`
- Expected: BUILD SUCCESSFUL
- No syntax errors, coroutine suspend function compiles

## Success Criteria

1. Gradle build succeeds
2. ModelConfig data class accessible from other files
3. ModelManager correctly reads/writes SharedPreferences
4. verifyModel() safely restores original path even on exception
5. isActive flag correctly reflects current model
