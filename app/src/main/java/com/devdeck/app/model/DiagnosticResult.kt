package com.devdeck.app.model

data class DiagnosticResult(
    val rootCause: String,
    val location: String,
    val fix: String,
    val isParsed: Boolean = true,
    val rawOutput: String? = null,
    val tokensPerSecond: Float = 0f,
    val memoryUsageMB: Int = 0,
    val repairFile: String? = null,
    val repairLine: Int? = null,
    val repairCode: String? = null,
    val originalLine: String? = null
)
