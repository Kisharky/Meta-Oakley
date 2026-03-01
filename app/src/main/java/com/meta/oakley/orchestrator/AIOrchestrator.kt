package com.meta.oakley.orchestrator

import com.meta.oakley.llm.ChatMessage
import com.meta.oakley.llm.LLMService
import com.meta.oakley.speech.SpeechRecognitionResult
import com.meta.oakley.speech.SpeechToTextService
import com.meta.oakley.translation.TranslationService
import com.meta.oakley.tts.TextToSpeechService
import com.meta.oakley.wakeword.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pipeline state exposed to the UI.
 */
sealed class PipelineState {
    data object Idle : PipelineState()
    data object Listening : PipelineState()
    data object Processing : PipelineState()
    data object Speaking : PipelineState()
    data class Error(val message: String) : PipelineState()
}

/**
 * Coordinates wake word → STT → LLM → optional translation → TTS.
 *
 * **Mic exclusivity:** Porcupine and SpeechRecognizer cannot run at the same time. Pipeline:
 * 1. Idle → wake word active only.
 * 2. Wake word detected → stop wake engine → start STT.
 * 3. After STT/LLM/TTS → restart wake word (loop).
 *
 * **SpeechRecognizer:** Not truly continuous; stops after pause. Orchestrator restarts listening
 * each turn and treats ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT as recoverable (return to idle, restart wake word).
 */
class AIOrchestrator(
    private val wakeWordService: WakeWordService,
    private val speechToTextService: SpeechToTextService,
    private val llmService: LLMService,
    private val translationService: TranslationService,
    private val textToSpeechService: TextToSpeechService,
    private val scope: CoroutineScope,
    private val targetTranslationLang: String? = null,
    private val systemPrompt: String = "You are a helpful wearable assistant. Reply concisely."
) {
    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    private var pipelineJob: Job? = null
    private val conversationHistory = mutableListOf<ChatMessage>()

    fun startPipeline() {
        if (pipelineJob?.isActive == true) return
        pipelineJob = scope.launch {
            runWakeWordLoop()
        }
    }

    fun stopPipeline() {
        pipelineJob?.cancel()
        pipelineJob = null
        speechToTextService.stopListening()
        textToSpeechService.stop()
        _pipelineState.value = PipelineState.Idle
    }

    private suspend fun runWakeWordLoop() {
        while (isActive) {
            try {
                wakeWordService.start()
            } catch (e: Exception) {
                _pipelineState.value = PipelineState.Error("Wake word: ${e.message ?: "check PICOVOICE_ACCESS_KEY"}")
                return
            }
            val wakeEventChannel = Channel<Unit>(Channel.RENDEZVOUS)
            val collectJob = launch {
                wakeWordService.wakeWordEvents()
                    .catch { e -> _pipelineState.value = PipelineState.Error(e.message ?: "Wake word error") }
                    .onEach { wakeEventChannel.trySend(Unit) }
                    .launchIn(this)
            }
            val gotWake = runCatching { wakeEventChannel.receive() }.getOrNull()
            collectJob.cancel()
            wakeWordService.stop()
            if (gotWake == null) break
            _pipelineState.value = PipelineState.Listening
            speechToTextService.startListening()
            runSttToLlmToTts()
            _pipelineState.value = PipelineState.Idle
        }
    }

    private suspend fun runSttToLlmToTts() {
        val finalChannel = Channel<String?>(Channel.RENDEZVOUS)
        val resultJob = launch {
            speechToTextService.recognitionResults()
                .catch { e ->
                    _pipelineState.value = PipelineState.Error(e.message ?: "STT error")
                    finalChannel.trySend(null)
                }
                .onEach { result ->
                    when (result) {
                        is SpeechRecognitionResult.Final -> {
                            speechToTextService.stopListening()
                            finalChannel.trySend(result.text)
                        }
                        is SpeechRecognitionResult.Partial -> { }
                        is SpeechRecognitionResult.Error -> {
                            val recoverable = result.errorCode == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                                result.errorCode == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            if (recoverable) {
                                speechToTextService.stopListening()
                                finalChannel.trySend(null)
                            } else {
                                _pipelineState.value = PipelineState.Error(result.message ?: "Recognition error")
                                finalChannel.trySend(null)
                            }
                        }
                    }
                }
                .launchIn(this)
        }
        val userText = withTimeoutOrNull(30_000L) { finalChannel.receive() }
        resultJob.cancel()
        if (userText == null || userText.isBlank()) {
            return
        }
        conversationHistory.add(ChatMessage(role = "user", content = userText))

        _pipelineState.value = PipelineState.Processing
        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt)
        ) + conversationHistory.map { it }
        val llmResult = llmService.respond(messages)
        val assistantText = llmResult.getOrElse {
            _pipelineState.value = PipelineState.Error(it.message ?: "LLM error")
            return
        }
        conversationHistory.add(ChatMessage(role = "assistant", content = assistantText))

        val toSpeak = if (targetTranslationLang != null) {
            translationService.translateToTarget(assistantText, targetTranslationLang).getOrElse { assistantText }
        } else {
            assistantText
        }

        _pipelineState.value = PipelineState.Speaking
        textToSpeechService.speak(toSpeak)
        _pipelineState.value = PipelineState.Idle
    }
}
