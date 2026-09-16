package org.furo.rtcmstreamer.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalCodeMapTest {

    @Test
    fun bandFromCarrierFrequency() {
        assertEquals(1, SignalCodeMap.band(1575.42e6))   // GPS L1 / Galileo E1 / BeiDou B1C
        assertEquals(1, SignalCodeMap.band(1602.0e6 + 7 * 0.5625e6)) // GLONASS G1, FCN +7
        assertEquals(1, SignalCodeMap.band(1602.0e6 - 7 * 0.5625e6)) // GLONASS G1, FCN -7
        assertEquals(5, SignalCodeMap.band(1176.45e6))   // L5 / E5a / B2a
        assertEquals(2, SignalCodeMap.band(1561.098e6))  // BeiDou B1I
        assertEquals(1, SignalCodeMap.band(null))        // absent frequency -> L1
    }

    @Test
    fun unknownFrequencyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SignalCodeMap.band(1227.60e6) } // L2
    }

    @Test
    fun obsCodePerConstellation() {
        assertEquals("1C", SignalCodeMap.obsCode(1575.42e6, 'G'))
        assertEquals("5Q", SignalCodeMap.obsCode(1176.45e6, 'G'))
        assertEquals("1C", SignalCodeMap.obsCode(1575.42e6, 'E'))
        assertEquals("5Q", SignalCodeMap.obsCode(1176.45e6, 'E'))
        assertEquals("1C", SignalCodeMap.obsCode(1575.42e6, 'J'))
        assertEquals("1C", SignalCodeMap.obsCode(1602.0e6, 'R'))
        // BeiDou uses D/P/X naming for B1C / B2a and I for B1I (RTKLIB MSM signal table).
        assertEquals("1P", SignalCodeMap.obsCode(1575.42e6, 'C'))
        assertEquals("5P", SignalCodeMap.obsCode(1176.45e6, 'C'))
        assertEquals("2I", SignalCodeMap.obsCode(1561.098e6, 'C'))
    }
}
