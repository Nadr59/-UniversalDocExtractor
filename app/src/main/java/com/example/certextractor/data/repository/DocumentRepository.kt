package com.example.certextractor.data.repository

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.example.certextractor.data.local.AiSettings
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import com.example.certextractor.utils.DynamicPromptBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.math.max

class DocumentRepository(
    private val context: Context
) {

    companion object {

        private const val TAG = "DocumentRepository"

        private const val DEFAULT_GEMINI_MODEL =
            "gemini-2.5-flash"

        private const val OPENROUTER_URL =
            "https://openrouter.ai/api/v1/chat/completions"

        private const val OPENAI_URL =
            "https://api.openai.com/v1/chat/completions"

        private const val GROQ_URL =
            "https://api.groq.com/openai/v1/chat/completions"

        private const val MISTRAL_URL =
            "https://api.mistral.ai/v1/chat/completions"

        private const val MAX_IMAGE_DIMENSION = 2048

        private const val JPEG_QUALITY = 85

        private const val CONNECT_TIMEOUT_SECONDS = 120L

        private const val READ_TIMEOUT_SECONDS = 180L

        /*
         * الحد الأقصى للصور في طلب واحد.
         *
         * مثال:
         *
         * 200 صورة
         *
         * 3 + 3 + 3 + ... + 2
         *
         * وكل صورة تنتج نتيجة مستقلة.
         */
        private const val GEMINI_BATCH_SIZE = 3
    }

    /*
     * إعدادات الذكاء الاصطناعي.
     *
     * DocumentViewModel يعتمد على:
     *
     * repository.settings
     *
     * لذلك يجب أن تكون هذه الخاصية public.
     */
    val settings: AiSettings by lazy {
        AiSettings(context)
    }

    private val httpClient: OkHttpClient by lazy {

        OkHttpClient.Builder()
            .connectTimeout(
                CONNECT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            .readTimeout(
                READ_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                READ_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            .build()
    }

    // =========================================================================
    // معالجة صورة واحدة
    // =========================================================================

    suspend fun processDocument(
        uri: Uri,
        fields: List<ExtractionField>,
        freeTextPrompt: String = "",
        useFreeText: Boolean = false
    ): ExtractionResult {

        return withContext(Dispatchers.IO) {

            val fileName = getFileName(uri)

            try {

                val image = prepareImage(uri)

                val prompt =
                    if (useFreeText) {

                        DynamicPromptBuilder.buildFreeTextPrompt(
                            freeTextPrompt
                        )

                    } else {

                        DynamicPromptBuilder.buildFieldsPrompt(
                            fields
                        )
                    }

                val response =
                    callVisionApi(
                        prompt = prompt,
                        images = listOf(image)
                    )

                val results =
                    parseBatchResponse(
                        response = response,
                        fileNames = listOf(fileName)
                    )

                results.firstOrNull()
                    ?: ExtractionResult(
                        fileName = fileName,
                        status = "error",
                        errorMessage =
                            "لم يتم العثور على نتيجة في استجابة الذكاء الاصطناعي"
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

    // =========================================================================
    // معالجة جميع الصور
    // =========================================================================

    suspend fun processBatch(
        uris: List<Uri>,
        fields: List<ExtractionField>,
        freeTextPrompt: String = "",
        isFreeTextMode: Boolean = false,
        onProgress: suspend (
            current: Int,
            total: Int,
            result: ExtractionResult
        ) -> Unit = { _, _, _ -> }
    ): List<ExtractionResult> {

        /*
         * مهم جدًا:
         *
         * يتم استدعاء processBatch من ViewModel.
         *
         * لذلك نضع العملية كاملة داخل Dispatchers.IO.
         *
         * هذا يمنع:
         *
         * NetworkOnMainThreadException
         *
         * ويضمن أن قراءة الصور وطلبات HTTP لا تعمل على Main Thread.
         */
        return withContext(Dispatchers.IO) {

            if (uris.isEmpty()) {
                return@withContext emptyList()
            }

            val total = uris.size

            val documents =
                uris.mapIndexed { index, uri ->

                    DocumentItem(
                        index = index,
                        uri = uri,
                        fileName = getFileName(uri)
                    )
                }

            val allResults =
                mutableListOf<IndexedResult>()

            /*
             * تقسيم الصور إلى مجموعات من 3.
             */
            val batches =
                documents.chunked(
                    GEMINI_BATCH_SIZE
                )

            var processedCount = 0

            for (batch in batches) {

                val batchResults =
                    processBatchWithFallback(
                        batch = batch,
                        fields = fields,
                        freeTextPrompt = freeTextPrompt,
                        useFreeText = isFreeTextMode
                    )

                /*
                 * نضيف النتائج بالترتيب.
                 */
                allResults.addAll(
                    batchResults
                )

                /*
                 * إرسال نتيجة كل صورة إلى ViewModel.
                 *
                 * هذا مهم لأن DocumentViewModel الحالي
                 * ينتظر:
                 *
                 * current
                 * total
                 * result
                 */
                for (indexedResult in batchResults) {

                    processedCount++

                    onProgress(
                        processedCount,
                        total,
                        indexedResult.result
                    )
                }

                /*
                 * إذا كان هناك المزيد من الصور،
                 * ننتظر ثانية واحدة بين الدفعات.
                 */
                if (processedCount < total) {
                    delay(1000L)
                }
            }

            /*
             * ترتيب نهائي حسب ترتيب الصور الأصلية.
             */
            allResults
                .sortedBy {
                    it.index
                }
                .map {
                    it.result
                }
        }
    }

    // =========================================================================
    // معالجة الدفعة مع Fallback
    // =========================================================================

    private suspend fun processBatchWithFallback(
        batch: List<DocumentItem>,
        fields: List<ExtractionField>,
        freeTextPrompt: String,
        useFreeText: Boolean
    ): List<IndexedResult> {

        if (batch.isEmpty()) {
            return emptyList()
        }

        return try {

            /*
             * تجهيز جميع الصور في الدفعة.
             */
            val images =
                batch.map {
                    prepareImage(it.uri)
                }

            val prompt =
                if (useFreeText) {

                    DynamicPromptBuilder.buildFreeTextPrompt(
                        freeTextPrompt
                    )

                } else {

                    DynamicPromptBuilder.buildFieldsPrompt(
                        fields
                    )
                }

            val response =
                callVisionApi(
                    prompt = prompt,
                    images = images
                )

            val fileNames =
                batch.map {
                    it.fileName
                }

            val parsedResults =
                parseBatchResponse(
                    response = response,
                    fileNames = fileNames
                )

            /*
             * يجب أن يكون لدينا نتيجة لكل صورة.
             */
            if (parsedResults.size != batch.size) {

                throw IOException(
                    "عدد النتائج لا يطابق عدد الصور: " +
                            "تم إرسال ${batch.size} صور، " +
                            "لكن تم استلام ${parsedResults.size} نتائج"
                )
            }

            batch.mapIndexed { index, document ->

                IndexedResult(
                    index = document.index,
                    result = parsedResults[index]
                )
            }

        } catch (e: Exception) {

            /*
             * إذا فشلت دفعة من أكثر من صورة،
             * نقسمها إلى نصفين.
             *
             * مثال:
             *
             * 3
             * ↓
             * 1 + 2
             *
             * وإذا فشلت 2:
             *
             * 1 + 1
             *
             * وهكذا لا تضيع جميع الصور بسبب صورة واحدة.
             */
            if (batch.size > 1) {

                val middle =
                    batch.size / 2

                val firstHalf =
                    batch.subList(
                        0,
                        middle
                    )

                val secondHalf =
                    batch.subList(
                        middle,
                        batch.size
                    )

                val firstResults =
                    processBatchWithFallback(
                        batch = firstHalf,
                        fields = fields,
                        freeTextPrompt = freeTextPrompt,
                        useFreeText = useFreeText
                    )

                val secondResults =
                    processBatchWithFallback(
                        batch = secondHalf,
                        fields = fields,
                        freeTextPrompt = freeTextPrompt,
                        useFreeText = useFreeText
                    )

                firstResults + secondResults

            } else {

                /*
                 * وصلت المشكلة إلى صورة واحدة.
                 *
                 * نسجل الخطأ بدل إسقاط الصورة.
                 */
                val document =
                    batch.first()

                listOf(
                    IndexedResult(
                        index = document.index,
                        result =
                            ExtractionResult(
                                fileName = document.fileName,
                                status = "error",
                                errorMessage =
                                    getErrorMessage(e)
                            )
                    )
                )
            }
        }
    }

    // =========================================================================
    // اختيار مزود الذكاء الاصطناعي
    // =========================================================================

    private fun callVisionApi(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        return when (
            settings.provider
                .trim()
                .lowercase()
        ) {

            "gemini",
            "google",
            "google gemini" -> {

                callGeminiVision(
                    prompt = prompt,
                    images = images
                )
            }

            "openrouter" -> {

                callOpenAICompatibleVision(
                    url = OPENROUTER_URL,
                    apiKey = settings.openrouterKey,
                    model = settings.openrouterModel,
                    prompt = prompt,
                    images = images
                )
            }

            "openai" -> {

                callOpenAICompatibleVision(
                    url = OPENAI_URL,
                    apiKey = settings.openaiKey,
                    model = settings.openaiModel,
                    prompt = prompt,
                    images = images
                )
            }

            "groq" -> {

                callOpenAICompatibleVision(
                    url = GROQ_URL,
                    apiKey = settings.groqKey,
                    model = settings.groqModel,
                    prompt = prompt,
                    images = images
                )
            }

            "mistral" -> {

                callOpenAICompatibleVision(
                    url = MISTRAL_URL,
                    apiKey = settings.mistralKey,
                    model = settings.mistralModel,
                    prompt = prompt,
                    images = images
                )
            }

            "custom" -> {

                callOpenAICompatibleVision(
                    url = settings.customUrl,
                    apiKey = settings.customKey,
                    model = settings.customModel,
                    prompt = prompt,
                    images = images
                )
            }

            else -> {

                callGeminiVision(
                    prompt = prompt,
                    images = images
                )
            }
        }
    }

    // =========================================================================
    // Gemini Vision
    // =========================================================================

    private fun callGeminiVision(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        val apiKey =
            settings.geminiKey.trim()

        if (apiKey.isBlank()) {

            throw IOException(
                "مفتاح Gemini API غير موجود"
            )
        }

        /*
         * النموذج الموجود في إعدادات التطبيق.
         *
         * إذا كان فارغًا نستخدم الافتراضي.
         */
        val configuredModel =
            settings.geminiModel
                .trim()
                .ifBlank {
                    DEFAULT_GEMINI_MODEL
                }

        /*
         * لا نستخدم نماذج قديمة بشكل إجباري.
         *
         * النموذج الذي اختاره المستخدم هو الأولوية.
         */
        val models =
            linkedSetOf(
                configuredModel
            )

        var lastError: Exception? = null

        for (model in models) {

            try {

                val url =
                    "https://generativelanguage.googleapis.com/" +
                            "v1beta/models/$model:generateContent" +
                            "?key=$apiKey"

                val parts =
                    JSONArray()

                /*
                 * النص.
                 */
                parts.put(
                    JSONObject().apply {

                        put(
                            "text",
                            prompt
                        )
                    }
                )

                /*
                 * الصور.
                 *
                 * كل صورة مستقلة.
                 */
                for (image in images) {

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

                val contents =
                    JSONArray()

                contents.put(
                    JSONObject().apply {

                        put(
                            "role",
                            "user"
                        )

                        put(
                            "parts",
                            parts
                        )
                    }
                )

                val generationConfig =
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

                val body =
                    JSONObject().apply {

                        put(
                            "contents",
                            contents
                        )

                        put(
                            "generationConfig",
                            generationConfig
                        )
                    }

                val requestBody =
                    body
                        .toString()
                        .toRequestBody(
                            "application/json; charset=utf-8"
                                .toMediaType()
                        )

                val request =
                    Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .addHeader(
                            "Content-Type",
                            "application/json"
                        )
                        .build()

                httpClient
                    .newCall(request)
                    .execute()
                    .use { response ->

                        val responseBody =
                            response.body
                                ?.string()
                                .orEmpty()

                        if (!response.isSuccessful) {

                            throw IOException(
                                "Gemini HTTP ${response.code}: " +
                                        "${response.message}\n" +
                                        "Response: $responseBody"
                            )
                        }

                        if (responseBody.isBlank()) {

                            throw IOException(
                                "Gemini أعاد استجابة فارغة"
                            )
                        }

                        val root =
                            JSONObject(responseBody)

                        val candidates =
                            root.optJSONArray(
                                "candidates"
                            )

                        if (
                            candidates == null ||
                            candidates.length() == 0
                        ) {

                            throw IOException(
                                "Gemini لم يُرجع candidates.\n" +
                                        "Response: $responseBody"
                            )
                        }

                        val candidate =
                            candidates.optJSONObject(0)
                                ?: throw IOException(
                                    "Gemini أعاد candidate غير صالح"
                                )

                        val content =
                            candidate.optJSONObject(
                                "content"
                            )
                                ?: throw IOException(
                                    "Gemini أعاد content غير موجود"
                                )

                        val responseParts =
                            content.optJSONArray(
                                "parts"
                            )
                                ?: throw IOException(
                                    "Gemini أعاد parts غير موجودة"
                                )

                        val textBuilder =
                            StringBuilder()

                        for (
                            i in
                            0 until responseParts.length()
                        ) {

                            val part =
                                responseParts
                                    .optJSONObject(i)
                                    ?: continue

                            val text =
                                part.optString(
                                    "text",
                                    ""
                                )

                            if (text.isNotBlank()) {

                                textBuilder.append(
                                    text
                                )
                            }
                        }

                        val result =
                            textBuilder
                                .toString()
                                .trim()

                        if (result.isBlank()) {

                            throw IOException(
                                "Gemini أعاد نصًا فارغًا.\n" +
                                        "Response: $responseBody"
                            )
                        }

                        return result
                    }

            } catch (e: SocketTimeoutException) {

                lastError =
                    IOException(
                        "$model: انتهت مهلة الاتصال بـ Gemini",
                        e
                    )

            } catch (e: Exception) {

                lastError = e
            }
        }

        throw IOException(
            buildString {

                appendLine("Gemini failed")

                if (lastError != null) {

                    append(
                        getErrorMessage(
                            lastError
                        )
                    )
                }
            },
            lastError
        )
    }

    // =========================================================================
    // OpenAI / OpenRouter / Groq / Mistral / Custom
    // =========================================================================

    private fun callOpenAICompatibleVision(
        url: String,
        apiKey: String,
        model: String,
        prompt: String,
        images: List<PreparedImage>
    ): String {

        if (apiKey.isBlank()) {

            throw IOException(
                "API key غير موجود"
            )
        }

        if (url.isBlank()) {

            throw IOException(
                "رابط API المخصص غير موجود"
            )
        }

        if (model.isBlank()) {

            throw IOException(
                "اسم النموذج غير موجود"
            )
        }

        val content =
            JSONArray()

        /*
         * Prompt.
         */
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

        /*
         * الصور.
         */
        for (image in images) {

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

        val messages =
            JSONArray()

        messages.put(
            JSONObject().apply {

                put(
                    "role",
                    "user"
                )

                put(
                    "content",
                    content
                )
            }
        )

        val body =
            JSONObject().apply {

                put(
                    "model",
                    model
                )

                put(
                    "messages",
                    messages
                )

                put(
                    "temperature",
                    0.1
                )

                put(
                    "max_tokens",
                    8192
                )

                /*
                 * نطلب JSON Object لأن بعض OpenAI-compatible
                 * APIs لا تقبل Array كجذر عند استخدام response_format.
                 *
                 * DynamicPromptBuilder يسمح أيضًا بإرجاع:
                 *
                 * {
                 *   "results": [...]
                 * }
                 */
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

        val requestBody =
            body
                .toString()
                .toRequestBody(
                    "application/json; charset=utf-8"
                        .toMediaType()
                )

        val request =
            Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader(
                    "Authorization",
                    "Bearer $apiKey"
                )
                .addHeader(
                    "Content-Type",
                    "application/json"
                )
                .build()

        httpClient
            .newCall(request)
            .execute()
            .use { response ->

                val responseBody =
                    response.body
                        ?.string()
                        .orEmpty()

                if (!response.isSuccessful) {

                    throw IOException(
                        "HTTP ${response.code}: " +
                                "${response.message}\n" +
                                "Response: $responseBody"
                    )
                }

                if (responseBody.isBlank()) {

                    throw IOException(
                        "API أعاد استجابة فارغة"
                    )
                }

                val root =
                    JSONObject(responseBody)

                val choices =
                    root.optJSONArray(
                        "choices"
                    )

                if (choices == null) {

                    throw IOException(
                        "API لم يُرجع choices.\n" +
                                "Response: $responseBody"
                    )
                }

                if (choices.length() == 0) {

                    throw IOException(
                        "API أعاد choices فارغة"
                    )
                }

                val choice =
                    choices.optJSONObject(0)
                        ?: throw IOException(
                            "API أعاد choice غير صالح"
                        )

                val message =
                    choice.optJSONObject(
                        "message"
                    )
                        ?: throw IOException(
                            "API أعاد message غير موجود"
                        )

                val contentValue =
                    message.opt("content")

                val result =
                    when (contentValue) {

                        is String -> {
                            contentValue.trim()
                        }

                        is JSONArray -> {
                            contentValue.toString()
                        }

                        else -> {
                            contentValue
                                ?.toString()
                                ?.trim()
                                .orEmpty()
                        }
                    }

                if (result.isBlank()) {

                    throw IOException(
                        "API أعاد content فارغًا"
                    )
                }

                return result
            }
    }

    // =========================================================================
    // تجهيز الصورة
    // =========================================================================

    private fun prepareImage(
        uri: Uri
    ): PreparedImage {

        val inputStream =
            context.contentResolver
                .openInputStream(uri)
                ?: throw IOException(
                    "تعذر فتح الصورة: $uri"
                )

        val originalBitmap =
            inputStream.use {
                BitmapFactory.decodeStream(it)
            }
                ?: throw IOException(
                    "تعذر قراءة الصورة: $uri"
                )

        var bitmapToCompress =
            originalBitmap

        try {

            val width =
                originalBitmap.width

            val height =
                originalBitmap.height

            val largestDimension =
                max(
                    width,
                    height
                )

            if (
                largestDimension >
                MAX_IMAGE_DIMENSION
            ) {

                val scale =
                    MAX_IMAGE_DIMENSION.toFloat() /
                            largestDimension.toFloat()

                val newWidth =
                    (width * scale)
                        .toInt()
                        .coerceAtLeast(1)

                val newHeight =
                    (height * scale)
                        .toInt()
                        .coerceAtLeast(1)

                bitmapToCompress =
                    Bitmap.createScaledBitmap(
                        originalBitmap,
                        newWidth,
                        newHeight,
                        true
                    )
            }

            val output =
                ByteArrayOutputStream()

            val compressed =
                bitmapToCompress.compress(
                    Bitmap.CompressFormat.JPEG,
                    JPEG_QUALITY,
                    output
                )

            if (!compressed) {

                throw IOException(
                    "فشل ضغط الصورة"
                )
            }

            val bytes =
                output.toByteArray()

            if (bytes.isEmpty()) {

                throw IOException(
                    "فشل إنشاء بيانات الصورة"
                )
            }

            val base64 =
                android.util.Base64
                    .encodeToString(
                        bytes,
                        android.util.Base64.NO_WRAP
                    )

            return PreparedImage(
                base64 = base64,
                mimeType = "image/jpeg"
            )

        } finally {

            /*
             * تحرير الذاكرة مباشرة بعد تحويل الصورة
             * إلى JPEG/Base64.
             *
             * هذا مهم خصوصًا عند معالجة مئات الصور
             * على أجهزة ذات RAM محدودة.
             */
            if (
                bitmapToCompress !==
                originalBitmap
            ) {

                bitmapToCompress.recycle()
            }

            originalBitmap.recycle()
        }
    }

    // =========================================================================
    // تحليل JSON
    // =========================================================================

    private fun parseBatchResponse(
        response: String,
        fileNames: List<String>
    ): List<ExtractionResult> {

        val json =
            extractJson(response)

        val root =
            try {

                JsonParser.parseString(json)

            } catch (e: Exception) {

                throw IOException(
                    "فشل تحليل JSON:\n$json",
                    e
                )
            }

        /*
         * الحالة المثالية:
         *
         * [
         *   {
         *      "image_index": 1,
         *      "اسم الطالب": "...",
         *      ...
         *   },
         *   {
         *      "image_index": 2,
         *      ...
         *   }
         * ]
         */
        if (root.isJsonArray) {

            val array =
                root.asJsonArray

            return array.mapIndexed { index, element ->

                jsonElementToResult(
                    element = element,
                    fileName =
                        fileNames
                            .getOrNull(index)
                            .orEmpty()
                )
            }
        }

        /*
         * الحالة الثانية:
         *
         * {
         *   "results": [...]
         * }
         *
         * أو:
         *
         * {
         *   "documents": [...]
         * }
         */
        if (root.isJsonObject) {

            val obj =
                root.asJsonObject

            val possibleKeys =
                listOf(
                    "results",
                    "documents",
                    "items",
                    "data"
                )

            for (key in possibleKeys) {

                val element =
                    obj.get(key)

                if (
                    element != null &&
                    element.isJsonArray
                ) {

                    return element
                        .asJsonArray
                        .mapIndexed { index, item ->

                            jsonElementToResult(
                                element = item,
                                fileName =
                                    fileNames
                                        .getOrNull(index)
                                        .orEmpty()
                            )
                        }
                }
            }

            /*
             * إذا كانت صورة واحدة فقط،
             * يمكن أن يكون الجذر نفسه هو النتيجة.
             */
            if (fileNames.size == 1) {

                return listOf(
                    jsonElementToResult(
                        element = root,
                        fileName =
                            fileNames.first()
                    )
                )
            }

            /*
             * إذا كانت عدة صور ولكن النموذج أعاد Object
             * بدون مصفوفة نتائج، نعتبرها استجابة غير مكتملة.
             */
            throw IOException(
                "الاستجابة تحتوي JSON Object " +
                        "لكنها لا تحتوي مصفوفة نتائج مناسبة. " +
                        "عدد الصور: ${fileNames.size}"
            )
        }

        throw IOException(
            "صيغة JSON غير مدعومة"
        )
    }

    // =========================================================================
    // تحويل JSON إلى ExtractionResult
    // =========================================================================

    private fun jsonElementToResult(
        element: JsonElement,
        fileName: String
    ): ExtractionResult {

        if (!element.isJsonObject) {

            return ExtractionResult(
                fileName = fileName,
                status = "error",
                errorMessage =
                    "عنصر النتيجة ليس JSON Object"
            )
        }

        val obj =
            element.asJsonObject

        val values =
            linkedMapOf<String, String>()

        /*
         * هذه مفاتيح تقنية وليست حقول استخراج.
         */
        val ignoredKeys =
            setOf(
                "image_index",
                "imageIndex",
                "index",
                "fileName",
                "file_name",
                "status"
            )

        for ((key, value) in obj.entrySet()) {

            if (key in ignoredKeys) {
                continue
            }

            values[key] =
                when {

                    value.isJsonNull -> {
                        ""
                    }

                    value.isJsonPrimitive -> {

                        value
                            .asJsonPrimitive
                            .toString()
                            .removeSurrounding("\"")
                    }

                    else -> {

                        /*
                         * إذا كانت القيمة Array أو Object
                         * نحفظها كنص JSON.
                         */
                        value.toString()
                    }
                }
        }

        return ExtractionResult(
            fileName = fileName,
            values = values,
            status = "success",
            errorMessage = ""
        )
    }

    // =========================================================================
    // استخراج JSON من النص
    // =========================================================================

    private fun extractJson(
        response: String
    ): String {

        var text =
            response.trim()

        /*
         * إزالة Markdown code fences.
         *
         * ```json
         * [...]
         * ```
         */
        if (text.startsWith("```")) {

            text =
                text
                    .removePrefix("```json")
                    .removePrefix("```JSON")
                    .removePrefix("```")
                    .trim()

            if (text.endsWith("```")) {

                text =
                    text
                        .removeSuffix("```")
                        .trim()
            }
        }

        /*
         * إذا بدأت الاستجابة بمصفوفة JSON.
         */
        val arrayStart =
            text.indexOf("[")

        val arrayEnd =
            text.lastIndexOf("]")

        if (
            arrayStart >= 0 &&
            arrayEnd > arrayStart
        ) {

            return text.substring(
                arrayStart,
                arrayEnd + 1
            )
        }

        /*
         * البحث عن Object.
         */
        val objectStart =
            text.indexOf("{")

        val objectEnd =
            text.lastIndexOf("}")

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
            "لم يتم العثور على JSON صالح في الاستجابة:\n$text"
        )
    }

    // =========================================================================
    // الحصول على اسم الملف
    // =========================================================================

    private fun getFileName(
        uri: Uri
    ): String {

        var name: String? = null

        val cursor: Cursor? =
            try {

                context.contentResolver.query(
                    uri,
                    arrayOf(
                        OpenableColumns.DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )

            } catch (_: Exception) {

                null
            }

        cursor?.use {

            if (it.moveToFirst()) {

                val index =
                    it.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                    )

                if (index >= 0) {

                    name =
                        it.getString(index)
                }
            }
        }

        return name
            ?.takeIf {
                it.isNotBlank()
            }
            ?: uri.lastPathSegment
            ?: "document"
    }

    // =========================================================================
    // تشخيص الأخطاء
    // =========================================================================

    private fun getErrorMessage(
        throwable: Throwable
    ): String {

        val messages =
            mutableListOf<String>()

        var current: Throwable? =
            throwable

        while (current != null) {

            val message =
                current.message
                    ?.trim()
                    .orEmpty()

            if (message.isNotBlank()) {

                messages.add(
                    "${current.javaClass.simpleName}: $message"
                )
            }

            current =
                current.cause
        }

        return if (messages.isNotEmpty()) {

            messages
                .distinct()
                .joinToString(
                    separator = "\n"
                )

        } else {

            throwable
                .javaClass
                .simpleName
        }
    }

    // =========================================================================
    // Data Classes
    // =========================================================================

    private data class PreparedImage(
        val base64: String,
        val mimeType: String
    )

    private data class DocumentItem(
        val index: Int,
        val uri: Uri,
        val fileName: String
    )

    private data class IndexedResult(
        val index: Int,
        val result: ExtractionResult
    )
}
