package org.furo.rtcmstreamer.geo

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class GeoConvertTest {

    @Test
    fun ecefOfEquatorPrimeMeridianIsSemiMajorAxis() {
        val p = GeoConvert.pos2ecef(0.0, 0.0, 0.0)
        assertEquals(6378137.0, p[0], 1e-6)
        assertEquals(0.0, p[1], 1e-6)
        assertEquals(0.0, p[2], 1e-6)
    }

    @Test
    fun ecefOfPoleIsSemiMinorAxis() {
        val p = GeoConvert.pos2ecef(90.0, 0.0, 0.0)
        val b = 6378137.0 * (1.0 - 1.0 / 298.257223563)
        assertEquals(0.0, sqrt(p[0] * p[0] + p[1] * p[1]), 1e-6)
        assertEquals(b, p[2], 1e-6)
    }

    @Test
    fun enuOfOriginIsZero() {
        val en = GeoConvert.enu(35.0, 139.0, 50.0, 35.0, 139.0, 50.0)
        assertEquals(0f, en[0], 1e-6f)
        assertEquals(0f, en[1], 1e-6f)
    }

    @Test
    fun smallNorthOffsetMatchesMeridionalRadius() {
        // 1e-5 deg of latitude at 35 N: M * dphi with M = a(1-e2)/(1-e2 sin^2)^1.5 = 6356436 m.
        val en = GeoConvert.enu(35.0, 139.0, 0.0, 35.0 + 1e-5, 139.0, 0.0)
        assertEquals(0.0, en[0].toDouble(), 1e-3)
        assertEquals(1.10941, en[1].toDouble(), 2e-3)
    }

    @Test
    fun smallEastOffsetMatchesPrimeVerticalRadius() {
        // 1e-5 deg of longitude at 35 N: N cos(phi) dlambda with N = a/sqrt(1-e2 sin^2) = 6385174 m.
        val en = GeoConvert.enu(35.0, 139.0, 0.0, 35.0, 139.0 + 1e-5, 0.0)
        assertEquals(0.91287, en[0].toDouble(), 2e-3)
        assertEquals(0.0, en[1].toDouble(), 1e-3)
    }
}
