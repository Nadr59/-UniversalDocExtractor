package com.example.certextractor.utils

import com.example.certextractor.data.model.ExtractionField

object DynamicPromptBuilder {

    fun buildFieldsPrompt(fields: List<ExtractionField>): String {
        val activeFields = fields.filter { it.enabled }

        val fieldsDescription = activeFields.joinToString("\n") { field ->
            val desc = field.description.ifBlank {
                "استخرج القيمة المتعلقة بـ \"${field.name}\""
            }
            "- \"${field.name}\": $desc"
        }

        val jsonExample = activeFields.joinToString(",\n") { field ->
            "    \"${field.name}\": \"\""
        }

        return """
أنت نظام متخصص في تحليل الصور والوثائق.
قم بتحليل الصورة بدقة واستخرج المعلومات التالية:

$fieldsDescription

قواعد مهمة:
1. لا تخترع معلومات غير موجودة في الصورة.
2. إذا لم تجد معلومة، اترك القيمة فارغة "".
3. حافظ على النص كما يظهر في الوثيقة.
4. صحح أخطاء OCR الواضحة فقط إذا كنت متأكدًا.
5. أعد النتيجة بصيغة JSON فقط.
6. لا تكتب أي شرح أو نص خارج JSON.

الشكل المطلوب:
{
$jsonExample
}
        """.trimIndent()
    }

    fun buildFreeTextPrompt(userPrompt: String): String {
        return """
أنت نظام متخصص في تحليل الصور والوثائق.

المستخدم يطلب منك:
"$userPrompt"

قم بتحليل الصورة واستخرج المعلومات المطلوبة بدقة.

قواعد مهمة:
1. لا تخترع معلومات غير موجودة في الصورة.
2. إذا لم تجد معلومة، اترك القيمة فارغة.
3. حافظ على النص كما يظهر في الوثيقة.
4. أعد النتيجة بصيغة JSON فقط.
5. إذا كان الاستخراج يتضمن عدة عناصر (جدول أو قائمة)،
   أعد كل عنصر في مصفوفة JSON.
6. لا تكتب أي شرح أو نص خارج JSON.

استخدم هذا الشكل:
{
    "اسم_المفتاح_1": "القيمة",
    "اسم_المفتاح_2": "القيمة"
}
        """.trimIndent()
    }
}
