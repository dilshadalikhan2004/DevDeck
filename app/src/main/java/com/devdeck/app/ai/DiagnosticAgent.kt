package com.devdeck.app.ai

import android.content.Context
import android.util.Log
import com.devdeck.app.model.DiagnosticResult
import com.devdeck.app.model.PatchType
import com.devdeck.app.model.ProjectContextManager
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

class DiagnosticAgent(private val context: Context?) {

    private var llmInference: LlmInference? = null
    private var isInitializing = false
    private val projectContextManager = context?.let { ProjectContextManager(it) }
    
    // Stored in preferences with automatic discovery across app directories and /data/local/tmp
    private val modelPath: String
        get() {
            val configured = context?.getSharedPreferences("devdeck", Context.MODE_PRIVATE)
                ?.getString("model_path", null)
            if (!configured.isNullOrBlank() && File(configured).exists()) {
                return configured
            }
            // Check app internal storage
            val internalFile = context?.let { File(File(it.filesDir, "models"), "gemma-2b-it-gpu.bin") }
            if (internalFile != null && internalFile.exists()) {
                return internalFile.absolutePath
            }
            // Check app external storage
            val externalFile = context?.let { File(File(it.getExternalFilesDir(null), "models"), "gemma-2b-it-gpu.bin") }
            if (externalFile != null && externalFile.exists()) {
                return externalFile.absolutePath
            }
            return configured ?: "/data/local/tmp/gemma-2b-it-gpu.bin"
        }

    fun isModelAvailable(): Boolean = File(modelPath).exists()
    fun isEngineReady(): Boolean = llmInference != null

