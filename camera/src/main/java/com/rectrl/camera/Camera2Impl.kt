package com.rectrl.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import kotlinx.coroutines.*
import java.lang.Long.signum
import java.util.*
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.system.measureTimeMillis

class Camera2Impl(
    private val context: Context, private val cameraId: String, private val textureView: TextureView
) : Camera {
    companion object {
        const val PREVIEW_WIDTH = 1024
        const val PREVIEW_HEIGHT = 768
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val lifecycleScope = MainScope()

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    private val cameraThread = HandlerThread("CameraThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val imageThread = HandlerThread("ImageThread").also { it.start() }
    private val imageHandler = Handler(imageThread.looper)

    private var previewSize = Size(PREVIEW_WIDTH, PREVIEW_HEIGHT)

    private var imageAvailableListener: Camera.OnImageAvailableListener? = null

    override fun startCamera() {
        lifecycleScope.launch {
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

            val streamConfigurationMap =
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            Log.e(
                "Camera", "cameraId=${cameraId}, streamConfigurationMap=${streamConfigurationMap}"
            )

            var aspectRatio = Size(PREVIEW_WIDTH, PREVIEW_HEIGHT)

            if (streamConfigurationMap != null) {
                val outputSizes = streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
                for (size in outputSizes) {
                    Log.e("Camera", "getOutputSizes=$size")
                }
                aspectRatio = Collections.max(
                    listOf(*outputSizes), CompareSizeByArea()
                )
                Log.e("Camera", "aspectRatio=$aspectRatio")
            }

            val imageReader = ImageReader.newInstance(
                aspectRatio.width, aspectRatio.height, ImageFormat.YUV_420_888, 3
            )

            imageReader.setOnImageAvailableListener({ reader ->
                val totalTime = measureTimeMillis {
                    val image = reader.acquireNextImage()
                    val planes = image.planes
                    val width = image.width
                    val height = image.height
                    if (image != null) {
                        val nv21 = imageToNv21(planes, width, height)
                        imageAvailableListener?.onImageAvailable(nv21, width, height)
                        image.close()
                    }
                }
                Log.e("Camera", "cameraId=${cameraId}, totalTime=$totalTime")
            }, imageHandler)

            val sensorOrientation =
                cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            val lensFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            val displayRotation = displayManager.displays.first().rotation

            Log.e(
                "Camera",
                "sensorOrientation=$sensorOrientation, lensFacing=$lensFacing, displayRotation=$displayRotation"
            )

            val surfaceTexture = textureView.surfaceTexture
                ?: throw IllegalStateException("SurfaceTexture can't be null.")

            if (streamConfigurationMap != null) {
                val outputSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
                for (size in outputSizes) {
                    Log.e("Camera", "getOutputSizes=$size")
                }
                previewSize = getOptimalSize(
                    listOf(*outputSizes), textureView.width, textureView.height, aspectRatio
                )
                Log.e("Camera", "previewSize=$previewSize")
            }

            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(surfaceTexture)

            configureTransform(textureView, lensFacing)

            val cameraDevice = createCameraDevice(cameraId)

            val captureSession =
                createCaptureSession(cameraDevice, listOf(previewSurface, imageReader.surface))
            val captureRequest =
                createCaptureRequest(cameraDevice, listOf(previewSurface, imageReader.surface))
            captureSession.setRepeatingRequest(captureRequest, null, cameraHandler)
        }
    }

    override fun stopCamera() {
        try {
            cameraThread.quitSafely()
            cameraThread.interrupt()
            imageThread.quitSafely()
            imageThread.interrupt()
            if (lifecycleScope.isActive) {
                lifecycleScope.cancel("stopCamera")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setImageAvailableListener(listener: Camera.OnImageAvailableListener) {
        imageAvailableListener = listener
    }

    private fun getOptimalSize(
        outputSizes: List<Size>, textureWidth: Int, textureHeight: Int, aspectRatio: Size
    ): Size {
        val width = aspectRatio.width
        val height = aspectRatio.height

        val sizeList = outputSizes.filter {
            it.height == it.width * width / height && it.width >= textureWidth && it.height >= textureHeight
        }

        return when {
            sizeList.isNotEmpty() -> {
                Collections.min(sizeList, CompareSizeByArea())
            }
            else -> {
                outputSizes.first()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun createCameraDevice(cameraId: String) = suspendCoroutine { cont ->
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) = cont.resume(camera)

            override fun onDisconnected(camera: CameraDevice) = Unit

            override fun onError(camera: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                cont.resumeWithException(RuntimeException("Camera $cameraId error: (${error}) $msg"))
            }
        }, cameraHandler)
    }

    private fun createCaptureRequest(
        cameraDevice: CameraDevice, outputSurface: Surface
    ): CaptureRequest {
        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(outputSurface)
        return captureRequest.build()
    }

    private fun createCaptureRequest(
        cameraDevice: CameraDevice, outputsSurface: List<Surface>
    ): CaptureRequest {
        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.set(
            CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO
        )
        for (outputSurface in outputsSurface) {
            captureRequest.addTarget(outputSurface)
        }
        return captureRequest.build()
    }

    private suspend fun createCaptureSession(
        cameraDevice: CameraDevice, outputsSurface: List<Surface>
    ) = suspendCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputs = outputsSurface.map {
                OutputConfiguration(it)
            }
            val session = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                outputs,
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
                    override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                })
            cameraDevice.createCaptureSession(session)
        } else {
            cameraDevice.createCaptureSession(
                outputsSurface,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
                    override fun onConfigureFailed(session: CameraCaptureSession) =
                        cont.resumeWithException(RuntimeException("Camera $cameraId session configuration failed."))
                },
                cameraHandler
            )
        }
    }

    private fun configureTransform(textureView: TextureView, lensFacing: Int) {
        when (lensFacing) {
            CameraCharacteristics.LENS_FACING_BACK -> {
                val matrix = Matrix()
                // val viewRect = RectF(0F, 0F, 1F * textureView.width, 1F * textureView.height)
                // val buffRect = RectF(0F, 0F, 1F * previewSize.width, 1F * previewSize.height)
                // buffRect.offset(
                //     viewRect.centerX() - buffRect.centerX(), viewRect.centerY() - buffRect.centerX()
                // )
                // matrix.setRectToRect(viewRect, buffRect, Matrix.ScaleToFit.FILL)
                // val scaleX = 1F * viewRect.width() / buffRect.width()
                // val scaleY = 1F * viewRect.height() / buffRect.height()
                // val scale = max(scaleX, scaleY)
                // matrix.postScale(scale, scale, buffRect.centerX(), buffRect.centerY())
                // matrix.postRotate(-90F, buffRect.centerX(), buffRect.centerY())
                textureView.setTransform(matrix)
            }

            CameraCharacteristics.LENS_FACING_FRONT -> {
                val matrix = Matrix()
                // val viewRect = RectF(0F, 0F, 1F * textureView.width, 1F * textureView.height)
                // val buffRect = RectF(0F, 0F, 1F * previewSize.width, 1F * previewSize.height)
                // buffRect.offset(
                //     viewRect.centerX() - buffRect.centerX(), viewRect.centerY() - buffRect.centerX()
                // )
                // matrix.setRectToRect(viewRect, buffRect, Matrix.ScaleToFit.FILL)
                // val scaleX = 1F * viewRect.width() / buffRect.width()
                // val scaleY = 1F * viewRect.height() / buffRect.height()
                // val scale = max(scaleX, scaleY)
                // matrix.postScale(scale, scale, buffRect.centerX(), buffRect.centerY())
                // matrix.postRotate(0F, buffRect.centerX(), buffRect.centerY())
                textureView.setTransform(matrix)
            }
        }
    }

    private fun imageToNv21(
        planes: Array<Image.Plane>, width: Int, height: Int
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

    class CompareSizeByAspectRatio(private val width: Int, private val height: Int) :
        Comparator<Size> {
        override fun compare(o1: Size, o2: Size): Int {
            val aspectRatio = 1f * height / width

            val lhsAspectRatio = 1f * o1.height / o1.width
            val rhsAspectRatio = 1f * o2.height / o2.width

            Log.e(
                "AspectRatio",
                "aspectRatio=$aspectRatio, lhsAspectRatio=$lhsAspectRatio, rhsAspectRatio=$rhsAspectRatio"
            )

            return if (lhsAspectRatio < rhsAspectRatio) 1 else -1
        }
    }

    class CompareSizeByArea : Comparator<Size> {
        override fun compare(o1: Size, o2: Size): Int {
            return signum(
                1L * o1.width * o1.height - 1L * o2.width * o2.height
            )
        }
    }
}
