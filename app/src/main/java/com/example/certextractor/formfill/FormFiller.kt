package com.example.certextractor.formfill

import java.io.InputStream
import java.io.OutputStream
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FillReport(val written: Int, val clonedRows: Int, val skipped: List<String>)

/**
 * يكتب في نموذج xlsx بتعديل نص الورقة فقط، ويترك بقية ملفات الـ ZIP كما هي.
 * الصفوف الجديدة تُستنسخ من آخر صف منسق في النموذج.
 */
class FormFiller(private val firstDataRow: Int = 3) {

    private class Row(val r: Int, val attrs: String, val cells: TreeMap<Int, String>)
    private class Patched(val xml: String, val report: FillReport, val oldLast: Int, val newLast: Int)

    private val rowRe = Regex("""<row\b[^>]*?(?:/>|>.*?</row>)""", RegexOption.DOT_MATCHES_ALL)
    private val cellRe = Regex("""<c\b[^>]*?(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val styleRe = Regex("""\bs=["'](\d+)["']""")
    private val cellRefRe = Regex("""\br=["']([A-Z]+)\d+["']""")
    private val rowOpenRe = Regex("""<row\b([^>]*?)/?>""")
    private val rowNumRe = Regex("""\br=["'](\d+)["']""")
    private val rowNumRemoveRe = Regex("""(^|\s)r=["']\d+["']""")

    /** يكتب السجلات ويحفظ الناتج. إن كان sheetPath فارغاً تُستخدم أول ورقة. */
    fun fill(
        template: ByteArray,
        dst: OutputStream,
        records: List<Map<String, Cell>>,
        sheetPath: String? = null,
        numberCol: String? = "A",      // ترقيم الصفوف الجديدة فقط
        overwrite: Boolean = false
    ): FillReport {
        val files = readZip(template.inputStream())
        val path = sheetPath ?: firstSheetPath(template)
        val sheet = files[path] ?: error("الورقة غير موجودة: $path")

        val p = patchSheet(String(sheet, Charsets.UTF_8), records, numberCol, overwrite)

        var xml = p.xml
        if (p.newLast > p.oldLast) {
            xml = shiftRefs(xml, p.oldLast, p.newLast, "dimension|autoFilter|dataValidation|conditionalFormatting")
            files["xl/workbook.xml"]?.let {
                files["xl/workbook.xml"] =
                    shiftNames(String(it, Charsets.UTF_8), p.oldLast, p.newLast).toByteArray(Charsets.UTF_8)
            }
            for (name in files.keys.toList()) {
                if (name.startsWith("xl/tables/") && name.endsWith(".xml")) {
                    files[name] = shiftRefs(String(files[name]!!, Charsets.UTF_8), p.oldLast, p.newLast, "table|autoFilter")
                        .toByteArray(Charsets.UTF_8)
                }
            }
        }
        files[path] = xml.toByteArray(Charsets.UTF_8)

        ZipOutputStream(dst).use { z ->
            for ((name, bytes) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return p.report
    }

    // ---------------------------------------------------------------- الورقة

    private fun patchSheet(
        xml: String,
        recs: List<Map<String, Cell>>,
        numberCol: String?,
        overwrite: Boolean
    ): Patched {
        val open = "<sheetData>"
        val a = xml.indexOf(open)
        val b = xml.indexOf("</sheetData>")
        require(a >= 0 && b > a) { "الورقة لا تحتوي صفوفاً" }

        val rows = TreeMap<Int, Row>()
        for (m in rowRe.findAll(xml.substring(a + open.length, b))) {
            val r = parseRow(m.value)
            rows[r.r] = r
        }
        val template = rows.values.lastOrNull { it.cells.isNotEmpty() }
            ?: error("لا توجد خلايا منسقة في النموذج")
        require(template.r >= firstDataRow) { "النموذج لا يحتوي صفاً منسقاً للبيانات بدءاً من الصف $firstDataRow" }

        val oldLast = template.r
        val newLast = maxOf(oldLast, firstDataRow + recs.size - 1)

        val skipped = ArrayList<String>()
        var written = 0
        recs.forEachIndexed { i, rec ->
            val rn = firstDataRow + i
            val row = rows.getOrPut(rn) { cloneEmpty(template, rn, numberCol) }
            for ((col, value) in rec) {
                val ci = colNum(col)
                val existing = row.cells[ci]
                if (existing != null && hasContent(existing) && !overwrite) {
                    skipped += "$col$rn"
                    continue
                }
                val style = styleOf(existing) ?: styleOf(template.cells[ci])
                row.cells[ci] = cellXml(col, rn, style, value)
                written++
            }
        }

        val sb = StringBuilder(xml.substring(0, a + open.length))
        for (r in rows.values) {
            sb.append("<row r=\"").append(r.r).append("\"").append(r.attrs).append(">")
            for (c in r.cells.values) sb.append(c)
            sb.append("</row>")
        }
        sb.append(xml.substring(b))
        return Patched(sb.toString(), FillReport(written, newLast - oldLast, skipped), oldLast, newLast)
    }

    private fun parseRow(x: String): Row {
        val open = rowOpenRe.find(x)!!
        val attrStr = open.groupValues[1]
        val r = rowNumRe.find(attrStr)!!.groupValues[1].toInt()
        val attrs = attrStr.replace(rowNumRemoveRe, "$1").let { if (it.isBlank()) "" else if (it.startsWith(" ")) it else " $it" }
        val cells = TreeMap<Int, String>()
        var seq = 0
        for (m in cellRe.findAll(x.substring(open.range.last + 1))) {
            seq++
            val tag = openTag(m.value)
            val col = cellRefRe.find(tag)?.groupValues?.get(1)
            cells[if (col != null) colNum(col) else seq] = m.value
        }
        return Row(r, attrs, cells)
    }

    private fun cloneEmpty(t: Row, rn: Int, numberCol: String?): Row {
        val cells = TreeMap<Int, String>()
        for ((ci, c) in t.cells) {
            val style = styleOf(c)
            val col = colName(ci)
            cells[ci] = if (col == numberCol)
                cellXml(col, rn, style, Cell.Num((rn - firstDataRow + 1).toLong()))
            else cellXml(col, rn, style, null)
        }
        return Row(rn, t.attrs, cells)
    }

    private fun openTag(c: String): String {
        val i = c.indexOf('>')
        return if (i >= 0) c.substring(0, i + 1) else c
    }

    private fun styleOf(c: String?): String? =
        c?.let { styleRe.find(openTag(it))?.groupValues?.get(1) }

    private fun hasContent(c: String) = "<v>" in c || "<is>" in c || "<f" in c

    private fun cellXml(col: String, row: Int, style: String?, c: Cell?): String {
        val s = if (style != null) " s=\"$style\"" else ""
        return when (c) {
            null -> "<c r=\"$col$row\"$s/>"
            is Cell.Num -> "<c r=\"$col$row\"$s><v>${c.n}</v></c>"
            is Cell.Text ->
                "<c r=\"$col$row\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(c.s)}</t></is></c>"
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colNum(l: String): Int {
        var n = 0
        for (ch in l) n = n * 26 + (ch - 'A' + 1)
        return n
    }

    private fun colName(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x > 0) {
            sb.insert(0, 'A' + (x - 1) % 26)
            x = (x - 1) / 26
        }
        return sb.toString()
    }

    // ------------------------------------------------------- تمديد النطاقات

    /** يمدّد النطاقات (dimension، الفلتر، التحقق، التنسيق الشرطي، الجداول) من آخر صف قديم إلى الجديد */
    private fun shiftRefs(xml: String, old: Int, new: Int, tagNames: String): String {
        val tags = Regex("""<($tagNames)\b[^>]*>""")
        val attrs = Regex("""\b(ref|sqref)=(["'])([^"']*)\2""")
        val rowRef = Regex("""([A-Z]+)$old\b""")
        return tags.replace(xml) { m ->
            attrs.replace(m.value) { a ->
                val fixed = rowRef.replace(a.groupValues[3]) { it.groupValues[1] + new }
                a.groupValues[1] + "=" + a.groupValues[2] + fixed + a.groupValues[2]
            }
        }
    }

    /** يمدّد نطاق الطباعة ونطاق الفلتر في workbook.xml (يفترض ورقة واحدة في النموذج) */
    private fun shiftNames(xml: String, old: Int, new: Int): String {
        val names = Regex("""<definedName\b[^>]*>[^<]*</definedName>""")
        val rowRef = Regex("""(\$[A-Z]+\$)""" + old + """\b""")
        return names.replace(xml) { m ->
            rowRef.replace(m.value) { it.groupValues[1] + new }
        }
    }

    // ------------------------------------------------------------- ZIP

    private fun readZip(src: InputStream): LinkedHashMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(src).use { z ->
            var e = z.nextEntry
            while (e != null) {
                files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return files
    }

    /** مسار أول ورقة في المصنف، مثل xl/worksheets/sheet1.xml */
    fun firstSheetPath(xlsx: ByteArray): String {
        val files = readZip(xlsx.inputStream())
        val sheetFiles = files.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()

        val wb = files["xl/workbook.xml"]?.toString(Charsets.UTF_8)
        val rels = files["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8)
        if (wb != null && rels != null) {
            val sheetTag = Regex("""<sheet\b[^>]*>""").find(wb)?.value
            val rid = sheetTag?.let {
                Regex("""\br:id\s*=\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)
            }
            if (rid != null) {
                for (m in Regex("""<Relationship\b[^>]*>""").findAll(rels)) {
                    if (attr(m.value, "Id") == rid) {
                        val target = attr(m.value, "Target")?.trimStart('/')
                        if (target != null) {
                            val p = if (target.startsWith("xl/")) target else "xl/$target"
                            if (files.containsKey(p)) return p
                        }
                    }
                }
            }
        }
        return sheetFiles.firstOrNull() ?: error("لا توجد أوراق في الملف")
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)
}
