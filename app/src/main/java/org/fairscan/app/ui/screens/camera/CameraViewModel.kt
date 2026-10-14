/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.fairscan.app.ui.screens.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.AppContainer
import org.fairscan.app.domain.CapturedPage
import org.fairscan.app.platform.extractDocumentFromBitmap
import org.fairscan.imageprocessing.ImageSize
import org.fairscan.imageprocessing.Mode
import org.fairscan.imageprocessing.OpticalMeasures
import org.fairscan.imageprocessing.detectDocumentQuad
import java.util.concurrent.CancellationException

sealed interface CameraEvent {
    data class ImageCaptured(val page: CapturedPage) : CameraEvent
}

class CameraViewModel(appContainer: AppContainer): ViewModel() {

    private val imageSegmentationService = appContainer.imageSegmentationService
    private val settingsRepository = appContainer.settingsRepository
    private val imageLoader = appContainer.imageLoader
    private val logger = appContainer.logger

    private val _events = MutableSharedFlow<CameraEvent>()
    val events = _events.asSharedFlow()

    private var _liveAnalysisState = MutableStateFlow(LiveAnalysisState())
    val liveAnalysisState: StateFlow<LiveAnalysisState> = _liveAnalysisState.asStateFlow()

    private var quadStabilizer = QuadStabilizer()

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    private var importJob: Job? = null

    private val _isTorchEnabled = MutableStateFlow(false)
    val isTorchEnabled: StateFlow<Boolean> = _isTorchEnabled

    private val _volumeKeyEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumeKeyEvent = _volumeKeyEvent.asSharedFlow()

    // --- AutoFair-Scan: captura automática ---
    private val autoCaptureController = AutoCaptureController()

    // Desligado por padrão; o usuário liga pelo botão AUTO na tela da câmera.
    private val _autoCaptureEnabled = MutableStateFlow(false)
    val autoCaptureEnabled: StateFlow<Boolean> = _autoCaptureEnabled.asStateFlow()

    fun setAutoCaptureEnabled(enabled: Boolean) {
        _autoCaptureEnabled.value = enabled
        autoCaptureController.reset()
        _autoProgress.value = 0f
    }

    // Evento da captura automática: a tela foca e só depois captura.
    private val _autoCaptureEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autoCaptureEvent = _autoCaptureEvent.asSharedFlow()

    // Progresso da contagem (0..1) para o anel na tela da câmera.
    private val _autoProgress = MutableStateFlow(0f)
    val autoProgress: StateFlow<Float> = _autoProgress.asStateFlow()

    // Ajustes vindos das Configurações (SharedPreferences).
    private val _autoConfig = MutableStateFlow(AutoCaptureConfig())
    val autoConfig: StateFlow<AutoCaptureConfig> = _autoConfig.asStateFlow()

    // Valores de movimento para o modo debug.
    private val _autoDebug = MutableStateFlow("")
    val autoDebug: StateFlow<String> = _autoDebug.asStateFlow()

    /** Lê os ajustes salvos. Chamado sempre que a tela da câmera abre. */
    fun loadAutoConfig(context: Context) {
        val config = AutoCaptureSettings(context).load()
        _autoConfig.value = config
        autoCaptureController.quietMs = config.quietMs
        autoCaptureController.motionTol = config.motionTol
        autoCaptureController.changeTol = config.changeTol
    }
    // ------------------------------------------

    fun onVolumeKeyPressed() {
        _volumeKeyEvent.tryEmit(Unit)
    }

    fun resetLiveAnalysis() {
        quadStabilizer = QuadStabilizer()
        _liveAnalysisState.value = LiveAnalysisState()
    }

    fun onCapturePressed(frozenImage: Bitmap) {
        _autoProgress.value = 0f
        _captureState.value = CaptureState.Capturing(frozenImage)
        resetLiveAnalysis()
    }

    private fun onCaptureProcessed(captured: CapturedPage?) {
        val current = _captureState.value
        _captureState.value = when {
            current is CaptureState.Capturing && captured != null ->
                CaptureState.CapturePreview(current.frozenImage, captured)
            current is CaptureState.Capturing ->
                CaptureState.CaptureError(current.frozenImage)
            else -> CaptureState.Idle
        }
    }