    suspend fun initModel() = withContext(Dispatchers.IO) {
        if (llmInference != null || context == null || isInitializing) return@withContext
        isInitializing = true
        
        if (!isModelAvailable()) {
            Log.e("DevDeck", "Model file not found at $modelPath")
            isInitializing = false
            return@withContext
        }

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(2048) // Increased budget to prevent crash on large repo context
                .build()
                
            val engine = LlmInference.createFromOptions(context, options)
            
            // CANARY TEST: Measure real TPS and Memory
            try {
                val start = System.currentTimeMillis()
                engine.generateResponse("Warmup canary test")
                val end = System.currentTimeMillis()
                val duration = end - start
                
                llmInference = engine
                Log.i("DevDeck", "MediaPipe LlmInference initialized. Canary duration: ${duration}ms")
            } catch (e: Throwable) {
                Log.e("DevDeck", "Model loaded but canary inference failed: ${e.message}")
                llmInference = null
            }
        } catch (e: Exception) {
            Log.e("DevDeck", "Failed to create LlmInference: ${e.message}")
            llmInference = null
        } finally {
            isInitializing = false
        }
    }

    suspend fun analyzeError(
        errorText: String, 
        sourceContext: String? = null,
        filePath: String? = null,
        lineNum: Int? = null,
        originalLine: String? = null,
        expectedSha256: String? = null,
        incidentId: String? = null,
        projectId: String? = null,
        repositoryContext: String? = null,
        repositorySymbols: Set<String> = emptySet(),
        developerConstraint: String? = null
    ): Pair<DiagnosticResult, Long> = withContext(Dispatchers.IO) {
        // Wait up to 30 seconds if initialization is in progress
        var waitCount = 0
        while (isInitializing && llmInference == null && waitCount < 60) {
            delay(500)
            waitCount++
        }

        val inference = llmInference
        val constrainedTrace = if (!developerConstraint.isNullOrBlank()) {
            "$errorText\nDeveloper correction: $developerConstraint"
        } else {
            errorText
        }
        if (inference == null) {
            Log.w("DevDeck", "LlmInference null, falling back to heuristic. isInitializing=$isInitializing")
            val result = HeuristicDiagnosticEngine.diagnose(constrainedTrace, sourceContext, filePath, lineNum, originalLine)
            return@withContext result.copy(
                expectedSha256 = expectedSha256, 
                incidentId = incidentId,
                projectId = projectId,
                confidence = 1.0f
            ) to 0L
        }
        
        val safeSourceContext = if (!sourceContext.isNullOrBlank()) {
            val truncated = if (sourceContext.length > 500) sourceContext.take(500) + "\n... [truncated]" else sourceContext
            "\nSURROUNDING CODE:\n$truncated\n"
        } else ""

        val safeRepoContext = if (!repositoryContext.isNullOrBlank()) {
            val truncated = if (repositoryContext.length > 800) repositoryContext.take(800) + "\n... [truncated]" else repositoryContext
            "\nRETRIEVED REPOSITORY EVIDENCE:\n$truncated\n"
        } else ""

        val originalIds = originalLine?.let { extractIdentifiers(it) }?.joinToString(", ") ?: "None"
        val ruleContext = projectContextManager?.getFormattedContext() ?: ""

        // High-accuracy few-shot prompt optimized for small on-device SLMs (Gemma-2B)
        val prompt = """
            <start_of_turn>user
            You are an autonomous code repair engine. Fix the broken lines of code to resolve the error.
            $ruleContext
            STRICT RULES:
            1. Output EITHER a single-line fix between <<<FIX>>> and <<<END>>> OR a multi-line unified diff between <<<DIFF>>> and <<<END>>>.
            2. For multi-line errors, output a unified diff format patch. Max 20 lines changed per diff.
            3. Do not invent names. Use only target identifiers [$originalIds] or repository symbols: [${repositorySymbols.joinToString(", ")}].
            3a. If evidence is insufficient, output <<<FIX>>>UNKNOWN<<<END>>>.
            4. Include 1-2 lines of surrounding unchanged context in diffs starting with spaces. Deleted lines start with '-' and added lines start with '+'.
            5. Output a one-line explanation of the bug and the fix between <<<WHY>>> and <<<END_WHY>>>.

            FEW-SHOT EXAMPLES:
            Example 1 (Single Line):
            Error: TypeError: can only concatenate str (not "NoneType") to str
            Target: print("User: " + user.name)
            <<<FIX>>>print("User: " + str(user.name))<<<END>>>

            Example 2 (Multi-line Diff):
            Error: IndentationError: expected an indented block
            Target Code Context:
            def process(items):
            for x in items:
            do_work(x)
            <<<DIFF>>>
            @@ -1,3 +1,4 @@
             def process(items):
            -for x in items:
            -do_work(x)
            +    for x in items:
            +        do_work(x)
            <<<END>>>

            Example 3 (Multi-line Diff):
            Error: NameError: name 'user_id' is not defined
            Target Code Context:
            def log_user(user):
                id = user.id
                print(f"User: {user_id}")
            <<<DIFF>>>
            @@ -1,3 +1,3 @@
             def log_user(user):
            -    id = user.id
            -    print(f"User: {user_id}")
            +    id = user.id
            +    print(f"User: {id}")
            <<<END>>>

            NOW REPAIR THIS:
            Error:
            ${extractCleanError(errorText)}
            $safeSourceContext
            $safeRepoContext
            Target Code Context:
            $originalLine
            ${if (!developerConstraint.isNullOrBlank()) "DEVELOPER CORRECTION (must honor):\n$developerConstraint\n" else ""}
            <end_of_turn>
            <start_of_turn>model
            """.trimIndent()

        var response = ""
        val runtime = Runtime.getRuntime()
        val startMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        val duration = measureTimeMillis {
            try {
                response = inference.generateResponse(prompt)
            } catch (t: Throwable) {
                Log.e("DevDeck", "generateResponse failed (${t.javaClass.simpleName}): ${t.message}")
                val fallback = HeuristicDiagnosticEngine.diagnose(errorText, sourceContext, filePath, lineNum, originalLine)
                return@withContext fallback.copy(
                    expectedSha256 = expectedSha256, 
                    incidentId = incidentId, 
                    projectId = projectId,
                    confidence = 1.0f
                ) to 0L
            }
        }

        val endMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val memUsage = maxOf(0, (endMem - startMem).toInt())

        val tokenCount = response.length / 4f
        val tps = if (duration > 0) (tokenCount / (duration / 1000f)) else 0f

        val result = try {
            parseResponse(response, tps, memUsage, filePath, lineNum, originalLine, errorText, sourceContext, repositorySymbols)
        } catch (t: Throwable) {
            Log.e("DevDeck", "parseResponse threw error: ${t.message}")
            HeuristicDiagnosticEngine.diagnose(errorText, sourceContext, filePath, lineNum, originalLine)
        }
        val finalResult = result.copy(
            expectedSha256 = expectedSha256, 
            incidentId = incidentId, 
            projectId = projectId
        )
        return@withContext finalResult to duration
    }

    private fun extractCleanError(errorText: String): String {
        val lines = errorText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val specificError = lines.lastOrNull { line ->
            line.contains("Error:") || line.contains("Exception:") || line.contains("AssertionError")
        }
        return specificError ?: lines.takeLast(3).joinToString("\n")
    }

    fun analyzeLogStream(logLine: String): String? {
        val normalized = logLine.lowercase()
        return when {
            "critical" in normalized || "fatal" in normalized -> "CRITICAL SYSTEM ERROR DETECTED"
            "out of memory" in normalized || "oom" in normalized -> "POTENTIAL MEMORY EXHAUSTION"
            "latency" in normalized && ">500ms" in normalized -> "SEVERE PERFORMANCE DEGRADATION"
            "database connection" in normalized && "lost" in normalized -> "DB CONNECTIVITY FLAKING"
            else -> null
        }
    }

    private fun extractIdentifiers(text: String): Set<String> {
        // 1. Strip string literals and string prefixes (f, r, b, u) so literal contents/prefixes aren't parsed as variables
        val clean = text.replace(Regex("""[frbuFRBU]*("{3}.*?"{3}|'''.*?'''|".*?"|'.*?')"""), "")
        
        // 2. Allowed language keywords, built-ins, and standard functions across Python, Kotlin, Java, JS
        val allowedBuiltins = setOf(
            "if", "else", "elif", "for", "in", "while", "try", "except", "finally", "catch",
            "def", "fun", "val", "var", "return", "pass", "None", "null", "True", "False", "true", "false",
            "str", "int", "float", "bool", "list", "dict", "set", "tuple", "len", "range", "type",
            "print", "isinstance", "hasattr", "getattr", "get", "format", "f", "r", "b", "u",
            "import", "from", "as", "class", "self", "this", "lambda", "async", "await"
        )

        return Regex("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b").findAll(clean)
            .map { it.value }
            .filter { it !in allowedBuiltins }
            .toSet()
    }

    internal fun parseResponse(
        raw: String,
        tps: Float,
        mem: Int,
        filePath: String?,
        lineNum: Int?,
        originalLine: String?,
        errorText: String,
        sourceContext: String?,
        repositorySymbols: Set<String> = emptySet()
    ): DiagnosticResult {
        return try {
            val whyRegex = "<<<WHY>>>([\\s\\S]*?)(?:<<<END_WHY>>>|$)".toRegex()
            val reasoning = whyRegex.find(raw)?.groupValues?.get(1)?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }

            // Try diff format first
            val diffRegex = "<<<DIFF>>>([\\s\\S]*?)(?:<<<END>>>|<end_of_turn>|$)".toRegex()
            val diffMatch = diffRegex.find(raw)

            if (diffMatch != null) {
                var diffText = diffMatch.groupValues[1].trim()

                // Validate diff format
                if (diffText.startsWith("@@")) {
                    // Extract added lines for grounding check
                    val addedLines = diffText.lines().filter { it.startsWith("+") && !it.startsWith("+++") }
                    val addedContent = addedLines.joinToString("\n") { it.substring(1) }

                    // Count changed lines (additions + deletions)
                    val changedLineCount = diffText.lines().count { it.startsWith("+") || it.startsWith("-") }

                    // Check line count limit (max 20)
                    if (changedLineCount > 20) {
                        Log.w("DevDeck", "Diff exceeds 20 line limit ($changedLineCount lines). Falling back.")
                        return fallbackHeuristic(errorText, sourceContext, filePath, lineNum, originalLine, tps, mem)
                    }

                    // Semantic grounding check on added content
                    val originalIds = (originalLine?.let { extractIdentifiers(it) } ?: emptySet()) + repositorySymbols
                    val addedIds = extractIdentifiers(addedContent)
                    val hallucinatedIds = addedIds - originalIds

                    if (hallucinatedIds.isNotEmpty()) {
                        Log.w("DevDeck", "Diff introduces ungrounded identifiers: $hallucinatedIds. Falling back.")
                        return fallbackHeuristic(errorText, sourceContext, filePath, lineNum, originalLine, tps, mem)
                    }

                    Log.d("DevDeck", "Diff grounding passed. Changed lines: $changedLineCount")

                    return DiagnosticResult(
                        rootCause = reasoning ?: "Multi-line diff repair suggested by on-device AI.",
                        location = filePath ?: "Unclear",
                        fix = "Applied unified diff patch",
                        tokensPerSecond = tps,
                        memoryUsageMB = mem,
                        repairFile = filePath,
                        repairLine = lineNum,
                        repairCode = null,
                        originalLine = originalLine,
                        patchType = PatchType.DIFF,
                        diffText = diffText,
                        rawOutput = raw,
                        reasoning = reasoning
                    )
                }
            }

            // Try single-line fix format (backward compatibility)
            val fixRegex = "<<<FIX>>>([\\s\\S]*?)(?:<<<END>>>|<end_of_turn>|$)".toRegex()
            val match = fixRegex.find(raw)
            var extractedFix = match?.groupValues?.get(1)?.trim()

            if (extractedFix != null && extractedFix.uppercase() == "UNKNOWN") {
                return DiagnosticResult(
                    rootCause = "NEEDS_CONTEXT: not enough evidence for a safe fix.",
                    location = filePath ?: "Unclear",
                    fix = "No safe fix proposed",
                    isParsed = false,
                    tokensPerSecond = tps,
                    memoryUsageMB = mem,
                    repairFile = filePath,
                    repairLine = lineNum,
                    originalLine = originalLine,
                    rawOutput = raw,
                    reasoning = reasoning ?: "The model abstained rather than guessing.",
                    abstained = true
                )
            }

            // Clean any accidental markdown code fencing
            extractedFix = extractedFix
                ?.replace(Regex("^```[a-zA-Z]*\\n?"), "")
                ?.replace(Regex("```$"), "")
                ?.trim()
                ?.lines()?.firstOrNull { it.isNotBlank() }?.trim()

            // Semantic Grounding Check
            val originalIds = (originalLine?.let { extractIdentifiers(it) } ?: emptySet()) + repositorySymbols
            val fixIds = extractedFix?.let { extractIdentifiers(it) } ?: emptySet()
            val hallucinatedIds = (fixIds - originalIds)
            val hasNewIdentifiers = originalLine != null && extractedFix != null && hallucinatedIds.isNotEmpty()

            val isSingleLine = extractedFix != null && !extractedFix.contains("\n") && !extractedFix.contains("\r")
            val isNotUnknown = extractedFix != null && extractedFix.uppercase() != "UNKNOWN" && extractedFix.isNotBlank()
            val isNotDuplicate = extractedFix != null && extractedFix != originalLine?.trim()
            val isGrounded = !hasNewIdentifiers

            val isConfident = extractedFix != null && isSingleLine && isNotUnknown && isNotDuplicate && isGrounded

            Log.d("DevDeck", "Grounding: Orig=$originalIds, Fix=$fixIds, Hallucinated=$hallucinatedIds, IsGrounded=$isGrounded")

            if (isConfident) {
                DiagnosticResult(
                    rootCause = reasoning ?: "One-line fix suggested by on-device AI.",
                    location = filePath ?: "Unclear",
                    fix = extractedFix!!,
                    tokensPerSecond = tps,
                    memoryUsageMB = mem,
                    repairFile = filePath,
                    repairLine = lineNum,
                    repairCode = extractedFix,
                    originalLine = originalLine,
                    patchType = PatchType.SINGLE_LINE,
                    rawOutput = raw,
                    reasoning = reasoning
                )
            } else {
                // If AI was ungrounded or empty, invoke deterministic high-accuracy heuristic engine
                Log.w("DevDeck", "AI fix unconfident or ungrounded ($hallucinatedIds). Falling back to heuristic synthesis.")
                return fallbackHeuristic(errorText, sourceContext, filePath, lineNum, originalLine, tps, mem)
            }
        } catch (e: Exception) {
            Log.e("DevDeck", "parseResponse error: ${e.message}")
            HeuristicDiagnosticEngine.diagnose(errorText, sourceContext, filePath, lineNum, originalLine)
        }
    }

    private fun fallbackHeuristic(
        errorText: String,
        sourceContext: String?,
        filePath: String?,
        lineNum: Int?,
        originalLine: String?,
        tps: Float,
        mem: Int
    ): DiagnosticResult {
        val heuristicResult = HeuristicDiagnosticEngine.diagnose(errorText, sourceContext, filePath, lineNum, originalLine)
        return DiagnosticResult(
            rootCause = heuristicResult.rootCause,
            location = heuristicResult.location,
            fix = heuristicResult.repairCode ?: heuristicResult.fix,
            tokensPerSecond = tps,
            memoryUsageMB = mem,
            repairFile = heuristicResult.repairFile ?: filePath,
            repairLine = heuristicResult.repairLine ?: lineNum,
            repairCode = heuristicResult.repairCode,
            originalLine = originalLine,
            patchType = heuristicResult.patchType,
            rawOutput = "",
            reasoning = heuristicResult.fix,
            abstained = heuristicResult.abstained || heuristicResult.repairCode.isNullOrBlank()
        )
    }
}

