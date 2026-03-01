package com.meta.oakley.vision

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Placeholder result for a single frame; extend with labels/embeddings when adding AI vision.
 */
data class VisionAnalysisResult(
    val timestamp: Long = System.currentTimeMillis(),
    val width: Int = 0,
    val height: Int = 0
)

/**
 * **Uses the phone's camera (CameraX), not the Ray-Ban Meta glasses camera.**
 * The glasses camera is not exposed for third-party streaming. This service provides the phone's
 * CameraX preview and optional frame analysis for wearable UI or as a secondary view.
 *
 * Call [bindPreview] with a [LifecycleOwner] and [PreviewView]; then [startAnalysis]/[stopAnalysis].
 */
class VisionService(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val resultChannel = Channel<VisionAnalysisResult>(Channel.UNLIMITED)
    val analysisResults: Flow<VisionAnalysisResult> = resultChannel.receiveAsFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var analysisActive = false
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundPreviewView: androidx.camera.view.PreviewView? = null

    fun getPreviewUseCase(): Preview = Preview.Builder().build()

    /**
     * Binds preview and optional analysis to the lifecycle and surface.
     * Call from Main thread; binding runs asynchronously to avoid blocking.
     */
    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView
    ) {
        boundLifecycleOwner = lifecycleOwner
        boundPreviewView = previewView
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = future.get()
                cameraProvider = provider
                doBind(provider, lifecycleOwner, previewView)
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun doBind(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView
    ) {
        provider.unbindAll()
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        if (analysisActive) {
            val analysis = buildImageAnalysis()
            imageAnalysis = analysis
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } else {
            provider.bindToLifecycle(lifecycleOwner, selector, preview)
        }
    }

    fun startAnalysis() {
        analysisActive = true
        val owner = boundLifecycleOwner
        val view = boundPreviewView
        val provider = cameraProvider
        if (owner != null && view != null && provider != null) {
            doBind(provider, owner, view)
        } else {
            imageAnalysis = buildImageAnalysis()
        }
    }

    fun stopAnalysis() {
        analysisActive = false
        imageAnalysis?.let { cameraProvider?.unbind(it) }
        imageAnalysis = null
        val owner = boundLifecycleOwner
        val view = boundPreviewView
        val provider = cameraProvider
        if (owner != null && view != null && provider != null) {
            doBind(provider, owner, view)
        }
    }

    private fun buildImageAnalysis(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { imageProxy ->
                    val result = VisionAnalysisResult(
                        timestamp = System.currentTimeMillis(),
                        width = imageProxy.width,
                        height = imageProxy.height
                    )
                    scope.launch {
                        resultChannel.trySend(result)
                    }
                    imageProxy.close()
                }
            }
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        imageAnalysis = null
        cameraProvider = null
        boundLifecycleOwner = null
        boundPreviewView = null
        analysisExecutor.shutdown()
    }
}
