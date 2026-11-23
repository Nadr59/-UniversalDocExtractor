package com.example.certextractor.formfill

sealed interface Cell {
    data class Text(val s: String) : Cell
    data class Num(val n: Long) : Cell
}

class RecordResult(val cells: Map<String, Cell>, val notes: List<String>)

/**
 * يحوّل سجلاً واحداً إلى خلايا النموذج:
 * B الرقم المدرسي، C..G الاسم، H..J اليوم والشهر والسنة، K الجنس
 */
fun buildRecord(
    schoolNo: String,
    fullName: String,
    date: String,
    dayFirst: Boolean,
    gender: String?
): RecordResult {
    val cells = LinkedHashMap<String, Cell>()
    val notes = ArrayList<String>()

    val no = TextClean.normalize(schoolNo)
    if (no.isNotEmpty()) {
        cells["B"] = if (no.all { it.isDigit() } && !no.startsWith("0") && no.length <= 15)
            Cell.Num(no.toLong()) else Cell.Text(no)      // الأصفار الأولى تُحفظ كنص
    }

    val n = NameSplitter.split(fullName)
    notes += n.notes
    n.fields?.forEachIndexed { i, p ->
        if (p.isNotBlank()) cells["CDEFG"[i].toString()] = Cell.Text(p)
    }

    if (date.isBlank()) {
        notes += "لا يوجد تاريخ"
    } else {
        val d = DateSplitter.parse(date, dayFirst)
        if (d != null) {
            cells["H"] = Cell.Num(d.first.toLong())
            cells["I"] = Cell.Num(d.second.toLong())
            cells["J"] = Cell.Num(d.third.toLong())
        } else notes += "تاريخ غير مفهوم: $date"
    }

    if (!gender.isNullOrBlank()) cells["K"] = Cell.Text(gender)
    return RecordResult(cells, notes)
}
