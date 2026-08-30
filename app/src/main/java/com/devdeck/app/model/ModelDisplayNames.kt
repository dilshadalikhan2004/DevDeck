package com.devdeck.app.model

import java.io.File

object ModelDisplayNames {

    fun fromPath(path: String?): String {
        if (path.isNullOrBlank()) return "On-device model"
        val file = File(path).name
        val lower = file.lowercase()
        return when {
            "gemma" in lower -> "Gemma 2B IT"
            "qwen" in lower -> "Qwen2.5 Coder 1.5B"
            "phi" in lower -> "Phi-3.5 Mini"
            else -> humanizeFileName(file)
        }
    }

    fun humanizeFileName(fileName: String): String {
        val stem = fileName
            .substringBeforeLast('.')
            .ifBlank { fileName }
            .replace(Regex("[-_]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (stem.isBlank()) return "Custom model"
        return stem.split(' ').joinToString(" ") { part ->
            if (part.isEmpty()) part
            else part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
        }
    }
}
