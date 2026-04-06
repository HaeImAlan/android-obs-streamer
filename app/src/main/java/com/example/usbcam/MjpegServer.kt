package com.example.usbcam

import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class MjpegServer(private val port: Int = 4747) {

    @Volatile
    var latestFrame: ByteArray? = null

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val sessions = CopyOnWriteArrayList<StreamSession>()
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        executor.submit {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        val session = StreamSession(socket, this)
                        sessions.add(session)
                        session.start()
                    } catch (e: SocketException) {
                        // Server socket closed during accept — expected on stop
                        break
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        running = false
        sessions.forEach { it.stop() }
        sessions.clear()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
        serverSocket = null
    }

    fun removeSession(session: StreamSession) {
        sessions.remove(session)
    }
}
