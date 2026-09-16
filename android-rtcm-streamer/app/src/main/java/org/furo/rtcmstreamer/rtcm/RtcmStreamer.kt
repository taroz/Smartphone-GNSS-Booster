package org.furo.rtcmstreamer.rtcm

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.util.Log
import java.io.File
import org.furo.rtcmstreamer.net.RtcmUplink
import org.furo.rtcmstreamer.obs.ObsConverter

/**
 * Live pipeline: GnssMeasurementsEvent -> ObsConverter -> Rtcm3Encoder (per-system MSM7)
 * -> RtcmUplink (TCP). One epoch per event.
 *
 * Forwards every constellation/frequency the front end produces (GPS/GLONASS/Galileo/BeiDou/QZSS,
 * L1+L5).
 */
class RtcmStreamer(private val staid: Int = 0) {

    // fixBias=true holds the receiver clock bias at a fixed base (reset only on a clock
    // discontinuity), so the pseudorange and carrier phase share one clock reference. Otherwise
    // (per-epoch bias) the pseudorange has the clock drift removed but the carrier phase keeps it,
    // making P - lambda*L drift at the clock rate and tripping the MSM lock-time slip detector
    // (spurious loss-of-lock every ~16 s), which would reset RTK ambiguities. Matches the MATLAB
    // gnsslog2obs.m / MatRTKLIB approach (fixed basebiasnanos).
    private val converter = ObsConverter(fixBias = true)
    private val encoder = Rtcm3Encoder()
    val uplink = RtcmUplink()

    var epochsSent: Long = 0
        private set
    var lastMsgBytes: Int = 0
        private set

    /** Satellites dropped in the last epoch for cross-frequency inconsistency (for the UI/log). */
    var lastDropped: List<String> = emptyList()
        private set

    // RINEX recording active (Log on): build obsd_t and write RINEX every epoch regardless of the
    // server connection. RTCM is still only sent (and saved) while connected.
    @Volatile
    private var rinexActive = false

    // Optional: tee the sent RTCM3 bytes to a file (started/stopped on Connect).
    private var rtcmOut: java.io.OutputStream? = null

    /**
     * Start writing a RINEX 3.04 OBS file at [file] from the same obsd_t fed to gen_rtcm3. Written
     * every epoch while active, independent of the server connection (Log button).
     */
    fun startRinex(file: File) {
        file.parentFile?.mkdirs()
        encoder.rinexOpen(file.absolutePath)
        rinexActive = true
    }

    /** Stop and close the RINEX OBS file. */
    fun stopRinex() {
        rinexActive = false
        encoder.rinexClose()
    }

    /** Start saving the sent RTCM3 byte stream to [file] (Connect). */
    fun startRtcmSave(file: File) {
        file.parentFile?.mkdirs()
        rtcmOut = file.outputStream().buffered()
    }

    /** Stop and close the RTCM3 save file. */
    fun stopRtcmSave() {
        rtcmOut?.let { try { it.flush(); it.close() } catch (_: Exception) {} }
        rtcmOut = null
    }

