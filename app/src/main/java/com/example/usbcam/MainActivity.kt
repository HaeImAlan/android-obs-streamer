package com.example.usbcam

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private val FPS_OPTIONS = intArrayOf(10, 15, 20, 24, 30, 60)
    }

    // Views
    private lateinit var textureView: TextureView
    private lateinit var statusBar: TextView
    private lateinit var menuButton: TextView
    private lateinit var controlsPanel: View
    private lateinit var connectionInfo: TextView
    private lateinit var toggleButton: Button
    private lateinit var screenOffButton: Button
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var afModeButton: TextView
    private lateinit var aeLockButton: TextView
    private lateinit var evSeekBar: SeekBar
    private lateinit var evValueText: TextView
    private lateinit var focusRing: View
    private lateinit var cameraSwitchButton: TextView
    private lateinit var torchButton: TextView
    private lateinit var ringLightButton: TextView
    private lateinit var ringLightOverlay: View
    private lateinit var ringLightBrightnessRow: View
    private lateinit var ringLightSeekBar: SeekBar

    // State
    private val server = MjpegServer()
    private lateinit var cameraStreamer: CameraStreamer
    private val uiHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var orientationListener: OrientationEventListener? = null
    private var streaming = false
    private var surfaceReady = false
    private var screenOff = false
    private var menuOpen = false
    private var ringLightOn = false
    private val availableResolutions = mutableListOf<Size>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        cameraStreamer = CameraStreamer(this, server)

        setupTextureView()
        setupTapToFocus()
        setupMenuButton()
        setupButtons()
        setupFpsSpinner()
        setupEvSlider()
        setupRingLightSlider()
        setupOrientationListener()
        acquireWakeLock()
        requestCameraPermission()
    }

    private fun bindViews() {
        textureView = findViewById(R.id.textureView)
        statusBar = findViewById(R.id.statusBar)
        menuButton = findViewById(R.id.menuButton)
        controlsPanel = findViewById(R.id.controlsPanel)
        connectionInfo = findViewById(R.id.connectionInfo)
        toggleButton = findViewById(R.id.toggleButton)
        screenOffButton = findViewById(R.id.screenOffButton)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        afModeButton = findViewById(R.id.afModeButton)
        aeLockButton = findViewById(R.id.aeLockButton)
        evSeekBar = findViewById(R.id.evSeekBar)
        evValueText = findViewById(R.id.evValueText)
        focusRing = findViewById(R.id.focusRing)
        focusRing.setBackgroundResource(R.drawable.focus_ring)
        cameraSwitchButton = findViewById(R.id.cameraSwitchButton)
        torchButton = findViewById(R.id.torchButton)
        ringLightButton = findViewById(R.id.ringLightButton)
        ringLightOverlay = findViewById(R.id.ringLightOverlay)
        ringLightBrightnessRow = findViewById(R.id.ringLightBrightnessRow)
        ringLightSeekBar = findViewById(R.id.ringLightSeekBar)
    }

    // ── Wake lock ────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "usbcam:streaming"
        )
        wakeLock?.acquire()
    }

    // ── Orientation ──────────────────────────────────────────────────────

    private fun setupOrientationListener() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation in 315..360 || orientation in 0..44 -> Surface.ROTATION_0
                    orientation in 45..134 -> Surface.ROTATION_270
                    orientation in 135..224 -> Surface.ROTATION_180
                    orientation in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation != cameraStreamer.deviceRotation) {
                    cameraStreamer.deviceRotation = rotation
                    if (streaming) cameraStreamer.applySettings()
                }
            }
        }
        orientationListener?.enable()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Layout handles itself — just update rotation
        cameraStreamer.deviceRotation = windowManager.defaultDisplay.rotation
        if (streaming) cameraStreamer.applySettings()
    }

    // ── TextureView ──────────────────────────────────────────────────────

    private fun setupTextureView() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surfaceReady = true
                if (streaming && !screenOff) {
                    cameraStreamer.stop()
                    cameraStreamer.start(st)
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceReady = false
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    // ── Menu drawer ──────────────────────────────────────────────────────

    private fun setupMenuButton() {
        menuButton.setOnClickListener { toggleMenu() }
    }

    private fun toggleMenu() {
        menuOpen = !menuOpen
        if (menuOpen) {
            controlsPanel.visibility = View.VISIBLE
            controlsPanel.translationX = controlsPanel.width.toFloat()
            controlsPanel.animate().translationX(0f).setDuration(200).start()
            menuButton.text = "X"
        } else {
            controlsPanel.animate().translationX(controlsPanel.width.toFloat())
                .setDuration(200)
                .withEndAction { controlsPanel.visibility = View.GONE }
                .start()
            menuButton.text = "..."
        }
    }

    // ── Buttons ──────────────────────────────────────────────────────────

    private fun setupButtons() {
        toggleButton.setOnClickListener {
            if (streaming) stopStreaming() else startStreaming()
        }
        screenOffButton.setOnClickListener {
            if (screenOff) screenOn() else screenOff()
        }
        cameraSwitchButton.setOnClickListener { switchCamera() }
        torchButton.setOnClickListener { toggleTorch() }
        ringLightButton.setOnClickListener { toggleRingLight() }
        afModeButton.setOnClickListener { toggleAfMode() }
        aeLockButton.setOnClickListener { toggleAeLock() }
    }

    // ── Camera switch ────────────────────────────────────────────────────

    private fun switchCamera() {
        cameraStreamer.useFrontCamera = !cameraStreamer.useFrontCamera
        if (cameraStreamer.useFrontCamera) cameraStreamer.torchEnabled = false
        updateCameraLabels()
        if (streaming) restartCamera()
    }

    private fun updateCameraLabels() {
        val isFront = cameraStreamer.useFrontCamera
        cameraSwitchButton.text = if (isFront) "Front Cam" else "Back Cam"
        cameraSwitchButton.setTextColor(if (isFront) 0xFFFFAA00.toInt() else 0xFF00CC66.toInt())
        torchButton.isEnabled = !isFront && cameraStreamer.hasFlash
        updateTorchLabel()
    }

    // ── Torch ────────────────────────────────────────────────────────────

    private fun toggleTorch() {
        if (cameraStreamer.useFrontCamera) return
        cameraStreamer.torchEnabled = !cameraStreamer.torchEnabled
        updateTorchLabel()
        if (streaming) cameraStreamer.applySettings()
    }

    private fun updateTorchLabel() {
        if (cameraStreamer.torchEnabled && !cameraStreamer.useFrontCamera) {
            torchButton.text = "Torch: On"
            torchButton.setTextColor(0xFFFFCC00.toInt())
        } else {
            torchButton.text = "Torch: Off"
            torchButton.setTextColor(0xFF888888.toInt())
        }
    }

    // ── Ring light ───────────────────────────────────────────────────────

    private fun toggleRingLight() {
        ringLightOn = !ringLightOn
        if (ringLightOn) {
            updateRingLightBrightness(ringLightSeekBar.progress)
            ringLightOverlay.visibility = View.VISIBLE
            ringLightBrightnessRow.visibility = View.VISIBLE
            ringLightButton.text = "Ring Light: On"
            ringLightButton.setTextColor(0xFFFFCC00.toInt())
            val lp = window.attributes
            lp.screenBrightness = 1.0f
            window.attributes = lp
        } else {
            ringLightOverlay.visibility = View.GONE
            ringLightBrightnessRow.visibility = View.GONE
            ringLightButton.text = "Ring Light: Off"
            ringLightButton.setTextColor(0xFF888888.toInt())
            if (!screenOff) {
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    private fun setupRingLightSlider() {
        ringLightSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (ringLightOn) updateRingLightBrightness(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun updateRingLightBrightness(brightness: Int) {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.setColor(Color.argb(brightness, 255, 255, 255))
        ringLightOverlay.background = bg
    }

    // ── Tap-to-focus ─────────────────────────────────────────────────────

    private fun setupTapToFocus() {
        textureView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    if (streaming) {
                        val nx = event.x / v.width
                        val ny = event.y / v.height
                        cameraStreamer.tapToFocus(nx, ny)
                        showFocusRing(event.x, event.y)
                    }
                    v.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val size = focusRing.layoutParams.width
        focusRing.x = x - size / 2f
        focusRing.y = y - size / 2f
        focusRing.alpha = 1f
        focusRing.scaleX = 1.3f
        focusRing.scaleY = 1.3f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        uiHandler.removeCallbacksAndMessages("focus")
        uiHandler.postDelayed({
            focusRing.animate().alpha(0f).setDuration(300).withEndAction {
                focusRing.visibility = View.GONE
            }.start()
        }, 1500)
    }

    // ── AF / AE ──────────────────────────────────────────────────────────

    private fun toggleAfMode() {
        cameraStreamer.continuousAf = !cameraStreamer.continuousAf
        updateAfLabel()
        if (streaming) cameraStreamer.applySettings()
    }

    private fun toggleAeLock() {
        cameraStreamer.aeLocked = !cameraStreamer.aeLocked
        updateAeLabel()
        if (streaming) cameraStreamer.applySettings()
    }

    private fun updateAfLabel() {
        if (cameraStreamer.continuousAf) {
            afModeButton.text = "AF: Cont"
            afModeButton.setTextColor(0xFF00CC66.toInt())
        } else {
            afModeButton.text = "AF: Tap"
            afModeButton.setTextColor(0xFFFFAA00.toInt())
        }
    }

    private fun updateAeLabel() {
        if (cameraStreamer.aeLocked) {
            aeLockButton.text = "AE: Lock"
            aeLockButton.setTextColor(0xFFFFAA00.toInt())
        } else {
            aeLockButton.text = "AE: Auto"
            aeLockButton.setTextColor(0xFF00CC66.toInt())
        }
    }

    // ── Exposure compensation ────────────────────────────────────────────

    private fun setupEvSlider() {
        evSeekBar.max = 0
        evSeekBar.progress = 0
        evValueText.text = "0"

        evSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ev = cameraStreamer.evRange.lower + progress
                cameraStreamer.exposureCompensation = ev
                evValueText.text = if (ev >= 0) "+$ev" else "$ev"
                if (streaming) cameraStreamer.applySettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun configureEvSlider() {
        val range = cameraStreamer.evRange
        evSeekBar.max = range.upper - range.lower
        evSeekBar.progress = -range.lower
        evValueText.text = "0"
    }

    // ── Screen off ───────────────────────────────────────────────────────

    private fun screenOff() {
        screenOff = true
        screenOffButton.text = "Screen On"
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
        textureView.visibility = View.INVISIBLE
        ringLightOverlay.visibility = View.GONE
        statusBar.visibility = View.GONE
        menuButton.visibility = View.GONE
        if (menuOpen) toggleMenu()
    }

    private fun screenOn() {
        screenOff = false
        screenOffButton.text = "Screen Off"
        val lp = window.attributes
        lp.screenBrightness = if (ringLightOn) 1.0f
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        textureView.visibility = View.VISIBLE
        if (ringLightOn) ringLightOverlay.visibility = View.VISIBLE
        menuButton.visibility = View.VISIBLE
        if (streaming) statusBar.visibility = View.VISIBLE
    }

    // ── Permissions ──────────────────────────────────────────────────────

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onPermissionGranted()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onPermissionGranted()
            } else {
                statusBar.text = "Camera permission denied"
                statusBar.visibility = View.VISIBLE
            }
        }
    }

    private fun onPermissionGranted() {
        toggleButton.isEnabled = true
        screenOffButton.isEnabled = true
        setupResolutionSpinner()
        startStreaming()
    }

    // ── Spinners ─────────────────────────────────────────────────────────

    private fun setupFpsSpinner() {
        val labels = FPS_OPTIONS.map { "${it}fps" }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fpsSpinner.adapter = adapter
        fpsSpinner.setSelection(FPS_OPTIONS.indexOf(30).coerceAtLeast(0))

        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newFps = FPS_OPTIONS[pos]
                if (newFps != cameraStreamer.targetFps) {
                    cameraStreamer.targetFps = newFps
                    if (streaming) cameraStreamer.applySettings()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupResolutionSpinner() {
        val supported = cameraStreamer.getSupportedResolutions()
        val common = listOf(Size(640, 480), Size(1280, 720), Size(1920, 1080))
        availableResolutions.clear()
        for (size in common) {
            if (supported.any { it.width == size.width && it.height == size.height }) {
                availableResolutions.add(size)
            }
        }
        if (availableResolutions.isEmpty() && supported.isNotEmpty()) {
            availableResolutions.addAll(supported.take(5))
        }

        val labels = availableResolutions.map { "${it.width}x${it.height}" }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resolutionSpinner.adapter = adapter

        val defaultIdx = availableResolutions.indexOfFirst { it.width == 1280 && it.height == 720 }
        if (defaultIdx >= 0) resolutionSpinner.setSelection(defaultIdx)

        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newRes = availableResolutions[pos]
                if (newRes != cameraStreamer.resolution) {
                    cameraStreamer.resolution = newRes
                    if (streaming) restartCamera()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── Streaming lifecycle ──────────────────────────────────────────────

    private fun restartCamera() {
        cameraStreamer.stop()
        val surface = if (surfaceReady && !screenOff) textureView.surfaceTexture else null
        cameraStreamer.start(surface)
        uiHandler.postDelayed({
            configureEvSlider()
            updateCameraLabels()
        }, 500)
    }

    private fun startStreaming() {
        server.start()
        cameraStreamer.deviceRotation = windowManager.defaultDisplay.rotation
        val surface = if (surfaceReady && !screenOff) textureView.surfaceTexture else null
        cameraStreamer.start(surface)
        streaming = true
        updateStatusBar()
        toggleButton.text = "Stop"
        uiHandler.postDelayed({
            configureEvSlider()
            updateCameraLabels()
        }, 500)
    }

    private fun stopStreaming() {
        cameraStreamer.stop()
        server.stop()
        streaming = false
        statusBar.text = "Stopped"
        connectionInfo.visibility = View.GONE
        toggleButton.text = "Start"
    }

    private fun updateStatusBar() {
        val ip = getDeviceIp()
        val statusLine = if (ip != null) "http://$ip:4747" else ":4747"
        statusBar.text = statusLine
        statusBar.visibility = View.VISIBLE

        val wifiLine = if (ip != null) "wifi  http://$ip:4747/video" else "wifi  not connected"
        val adbLine = "usb   adb forward tcp:4747 tcp:4747\n      http://localhost:4747/video"
        connectionInfo.text = "$wifiLine\n$adbLine"
        connectionInfo.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        if (streaming) cameraStreamer.stop()
    }

    override fun onResume() {
        super.onResume()
        if (streaming) {
            cameraStreamer.deviceRotation = windowManager.defaultDisplay.rotation
            val surface = if (surfaceReady && !screenOff) textureView.surfaceTexture else null
            cameraStreamer.start(surface)
        }
        updateAfLabel()
        updateAeLabel()
        updateCameraLabels()
        updateTorchLabel()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationListener?.disable()
        wakeLock?.let { if (it.isHeld) it.release() }
        cameraStreamer.stop()
        server.stop()
    }

    private fun getDeviceIp(): String? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }
}
