package org.furo.rtcmstreamer.net

/** One parsed RTKLIB .pos LLH solution. */
data class Solution(
    val timeStr: String,
    val lat: Double,
    val lon: Double,
    val height: Double,
    val q: Int,
    val ns: Int,
    val sdn: Double,
    val sde: Double,
    val sdu: Double,
    val age: Double,
    val ratio: Double,
)

/**
 * Parser for RTKLIB .pos LLH lines.
 * Drops blank lines and lines starting with '%', then splits on whitespace. Token order:
 * [0]date [1]time [2]lat [3]lon [4]height [5]Q [6]ns [7]sdn [8]sde [9]sdu
 * [10]sdne [11]sdeu [12]sdun [13]age [14]ratio (15 total).
 * Q: 1=FIX 2=FLOAT 3=SBAS 4=DGPS 5=SINGLE 6=PPP 0=none.
 */
object SolParser {

    fun parse(line: String): Solution? {
        val s = line.trim()
        if (s.isEmpty() || s.startsWith("%")) return null
        val t = s.split(Regex("\\s+"))
        if (t.size < 15) return null
        return try {
            Solution(
                timeStr = "${t[0]} ${t[1]}",
                lat = t[2].toDouble(),
                lon = t[3].toDouble(),
                height = t[4].toDouble(),
                q = t[5].toInt(),
                ns = t[6].toInt(),
                sdn = t[7].toDouble(),
                sde = t[8].toDouble(),
                sdu = t[9].toDouble(),
                age = t[13].toDouble(),
                ratio = t[14].toDouble(),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}
