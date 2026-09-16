package org.furo.rtcmstreamer.geo

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coordinate conversion (pure Kotlin, unit-testable).
 * Geodetic (lat,lon,h) -> ECEF -> ENU relative to an origin (WGS84).
 * Used to plot the East-North track relative to the first received solution.
 */
object GeoConvert {

    private const val A = 6378137.0                 // WGS84 semi-major axis (m)
    private const val F = 1.0 / 298.257223563       // WGS84 flattening
    private val E2 = F * (2.0 - F)                   // eccentricity squared
    private const val DEG2RAD = Math.PI / 180.0

    /** Geodetic (deg, deg, m) -> ECEF (m). */
    fun pos2ecef(latDeg: Double, lonDeg: Double, h: Double): DoubleArray {
        val lat = latDeg * DEG2RAD
        val lon = lonDeg * DEG2RAD
        val sinp = sin(lat); val cosp = cos(lat)
        val sinl = sin(lon); val cosl = cos(lon)
        val v = A / sqrt(1.0 - E2 * sinp * sinp)
        return doubleArrayOf(
            (v + h) * cosp * cosl,
            (v + h) * cosp * sinl,
            (v * (1.0 - E2) + h) * sinp,
        )
    }

    /**
     * East/North (m) of (lat,lon,h) relative to origin (origLat,origLon,origH).
     * Returns [E, N] (Up is dropped for the horizontal plot).
     */
    fun enu(
        origLatDeg: Double, origLonDeg: Double, origH: Double,
        latDeg: Double, lonDeg: Double, h: Double,
    ): FloatArray {
        val o = pos2ecef(origLatDeg, origLonDeg, origH)
        val p = pos2ecef(latDeg, lonDeg, h)
        val dx = p[0] - o[0]; val dy = p[1] - o[1]; val dz = p[2] - o[2]
        val lat = origLatDeg * DEG2RAD; val lon = origLonDeg * DEG2RAD
        val sinp = sin(lat); val cosp = cos(lat)
        val sinl = sin(lon); val cosl = cos(lon)
        val e = -sinl * dx + cosl * dy
        val n = -sinp * cosl * dx - sinp * sinl * dy + cosp * dz
        return floatArrayOf(e.toFloat(), n.toFloat())
    }
}
