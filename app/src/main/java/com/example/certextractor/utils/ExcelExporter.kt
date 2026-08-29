ExcelExporter.ktpackage com.example.certextractor.utils

import android.content.Context
import android.net.Uri
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import java.io.OutputStreamWriter

object ExcelExporter {

    fun writeExcelToUri(
        context: Context,
        uri: Uri,
        results: List<ExtractionResult>,
        fields: List<ExtractionField>
    ) {
        val fieldNames = if (fields.isNotEmpty()) {
            fields.filter { it.enabled }.map { it.name }
        } else {
            results.flatMap { it.values.keys }.distinct()
        }

        val html = buildString {
            append("<html dir=\"rtl\" lang=\"ar\">")
            append("<head>")
            append("<meta charset=\"UTF-8\">")
            append("<style>")
            append("table { border-collapse: collapse; width: 100%; direction: rtl; }")
            append("th { background-color: #1565C0; color: white; font-weight: bold; padding: 8px; text-align: right; border: 1px solid #0D47A1; }")
            append("td { border: 1px solid #ddd; padding: 6px; text-align: right; }")
            append("tr:nth-child(even) { background-color: #f5f5f5; }")
            append(".success { color: #2E7D32; font-weight: bold; }")
            append(".error { color: #C62828; font-weight: bold; }")
            append(".pending { color: #F57F17; font-weight: bold; }")
            append("</style>")
            append("</head>")
            append("<body>")

            // ملخص أعلى الجدول
            val successCount = results.count { it.status == "success" }
            append("<p style=\"font-size:14px; color:#555;\">")
            append("إجمالي الملفات: ${results.size} | ")
            append("ناجحة: $successCount | ")
            append("withErrors: ${results.size - successCount}")
            append("</p>")

            append("<table>")

            // صف العناوين
            append("<tr>")
            append("<th>#</th>")
            append("<th>${escapeHtml("اسم الملف")}</th>")
            fieldNames.forEach { name ->
                append("<th>${escapeHtml(name)}</th>")
            }
            append("<th>${escapeHtml("الحالة")}</th>")
            append("<th>${escapeHtml("ملاحظات")}</th>")
            append("</tr>")

            // صفوف البيانات
            results.forEachIndexed { index, result ->
                val statusClass = when (result.status) {
                    "success" -> "success"
                    "error" -> "error"
                    else -> "pending"
                }
                val statusText = when (result.status) {
                    "success" -> "ناجح"
                    "error" -> "خطأ"
                    else -> "قيد الانتظار"
                }

                append("<tr>")
                append("<td>${index + 1}</td>")
                append("<td>${escapeHtml(result.fileName)}</td>")
                fieldNames.forEach { fieldName ->
                    val value = result.values[fieldName] ?: ""
                    append("<td>${escapeHtml(value)}</td>")
                }
                append("<td class=\"$statusClass\">$statusText</td>")
                append("<td>${escapeHtml(result.errorMessage)}</td>")
                append("</tr>")
            }

            append("</table>")
            append("</body>")
            append("</html>")
        }

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                writer.write(html)
                writer.flush()
            }
        }
    }

    /**
     * حماية ضد حقن HTML في كل النصوص
     * بما فيها أسماء الحقول التي يدخلها المستخدم
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("\r\n", "<br>")
            .replace("\n", "<br>")
            .replace("\r", "<br>")
    }
}