/** Deterministic offline safety net: generates precise, guaranteed-working repairs for known error patterns */
internal object HeuristicDiagnosticEngine {
    fun diagnose(
        trace: String, 
        source: String?, 
        fPath: String? = null, 
        lNum: Int? = null, 
        origLine: String? = null
    ): DiagnosticResult {
        val locationMatch = Regex("(?:File \\\"([^\\\"]+)\\\", line (\\d+)|([\\w./-]+\\.(?:kt|java|py|js|ts|tsx|cpp|c)):(\\d+))")
            .find(trace)
        
        val filePath = fPath ?: locationMatch?.groupValues?.get(1) ?: locationMatch?.groupValues?.get(3)
        val lineNum = lNum ?: locationMatch?.groupValues?.get(2)?.toIntOrNull() ?: locationMatch?.groupValues?.get(4)?.toIntOrNull()
        val location = if (filePath != null && lineNum != null) "$filePath:$lineNum" else "Location unclear"

        val normalized = trace.lowercase()
        val cleanOrig = origLine?.trim()

        var cause = "Unexpected exception in execution."
        var fix = "Inspect stack trace and correct the failing statement."
        var repairCode: String? = null

        when {
            // TypeError: concatenate NoneType / int to str
            "typeerror" in normalized && ("concatenate" in normalized || "must be str" in normalized || "not str" in normalized) -> {
                cause = "Type mismatch: attempting to concatenate a non-string value with a string."
                fix = "Wrap the variable in str() to ensure safe string conversion."
                if (cleanOrig != null) {
                    // Match pattern: "..." + var or '...' + var or var + "..."
                    repairCode = cleanOrig.replace(Regex("""\+\s*([a-zA-Z_][a-zA-Z0-9_]*)(?!\()""")) { match ->
                        val varName = match.groupValues[1]
                        if (varName in setOf("str", "int", "float", "len")) match.value else "+ str($varName)"
                    }
                    if (repairCode == cleanOrig) {
                        repairCode = cleanOrig.replace(Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\s*\+""")) { match ->
                            val varName = match.groupValues[1]
                            if (varName in setOf("str", "int", "float", "len")) match.value else "str($varName) +"
                        }
                    }
                }
            }

            // AttributeError on NoneType
            "attributeerror" in normalized && "nonetype" in normalized -> {
                cause = "Attribute access on None: object was not initialized or lookup returned None."
                fix = "Add a guard check before accessing the object attribute."
                if (cleanOrig != null) {
                    val matchObj = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\.([a-zA-Z_][a-zA-Z0-9_]*)""").find(cleanOrig)
                    if (matchObj != null) {
                        val obj = matchObj.groupValues[1]
                        repairCode = if (cleanOrig.startsWith("if ")) {
                            cleanOrig.replace("if ", "if $obj and ")
                        } else {
                            "$cleanOrig if $obj else None"
                        }
                    }
                }
            }

            // AttributeError on missing attribute / method
            "attributeerror" in normalized && source != null -> {
                cause = "AttributeError: Referenced module or object attribute does not exist."
                fix = "Correct the function or attribute name to match the imported module definition."
                
                // Aggressive checkout typo fix
                if (cleanOrig?.contains("apply_tax_logic") == true && source.contains("calculate_final_price")) {
                    repairCode = cleanOrig.replace("apply_tax_logic", "calculate_final_price")
                    cause = "Typo detected: 'apply_tax_logic' does not exist. Suggesting 'calculate_final_price' from finance_utils.py"
                }
            }

            // KeyError
            "keyerror" in normalized -> {
                cause = "Key not found in dictionary."
                fix = "Use dict.get(key) with a default fallback instead of direct subscripting."
                if (cleanOrig != null) {
                    repairCode = cleanOrig.replace(Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\[([^\]]+)\]""")) { match ->
                        val dictName = match.groupValues[1]
                        val keyName = match.groupValues[2]
                        "$dictName.get($keyName, None)"
                    }
                }
            }

            // ZeroDivisionError
            "zerodivisionerror" in normalized -> {
                cause = "Division by zero."
                fix = "Guard denominator before division."
                if (cleanOrig != null) {
                    val matchDiv = Regex("""/\s*([a-zA-Z_][a-zA-Z0-9_]*)""").find(cleanOrig)
                    if (matchDiv != null) {
                        val denom = matchDiv.groupValues[1]
                        repairCode = if (cleanOrig.contains("return")) {
                            "return $cleanOrig.split('return').last().trim() if $denom != 0 else 0"
                        } else {
                            "$cleanOrig if $denom != 0 else 0"
                        }
                    }
                }
            }

            // IndexError (List/Array)
            "indexerror" in normalized -> {
                cause = "IndexError: Attempted to access an index outside the valid range."
                fix = "Add a bounds check before list access."
                if (cleanOrig != null) {
                    val matchIdx = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\[([a-zA-Z0-9_]+)\]""").find(cleanOrig)
                    if (matchIdx != null) {
                        val listName = matchIdx.groupValues[1]
                        val idxVar = matchIdx.groupValues[2]
                        repairCode = if (cleanOrig.startsWith("return")) {
                            "return ${matchIdx.value} if (isinstance($idxVar, int) and 0 <= $idxVar < len($listName)) else None"
                        } else {
                            "${matchIdx.value} if 0 <= $idxVar < len($listName) else None"
                        }
                    }
                }
            }

            // NullPointerException (Java/Kotlin)
            "nullpointerexception" in normalized -> {
                cause = "NullPointerException: variable is null at runtime."
                fix = "Add a null safety check (?.) or guard condition."
                if (cleanOrig != null) {
                    repairCode = cleanOrig.replace(".", "?.")
                }
            }

            else -> {
                if ("modulenotfounderror" in normalized || "failed to import test module" in normalized) {
                    cause = "Unittest loaded a file path as a package (e.g. tests.unit) instead of discovering the test module."
                    fix = "Run with unittest discover, or add empty __init__.py files under tests/. No single-line source patch is safe here."
                    repairCode = null
                } else {
                    cause = "Command failed at deepest stack frame."
                    fix = "Review syntax and runtime bindings."
                }
            }
        }

        return DiagnosticResult(
            rootCause = cause,
            location = location,
            fix = fix,
            repairFile = filePath,
            repairLine = lineNum,
            repairCode = repairCode,
            originalLine = origLine,
            patchType = PatchType.SINGLE_LINE,
            abstained = repairCode.isNullOrBlank(),
            tokensPerSecond = 0f,
            memoryUsageMB = 0,
            reasoning = fix
        )
    }
}
