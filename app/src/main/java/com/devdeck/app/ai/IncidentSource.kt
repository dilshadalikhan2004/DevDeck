package com.devdeck.app.ai

/**
 * Recovers the failing source line and line number when the laptop omits
 * original_line / error_line (JSON null) or Android optString turns null into "null".
 */
object IncidentSource {

    fun usableLine(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t.equals("null", ignoreCase = true) || t.equals("None", ignoreCase = true)) {
            return null
        }
        return t
    }

    fun usableLineNumber(n: Int?): Int? = n?.takeIf { it > 0 }

    fun fromContext(sourceContext: String?): String? {
        if (sourceContext.isNullOrBlank()) return null
        val marked = Regex(""">>>\s+\d+\s+\|\s+(.*)""").find(sourceContext)?.groupValues?.get(1)?.trim()
        return usableLine(marked)
    }

    /** Last Python/JS-style source line sitting under a File/at frame, before the exception. */
    fun fromTrace(errorText: String): Pair<String?, Int?> {
        if (errorText.isBlank()) return null to null
        val lines = errorText.lines()
        var bestLine: String? = null
        var bestNum: Int? = null
        val filePy = Regex("""File\s+"[^"]+",\s+line\s+(\d+)""")
        val fileBare = Regex("""([\w./\\-]+\.(?:py|js|ts|kt|java)):(\d+)""")
        for (i in lines.indices) {
            val row = lines[i].trim()
            val py = filePy.find(row)
            val bare = fileBare.find(row)
            val num = py?.groupValues?.get(1)?.toIntOrNull()
                ?: bare?.groupValues?.get(2)?.toIntOrNull()
            if (num != null) {
                bestNum = num
                val following = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (following.isNotEmpty() && !isExceptionLine(following) && !following.startsWith("File ")) {
                    bestLine = following.trimStart('>', ' ', '\t')
                }
            }
        }
        val caret = lines.lastOrNull { it.trim().startsWith(">") && it.contains("assert") }
            ?.trim()?.trimStart('>')?.trim()
        if (bestLine == null && caret != null) bestLine = caret
        return usableLine(bestLine) to usableLineNumber(bestNum)
    }

    fun recover(errorText: String, sourceContext: String?, originalLine: String?, lineNum: Int?): Pair<String?, Int?> {
        val fromTrace = fromTrace(errorText)
        val line = usableLine(originalLine) ?: fromContext(sourceContext) ?: fromTrace.first
        val num = usableLineNumber(lineNum) ?: fromTrace.second
        return line to num
    }

    private fun isExceptionLine(s: String): Boolean {
        val t = s.trim()
        return t.contains("Error:") || t.contains("Exception:") ||
            t.matches(Regex("""^[A-Za-z_]\w*(Error|Exception)\b.*"""))
    }
}
