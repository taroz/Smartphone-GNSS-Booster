package org.furo.rtcmstreamer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SolParserTest {

    private val line =
        "2026/06/12 13:00:38.000   35.123456789  139.123456789    45.1234   1  12" +
            "   0.0100   0.0200   0.0300  -0.0010   0.0020  -0.0030   1.00   15.5"

    @Test
    fun parsesLlhLine() {
        val s = SolParser.parse(line)
        assertNotNull(s)
        s!!
        assertEquals("2026/06/12 13:00:38.000", s.timeStr)
        assertEquals(35.123456789, s.lat, 1e-12)
        assertEquals(139.123456789, s.lon, 1e-12)
        assertEquals(45.1234, s.height, 1e-9)
        assertEquals(1, s.q)
        assertEquals(12, s.ns)
        assertEquals(0.01, s.sdn, 1e-9)
        assertEquals(0.02, s.sde, 1e-9)
        assertEquals(0.03, s.sdu, 1e-9)
        assertEquals(1.0, s.age, 1e-9)
        assertEquals(15.5, s.ratio, 1e-9)
    }

    @Test
    fun skipsHeaderAndBlankLines() {
        assertNull(SolParser.parse(""))
        assertNull(SolParser.parse("   "))
        assertNull(SolParser.parse("% program   : RTKRCV ver.demo5"))
        assertNull(SolParser.parse("%  GPST          latitude(deg) longitude(deg)  height(m)   Q  ns"))
    }

    @Test
    fun rejectsShortOrMalformedLines() {
        assertNull(SolParser.parse("2026/06/12 13:00:38.000 35.0 139.0 45.0 1 12"))
        assertNull(SolParser.parse(line.replace("35.123456789", "abc")))
    }
}
