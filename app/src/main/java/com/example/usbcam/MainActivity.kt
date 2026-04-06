package com.example.usbcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Size
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var resolutionSpinner: Spinner

    private val server = MjpegServer()
    private lateinit var cameraStreamer: CameraStreamer
    private var streaming = false
    private var surfaceReady = false

    private val availableResolutions = mutableListOf<Size>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)

        cameraStreamer = CameraStreamer(this, server)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surfaceReady = true
                if (streaming) {
                    // Surface recreated while streaming — restart camera
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

        toggleButton.setOnClickListener {
            if (streaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        requestCameraPermission()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onPermissionGranted()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onPermissionGranted()
            } else {
                statusText.text = "Camera permission denied"
                toggleButton.isEnabled = false
            }
        }
    }

    private fun onPermissionGranted() {
        toggleButton.isEnabled = true
        statusText.text = "Ready — press Start"
        setupResolutionSpinner()
        // Auto-start streaming
        startStreaming()
    }

    private fun setupResolutionSpinner() {
        val supported = cameraStreamer.getSupportedResolutions()
        // Filter to common useful resolutions
        val common = listOf(
            Size(640, 480),
            Size(1280, 720),
            Size(1920, 1080)
        )
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

        // Default to 1280x720 if available
        val defaultIdx = availableResolutions.indexOfFirst { it.width == 1280 && it.height == 720 }
        if (defaultIdx >= 0) {
            resolutionSpinner.setSelection(defaultIdx)
        }

        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newRes = availableResolutions[pos]
                if (newRes != cameraStreamer.resolution) {
                    cameraStreamer.resolution = newRes
                    if (streaming) {
                        // Restart with new resolution
                        cameraStreamer.stop()
                        cameraStreamer.start(
                            if (surfaceReady) textureView.surfaceTexture else null
                        )
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun startStreaming() {
        server.start()
        cameraStreamer.start(if (surfaceReady) textureView.surfaceTexture else null)
        streaming = true
        val ip = getDeviceIp()
        statusText.text = if (ip != null) {
            "Streaming at http://$ip:4747/video"
        } else {
            "Streaming on port 4747 (connect to WiFi to see IP)"
        }
        toggleButton.text = "Stop"
    }

    private fun stopStreaming() {
        cameraStreamer.stop()
        server.stop()
        streaming = false
        statusText.text = "Stopped"
        toggleButton.text = "Start"
    }

    override fun onPause() {
        super.onPause()
        if (streaming) {
            cameraStreamer.stop()
        }
    }

    override fun onResume() {
        super.onResume()
        if (streaming) {
            cameraStreamer.start(if (surfaceReady) textureView.surfaceTexture else null)
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
