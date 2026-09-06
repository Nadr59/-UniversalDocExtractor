package com.example.certextractor.data.local

import android.content.Context

class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString("provider", "gemini") ?: "gemini"
        set(value) = prefs.edit().putString("provider", value).apply()

    var groqKey: String
        get() = prefs.getString("groq_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_key", value).apply()

    var groqModel: String
        get() = prefs.getString("groq_model", "meta-llama/llama-4-scout-17b-16e-instruct") ?: "meta-llama/llama-4-scout-17b-16e-instruct"
        set(value) = prefs.edit().putString("groq_model", value).apply()

    var openrouterKey: String
        get() = prefs.getString("openrouter_key", "") ?: ""
        set(value) = prefs.edit().putString("openrouter_key", value).apply()

    var openrouterModel: String
        get() = prefs.getString("openrouter_model", "google/gemini-2.5-flash") ?: "google/gemini-2.5-flash"
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

    // النموذج الافتراضي: gemini-3.6-flash
    // النماذج المتاحة الفعلية من مفتاحك:
    // gemini-2.5-flash
    // gemini-2.5-flash-lite
    // gemini-2.5-pro
    // gemini-3.5-flash
    // gemini-3.5-flash-lite
    // gemini-3.6-flash  ← الأفضل حالياً
    // gemini-3.7-flash
    // gemini-3.8-flash
    // gemini-flash-latest
    // gemini-flash-lite-latest
    // gemini-pro-latest
    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-3.6-flash") ?: "gemini-3.6-flash"
        set(value) = prefs.edit().putString("gemini_model", value).apply()

    var mistralKey: String
        get() = prefs.getString("mistral_key", "") ?: ""
        set(value) = prefs.edit().putString("mistral_key", value).apply()

    // النماذج المتاحة في Mistral:
    // pixtral-12b-2409     ← vision، مجاني، ضعيف في الجداول العربية
    // pixtral-large-2411   ← vision، مدفوع، أفضل
    // mistral-small-latest ← نص فقط، لا يدعم الصور
    // mistral-large-latest ← نص فقط، لا يدعم الصور
    var mistralModel: String
        get() = prefs.getString("mistral_model", "pixtral-large-2411") ?: "pixtral-large-2411"
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
