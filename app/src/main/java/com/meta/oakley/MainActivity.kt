package com.meta.oakley

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meta.oakley.databinding.ActivityMainBinding
import com.meta.oakley.ble.BLEManager
import com.meta.oakley.llm.GroqApiFactory
import com.meta.oakley.llm.GroqLLMApi
import com.meta.oakley.llm.LLMService
import com.meta.oakley.orchestrator.AIOrchestrator
import com.meta.oakley.orchestrator.PipelineState
import com.meta.oakley.speech.SpeechToTextService
import com.meta.oakley.translation.NoOpTranslationApi
import com.meta.oakley.translation.TranslationService
import com.meta.oakley.tts.TextToSpeechService
import com.meta.oakley.vision.VisionService
import com.meta.oakley.wakeword.WakeWordService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button

    private lateinit var bleManager: BLEManager
    private lateinit var wakeWordService: WakeWordService
    private lateinit var speechToTextService: SpeechToTextService
    private lateinit var llmService: LLMService
    private lateinit var translationService: TranslationService
    private lateinit var visionService: VisionService
    private lateinit var textToSpeechService: TextToSpeechService
    private lateinit var orchestrator: AIOrchestrator

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { !it }) {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        statusText = binding.statusText
        startStopButton = binding.startStopButton

        statusText.text = getString(R.string.status, "Idle")
        requestPermissionsIfNeeded()
        createServices()
        bindCameraPreview()
        observePipelineState()
        startStopButton.setOnClickListener { togglePipeline() }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun createServices() {
        val app = application as MetaOakleyApp
        bleManager = BLEManager()
        val groqApiKey = BuildConfig.GROQ_API_KEY
        val llmApi = if (groqApiKey.isNotEmpty()) {
            GroqLLMApi(GroqApiFactory.create(groqApiKey))
        } else {
            com.meta.oakley.llm.StubLLMApi()
        }
        llmService = LLMService(llmApi)
        translationService = TranslationService(NoOpTranslationApi())
        speechToTextService = SpeechToTextService(this)
        textToSpeechService = TextToSpeechService(this)
        visionService = VisionService(this)

        val picovoiceKey = getPicovoiceAccessKey()
        wakeWordService = WakeWordService(this, accessKey = picovoiceKey)

        orchestrator = AIOrchestrator(
            wakeWordService = wakeWordService,
            speechToTextService = speechToTextService,
            llmService = llmService,
            translationService = translationService,
            textToSpeechService = textToSpeechService,
            scope = app.applicationScope
        )
    }

    private fun getPicovoiceAccessKey(): String {
        return BuildConfig.PICOVOICE_ACCESS_KEY.ifEmpty {
            "placeholder_key" // Add PICOVOICE_ACCESS_KEY to local.properties from https://console.picovoice.ai
        }
    }

    private fun bindCameraPreview() {
        visionService.bindPreview(this, binding.previewView)
    }

    private fun observePipelineState() {
        lifecycleScope.launch {
            orchestrator.pipelineState.collectLatest { state ->
                statusText.text = getString(R.string.status, stateToString(state))
            }
        }
    }

    private fun stateToString(state: PipelineState): String = when (state) {
        is PipelineState.Idle -> "Idle"
        is PipelineState.Listening -> "Listening"
        is PipelineState.Processing -> "Processing"
        is PipelineState.Speaking -> "Speaking"
        is PipelineState.Error -> "Error: ${state.message}"
    }

    private var pipelineRunning = false

    private fun togglePipeline() {
        if (pipelineRunning) {
            orchestrator.stopPipeline()
            wakeWordService.stop()
            speechToTextService.release()
            textToSpeechService.release()
            startStopButton.text = getString(R.string.start_pipeline)
        } else {
            orchestrator.startPipeline()
            startStopButton.text = getString(R.string.stop_pipeline)
        }
        pipelineRunning = !pipelineRunning
    }

    override fun onPause() {
        if (pipelineRunning) {
            orchestrator.stopPipeline()
            wakeWordService.stop()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (pipelineRunning) {
            speechToTextService.release()
            textToSpeechService.release()
        }
        visionService.unbind()
        super.onDestroy()
    }
}
