package com.devdeck.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.atomic.AtomicBoolean

interface SessionCallbacks {
    fun onPartial(text: String)
    fun onFinished(text: String)
    fun onNoSpeech()
    fun onFailed(message: String)
}

class VoskSpeechSession(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var lastHeardAt = 0L
    private var lastPartial = ""
    private var silenceLoop: Runnable? = null
    private var emptyTimeout: Runnable? = null
    private var maxTimeout: Runnable? = null

    fun prepare(): Result<Unit> {
        return try {
            if (model == null) {
                val dir = VoskModelStore.installFromAssets(context)
                model = Model(dir.absolutePath)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e("DevDeck", "Vosk model load failed: ${t.message}")
            Result.failure(
                IllegalStateException(
                    t.message
                        ?: "Voice model failed to load. Rebuild the app so the English Vosk model is packaged."
                )
            )
        }
    }

    fun start(callbacks: SessionCallbacks) {
        if (!active.compareAndSet(false, true)) return
        val loaded = model
        if (loaded == null) {
            active.set(false)
            callbacks.onFailed("Voice model is not loaded.")
            return
        }
        lastHeardAt = 0L
        lastPartial = ""
        try {
            val recognizer = Recognizer(loaded, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = parsePartial(hypothesis)
                    if (text.isNotBlank()) {
                        lastPartial = text
                        lastHeardAt = System.currentTimeMillis()
                        main.post { callbacks.onPartial(text) }
                    }
                }

                override fun onResult(hypothesis: String?) {
                    val text = parseFinal(hypothesis)
                    if (text.isNotBlank()) {
                        lastPartial = text
                        lastHeardAt = System.currentTimeMillis()
                        main.post { callbacks.onPartial(text) }
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = parseFinal(hypothesis).ifBlank { lastPartial }
                    complete(text, callbacks)
                }

                override fun onError(exception: Exception?) {
                    fail(exception?.message ?: "Speech recognition failed.", callbacks)
                }

                override fun onTimeout() {
                    val text = lastPartial
                    if (text.isBlank()) {
                        failQuietNoSpeech(callbacks)
                    } else {
                        complete(text, callbacks)
                    }
                }
            })
            silenceLoop = object : Runnable {
                override fun run() {
                    if (!active.get()) return
                    if (lastHeardAt > 0L && System.currentTimeMillis() - lastHeardAt >= SILENCE_MS) {
                        stopListening()
                        return
                    }
                    main.postDelayed(this, 250)
                }
            }
            emptyTimeout = Runnable {
                if (active.get() && lastHeardAt == 0L) stopListening()
            }
            maxTimeout = Runnable {
                if (active.get()) stopListening()
            }
            main.post(silenceLoop!!)
            main.postDelayed(emptyTimeout!!, EMPTY_TIMEOUT_MS)
            main.postDelayed(maxTimeout!!, MAX_LISTEN_MS)
        } catch (t: Throwable) {
            active.set(false)
            callbacks.onFailed(t.message ?: "Could not start the microphone.")
        }
    }

    fun stopListening() {
        try {
            speechService?.stop()
        } catch (t: Throwable) {
            Log.w("DevDeck", "Vosk stop failed: ${t.message}")
        }
    }

    fun cancel() {
        clearTimers()
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Throwable) {
        }
        speechService = null
        active.set(false)
    }

    fun release() {
        cancel()
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        model = null
    }

    private fun complete(text: String, callbacks: SessionCallbacks) {
        if (!active.compareAndSet(true, false)) return
        teardownService()
        main.post {
            if (text.isBlank()) callbacks.onNoSpeech() else callbacks.onFinished(text)
        }
    }

    private fun failQuietNoSpeech(callbacks: SessionCallbacks) {
        if (!active.compareAndSet(true, false)) return
        teardownService()
        main.post { callbacks.onNoSpeech() }
    }

    private fun fail(message: String, callbacks: SessionCallbacks) {
        if (!active.compareAndSet(true, false)) return
        teardownService()
        main.post { callbacks.onFailed(message) }
    }

    private fun teardownService() {
        clearTimers()
        try {
            speechService?.shutdown()
        } catch (_: Throwable) {
        }
        speechService = null
    }

    private fun clearTimers() {
        silenceLoop?.let { main.removeCallbacks(it) }
        emptyTimeout?.let { main.removeCallbacks(it) }
        maxTimeout?.let { main.removeCallbacks(it) }
    }

    companion object {
        private const val SAMPLE_RATE = 16000.0f
        private const val SILENCE_MS = 1800L
        private const val EMPTY_TIMEOUT_MS = 4000L
        private const val MAX_LISTEN_MS = 12000L

        fun parsePartial(hypothesis: String?): String {
            if (hypothesis.isNullOrBlank()) return ""
            return try {
                JSONObject(hypothesis).optString("partial").trim()
            } catch (_: Exception) {
                ""
            }
        }

        fun parseFinal(hypothesis: String?): String {
            if (hypothesis.isNullOrBlank()) return ""
            return try {
                JSONObject(hypothesis).optString("text").trim()
            } catch (_: Exception) {
                hypothesis.trim()
            }
        }
    }
}
