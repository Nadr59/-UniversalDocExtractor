package com.example.certextractor.formfill

object DateSplitter {
    private val re = Regex("""^(\d{1,4})[/\-.](\d{1,2})[/\-.](\d{1,4})$""")
    private val sepSpaces = Regex("""\s*([/\-.])\s*""")

    private fun clean(raw: String): String {
        val t = TextClean.normalize(raw).replace(sepSpaces, "\$1")
        return t.substringBefore(' ')          // يحذف جزء الوقت إن وُجد
    }

    /** true = يوم/شهر، false = شهر/يوم، null = لا دليل في البيانات */
    fun detectDayFirst(values: List<String>): Boolean? {
        var day = false
        var month = false
        for (v in values) {
            val m = re.matchEntire(clean(v)) ?: continue
            if (m.groupValues[1].length == 4) continue
            if (m.groupValues[1].toInt() > 12) day = true
            if (m.groupValues[2].toInt() > 12) month = true
        }
        return when {
            day && !month -> true
            month && !day -> false
            else -> null
        }
    }

    /** يرجع (يوم، شهر، سنة) أو null إن لم يكن التاريخ مفهوماً أو غير موجود فعلاً */
    fun parse(raw: String, dayFirst: Boolean): Triple<Int, Int, Int>? {
        val m = re.matchEntire(clean(raw)) ?: return null
        val p1 = m.groupValues[1]
        val p2 = m.groupValues[2]
        val p3 = m.groupValues[3]
        val (d, mo, y) = when {
            p1.length == 4 -> Triple(p3.toInt(), p2.toInt(), p1.toInt())   // yyyy-mm-dd
            dayFirst -> Triple(p1.toInt(), p2.toInt(), p3.toInt())
            else -> Triple(p2.toInt(), p1.toInt(), p3.toInt())
        }
        if (y !in 1900..2100 || mo !in 1..12 || d < 1) return null          // يرفض السنة بخانتين
        val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
        val dim = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[mo - 1]
        return if (d <= dim) Triple(d, mo, y) else null
    }
}
