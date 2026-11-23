package com.example.certextractor.formfill

class NameResult(val fields: List<String>?, val notes: List<String>)

object NameSplitter {
    // كلمات تلتصق بما بعدها (تُقارَن بعد توحيد الهمزات)
    private val glue = setOf("عبد", "ابو", "ابي", "ام", "بو", "بن", "ابن", "بنت", "ال")
    private val ambiguous = setOf("بن", "ابن", "بنت")

    private fun key(w: String) = w.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')

    /** يرجع 5 أجزاء: الاسم، الأب، الجد الأول، الجد الثاني، اللقب */
    fun split(full: String): NameResult {
        val words = TextClean.normalize(full).split(" ").filter { it.isNotEmpty() }
        val parts = ArrayList<String>()
        val notes = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            var cur = words[i++]
            while (key(cur.substringAfterLast(' ')) in glue && i < words.size) {
                val g = key(cur.substringAfterLast(' '))
                if (g in ambiguous) notes += "دُمجت «$g» مع «${words[i]}» (راجع)"
                cur += " " + words[i++]
            }
            parts += cur
        }
        return when (parts.size) {
            5 -> NameResult(parts, notes)
            4 -> NameResult(
                listOf(parts[0], parts[1], parts[2], "", parts[3]),
                notes + "اسم من 4 أجزاء: خانة الجد الثاني فارغة"
            )
            else -> NameResult(null, notes + "عدد الأجزاء ${parts.size} (المتوقع 5): لم يُكتب الاسم")
        }
    }
}
