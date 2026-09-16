package org.furo.rtcmstreamer.gnss

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * GnssLogger-compatible CSV (`Raw,` rows) output (toggled on/off).
 *
 * The column order follows the GnssLogger Raw row from google/gps-measurement-tools.
 * android_rinex parses the header line `# Raw,<col>,...` as column names and maps data rows
 * `Raw,<val>,...` by name (extra columns are ignored; a missing accessed column raises KeyError).
 * So we emit the full standard column set and leave unsupported/absent values empty.
 *
 * Output goes to the app-specific external dir (/sdcard/Android/data/<pkg>/files/gnsslog/).
 * Retrieve with `adb pull` and convert to RINEX with android_rinex to use as verification truth.
 */
class GnssLogger(private val dir: File) {

    private var writer: BufferedWriter? = null

    var currentFile: File? = null
        private set

    val isRunning: Boolean get() = writer != null

    fun start(stamp: String) {
        if (writer != null) return
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "gnss_log_$stamp.txt")
        val w = BufferedWriter(FileWriter(f))
        w.write(buildHeader())
        w.flush()
        writer = w
        currentFile = f
    }

    fun log(event: GnssMeasurementsEvent, utcTimeMillis: Long) {
        val w = writer ?: return
        val clock = event.clock
        val sb = StringBuilder()
        for (m in event.measurements) {
            appendRawRow(sb, clock, m, utcTimeMillis)
            sb.append('\n')
        }
        w.write(sb.toString())
        w.flush()
    }

    fun stop() {
        writer?.let {
            it.flush()
            it.close()
        }
        writer = null
    }

    private fun appendRawRow(
        sb: StringBuilder,
        c: GnssClock,
        m: GnssMeasurement,
        utcTimeMillis: Long,
    ) {
        // The column order here must match COLS exactly.
        sb.append("Raw,")
        sb.append(utcTimeMillis).append(',')                                   // utcTimeMillis
        sb.append(c.timeNanos).append(',')                                     // TimeNanos
        sb.append(if (c.hasLeapSecond()) c.leapSecond.toString() else "").append(',')
        sb.append(if (c.hasTimeUncertaintyNanos()) c.timeUncertaintyNanos.toString() else "").append(',')
        sb.append(if (c.hasFullBiasNanos()) c.fullBiasNanos.toString() else "").append(',')
        sb.append(if (c.hasBiasNanos()) c.biasNanos.toString() else "").append(',')
        sb.append(if (c.hasBiasUncertaintyNanos()) c.biasUncertaintyNanos.toString() else "").append(',')
        sb.append(if (c.hasDriftNanosPerSecond()) c.driftNanosPerSecond.toString() else "").append(',')
        sb.append(if (c.hasDriftUncertaintyNanosPerSecond()) c.driftUncertaintyNanosPerSecond.toString() else "").append(',')
        sb.append(c.hardwareClockDiscontinuityCount).append(',')               // HardwareClockDiscontinuityCount
        sb.append(m.svid).append(',')                                          // Svid
        sb.append(m.timeOffsetNanos).append(',')                               // TimeOffsetNanos
        sb.append(m.state).append(',')                                         // State
        sb.append(m.receivedSvTimeNanos).append(',')                           // ReceivedSvTimeNanos
        sb.append(m.receivedSvTimeUncertaintyNanos).append(',')                // ReceivedSvTimeUncertaintyNanos
        sb.append(m.cn0DbHz).append(',')                                       // Cn0DbHz
        sb.append(m.pseudorangeRateMetersPerSecond).append(',')                // PseudorangeRateMetersPerSecond
        sb.append(m.pseudorangeRateUncertaintyMetersPerSecond).append(',')     // PseudorangeRateUncertaintyMetersPerSecond
        sb.append(m.accumulatedDeltaRangeState).append(',')                    // AccumulatedDeltaRangeState
        sb.append(m.accumulatedDeltaRangeMeters).append(',')                   // AccumulatedDeltaRangeMeters
        sb.append(m.accumulatedDeltaRangeUncertaintyMeters).append(',')        // AccumulatedDeltaRangeUncertaintyMeters
        sb.append(if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toString() else "").append(',')
        sb.append(',')                                                         // CarrierCycles (deprecated, unused)
        sb.append(',')                                                         // CarrierPhase (deprecated, unused)
        sb.append(',')                                                         // CarrierPhaseUncertainty (deprecated, unused)
        sb.append(m.multipathIndicator).append(',')                            // MultipathIndicator
        sb.append(if (m.hasSnrInDb()) m.snrInDb.toString() else "").append(',')
        sb.append(m.constellationType).append(',')                            // ConstellationType
        sb.append(',')                                                         // AgcDb (unused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sb.append(if (m.hasBasebandCn0DbHz()) m.basebandCn0DbHz.toString() else "").append(',')
            sb.append(if (m.hasFullInterSignalBiasNanos()) m.fullInterSignalBiasNanos.toString() else "").append(',')
            sb.append(if (m.hasFullInterSignalBiasUncertaintyNanos()) m.fullInterSignalBiasUncertaintyNanos.toString() else "").append(',')
            sb.append(if (m.hasSatelliteInterSignalBiasNanos()) m.satelliteInterSignalBiasNanos.toString() else "").append(',')
            sb.append(if (m.hasSatelliteInterSignalBiasUncertaintyNanos()) m.satelliteInterSignalBiasUncertaintyNanos.toString() else "").append(',')
        } else {
            sb.append(",,,,,")                                                 // leave the 5 columns above empty
        }
        sb.append(if (m.hasCodeType()) m.codeType else "").append(',')         // CodeType
        sb.append("")                                                          // ChipsetElapsedRealtimeNanos (unused)
    }

    companion object {
        // GnssLogger Raw row column order (including the leading "Raw").
        private const val COLS =
            "Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos," +
                "BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond," +
                "HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos," +
                "ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond," +
                "PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState," +
                "AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz," +
                "CarrierCycles,CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb," +
                "ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos," +
                "FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos," +
                "SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos"

        /**
         * Header in GnssLogger style. The `# Version: ... Model: ...` line is required by
         * android_rinex (it reads the Model parameter), so emit it with the real device info.
         */
        private fun buildHeader(): String = buildString {
            append("# Header Description:\n")
            append("# \n")
            append("# GnssLogger-compatible raw log (org.furo.rtcmstreamer)\n")
            append(
                "# Version: 1.0.0 Platform: ${Build.VERSION.SDK_INT} " +
                    "Manufacturer: ${Build.MANUFACTURER} Model: ${Build.MODEL}\n"
            )
            append("# \n")
            append("# $COLS\n")
            append("# \n")
        }
    }
}
