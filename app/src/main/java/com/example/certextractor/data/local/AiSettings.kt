package com.example.certextractor.data.local

import android.content.Context
import javax.inject.Singleton

@Singleton
class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString("provider", "groq") ?: "groq"
        set(value) = prefs.edit().putString("provider", value).apply()

    var groqKey: String
        get() = prefs.getString("groq_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_key", value).apply()

    var groqModel: String
        get() = prefs.getString("groq_model", "llama-3.2-11b-vision-preview") ?: "llama-3.2-11b-vision-preview"
        set(value) = prefs.edit().putString("groq_model", value).apply()

    var openrouterKey: String
        get() = prefs.getString("openrouter_key", "") ?: ""
        set(value) = prefs.edit().putString("openrouter_key", value).apply()

    var openrouterModel: String
        get() = prefs.getString("openrouter_model", "google/gemini-2.0-flash-exp:free") ?: "google/gemini-2.0-flash-exp:free"
        set(value) = prefs.edit().putString("openrouter_model", value).apply()

    var openaiKey: String
        get() = prefs.getString("openai_key", "") ?: ""
        set(value) = prefs.edit().putString("openai_key", value).apply()

    var openaiModel: String
        get() = prefs.getString("openai_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString("openai_model", value).apply()

    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_key", value).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        set(value) = prefs.edit().putString("gemini_model", value).apply()

    var mistralKey: String
        get() = prefs.getString("mistral_key", "") ?: ""
        set(value) = prefs.edit().putString("mistral_key", value).apply()

    var mistralModel: String
        get() = prefs.getString("mistral_model", "pixtral-12b-2409") ?: "pixtral-12b-2409"
        set(value) = prefs.edit().putString("mistral_model", value).apply()

    var customUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(value) = prefs.edit().putString("custom_url", value).apply()

    var customKey: String
        get() = prefs.getString("custom_key", "") ?: ""
        set(value) = prefs.edit().putString("custom_key", value).apply()

    var customModel: String
        get() = prefs.getString("custom_model", "") ?: ""
        set(value) = prefs.edit().putString("custom_model", value).apply()

    fun getActiveKey(): String {
        return when (provider) {
            "groq" -> groqKey
            "openrouter" -> openrouterKey
            "openai" -> openaiKey
            "gemini" -> geminiKey
            "mistral" -> mistralKey
            "custom" -> customKey
            else -> ""
        }
    }

    fun getActiveModel(): String {
        return when (provider) {
            "groq" -> groqModel
            "openrouter" -> openrouterModel
            "openai" -> openaiModel
            "gemini" -> geminiModel
            "mistral" -> mistralModel
            "custom" -> customModel
            else -> ""
        }
    }

    fun isConfigured(): Boolean {
        return getActiveKey().isNotBlank()
    }
}
