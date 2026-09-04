package com.example.certextractor.data.repository

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.certextractor.data.local.AiSettings
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import com.example.certextractor.utils.DynamicPromptBuilder
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class DocumentRepository(private val context: Context) {

    val settings = AiSettings(context)
    private val gson = Gson()

    /*
     * عدد الصور التي ترسل في طلب Gemini واحد.
     *
     * 3 هو رقم بداية محافظ.
     * إذا كان مستقرًا يمكن رفعه لاحقًا إلى 4 أو 5.
     */
    private val geminiBatchSize = 3

    suspend fun processDocument(
        uri: Uri,
        fileName: String,
        fields: List<ExtractionField>,
        freeTextPrompt: String?,
        isFreeTextMode: Boolean
    ): ExtractionResult {
        return withContext(Dispatchers.IO) {
            try {
                if (!settings.isConfigured()) {
                    return@withContext ExtractionResult(
                        fileName = fileName,
                        status = "error",
                        errorMessage = "Configure API key in Settings first"
                    )
                }

                val image = prepareImage(uri)

                val prompt = if (isFreeTextMode) {
                    DynamicPromptBuilder.buildFreeTextPrompt(freeTextPrompt ?: "")
                } else {
                    DynamicPromptBuilder.buildFieldsPrompt(fields)
                }

                val responseText = callVisionApi(
                    prompt = prompt,
                    images = listOf(image)
                )

                val results = parseBatchResponse(
                    content = responseText,
                    fileNames = listOf(fileName)
                )

                results.firstOrNull()
                    ?: ExtractionResult(
                        fileName = fileName,
                        status = "error",
                        errorMessage = "Empty AI response"
                    )

            } catch (e: Exception) {
                ExtractionResult(
                    fileName = fileName,
                    status = "error",
                    errorMessage = e.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * معالجة عدة صور.
     *
     * كل صورة = سجل مستقل.
     *
     * مثال:
     * 200 صورة
     * ↓
     * 3 صور في الطلب
     * ↓
     * حوالي 67 طلبًا بدل 200
     *
     * إذا فشل Batch، يتم تقسيمه تلقائيًا إلى مجموعات أصغر.
     */
    suspend fun processBatch(
        uris: List<Uri>,
        fields: List<ExtractionField>,
        freeTextPrompt: String?,
        isFreeTextMode: Boolean,
        onProgress: (current: Int, total: Int, result: ExtractionResult) -> Unit
    ): List<ExtractionResult> {

        if (uris.isEmpty()) return emptyList()

        val results = mutableListOf<ExtractionResult>()

        val prompt = if (isFreeTextMode) {
            DynamicPromptBuilder.buildFreeTextPrompt(freeTextPrompt ?: "")
        } else {
            DynamicPromptBuilder.buildFieldsPrompt(fields)
        }

        val total = uris.size

        /*
         * نحتفظ بالترتيب الأصلي.
         *
         * كل عنصر يحتوي:
         * index + Uri + fileName
         */
        val documents = uris.mapIndexed { index, uri ->
            DocumentItem(
                index = index,
                uri = uri,
                fileName = getFileName(uri, index)
            )
        }

        var position = 0

        while (position < documents.size) {

            val end = minOf(
                position + geminiBatchSize,
                documents.size
            )

            val batch = documents.subList(position, end)

            val batchResults = processBatchWithFallback(
                batch = batch,
                prompt = prompt
            )

            /*
             * batchResults قد تعود بترتيب مختلف،
             * لذلك نرتبها حسب index.
             */
            val orderedResults = batchResults.sortedBy { it.index }

            for (item in orderedResults) {
                results.add(item.result)

                onProgress(
                    results.size,
                    total,
                    item.result
                )
            }

            position = end

            /*
             * تأخير بسيط بين الطلبات.
             *
             * لا نحتاج 2.5 ثانية ثابتة بعد كل صورة،
             * لأننا أصبحنا نرسل عدة صور في الطلب الواحد.
             */
            if (position < documents.size) {
                delay(1000)
            }
        }

        /*
         * إعادة ترتيب نهائية حسب ترتيب الصور الأصلية.
         */
        return results.sortedBy { result ->
            documents.indexOfFirst {
                it.fileName == result.fileName
            }
        }
    }

    /**
     * يحاول معالجة المجموعة كاملة.
     *
     * إذا فشل الطلب:
     *
     * 3 صور
     * ↓
     * 1 + 2
     *
     * وإذا فشل أحدها:
     *
     * 2 صور
     * ↓
     * صورة + صورة
     *
     * وهكذا لا نخسر الصور بسبب فشل Batch.
     */
    private suspend fun processBatchWithFallback(
        batch: List<DocumentItem>,
        prompt: String
    ): List<IndexedResult> {

        if (batch.isEmpty()) return emptyList()

        try {
            val images = batch.map { document ->
                prepareImage(document.uri)
            }

            val responseText = callVisionApi(
                prompt = prompt,
                images = images
            )

            val parsed = parseBatchResponse(
                content = responseText,
                fileNames = batch.map { it.fileName }
            )

            /*
             * يجب أن نحصل على نتيجة لكل صورة.
             */
            if (parsed.size == batch.size) {
                return batch.mapIndexed { index, document ->
                    IndexedResult(
                        index = document.index,
                        result = parsed[index]
                    )
                }
            }

            /*
             * إذا أعاد Gemini عدد نتائج أقل من الصور،
             * نعتبر الـBatch غير موثوق ونستخدم fallback.
             */
            throw Exception(
                "AI returned ${parsed.size} results for ${batch.size} images"
            )

        } catch (e: Exception) {

            /*
             * إذا كانت صورة واحدة فقط، لا يمكن تقسيمها أكثر.
             */
            if (batch.size == 1) {
                val document = batch.first()

                return listOf(
                    IndexedResult(
                        index = document.index,
                        result = ExtractionResult(
                            fileName = document.fileName,
                            status = "error",
                            errorMessage = e.message ?: "Processing failed"
                        )
                    )
                )
            }

            /*
             * تقسيم المجموعة إلى نصفين.
             */
            val middle = batch.size / 2

            val firstHalf = batch.subList(0, middle)
            val secondHalf = batch.subList(middle, batch.size)

            val firstResults = processBatchWithFallback(
                batch = firstHalf,
                prompt = prompt
            )

            val secondResults = processBatchWithFallback(
                batch = secondHalf,
                prompt = prompt
            )

            return firstResults + secondResults
        }
    }

    private fun callVisionApi(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        return when (settings.provider) {
            "gemini" -> callGeminiVision(prompt, images)
            "openrouter" -> callOpenRouterVision(prompt, images)
            "openai" -> callOpenAIVision(prompt, images)
            "groq" -> callGroqVision(prompt, images)
            "mistral" -> callMistralVision(prompt, images)
            "custom" -> callCustomVision(prompt, images)
            else -> throw Exception(
                "Unknown provider: ${settings.provider}"
            )
        }
    }

    /**
     * Gemini Vision.
     *
     * يدعم صورة واحدة أو عدة صور في نفس الطلب.
     */
    private fun callGeminiVision(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        val apiKey = settings.geminiKey.trim()

        if (apiKey.isBlank()) {
            throw Exception("Gemini API key is empty")
        }

        /*
         * لا نستخدم نماذج Gemini 2.5 القديمة كـ fallback.
         */
        val modelsToTry = listOf(
            settings.geminiModel.trim(),
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite"
        )
            .filter { it.isNotBlank() }
            .distinct()

        val errors = mutableListOf<String>()

        for (model in modelsToTry) {

            var conn: HttpURLConnection? = null

            try {

                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                            "$model:generateContent?key=$apiKey"
                )

                conn = url.openConnection() as HttpURLConnection

                conn.apply {
                    requestMethod = "POST"

                    setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                    )

                    connectTimeout = 120_000
                    readTimeout = 180_000

                    doOutput = true
                    doInput = true
                }

                val parts = JSONArray()

                /*
                 * الـPrompt أولاً.
                 */
                parts.put(
                    JSONObject().apply {
                        put("text", prompt)
                    }
                )

                /*
                 * ثم جميع الصور.
                 */
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

                val body = JSONObject().apply {

                    put(
                        "contents",
                        JSONArray().apply {

                            put(
                                JSONObject().apply {

                                    put("role", "user")

                                    put("parts", parts)
                                }
                            )
                        }
                    )

                    put(
                        "generationConfig",
                        JSONObject().apply {

                            put("temperature", 0.1)

                            put(
                                "maxOutputTokens",
                                4096 * images.size
                            )

                            put(
                                "responseMimeType",
                                "application/json"
                            )
                        }
                    )
                }

                OutputStreamWriter(
                    conn.outputStream,
                    Charsets.UTF_8
                ).use { writer ->

                    writer.write(body.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode

                if (responseCode != 200) {

                    val message = getErrorMessage(conn)

                    errors.add(
                        "$model: HTTP $responseCode - $message"
                    )

                    conn.disconnect()
                    continue
                }

                val response = conn.inputStream
                    .bufferedReader()
                    .readText()

                conn.disconnect()

                val json = JSONObject(response)

                val candidates =
                    json.optJSONArray("candidates")

                if (candidates != null &&
                    candidates.length() > 0
                ) {

                    val partsResponse =
                        candidates
                            .getJSONObject(0)
                            .optJSONObject("content")
                            ?.optJSONArray("parts")

                    if (partsResponse != null &&
                        partsResponse.length() > 0
                    ) {

                        val textParts = mutableListOf<String>()

                        for (i in 0 until partsResponse.length()) {

                            val text =
                                partsResponse
                                    .getJSONObject(i)
                                    .optString("text", "")

                            if (text.isNotBlank()) {
                                textParts.add(text)
                            }
                        }

                        if (textParts.isNotEmpty()) {
                            return textParts.joinToString("\n").trim()
                        }
                    }
                }

                errors.add("$model: Empty response")

            } catch (e: SocketTimeoutException) {

                errors.add(
                    "$model: timeout"
                )

                conn?.disconnect()

            } catch (e: Exception) {

                errors.add(
                    "$model: ${e.message?.take(250)}"
                )

                conn?.disconnect()
            }
        }

        throw Exception(
            "Gemini failed:\n${errors.joinToString("\n")}"
        )
    }

    /*
     * OpenRouter / OpenAI-compatible providers.
     *
     * نرسل الصور كلها داخل content.
     */
    private fun callOpenRouterVision(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        val apiKey = settings.openrouterKey.trim()

        if (apiKey.isBlank()) {
            throw Exception("OpenRouter API key is empty")
        }

        val cleanModel =
            settings.openrouterModel
                .trim()
                .removeSuffix(":free")
                .trim()

        val modelsToTry = mutableListOf<String>()

        if (cleanModel.isNotBlank()) {
            modelsToTry.add(cleanModel)
        }

        val fallbacks = listOf(
            "google/gemini-3.6-flash",
            "google/gemini-3.5-flash",
            "qwen/qwen-2.5-vl-72b-instruct:free"
        )

        for (fb in fallbacks) {
            if (fb !in modelsToTry) {
                modelsToTry.add(fb)
            }
        }

        return callOpenAICompatibleVision(
            url =
                "https://openrouter.ai/api/v1/chat/completions",
            apiKey = apiKey,
            models = modelsToTry,
            prompt = prompt,
            images = images,
            extraHeaders = mapOf(
                "HTTP-Referer" to
                    "https://github.com/Nadr59/UniversalDocExtractor",
                "X-Title" to
                    "UniversalDocExtractor"
            )
        )
    }

    private fun callOpenAIVision(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        val apiKey = settings.openaiKey.trim()

        if (apiKey.isBlank()) {
            throw Exception("OpenAI API key is empty")
        }

        return callOpenAICompatibleVision(
            url = "https://api.openai.com/v1/chat/completions",
            apiKey = apiKey,
            models = listOf(
                settings.openaiModel.trim(),
                "gpt-4o-mini",
                "gpt-4o"
            ),
            prompt = prompt,
            images = images,
            extraHeaders = emptyMap()
        )
    }

    private fun callGroqVision(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        val apiKey = settings.groqKey.trim()

        if (apiKey.isBlank()) {
            throw Exception("Groq API key is empty")
        }

        val modelsToTry = mutableListOf<String>()

        val chosen = settings.groqModel.trim()

        if (chosen.isNotBlank()) {
            modelsToTry.add(chosen)
        }

        val fallbacks = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant"
        )

        for (fb in fallbacks) {
            if (fb !in modelsToTry) {
                modelsToTry.add(fb)
            }
        }

        return callOpenAICompatibleVision(
            url =
                "https://api.groq.com/openai/v1/chat/completions",
            apiKey = apiKey,
            models = modelsToTry,
            prompt = prompt,
            images = images,
            extraHeaders = emptyMap()
        )
    }

    private fun callMistralVision(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        val apiKey = settings.mistralKey.trim()

        if (apiKey.isBlank()) {
            throw Exception("Mistral API key is empty")
        }

        return callOpenAICompatibleVision(
            url = "https://api.mistral.ai/v1/chat/completions",
            apiKey = apiKey,
            models = listOf(
                settings.mistralModel.trim(),
                "pixtral-12b-2409"
            ),
            prompt = prompt,
            images = images,
            extraHeaders = emptyMap()
        )
    }

    private fun callCustomVision(
        prompt: String,
        images: List<PreparedImage>
    ): String {

        val baseUrl =
            settings.customUrl
                .trim()
                .trimEnd('/')

        val apiKey =
            settings.customKey.trim()

        if (baseUrl.isBlank()) {
            throw Exception("Custom server URL is empty")
        }

        if (apiKey.isBlank()) {
            throw Exception("Custom API key is empty")
        }

        return callOpenAICompatibleVision(
            url = "$baseUrl/v1/chat/completions",
            apiKey = apiKey,
            models = listOf(
                settings.customModel.trim()
            ),
            prompt = prompt,
            images = images,
            extraHeaders = emptyMap()
        )
    }

    private fun callOpenAICompatibleVision(
        url: String,
        apiKey: String,
        models: List<String>,
        prompt: String,
        images: List<PreparedImage>,
        extraHeaders: Map<String, String>
    ): String {

        val errors = mutableListOf<String>()

        for (model in models) {

            if (model.isBlank()) continue

            var conn: HttpURLConnection? = null

            try {

                conn = URL(url)
                    .openConnection() as HttpURLConnection

                conn.apply {

                    requestMethod = "POST"

                    setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                    )

                    setRequestProperty(
                        "Authorization",
                        "Bearer $apiKey"
                    )

                    extraHeaders.forEach { (key, value) ->
                        setRequestProperty(key, value)
                    }

                    connectTimeout = 120_000
                    readTimeout = 180_000

                    doOutput = true
                    doInput = true
                }

                val contentArray = JSONArray()

                contentArray.put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    }
                )

                images.forEach { image ->

                    contentArray.put(
                        JSONObject().apply {

                            put("type", "image_url")

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

                val body = JSONObject().apply {

                    put("model", model)

                    put(
                        "messages",
                        JSONArray().apply {

                            put(
                                JSONObject().apply {

                                    put("role", "user")

                                    put(
                                        "content",
                                        contentArray
                                    )
                                }
                            )
                        }
                    )

                    put("temperature", 0.1)

                    put(
                        "max_tokens",
                        4096 * images.size
                    )
                }

                OutputStreamWriter(
                    conn.outputStream,
                    Charsets.UTF_8
                ).use { writer ->

                    writer.write(body.toString())
                    writer.flush()
                }

                if (conn.responseCode != 200) {

                    val errorMsg =
                        getErrorMessage(conn)

                    errors.add(
                        "$model: $errorMsg"
                    )

                    conn.disconnect()
                    continue
                }

                val response =
                    conn.inputStream
                        .bufferedReader()
                        .readText()

                conn.disconnect()

                val json =
                    JSONObject(response)

                val choices =
                    json.optJSONArray("choices")

                if (choices != null &&
                    choices.length() > 0
                ) {

                    val content =
                        choices
                            .getJSONObject(0)
                            .optJSONObject("message")
                            ?.optString(
                                "content",
                                ""
                            )

                    if (!content.isNullOrBlank()) {
                        return content.trim()
                    }
                }

                errors.add("$model: Empty response")

            } catch (e: SocketTimeoutException) {

                errors.add("$model: timeout")

                conn?.disconnect()

            } catch (e: Exception) {

                errors.add(
                    "$model: ${e.message?.take(250)}"
                )

                conn?.disconnect()
            }
        }

        throw Exception(
            "Failed:\n${errors.joinToString("\n")}"
        )
    }

    /**
     * تجهيز الصورة.
     *
     * نستخدم 2048 كحد أقصى حتى لا نخسر تفاصيل OCR.
     */
    private fun prepareImage(uri: Uri): PreparedImage {

        val inputStream =
            context.contentResolver
                .openInputStream(uri)
                ?: throw IllegalStateException(
                    "Cannot open file"
                )

        val boundsOptions =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

        BitmapFactory.decodeStream(
            inputStream,
            null,
            boundsOptions
        )

        inputStream.close()

        val sampleSize =
            calculateSampleSize(
                boundsOptions.outWidth,
                boundsOptions.outHeight,
                2048
            )

        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

        val stream2 =
            context.contentResolver
                .openInputStream(uri)
                ?: throw IllegalStateException(
                    "Cannot reopen image"
                )

        val bitmap =
            BitmapFactory.decodeStream(
                stream2,
                null,
                decodeOptions
            )

        stream2.close()

        bitmap
            ?: throw IllegalStateException(
                "Cannot decode image"
            )

        val outputStream =
            ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            85,
            outputStream
        )

        bitmap.recycle()

        val bytes =
            outputStream.toByteArray()

        return PreparedImage(
            base64 =
                Base64.encodeToString(
                    bytes,
                    Base64.NO_WRAP
                ),
            mimeType = "image/jpeg"
        )
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        maxSize: Int
    ): Int {

        var sampleSize = 1

        while (
            width / sampleSize > maxSize ||
            height / sampleSize > maxSize
        ) {
            sampleSize *= 2
        }

        return sampleSize
    }

    /**
     * يحاول استخراج اسم الملف الأصلي.
     */
    private fun getFileName(
        uri: Uri,
        index: Int
    ): String {

        var name: String? = null

        if (uri.scheme == "content") {

            val cursor: Cursor? =
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        OpenableColumns.DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )

            cursor?.use {
                if (it.moveToFirst()) {

                    val column =
                        it.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                        )

                    if (column >= 0) {
                        name = it.getString(column)
                    }
                }
            }
        }

        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment
        }

        return name
            ?.takeIf { it.isNotBlank() }
            ?: "document_${index + 1}"
    }

    /**
     * تحويل استجابة Gemini إلى ExtractionResult لكل صورة.
     *
     * يدعم:
     *
     * [
     *   {...},
     *   {...}
     * ]
     *
     * وكذلك إذا أعاد النموذج:
     *
     * {
     *   "results": [
     *      {...},
     *      {...}
     *   ]
     * }
     */
    private fun parseBatchResponse(
        content: String,
        fileNames: List<String>
    ): List<ExtractionResult> {

        val cleaned = extractJson(content)

        return try {

            val root =
                JsonParser.parseString(cleaned)

            val elements =
                when {

                    root.isJsonArray -> {
                        root.asJsonArray.toList()
                    }

                    root.isJsonObject -> {

                        val obj =
                            root.asJsonObject

                        val possibleArrayKeys =
                            listOf(
                                "results",
                                "documents",
                                "items",
                                "data"
                            )

                        var array: List<JsonElement>? =
                            null

                        for (key in possibleArrayKeys) {

                            val candidate =
                                obj.get(key)

                            if (
                                candidate != null &&
                                candidate.isJsonArray
                            ) {
                                array =
                                    candidate
                                        .asJsonArray
                                        .toList()
                                break
                            }
                        }

                        if (array != null) {
                            array
                        } else {
                            listOf(root)
                        }
                    }

                    else -> emptyList()
                }

            elements.mapIndexedNotNull { index, element ->

                if (!element.isJsonObject) {
                    return@mapIndexedNotNull null
                }

                val obj =
                    element.asJsonObject

                val values =
                    mutableMapOf<String, String>()

                obj.entrySet().forEach { entry ->

                    if (
                        entry.key == "image_index" ||
                        entry.key == "imageIndex" ||
                        entry.key == "index"
                    ) {
                        return@forEach
                    }

                    values[entry.key] =
                        jsonElementToString(
                            entry.value
                        )
                }

                val fileName =
                    fileNames.getOrNull(index)
                        ?: "document_${index + 1}"

                ExtractionResult(
                    fileName = fileName,
                    values = values,
                    status = "success"
                )
            }

        } catch (e: Exception) {

            /*
             * في حالة فشل تحليل JSON لا نرمي الخطأ
             * هنا فقط؛ حتى يستطيع fallback تقسيم الـBatch.
             */
            throw Exception(
                "JSON parse error: ${e.message}"
            )
        }
    }

    private fun jsonElementToString(
        element: JsonElement
    ): String {

        return when {

            element.isJsonNull -> ""

            element.isJsonPrimitive ->
                element.asJsonPrimitive
                    .let { primitive ->

                        when {
                            primitive.isString ->
                                primitive.asString

                            primitive.isBoolean ->
                                primitive.asBoolean.toString()

                            primitive.isNumber ->
                                primitive.asNumber.toString()

                            else ->
                                primitive.toString()
                        }
                    }

            element.isJsonArray ->
                element.asJsonArray.joinToString(" | ") {
                    jsonElementToString(it)
                }

            element.isJsonObject ->
                element.asJsonObject
                    .entrySet()
                    .joinToString(", ") { entry ->
                        "${entry.key}: ${
                            jsonElementToString(
                                entry.value
                            )
                        }"
                    }

            else -> element.toString()
        }
    }

    /**
     * استخراج JSON من إجابة النموذج.
     */
    private fun extractJson(raw: String): String {

        var text = raw.trim()

        /*
         * إزالة Markdown fences.
         */
        if (text.contains("```")) {

            val start =
                text.indexOf("```")

            var afterStart =
                start + 3

            if (
                afterStart < text.length &&
                text[afterStart] != '\n'
            ) {

                val newline =
                    text.indexOf(
                        '\n',
                        afterStart
                    )

                if (newline != -1) {
                    afterStart =
                        newline + 1
                }
            }

            val end =
                text.indexOf(
                    "```",
                    afterStart
                )

            text =
                if (end != -1) {
                    text.substring(
                        afterStart,
                        end
                    ).trim()
                } else {
                    text.substring(
                        afterStart
                    ).trim()
                }
        }

        /*
         * أولاً نحاول العثور على Array.
         */
        val firstBracket =
            text.indexOf('[')

        val lastBracket =
            text.lastIndexOf(']')

        if (
            firstBracket != -1 &&
            lastBracket > firstBracket
        ) {
            return text.substring(
                firstBracket,
                lastBracket + 1
            ).trim()
        }

        /*
         * ثم Object.
         */
        val firstBrace =
            text.indexOf('{')

        val lastBrace =
            text.lastIndexOf('}')

        if (
            firstBrace != -1 &&
            lastBrace > firstBrace
        ) {

            return text.substring(
                firstBrace,
                lastBrace + 1
            ).trim()
        }

        throw Exception(
            "No valid JSON found"
        )
    }

    private fun getErrorMessage(
        conn: HttpURLConnection
    ): String {

        return try {

            val errorStream =
                conn.errorStream

            if (errorStream != null) {

                val errorText =
                    errorStream
                        .bufferedReader()
                        .readText()

                try {

                    val json =
                        JSONObject(errorText)

                    json.optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: errorText.take(500)

                } catch (_: Exception) {

                    errorText.take(500)
                }

            } else {

                "HTTP ${conn.responseCode}: " +
                        conn.responseMessage
            }

        } catch (_: Exception) {

            "HTTP ${conn.responseCode}: " +
                    conn.responseMessage
        }
    }

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
