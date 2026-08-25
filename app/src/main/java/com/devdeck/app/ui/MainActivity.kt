package com.devdeck.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.devdeck.app.ai.DiagnosticAgent
import com.devdeck.app.databinding.ActivityMainBinding
import com.devdeck.app.model.DiagnosticHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val agent by lazy { DiagnosticAgent(this) }
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val history by lazy { DiagnosticHistory(this) }

    private val cameraLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val scannedText = result.data?.getStringExtra("scanned_text")
            if (!scannedText.isNullOrBlank()) {
                handleIncomingError(JSONObject().put("error_text", scannedText).toString())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initAgent()
        connectToRelay()
        setupNavigation()
        setupActionButtons()
        
        // Set initial state
        binding.btnNavHome.performClick()
    }

    private fun setupNavigation() {
        val screens = mapOf(
            binding.btnNavHome to binding.screenHome,
            binding.btnNavTrace to binding.screenTrace,
            binding.btnNavDiag to binding.screenDiag
        )

        screens.forEach { (btn, view) ->
            btn.setOnClickListener {
                vibrate()
                // Reset all buttons
                screens.keys.forEach { 
                    it.setTextColor(Color.parseColor("#9297A1"))
                    it.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                }
                // Highlight active
                btn.setTextColor(Color.parseColor("#0B8A78"))
                btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E6F5F2"))
                // Show screen
                screens.values.forEach { it.visibility = View.GONE }
                view.visibility = View.VISIBLE
            }
        }

        // Trace screen tabs
        binding.btnTabTrace.setOnClickListener {
            vibrate()
            binding.btnTabTrace.setBackgroundColor(Color.parseColor("#FBFBFC"))
            binding.btnTabTrace.setTextColor(Color.parseColor("#15171C"))
            binding.btnTabSource.setBackgroundColor(Color.TRANSPARENT)
            binding.btnTabSource.setTextColor(Color.parseColor("#9297A1"))
            
            binding.errorText.visibility = View.VISIBLE
            binding.sourceText.visibility = View.GONE
        }
        binding.btnTabSource.setOnClickListener {
            vibrate()
            binding.btnTabSource.setBackgroundColor(Color.parseColor("#FBFBFC"))
            binding.btnTabSource.setTextColor(Color.parseColor("#15171C"))
            binding.btnTabTrace.setBackgroundColor(Color.TRANSPARENT)
            binding.btnTabTrace.setTextColor(Color.parseColor("#9297A1"))

            binding.errorText.visibility = View.GONE
            binding.sourceText.visibility = View.VISIBLE
        }
    }

    private fun setupActionButtons() {
        binding.demoButton.setOnClickListener { 
            vibrate()
            runDemo() 
        }
        binding.setupButton.setOnClickListener { 
            vibrate()
            showSetup() 
        }
        binding.historyButton.setOnClickListener { 
            vibrate()
            showHistory() 
        }
        binding.scanButton.setOnClickListener { 
            vibrate()
            cameraLauncher.launch(Intent(this, CameraActivity::class.java))
        }
        binding.addContextButton.setOnClickListener { 
            vibrate()
            showAddContextDialog() 
        }
        binding.incidentPreview.setOnClickListener {
            vibrate()
            binding.btnNavDiag.performClick()
        }
    }

    private fun vibrate() {
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun showAddContextDialog() {
        val manager = com.devdeck.app.model.ProjectContextManager(this)
        val input = EditText(this).apply {
            hint = "e.g., Always use CustomLogger for network calls"
        }
        AlertDialog.Builder(this)
            .setTitle("Add Project Rule")
            .setMessage("These rules ground the AI's diagnosis in your specific coding standards.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val rule = input.text.toString().trim()
                if (rule.isNotEmpty()) {
                    manager.addRule(rule)
                    Toast.makeText(this, "Rule added to local knowledge", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear all") { _, _ ->
                manager.clear()
                Toast.makeText(this, "Project context cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun initAgent() {
        lifecycleScope.launch {
            try {
                agent.initModel()
                when {
                    agent.isEngineReady() -> {
                        binding.modelStatus.text = "LOCAL AI: READY"
                        binding.modelStatus.setTextColor(Color.parseColor("#0B8A78"))
                        binding.modelDot.setBackgroundColor(Color.parseColor("#0B8A78"))
                        binding.modelStatusContainer.setBackgroundColor(Color.parseColor("#E6F5F2"))
                    }
                    agent.isModelAvailable() -> {
                        binding.modelStatus.text = "MODEL ERROR"
                        binding.modelStatus.setTextColor(Color.parseColor("#B42318"))
                        binding.modelDot.setBackgroundColor(Color.parseColor("#B42318"))
                        binding.modelStatusContainer.setBackgroundColor(Color.parseColor("#FDECEA"))
                        appendToTerminal("[System] Model file found but format is unsupported.", "fail")
                    }
                    else -> {
                        binding.modelStatus.text = "MODEL MISSING"
                        binding.modelStatus.setTextColor(Color.parseColor("#B42318"))
                        binding.modelDot.setBackgroundColor(Color.parseColor("#B42318"))
                        binding.modelStatusContainer.setBackgroundColor(Color.parseColor("#FDECEA"))
                    }
                }
            } catch (e: Exception) {
                binding.modelStatus.text = "OFFLINE FALLBACK"
                binding.modelStatus.setTextColor(Color.parseColor("#A9660A"))
                binding.modelDot.setBackgroundColor(Color.parseColor("#A9660A"))
                binding.modelStatusContainer.setBackgroundColor(Color.parseColor("#FBF1E1"))
            }
        }
    }

    private var reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    private fun connectToRelay() {
        cancelReconnect()
        webSocket?.cancel()
        
        // Keep screen active while app is paired for Desk Standby Mode
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val relay = getSharedPreferences("devdeck", MODE_PRIVATE)
            .getString("relay_url", "ws://localhost:8765")!!
        val request = try { Request.Builder().url(relay).build() } catch (_: Exception) {
            updateRelayStatus("INVALID URL", false)
            return
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    updateRelayStatus("CONNECTED", true)
                    binding.liveTag.visibility = View.VISIBLE
                    appendToTerminal("[Bridge] Connected to $relay", "ok")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    dispatchMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    updateRelayStatus("DISCONNECTED", false)
                    binding.liveTag.visibility = View.GONE
                    scheduleAutoReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    updateRelayStatus("RETRYING...", false)
                    binding.liveTag.visibility = View.GONE
                    appendToTerminal("[Socket] Disconnected: ${t.message ?: "Connection lost"}", "fail")
                    scheduleAutoReconnect()
                }
            }
        }
        webSocket = client.newWebSocket(request, listener)
    }

    private fun scheduleAutoReconnect() {
        cancelReconnect()
        reconnectRunnable = Runnable {
            appendToTerminal("[Bridge] Attempting auto-reconnect to relay...", "sys")
            connectToRelay()
        }
        reconnectHandler.postDelayed(reconnectRunnable!!, 3000)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun updateRelayStatus(status: String, ok: Boolean) {
        binding.relayStatus.text = "RELAY: $status"
        val color = if (ok) "#0B8A78" else "#B42318"
        val tint = if (ok) "#E6F5F2" else "#FDECEA"
        binding.relayStatus.setTextColor(Color.parseColor(color))
        binding.relayDot.setBackgroundColor(Color.parseColor(color))
        binding.relayStatusContainer.setBackgroundColor(Color.parseColor(tint))
    }

    private fun dispatchMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val type = json.optString("type", "error")
            if (type == "log_stream") {
                val log = json.optString("log_line", "")
                val logType = when {
                    "SUCCESS" in log -> "ok"
                    "FAILED" in log -> "fail"
                    "Agent" in log -> "agent"
                    else -> "sys"
                }
                appendToTerminal(log, logType)
                handleLogStream(log)
            } else {
                handleIncomingError(jsonText)
            }
        } catch (e: Exception) {
            appendToTerminal("Error processing message: ${e.message}", "fail")
        }
    }

    private fun appendToTerminal(text: String, type: String = "sys") {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = if (text.length > 1000) text.take(997) + "..." else text
        
        val color = when(type) {
            "agent" -> "#5EEAD4" // Teal
            "ok" -> "#9FE8B0" // Green
            "fail" -> "#FF8A8A" // Red
            else -> "#5B6270" // Muted Gray
        }

        val spannable = android.text.SpannableString("[$timestamp] $logLine\n")
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(Color.parseColor(color)),
            0, spannable.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.terminalText.append(spannable)
        
        // Auto-scroll to bottom
        binding.terminalScroll.post {
            binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
        }
        
        // Limit buffer
        val currentText = binding.terminalText.text
        if (currentText.length > 5000) {
            binding.terminalText.text = currentText.substring(currentText.length - 3000)
        }
    }

    private fun handleLogStream(logLine: String) {
        if (logLine.isBlank()) return
        lifecycleScope.launch {
            val alert = agent.analyzeLogStream(logLine)
            if (alert != null) {
                binding.modelStatus.text = "PROACTIVE: $alert"
                binding.modelStatus.setTextColor(Color.parseColor("#D29922")) // Amber
            }
        }
    }

    private fun handleIncomingError(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val errorTrace = json.getString("error_text")
            val sourceContext = if (json.has("source_context") && !json.isNull("source_context")) {
                json.getString("source_context")
            } else null
            
            val errorFile = json.optString("error_file", "unknown_file")
            val errorLine = if (json.has("error_line")) json.getInt("error_line") else -1
            val originalLine = json.optString("original_line", "unknown line")

            // Update Home Screen Card
            val title = try {
                val lastLine = errorTrace.trim().split('\n').last()
                val errorName = if (":" in lastLine) lastLine.split(':').first() else "Error"
                val fileName = errorFile.split('\\').last().split('/').last()
                "$errorName · $fileName"
            } catch (e: Exception) {
                "Incident Detected"
            }
            binding.incidentTitle.text = title
            binding.incidentMeta.text = "line $errorLine · Just now"
            
            // Update Trace Screen
            binding.tracePath.text = errorFile
            binding.traceLine.text = "LINE $errorLine"
            binding.errorText.text = SyntaxHighlighter.highlight(this, errorTrace)
            binding.sourceText.text = if (!sourceContext.isNullOrBlank()) {
                SyntaxHighlighter.highlight(this, sourceContext)
            } else "No source context available."

            // Update Diagnosis Screen
            appendToTerminal("Updating UI for incident...", "sys")
            binding.diagTargetFile.text = errorFile.split('\\').last().split('/').last()
            binding.diagnosisCard.visibility = View.VISIBLE
            
            // Reset UI for new analysis
            binding.causeText.text = "Analyzing..."
            binding.locationText.text = "$errorFile : $errorLine"
            binding.diffRemoved.text = "− $originalLine"
            binding.diffAdded.visibility = View.GONE
            binding.inferenceTimeText.text = "Ready"
            binding.telemetryLayout.visibility = View.GONE
            binding.loadingText.visibility = View.VISIBLE
            binding.specBlock.visibility = View.VISIBLE

            // Auto-switch to Diagnosis Screen
            binding.btnNavDiag.performClick()

            appendToTerminal("Starting on-device analysis...", "sys")
            // NPU Pulsing Animation
            val pulseAnim = android.animation.ObjectAnimator.ofFloat(binding.diagnosisCard, "alpha", 1.0f, 0.7f).apply {
                duration = 800
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }

            lifecycleScope.launch {
                val (result, duration) = agent.analyzeError(errorTrace, sourceContext, errorFile, errorLine, originalLine)
                
                // PRINT FULL AI OUTPUT TO CONSOLE
                appendToTerminal("Agent: Targeting line: '$originalLine'", "agent")
                if (result.repairCode != null) {
                    appendToTerminal("Agent: REPAIR GENERATED for ${result.location}", "ok")
                } else {
                    appendToTerminal("Agent: NO CONFIDENT REPAIR FOUND.", "fail")
                }
                
                pulseAnim.cancel()
                binding.diagnosisCard.alpha = 1.0f
                
                binding.loadingText.visibility = View.GONE
                binding.inferenceTimeText.text = if (duration > 0) "${(duration / 1000f)}s" else "offline"
                
                binding.telemetryLayout.visibility = View.VISIBLE
                binding.tpsText.text = "TPS %.1f".format(result.tokensPerSecond)
                binding.memText.text = "MEM ${result.memoryUsageMB}MB"
                
                if (result.isParsed) {
                    binding.causeText.text = result.rootCause
                    binding.locationText.text = "${result.location} : ${result.repairLine ?: errorLine}"
                    
                    if (result.repairCode != null) {
                        binding.diffAdded.visibility = View.VISIBLE
                        binding.diffAdded.text = "+ ${result.repairCode}"
                        
                        binding.repairButton.visibility = View.VISIBLE
                        binding.repairButton.text = "Apply autonomous repair"
                        binding.repairButton.isEnabled = true
                        binding.repairButton.setOnClickListener {
                            vibrate()
                            sendRepair(result)
                        }
                        
                        // AGENTIC BEHAVIOR: If switch is on, auto-send repair
                        if (binding.agentModeSwitch.isChecked) {
                            appendToTerminal("Agent: High confidence fix found. Applying autonomously...")
                            sendRepair(result)
                        }
                    } else {
                        binding.diffAdded.visibility = View.GONE
                        binding.repairButton.visibility = View.GONE
                        
                        // Debugging: If no repair found, allow tapping the card to see raw AI output
                        binding.diagnosisCard.setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Raw AI Reasoning")
                                .setMessage(result.rawOutput ?: "No raw output captured.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                } else {
                    binding.causeText.text = "Analysis complete (Verbose format)"
                    // Fallback fix text if not parsed but present in fix field
                    binding.diffAdded.visibility = View.VISIBLE
                    binding.diffAdded.text = "+ ${result.fix}"
                }
                history.add(result)
            }
        } catch (e: Exception) {
            appendToTerminal("[System] Failed to parse incident: ${e.message}")
        }
    }

    private fun sendRepair(result: com.devdeck.app.model.DiagnosticResult) {
        val json = when (result.patchType) {
            com.devdeck.app.model.PatchType.SINGLE_LINE -> JSONObject().apply {
                put("type", "repair")
                put("patch_type", "single_line")
                put("file", result.repairFile)
                put("line", result.repairLine)
                put("code", result.repairCode)
            }
            com.devdeck.app.model.PatchType.DIFF -> JSONObject().apply {
                put("type", "repair")
                put("patch_type", "diff")
                put("file", result.repairFile)
                put("diff_text", result.diffText)
            }
        }
        appendToTerminal("Sending ${result.patchType} repair to laptop...", "sys")
        val sent = webSocket?.send(json.toString()) ?: false
        if (sent) {
            appendToTerminal("Repair payload SENT successfully.", "ok")
            binding.repairButton.text = "Repair sent to laptop"
            binding.repairButton.isEnabled = false
            binding.repairButton.icon = ContextCompat.getDrawable(this, android.R.drawable.checkbox_on_background)
            binding.repairButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E6F5F2"))
            binding.repairButton.setTextColor(Color.parseColor("#0B8A78"))
        } else {
            appendToTerminal("ERROR: Failed to send repair payload.", "fail")
        }
    }

    private fun runDemo() {
        handleIncomingError("""
            Traceback (most recent call last):
              File "auth_service.py", line 42, in get_user_token
                if user.is_authenticated():
            AttributeError: 'NoneType' object has no attribute 'is_authenticated'
        """.trimIndent().let { trace ->
            JSONObject()
                .put("error_text", trace)
                .put("error_file", "auth_service.py")
                .put("error_line", 42)
                .put("original_line", "if user.is_authenticated():")
                .put("source_context", """
                38: user = db.find_user(user_id)
                40: print(f"Fetching token for {user_id}")
                >>> 42: if user.is_authenticated():
                43:     return user.token
            """.trimIndent()).toString()
        })
    }

    private fun showSetup() {
        val prefs = getSharedPreferences("devdeck", MODE_PRIVATE)
        val input = EditText(this).apply {
            hint = "ws://192.168.x.x:8765"
            setText(prefs.getString("relay_url", "ws://localhost:8765"))
            setSelectAllOnFocus(false)
        }
        AlertDialog.Builder(this)
            .setTitle("Laptop pairing")
            .setMessage("Use ws://localhost:8765 with adb reverse, or enter your laptop's local-network address.")
            .setView(input)
            .setPositiveButton("Save & connect") { _, _ ->
                prefs.edit().putString("relay_url", input.text.toString().trim()).apply()
                connectToRelay()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showHistory() {
        AlertDialog.Builder(this)
            .setTitle("Private debugging log")
            .setMessage(history.summary())
            .setPositiveButton("Done", null).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelReconnect()
        webSocket?.close(1000, "App destroyed")
    }
}
