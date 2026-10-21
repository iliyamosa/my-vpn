package app.vpn.common

import java.util.Calendar
import java.util.TimeZone

/** Persian (Jalali) calendar helpers for showing dates in the admin app. */
object Jalali {
    private val MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    /** Gregorian (y, m 1-12, d) to Jalali (y, m 1-12, d). */
    fun fromGregorian(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return intArrayOf(jy, jm, jd)
    }

    /** "2026-10-21" -> "۲۹ مهر" */
    fun shortLabel(isoDay: String): String {
        val p = isoDay.split("-")
        if (p.size != 3) return isoDay
        val j = fromGregorian(p[0].toInt(), p[1].toInt(), p[2].toInt())
        return "${j[2]} ${MONTHS[j[1] - 1]}".faDigits()
    }

    /** Epoch millis -> "۲۹ مهر ۱۴۰۵" in Tehran time. */
    fun fullDate(millis: Long): String {
        if (millis <= 0) return "—"
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        c.timeInMillis = millis
        val j = fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return "${j[2]} ${MONTHS[j[1] - 1]} ${j[0]}".faDigits()
    }

    /** "همین الان" / "۵ دقیقه پیش" / "۳ ساعت پیش" / "۲ روز پیش" */
    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0) return "—"
        val s = ((now - millis) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "همین الان"
            s < 3600 -> "${s / 60} دقیقه پیش".faDigits()
            s < 86400 -> "${s / 3600} ساعت پیش".faDigits()
            s < 30 * 86400 -> "${s / 86400} روز پیش".faDigits()
            else -> fullDate(millis)
        }
    }
}
