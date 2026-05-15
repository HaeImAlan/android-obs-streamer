package com.example.usbcam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface

class CameraStreamer(
    private val context: Context,
    private val server: MjpegServer,
    private val rawServer: RawStreamServer? = null
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var surfaces: List<Surface> = emptyList()
    private var sensorArraySize: Rect? = null
    private var sensorOrientation: Int = 0
    @Volatile
    private var running = false

    var resolution: Size = Size(1280, 720)
    var targetFps: Int = 30
    var jpegQuality: Int = 85
    var continuousAf: Boolean = true
    var aeLocked: Boolean = false
    var exposureCompensation: Int = 0
    var useFrontCamera: Boolean = false
    var torchEnabled: Boolean = false
    var whiteBalance: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO
    var deviceRotation: Int = 0  // Surface.ROTATION_0, etc.
    @Volatile
    var framesCaptured: Long = 0
        private set

    var evRange: Range<Int> = Range(0, 0)
        private set

    var hasFlash: Boolean = false
        private set

    fun getSupportedResolutions(): List<Size> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(manager) ?: return emptyList()
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        return map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
    }

    fun start(surfaceTexture: SurfaceTexture?) {
        if (running) return
        running = true

        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        imageReader = ImageReader.newInstance(
            resolution.width, resolution.height, ImageFormat.JPEG, 2
        )
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                server.latestFrame = bytes
                rawServer?.latestFrame = bytes
                framesCaptured++
            } finally {
                image.close()
            }
        }, cameraHandler)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(manager) ?: return

        val characteristics = manager.getCameraCharacteristics(cameraId)
        sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: Range(0, 0)
        hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera, surfaceTexture)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    if (running) {
                        cameraHandler?.postDelayed({ start(surfaceTexture) }, 1000)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun createSession(camera: CameraDevice, surfaceTexture: SurfaceTexture?) {
        try {
            val surfaceList = mutableListOf<Surface>()
            surfaceList.add(imageReader!!.surface)

            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
                surfaceList.add(Surface(surfaceTexture))
            }
            surfaces = surfaceList

            camera.createCaptureSession(surfaceList, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        surfaces.forEach { addTarget(it) }
                        set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                    }
                    applySettings()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun computeJpegOrientation(): Int {
        val deviceDegrees = when (deviceRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (useFrontCamera) {
            (sensorOrientation - deviceDegrees + 360) % 360
        } else {
            (sensorOrientation + deviceDegrees) % 360
        }
    }

    fun applySettings() {
        val builder = requestBuilder ?: return
        val session = captureSession ?: return

        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            if (continuousAf) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else CaptureRequest.CONTROL_AF_MODE_AUTO
        )
        builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
        builder.set(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(targetFps, targetFps)
        )
        builder.set(CaptureRequest.JPEG_ORIENTATION, computeJpegOrientation())
        builder.set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
        builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalance)

        if (hasFlash && !useFrontCamera) {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH
                else CaptureRequest.FLASH_MODE_OFF
            )
        }

        try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            // session closed
        }
    }

    fun tapToFocus(nx: Float, ny: Float) {
        val builder = requestBuilder ?: return
        val session = captureSession ?: return
        val sensorRect = sensorArraySize ?: return

        val focusSize = 200
        val cx = (nx * sensorRect.width()).toInt().coerceIn(focusSize / 2, sensorRect.width() - focusSize / 2)
        val cy = (ny * sensorRect.height()).toInt().coerceIn(focusSize / 2, sensorRect.height() - focusSize / 2)

        val focusArea = MeteringRectangle(
            cx - focusSize / 2, cy - focusSize / 2,
            focusSize, focusSize,
            MeteringRectangle.METERING_WEIGHT_MAX
        )

        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, cameraHandler)

            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusArea))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusArea))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, cameraHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            // session closed
        }
    }

    fun stop() {
        running = false
        framesCaptured = 0
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: CameraAccessException) {
            // ignore
        } catch (e: IllegalStateException) {
            // ignore
        }
        captureSession = null
        requestBuilder = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(1000)
        } catch (e: InterruptedException) {
            // ignore
        }
        cameraThread = null
        cameraHandler = null
    }

    private fun findCamera(manager: CameraManager): String? {
        val targetFacing = if (useFrontCamera)
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == targetFacing) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }
}
