package com.example.certextractor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.certextractor.BuildConfig
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
import com.example.certextractor.data.model.GroqResponse
import com.example.certextractor.data.network.GroqApiService
import com.example.certextractor.utils.DynamicPromptBuilder
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class DocumentRepository(private val context: Context) {

    private val apiKey: String = BuildConfig.GROQ_API_KEY
    private val modelName: String = BuildConfig.GROQ_MODEL
    private val baseUrl: String = BuildConfig.GROQ_BASE_URL

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val apiService: GroqApiService = retrofit.create(GroqApiService::class.java)
    private val gson: Gson = Gson()

    suspend fun processDocument(
        uri: Uri,
        fileName: String,
        fields: List<ExtractionField>,
        freeTextPrompt: String?,
        isFreeTextMode: Boolean
    ): ExtractionResult {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) {
                    return@withContext ExtractionResult(
                        fileName = fileName,
                        status = "error",
                        errorMessage = "API key not configured"
                    )
                }

                val base64Image: String = uriToBase64(uri)
                val request: JsonObject = buildRequest(base64Image, fields, freeTextPrompt, isFreeTextMode)
                val response: GroqResponse = apiService.extractData("Bearer $apiKey", request)
                parseResponse(response, fileName)

            } catch (e: HttpException) {
                val msg: String = when (e.code()) {
                    429 -> "Rate limit exceeded. Wait and retry."
                    401 -> "Invalid API key."
                    413 -> "Image too large."
                    else -> "Server error: ${e.code()}"
                }
                ExtractionResult(fileName = fileName, status = "error", errorMessage = msg)
            } catch (e: Exception) {
                ExtractionResult(
                    fileName = fileName,
                    status = "error",
                    errorMessage = e.message ?: "Unknown error"
                )
            }
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

        val sampleSize: Int = calculateSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, 2048)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        val stream2 = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot reopen file")
        val bitmap: Bitmap = BitmapFactory.decodeStream(stream2, null, decodeOptions)
            ?: throw IllegalStateException("Cannot decode image")
        stream2.close()

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val byteArray: ByteArray = outputStream.toByteArray()
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

    private fun buildRequest(
        base64Image: String,
        fields: List<ExtractionField>,
        freeTextPrompt: String?,
        isFreeTextMode: Boolean
    ): JsonObject {
        val prompt: String = if (isFreeTextMode) {
            DynamicPromptBuilder.buildFreeTextPrompt(freeTextPrompt ?: "")
        } else {
            DynamicPromptBuilder.buildFieldsPrompt(fields)
        }

        val contentArray = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", prompt)
            })
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", "data:image/jpeg;base64,$base64Image")
                })
            })
        }

        val messagesArray = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                add("content", contentArray)
            })
        }

        return JsonObject().apply {
            addProperty("model", modelName)
            add("messages", messagesArray)
            addProperty("temperature", 0.1)
            addProperty("max_completion_tokens", 1024)
            add("response_format", JsonObject().apply {
                addProperty("type", "json_object")
            })
        }
    }

    private fun parseResponse(response: GroqResponse, fileName: String): ExtractionResult {
        val content: String = response.choices.firstOrNull()?.message?.content ?: ""

        return try {
            val json: JsonObject = gson.fromJson(content, JsonObject::class.java)
            val values = mutableMapOf<String, String>()

            json.entrySet().forEach { entry ->
                val key: String = entry.key
                val value = entry.value
                values[key] = when {
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
