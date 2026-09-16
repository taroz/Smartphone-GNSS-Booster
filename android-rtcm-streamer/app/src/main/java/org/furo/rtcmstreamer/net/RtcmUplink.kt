package org.furo.rtcmstreamer.net

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Uplink: send RTCM3 to the RTK server over TCP (the phone is an outbound TCP client).
 * Connect/disconnect and sends run on a single background thread so the GNSS callback never blocks.
 */
class RtcmUplink {

    @Volatile
    var connected: Boolean = false
        private set

    var bytesSent: Long = 0
        private set

    /** Called on the worker thread with (connected, message). */
    var onState: ((Boolean, String) -> Unit)? = null

    private val exec = Executors.newSingleThreadExecutor()
    private var socket: Socket? = null
    private var out: OutputStream? = null

    fun connect(host: String, port: Int) {
        exec.execute {
            try {
                close()
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                out = s.getOutputStream()
                bytesSent = 0
                connected = true
                onState?.invoke(true, "connected $host:$port")
            } catch (e: Exception) {
                connected = false
                onState?.invoke(false, "connect failed: ${e.message}")
            }
        }
    }

    fun send(data: ByteArray) {
        if (!connected) return
        exec.execute {
            try {
                out?.write(data)
                out?.flush()
                bytesSent += data.size
            } catch (e: Exception) {
                connected = false
                close()
                onState?.invoke(false, "send failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        exec.execute {
            close()
            connected = false
            onState?.invoke(false, "disconnected")
        }
    }

    private fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
    }
}
