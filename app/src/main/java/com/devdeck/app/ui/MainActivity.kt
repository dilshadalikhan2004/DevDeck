package com.devdeck.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
import android.widget.Toast
import android.util.Log
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.devdeck.app.ai.DiagnosticAgent
import com.devdeck.app.model.DiagnosticHistory
import com.devdeck.app.service.RelayService
import com.devdeck.app.ui.components.DashboardScreen
import com.devdeck.app.ui.theme.LuminaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val agent by lazy { DiagnosticAgent(this) }
    private var relayService: RelayService? = null
    private val history by lazy { DiagnosticHistory(this) }
    
    private val telemetryLogs = MutableStateFlow<List<String>>(listOf(
        "INFO: Gateway initialized on port 8080",
        "REQ: /api/v3/auth/token - 200 OK (12ms)",
        "REQ: /api/v3/users/me - 200 OK (45ms)",
        "WARN: Rate limit approaching for client_id: 8f92a",
        "REQ: /api/v3/data/sync - 202 ACCEPTED (210ms)"
    ))

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

    private val relayListener = object : RelayService.RelayListener {
        override fun onConnectionStateChanged(connected: Boolean) {
            appendToTerminal(if (connected) "[Relay] Connected to desktop bridge" else "[Relay] Reconnecting...", if (connected) "ok" else "sys")
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

    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrData = result.data?.getStringExtra("qr_data")
            if (!qrData.isNullOrEmpty()) {
                handlePairingData(qrData)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            LuminaTheme {
                DashboardScreen(
                    telemetryLogs = telemetryLogs.collectAsState().value,
                    onAction = { action ->
                        when (action) {
                            "new_shell" -> appendToTerminal("System: New shell session requested.")
                            "sync_db" -> appendToTerminal("System: Database synchronization started.")
                            "run_tests" -> appendToTerminal("System: Test suite execution initiated.")
                            "deploy" -> appendToTerminal("System: Deployment pipeline triggered.")
                        }
                    }
                )
            }
        }

        initAgent()
        startRelayService()
    }

    private fun initAgent() {
        lifecycleScope.launch {
            try {
                agent.initModel()
                if (agent.isEngineReady()) {
                    appendToTerminal("Local AI Engine initialized and ready.", "ok")
                } else {
                    appendToTerminal("Local AI Engine initialization failed.", "fail")
                }
            } catch (e: Exception) {
                appendToTerminal("Offline fallback active: ${e.message}", "sys")
            }
        }
    }

    private fun startRelayService() {
        val intent = Intent(this, RelayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun handlePairingData(qrData: String) {
        try {
            val uri = Uri.parse(qrData)
            val url = uri.getQueryParameter("url")
            val secret = uri.getQueryParameter("secret")

            if (!url.isNullOrEmpty() && !secret.isNullOrEmpty()) {
                securePrefs.edit()
                    .putString("relay_url", url)
                    .putString("pairing_secret", secret)
                    .apply()
                
                appendToTerminal("[Bridge] Paired with $url. Reconnecting...", "ok")
                stopService(Intent(this, RelayService::class.java))
                startRelayService()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Pairing failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val type = json.optString("type", "unknown")
            when (type) {
                "log_stream" -> {
                    val log = json.optString("log_line", "")
                    val logType = when {
                        "SUCCESS" in log || "PATCH APPLIED" in log -> "ok"
                        "FAILED" in log -> "fail"
                        "Agent" in log -> "agent"
                        else -> "sys"
                    }
                    appendToTerminal(log, logType)
                }
                "pair_result" -> {
                    val success = json.optBoolean("success", false)
                    if (success) {
                        appendToTerminal("[Bridge] Auth verified. Authority granted.", "ok")
                    } else {
                        appendToTerminal("[Bridge] Auth failed: ${json.optString("error")}", "fail")
                    }
                }
                "incident" -> {
                    appendToTerminal("[System] Incoming incident detected. Analyzing...", "sys")
                }
            }
        } catch (e: Exception) {
            appendToTerminal("Error processing message: ${e.message}", "fail")
        }
    }

    private fun appendToTerminal(text: String, type: String = "sys") {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = if (text.length > 1000) text.take(997) + "..." else text
        
        val prefix = when(type) {
            "agent" -> "Agent: "
            "ok" -> "SUCCESS: "
            "fail" -> "FAILED: "
            else -> ""
        }
        
        telemetryLogs.update { (it + "[$timestamp] $prefix$logLine").takeLast(50) }
    }

    override fun onDestroy() {
        super.onDestroy()
        relayService?.removeListener(relayListener)
        unbindService(serviceConnection)
    }
}
