package com.atlas.agent.core.voice

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Speech-to-text for the composer mic button. Must be created and driven on the main thread. */
class VoiceInput(private val ctx: Context) {

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data class Partial(val text: String) : State
        data class Final(val text: String) : State
        data class Failure(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)

    fun start() {
        if (!available()) {
            state.value = State.Failure("Speech recognition is not available on this device.")
            return
        }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                state.value = State.Listening
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "didn't catch that"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission denied"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network error"
                    else -> "recognition error ($error)"
                }
                state.value = State.Failure(msg)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                state.value = if (text.isBlank()) State.Failure("no speech detected") else State.Final(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) state.value = State.Partial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(intent) }
            .onFailure { state.value = State.Failure(it.message ?: "could not start listening") }
    }

    fun stop() {
        runCatching {
            recognizer?.stopListening()
            recognizer?.destroy()
        }
        recognizer = null
    }

    fun reset() {
        state.value = State.Idle
    }
}

/** Text-to-speech, initialised once per process. */
class Tts(private val app: Application) {

    private var engine: TextToSpeech? = null

    @Volatile
    private var ready = false

    init {
        runCatching {
            engine = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) runCatching { engine?.language = Locale.getDefault() }
            }
        }
    }

    fun available(): Boolean = ready

    fun speak(text: String) {
        if (!ready) return
        runCatching {
            engine?.speak(text.take(3000), TextToSpeech.QUEUE_FLUSH, null, "atlas-${System.currentTimeMillis()}")
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        runCatching { engine?.shutdown() }
    }
}
