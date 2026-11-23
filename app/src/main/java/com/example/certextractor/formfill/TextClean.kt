package com.example.certextractor.formfill

object TextClean {
    private val spaces = Regex("\\s+")

    /** يوحّد الأرقام العربية، ويحذف الرموز المخفية والتطويل، ويضغط المسافات */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append('0' + (ch - '\u0660'))
                in '\u06F0'..'\u06F9' -> sb.append('0' + (ch - '\u06F0'))
                '\u00A0' -> sb.append(' ')
                '\u200E', '\u200F', '\u200B', '\u202A', '\u202B', '\u202C', '\u0640' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(spaces, " ").trim()
    }
}