    fun liveAnalysis(imageProxy: ImageProxy) {
        if (_captureState.value !is CaptureState.Idle || _importState.value !is ImportState.Idle) {
            imageProxy.close()
            return
        }
        viewModelScope.launch {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val (frame, result) = withContext(Dispatchers.IO) {
                val bitmap = imageProxy.toBitmap()
                bitmap to imageSegmentationService.runSegmentationAndReturn(bitmap)
            }
            result?.let {
                val segmentation = result.segmentation
                val maskSize = segmentation.maskSize()
                val originalSize = ImageSize(imageProxy.width, imageProxy.height)
                val rawQuad = withContext(Dispatchers.Default) {
                    detectDocumentQuad(segmentation, originalSize, Mode.LIVE_ANALYSIS)
                        ?.rotate90(rotationDegrees / 90, maskSize)
                }
                val binaryMaskProvider = { ->
                    var binaryMask: Bitmap = segmentation.toBinaryMask()
                    if (rotationDegrees != 0) {
                        binaryMask = rotateBitmap(binaryMask, rotationDegrees.toFloat())
                    }
                    binaryMask
                }
                val stableQuad = quadStabilizer.update(rawQuad)
                _liveAnalysisState.value = LiveAnalysisState(
                    inferenceTime = result.inferenceTime,
                    binaryMaskProvider = binaryMaskProvider,
                    maskSize = maskSize,
                    stableQuad = stableQuad,
                )

                // --- AutoFair-Scan: captura automática ---
                if (_autoCaptureEnabled.value) {
                    val thumb = withContext(Dispatchers.Default) { frame.toThumbnail() }
                    val shouldCapture = autoCaptureController.onFrame(
                        SystemClock.elapsedRealtime(), stableQuad, rawQuad, thumb
                    )
                    _autoDebug.value = String.format(
                        java.util.Locale.US, "mov %.3f | troca %.3f",
                        autoCaptureController.lastMotion, autoCaptureController.lastChange
                    )
                    _autoProgress.value = autoCaptureController.progress
                    if (shouldCapture) {
                        // A tela foca no centro e então chama o mesmo onCapture do botão.
                        _autoCaptureEvent.tryEmit(Unit)
                    }
                }
                // ------------------------------------------
            }
            imageProxy.close()
        }
    }

    fun onImageCaptured(imageProxy: ImageProxy?, opticalMeasures: OpticalMeasures?) {
        if (imageProxy != null) {
            viewModelScope.launch {
                try {
                    val source = imageProxy.toBitmap()
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val page =
                        processCapturedImage(source, rotationDegrees, opticalMeasures, Mode.CAPTURE)
                    imageProxy.close()
                    onCaptureProcessed(page)
                } catch (e: RuntimeException) {
                    logger.e("Camera", "Failed to process captured image", e)
                    onCaptureProcessed(null)
                }
            }
        } else {
            onCaptureProcessed(null)
        }
    }

    private suspend fun processCapturedImage(
        source: Bitmap,
        rotationDegrees: Int,
        opticalMeasures: OpticalMeasures?,
        mode: Mode,
    ): CapturedPage = withContext(Dispatchers.IO) {
        val segmentation = imageSegmentationService.runSegmentationAndReturn(source)
        val mask = segmentation?.segmentation
        val originalSize = ImageSize(source.width, source.height)
        val quad = mask?.let { detectDocumentQuad(mask, originalSize, mode) }
        val defaultColorMode = settingsRepository.defaultColorMode.first()
        val result = extractDocumentFromBitmap(
            source, quad, rotationDegrees, mask, viewModelScope, defaultColorMode, opticalMeasures)
        return@withContext result
    }

    fun addProcessedImage() {
        val current = _captureState.value
        if (current is CaptureState.CapturePreview) {
            viewModelScope.launch {
                _events.emit(CameraEvent.ImageCaptured(current.capturedPage))
            }
        }
        _captureState.value = CaptureState.Idle
    }

    fun afterCaptureError() {
        // Falhou: volta a contar para tentar de novo com a página parada.
        autoCaptureController.onCaptureFailed()
        _autoProgress.value = 0f
        _captureState.value = CaptureState.Idle
    }

    fun logError(message:String, throwable: Throwable) {
        viewModelScope.launch {
            logger.e("Camera", message, throwable)
        }
    }

    fun setTorchEnabled(enabled: Boolean) {
        _isTorchEnabled.value = enabled
    }

    fun importPhotos(uris: List<Uri>) {
        importJob?.cancel()
        if (uris.isEmpty()) {
            _importState.value = ImportState.Idle
            return
        }
        importJob = viewModelScope.launch {
            _importState.value = ImportState.Importing(0, uris.size)
            uris.forEachIndexed { index, uri ->
                ensureActive()
                try {
                    val photoToImport = imageLoader.load(uri)
                    ensureActive()
                    val page = processCapturedImage(photoToImport, 0, null, Mode.IMPORT)
                    ensureActive()
                    _events.emit(CameraEvent.ImageCaptured(page))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("Import", "Failed to import image: $uri", e)
                }
                _importState.value = ImportState.Importing(index + 1, uris.size)
            }
            _importState.value = ImportState.Idle
        }
    }

    fun onImportClicked() {
        _importState.value = ImportState.Selecting
        resetLiveAnalysis()
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportState.Idle
    }
}

sealed class CaptureState {
    open val frozenImage: Bitmap? = null

    object Idle : CaptureState()

    data class Capturing(override val frozenImage: Bitmap) : CaptureState()

    data class CaptureError(override val frozenImage: Bitmap) : CaptureState()

    data class CapturePreview(
        override val frozenImage: Bitmap,
        val capturedPage: CapturedPage,
    ) : CaptureState()
}

fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(angle)
    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true)
}

/** Miniatura 32x32 em tons de cinza (0..1), usada para detectar movimento e troca de página. */
private fun Bitmap.toThumbnail(size: Int = 32): FloatArray {
    val small = Bitmap.createScaledBitmap(this, size, size, true)
    val pixels = IntArray(size * size)
    small.getPixels(pixels, 0, size, 0, 0, size, size)
    return FloatArray(pixels.size) { i ->
        val c = pixels[i]
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    }
}
