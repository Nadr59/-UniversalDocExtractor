package com.example.certextractor.utils

import android.content.Context
import android.net.Uri
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import java.io.OutputStreamWriter

object CsvExporter {

    fun writeCsvToUri(
        context: Context,
        uri: Uri,
        results: List<ExtractionResult>,
        fields: List<ExtractionField>
    ) {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                writer.write("\uFEFF")

                val fieldNames = if (fields.isNotEmpty()) {
                    fields.filter { it.enabled }.map { it.name }
                } else {
                    results.flatMap { it.values.keys }.distinct()
                }

                val headers = listOf("اسم الملف") + fieldNames + listOf("الحالة", "الخطأ")
                writer.write(headers.joinToString(",") { escapeCsv(it) })
                writer.write("\n")

                results.forEach { result ->
                    val row = mutableListOf<String>()
                    row.add(escapeCsv(result.fileName))
                    fieldNames.forEach { fieldName ->
                        row.add(escapeCsv(result.values[fieldName] ?: ""))
                    }
                    row.add(escapeCsv(translateStatus(result.status)))
                    row.add(escapeCsv(result.errorMessage))
                    writer.write(row.joinToString(","))
                    writer.write("\n")
                }

                writer.flush()
            }
        }
    }

    private fun escapeCsv(value: String): String {
        val cleaned = value.replace("\n", " ").replace("\r", "")
        return if (cleaned.contains(",") || cleaned.contains("\"")) {
            "\"${cleaned.replace("\"", "\"\"")}\""
        } else {
            cleaned
        }
    }

    private fun translateStatus(status: String): String {
        return when (status) {
            "success" -> "تم بنجاح"
            "error" -> "خطأ"
            "pending" -> "قيد الانتظار"
            else -> status
        }
    }
}
