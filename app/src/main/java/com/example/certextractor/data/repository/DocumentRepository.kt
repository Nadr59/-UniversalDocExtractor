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
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class DocumentRepository(private val context: Context) {

    val settings = AiSettings(context)
    private val gson = Gson()

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

                val base64Image = uriToBase64(uri)
                val prompt = if (isFreeTextMode) {
                    DynamicPromptBuilder.buildFreeTextPrompt(freeTextPrompt ?: "")
                } else {
                    DynamicPromptBuilder.buildFieldsPrompt(fields)
                }

                val responseText = callVisionApi(prompt, base64Image)
                parseResponse(responseText, fileName)

            } catch (e: Exception) {
                ExtractionResult(
                    fileName = fileName,
                    status = "error",
                    errorMessage = e.message ?: "Unknown error"
                )
            }
        }
    }

    private fun callVisionApi(prompt: String, base64Image: String): String {
        return when (settings.provider) {
            "groq" -> callGroqVision(prompt, base64Image)
            "openrouter" -> callOpenRouterVision(prompt, base64Image)
            "openai" -> callOpenAIVision(prompt, base64Image)
            "gemini" -> callGeminiVision(prompt, base64Image)
            "mistral" -> callMistralVision(prompt, base64Image)
            "custom" -> callCustomVision(prompt, base64Image)
            else -> throw Exception("Unknown provider: ${settings.provider}")
        }
    }

            private fun callGroqVision(prompt: String, base64Image: String): String {
        val modelsToTry = mutableListOf(settings.groqModel.trim())
        val fallbacks = listOf(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "qwen/qwen3.6-27b"
        )
        for (fb in fallbacks) {
            if (fb !in modelsToTry) modelsToTry.add(fb)
        }

        val errors = mutableListOf<String>()

        for (model in modelsToTry) {
            if (model.isBlank()) continue
            try {
                val result = callOpenAICompatibleVisionSingle(
                    url = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = settings.groqKey.trim(),
                    model = model,
                    prompt = prompt,
                    base64Image = base64Image,
                    extraHeaders = emptyMap()
                )
                if (result.isNotBlank()) return result
            } catch (e: Exception) {
                errors.add("$model: ${e.message?.take(150)}")
            }
        }

        throw Exception("Groq failed:\n${errors.joinToString("\n")}")
            }

    private fun callOpenRouterVision(prompt: String, base64Image: String): String {
        val cleanModel = settings.openrouterModel.trim().removeSuffix(":free").trim()
        val modelsToTry = mutableListOf(cleanModel)
        val fallbacks = listOf(
            "google/gemini-2.0-flash-exp:free",
            "meta-llama/llama-3.2-11b-vision-instruct",
            "mistralai/pixtral-12b"
        )
        for (fb in fallbacks) {
            if (fb !in modelsToTry) modelsToTry.add(fb)
        }

        return callOpenAICompatibleVision(
            url = "https://openrouter.ai/api/v1/chat/completions",
            apiKey = settings.openrouterKey.trim(),
            models = modelsToTry,
            prompt = prompt,
            base64Image = base64Image,
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://github.com/Nadr59/UniversalDocExtractor",
                "X-Title" to "UniversalDocExtractor"
            )
        )
    }

    private fun callOpenAIVision(prompt: String, base64Image: String): String {
        return callOpenAICompatibleVision(
            url = "https://api.openai.com/v1/chat/completions",
            apiKey = settings.openaiKey.trim(),
            models = listOf(settings.openaiModel.trim(), "gpt-4o-mini", "gpt-4o"),
            prompt = prompt,
            base64Image = base64Image,
            extraHeaders = emptyMap()
        )
    }

    private fun callMistralVision(prompt: String, base64Image: String): String {
        return callOpenAICompatibleVision(
            url = "https://api.mistral.ai/v1/chat/completions",
            apiKey = settings.mistralKey.trim(),
            models = listOf(settings.mistralModel.trim(), "pixtral-12b-2409"),
            prompt = prompt,
            base64Image = base64Image,
            extraHeaders = emptyMap()
        )
    }

    private fun callCustomVision(prompt: String, base64Image: String): String {
        val baseUrl = settings.customUrl.trim().trimEnd('/')
        return callOpenAICompatibleVision(
            url = "$baseUrl/v1/chat/completions",
            apiKey = settings.customKey.trim(),
            models = listOf(settings.customModel.trim()),
            prompt = prompt,
            base64Image = base64Image,
            extraHeaders = emptyMap()
        )
    }

    private fun callOpenAICompatibleVision(
        url: String,
        apiKey: String,
        models: List<String>,
        prompt: String,
        base64Image: String,
        extraHeaders: Map<String, String>
    ): String {
        val errors = mutableListOf<String>()

        for (model in models) {
            if (model.isBlank()) continue
            try {
                val result = callOpenAICompatibleVisionSingle(
                    url, apiKey, model, prompt, base64Image, extraHeaders
                )
                if (result.isNotBlank()) return result
            } catch (e: Exception) {
                errors.add("$model: ${e.message?.take(150)}")
            }
        }

        throw Exception("Failed:\n${errors.joinToString("\n")}")
    }

    private fun callOpenAICompatibleVisionSingle(
        url: String,
        apiKey: String,
        model: String,
        prompt: String,
        base64Image: String,
        extraHeaders: Map<String, String>
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 60000
            readTimeout = 60000
            doOutput = true
            doInput = true
        }

        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Image")
                })
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 1024)
            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
            it.write(body.toString())
            it.flush()
        }

        if (conn.responseCode != 200) {
            val errorMsg = getErrorMessage(conn)
            conn.disconnect()
            throw Exception(errorMsg)
        }

        val response = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val json = JSONObject(response)
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val content = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content", "")
            if (!content.isNullOrBlank()) return content.trim()
        }
        throw Exception("Empty response")
    }

    private fun callGeminiVision(prompt: String, base64Image: String): String {
        val apiKey = settings.geminiKey.trim()
        if (apiKey.isBlank()) throw Exception("Gemini API key is empty")

        val modelsToTry = listOf(
            settings.geminiModel.trim(),
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-3.6-flash",
            "gemini-3.7-flash"
        ).filter { it.isNotBlank() }.distinct()

        val errors = mutableListOf<String>()

        for (model in modelsToTry) {
            try {
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 60000
                    readTimeout = 60000
                    doOutput = true
                }

                val body = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64Image)
                                    })
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.1)
                        put("maxOutputTokens", 1024)
                        put("responseMimeType", "application/json")
                    })
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(body.toString())
                    it.flush()
                }

                if (conn.responseCode != 200) {
                    val msg = getErrorMessage(conn)
                    conn.disconnect()
                    errors.add("$model: $msg")
                    continue
                }

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(response)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0)
                        .optJSONObject("content")
                        ?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        return parts.getJSONObject(0).getString("text").trim()
                    }
                }
                errors.add("$model: Empty response")
            } catch (e: Exception) {
                errors.add("$model: ${e.message?.take(150)}")
            }
        }

        throw Exception("Gemini failed:\n${errors.joinToString("\n")}")
    }

    private fun getErrorMessage(conn: HttpURLConnection): String {
        return try {
            val errorStream = conn.errorStream
            if (errorStream != null) {
                val errorText = errorStream.bufferedReader().readText()
                val json = JSONObject(errorText)
                json.optJSONObject("error")?.optString("message")
                    ?: errorText.take(300)
            } else {
                "HTTP ${conn.responseCode}: ${conn.responseMessage}"
            }
        } catch (_: Exception) {
            "HTTP ${conn.responseCode}: ${conn.responseMessage}"
        }
    }

    private fun uriToBase64(uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open file")

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(inputStream, null, boundsOptions)
        inputStream.close()

        val sampleSize = calculateSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, 2048)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        val stream2 = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot reopen file")
        val bitmap = BitmapFactory.decodeStream(stream2, null, decodeOptions)
            ?: throw IllegalStateException("Cannot decode image")
        stream2.close()

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val byteArray = outputStream.toByteArray()
        bitmap.recycle()

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxSize || height / sampleSize > maxSize) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun parseResponse(content: String, fileName: String): ExtractionResult {
        return try {
            val json = gson.fromJson(content, com.google.gson.JsonObject::class.java)
            val values = mutableMapOf<String, String>()

            json.entrySet().forEach { entry ->
                val value = entry.value
                values[entry.key] = when {
                    value.isJsonNull -> ""
                    value.isJsonArray -> {
                        value.asJsonArray.joinToString(" | ") { element ->
                            if (element.isJsonObject) {
                                element.asJsonObject.entrySet().joinToString(", ") { e ->
                                    e.key + ": " + e.value.asString
                                }
                            } else {
                                element.asString
                            }
                        }
                    }
                    else -> value.asString
                }
            }

            ExtractionResult(fileName = fileName, values = values, status = "success")
        } catch (e: Exception) {
            ExtractionResult(
                fileName = fileName,
                status = "error",
                errorMessage = "JSON parse error: " + e.message
            )
        }
    }

    suspend fun processBatch(
        uris: List<Uri>,
        fields: List<ExtractionField>,
        freeTextPrompt: String?,
        isFreeTextMode: Boolean,
        onProgress: (current: Int, total: Int, result: ExtractionResult) -> Unit
    ): List<ExtractionResult> {
        val results = mutableListOf<ExtractionResult>()

        uris.forEachIndexed { index, uri ->
            val fileName = "document_" + (index + 1)
            val result = processDocument(uri, fileName, fields, freeTextPrompt, isFreeTextMode)
            results.add(result)
            onProgress(index + 1, uris.size, result)
            if (index < uris.lastIndex) delay(2500)
        }

        return results
    }
}
