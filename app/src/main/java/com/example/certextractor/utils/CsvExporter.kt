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
        // جمع كل المفاتيح الفريدة من النتائج الفعلية
        val allKeys = mutableListOf<String>()
        results.forEach { result ->
            result.values.keys.forEach { key ->
                if (key !in allKeys) allKeys.add(key)
            }
        }

        // إذا لم توجد مفاتيح من النتائج، نستخدم أسماء الحقول المعرّفة
        if (allKeys.isEmpty() && fields.isNotEmpty()) {
            allKeys.addAll(fields.filter { it.enabled }.map { it.name })
        }

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                // BOM for Arabic support in Excel
                writer.write("\uFEFF")

                val headers = listOf("اسم الملف") + allKeys + listOf("الحالة", "الخطأ")
                writer.write(headers.joinToString(",") { esc(it) })
                writer.write("\n")

                results.forEach { result ->
                    val row = mutableListOf<String>()
                    row.add(esc(result.fileName))
                    allKeys.forEach { key ->
                        row.add(esc(result.values[key] ?: ""))
                    }
                    row.add(esc(transStatus(result.status)))
                    row.add(esc(result.errorMessage))
                    writer.write(row.joinToString(","))
                    writer.write("\n")
                }

                writer.flush()
            }
        }
    }

    private fun esc(value: String): String {
        val cleaned = value.replace("\n", " ").replace("\r", "")
        return if (cleaned.contains(",") || cleaned.contains("\"")) {
            "\"${cleaned.replace("\"", "\"\"")}\""
        } else {
            cleaned
        }
    }

    private fun transStatus(status: String): String {
        return when (status) {
            "success" -> "تم بنجاح"
            "error" -> "خطأ"
            "pending" -> "قيد الانتظار"
            else -> status
        }
    }
}
