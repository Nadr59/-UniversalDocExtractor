package com.example.certextractor.utils

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
            append("</style>")
            append("</head>")
            append("<body>")

            val successCount = results.count { it.status == "success" }
            append("<p>")
            append("Total: ${results.size} | OK: $successCount | Errors: ${results.size - successCount}")
            append("</p>")

            append("<table>")
            append("<tr>")
            append("<th>#</th>")
            append("<th>${esc("File")}</th>")
            fieldNames.forEach { name ->
                append("<th>${esc(name)}</th>")
            }
            append("<th>${esc("Status")}</th>")
            append("<th>${esc("Notes")}</th>")
            append("</tr>")

            results.forEachIndexed { index, result ->
                val cls = if (result.status == "success") "success" else "error"
                val txt = if (result.status == "success") "OK" else "Error"

                append("<tr>")
                append("<td>${index + 1}</td>")
                append("<td>${esc(result.fileName)}</td>")
                fieldNames.forEach { fieldName ->
                    val value = result.values[fieldName] ?: ""
                    append("<td>${esc(value)}</td>")
                }
                append("<td class=\"$cls\">$txt</td>")
                append("<td>${esc(result.errorMessage)}</td>")
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

    private fun esc(text: String): String {
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
