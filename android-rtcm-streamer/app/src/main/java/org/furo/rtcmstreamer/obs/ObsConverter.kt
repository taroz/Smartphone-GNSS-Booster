package org.furo.rtcmstreamer.obs

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Front end: observable conversion, ported from android_rinex's gnsslogger.py (process()).
 *
 * Reconstructs pseudorange, carrier phase (cycles), Doppler (cycles/s) and CN0 from one
 * raw GNSS measurement plus its clock fields. Pure Kotlin (no Android deps) so it is unit-testable
 * (see app/src/test); GPS L1 output was verified against android_rinex RINEX as truth.
 *
 * Handles every constellation the Android API reports: GPS, GLONASS (time-of-day -> GPST),
 * Galileo, BeiDou (BDT -> GPST), QZSS and SBAS.
 *
 * Signal -> code mapping lives in [SignalCodeMap] (device-dependent).
 */
class ObsConverter(
    private val timeAdj: Double = 1e-7,
    private val pseudorangeBias: Double = 0.0,
    private val slipMask: Int = 3,
    private val fixBias: Boolean = false,
    private val removeClockFromPhase: Boolean = false,
    private val model: String = "Pixel 7 Pro",
) {
    // Held clock bias state, mirroring the globals in gnsslogger.py.
    private var clockDiscontinuities: Int = -1
    private var fullBiasNanos: Double = 0.0
    private var biasNanos: Double = 0.0
    // Clock baseline latched at each discontinuity (carrier-phase / ADR anchor). Used only when
    // removeClockFromPhase is set; see the clockDeltaSeconds note in process().
    private var baseFullBiasNanos: Double = 0.0
    private var baseBiasNanos: Double = 0.0

    /** Raw inputs needed for conversion (parsed as Double like Python's float()). */
    data class RawMeas(
        val timeNanos: Double,
        val fullBiasNanos: Double,
        val biasNanos: Double,
        val timeOffsetNanos: Double,
        val hardwareClockDiscontinuityCount: Int,
        val svid: Int,
        val constellationType: Int,
        val state: Int,
        val receivedSvTimeNanos: Double,
        val receivedSvTimeUncertaintyNanos: Double,
        val cn0DbHz: Double,
        val pseudorangeRateMetersPerSecond: Double,
        val accumulatedDeltaRangeMeters: Double,
        val accumulatedDeltaRangeState: Int,
        val accumulatedDeltaRangeUncertaintyMeters: Double,
        val multipathIndicator: Int,
        val carrierFrequencyHz: Double?,
    )

    /** One finalized observable (RINEX-style values). */
    data class Obs(
        val sat: String,       // e.g. "G10"
        val code: String,      // e.g. "1C"
        val pseudorange: Double,
        val carrierPhase: Double,
        val doppler: Double,
        val cn0: Double,
        val slip: Int,
        val lstd: Int,
        val pstd: Int,
        val fcn: Int = 0,      // GLONASS frequency channel number (-7..+6); 0 for other systems
    )

    /**
     * Process one measurement. Returns null if the satellite is skipped or the measurement is
     * filtered out (same criteria as android_rinex filter_obs, with multipath rejected only when
     * MultipathIndicator == PRESENT(1); UNKNOWN(0) and NOT_PRESENT(2) pass).
     */
    fun process(m: RawMeas): Obs? {
        val satName = satName(m) ?: return null
        val constLetter = constellationLetter(m.constellationType)
        val obsCode = SignalCodeMap.obsCode(m.carrierFrequencyHz, constLetter)

        // Clock bias handling (fixBias=false => reload each measurement).
        var zeroCarrierPhase = false
        if (clockDiscontinuities != m.hardwareClockDiscontinuityCount) {
            fullBiasNanos = m.fullBiasNanos
            biasNanos = m.biasNanos
            // Re-anchor the clock baseline (and reset the phase) at every discontinuity, including
            // the first measurement.
            baseFullBiasNanos = m.fullBiasNanos
            baseBiasNanos = m.biasNanos
            if (clockDiscontinuities > 0) zeroCarrierPhase = true
        } else if (!fixBias) {
            fullBiasNanos = m.fullBiasNanos
            biasNanos = m.biasNanos
        }
        clockDiscontinuities = m.hardwareClockDiscontinuityCount

        // Receiver-clock offset between the bias used for the pseudorange this epoch and the
        // baseline the carrier phase (ADR) is anchored to. With fixBias=true the used bias IS the
        // baseline, so this is 0. With fixBias=false the pseudorange follows the live clock; when
        // removeClockFromPhase is set we subtract the same offset (cycles = seconds * freq) from
        // the phase, so P and lambda*L share one clock reference (no drift => no false MSM
        // loss-of-lock). Left at 0 (default) the phase stays raw ADR/lambda, matching android_rinex
        // for the verification harness.
        val clockDeltaSeconds = if (removeClockFromPhase) {
            ((fullBiasNanos + biasNanos) - (baseFullBiasNanos + baseBiasNanos)) * NS_TO_S
        } else {
            0.0
        }

        val timeNanos = m.timeNanos

        // GPS week number and receiver clock epoch (seconds of week).
        val gpsWeek = floor(-fullBiasNanos * NS_TO_S / GPS_WEEKSECS)
        val localEstGpsTime = timeNanos - (fullBiasNanos + biasNanos)
        val gpsSow = localEstGpsTime * NS_TO_S - gpsWeek * GPS_WEEKSECS

        // Adjust timestamp to the nearest rounded interval.
        val toff = if (timeAdj > 0.0) {
            (gpsSow / timeAdj - (gpsSow / timeAdj + 0.5).toLong()) * timeAdj
        } else {
            0.0
        }

        val tRxSeconds = gpsSow - m.timeOffsetNanos * NS_TO_S

        // GLONASS frequency channel number (FCN) from the measured carrier frequency.
        val fcn = if (m.constellationType == CONSTELLATION_GLONASS && m.carrierFrequencyHz != null) {
            ((m.carrierFrequencyHz - FREQ1_GLO) / DFREQ1_GLO).roundToLong().toInt()
        } else {
            0
        }

        val freq = nominalFrequency(m.constellationType, m.carrierFrequencyHz, fcn)
        val wavelength = SPEED_OF_LIGHT / freq

        if (!passesFilter(m)) return null

        // Transmit time (constellation dependent).
        val tTxSeconds = when (m.constellationType) {
            CONSTELLATION_GLONASS -> {
                // GLONASS broadcasts time-of-day (Moscow); convert to GPS time-of-week.
                val gpssowAdj = gpsSow - toff
                val gpstEpoch = GPS_EPOCH.plusSeconds(gpsWeek.toLong() * GPS_WEEKSECS.toLong() + floor(gpssowAdj).toLong())
                glotToGpst(gpstEpoch, m.receivedSvTimeNanos * NS_TO_S)
            }
            CONSTELLATION_BEIDOU -> m.receivedSvTimeNanos * NS_TO_S + BDST_TO_GPST
            else -> m.receivedSvTimeNanos * NS_TO_S
        }
        val tau = checkWeekCrossover(tRxSeconds, tTxSeconds)

        val pru = minOf(999.0, m.receivedSvTimeUncertaintyNanos / 1e9 * SPEED_OF_LIGHT)
        var range = if (pru < MAX_PRU) tau * SPEED_OF_LIGHT - pseudorangeBias else 0.0

        var adru = minOf(999.0, m.accumulatedDeltaRangeUncertaintyMeters)
        var slip = 0
        var cphase = 0.0
        val adrSlip = checkAdrState(m.accumulatedDeltaRangeState)
        if (adrSlip != null) {
            slip = adrSlip
            cphase = if (adru < MAX_ADRU && !zeroCarrierPhase) {
                m.accumulatedDeltaRangeMeters / wavelength - clockDeltaSeconds * freq
            } else {
                slip = slip or 1
                0.0
            }
        }

        val doppler = -m.pseudorangeRateMetersPerSecond / wavelength
        val cn0 = m.cn0DbHz

        // Adjust phase uncertainty by LLI flags.
        if (slip and 1 != 0) adru *= 10 else if (slip and 2 != 0) adru *= 10

        // Refer range/phase to the integer epoch and compute RTKLIB-style std indices.
        var pstd = 0
        var lstd = 0
        if (range != 0.0) {
            pstd = clampStd((log2(pru * 100) - 5).roundToLong())
            if (toff != 0.0) range -= toff * SPEED_OF_LIGHT
        }
        if (cphase != 0.0) {
            lstd = clampStd(((adru / 0.2) / 0.004).roundToLong())
            if (toff != 0.0) cphase -= toff * freq
        }

        slip = slip and slipMask
        range = maxOf(range, 0.0)

        return Obs(satName, obsCode, range, cphase, doppler, cn0, slip, lstd, pstd, fcn)
    }

    /**
     * Epoch (GPS week, time-of-week) computed from the SAME held clock bias that [process] used
     * for this epoch's observables. Call after processing the epoch's measurements. With
     * fixBias=true this uses the held base bias, keeping the epoch time, pseudorange and carrier
     * phase on one consistent clock reference (so P - lambda*L carries no receiver clock and the
     * MSM lock-time slip detection is not tripped by clock drift).
     */
    fun epoch(timeNanos: Double): Pair<Int, Double> {
        val gpsWeek = floor(-fullBiasNanos * NS_TO_S / GPS_WEEKSECS)
        val localEst = timeNanos - (fullBiasNanos + biasNanos)
        val gpsSow = localEst * NS_TO_S - gpsWeek * GPS_WEEKSECS
        val toff = if (timeAdj > 0.0) {
            (gpsSow / timeAdj - (gpsSow / timeAdj + 0.5).toLong()) * timeAdj
        } else {
            0.0
        }
        return gpsWeek.toInt() to (gpsSow - toff)
    }

    // --- helpers (ported from gnsslogger.py) ---

    /** Returns slip flags, or null when ADR is invalid (carrier phase unusable). */
    private fun checkAdrState(adrState: Int): Int? {
        var slip = 0
        if (model != "MI 8" && (adrState and ADR_STATE_HALF_CYCLE_RESOLVED) == 0) slip = slip or 2
        return if ((adrState and ADR_STATE_CYCLE_SLIP) != 0) {
            slip or 1
        } else if ((adrState and ADR_STATE_VALID) == 0) {
            null // ADR_STATE_VALID not set
        } else {
            slip
        }
    }

    private fun passesFilter(m: RawMeas): Boolean {
        val state = m.state
        if (state and (STATE_CODE_LOCK or STATE_GAL_E1BC_CODE_LOCK) == 0) return false
        if (m.constellationType == CONSTELLATION_GLONASS) {
            if (state and (STATE_GLO_TOD_DECODED or STATE_GLO_TOD_KNOWN) == 0) return false
        } else {
            if (state and (STATE_TOW_DECODED or STATE_TOW_KNOWN) == 0) return false
        }
        if (m.constellationType == CONSTELLATION_UNKNOWN) return false
        if (m.cn0DbHz < MIN_CN0) return false
        if (m.receivedSvTimeUncertaintyNanos > MAX_SVTIME_UNCERTAINTY) return false
        // Multipath: reject only PRESENT(1); UNKNOWN(0) and NOT_PRESENT(2) pass.
        if (m.multipathIndicator == MULTIPATH_PRESENT) return false
        return true
    }

    /** Nominal carrier frequency used for wavelength (not the measured CarrierFrequencyHz). */
    private fun nominalFrequency(constellationType: Int, carrierFrequencyHz: Double?, fcn: Int): Double {
        return when (SignalCodeMap.band(carrierFrequencyHz)) {
            1 -> when (constellationType) {
                CONSTELLATION_GLONASS -> FREQ1_GLO + fcn * DFREQ1_GLO // G1 channel frequency
                else -> FREQ1 // GPS/QZSS L1, Galileo E1, BeiDou B1C (all 1575.42 MHz)
            }
            // BeiDou B1I. Use the nominal frequency, NOT the measured CarrierFrequencyHz: the
            // Android value is a float32 and 1561098000 Hz is not exactly representable (nearest
            // is 1561097984, -16 Hz off), which would bias the phase by ADR*(-16)/c per satellite
            // and mismatch the RTKLIB encoder/decoder (which use FREQ1_CMP = 1561098000).
            2 -> FREQ1_BDS
            5 -> FREQ5
            else -> carrierFrequencyHz ?: FREQ1
        }
    }

    /**
     * Convert a GLONASS time-of-day to GPS time-of-week (seconds), ported from android_rinex
     * glot_to_gpst(). The current GPST epoch fixes the GLONASS calendar day; GLONASS runs on
     * Moscow time (UTC+3), so tow = day-of-week*86400 + tod - GLOT_TO_UTC + leap.
     */
    private fun glotToGpst(gpstEpoch: java.time.LocalDateTime, todSeconds: Double): Double {
        val todSec = todSeconds.toLong() // integer part, matches Python int(modf)
        val gloEpoch = gpstEpoch.withNano(0)
            .plusHours(3).minusSeconds(CURRENT_GPS_LEAP_SECOND.toLong())
        val gloTod = gloEpoch.toLocalDate().atStartOfDay().plusSeconds(todSec)
        val dayOfWeekSec = gloTod.dayOfWeek.value * DAYSEC // Mon=1..Sun=7, as Python isoweekday()
        return dayOfWeekSec + todSeconds - GLOT_TO_UTC + CURRENT_GPS_LEAP_SECOND
    }

    private fun satName(m: RawMeas): String? {
        val c = constellationLetter(m.constellationType)
        var svid = m.svid
        if (c == 'J') svid -= 192
        // Skip GLONASS measurements reported by FCN instead of OSN.
        if (svid > 50 && c == 'R') return null
        return "%c%02d".format(c, svid)
    }

    private fun constellationLetter(type: Int): Char = when (type) {
        CONSTELLATION_GPS -> 'G'
        CONSTELLATION_SBAS -> 'S'
        CONSTELLATION_GLONASS -> 'R'
        CONSTELLATION_QZSS -> 'J'
        CONSTELLATION_BEIDOU -> 'C'
        CONSTELLATION_GALILEO -> 'E'
        else -> 'X'
    }

    private fun checkWeekCrossover(tRxSeconds: Double, tTxSeconds: Double): Double {
        var tau = tRxSeconds - tTxSeconds
        if (abs(tau) > GPS_WEEKSECS / 2.0) {
            val delSec = (tau / GPS_WEEKSECS).roundToLong() * GPS_WEEKSECS
            val rhoSec = tau - delSec
            tau = if (rhoSec > 10) 0.0 else rhoSec
        }
        return tau
    }

    private fun clampStd(v: Long): Int = maxOf(0L, minOf(9L, v)).toInt()

    private fun log2(x: Double): Double = ln(x) / LN2

    companion object {
        const val SPEED_OF_LIGHT = 299792458.0
        const val GPS_WEEKSECS = 604800.0
        const val NS_TO_S = 1.0e-9
        const val BDST_TO_GPST = 14.0

        // GLONASS time conversion (android_rinex glot_to_gpst).
        const val CURRENT_GPS_LEAP_SECOND = 18
        const val GLOT_TO_UTC = 10800 // GLONASS (Moscow, UTC+3) to UTC, seconds
        const val DAYSEC = 86400
        const val DFREQ1_GLO = 0.56250e6 // GLONASS G1 channel spacing (Hz)
        private val GPS_EPOCH: java.time.LocalDateTime = java.time.LocalDateTime.of(1980, 1, 6, 0, 0, 0)

        const val MAX_PRU = 150.0
        const val MAX_ADRU = 0.1
        const val MIN_CN0 = 20.0
        const val MAX_SVTIME_UNCERTAINTY = 500.0

        const val FREQ1 = 1.57542e9
        const val FREQ1_GLO = 1.60200e9
        const val FREQ1_BDS = 1.561098e9
        const val FREQ5 = 1.17645e9

        private val LN2 = ln(2.0)

        // Constellation types (GnssStatus.CONSTELLATION_*).
        const val CONSTELLATION_UNKNOWN = 0
        const val CONSTELLATION_GPS = 1
        const val CONSTELLATION_SBAS = 2
        const val CONSTELLATION_GLONASS = 3
        const val CONSTELLATION_QZSS = 4
        const val CONSTELLATION_BEIDOU = 5
        const val CONSTELLATION_GALILEO = 6

        // GnssMeasurement state flags.
        const val STATE_CODE_LOCK = 0x00000001
        const val STATE_TOW_DECODED = 0x00000008
        const val STATE_TOW_KNOWN = 0x00004000
        const val STATE_GLO_TOD_DECODED = 0x00000080
        const val STATE_GLO_TOD_KNOWN = 0x00008000
        const val STATE_GAL_E1BC_CODE_LOCK = 0x00000400

        // ADR state flags.
        const val ADR_STATE_VALID = 0x00000001
        const val ADR_STATE_CYCLE_SLIP = 0x00000004
        const val ADR_STATE_HALF_CYCLE_RESOLVED = 0x00000008

        // MultipathIndicator values.
        const val MULTIPATH_PRESENT = 1

        /**
         * Epoch GPST (week, time-of-week rounded to [timeAdj]) from the GnssClock fields.
         * Mirrors the per-measurement computation in [process] (with fixBias=false the held bias
         * equals the clock's own values), so the returned tow matches the toff-adjusted P/L.
         */
        fun epochTime(
            timeNanos: Double,
            fullBiasNanos: Double,
            biasNanos: Double,
            timeAdj: Double = 1e-7,
        ): Pair<Int, Double> {
            val gpsWeek = floor(-fullBiasNanos * NS_TO_S / GPS_WEEKSECS)
            val localEst = timeNanos - (fullBiasNanos + biasNanos)
            val gpsSow = localEst * NS_TO_S - gpsWeek * GPS_WEEKSECS
            val toff = if (timeAdj > 0.0) {
                (gpsSow / timeAdj - (gpsSow / timeAdj + 0.5).toLong()) * timeAdj
            } else {
                0.0
            }
            return gpsWeek.toInt() to (gpsSow - toff)
        }
    }
}
