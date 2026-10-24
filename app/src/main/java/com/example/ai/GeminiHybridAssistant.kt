package com.example.ai

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Гибридный клиент Gemini API (gemini-3.5-flash) для углубленного анализа краш-логов
 * и интеллектуального подбора палитры скинов при наличии ключа в BuildConfig.GEMINI_API_KEY.
 */
class GeminiHybridAssistant {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isApiKeyConfigured(): Boolean {
        val key = BuildConfig.GEMINI_API_KEY
        return key.isNotBlank() &&
            key != "MY_GEMINI_API_KEY" &&
            key != "CI_PLACEHOLDER_KEY"
    }

    suspend fun enrichCrashAnalysisWithGemini(
        logSnippet: String,
        versionId: String,
        javaTitle: String
    ): String? = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) return@withContext null
        try {
            val prompt = """
                Ты — системный инженер PojavLauncher (Android). Кратко (в 2-3 предложениях на русском языке) объясни причину сбоя Vanilla Minecraft $versionId на $javaTitle и дай точный совет по флагам JVM или рендереру GL4ES/ANGLE:
                $logSnippet
            """.trimIndent()
            callGeminiFlash(prompt)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun suggestSkinColorsWithGemini(
        description: String
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) return@withContext null
        try {
            val prompt = """
                Подбери 2 HEX-цвета (основной цвет брони/одежды и яркий акцент) для скина Minecraft по описанию: "$description".
                Ответь строго в формате: #RRGGBB,#RRGGBB
            """.trimIndent()
            val text = callGeminiFlash(prompt) ?: return@withContext null
            val regex = Regex("#([0-9A-Fa-f]{6})")
            val matches = regex.findAll(text).map {
                (0xFF000000.toInt() or it.groupValues[1].toInt(16))
            }.toList()
            if (matches.size >= 2) {
                matches[0] to matches[1]
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun callGeminiFlash(promptText: String): String? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        val bodyJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put(
                            "parts",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("text", promptText)
                                }
                            )
                        )
                    }
                )
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string().orEmpty())
            return root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
        }
    }
}
