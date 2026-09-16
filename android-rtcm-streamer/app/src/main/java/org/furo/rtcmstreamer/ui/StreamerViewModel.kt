package org.furo.rtcmstreamer.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.furo.rtcmstreamer.geo.GeoConvert
import org.furo.rtcmstreamer.gnss.SatSummary
import org.furo.rtcmstreamer.net.Solution

/** A plotted East-North point with its solution quality. */
data class TrackPoint(val e: Float, val n: Float, val q: Int)

/**
 * Rover runtime state aggregated for the screen: satellite counts, permission, CSV logging,
 * RTCM uplink connection state, received solution + East-North track for the plot.
 *
 * Server settings (host / ports / save toggle) are persisted to SharedPreferences so they survive
 * an app restart — useful for outdoor sessions where the server address must not be re-typed each time.
 */
class StreamerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SharedPreferences =
        app.getSharedPreferences("rover_settings", Context.MODE_PRIVATE)

    var summary by mutableStateOf(SatSummary())
        private set

    var permissionGranted by mutableStateOf(false)
    var logging by mutableStateOf(false)
    var logPath by mutableStateOf<String?>(null)

    // When set, a Connect session also saves the sent RTCM3 and received .pos, and offers them via
    // the share sheet on Disconnect. Off by default (Connect is often just for live use). Persisted.
    private var _saveComms by mutableStateOf(prefs.getBoolean(KEY_SAVE_COMMS, false))
    var saveComms: Boolean
        get() = _saveComms
        set(v) { _saveComms = v; prefs.edit().putBoolean(KEY_SAVE_COMMS, v).apply() }

    // Server connection. Host / ports are persisted across restarts.
    private var _host by mutableStateOf(prefs.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1")
    var host: String
        get() = _host
        set(v) { _host = v; prefs.edit().putString(KEY_HOST, v).apply() }

    private var _rtcmPort by mutableStateOf(prefs.getString(KEY_RTCM_PORT, "8765") ?: "8765")
    var rtcmPort: String
        get() = _rtcmPort
        set(v) { _rtcmPort = v; prefs.edit().putString(KEY_RTCM_PORT, v).apply() }

    private var _solPort by mutableStateOf(prefs.getString(KEY_SOL_PORT, "8766") ?: "8766")
    var solPort: String
        get() = _solPort
        set(v) { _solPort = v; prefs.edit().putString(KEY_SOL_PORT, v).apply() }

    var connected by mutableStateOf(false)
    var connStatus by mutableStateOf("disconnected")
    var solStatus by mutableStateOf("sol disconnected")
    var epochsSent by mutableStateOf(0L)

    // Solution / track.
    var latest by mutableStateOf<Solution?>(null)
    private var origin: DoubleArray? = null // lat, lon, h of the first solution
    val track = mutableStateListOf<TrackPoint>()
    var pxPerMeter by mutableStateOf(20f)

    fun onSummary(s: SatSummary) {
        summary = s
    }

    fun onSolution(sol: Solution) {
        latest = sol
        if (origin == null) origin = doubleArrayOf(sol.lat, sol.lon, sol.height)
        val o = origin!!
        val en = GeoConvert.enu(o[0], o[1], o[2], sol.lat, sol.lon, sol.height)
        track.add(TrackPoint(en[0], en[1], sol.q))
        if (track.size > MAX_POINTS) track.removeAt(0)
    }

    fun setScale(s: Float) {
        pxPerMeter = s.coerceIn(1f, 2000f)
    }

    fun resetTrack() {
        origin = null
        track.clear()
        latest = null
    }

    companion object {
        private const val MAX_POINTS = 3600

        // SharedPreferences keys for the persisted server settings.
        private const val KEY_HOST = "host"
        private const val KEY_RTCM_PORT = "rtcm_port"
        private const val KEY_SOL_PORT = "sol_port"
        private const val KEY_SAVE_COMMS = "save_comms"
    }
}
