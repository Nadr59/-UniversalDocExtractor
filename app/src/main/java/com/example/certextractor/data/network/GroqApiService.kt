package com.example.certextractor.data.network

import com.example.certextractor.data.model.GroqResponse
import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface GroqApiService {
    @POST("openai/v1/chat/completions")
    suspend fun extractData(
        @Header("Authorization") authorization: String,
        @Body request: JsonObject
    ): GroqResponse
}
