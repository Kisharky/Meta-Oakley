package com.meta.oakley.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Result of a speech recognition turn (partial or final).
 */
sealed class SpeechRecognitionResult {
    data class Partial(val text: String) : SpeechRecognitionResult()
    data class Final(val text: String) : SpeechRecognitionResult()
    data class Error(val errorCode: Int, val message: String?) : SpeechRecognitionResult()
}

/**
 * Speech-to-text via Android [SpeechRecognizer]. Emits partial and final results on [recognitionResults].
 *
 * **Not truly continuous:** SpeechRecognizer stops after a pause and must be restarted. The orchestrator
 * restarts listening each turn and handles ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT so the pipeline does not silently die.
 */
class SpeechToTextService(
    private val context: Context,
    private val language: String = "en-US",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
) {
    private val resultChannel = Channel<SpeechRecognitionResult>(Channel.UNLIMITED)
    val recognitionResults: Flow<SpeechRecognitionResult> = resultChannel.receiveAsFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getOrCreateRecognizer(): SpeechRecognizer? {
        if (SpeechRecognizer.isRecognitionAvailable(context).not()) return null
        return speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            speechRecognizer = it
            it.setRecognitionListener(recognitionListener)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            scope.launch {
                resultChannel.trySend(SpeechRecognitionResult.Error(error, errorCodeToString(error)))
            }
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) {
                scope.launch {
                    resultChannel.trySend(SpeechRecognitionResult.Final(text))
                }
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) {
                scope.launch {
                    resultChannel.trySend(SpeechRecognitionResult.Partial(text))
                }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun startListening() {
        mainHandler.post {
            getOrCreateRecognizer()?.let { recognizer ->
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer.startListening(intent)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            speechRecognizer?.stopListening()
        }
    }

    fun release() {
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    private fun errorCodeToString(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        else -> "Unknown error $error"
    }
}
