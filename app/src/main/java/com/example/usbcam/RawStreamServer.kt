package com.example.usbcam

import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * Low-latency raw TCP server for the native OBS plugin.
 * Protocol: "ACAM" magic → repeating [uint32 BE frame size][JPEG bytes]
 * Port 4748, TCP_NODELAY, single active client.
 */
class RawStreamServer(private val port: Int = 4748) {

    @Volatile
    var latestFrame: ByteArray? = null
    @Volatile
    var clientConnected: Boolean = false
        private set
    @Volatile
    var framesSent: Long = 0
        private set

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var running = false
    @Volatile
    private var activeClient: Socket? = null
    private var clientThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        executor.submit {
            try {
                serverSocket = ServerSocket(port).also {
                    it.reuseAddress = true
                }
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        // Disconnect previous client
                        activeClient?.let { old ->
                            try { old.close() } catch (_: IOException) {}
                        }
                        socket.tcpNoDelay = true
                        socket.sendBufferSize = 65536
                        activeClient = socket
                        clientConnected = true
                        clientThread = Thread { handleClient(socket) }
                        clientThread?.isDaemon = true
                        clientThread?.start()
                    } catch (e: SocketException) {
                        break
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val out = socket.getOutputStream()
            // Send magic header
            out.write("ACAM".toByteArray())
            out.flush()

            var lastSentFrame: ByteArray? = null
            while (running && !socket.isClosed) {
                val frame = latestFrame
                if (frame == null || frame === lastSentFrame) {
                    Thread.sleep(1)
                    continue
                }
                writeFrame(out, frame)
                framesSent++
                lastSentFrame = frame
            }
        } catch (e: IOException) {
            // Client disconnected
        } finally {
            try { socket.close() } catch (_: IOException) {}
            if (activeClient === socket) {
                activeClient = null
                clientConnected = false
            }
        }
    }

    private fun writeFrame(out: OutputStream, frame: ByteArray) {
        val size = frame.size
        // 4 bytes big-endian length
        val header = byteArrayOf(
            (size shr 24 and 0xFF).toByte(),
            (size shr 16 and 0xFF).toByte(),
            (size shr 8 and 0xFF).toByte(),
            (size and 0xFF).toByte()
        )
        out.write(header)
        out.write(frame)
        out.flush()
    }

    fun stop() {
        running = false
        try { activeClient?.close() } catch (_: IOException) {}
        activeClient = null
        clientConnected = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        framesSent = 0
    }
}
