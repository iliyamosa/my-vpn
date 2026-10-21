package app.vpn.client.ui

import android.util.Base64
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Land dots for the 3D globe and rough locations for server names. */
object GlobeData {
    /** 180 x 90 bit mask of land (2 degree cells), generated from public world map outlines. */
    private const val LAND_MASK =
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAOAfAP8DAAAAAAAAAAAAAAAAAAAAAADo//z//wcAAAAAAAACAAAAAAAAAAAAhvvw//8PAPABAAAAwAMAAAAAAAAAwADkw////wEABAAAAABgAAAAAAAAAADAUT8A/v8PAAAAAAwA/j8AuAEAAAAAcBHtDcD/fwAAAAAwAPz/fwMAABCAAQD5w/wD8P8FAAAMAIL7//9//xOAAP/ff0668QD/HwAA+A8At///////v3/w/////x8++B8AAOD/1//7////////jP////+/+AE/gAcAn9f//////////w/w/////wEs4AEAAHz+////////////gL////8HeAAcAADg5///////////9ADgAf7/f4AnAAAAAH78////////H0QAAAiA//8f8AcAAIBB4////////38ADwAQAOD//5//AQAAHAT/////////A3AAAAAA/v//+T8AAGDz//////////8DAQAAAMD/////AwAAsP//////////LwAAAAAA6P///2IAAAD+//////////8CAAAAAAD///8/CAAA4P//////////JwAAAAAA8P///wYAAAD+/unz/////z8AAAAAAAD///8HAAAA/pgPPP//////MQAAAAAA8P//PwAAAMBD9v7n/////wcBAAAAAAD///8AAAAAPkD7f/7///8hEAAAAAAA4P//DwAAAIDhAv/n////f8YAAAAAAAD8//8AAAAA+AdE//////8jDwAAAAAAgP//AwAAAMD/APD/////PxgAAAAAAADw/x8AAAAA/n/v//////8HAAAAAAAAAPwDAgAAAOD////7////fwAAAAAAAACgHyAAAACA//9/f/7///8DAAAAAAAAAPQBAAAAAPj//+cv+P//PwAAAAAAAAAAHjAAAADA/////g/+//8EAAAAAAAAAOBhCAAAAP7//99/4D//AAAAAAAAAAAAPAMEAADA////+Qf84BcAAAAAAAAAAAA/AAAAAPz//58fgAf+QAAAAAAAAAAAAA8AAADg////ewA4gA8EAAAAAAAAAADAAAAAAPz//38BgAP4QQAAAAAAAAAAAAgPAADA////zwAwgAwQAAAAAAAAAAAA9Q8AAPj///8HAAVIAAAAAAAAAAAAAID/AQAA////fwBAAAAQAAAAAAAAAAAA+P8AAGDh//8DAAA0GAAAAAAAAAAAAID/HwAAAPj/HwAAgMIBAAAAAAAAAAAA/P8BAACA//8AAAAYXgAAAAAAAAAAAMD/fwAAAPz/BwAAAOOBAQAAAAAAAAAA/P8/AACA/z8AAABgbtQBAAAAAAAAAOD//w8AAPD/AwAAAAQIeAAAAAAAAAAA/P//AQAA/z8AAACAA4APAQAAAAAAAID//w8AAPD/AwAAAAARsEAAAAAAAAAA+P9/AAAA/j8AAAAAAAAAAAAAAAAAAAD//wcAAPD/QwAAAACAIwAAAAAAAAAA8P9/AAAA/z8EAAAAAD8GIAAAAAAAAAD8/wMAAPD/cQAAAAD4ZwAAAAAAAAAAgP8/AAAA/w8HAAAAgP8HAAAAAAAAAAD4/wMAAOD/MAAAAAD//wEBAAAAAAAAgP8PAAAA/g8DAAAA+P8fAAAAAAAAAAD4PwAAAOB/EAAAAID//wMAAAAAAAAAgP8DAAAA/AMAAAAA+P9/AAAAAAAAAAD8HwAAAMA/AAAAAID//wcAAAAAAAAAwP8BAAAA+AEAAAAA8P9/AAAAAAAAAAD8DwAAAIAPAAAAAAAP/gMAAAAAAAAAwB8AAAAAAAAAAAAAEIAfAAEAAAAAAAD+AwAAAAAAAAAAAAAA8AEgAAAAAAAA4AcAAAAAAAAAAAAAAAAAAAYAAAAAAABeAAAAAAAAAAAAAAAAwAAwAAAAAAAAwAMAAAAAAAAAAAAAAAAIgAEAAAAAAAAeAAAAAAAAAAAAAAAAAAAMAAAAAAAA4AEAAAAAAAAAAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAABAAAAAAAAAAAAAAAAcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAOAAAAAAAAAAAAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAA////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////"

