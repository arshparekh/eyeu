package com.eyeu.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper around Android's built-in on-device-capable SpeechRecognizer (STT) and
 * TextToSpeech (spoken feedback) so the overlay doesn't deal with the callback soup directly.
 */
class VoiceIO(context: Context) {

    companion object {
        private const val TAG = "EyeU/VoiceIO"
    }

    interface Listener {
        fun onPartial(text: String) {}
        fun onFinal(text: String)
        fun onListeningStateChanged(listening: Boolean) {}
        fun onError(message: String) {}
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun init() {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
        }
    }

    fun startListening(listener: Listener) {
        stopListening()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onError("Speech recognition isn't available on this device.")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listener.onListeningStateChanged(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listener.onListeningStateChanged(false) }

            override fun onError(error: Int) {
                listener.onListeningStateChanged(false)
                listener.onError(describeError(error))
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) listener.onFinal(text) else listener.onError("Didn't catch that.")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) listener.onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        r.startListening(intent)
    }

    fun stopListening() {
        recognizer?.let {
            it.stopListening()
            it.destroy()
        }
        recognizer = null
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready, skipping: $text")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "eyeu_utt_${System.currentTimeMillis()}")
    }

    fun release() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during speech recognition."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy, try again."
        else -> "Speech recognition error ($code)."
    }
}
