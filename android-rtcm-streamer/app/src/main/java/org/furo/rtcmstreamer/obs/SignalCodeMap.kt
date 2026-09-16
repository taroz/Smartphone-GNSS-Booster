package org.furo.rtcmstreamer.obs

import kotlin.math.roundToInt

/**
 * Signal -> observation code (RINEX 3) mapping.
 * Ported from android_rinex gnsslogger.py (get_rnx_band_from_freq / get_rnx_attr / get_obscode).
 *
 * Kept in its own file so it can be swapped out, since it is device-dependent
 * (may differ on non-Pixel / future devices).
 */
object SignalCodeMap {

    private const val FREQ_QUANT = 10.23e6

    /**
     * RINEX frequency band from the carrier frequency.
     * 1 = L1/E1 (ifreq >= 154), 5 = L5/E5a (ifreq == 115), 2 = BDS B1I (ifreq == 153).
     * A null/absent frequency is treated as GPS L1 (ifreq 154), matching android_rinex.
     */
    fun band(carrierFrequencyHz: Double?): Int {
        val ifreq = if (carrierFrequencyHz == null) 154 else (carrierFrequencyHz / FREQ_QUANT).roundToInt()
        return when {
            ifreq >= 154 -> 1
            ifreq == 115 -> 5
            ifreq == 153 -> 2
            else -> throw IllegalArgumentException(
                "Cannot map carrier frequency to a RINEX band: $carrierFrequencyHz (ifreq=$ifreq)"
            )
        }
    }

    /**
     * RINEX 3 attribute letter. Defaults to 'C' for L1/E1, 'I' for BDS B1I.
     * BeiDou (pilot tracking): B1C->'P' (1P), B2a->'P' (5P). GPS/Galileo/QZSS L5/E5a -> 'Q' (pilot).
     * Galileo E1 -> 'C' (1C): we always emit 1C rather than disambiguating E1B/E1C by tracking
     * state, since most reference networks/bases and GnssLogger report E1 as 1C; matching the
     * majority maximizes rover-base pairing in RTK (the observable value is identical either way).
     *
     * Note: BeiDou uses D/P/X naming for B1C (1D/1P/1X) and B2a (5D/5P/5X), not the GPS-style
     * 1C / 5I/5Q. RTKLIB's BeiDou MSM signal table (msm_sig_cmp) has no "1C" or "5Q", so those
     * codes are silently dropped by the MSM encoder. We track the pilot component (no nav-data
     * decode), so B1C->"1P" and B2a->"5P". This deviates from android_rinex (emits "5Q"/"1C" for
     * BeiDou, which would be dropped).
     */
    fun attr(band: Int, constellationLetter: Char): Char {
        var a = 'C'
        if (band == 1 && constellationLetter == 'C') a = 'P' // BeiDou B1C pilot (1P)
        if (band == 5) a = if (constellationLetter == 'C') 'P' else 'Q'
        if (band == 2 && constellationLetter == 'C') a = 'I'
        return a
    }

    /** RINEX 3 observation code, e.g. "1C", "5Q", "2I". */
    fun obsCode(carrierFrequencyHz: Double?, constellationLetter: Char): String {
        val b = band(carrierFrequencyHz)
        return "$b${attr(b, constellationLetter)}"
    }
}
