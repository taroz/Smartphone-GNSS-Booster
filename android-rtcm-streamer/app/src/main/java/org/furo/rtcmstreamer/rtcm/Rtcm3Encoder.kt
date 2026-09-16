package org.furo.rtcmstreamer.rtcm

/**
 * Back-end caller layer (JNI wrapper to RTKLIB gen_rtcm3).
 * Arrays of finalized observables -> JNI -> group into obsd_t + gen_rtcm3() on the C side
 * -> RTCM3 byte stream.
 *
 * Emits one MSM7 message per present constellation (GPS 1077, GLONASS 1087, Galileo 1097,
 * QZSS 1117, BeiDou 1127), concatenated for one epoch.
 */
class Rtcm3Encoder {

    /**
     * Encode one mixed-constellation epoch to concatenated RTCM3 MSM7 messages.
     *
     * Each array entry is one satellite+signal observation; multiple signals on the same
     * satellite (e.g. L1 and L5) are grouped into one obsd_t on the C side by [satid].
     *
     * @param week GPS week
     * @param tow  GPS time of week (seconds)
     * @param staid RTCM reference station id
     * @param satid RINEX-3 satellite ids, e.g. "G10", "E05", "C06", "J01"
     * @param code  RINEX-3 observation codes, e.g. "1C", "5Q", "2I", parallel to [satid]
     * @param pseudorange pseudorange (m), parallel to [satid]
     * @param carrier carrier phase (cycles), parallel to [satid]
     * @param doppler Doppler (Hz), parallel to [satid]
     * @param snr signal strength (dBHz), parallel to [satid]
     * @param lli loss-of-lock indicator, parallel to [satid]
     * @param fcn GLONASS frequency channel number (-7..+6), parallel to [satid]; 0 for other systems
     * @return the concatenated RTCM3 message bytes (empty if generation failed)
     */
    external fun encodeMsm7(
        week: Int,
        tow: Double,
        staid: Int,
        satid: Array<String>,
        code: Array<String>,
        pseudorange: DoubleArray,
        carrier: DoubleArray,
        doppler: FloatArray,
        snr: FloatArray,
        lli: IntArray,
        fcn: IntArray,
    ): ByteArray

    /** Encode sample observables, decode them back, and compare. Result starts with "OK" on success. */
    external fun selfTestRoundTrip(): String

    /**
     * Open a RINEX 3.04 OBS file at [path]. While open, each [encodeMsm7] epoch is also written to
     * it (from the same obsd_t fed to gen_rtcm3 -> a faithful record of the transmitted observations).
     */
    external fun rinexOpen(path: String)

    /** Close the RINEX OBS file opened by [rinexOpen]. */
    external fun rinexClose()

    companion object {
        init {
            System.loadLibrary("rtcmjni")
        }
    }
}
