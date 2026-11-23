package com.example.certextractor.formfill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FormFillTest {

    // ---------------------------------------------------------- التحويلات

    @Test fun names() {
        assertEquals(
            listOf("ابراهيم", "سالم", "عبيد", "احمد", "رفاعي"),
            NameSplitter.split("ابراهيم سالم  عبيد احمد  رفاعي").fields
        )
        assertEquals(
            listOf("ابوبكر", "وليد", "ابوبكر", "بن قاسم", "باعشور"),
            NameSplitter.split("ابوبكر وليد ابوبكر بن قاسم باعشور ").fields
        )
        assertEquals(
            listOf("ابو بكر", "سالم", "محمد", "احمد", "رفاعي"),
            NameSplitter.split("ابو بكر سالم محمد احمد رفاعي").fields
        )
        assertEquals(
            listOf("احمد", "علي", "حسن", "", "الجبوري"),
            NameSplitter.split("احمد علي حسن الجبوري").fields
        )
        assertNull(NameSplitter.split("احمد علي حسن محمد سالم عمر").fields)
    }

    @Test fun dates() {
        assertEquals(Triple(15, 6, 2009), DateSplitter.parse("15/06/2009", true))
        assertEquals(Triple(5, 8, 2010), DateSplitter.parse("05/08/2010", true))
        assertEquals(Triple(15, 6, 2009), DateSplitter.parse("2009-06-15", true))
        assertEquals(Triple(15, 6, 2009), DateSplitter.parse("\u0661\u0665/\u0660\u0666/\u0662\u0660\u0660\u0669", true))
        assertNull(DateSplitter.parse("31/02/2009", true))
        assertNull(DateSplitter.parse("15/06/09", true))
        assertEquals(true, DateSplitter.detectDayFirst(listOf("05/08/2010", "15/06/2009")))
        assertEquals("34566", TextClean.normalize("\u0663\u0664\u0665\u0666\u0666"))
    }

    // ------------------------------------------- نموذج مصطنع (بدون رفع ملف)

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(c.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                m[e.name] = String(z.readBytes(), Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        return m
    }

    private fun dataRow(r: Int): String {
        val sb = StringBuilder("<row r=\"$r\" spans=\"1:11\" ht=\"20\" customHeight=\"1\">")
        sb.append("<c r=\"A$r\" s=\"2\"><v>${r - 2}</v></c>")
        for (col in "BCDEFGHIJK") sb.append("<c r=\"$col$r\" s=\"2\"/>")
        sb.append("</row>")
        return sb.toString()
    }

    private fun syntheticTemplate(): ByteArray {
        val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:K5"/><sheetViews><sheetView rightToLeft="1" workbookViewId="0"/></sheetViews><sheetData><row r="1" spans="1:11"><c r="A1" s="1" t="inlineStr"><is><t>m</t></is></c></row><row r="2" spans="1:11"><c r="A2" s="1" t="inlineStr"><is><t>n</t></is></c></row>${dataRow(3)}${dataRow(4)}${dataRow(5)}</sheetData><autoFilter ref="A2:K5"/></worksheet>"""
        return zipOf(
            linkedMapOf(
                "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>""",
                "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="x" Target="worksheets/sheet1.xml"/></Relationships>""",
                "xl/worksheets/sheet1.xml" to sheet
            )
        )
    }

    @Test fun fill_synthetic_template() {
        val template = syntheticTemplate()
        val names = listOf("ابراهيم سالم عبيد احمد رفاعي", "ابوبكر وليد ابوبكر بن قاسم باعشور")
        val records = (0 until 10).map { i ->
            buildRecord("${30000 + i}", names[i % 2], "15/06/2009", true, null).cells
        }
        val out = ByteArrayOutputStream()
        val report = FormFiller(3).fill(template, out, records)

        val sheet = unzip(out.toByteArray())["xl/worksheets/sheet1.xml"]!!
        println(sheet)

        assertEquals("xl/worksheets/sheet1.xml", FormFiller(3).firstSheetPath(template))
        assertEquals(7, report.clonedRows)
        assertTrue(report.skipped.isEmpty())
        assertTrue(sheet.contains("<dimension ref=\"A1:K12\"/>"))
        assertTrue(sheet.contains("<autoFilter ref=\"A2:K12\"/>"))
        assertTrue(sheet.contains("rightToLeft=\"1\""))
        assertTrue(sheet.contains("<row r=\"12\""))
        assertFalse(sheet.contains("<row r=\"13\""))
        assertTrue(sheet.contains("<c r=\"A12\" s=\"2\"><v>10</v></c>"))     // ترقيم الصف الجديد
        assertTrue(sheet.contains("<c r=\"A3\" s=\"2\"><v>1</v></c>"))       // الأصلي لم يُمس
        assertTrue(sheet.contains("ابراهيم"))
        assertTrue(sheet.contains("<c r=\"J3\" s=\"2\"><v>2009</v></c>"))
    }

    // ------------------------------------------------ النموذج الحقيقي

    @Test fun fill_real_template() {
        val stream = javaClass.classLoader!!.getResourceAsStream("template.xlsx")
        assumeTrue("template.xlsx غير مرفوع، تم تجاوز الاختبار", stream != null)
        val bytes = stream!!.readBytes()

        val filler = FormFiller(firstDataRow = 3)
        println("الورقة المستخدمة: ${filler.firstSheetPath(bytes)}")

        val names = listOf(
            "ابراهيم سالم عبيد احمد رفاعي",
            "ابوبكر وليد ابوبكر بن قاسم باعشور",
            "ابوبكر سالم محمد سالم باديان"
        )
        val records = (0 until 200).map { i ->
            buildRecord("${30000 + i}", names[i % 3], "15/06/2009", true, null).cells
        }

        val out = File("build/test-output/filled.xlsx")
        out.parentFile.mkdirs()
        val report = out.outputStream().use { filler.fill(bytes, it, records) }

        println("كُتبت ${report.written} خلية، صفوف جديدة: ${report.clonedRows}، متخطاة: ${report.skipped}")
        assertTrue(report.written > 0)
    }
}
