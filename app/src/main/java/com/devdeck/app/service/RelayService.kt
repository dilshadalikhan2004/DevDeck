package com.devdeck.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RelayService : Service() {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private val handler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()

    private val listeners = mutableSetOf<RelayListener>()

    interface RelayListener {
        fun onConnectionStateChanged(connected: Boolean)
        fun onMessageReceived(text: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): RelayService = this@RelayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Initializing..."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connect()
        return START_STICKY
    }

    fun addListener(listener: RelayListener) {
        listeners.add(listener)
        listener.onConnectionStateChanged(isConnected.get())
    }

    fun removeListener(listener: RelayListener) {
        listeners.remove(listener)
    }

    fun isConnected(): Boolean = isConnected.get()

    fun sendMessage(text: String): Boolean {
        val currentWs = webSocket
        return if (currentWs != null && isConnected.get()) {
            currentWs.send(text)
        } else {
            false
        }
    }

    fun updatePairingAndReconnect(url: String, secret: String) {
        val prefs = getSecurePrefs()
        val oldUrl = prefs.getString("relay_url", "")
        val oldSecret = prefs.getString("pairing_secret", "")
        prefs.edit()
            .putString("relay_url", url)
            .putString("pairing_secret", secret)
            .apply()

        if (oldUrl != url || oldSecret != secret || !isConnected.get() || webSocket == null) {
            reconnectAttempt = 0
            handler.removeCallbacks(reconnectRunnable)
            handler.removeCallbacks(notifyDisconnected)
            connecting.set(false)
            try {
                webSocket?.close(1000, "Switching host")
            } catch (e: Exception) {
                webSocket?.cancel()
            }
            webSocket = null
            isConnected.set(false)
            connect()
        }
    }

    fun reconnect() {
        reconnectAttempt = 0
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(notifyDisconnected)
        connecting.set(false)
        try {
            webSocket?.close(1000, "Manual reconnect")
        } catch (e: Exception) {
            webSocket?.cancel()
        }
        webSocket = null
        isConnected.set(false)
        connect()
    }

    private fun getSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            this,
            "devdeck_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    private fun connect() {
        if (isConnected.get() && webSocket != null) {
            Log.d(TAG, "Already connected to bridge, skipping redundant connect()")
            return
        }
        if (!connecting.compareAndSet(false, true)) {
            Log.d(TAG, "Connection already in progress, skipping redundant connect()")
            return
        }

        handler.removeCallbacks(reconnectRunnable)
        val prefs = getSecurePrefs()
        val url = prefs.getString("relay_url", "ws://10.0.2.2:8765") ?: "ws://10.0.2.2:8765"
        val secret = prefs.getString("pairing_secret", "DECK-POCKET-SAFE") ?: "DECK-POCKET-SAFE"

        Log.d(TAG, "Connecting to $url")

        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            Log.e(TAG, "Invalid URL: $url")
            connecting.set(false)
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connecting.set(false)
                isConnected.set(true)
                reconnectAttempt = 0
                handler.removeCallbacks(notifyDisconnected)
                updateNotification("Connected to Bridge")
                
                // Pair immediately
                ws.send(JSONObject().apply {
                    put("type", "pair")
                    put("secret", secret)
                    put("device_public_key", Build.MODEL ?: "Android-DevDeck")
                }.toString())
                
                notifyListeners { it.onConnectionStateChanged(true) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                notifyListeners { it.onMessageReceived(text) }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                handleDisconnect("Closing ($code: $reason)")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handleDisconnect("Closed ($code: $reason)")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect("Failure: ${t.message}")
            }
        })
    }

    private val reconnectRunnable = Runnable {
        if (!isConnected.get() && !connecting.get()) {
            connect()
        }
    }

    private val notifyDisconnected = Runnable {
        if (!isConnected.get()) {
            updateNotification("Disconnected. Retrying...")
            notifyListeners { it.onConnectionStateChanged(false) }
        }
    }

    private fun handleDisconnect(reason: String) {
        Log.d(TAG, "Disconnected: $reason")
        connecting.set(false)
        val wasConnected = isConnected.getAndSet(false)
        webSocket = null
        if (wasConnected) {
            handler.removeCallbacks(notifyDisconnected)
            handler.postDelayed(notifyDisconnected, 1200)
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (isConnected.get() || connecting.get()) return
        handler.removeCallbacks(reconnectRunnable)
        val delay = (1L shl minOf(reconnectAttempt, 5)) * 1000L // 1s, 2s, 4s, 8s, 16s, 32s
        reconnectAttempt++
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun notifyListeners(action: (RelayListener) -> Unit) {
        handler.post {
            listeners.forEach(action)
        }
    }

    private fun updateNotification(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, com.devdeck.app.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DevDeck Bridge")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DevDeck Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        connecting.set(false)
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(notifyDisconnected)
        webSocket?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "relay_connection"
        private const val NOTIFICATION_ID = 1
    }
}
