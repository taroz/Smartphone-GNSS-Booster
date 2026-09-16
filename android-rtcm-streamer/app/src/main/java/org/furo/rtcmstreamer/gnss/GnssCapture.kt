package org.furo.rtcmstreamer.gnss

import android.Manifest
import android.content.Context
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import org.furo.rtcmstreamer.obs.ObsConverter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Per-epoch satellite count summary (distinct Svid per constellation and the total). */
data class SatSummary(
    val perConstellation: Map<String, Int> = emptyMap(),
    val total: Int = 0,
    val epoch: Long = 0,
    val gpst: String = "", // GNSS time (GPST calendar), updates at 1 Hz
)

/**
 * Raw GNSS Measurements API capture layer.
 * - start()ed at launch and runs continuously at 1 Hz (independent of the server connection)
 * - On API 31+, setFullTracking(true) disables duty cycling (required for stable L5)
 * - Counts each epoch's measurements by distinct Svid per constellation and emits via [onSummary]
 * - Forwards raw events via [onEvent] for the CSV logger etc.
 *
 * Callbacks run on the Executor/Handler given at registration (the main thread).
 */
class GnssCapture(private val context: Context) {

    private val lm = context.getSystemService(LocationManager::class.java)

    /** Sink for satellite-count summaries (main thread). */
    var onSummary: ((SatSummary) -> Unit)? = null

    /** Sink for raw events (main thread). Used by the CSV logger and observable conversion. */
    var onEvent: ((GnssMeasurementsEvent) -> Unit)? = null

    private var epoch = 0L
    private var registered = false

    private val callback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            epoch++
            onEvent?.invoke(event)
            onSummary?.invoke(summarize(event))
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start() {
        val manager = lm ?: return
        if (registered) return
        registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = GnssMeasurementRequest.Builder()
                .setFullTracking(true)
                .build()
            manager.registerGnssMeasurementsCallback(request, context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            manager.registerGnssMeasurementsCallback(
                callback,
                Handler(Looper.getMainLooper())
            )
        }
    }

    fun stop() {
        val manager = lm ?: return
        if (!registered) return
        manager.unregisterGnssMeasurementsCallback(callback)
        registered = false
    }

    private fun summarize(event: GnssMeasurementsEvent): SatSummary {
        val sets = HashMap<String, MutableSet<Int>>()
        for (m in event.measurements) {
            val name = constellationName(m.constellationType)
            sets.getOrPut(name) { HashSet() }.add(m.svid)
        }
        val per = sets.mapValues { it.value.size }
        val total = per.values.sum()
        return SatSummary(per, total, epoch, gpstString(event.clock))
    }

    /** GNSS time (GPST calendar) from the clock, or "" if the clock bias is not yet known. */
    private fun gpstString(c: android.location.GnssClock): String {
        if (!c.hasFullBiasNanos()) return ""
        val (week, tow) = ObsConverter.epochTime(
            c.timeNanos.toDouble(),
            c.fullBiasNanos.toDouble(),
            if (c.hasBiasNanos()) c.biasNanos else 0.0,
        )
        // GPST seconds since Unix epoch (GPS epoch 1980-01-06 = 315964800 s); no leap correction = GPST clock reading.
        val gpstMillis = (315964800L + week.toLong() * 604800L) * 1000L + Math.round(tow * 1000.0)
        return GPST_FORMAT.format(java.util.Date(gpstMillis))
    }

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "OTHER"
    }

    companion object {
        private val GPST_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