    /**
     * Process one epoch: build obsd_t (+ write RINEX if recording), and if connected, encode and
     * send RTCM3 (and save it if a save file is open). Call on the GNSS callback thread.
     */
    fun onGnssEvent(event: GnssMeasurementsEvent) {
        if (!rinexActive && !uplink.connected) return
        val clock = event.clock
        if (!clock.hasFullBiasNanos()) return

        // Cross-frequency consistency check: a satellite's signals must agree on the transmit time
        // (ReceivedSvTimeNanos). A large disagreement is a millisecond-ambiguity in the raw
        // pseudorange — the satellite passes the standard quality filters (good CN0, code lock, low
        // ReceivedSvTimeUncertainty) yet carries a gross (ms-level) pseudorange error that wrecks
        // the position solution. Drop such satellites before encoding. Only multi-frequency
        // satellites can be checked this way; a single-frequency ms-error needs position-domain RAIM.
        val svMin = HashMap<Long, Double>()
        val svMax = HashMap<Long, Double>()
        for (m in event.measurements) {
            val k = m.constellationType.toLong() * 1000 + m.svid
            val t = m.receivedSvTimeNanos.toDouble()
            svMin[k] = minOf(svMin[k] ?: t, t)
            svMax[k] = maxOf(svMax[k] ?: t, t)
        }

        val satid = ArrayList<String>()
        val code = ArrayList<String>()
        val p = ArrayList<Double>()
        val l = ArrayList<Double>()
        val d = ArrayList<Float>()
        val s = ArrayList<Float>()
        val lli = ArrayList<Int>()
        val fcn = ArrayList<Int>()
        val dropped = ArrayList<String>()

        for (m in event.measurements) {
            val k = m.constellationType.toLong() * 1000 + m.svid
            if ((svMax[k] ?: 0.0) - (svMin[k] ?: 0.0) > MAX_INTERFREQ_SVTIME_DIFF_NS) {
                continue // millisecond-ambiguity satellite (handled once for the drop list below)
            }
            val obs = converter.process(toRawMeas(clock, m)) ?: continue
            satid.add(obs.sat)
            code.add(obs.code)
            p.add(obs.pseudorange)
            l.add(obs.carrierPhase)
            d.add(obs.doppler.toFloat())
            s.add(obs.cn0.toFloat())
            lli.add(obs.slip)
            fcn.add(obs.fcn)
        }
        // Record the dropped satellites (distinct) for visibility.
        for ((k, lo) in svMin) {
            if ((svMax[k] ?: lo) - lo > MAX_INTERFREQ_SVTIME_DIFF_NS) {
                dropped.add("sys%d/sv%d".format(k / 1000, k % 1000))
            }
        }
        lastDropped = dropped
        if (dropped.isNotEmpty()) {
            Log.w("RtcmStreamer", "dropped cross-freq-inconsistent sats: $dropped")
        }
        if (satid.isEmpty()) return

        // Epoch time from the converter's held bias (set while processing above), so the epoch,
        // pseudorange and carrier phase all share the same clock reference.
        val (week, tow) = converter.epoch(clock.timeNanos.toDouble())

        // Builds obsd_t and (if a RINEX file is open) writes the RINEX epoch — done every epoch,
        // even when not connected (RINEX recording). The returned RTCM bytes are only sent/saved
        // while connected.
        val bytes = encoder.encodeMsm7(
            week, tow, staid,
            satid.toTypedArray(), code.toTypedArray(),
            p.toDoubleArray(), l.toDoubleArray(),
            d.toFloatArray(), s.toFloatArray(), lli.toIntArray(), fcn.toIntArray(),
        )
        if (uplink.connected && bytes.isNotEmpty()) {
            uplink.send(bytes)
            rtcmOut?.let { try { it.write(bytes); it.flush() } catch (_: Exception) {} }
            lastMsgBytes = bytes.size
            epochsSent++
        }
    }

    private fun toRawMeas(c: GnssClock, m: GnssMeasurement): ObsConverter.RawMeas =
        ObsConverter.RawMeas(
            timeNanos = c.timeNanos.toDouble(),
            fullBiasNanos = c.fullBiasNanos.toDouble(),
            biasNanos = if (c.hasBiasNanos()) c.biasNanos else 0.0,
            timeOffsetNanos = m.timeOffsetNanos,
            hardwareClockDiscontinuityCount = c.hardwareClockDiscontinuityCount,
            svid = m.svid,
            constellationType = m.constellationType,
            state = m.state,
            receivedSvTimeNanos = m.receivedSvTimeNanos.toDouble(),
            receivedSvTimeUncertaintyNanos = m.receivedSvTimeUncertaintyNanos.toDouble(),
            cn0DbHz = m.cn0DbHz,
            pseudorangeRateMetersPerSecond = m.pseudorangeRateMetersPerSecond,
            accumulatedDeltaRangeMeters = m.accumulatedDeltaRangeMeters,
            accumulatedDeltaRangeState = m.accumulatedDeltaRangeState,
            accumulatedDeltaRangeUncertaintyMeters = m.accumulatedDeltaRangeUncertaintyMeters,
            multipathIndicator = m.multipathIndicator,
            carrierFrequencyHz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null,
        )

    companion object {
        // Max allowed spread of ReceivedSvTimeNanos across a satellite's signals (1 us ~ 300 m).
        // Healthy satellites report identical transmit times across frequencies (spread = 0);
        // a millisecond-ambiguity shows up as a ms-level (~10^6 ns) spread.
        private const val MAX_INTERFREQ_SVTIME_DIFF_NS = 1000.0
    }
}
