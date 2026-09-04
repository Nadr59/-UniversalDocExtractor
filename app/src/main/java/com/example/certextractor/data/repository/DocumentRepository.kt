package com.example.certextractor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.certextractor.data.local.AiSettings
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import com.example.certextractor.utils.DynamicPromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class DocumentRepository(
    private val context: Context
) {

    val settings: AiSettings by lazy {
        AiSettings(context)
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    companion object {

        private const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

        /**
         * عدد الصور في الطلب الواحد.
         *
         * كل صورة تمثل وثيقة مستقلة.
         */
        private const val GEMINI_BATCH_SIZE = 3

        /**
         * عدد محاولات إعادة الاتصال للأخطاء المؤقتة فقط.
         *
         * مهم:
         * 429 لا يعاد تلقائيًا لأنه قد يعني تجاوز الحصة اليومية.
         */
        private const val MAX_RETRY_ATTEMPTS = 4

        private val RETRY_DELAYS_MS = longArrayOf(
            0L,
            2000L,
            5000L,
            10000L
        )

        private const val MAX_IMAGE_DIMENSION = 2048
        private const val JPEG_QUALITY = 85
    }

    /**
     * استثناء خاص بتجاوز الحصة اليومية.
     *
     * يتم إيقاف الدفعة الحالية وبقية الدفعات
     * بدل الاستمرار في إرسال طلبات ستفشل بنفس الخطأ.
     */
    private class DailyQuotaExceededException(
        message: String
    ) : IOException(message)

    /**
     * عنصر داخلي يربط نتيجة التحليل بفهرس الصورة الأصلي.
     */
    private data class IndexedResult(
        val index: Int,
        val fileName: String,
        val result: ExtractionResult
    )

    /**
     * تمثيل داخلي للصورة قبل إرسالها.
     */
    private data class PreparedImage(
        val mimeType: String,
        val base64: String
    )

    /**
     * تمثيل داخلي لمستند.
     *
     * الاحتفاظ به هنا حتى لا نعتمد على نموذج خارجي غير ضروري.
     */
    private data class DocumentItem(
        val index: Int,
        val uri: Uri,
        val fileName: String
    )

    // ============================================================
    // معالجة مستند واحد
    // ============================================================

    suspend fun processDocument(
        uri: Uri,
        fields: List<ExtractionField>,
        freeTextPrompt: String = "",
        isFreeTextMode: Boolean = false
    ): ExtractionResult {

        return withContext(Dispatchers.IO) {

            val fileName = getFileName(uri)

            try {

                val preparedImage = prepareImage(uri)

                val prompt = if (isFreeTextMode) {
                    DynamicPromptBuilder.buildFreeTextPrompt(
                        freeTextPrompt
                    )
                } else {
                    DynamicPromptBuilder.buildFieldsPrompt(
                        fields
                    )
                }

                val response = callVisionApiWithRetry(
                    prompt = prompt,
                    images = listOf(preparedImage)
                )

                val parsed = parseBatchResponse(
                    response = response,
                    expectedCount = 1
                )

                if (parsed.isEmpty()) {
                    throw IOException(
                        "لم يتم العثور على نتيجة للصورة"
                    )
                }

                parsed.first().copy(
                    fileName = fileName,
                    status = "success"
                )

            } catch (e: DailyQuotaExceededException) {

                ExtractionResult(
                    fileName = fileName,
                    status = "error",
                    errorMessage = e.message
                        ?: "تم تجاوز الحصة اليومية لخدمة الذكاء الاصطناعي."
                )

            } catch (e: Exception) {

                ExtractionResult(
                    fileName = fileName,
                    status = "error",
                    errorMessage = getErrorMessage(e)
                )
            }
        }
    }

    // ============================================================
    // معالجة مجموعة كبيرة من الصور
    // ============================================================

    suspend fun processBatch(
        uris: List<Uri>,
        fields: List<ExtractionField>,
        freeTextPrompt: String = "",
        isFreeTextMode: Boolean = false,
        onProgress: (
            current: Int,
            total: Int,
            result: ExtractionResult
        ) -> Unit
    ): List<ExtractionResult> {

        return withContext(Dispatchers.IO) {

            val allResults = mutableListOf<ExtractionResult>()

            if (uris.isEmpty()) {
                return@withContext emptyList()
            }

            val documents = uris.mapIndexed { index, uri ->
                DocumentItem(
                    index = index,
                    uri = uri,
                    fileName = getFileName(uri)
                )
            }

            val total = documents.size
            var processedCount = 0

            val batches = documents.chunked(GEMINI_BATCH_SIZE)

            for (batch in batches) {

                var batchResults: List<IndexedResult>

                try {

                    batchResults = processBatchWithFallback(
                        batch = batch,
                        fields = fields,
                        freeTextPrompt = freeTextPrompt,
                        isFreeTextMode = isFreeTextMode
                    )

                } catch (e: DailyQuotaExceededException) {

                    /**
                     * تجاوز الحصة اليومية:
                     *
                     * لا نقوم بتقسيم الدفعة ولا نعيد الطلب.
                     * نسجل الصور الحالية كفاشلة ونوقف بقية الدفعات.
                     */

                    for (document in batch) {

                        val errorResult = ExtractionResult(
                            fileName = document.fileName,
                            status = "error",
                            errorMessage = e.message
                                ?: "تم تجاوز الحصة اليومية لخدمة الذكاء الاصطناعي."
                        )

                        allResults.add(errorResult)

                        processedCount++

                        onProgress(
                            processedCount,
                            total,
                            errorResult
                        )
                    }

                    break

                } catch (e: Exception) {

                    /**
                     * خطأ عادي في الدفعة.
                     *
                     * لا نوقف كامل العملية.
                     * نسجل كل صورة في الدفعة كفاشلة.
                     */

                    batchResults = batch.map { document ->

                        IndexedResult(
                            index = document.index,
                            fileName = document.fileName,
                            result = ExtractionResult(
                                fileName = document.fileName,
                                status = "error",
                                errorMessage = getErrorMessage(e)
                            )
                        )
                    }
                }

                /**
                 * ترتيب النتائج حسب ترتيب الصور الأصلي.
                 */
                val orderedBatchResults =
                    batchResults.sortedBy { it.index }

                for (indexedResult in orderedBatchResults) {

                    val result = indexedResult.result.copy(
                        fileName = indexedResult.fileName
                    )

                    allResults.add(result)

                    processedCount++

                    onProgress(
                        processedCount,
                        total,
                        result
                    )
                }

                /**
                 * تأخير بسيط بين الدفعات.
                 *
                 * يساعد على تقليل الضغط على API.
                 */
                if (batch !== batches.last()) {
                    delay(1000L)
                }
            }

            allResults
        }
    }

    // ============================================================
    // معالجة الدفعة مع Fallback
    // ============================================================

    private suspend fun processBatchWithFallback(
        batch: List<DocumentItem>,
        fields: List<ExtractionField>,
        freeTextPrompt: String,
        isFreeTextMode: Boolean
    ): List<IndexedResult> {

        /**
         * في حالة وجود أكثر من صورة:
         * نرسلها في طلب واحد.
         *
         * إذا فشل الطلب بسبب مشكلة عادية في تحليل المجموعة،
         * نقسمها إلى مجموعات أصغر.
         *
         * أما 429 بسبب الحصة اليومية فلا نقسم ولا نعيد المحاولة.
         */

        try {

            val preparedImages = batch.map { document ->
                prepareImage(document.uri)
            }

            val prompt = if (isFreeTextMode) {
                DynamicPromptBuilder.buildFreeTextPrompt(
                    freeTextPrompt
                )
            } else {
                DynamicPromptBuilder.buildFieldsPrompt(
                    fields
                )
            }

            val response = callVisionApiWithRetry(
                prompt = prompt,
                images = preparedImages
            )

            val parsedResults = parseBatchResponse(
                response = response,
                expectedCount = batch.size
            )

            if (parsedResults.size != batch.size) {
                throw IOException(
                    "عدد النتائج (${parsedResults.size}) لا يطابق عدد الصور (${batch.size})"
                )
            }

            return batch.mapIndexed { localIndex, document ->

                val parsedResult = parsedResults[localIndex]

                IndexedResult(
                    index = document.index,
                    fileName = document.fileName,
                    result = parsedResult.copy(
                        fileName = document.fileName,
                        status = "success"
                    )
                )
            }

        } catch (e: DailyQuotaExceededException) {

            /**
             * مهم جدًا:
             * لا نقوم بالـ fallback عند تجاوز الحصة.
             */
            throw e

        } catch (e: Exception) {

            /**
             * إذا كانت الصورة واحدة فلا يوجد شيء لتقسيمه.
             */
            if (batch.size <= 1) {

                val document = batch.first()

                return listOf(
                    IndexedResult(
                        index = document.index,
                        fileName = document.fileName,
                        result = ExtractionResult(
                            fileName = document.fileName,
                            status = "error",
                            errorMessage = getErrorMessage(e)
                        )
                    )
                )
            }

            /**
             * تقسيم الدفعة إلى نصفين.
             */
            val middle = batch.size / 2

            val firstHalf = batch.subList(
                0,
                middle
            )

            val secondHalf = batch.subList(
                middle,
                batch.size
            )

            val firstResults = processBatchWithFallback(
                batch = firstHalf,
                fields = fields,
                freeTextPrompt = freeTextPrompt,
                isFreeTextMode = isFreeTextMode
            )

            val secondResults = processBatchWithFallback(
                batch = secondHalf,
                fields = fields,
                freeTextPrompt = freeTextPrompt,
                isFreeTextMode = isFreeTextMode
            )

            return firstResults + secondResults
        }
    }

    // ============================================================
    // API Retry
    // ============================================================

    private suspend fun callVisionApiWithRetry(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRY_ATTEMPTS) {

            if (attempt > 0) {
                delay(
                    RETRY_DELAYS_MS[
                        attempt.coerceAtMost(
                            RETRY_DELAYS_MS.lastIndex
                        )
                    ]
                )
            }

            try {

                return callVisionApi(
                    prompt = prompt,
                    images = images
                )

            } catch (e: DailyQuotaExceededException) {

                /**
                 * لا تعيد المحاولة عند الحصة اليومية.
                 */
                throw e

            } catch (e: Exception) {

                lastException = e

                if (!isRetryableError(e)) {
                    throw e
                }
            }
        }

        throw lastException
            ?: IOException("فشلت جميع محاولات الاتصال بالذكاء الاصطناعي.")
    }

    // ============================================================
    // اختيار Provider
    // ============================================================

    private fun callVisionApi(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        return when (settings.provider.lowercase()) {

            "gemini" -> {
                callGeminiVision(
                    prompt = prompt,
                    images = images
                )
            }

            "openrouter" -> {
                callOpenAICompatibleVision(
                    baseUrl = "https://openrouter.ai/api/v1/chat/completions",
                    apiKey = settings.openrouterKey,
                    model = settings.openrouterModel,
                    prompt = prompt,
                    images = images,
                    providerName = "OpenRouter"
                )
            }

            "openai" -> {
                callOpenAICompatibleVision(
                    baseUrl = "https://api.openai.com/v1/chat/completions",
                    apiKey = settings.openaiKey,
                    model = settings.openaiModel,
                    prompt = prompt,
                    images = images,
                    providerName = "OpenAI"
                )
            }

            "groq" -> {
                callOpenAICompatibleVision(
                    baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = settings.groqKey,
                    model = settings.groqModel,
                    prompt = prompt,
                    images = images,
                    providerName = "Groq"
                )
            }

            "mistral" -> {
                callOpenAICompatibleVision(
                    baseUrl = "https://api.mistral.ai/v1/chat/completions",
                    apiKey = settings.mistralKey,
                    model = settings.mistralModel,
                    prompt = prompt,
                    images = images,
                    providerName = "Mistral"
                )
            }

            "custom" -> {

                if (settings.customUrl.isBlank()) {
                    throw IOException(
                        "Custom API URL is empty"
                    )
                }

                callOpenAICompatibleVision(
                    baseUrl = settings.customUrl,
                    apiKey = settings.customKey,
                    model = settings.customModel,
                    prompt = prompt,
                    images = images,
                    providerName = "Custom"
                )
            }

            else -> {
                throw IOException(
                    "Unsupported AI provider: ${settings.provider}"
                )
            }
        }
    }

    // ============================================================
    // Gemini Vision
    // ============================================================

    private fun callGeminiVision(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        val apiKey = settings.geminiKey

        if (apiKey.isBlank()) {
            throw IOException(
                "Gemini API key is empty"
            )
        }

        val model = settings.geminiModel
            .ifBlank {
                DEFAULT_GEMINI_MODEL
            }

        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$model:generateContent?key=$apiKey"

        val parts = JSONArray()

        parts.put(
            JSONObject().apply {
                put("text", prompt)
            }
        )

        images.forEach { image ->

            parts.put(
                JSONObject().apply {
                    put(
                        "inline_data",
                        JSONObject().apply {
                            put(
                                "mime_type",
                                image.mimeType
                            )

                            put(
                                "data",
                                image.base64
                            )
                        }
                    )
                }
            )
        }

        val requestJson = JSONObject().apply {

            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put(
                            "parts",
                            parts
                        )
                    }
                )
            )

            put(
                "generationConfig",
                JSONObject().apply {
                    put(
                        "temperature",
                        0.1
                    )

                    put(
                        "maxOutputTokens",
                        8192
                    )

                    put(
                        "responseMimeType",
                        "application/json"
                    )
                }
            )
        }

        val requestBody = requestJson
            .toString()
            .toRequestBody(
                "application/json; charset=utf-8".toMediaType()
            )

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .addHeader(
                "Content-Type",
                "application/json"
            )
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {

                if (response.code == 429) {

                    if (isGeminiDailyQuotaResponse(body)) {

                        throw DailyQuotaExceededException(
                            buildGeminiQuotaMessage(body)
                        )
                    }

                    throw IOException(
                        "Gemini HTTP 429: ${extractApiErrorMessage(body)}"
                    )
                }

                if (
                    response.code == 500 ||
                    response.code == 502 ||
                    response.code == 503 ||
                    response.code == 504
                ) {
                    throw IOException(
                        "Gemini HTTP ${response.code}: " +
                                extractApiErrorMessage(body)
                    )
                }

                throw IOException(
                    "Gemini HTTP ${response.code}: " +
                            extractApiErrorMessage(body)
                )
            }

            return extractGeminiText(body)
        }
    }

    // ============================================================
    // OpenAI-compatible Vision
    // ============================================================

    private fun callOpenAICompatibleVision(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        images: List<PreparedImage>,
        providerName: String
    ): String {

        if (apiKey.isBlank()) {
            throw IOException(
                "$providerName API key is empty"
            )
        }

        if (model.isBlank()) {
            throw IOException(
                "$providerName model is empty"
            )
        }

        val content = JSONArray()

        content.put(
            JSONObject().apply {
                put(
                    "type",
                    "text"
                )

                put(
                    "text",
                    prompt
                )
            }
        )

        images.forEach { image ->

            content.put(
                JSONObject().apply {

                    put(
                        "type",
                        "image_url"
                    )

                    put(
                        "image_url",
                        JSONObject().apply {

                            put(
                                "url",
                                "data:${image.mimeType};base64,${image.base64}"
                            )
                        }
                    )
                }
            )
        }

        val message = JSONObject().apply {

            put(
                "role",
                "user"
            )

            put(
                "content",
                content
            )
        }

        val requestJson = JSONObject().apply {

            put(
                "model",
                model
            )

            put(
                "messages",
                JSONArray().put(message)
            )

            put(
                "temperature",
                0.1
            )

            put(
                "max_tokens",
                8192
            )

            put(
                "response_format",
                JSONObject().apply {
                    put(
                        "type",
                        "json_object"
                    )
                }
            )
        }

        val requestBody = requestJson
            .toString()
            .toRequestBody(
                "application/json; charset=utf-8".toMediaType()
            )

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .addHeader(
                "Content-Type",
                "application/json"
            )

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader(
                "Authorization",
                "Bearer $apiKey"
            )
        }

        if (providerName == "OpenRouter") {
            requestBuilder.addHeader(
                "HTTP-Referer",
                "https://github.com/"
            )

            requestBuilder.addHeader(
                "X-Title",
                "UniversalDocExtractor"
            )
        }

        client.newCall(
            requestBuilder.build()
        ).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {

                /**
                 * 429:
                 * لا نعتبره خطأ قابلًا لإعادة المحاولة تلقائيًا.
                 */
                if (response.code == 429) {
                    throw IOException(
                        "$providerName HTTP 429: " +
                                extractApiErrorMessage(body)
                    )
                }

                if (
                    response.code == 500 ||
                    response.code == 502 ||
                    response.code == 503 ||
                    response.code == 504
                ) {
                    throw IOException(
                        "$providerName HTTP ${response.code}: " +
                                extractApiErrorMessage(body)
                    )
                }

                throw IOException(
                    "$providerName HTTP ${response.code}: " +
                            extractApiErrorMessage(body)
                )
            }

            return extractOpenAICompatibleText(
                body
            )
        }
    }

    // ============================================================
    // استخراج نص Gemini
    // ============================================================

    private fun extractGeminiText(
        body: String
    ): String {

        try {

            val root = JSONObject(body)

            val candidates =
                root.optJSONArray("candidates")

            if (
                candidates == null ||
                candidates.length() == 0
            ) {
                throw IOException(
                    "Gemini returned no candidates: " +
                            extractApiErrorMessage(body)
                )
            }

            val candidate =
                candidates.optJSONObject(0)
                    ?: throw IOException(
                        "Gemini candidate is invalid"
                    )

            val content =
                candidate.optJSONObject("content")
                    ?: throw IOException(
                        "Gemini content is missing"
                    )

            val parts =
                content.optJSONArray("parts")
                    ?: throw IOException(
                        "Gemini parts are missing"
                    )

            val result = StringBuilder()

            for (i in 0 until parts.length()) {

                val part =
                    parts.optJSONObject(i)
                        ?: continue

                val text =
                    part.optString(
                        "text",
                        ""
                    )

                if (text.isNotBlank()) {
                    result.append(text)
                }
            }

            if (result.isBlank()) {
                throw IOException(
                    "Gemini returned empty text"
                )
            }

            return result.toString()

        } catch (e: IOException) {
            throw e

        } catch (e: Exception) {
            throw IOException(
                "Failed to parse Gemini response: " +
                        (e.message ?: "Unknown error")
            )
        }
    }

    // ============================================================
    // استخراج نص OpenAI compatible
    // ============================================================

    private fun extractOpenAICompatibleText(
        body: String
    ): String {

        try {

            val root = JSONObject(body)

            val choices =
                root.optJSONArray("choices")

            if (
                choices == null ||
                choices.length() == 0
            ) {
                throw IOException(
                    "API returned no choices: " +
                            extractApiErrorMessage(body)
                )
            }

            val choice =
                choices.optJSONObject(0)
                    ?: throw IOException(
                        "API choice is invalid"
                    )

            val message =
                choice.optJSONObject("message")
                    ?: throw IOException(
                        "API message is missing"
                    )

            val content =
                message.optString(
                    "content",
                    ""
                )

            if (content.isBlank()) {
                throw IOException(
                    "API returned empty content"
                )
            }

            return content

        } catch (e: IOException) {
            throw e

        } catch (e: Exception) {
            throw IOException(
                "Failed to parse API response: " +
                        (e.message ?: "Unknown error")
            )
        }
    }

    // ============================================================
    // تحليل نتيجة الدفعة
    // ============================================================

    private fun parseBatchResponse(
        response: String,
        expectedCount: Int
    ): List<ExtractionResult> {

        val cleanJson = extractJson(
            response
        )

        try {

            val root = JSONObject(
                cleanJson
            )

            /**
             * بعض النماذج تعيد:
             *
             * {
             *   "results": [...]
             * }
             */

            val wrapperKeys = listOf(
                "results",
                "documents",
                "items",
                "data"
            )

            for (key in wrapperKeys) {

                val array =
                    root.optJSONArray(key)

                if (array != null) {
                    return parseResultsArray(
                        array
                    )
                }
            }

            /**
             * إذا كانت صورة واحدة قد يعيد النموذج
             * كائنًا واحدًا بدل مصفوفة.
             */
            if (expectedCount == 1) {

                return listOf(
                    parseResultObject(root)
                )
            }

        } catch (_: Exception) {
            // نحاول Array بالأسفل
        }

        try {

            val array = JSONArray(
                cleanJson
            )

            return parseResultsArray(
                array
            )

        } catch (e: Exception) {

            throw IOException(
                "فشل تحليل JSON من الذكاء الاصطناعي: " +
                        (
                            e.message
                                ?: "Invalid JSON"
                        )
            )
        }
    }

    private fun parseResultsArray(
        array: JSONArray
    ): List<ExtractionResult> {

        val results =
            mutableListOf<ExtractionResult>()

        for (i in 0 until array.length()) {

            val obj =
                array.optJSONObject(i)
                    ?: continue

            results.add(
                parseResultObject(obj)
            )
        }

        return results
    }

    private fun parseResultObject(
        obj: JSONObject
    ): ExtractionResult {

        val values =
            mutableMapOf<String, String>()

        /**
         * ندعم:
         *
         * {
         *   "image_index": 1,
         *   "اسم الطالب": "...",
         *   "رقم القيد": "..."
         * }
         *
         * وكذلك:
         *
         * {
         *   "image_index": 1,
         *   "values": {
         *      "اسم الطالب": "..."
         *   }
         * }
         */

        val nestedValues =
            obj.optJSONObject("values")

        if (nestedValues != null) {

            val keys =
                nestedValues.keys()

            while (keys.hasNext()) {

                val key = keys.next()

                values[key] =
                    nestedValues.optString(
                        key,
                        ""
                    )
            }

        } else {

            val keys = obj.keys()

            while (keys.hasNext()) {

                val key = keys.next()

                if (
                    key == "image_index" ||
                    key == "fileName" ||
                    key == "status" ||
                    key == "errorMessage"
                ) {
                    continue
                }

                val value =
                    obj.opt(key)

                when (value) {

                    is JSONArray -> {
                        values[key] =
                            value.toString()
                    }

                    is JSONObject -> {
                        values[key] =
                            value.toString()
                    }

                    null -> {
                        values[key] = ""
                    }

                    else -> {
                        values[key] =
                            value.toString()
                    }
                }
            }
        }

        return ExtractionResult(
            fileName = obj.optString(
                "fileName",
                ""
            ),
            values = values,
            status = obj.optString(
                "status",
                "success"
            ),
            errorMessage = obj.optString(
                "errorMessage",
                ""
            )
        )
    }

    // ============================================================
    // استخراج JSON من النص
    // ============================================================

    private fun extractJson(
        response: String
    ): String {

        var text = response.trim()

        /**
         * إزالة Markdown fences.
         */
        if (text.startsWith("```")) {

            text = text
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }

        /**
         * البحث عن Array أولاً.
         */
        val arrayStart = text.indexOf("[")
        val arrayEnd = text.lastIndexOf("]")

        if (
            arrayStart >= 0 &&
            arrayEnd > arrayStart
        ) {
            return text.substring(
                arrayStart,
                arrayEnd + 1
            )
        }

        /**
         * ثم البحث عن Object.
         */
        val objectStart = text.indexOf("{")
        val objectEnd = text.lastIndexOf("}")

        if (
            objectStart >= 0 &&
            objectEnd > objectStart
        ) {
            return text.substring(
                objectStart,
                objectEnd + 1
            )
        }

        throw IOException(
            "لم يتم العثور على JSON صالح في استجابة الذكاء الاصطناعي."
        )
    }

    // ============================================================
    // تجهيز الصورة
    // ============================================================

    private fun prepareImage(
        uri: Uri
    ): PreparedImage {

        val resolver =
            context.contentResolver

        val inputStream =
            resolver.openInputStream(uri)
                ?: throw IOException(
                    "تعذر فتح الصورة: $uri"
                )

        val bitmap = try {

            BitmapFactory.decodeStream(
                inputStream
            )
                ?: throw IOException(
                    "تعذر قراءة الصورة: $uri"
                )

        } finally {
            inputStream.close()
        }

        val resizedBitmap =
            resizeBitmapIfNeeded(
                bitmap
            )

        val outputStream =
            java.io.ByteArrayOutputStream()

        try {

            resizedBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                JPEG_QUALITY,
                outputStream
            )

            val bytes =
                outputStream.toByteArray()

            return PreparedImage(
                mimeType = "image/jpeg",
                base64 = Base64.encodeToString(
                    bytes,
                    Base64.NO_WRAP
                )
            )

        } finally {

            outputStream.close()

            if (resizedBitmap !== bitmap) {
                resizedBitmap.recycle()
            }

            bitmap.recycle()
        }
    }

    private fun resizeBitmapIfNeeded(
        bitmap: Bitmap
    ): Bitmap {

        val width = bitmap.width
        val height = bitmap.height

        if (
            width <= MAX_IMAGE_DIMENSION &&
            height <= MAX_IMAGE_DIMENSION
        ) {
            return bitmap
        }

        val scale =
            minOf(
                MAX_IMAGE_DIMENSION.toFloat() /
                        width.toFloat(),
                MAX_IMAGE_DIMENSION.toFloat() /
                        height.toFloat()
            )

        val newWidth =
            (width * scale).toInt()

        val newHeight =
            (height * scale).toInt()

        return Bitmap.createScaledBitmap(
            bitmap,
            newWidth,
            newHeight,
            true
        )
    }

    // ============================================================
    // اسم الملف
    // ============================================================

    private fun getFileName(
    uri: Uri
): String {

    val queriedName = try {

        context.contentResolver
            .query(
                uri,
                arrayOf(
                    android.provider.OpenableColumns.DISPLAY_NAME
                ),
                null,
                null,
                null
            )
            ?.use { cursor ->

                if (cursor.moveToFirst()) {

                    val index =
                        cursor.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME
                        )

                    if (index >= 0) {
                        cursor.getString(index)
                            ?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }

                } else {
                    null
                }
            }

    } catch (_: Exception) {
        null
    }

    return queriedName
        ?: uri.lastPathSegment
            ?.substringAfterLast("/")
            ?.ifBlank {
                "document"
            }
        ?: "document"
    }
    // ============================================================
    // تحديد أخطاء إعادة المحاولة
    // ============================================================

    private fun isRetryableError(
        error: Throwable
    ): Boolean {

        /**
         * Timeout:
         * يمكن إعادة المحاولة.
         */
        if (error is SocketTimeoutException) {
            return true
        }

        val message =
            buildFullErrorMessage(error)
                .lowercase()

        /**
         * 429 غير قابل لإعادة المحاولة هنا.
         *
         * لأنه قد يكون:
         * - daily quota
         * - RPM
         * - billing quota
         *
         * وفي جميع الحالات لا نريد إرسال أربع محاولات
         * إضافية بشكل أعمى.
         */
        if (
            message.contains("429") ||
            message.contains("resource_exhausted") ||
            message.contains("quota")
        ) {
            return false
        }

        return message.contains("http 500") ||
                message.contains("http 502") ||
                message.contains("http 503") ||
                message.contains("http 504") ||
                message.contains("temporarily unavailable") ||
                message.contains("temporary unavailable") ||
                message.contains("connection reset") ||
                message.contains("connection refused") ||
                message.contains("failed to connect") ||
                message.contains("timeout")
    }

    // ============================================================
    // اكتشاف Gemini Daily Quota
    // ============================================================

    private fun isGeminiDailyQuotaResponse(
        body: String
    ): Boolean {

        val lower =
            body.lowercase()

        /**
         * المؤشرات الأقوى التي ظهرت في استجابة Gemini
         * التي واجهتها في المشروع.
         */
        if (
            lower.contains(
                "generate_content_free_tier_requests"
            )
        ) {
            return true
        }

        if (
            lower.contains(
                "generate_requests_per_day"
            ) &&
            lower.contains("free")
        ) {
            return true
        }

        if (
            lower.contains("perday") &&
            lower.contains("free")
        ) {
            return true
        }

        if (
            lower.contains("quotavalue") &&
            lower.contains("free")
        ) {
            return true
        }

        if (
            lower.contains(
                "generaterequestsperdaypermodelfreetier"
            )
        ) {
            return true
        }

        return false
    }

    // ============================================================
    // رسالة الحصة اليومية
    // ============================================================

    private fun buildGeminiQuotaMessage(
        body: String
    ): String {

        var quotaValue = ""

        var retryDelay = ""

        try {

            val root =
                JSONObject(body)

            val details =
                root.optJSONArray(
                    "details"
                )

            if (details != null) {

                for (i in 0 until details.length()) {

                    val item =
                        details.optJSONObject(i)
                            ?: continue

                    val quotaFailure =
                        item.optJSONObject(
                            "quotaFailure"
                        )

                    if (quotaFailure != null) {

                        val violations =
                            quotaFailure.optJSONArray(
                                "violations"
                            )

                        if (violations != null) {

                            for (
                                j in 0 until violations.length()
                            ) {

                                val violation =
                                    violations.optJSONObject(j)
                                        ?: continue

                                val value =
                                    violation.optString(
                                        "quotaValue",
                                        ""
                                    )

                                if (value.isNotBlank()) {
                                    quotaValue = value
                                    break
                                }
                            }
                        }
                    }

                    val retryInfo =
                        item.optJSONObject(
                            "retryDelay"
                        )

                    if (retryInfo != null) {

                        retryDelay =
                            retryInfo.optString(
                                "retryDelay",
                                ""
                            )
                    }

                    if (retryDelay.isBlank()) {

                        val retryInfo2 =
                            item.optJSONObject(
                                "retryInfo"
                            )

                        if (retryInfo2 != null) {
                            retryDelay =
                                retryInfo2.optString(
                                    "retryDelay",
                                    ""
                                )
                        }
                    }
                }
            }

        } catch (_: Exception) {
        }

        val quotaPart =
            if (quotaValue.isNotBlank()) {
                " الحد المسموح الظاهر في الاستجابة: $quotaValue."
            } else {
                ""
            }

        val retryPart =
            if (retryDelay.isNotBlank()) {
                " الخادم يقترح الانتظار $retryDelay قبل المحاولة التالية."
            } else {
                ""
            }

        return "تم تجاوز حصة Gemini الحالية للطلبات. " +
                "هذا ليس خطأ في الصورة أو استخراج البيانات؛ " +
                "إنما بسبب حدود استخدام API أو الخطة الحالية." +
                quotaPart +
                retryPart +
                " لن يعيد التطبيق إرسال الطلب تلقائيًا حتى لا يستهلك محاولات إضافية."
    }

    // ============================================================
    // قراءة رسالة خطأ API
    // ============================================================

    private fun extractApiErrorMessage(
        body: String
    ): String {

        if (body.isBlank()) {
            return "Empty response"
        }

        return try {

            val root =
                JSONObject(body)

            val error =
                root.optJSONObject("error")

            if (error != null) {

                val message =
                    error.optString(
                        "message",
                        ""
                    )

                val status =
                    error.optString(
                        "status",
                        ""
                    )

                when {
                    message.isNotBlank() &&
                            status.isNotBlank() -> {
                        "$status: $message"
                    }

                    message.isNotBlank() -> {
                        message
                    }

                    status.isNotBlank() -> {
                        status
                    }

                    else -> {
                        body.take(1000)
                    }
                }

            } else {

                body.take(1000)
            }

        } catch (_: Exception) {

            body.take(1000)
        }
    }

    // ============================================================
    // رسالة الخطأ النهائية
    // ============================================================

    private fun getErrorMessage(
        error: Throwable
    ): String {

        val message =
            buildFullErrorMessage(error)

        return if (message.isBlank()) {
            "Unknown error"
        } else {
            message
        }
    }

    private fun buildFullErrorMessage(
        error: Throwable
    ): String {

        val messages =
            mutableListOf<String>()

        var current: Throwable? =
            error

        val visited =
            mutableSetOf<Throwable>()

        while (
            current != null &&
            visited.add(current)
        ) {

            val message =
                current.message

            if (!message.isNullOrBlank()) {

                messages.add(
                    "${current.javaClass.simpleName}: $message"
                )
            }

            current =
                current.cause
        }

        return messages.joinToString(
            separator = " -> "
        )
    }
}
