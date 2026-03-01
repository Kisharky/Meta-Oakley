package com.meta.oakley.wakeword

import android.content.Context
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineManagerErrorCallback
import ai.picovoice.porcupine.Porcupine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Event emitted when the wake word is detected.
 */
data class WakeWordEvent(
    val keywordIndex: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Porcupine-based wake word detection. Emits [WakeWordEvent] when the configured keyword is detected.
 * Run [start] to begin listening; [stop] to release. Consume [wakeWordEvents] in the pipeline.
 *
 * Requires RECORD_AUDIO. Use a Picovoice access key and optionally a .ppn file in assets.
 */
class WakeWordService(
    private val context: Context,
    private val accessKey: String,
    private val keywordPath: String? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val eventChannel = Channel<WakeWordEvent>(Channel.UNLIMITED)
    private var porcupineManager: PorcupineManager? = null

    val wakeWordEvents: Flow<WakeWordEvent> = eventChannel.receiveAsFlow()

    private val detectionCallback = PorcupineManagerCallback { keywordIndex ->
        scope.launch {
            eventChannel.trySend(WakeWordEvent(keywordIndex = keywordIndex))
        }
    }

    private val errorCallback = PorcupineManagerErrorCallback { error ->
        scope.launch {
            // Optionally emit an error event; for now we only emit detections.
        }
    }

    /**
     * Starts wake word detection. Uses built-in keyword PORCUPINE if [keywordPath] is null.
     */
    @Throws(Exception::class)
    fun start() {
        val builder = PorcupineManager.Builder()
            .setAccessKey(accessKey)
            .setErrorCallback(errorCallback)
        if (keywordPath != null) {
            builder.setKeywordPath(keywordPath)
        } else {
            builder.setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
        }
        porcupineManager = builder.build(context, detectionCallback)
        porcupineManager?.start()
    }

    /**
     * Stops listening and releases Porcupine resources.
     */
    fun stop() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (_: Exception) { }
        porcupineManager = null
    }
}
