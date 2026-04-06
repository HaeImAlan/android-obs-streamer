package com.example.usbcam

import java.io.IOException
import java.io.OutputStream
import java.net.Socket

class StreamSession(
    private val socket: Socket,
    private val server: MjpegServer
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread {
            try {
                val out = socket.getOutputStream()
                writeHeader(out)
                var lastSentFrame: ByteArray? = null
                while (running && !socket.isClosed) {
                    val frame = server.latestFrame
                    if (frame == null || frame === lastSentFrame) {
                        Thread.sleep(10)
                        continue
                    }
                    writeFrame(out, frame)
                    lastSentFrame = frame
                }
            } catch (e: IOException) {
                // Client disconnected — exit silently
            } catch (e: InterruptedException) {
                // Thread interrupted — exit
            } finally {
                try {
                    socket.close()
                } catch (e: IOException) {
                    // ignore
                }
                server.removeSession(this)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try {
            socket.close()
        } catch (e: IOException) {
            // ignore
        }
        thread?.interrupt()
    }

    private fun writeHeader(out: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(header.toByteArray())
        out.flush()
    }

    private fun writeFrame(out: OutputStream, frame: ByteArray) {
        val header = "--frame\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${frame.size}\r\n" +
                "\r\n"
        out.write(header.toByteArray())
        out.write(frame)
        out.write("\r\n".toByteArray())
        out.flush()
    }
}
