package com.devdeck.app.model

import android.content.Context
import com.devdeck.app.ai.DiagnosticAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("devdeck", Context.MODE_PRIVATE)

    fun getPredefinedModels(): List<ModelConfig> {
        val currentPath = getCurrentModelPath()
        val gemmaPath = resolveModelLocation("gemma-2b-it-gpu.bin")
        val qwenPath = resolveModelLocation("qwen2.5-coder-1.5b-gpu.bin")
        val phiPath = resolveModelLocation("phi-3.5-mini-gpu.bin")

        val known = listOf(gemmaPath, qwenPath, phiPath)
        return listOf(
            ModelConfig(
                id = "qwen-coder",
                displayName = "Qwen2.5 Coder 1.5B",
                description = "Code-specialized model with fast inference",
                filePath = qwenPath,
                sizeGB = 0.9f,
                estimatedTPS = 24f,
                specialty = "Code repair specialist",
                tier = ModelTier.FAST,
                isActive = currentPath == qwenPath,
                isAvailable = isModelAvailable(qwenPath),
                recommendation = "Recommended for DevDeck repairs"
            ),
            ModelConfig(
                id = "gemma-2b",
                displayName = "Gemma 2B IT",
                description = "Lightweight general-purpose model optimized for mobile",
                filePath = gemmaPath,
                sizeGB = 1.3f,
                estimatedTPS = 18f,
                specialty = "General debugging",
                tier = ModelTier.FAST,
                isActive = currentPath == gemmaPath,
                isAvailable = isModelAvailable(gemmaPath),
                recommendation = "Good default if this file is already on the phone"
            ),
            ModelConfig(
                id = "phi-3.5",
                displayName = "Phi-3.5 Mini",
                description = "Advanced reasoning for complex errors",
                filePath = phiPath,
                sizeGB = 2.4f,
                estimatedTPS = 11f,
                specialty = "Complex logic bugs",
                tier = ModelTier.ADVANCED,
                isActive = currentPath == phiPath,
                isAvailable = isModelAvailable(phiPath),
                recommendation = "Optional — slower, better on harder bugs"
            ),
            ModelConfig(
                id = "custom",
                displayName = ModelDisplayNames.fromPath(currentPath),
                description = "Any MediaPipe-compatible .bin you push to the device",
                filePath = currentPath,
                sizeGB = 0f,
                estimatedTPS = 0f,
                specialty = "Your file",
                tier = ModelTier.FAST,
                isActive = currentPath.isNotBlank() && currentPath !in known,
                isAvailable = isModelAvailable(currentPath),
                recommendation = if (currentPath !in known) "Currently selected file" else null
            )
        )
    }

    fun getActiveDisplayName(): String {
        val path = getCurrentModelPath()
        val configured = prefs.getString("model_path", null)
        val stored = prefs.getString("model_display_name", null)
        if (!stored.isNullOrBlank() && configured == path) return stored
        return ModelDisplayNames.fromPath(path)
    }

    fun setModelPath(path: String, displayName: String? = null) {
        prefs.edit()
            .putString("model_path", path)
            .putString("model_display_name", displayName ?: ModelDisplayNames.fromPath(path))
            .apply()
    }

    private fun resolveModelLocation(fileName: String): String {
        val internal = java.io.File(java.io.File(context.filesDir, "models"), fileName)
        if (internal.exists()) return internal.absolutePath
        val external = java.io.File(java.io.File(context.getExternalFilesDir(null), "models"), fileName)
        if (external.exists()) return external.absolutePath
        return "/data/local/tmp/$fileName"
    }

    fun getCurrentModelPath(): String {
        val configured = prefs.getString("model_path", null)
        if (!configured.isNullOrBlank() && java.io.File(configured).exists()) {
            return configured
        }
        return resolveModelLocation("gemma-2b-it-gpu.bin")
    }

    fun isModelAvailable(path: String): Boolean {
        return java.io.File(path).exists()
    }

    suspend fun verifyModel(path: String): Triple<Boolean, Float, String?> = withContext(Dispatchers.IO) {
        val originalPath = getCurrentModelPath()
        val originalName = getActiveDisplayName()
        return@withContext try {
            setModelPath(path)
            val agent = DiagnosticAgent(context)
            agent.initModel()
            if (!agent.isEngineReady()) {
                setModelPath(originalPath, originalName)
                return@withContext Triple(false, 0f, "Model initialization failed")
            }
            val (result, duration) = agent.analyzeError(
                errorText = "Test: NameError: name 'x' is not defined",
                sourceContext = "y = x + 1",
                filePath = "test.py",
                lineNum = 1,
                originalLine = "y = x + 1"
            )
            val tps = result.tokensPerSecond
            setModelPath(originalPath, originalName)

            Triple(true, tps, null)
        } catch (e: Exception) {
            // Restore original path on error
            setModelPath(originalPath, originalName)
            Triple(false, 0f, e.message)
        }
    }
}
