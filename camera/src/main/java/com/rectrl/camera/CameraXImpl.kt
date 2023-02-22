package com.rectrl.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.Preview.SurfaceProvider
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraXImpl(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val surfaceProvider: SurfaceProvider
) : Camera {
    companion object {
        const val PREVIEW_WIDTH = 1024
        const val PREVIEW_HEIGHT = 768
    }

    private lateinit var cameraProvider: ProcessCameraProvider

    private val previewBuilder: Preview.Builder = Preview.Builder()
    private val analyzerBuilder: ImageAnalysis.Builder = ImageAnalysis.Builder()

    private lateinit var preview: Preview
    private lateinit var analyzer: ImageAnalysis
    private var imageAvailableListener: Camera.OnImageAvailableListener? = null

    private val lifecycleScope = lifecycleOwner.lifecycleScope
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    override fun startCamera() {
        lifecycleScope.launch {
            if (!::preview.isInitialized) {
                preview = previewBuilder.build()
                preview.setSurfaceProvider(surfaceProvider)
            }
            if (!::analyzer.isInitialized) {
                analyzer = analyzerBuilder.build()
                analyzer.setAnalyzer(analyzerExecutor) { image ->
                    val width = image.width
                    val height = image.height
                    val planes = image.planes
                    val nv21 = imageToNv21(planes, width, height)
                    imageAvailableListener?.onImageAvailable(nv21, width, height)
                    image.close()
                }
            }

            cameraProvider = getCameraProvider()
            cameraProvider.unbindAll()

            val hasFrontCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            val hasBackCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

            if (hasFrontCamera && hasBackCamera) {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analyzer
                )
            } else {
                if (hasFrontCamera) {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analyzer
                    )
                }

                if (hasBackCamera) {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analyzer
                    )
                }
            }
        }
    }

    override fun stopCamera() {
        cameraProvider.unbindAll()
        preview.setSurfaceProvider(null)
        analyzer.clearAnalyzer()
        imageAvailableListener = null
    }

    private suspend fun getCameraProvider() = suspendCoroutine { cont ->
        val provider = ProcessCameraProvider.getInstance(context)
        provider.addListener(
            {
                try {
                    val camera = provider.get()
                    cont.resume(camera)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun imageToNv21(
        planes: Array<ImageProxy.PlaneProxy>,
        width: Int,
        height: Int
    ): ByteArray {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()
        val ySize = yBuffer.remaining()
        var position = 0
        val nv21 = ByteArray(ySize + width * height / 2)

        // Add the full y buffer to the array. If rowStride > 1, some padding may be skipped.
        for (row in 0 until height) {
            yBuffer[nv21, position, width]
            position += width
            yBuffer.position(
                ySize.coerceAtMost(yBuffer.position() - width + yPlane.rowStride)
            )
        }
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        // Interleave the u and v frames, filling up the rest of the buffer. Use two line buffers to
        // perform faster bulk gets from the byte buffers.
        val vLineBuffer = ByteArray(vRowStride)
        val uLineBuffer = ByteArray(uRowStride)
        for (row in 0 until chromaHeight) {
            vBuffer[vLineBuffer, 0, vRowStride.coerceAtMost(vBuffer.remaining())]
            uBuffer[uLineBuffer, 0, uRowStride.coerceAtMost(uBuffer.remaining())]
            var vLineBufferPosition = 0
            var uLineBufferPosition = 0
            for (col in 0 until chromaWidth) {
                nv21[position++] = vLineBuffer[vLineBufferPosition]
                nv21[position++] = uLineBuffer[uLineBufferPosition]
                vLineBufferPosition += vPixelStride
                uLineBufferPosition += uPixelStride
            }
        }
        return nv21
    }

    override fun setImageAvailableListener(listener: Camera.OnImageAvailableListener) {
        imageAvailableListener = listener
    }
}
