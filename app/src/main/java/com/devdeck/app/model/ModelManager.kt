package com.devdeck.app.model

import android.content.Context
import com.devdeck.app.ai.DiagnosticAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("devdeck", Context.MODE_PRIVATE)

    fun getPredefinedModels(): List<ModelConfig> {
        val currentPath = getCurrentModelPath()
        return listOf(
            ModelConfig(
                id = "gemma-2b",
                displayName = "Gemma 2B IT",
                description = "Lightweight general-purpose model optimized for mobile",
                filePath = "/data/local/tmp/gemma-2b-it-gpu.bin",
                sizeGB = 1.3f,
                estimatedTPS = 18f,
                specialty = "General debugging",
                tier = ModelTier.FAST,
                isActive = currentPath == "/data/local/tmp/gemma-2b-it-gpu.bin"
            ),
            ModelConfig(
                id = "qwen-coder",
                displayName = "Qwen2.5 Coder 1.5B",
                description = "Code-specialized model with fast inference",
                filePath = "/data/local/tmp/qwen2.5-coder-1.5b-gpu.bin",
                sizeGB = 0.9f,
                estimatedTPS = 24f,
                specialty = "Code repair specialist",
                tier = ModelTier.FAST,
                isActive = currentPath == "/data/local/tmp/qwen2.5-coder-1.5b-gpu.bin"
            ),
            ModelConfig(
                id = "phi-3.5",
                displayName = "Phi-3.5 Mini",
                description = "Advanced reasoning for complex errors",
                filePath = "/data/local/tmp/phi-3.5-mini-gpu.bin",
                sizeGB = 2.4f,
                estimatedTPS = 11f,
                specialty = "Complex logic bugs",
                tier = ModelTier.ADVANCED,
                isActive = currentPath == "/data/local/tmp/phi-3.5-mini-gpu.bin"
            ),
            ModelConfig(
                id = "custom",
                displayName = "Custom Model",
                description = "User-provided model path",
                filePath = currentPath,
                sizeGB = 0f,
                estimatedTPS = 0f,
                specialty = "Unknown",
                tier = ModelTier.FAST,
                isActive = !listOf(
                    "/data/local/tmp/gemma-2b-it-gpu.bin",
                    "/data/local/tmp/qwen2.5-coder-1.5b-gpu.bin",
                    "/data/local/tmp/phi-3.5-mini-gpu.bin"
                ).contains(currentPath)
            )
        )
    }

    fun getCurrentModelPath(): String {
        return prefs.getString("model_path", "/data/local/tmp/gemma-2b-it-gpu.bin")!!
    }

    fun setModelPath(path: String) {
        prefs.edit().putString("model_path", path).apply()
    }

    fun isModelAvailable(path: String): Boolean {
        return java.io.File(path).exists()
    }

    suspend fun verifyModel(path: String): Triple<Boolean, Float, String?> = withContext(Dispatchers.IO) {
        val originalPath = getCurrentModelPath()
        return@withContext try {
            // Temporarily switch to test model
            setModelPath(path)

            // Create agent and initialize
            val agent = DiagnosticAgent(context)
            agent.initModel()

            if (!agent.isEngineReady()) {
                setModelPath(originalPath)
                return@withContext Triple(false, 0f, "Model initialization failed")
            }

            // Run canary test
            val (result, duration) = agent.analyzeError(
                errorText = "Test: NameError: name 'x' is not defined",
                sourceContext = "y = x + 1",
                filePath = "test.py",
                lineNum = 1,
                originalLine = "y = x + 1"
            )

            val tps = result.tokensPerSecond

            // Restore original path
            setModelPath(originalPath)

            Triple(true, tps, null)
        } catch (e: Exception) {
            // Restore original path on error
            setModelPath(originalPath)
            Triple(false, 0f, e.message)
        }
    }
}
