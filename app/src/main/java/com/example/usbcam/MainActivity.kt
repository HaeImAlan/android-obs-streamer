package com.example.usbcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Size
import android.view.TextureView
import android.view.View
import android.view.WindowManager
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

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var connectionInfo: TextView
    private lateinit var controlsPanel: View
    private lateinit var toggleButton: Button
    private lateinit var screenOffButton: Button
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner

    private val server = MjpegServer()
    private lateinit var cameraStreamer: CameraStreamer
    private var streaming = false
    private var surfaceReady = false
    private var screenOff = false
    private var controlsVisible = true

    private val availableResolutions = mutableListOf<Size>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        statusText = findViewById(R.id.statusText)
        connectionInfo = findViewById(R.id.connectionInfo)
        controlsPanel = findViewById(R.id.controlsPanel)
        toggleButton = findViewById(R.id.toggleButton)
        screenOffButton = findViewById(R.id.screenOffButton)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)

        cameraStreamer = CameraStreamer(this, server)

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

        // Tap preview to toggle controls visibility
        textureView.setOnClickListener { toggleControls() }

        toggleButton.setOnClickListener {
            if (streaming) stopStreaming() else startStreaming()
        }

        screenOffButton.setOnClickListener {
            if (screenOff) screenOn() else screenOff()
        }

        setupFpsSpinner()
        requestCameraPermission()
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        controlsPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
    }

    private fun screenOff() {
        screenOff = true
        screenOffButton.text = "Screen On"
        // Dim screen to minimum
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
        // Hide preview
        textureView.visibility = View.INVISIBLE
        controlsPanel.visibility = View.GONE
        controlsVisible = false
    }

    private fun screenOn() {
        screenOff = false
        screenOffButton.text = "Screen Off"
        // Restore brightness
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        // Show preview
        textureView.visibility = View.VISIBLE
        controlsPanel.visibility = View.VISIBLE
        controlsVisible = true
    }

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
                statusText.text = "Camera permission denied"
            }
        }
    }

    private fun onPermissionGranted() {
        toggleButton.isEnabled = true
        screenOffButton.isEnabled = true
        setupResolutionSpinner()
        startStreaming()
    }

    private fun setupFpsSpinner() {
        val labels = FPS_OPTIONS.map { "${it}fps" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fpsSpinner.adapter = adapter

        // Default to 30fps
        fpsSpinner.setSelection(FPS_OPTIONS.indexOf(30).coerceAtLeast(0))

        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newFps = FPS_OPTIONS[pos]
                if (newFps != cameraStreamer.targetFps) {
                    cameraStreamer.targetFps = newFps
                    if (streaming) restartCamera()
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
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
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

    private fun restartCamera() {
        cameraStreamer.stop()
        val surface = if (surfaceReady && !screenOff) textureView.surfaceTexture else null
        cameraStreamer.start(surface)
    }

    private fun startStreaming() {
        server.start()
        val surface = if (surfaceReady && !screenOff) textureView.surfaceTexture else null
        cameraStreamer.start(surface)
        streaming = true
        updateConnectionInfo()
        toggleButton.text = "Stop"
    }

    private fun stopStreaming() {
        cameraStreamer.stop()
        server.stop()
        streaming = false
        statusText.text = "Stopped"
        connectionInfo.visibility = View.GONE
        toggleButton.text = "Start"
    }

    private fun updateConnectionInfo() {
        val ip = getDeviceIp()
        val wifiLine = if (ip != null) "wifi  http://$ip:4747/video" else "wifi  not connected"
        val adbLine = "usb   adb forward tcp:4747 tcp:4747"

        statusText.text = "Streaming :4747"
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
            val surface = if (surfaceReady && !screenOff) textureView.surfaceTexture else null
            cameraStreamer.start(surface)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
