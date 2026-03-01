package com.meta.oakley.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * TTS lifecycle events for the pipeline.
 */
sealed class TTSEvent {
    data object Started : TTSEvent()
    data class Progress(val utteranceId: String?, val start: Int, val end: Int) : TTSEvent()
    data object Completed : TTSEvent()
    data class Error(val message: String?) : TTSEvent()
}

private const val UTTERANCE_DONE = "meta_oakley_tts_done"

/**
 * Converts text to speech and plays to the default audio output (e.g. Bluetooth when glasses are paired).
 * [speak] suspends until playback completes or is cancelled; [speakEvents] emits status.
 */
class TextToSpeechService(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
) {
    private val eventChannel = Channel<TTSEvent>(Channel.UNLIMITED)
    val speakEvents: Flow<TTSEvent> = eventChannel.receiveAsFlow()

    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Speaks [text] and suspends until done or cancelled. TTS is initialized on first use.
     */
    suspend fun speak(text: String) {
        val engine = waitForTts() ?: return
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                scope.launch { eventChannel.trySend(TTSEvent.Started) }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        scope.launch { eventChannel.trySend(TTSEvent.Progress(utteranceId, 0, 0)) }
                    }
                    override fun onDone(utteranceId: String?) {
                        scope.launch { eventChannel.trySend(TTSEvent.Completed) }
                        cont.resume(Unit)
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        scope.launch { eventChannel.trySend(TTSEvent.Error("TTS error $errorCode")) }
                        cont.resume(Unit)
                    }
                })
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_DONE)
                if (result != TextToSpeech.SUCCESS) {
                    scope.launch { eventChannel.trySend(TTSEvent.Error("speak() failed")) }
                    cont.resume(Unit)
                }
            }
        }
    }

    private suspend fun waitForTts(): TextToSpeech? = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val current = tts
            if (current != null) {
                cont.resume(current)
                return@post
            }
            TextToSpeech(context) { initStatus ->
                if (initStatus == TextToSpeech.SUCCESS) {
                    tts = this
                    this.language = Locale.US
                    cont.resume(this)
                } else {
                    scope.launch { eventChannel.trySend(TTSEvent.Error("TTS init failed")) }
                    cont.resume(null)
                }
            }
        }
    }

    fun stop() {
        mainHandler.post {
            tts?.stop()
        }
    }

    fun release() {
        mainHandler.post {
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }
}
