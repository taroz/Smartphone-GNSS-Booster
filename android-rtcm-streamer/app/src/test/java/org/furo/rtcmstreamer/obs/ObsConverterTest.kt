package org.furo.rtcmstreamer.obs

import org.furo.rtcmstreamer.obs.ObsConverter.Companion.ADR_STATE_HALF_CYCLE_RESOLVED
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.ADR_STATE_VALID
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.CONSTELLATION_BEIDOU
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.CONSTELLATION_GLONASS
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.CONSTELLATION_GPS
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.CONSTELLATION_QZSS
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.FREQ1
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.SPEED_OF_LIGHT
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.STATE_CODE_LOCK
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.STATE_GLO_TOD_DECODED
import org.furo.rtcmstreamer.obs.ObsConverter.Companion.STATE_TOW_DECODED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObsConverterTest {

    // Receiver clock: GPS week 2422, tow 478838.0 s, expressed only through FullBiasNanos
    // (TimeNanos = 0, BiasNanos = 0). -FullBiasNanos = (2422*604800 + 478838) * 1e9 ns.
    private val week = 2422
    private val tow = 478838.0
    private val fullBiasNanos = -(week * 604800.0 + tow) * 1e9

    private fun gps(
        svid: Int = 10,
        travelTimeSec: Double = 0.07,
        cn0: Double = 45.0,
        state: Int = STATE_CODE_LOCK or STATE_TOW_DECODED,
        adrState: Int = ADR_STATE_VALID or ADR_STATE_HALF_CYCLE_RESOLVED,
        adrMeters: Double = 1000.0,
        adrUncertainty: Double = 0.01,
        multipath: Int = 0,
        constellation: Int = CONSTELLATION_GPS,
        carrierFrequencyHz: Double? = FREQ1,
    ) = ObsConverter.RawMeas(
        timeNanos = 0.0,
        fullBiasNanos = fullBiasNanos,
        biasNanos = 0.0,
        timeOffsetNanos = 0.0,
        hardwareClockDiscontinuityCount = 0,
        svid = svid,
        constellationType = constellation,
        state = state,
        receivedSvTimeNanos = (tow - travelTimeSec) * 1e9,
        receivedSvTimeUncertaintyNanos = 10.0,
        cn0DbHz = cn0,
        pseudorangeRateMetersPerSecond = -500.0,
        accumulatedDeltaRangeMeters = adrMeters,
        accumulatedDeltaRangeState = adrState,
        accumulatedDeltaRangeUncertaintyMeters = adrUncertainty,
        multipathIndicator = multipath,
        carrierFrequencyHz = carrierFrequencyHz,
    )

    @Test
    fun epochTimeFromClockFields() {
        val (w, t) = ObsConverter.epochTime(0.0, fullBiasNanos, 0.0)
        assertEquals(week, w)
        assertEquals(tow, t, 1e-7)
    }

    @Test
    fun gpsL1PseudorangeIsReferredToTheReportedEpoch() {
        val conv = ObsConverter(fixBias = true)
        val m = gps(travelTimeSec = 0.07)
        val obs = conv.process(m)
        assertNotNull(obs)
        obs!!
        assertEquals("G10", obs.sat)
        assertEquals("1C", obs.code)

        // P = (epoch tow - transmit time) * c, with the epoch the converter itself reports.
        val (_, epochTow) = conv.epoch(m.timeNanos)
        val tTx = m.receivedSvTimeNanos * 1e-9
        assertEquals((epochTow - tTx) * SPEED_OF_LIGHT, obs.pseudorange, 0.01)

        // Doppler = -range rate / wavelength.
        assertEquals(500.0 / (SPEED_OF_LIGHT / FREQ1), obs.doppler, 1e-6)
        assertEquals(45.0, obs.cn0, 0.0)
        assertEquals(0, obs.slip)
        assertNotEquals(0.0, obs.carrierPhase, 0.0)
    }

    @Test
    fun invalidAdrZeroesTheCarrierPhaseAndFlagsSlip() {
        val obs = ObsConverter().process(gps(adrState = 0))
        assertNotNull(obs)
        assertEquals(0.0, obs!!.carrierPhase, 0.0)

        // Half-cycle unresolved -> slip bit 2 is set on the phase.
        val half = ObsConverter().process(gps(adrState = ADR_STATE_VALID))
        assertEquals(2, half!!.slip and 2)
    }

    @Test
    fun qualityFilterRejectsBadMeasurements() {
        assertNull(ObsConverter().process(gps(cn0 = 10.0)))                          // low C/N0
        assertNull(ObsConverter().process(gps(state = STATE_CODE_LOCK)))              // no TOW
        assertNull(ObsConverter().process(gps(multipath = 1)))                        // multipath present
        assertNotNull(ObsConverter().process(gps(multipath = 2)))                     // multipath not present
    }

    @Test
    fun satelliteNaming() {
        assertEquals("J01", ObsConverter().process(gps(svid = 193, constellation = CONSTELLATION_QZSS))!!.sat)
        assertEquals("C06", ObsConverter().process(gps(svid = 6, constellation = CONSTELLATION_BEIDOU))!!.sat)
        // GLONASS reported by FCN (svid > 50) instead of orbital slot number is skipped.
        assertNull(
            ObsConverter().process(
                gps(
                    svid = 100, constellation = CONSTELLATION_GLONASS,
                    state = STATE_CODE_LOCK or STATE_GLO_TOD_DECODED,
                    carrierFrequencyHz = 1602.0e6,
                )
            )
        )
    }

    @Test
    fun glonassFrequencyChannelNumber() {
        val obs = ObsConverter().process(
            gps(
                svid = 5, constellation = CONSTELLATION_GLONASS,
                state = STATE_CODE_LOCK or STATE_GLO_TOD_DECODED,
                carrierFrequencyHz = 1602.0e6 + 1 * 0.5625e6,
            )
        )
        assertNotNull(obs)
        assertEquals("R05", obs!!.sat)
        assertEquals(1, obs.fcn)
        assertEquals("1C", obs.code)
    }
}
