package com.example.certextractor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.certextractor.BuildConfig
import com.example.certextractor.data.model.ExtractionField
import com.example.certextractor.data.model.ExtractionResult
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

    private val apiKey = BuildConfig.GROQ_API_KEY
    private val modelName = BuildConfig.GROQ_MODEL
    private val baseUrl = BuildConfig.GROQ_BASE_URL

    private val client = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val apiService = retrofit.create(GroqApiService::class.java)
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
                if (apiKey.isBlank()) {
                    return@withContext ExtractionResult(
                        fileName = fileName,
                        status = "error",
                        errorMessage = "مفتاح API غير محدد. أضف GROQ_API_KEY في local.properties"
                    )
                }

                val base64Image = uriToBase64(uri)
                val request = buildRequest(base64Image, fields, freeTextPrompt, isFreeTextMode)
                val response = apiService.extractData("Bearer $apiKey", request)
                parseResponse(response, fileName)

            } catch (e: HttpException) {
                val message = when (e.code()) {
                    429 -> "تم تجاوز حد الطلبات. انتظر قليلاً ثم حاول مرة أخرى."
                    401 -> "مفتاح API غير صالح."
                    413 -> "الصورة كبيرة جداً. قلّص الحجم وحاول مرة أخرى."
                    else -> "خطأ في الخادم: ${e.code()}"
                }
                ExtractionResult(fileName = fileName, status = "error", errorMessage = message)
            } catch (e: Exception) {
                ExtractionResult(fileName = fileName, status = "error", errorMessage = e.message ?: "خطأ غير معروف")
            }
        }
    }

    private fun uriToBase64(uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("لا يمكن فتح الملف")

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, 2048)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }

        val inputStream2 = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("لا يمكن فتح الملف")
        val bitmap = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)
        inputStream2.close()

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

    private fun buildRequest(
        base64Image: String,
        fields: List<ExtractionField>,
        freeTextPrompt: String?,
        isFreeTextMode: Boolean
    ): JsonObject {
        val prompt = if (isFreeTextMode) {
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

    private fun parseResponse(
        response: com.example.certextractor.data.model.GroqResponse,
        fileName: String
    ): ExtractionResult {
        val content = response.choices.firstOrNull()?.message?.content ?: ""

        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val values = mutableMapOf<String, String>()

            json.entrySet().forEach { (key, value) ->
                values[key] = when {
                    value.isJsonNull -> ""
                    value.isJsonArray -> {
                        value.asJsonArray.joinToString(" | ") { element ->
                            if (element.isJsonObject) {
                                element.asJsonObject.entrySet().joinToString(", ") { (k, v) ->
                                    "$k: ${v.asString}"
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
            ExtractionResult(fileName = fileName, status = "error", errorMessage = "خطأ في تحليل JSON: ${e.message}")
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
            val fileName = "document_${index + 1}"
            val result = processDocument(uri, fileName, fields, freeTextPrompt, isFreeTextMode)
            results.add(result)
            onProgress(index + 1, uris.size, result)
            if (index < uris.lastIndex) delay(2500)
        }

        return results
    }
}
