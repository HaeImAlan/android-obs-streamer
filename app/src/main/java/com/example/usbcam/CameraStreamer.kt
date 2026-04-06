package com.example.usbcam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface

class CameraStreamer(
    private val context: Context,
    private val server: MjpegServer
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    @Volatile
    private var running = false

    var resolution: Size = Size(1280, 720)

    fun getSupportedResolutions(): List<Size> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findRearCamera(manager) ?: return emptyList()
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
            } finally {
                image.close()
            }
        }, cameraHandler)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findRearCamera(manager) ?: return

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera, surfaceTexture)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    // Attempt reopen after delay
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
            val surfaces = mutableListOf<Surface>()
            surfaces.add(imageReader!!.surface)

            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
                surfaces.add(Surface(surfaceTexture))
            }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        surfaces.forEach { addTarget(it) }
                        set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                        set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(15, 30)
                        )
                    }
                    session.setRepeatingRequest(request.build(), null, cameraHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    fun stop() {
        running = false
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: CameraAccessException) {
            // ignore
        } catch (e: IllegalStateException) {
            // ignore — session already closed
        }
        captureSession = null
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

    private fun findRearCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        // Fallback to first available camera
        return manager.cameraIdList.firstOrNull()
    }
}
