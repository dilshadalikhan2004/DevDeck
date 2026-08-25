package com.devdeck.app.model

enum class ModelTier {
    FAST,
    ADVANCED
}

data class ModelConfig(
    val id: String,
    val displayName: String,
    val description: String,
    val filePath: String,
    val sizeGB: Float,
    val estimatedTPS: Float,
    val specialty: String,
    val tier: ModelTier,
    val isActive: Boolean = false
)
