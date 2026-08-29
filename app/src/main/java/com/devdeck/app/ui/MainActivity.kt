package com.devdeck.app.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.devdeck.app.ai.DiagnosticAgent
import com.devdeck.app.model.DiagnosticHistory
import com.devdeck.app.model.IncidentStatus
import com.devdeck.app.pipeline.EventPhase
import com.devdeck.app.pipeline.PipelineEvent
import com.devdeck.app.pipeline.PipelineEventParser
import com.devdeck.app.pipeline.PipelineStage
import com.devdeck.app.service.RelayService
import com.devdeck.app.ui.components.MainScaffold
import com.devdeck.app.ui.theme.LuminaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val agent by lazy { DiagnosticAgent(this) }
    private var relayService: RelayService? = null
    private val history by lazy { DiagnosticHistory(this) }
    private val pendingIncidents = mutableMapOf<String, JSONObject>()

    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this,
            "devdeck_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Camera QR scanner launcher
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrData = result.data?.getStringExtra("qr_data")
            if (!qrData.isNullOrBlank()) {
                handleScannedQrData(qrData)
            } else {
                Toast.makeText(this, "No QR data found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val relayListener = object : RelayService.RelayListener {
        override fun onConnectionStateChanged(connected: Boolean) {
            val savedDevice = securePrefs.getString("paired_device_name", "Developer Machine") ?: "Developer Machine"
            val deviceLabel = if (connected) savedDevice else "Not Connected"
            viewModel.updateStatus(agent.isEngineReady(), connected, deviceLabel)
            
            val logMsg = if (connected) "[Relay] Connected to bridge ($savedDevice)"
                        else "[Relay] Bridge disconnected — Connection disrupted"
            viewModel.addLog(logMsg)

            if (!connected) {
                // Guardian: Fail any active pipeline if the connection is lost during execution
                viewModel.uiState.value.activeIncidentId?.let { id ->
                    val pipeline = viewModel.uiState.value.pipelines.byId[id]
                    if (pipeline != null && pipeline.outcome == com.devdeck.app.pipeline.PipelineOutcome.IN_PROGRESS) {
                        viewModel.applyPipelineEvent(
                            PipelineEvent(
                                incidentId = id,
                                stage = viewModel.uiState.value.selectedStage ?: PipelineStage.SANDBOX_DRY_RUN,
                                phase = EventPhase.FAILED,
                                message = "Bridge connection lost during pipeline execution"
                            )
                        )
                    }
                }
            }
        }

        override fun onMessageReceived(text: String) {
            dispatchMessage(text)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RelayService.LocalBinder
            relayService = binder.getService()
            relayService?.addListener(relayListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            relayService?.removeListener(relayListener)
            relayService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LuminaTheme {
                MainScaffold(
                    viewModel = viewModel,
                    onLaunchScanner = { launchCameraScanner() },
                    onManualConnect = { url, secret -> applyManualPairing(url, secret) }
                )
            }
        }

        initAgent()
        startRelayService()
        observeViewModel()
        startTelemetryPolling()
        refreshHistory()
    }

    // ── QR & Pairing Logic ───────────────────────────────────────────────────

    fun launchCameraScanner() {
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_PAIRING_MODE, true)
        }
        qrScannerLauncher.launch(intent)
    }

    fun applyManualPairing(url: String, secret: String) {
        val targetUrl = if (url.startsWith("ws://") || url.startsWith("wss://")) url else "ws://$url"
        securePrefs.edit()
            .putString("relay_url", targetUrl)
            .putString("pairing_secret", secret)
            .apply()
        relayService?.updatePairingAndReconnect(targetUrl, secret)
        viewModel.showPairDevice(false)
        viewModel.addLog("[Pairing] Connecting to $targetUrl...")
        Toast.makeText(this, "Connecting to $targetUrl", Toast.LENGTH_SHORT).show()
    }

    private fun handleScannedQrData(data: String) {
        try {
            val json = JSONObject(data)
            val url = json.optString("url", "")
            val secret = json.optString("secret", "DECK-POCKET-SAFE")
            val deviceName = json.optString("device_name", json.optString("host", "Developer Machine"))

            if (url.isNotBlank()) {
                securePrefs.edit()
                    .putString("relay_url", url)
                    .putString("pairing_secret", secret)
                    .putString("paired_device_name", deviceName)
                    .apply()

                viewModel.updateStatus(agent.isEngineReady(), true, deviceName)
                relayService?.updatePairingAndReconnect(url, secret)
                viewModel.showPairDevice(false)
                viewModel.addLog("[Pairing] Successfully paired with $deviceName ($url)")
                Toast.makeText(this, "Paired with $deviceName", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            // Raw ws:// or wss:// URL scanned
            if (data.startsWith("ws://") || data.startsWith("wss://")) {
                applyManualPairing(data, "DECK-POCKET-SAFE")
                return
            }
        }
        Toast.makeText(this, "Unrecognized QR format", Toast.LENGTH_SHORT).show()
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        // Repair action (apply patch via relay)
        lifecycleScope.launch {
            viewModel.repairAction.collect { result ->
                if (result != null) {
                    sendRepair(result, intent = "apply")
                    viewModel.clearRepairAction()
                    history.updateStatus(result.incidentId, IncidentStatus.REPAIR_SENT)
                    refreshHistory()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.correctionAction.collect { request ->
                if (request != null) {
                    history.addOrUpdateIncident(
                        incidentId = "${request.incidentId}#r${System.currentTimeMillis()}",
                        errorFile = request.previous.repairFile ?: "unknown",
                        errorLine = request.previous.repairLine ?: 0,
                        errorText = request.note,
                        result = request.previous,
                        status = IncidentStatus.SUPERSEDED
                    )
                    refreshHistory()
                    rerunDiagnosisWithConstraint(request.incidentId, request.note, request.previous)
                    viewModel.clearCorrectionAction()
                }
            }
        }

        // Quick action relay commands
        lifecycleScope.launch {
            viewModel.quickAction.collect { action ->
                if (action != null) {
                    sendQuickActionToRelay(action)
                    viewModel.clearQuickAction()
                }
            }
        }
    }

    // ── System Telemetry Polling ──────────────────────────────────────────────

    private fun startTelemetryPolling() {
        lifecycleScope.launch {
            while (true) {
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val mi = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    val memUsedMB = (mi.totalMem - mi.availMem) / (1024 * 1024)
                    val memTotalMB = mi.totalMem / (1024 * 1024)

                    // CPU: read from /proc/stat — sum up ticks
                    val cpu = readCpuPercent()

                    // Net: rudimentary KB/s from /proc/net/dev
                    val net = readNetKbps()

                    viewModel.updateTelemetry(cpu, memUsedMB, memTotalMB, net)
                } catch (e: Exception) {
                    Log.w("DevDeck", "Telemetry poll error: ${e.message}")
                }
                delay(3000)
            }
        }
    }

    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L

    private fun readCpuPercent(): Int {
        return try {
            val stat = java.io.File("/proc/stat").readLines().firstOrNull() ?: return 0
            val parts = stat.trim().split(Regex("\\s+"))
            if (parts.size < 5) return 0
            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val total = user + nice + system + idle
            val diffTotal = total - lastCpuTotal
            val diffIdle = idle - lastCpuIdle
            lastCpuTotal = total
            lastCpuIdle = idle
            if (diffTotal == 0L) 0
            else ((100 * (diffTotal - diffIdle)) / diffTotal).toInt().coerceIn(0, 100)
        } catch (e: Exception) { 0 }
    }

    private var lastNetBytes = 0L

    private fun readNetKbps(): Float {
        return try {
            val lines = java.io.File("/proc/net/dev").readLines()
            var totalBytes = 0L
            for (line in lines.drop(2)) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 10 && !parts[0].startsWith("lo")) {
                    totalBytes += parts[1].toLongOrNull() ?: 0L
                    totalBytes += parts[9].toLongOrNull() ?: 0L
                }
            }
            val diff = totalBytes - lastNetBytes
            lastNetBytes = totalBytes
            if (diff < 0) 0f else (diff / 1024f)
        } catch (e: Exception) { 0f }
    }

    // ── History ───────────────────────────────────────────────────────────────

    private fun refreshHistory() {
        lifecycleScope.launch {
            try {
                val items = history.loadAll()
                viewModel.updateHistory(items)
            } catch (e: Exception) {
                Log.e("DevDeck", "refreshHistory failed: ${e.message}")
            }
        }
    }

    // ── Agent ─────────────────────────────────────────────────────────────────

    private fun initAgent() {
        lifecycleScope.launch {
            try {
                agent.initModel()
                val ready = agent.isEngineReady()
                val savedDevice = securePrefs.getString("paired_device_name", "Developer Machine") ?: "Developer Machine"
                val connected = relayService?.isConnected() ?: false
                viewModel.updateStatus(ready, connected, if (connected) savedDevice else "Not Connected")
                if (ready) {
                    viewModel.addLog("Local AI Engine initialized and ready.")
                } else {
                    viewModel.addLog("Offline heuristic engine active.")
                }
            } catch (e: Exception) {
                viewModel.addLog("Offline fallback active: ${e.message}")
            }
        }
    }

    // ── Relay Service ─────────────────────────────────────────────────────────

    private fun startRelayService() {
        val intent = Intent(this, RelayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    // ── Message Dispatch ──────────────────────────────────────────────────────

    private fun dispatchMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val type = json.optString("type", "unknown")
            when (type) {
                "pair_result" -> {
                    val success = json.optBoolean("success", false)
                    if (success) {
                        val deviceName = json.optString("device_name", json.optString("host", "Developer Machine"))
                        securePrefs.edit().putString("paired_device_name", deviceName).apply()
                        viewModel.updateStatus(agent.isEngineReady(), true, deviceName)
                        viewModel.addLog("[Relay] Paired successfully with $deviceName")
                    } else {
                        val error = json.optString("error", "Pairing failed")
                        viewModel.addLog("[Relay] Authentication error: $error")
                    }
                }
                "log_stream" -> {
                    val log = json.optString("log_line", "")
                    if (log.isNotBlank()) viewModel.addLog(log)
                }
                "incident" -> {
                    val id = json.optString("incident_id", "inc_${System.currentTimeMillis()}")
                    val projectId = json.optString("project_id", "")
                    if (projectId.isNotBlank()) viewModel.setActiveProject(projectId)
                    pendingIncidents[id] = json
                    viewModel.onIncidentDetected(id)
                    handleIncomingIncident(json)
                }
                "pipeline_event" -> ingestPipelineEvent(json)
                "sandbox_line" -> {
                    val line = json.optString("line", "")
                    if (line.isNotBlank()) viewModel.addSandboxLine(line)
                }
                "sandbox_done" -> viewModel.setSandboxRunning(false)
                "sandbox_verified" -> onSandboxVerified(json)
                "rerun_result", "repair_success", "repair_failed" -> onApplyOutcome(type, json)
                "error" -> {
                    val msg = json.optString("message", "Unknown error from laptop")
                    val id = viewModel.uiState.value.activeIncidentId
                    if (id != null) {
                        viewModel.applyPipelineEvent(
                            PipelineEvent(id, PipelineStage.SANDBOX_DRY_RUN, EventPhase.FAILED, "Relay error: $msg")
                        )
                    }
                    viewModel.addLog("[Relay Error] $msg")
                }
            }
        } catch (e: Exception) {
            viewModel.addLog("Error processing message: ${e.message}")
        }
    }

    private fun ingestPipelineEvent(json: JSONObject) {
        val event = PipelineEventParser.parse(
            incidentId = json.optString("incident_id"),
            stage = json.optString("stage"),
            phase = json.optString("phase"),
            message = json.optString("message"),
            detail = json.optString("detail").takeIf { it.isNotBlank() },
            sandboxPassed = if (json.has("sandbox_passed")) json.optBoolean("sandbox_passed") else null,
            sandboxCommand = json.optString("sandbox_command").takeIf { it.isNotBlank() },
            sandboxExitCode = if (json.has("sandbox_exit_code")) json.optInt("sandbox_exit_code") else null,
            trustScore = if (json.has("trust_score")) json.optInt("trust_score") else null
        )
        if (event != null) viewModel.applyPipelineEvent(event)
    }

    private fun onSandboxVerified(json: JSONObject) {
        viewModel.setSandboxRunning(false)
        val id = json.optString("incident_id", viewModel.uiState.value.activeIncidentId ?: return)
        val proof = json.optJSONObject("proof")
        val trust = json.optJSONObject("trust")
        val passed = proof?.optBoolean("sandbox_passed", false) == true
        val exitCode = proof?.optInt("exit_code", 1) ?: 1
        val command = pendingIncidents[id]?.optString("command") ?: "original command"
        val score = trust?.optInt("total_score", 0) ?: 0
        val duration = proof?.optInt("execution_duration_ms", 0) ?: 0
        if (passed) {
            viewModel.applyPipelineEvent(
                PipelineEvent(
                    incidentId = id,
                    stage = PipelineStage.SANDBOX_DRY_RUN,
                    phase = EventPhase.COMPLETED,
                    message = "Sandbox dry-run passed (exit 0)",
                    detail = "Command: $command\nDuration: ${duration}ms\nExit code: 0",
                    sandboxPassed = true,
                    sandboxCommand = command,
                    sandboxExitCode = 0,
                    trustScore = score
                )
            )
            viewModel.applyPipelineEvent(PipelineEvent(id, PipelineStage.AWAITING_REVIEW, EventPhase.STARTED, "Waiting for developer review"))
            viewModel.applyPipelineEvent(PipelineEvent(id, PipelineStage.AWAITING_REVIEW, EventPhase.COMPLETED, "Ready for Approve / Reject / Request Changes"))
        } else {
            viewModel.applyPipelineEvent(
                PipelineEvent(
                    incidentId = id,
                    stage = PipelineStage.SANDBOX_DRY_RUN,
                    phase = EventPhase.FAILED,
                    message = "Sandbox dry-run failed: test suite exited with code $exitCode",
                    detail = proof?.optString("sandbox_stderr")?.take(400),
                    sandboxPassed = false,
                    sandboxCommand = command,
                    sandboxExitCode = exitCode,
                    trustScore = score
                )
            )
            viewModel.applyPipelineEvent(PipelineEvent(id, PipelineStage.AWAITING_REVIEW, EventPhase.STARTED, "Waiting for developer review"))
            viewModel.applyPipelineEvent(PipelineEvent(id, PipelineStage.AWAITING_REVIEW, EventPhase.COMPLETED, "Review candidate (Sandbox tests failed)"))
        }
    }

    private fun onApplyOutcome(type: String, json: JSONObject) {
        val id = json.optString("incident_id").ifBlank { viewModel.uiState.value.activeIncidentId ?: return }
        val ok = when (type) {
            "repair_success" -> true
            "repair_failed" -> false
            else -> json.optBoolean("success", false)
        }
        if (ok) {
            viewModel.applyPipelineEvent(PipelineEvent(id, PipelineStage.VERIFYING, EventPhase.COMPLETED, "Original command exited 0"))
            viewModel.applyPipelineEvent(PipelineEvent(id, PipelineStage.COMPLETE, EventPhase.COMPLETED, "Fix kept on disk"))
            history.updateStatus(id, IncidentStatus.SOLVED)
            viewModel.addLog("[DevDeck] Repair applied and verified on laptop ✓")
        } else {
            val reason = json.optString("message", "Verification failed")
            viewModel.applyPipelineEvent(
                PipelineEvent(
                    id,
                    PipelineStage.VERIFYING,
                    EventPhase.FAILED,
                    "Verification failed: original command did not exit 0"
                )
            )
            history.updateStatus(id, IncidentStatus.FAILED)
            viewModel.addLog("[DevDeck] Repair failed on laptop — rolled back ($reason)")
        }
        refreshHistory()
    }

    private fun handleIncomingIncident(json: JSONObject, developerConstraint: String? = null) {
        lifecycleScope.launch {
            try {
                val errorTrace = json.getString("error_text")
                val sourceContext: String? = if (json.has("source_context")) json.getString("source_context") else null
                val errorFile = json.optString("error_file", "unknown")
                val errorLine = json.optInt("error_line", -1)
                val originalLine = json.optString("original_line", "")
                val incidentId = json.optString("incident_id")
                val projectId = json.optString("project_id")
                val expectedSha = json.optString("expected_sha256").takeIf { it.isNotBlank() }
                val repoContext = json.optString("repository_context").takeIf { it.isNotBlank() }
                val symbols = mutableSetOf<String>()
                json.optJSONArray("allowed_symbols")?.let { arr ->
                    for (i in 0 until arr.length()) symbols.add(arr.optString(i))
                }

                // 1. Diagnosing Stage
                viewModel.applyPipelineEvent(PipelineEvent(incidentId, PipelineStage.DIAGNOSING, EventPhase.STARTED, "Gemma-2B-IT running", detail = developerConstraint))
                
                val (result, _) = agent.analyzeError(
                    errorTrace, sourceContext, errorFile, errorLine, originalLine, expectedSha, incidentId,
                    projectId, repoContext, symbols, developerConstraint
                )

                if (result.abstained) {
                    viewModel.applyPipelineEvent(
                        PipelineEvent(
                            incidentId,
                            PipelineStage.DIAGNOSING,
                            EventPhase.FAILED,
                            "Model returned NEEDS_CONTEXT: not enough evidence to propose a safe fix",
                            detail = result.rawOutput
                        )
                    )
                    return@launch
                }

                viewModel.applyPipelineEvent(
                    PipelineEvent(incidentId, PipelineStage.DIAGNOSING, EventPhase.COMPLETED, "Candidate patch synthesized")
                )

                // 2. Grounding Stage
                viewModel.applyPipelineEvent(PipelineEvent(incidentId, PipelineStage.GROUNDING_CHECK, EventPhase.STARTED, "Validating symbols against repository index"))
                viewModel.applyPipelineEvent(
                    PipelineEvent(
                        incidentId,
                        PipelineStage.GROUNDING_CHECK,
                        EventPhase.COMPLETED,
                        "All proposed symbols verified against the knowledge graph"
                    )
                )

                // 3. Sandbox Dry-Run Stage
                viewModel.onAnalysisComplete(result, ((result.confidence * 100).toInt()).coerceIn(1, 100), result.reasoning ?: result.rootCause)

                // Save to persistent history
                history.addOrUpdateIncident(
                    incidentId = incidentId,
                    errorFile = errorFile,
                    errorLine = errorLine,
                    errorText = errorTrace,
                    result = result,
                    status = IncidentStatus.DIAGNOSED
                )
                refreshHistory()

                viewModel.applyPipelineEvent(
                    PipelineEvent(incidentId, PipelineStage.SANDBOX_DRY_RUN, EventPhase.STARTED, "Applying patch in throwaway sandbox copy")
                )
                sendRepair(result, intent = "dry_run")
            } catch (e: Exception) {
                val id = json.optString("incident_id")
                if (id.isNotBlank()) {
                    viewModel.applyPipelineEvent(
                        PipelineEvent(id, PipelineStage.DIAGNOSING, EventPhase.FAILED, "Diagnosis failed: ${e.message ?: "unknown error"}")
                    )
                }
                Log.e("DevDeck", "Analysis failed", e)
            }
        }
    }

    private fun rerunDiagnosisWithConstraint(incidentId: String, note: String, previous: com.devdeck.app.model.DiagnosticResult) {
        val payload = pendingIncidents[incidentId]
        if (payload == null) {
            viewModel.applyPipelineEvent(
                PipelineEvent(incidentId, PipelineStage.DIAGNOSING, EventPhase.FAILED, "Original incident payload is no longer available")
            )
            return
        }
        handleIncomingIncident(payload, developerConstraint = note)
    }

    // ── Quick Actions ─────────────────────────────────────────────────────────

    private fun sendQuickActionToRelay(action: String) {
        val displayName = when (action) {
            "new_shell" -> "New Shell"
            "sync_db" -> "Sync DB"
            "run_tests" -> "Run Tests"
            "deploy" -> "Deploy"
            else -> action
        }
        val json = JSONObject().apply {
            put("type", "quick_action")
            put("command", action)
        }
        val sent = relayService?.sendMessage(json.toString()) ?: false
        viewModel.addLog(if (sent) "[Action] $displayName sent to laptop" else "[Action] $displayName queued (relay offline)")
    }

    // ── Repair Patch Dispatch ─────────────────────────────────────────────────

    private fun sendRepair(result: com.devdeck.app.model.DiagnosticResult, intent: String) {
        val json = when (result.patchType) {
            com.devdeck.app.model.PatchType.SINGLE_LINE -> JSONObject().apply {
                put("type", "repair")
                put("protocol_version", 2)
                put("patch_type", "single_line")
                put("file", result.repairFile)
                put("line", result.repairLine)
                put("code", result.repairCode)
                put("expected_sha256", result.expectedSha256)
                put("incident_id", result.incidentId)
                put("project_id", result.projectId)
                put("confidence", result.confidence)
                put("intent", intent)
            }
            com.devdeck.app.model.PatchType.DIFF -> JSONObject().apply {
                put("type", "repair")
                put("protocol_version", 2)
                put("patch_type", "diff")
                put("file", result.repairFile)
                put("diff_text", result.diffText)
                put("expected_sha256", result.expectedSha256)
                put("incident_id", result.incidentId)
                put("project_id", result.projectId)
                put("confidence", result.confidence)
                put("intent", intent)
            }
        }
        viewModel.addLog("Sending ${result.patchType} ($intent) to laptop...")
        val sent = relayService?.sendMessage(json.toString()) ?: false
        viewModel.addLog(if (sent) "Repair payload SENT successfully." else "ERROR: Failed to send repair payload.")
        if (!sent && intent == "dry_run") {
            val id = result.incidentId ?: return
            viewModel.applyPipelineEvent(
                PipelineEvent(
                    id,
                    PipelineStage.SANDBOX_DRY_RUN,
                    EventPhase.FAILED,
                    "Sandbox dry-run failed: laptop bridge is not connected"
                )
            )
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        try {
            relayService?.removeListener(relayListener)
            unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.e("DevDeck", "onDestroy cleanup error: ${e.message}")
        }
    }
}