    const val TEHRAN_LAT = 35.69f
    const val TEHRAN_LON = 51.39f

    /** Unit vectors (x, y, z) of dots that lie on land. */
    val landPoints: FloatArray by lazy { buildPoints(5200) }

    private fun buildPoints(n: Int): FloatArray {
        val bits = Base64.decode(LAND_MASK, Base64.DEFAULT)
        fun land(latDeg: Double, lonDeg: Double): Boolean {
            val row = ((90.0 - latDeg) / 2.0).toInt().coerceIn(0, 89)
            val col = ((lonDeg + 180.0) / 2.0).toInt().coerceIn(0, 179)
            val idx = row * 180 + col
            return (bits[idx shr 3].toInt() shr (idx and 7)) and 1 == 1
        }
        val out = ArrayList<Float>(n * 3 / 2)
        val golden = PI * (3.0 - sqrt(5.0))
        for (i in 0 until n) {
            val y = 1.0 - (i + 0.5) * 2.0 / n
            val r = sqrt(1.0 - y * y)
            val theta = golden * i
            val x = cos(theta) * r
            val z = sin(theta) * r
            val lat = Math.toDegrees(asin(y))
            val lon = Math.toDegrees(atan2(x, z))
            if (land(lat, lon)) {
                out.add(x.toFloat())
                out.add(y.toFloat())
                out.add(z.toFloat())
            }
        }
        return out.toFloatArray()
    }

    /** lat/lon (degrees) to a unit vector, same convention as landPoints. */
    fun vector(latDeg: Float, lonDeg: Float): FloatArray {
        val lat = Math.toRadians(latDeg.toDouble())
        val lon = Math.toRadians(lonDeg.toDouble())
        return floatArrayOf((cos(lat) * sin(lon)).toFloat(), sin(lat).toFloat(), (cos(lat) * cos(lon)).toFloat())
    }

    private class Place(val lat: Float, val lon: Float, vararg val names: String)

