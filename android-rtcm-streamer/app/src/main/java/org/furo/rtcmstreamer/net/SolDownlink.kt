package org.furo.rtcmstreamer.net

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Downlink: receive the position solution (.pos LLH ASCII) from the RTK server, line by line.
 * Parses each line with [SolParser] and delivers [Solution]s via [onSolution].
 * Runs a reader thread; close()/disconnect stops it.
 */
class SolDownlink {

    @Volatile
    var connected: Boolean = false
        private set

    /** Called on the reader thread with (connected, message). */
    var onState: ((Boolean, String) -> Unit)? = null

    /** Called on the reader thread for each parsed solution. */
    var onSolution: ((Solution) -> Unit)? = null

    private var socket: Socket? = null
    @Volatile
    private var running = false

    // Optional: save the raw received .pos lines verbatim to a file (started/stopped on Connect).
    @Volatile
    private var posWriter: BufferedWriter? = null

    fun startSave(file: File) {
        file.parentFile?.mkdirs()
        posWriter = BufferedWriter(FileWriter(file))
    }

    fun stopSave() {
        posWriter?.let { try { it.flush(); it.close() } catch (_: Exception) {} }
        posWriter = null
    }

    fun connect(host: String, port: Int) {
        if (running) disconnect()
        running = true
        thread(name = "SolDownlink") {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                connected = true
                onState?.invoke(true, "sol connected $host:$port")
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                while (running) {
                    val line = reader.readLine() ?: break
                    posWriter?.let { try { it.write(line); it.newLine(); it.flush() } catch (_: Exception) {} }
                    SolParser.parse(line)?.let { onSolution?.invoke(it) }
                }
            } catch (e: Exception) {
                onState?.invoke(false, "sol error: ${e.message}")
            } finally {
                connected = false
                close()
                onState?.invoke(false, "sol disconnected")
            }
        }
    }

    fun disconnect() {
        running = false
        close()
    }

    private fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
