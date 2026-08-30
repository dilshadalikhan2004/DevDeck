package com.devdeck.app.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class OnDeviceTts(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit
) {
    private val ready = AtomicBoolean(false)
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS && engine != null
            if (!ok) {
                ready.set(false)
                onReadyChanged(false)
                Log.w("DevDeck", "On-device TTS engine is not available")
                return@TextToSpeech
            }
            val result = engine?.setLanguage(Locale.US)
            val langOk = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            ready.set(langOk)
            onReadyChanged(langOk)
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeakingChanged(true)
                }

                override fun onDone(utteranceId: String?) {
                    onSpeakingChanged(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onSpeakingChanged(false)
                }
            })
        }
    }

    fun isAvailable(): Boolean = ready.get()

    fun speak(text: String) {
        val tts = engine
        if (!ready.get() || tts == null || text.isBlank()) return
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "devdeck-voice")
    }

    fun stop() {
        try {
            engine?.stop()
        } catch (_: Throwable) {
        }
        onSpeakingChanged(false)
    }

    fun shutdown() {
        stop()
        try {
            engine?.shutdown()
        } catch (_: Throwable) {
        }
        engine = null
        ready.set(false)
    }
}