    private val countries = mapOf(
        "DE" to Place(50.1f, 8.7f, "germany", "deutschland", "berlin", "frankfurt", "آلمان"),
        "NL" to Place(52.37f, 4.9f, "netherlands", "holland", "amsterdam", "هلند"),
        "FR" to Place(48.86f, 2.35f, "france", "paris", "فرانسه"),
        "FI" to Place(60.17f, 24.94f, "finland", "helsinki", "فنلاند"),
        "LV" to Place(56.95f, 24.1f, "latvia", "riga", "لتونی"),
        "LT" to Place(54.69f, 25.28f, "lithuania", "vilnius", "لیتوانی"),
        "EE" to Place(59.44f, 24.75f, "estonia", "tallinn", "استونی"),
        "SE" to Place(59.33f, 18.07f, "sweden", "stockholm", "سوئد"),
        "NO" to Place(59.91f, 10.75f, "norway", "oslo", "نروژ"),
        "DK" to Place(55.68f, 12.57f, "denmark", "copenhagen", "دانمارک"),
        "GB" to Place(51.51f, -0.13f, "united kingdom", "england", "london", "انگلیس", "انگلستان", "بریتانیا"),
        "IE" to Place(53.35f, -6.26f, "ireland", "dublin", "ایرلند"),
        "PL" to Place(52.23f, 21.01f, "poland", "warsaw", "لهستان"),
        "AT" to Place(48.21f, 16.37f, "austria", "vienna", "اتریش"),
        "CH" to Place(47.38f, 8.54f, "switzerland", "zurich", "سوئیس"),
        "IT" to Place(45.46f, 9.19f, "italy", "milan", "rome", "ایتالیا"),
        "ES" to Place(40.42f, -3.7f, "spain", "madrid", "اسپانیا"),
        "PT" to Place(38.72f, -9.14f, "portugal", "lisbon", "پرتغال"),
        "CZ" to Place(50.08f, 14.44f, "czech", "prague", "چک"),
        "HU" to Place(47.5f, 19.04f, "hungary", "budapest", "مجارستان"),
        "RO" to Place(44.43f, 26.1f, "romania", "bucharest", "رومانی"),
        "BG" to Place(42.7f, 23.32f, "bulgaria", "sofia", "بلغارستان"),
        "TR" to Place(41.01f, 28.98f, "turkey", "turkiye", "istanbul", "ترکیه"),
        "AM" to Place(40.18f, 44.51f, "armenia", "yerevan", "ارمنستان"),
        "GE" to Place(41.72f, 44.79f, "georgia", "tbilisi", "گرجستان"),
        "AE" to Place(25.2f, 55.27f, "emirates", "uae", "dubai", "امارات", "دبی"),
        "QA" to Place(25.29f, 51.53f, "qatar", "doha", "قطر"),
        "RU" to Place(55.76f, 37.62f, "russia", "moscow", "روسیه"),
        "UA" to Place(50.45f, 30.52f, "ukraine", "kyiv", "اوکراین"),
        "KZ" to Place(43.24f, 76.89f, "kazakhstan", "almaty", "قزاقستان"),
        "IN" to Place(19.08f, 72.88f, "india", "mumbai", "هند"),
        "SG" to Place(1.35f, 103.82f, "singapore", "سنگاپور"),
        "HK" to Place(22.32f, 114.17f, "hong kong", "هنگ کنگ"),
        "JP" to Place(35.68f, 139.69f, "japan", "tokyo", "ژاپن"),
        "KR" to Place(37.57f, 126.98f, "korea", "seoul", "کره"),
        "AU" to Place(-33.87f, 151.21f, "australia", "sydney", "استرالیا"),
        "US" to Place(40.71f, -74.0f, "united states", "usa", "america", "new york", "los angeles", "آمریکا", "امریکا"),
        "CA" to Place(43.65f, -79.38f, "canada", "toronto", "کانادا"),
        "BR" to Place(-23.55f, -46.63f, "brazil", "sao paulo", "برزیل"),
        "IR" to Place(35.69f, 51.39f, "iran", "tehran", "ایران", "نت ملی"),
    )

    /** Guesses the server's location from flags, country or city names in its title. */
    fun locate(serverName: String): FloatArray? {
        if (serverName.isBlank()) return null
        // A flag emoji is two "regional indicator" letters.
        val cps = serverName.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            val a = cps[i]
            val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val code = charArrayOf('A' + (a - 0x1F1E6), 'A' + (b - 0x1F1E6)).concatToString()
                countries[code]?.let { return floatArrayOf(it.lat, it.lon) }
            }
        }
        // Fancy unicode letters (bold/italic math alphabets) become plain letters with NFKC.
        val text = Normalizer.normalize(serverName, Normalizer.Form.NFKC).lowercase()
        for (place in countries.values) {
            if (place.names.any { text.contains(it) }) return floatArrayOf(place.lat, place.lon)
        }
        return null
    }
}
