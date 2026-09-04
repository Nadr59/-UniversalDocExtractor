package com.example.certextractor.data.repository

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class DocumentRepository @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "DocumentRepository"

        private const val DEFAULT_GEMINI_MODEL = "gemini-3.6-flash"

        private const val OPENROUTER_URL =
            "https://openrouter.ai/api/v1/chat/completions"

        private const val OPENAI_URL =
            "https://api.openai.com/v1/chat/completions"

        private const val GROQ_URL =
            "https://api.groq.com/openai/v1/chat/completions"

        private const val MISTRAL_URL =
            "https://api.mistral.ai/v1/chat/completions"

        private const val CUSTOM_URL = ""

        private const val MAX_IMAGE_DIMENSION = 2048
        private const val JPEG_QUALITY = 85

        private const val CONNECT_TIMEOUT_SECONDS = 120L
        private const val READ_TIMEOUT_SECONDS = 180L

        /*
         * كل طلب Gemini يحتوي على 3 صور كحد أقصى.
         *
         * مثال:
         * 200 صورة
         *   ↓
         * 3 + 3 + 3 + ... + 2
         *
         * وكل صورة تبقى نتيجة مستقلة.
         */
        private const val GEMINI_BATCH_SIZE = 3
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

    // -------------------------------------------------------------------------
    // إعدادات API
    // -------------------------------------------------------------------------

    private fun getApiKey(): String {
        return try {
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("api_key", "")
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun getProvider(): String {
        return try {
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("provider", "Gemini")
                .orEmpty()
        } catch (_: Exception) {
            "Gemini"
        }
    }

    private fun getModel(): String {
        return try {
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(
                    "model",
                    DEFAULT_GEMINI_MODEL
                )
                .orEmpty()
        } catch (_: Exception) {
            DEFAULT_GEMINI_MODEL
        }
    }

    // -------------------------------------------------------------------------
    // معالجة صورة واحدة
    // -------------------------------------------------------------------------

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

                val prompt = if (useFreeText) {
                    DynamicPromptBuilder.buildFreeTextPrompt(
                        freeTextPrompt
                    )
                } else {
                    DynamicPromptBuilder.buildFieldsPrompt(
                        fields
                    )
                }

                val response = callVisionApi(
                    prompt = prompt,
                    images = listOf(image)
                )

                val results = parseBatchResponse(
                    response = response,
                    fileNames = listOf(fileName)
                )

                results.firstOrNull()
                    ?: ExtractionResult(
                        fileName = fileName,
                        status = "error",
                        errorMessage = "لم يتم العثور على نتيجة في استجابة الذكاء الاصطناعي"
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

    // -------------------------------------------------------------------------
    // معالجة مجموعة الصور
    // -------------------------------------------------------------------------

    suspend fun processBatch(
        uris: List<Uri>,
        fields: List<ExtractionField>,
        freeTextPrompt: String = "",
        useFreeText: Boolean = false,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ExtractionResult> {

        /*
         * مهم جدًا:
         *
         * processDocuments() يتم استدعاؤها من ViewModel على Main.
         *
         * لذلك يجب أن يكون كامل جسم processBatch داخل IO،
         * وليس فقط بعض أجزاء الكود.
         *
         * هذا يمنع:
         *
         * NetworkOnMainThreadException
         *
         * ويضمن أن:
         * - قراءة الملفات
         * - تحويل الصور
         * - Base64
         * - HTTP requests
         * - Gemini
         * - OpenRouter
         * - OpenAI
         *
         * كلها تعمل خارج Main Thread.
         */
        return withContext(Dispatchers.IO) {

            if (uris.isEmpty()) {
                return@withContext emptyList()
            }

            val total = uris.size

            val documents = uris.mapIndexed { index, uri ->
                DocumentItem(
                    index = index,
                    uri = uri,
                    fileName = getFileName(uri)
                )
            }

            val allResults = mutableListOf<IndexedResult>()

            /*
             * Gemini:
             *
             * 3 صور في الطلب الواحد.
             *
             * إذا فشلت مجموعة من 3 صور:
             *
             * 3
             * ↓
             * 1 + 2
             * ↓
             * إذا فشلت 2:
             * 1 + 1
             *
             * وبذلك لا تضيع بقية الصور بسبب صورة واحدة.
             */
            val batches = documents.chunked(GEMINI_BATCH_SIZE)

            var processedCount = 0

            for (batch in batches) {

                val batchResults = processBatchWithFallback(
                    batch = batch,
                    fields = fields,
                    freeTextPrompt = freeTextPrompt,
                    useFreeText = useFreeText
                )

                allResults.addAll(batchResults)

                processedCount += batch.size

                onProgress(
                    processedCount,
                    total
                )

                /*
                 * تأخير بسيط بين الطلبات لتقليل الضغط على API.
                 *
                 * delay لا يحجز الخيط.
                 */
                if (processedCount < total) {
                    delay(1000L)
                }
            }

            /*
             * الترتيب النهائي حسب ترتيب الصور الأصلية.
             *
             * لا نعتمد على اسم الملف لأن أسماء الملفات قد تكون متشابهة
             * أو قد تكون متطابقة في بعض الحالات.
             */
            allResults
                .sortedBy { it.index }
                .map { it.result }
        }
    }

    // -------------------------------------------------------------------------
    // معالجة دفعة مع Fallback وتقسيم تلقائي
    // -------------------------------------------------------------------------

    private suspend fun processBatchWithFallback(
        batch: List<DocumentItem>,
        fields: List<ExtractionField>,
        freeTextPrompt: String,
        useFreeText: Boolean
    ): List<IndexedResult> {

        /*
         * هذه الدالة تُستدعى من داخل withContext(Dispatchers.IO)
         * الموجود في processBatch().
         */

        if (batch.isEmpty()) {
            return emptyList()
        }

        return try {

            val images = batch.map { document ->
                prepareImage(document.uri)
            }

            val prompt = if (useFreeText) {
                DynamicPromptBuilder.buildFreeTextPrompt(
                    freeTextPrompt
                )
            } else {
                DynamicPromptBuilder.buildFieldsPrompt(
                    fields
                )
            }

            val response = callVisionApi(
                prompt = prompt,
                images = images
            )

            val fileNames = batch.map {
                it.fileName
            }

            val parsedResults = parseBatchResponse(
                response = response,
                fileNames = fileNames
            )

            /*
             * يجب أن نحصل على نتيجة لكل صورة.
             *
             * إذا أرسلنا 3 صور ولكن Gemini أعاد نتيجتين فقط،
             * نعتبر الدفعة غير مكتملة ونستخدم التقسيم.
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
             * إذا كانت الدفعة تحتوي على أكثر من صورة،
             * نقسمها بدل إسقاط جميع الصور.
             */
            if (batch.size > 1) {

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
                    useFreeText = useFreeText
                )

                val secondResults = processBatchWithFallback(
                    batch = secondHalf,
                    fields = fields,
                    freeTextPrompt = freeTextPrompt,
                    useFreeText = useFreeText
                )

                firstResults + secondResults

            } else {

                /*
                 * وصلنا إلى صورة واحدة وفشلت.
                 * هنا نسجل الخطأ بدل أن نخفيه.
                 */
                val document = batch.first()

                listOf(
                    IndexedResult(
                        index = document.index,
                        result = ExtractionResult(
                            fileName = document.fileName,
                            status = "error",
                            errorMessage = getErrorMessage(e)
                        )
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // استدعاء مزود الذكاء الاصطناعي
    // -------------------------------------------------------------------------

    private suspend fun callVisionApi(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        return when (getProvider().lowercase()) {

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
                    apiKey = getApiKey(),
                    model = getModel(),
                    prompt = prompt,
                    images = images
                )
            }

            "openai" -> {
                callOpenAICompatibleVision(
                    url = OPENAI_URL,
                    apiKey = getApiKey(),
                    model = getModel(),
                    prompt = prompt,
                    images = images
                )
            }

            "groq" -> {
                callOpenAICompatibleVision(
                    url = GROQ_URL,
                    apiKey = getApiKey(),
                    model = getModel(),
                    prompt = prompt,
                    images = images
                )
            }

            "mistral" -> {
                callOpenAICompatibleVision(
                    url = MISTRAL_URL,
                    apiKey = getApiKey(),
                    model = getModel(),
                    prompt = prompt,
                    images = images
                )
            }

            "custom" -> {
                callOpenAICompatibleVision(
                    url = CUSTOM_URL,
                    apiKey = getApiKey(),
                    model = getModel(),
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

    // -------------------------------------------------------------------------
    // Gemini Vision
    // -------------------------------------------------------------------------

    private fun callGeminiVision(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        val apiKey = getApiKey()

        if (apiKey.isBlank()) {
            throw IOException(
                "مفتاح Gemini API غير موجود"
            )
        }

        /*
         * النموذج المحدد من الإعدادات أولًا،
         * ثم نماذج fallback.
         */
        val configuredModel = getModel()

        val models = linkedSetOf(
            configuredModel,
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite"
        ).filter {
            it.isNotBlank()
        }

        var lastError: Exception? = null

        for (model in models) {

            try {

                val url =
                    "https://generativelanguage.googleapis.com/" +
                            "v1beta/models/$model:generateContent" +
                            "?key=$apiKey"

                val parts = JSONArray()

                /*
                 * Prompt أولًا.
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
                 * ثم جميع الصور في نفس الطلب.
                 *
                 * كل صورة inline_data مستقلة.
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

                val contents = JSONArray()

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

                        /*
                         * حجم ثابت مناسب للدفعة.
                         *
                         * لا نضرب 4096 × عدد الصور،
                         * لأن المطلوب عادة بيانات مختصرة.
                         */
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

                val response =
                    httpClient.newCall(request).execute()

                val responseBody =
                    response.body?.string().orEmpty()

                if (!response.isSuccessful) {

                    throw IOException(
                        "HTTP ${response.code}: " +
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

                /*
                 * Gemini قد يعيد:
                 *
                 * candidates
                 *   -> content
                 *      -> parts
                 *         -> text
                 */
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

                for (i in 0 until responseParts.length()) {

                    val part =
                        responseParts.optJSONObject(i)
                            ?: continue

                    val text =
                        part.optString(
                            "text",
                            ""
                        )

                    if (text.isNotBlank()) {
                        textBuilder.append(text)
                    }
                }

                val result =
                    textBuilder.toString().trim()

                if (result.isBlank()) {
                    throw IOException(
                        "Gemini أعاد نصًا فارغًا.\n" +
                                "Response: $responseBody"
                    )
                }

                return result

            } catch (e: java.net.SocketTimeoutException) {

                lastError = IOException(
                    "$model: timeout أثناء الاتصال بـ Gemini",
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

    // -------------------------------------------------------------------------
    // OpenAI Compatible Vision APIs
    // -------------------------------------------------------------------------

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

        val content =
            JSONArray()

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

        val response =
            httpClient.newCall(request).execute()

        val responseBody =
            response.body?.string().orEmpty()

        if (!response.isSuccessful) {

            throw IOException(
                "HTTP ${response.code}: " +
                        "${response.message}\n" +
                        "Response: $responseBody"
            )
        }

        val root =
            JSONObject(responseBody)

        val choices =
            root.optJSONArray(
                "choices"
            )
                ?: throw IOException(
                    "API لم يُرجع choices.\n" +
                            "Response: $responseBody"
                )

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

        return message
            .optString(
                "content",
                ""
            )
            .trim()
            .ifBlank {
                throw IOException(
                    "API أعاد content فارغًا"
                )
            }
    }

    // -------------------------------------------------------------------------
    // تجهيز الصورة
    // -------------------------------------------------------------------------

    private fun prepareImage(
        uri: Uri
    ): PreparedImage {

        val inputStream =
            context.contentResolver.openInputStream(uri)
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

        val width =
            originalBitmap.width

        val height =
            originalBitmap.height

        val largestDimension =
            max(
                width,
                height
            )

        val bitmap =
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

                Bitmap.createScaledBitmap(
                    originalBitmap,
                    newWidth,
                    newHeight,
                    true
                )

            } else {
                originalBitmap
            }

        val output =
            ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            JPEG_QUALITY,
            output
        )

        /*
         * إذا أنشأنا Bitmap جديدًا، نحرر النسخة الأصلية.
         */
        if (bitmap !== originalBitmap) {
            bitmap.recycle()
            originalBitmap.recycle()
        }

        val bytes =
            output.toByteArray()

        if (bytes.isEmpty()) {
            throw IOException(
                "فشل ضغط الصورة"
            )
        }

        val base64 =
            android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.NO_WRAP
            )

        return PreparedImage(
            base64 = base64,
            mimeType = "image/jpeg"
        )
    }

    // -------------------------------------------------------------------------
    // تحليل JSON الناتج
    // -------------------------------------------------------------------------

    private fun parseBatchResponse(
        response: String,
        fileNames: List<String>
    ): List<ExtractionResult> {

        val json =
            extractJson(response)

        val root =
            JsonParser.parseString(json)

        /*
         * الحالة المثالية:
         *
         * [
         *   {
         *      "image_index": 1,
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
                        fileNames.getOrNull(index)
                            .orEmpty()
                )
            }
        }

        /*
         * بعض النماذج قد تعيد:
         *
         * {
         *   "results": [...]
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
             * إذا كان هناك كائن واحد فقط،
             * نعتبره نتيجة صورة واحدة.
             */
            return listOf(
                jsonElementToResult(
                    element = root,
                    fileName =
                        fileNames
                            .firstOrNull()
                            .orEmpty()
                )
            )
        }

        throw IOException(
            "صيغة JSON غير مدعومة"
        )
    }

    // -------------------------------------------------------------------------
    // تحويل عنصر JSON إلى ExtractionResult
    // -------------------------------------------------------------------------

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
         * image_index ليس حقل استخراج.
         */
        val ignoredKeys =
            setOf(
                "image_index",
                "imageIndex",
                "index"
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
                        value.asJsonPrimitive
                            .toString()
                            .removeSurrounding("\"")
                    }

                    else -> {
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

    // -------------------------------------------------------------------------
    // استخراج JSON من استجابة النموذج
    // -------------------------------------------------------------------------

    private fun extractJson(
        response: String
    ): String {

        var text =
            response.trim()

        /*
         * إزالة Markdown fences:
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
         * البحث عن أول JSON Array.
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
         * البحث عن JSON Object.
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

    // -------------------------------------------------------------------------
    // اسم الملف
    // -------------------------------------------------------------------------

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
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "document"
    }

    // -------------------------------------------------------------------------
    // تشخيص الأخطاء
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Data Classes
    // -------------------------------------------------------------------------

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
