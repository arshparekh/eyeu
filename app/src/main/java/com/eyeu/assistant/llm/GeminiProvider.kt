package com.eyeu.assistant.llm

import android.util.Log
import com.eyeu.assistant.data.LlmStepResponse
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to Google's Generative Language API (Gemini).
 * Supports multimodal vision requests and text-only layout fallback requests.
 * Includes automatic retry on transient rate-limits (429) or service outages (503).
 */
class GeminiProvider(
    private val apiKey: String,
    private val model: String
) : LlmProvider {

    companion object {
        private const val TAG = "EyeU/Gemini"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    override suspend fun nextStep(
        userGoal: String,
        history: List<String>,
        screenText: String,
        screenshotBase64Jpeg: String?
    ): LlmStepResponse = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API key is not set. Please add it in Settings." }

        val parts = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("text", PromptBuilder.userTurn(userGoal, history, screenText))
            })
            if (!screenshotBase64Jpeg.isNullOrBlank()) {
                add(JsonObject().apply {
                    add("inline_data", JsonObject().apply {
                        addProperty("mime_type", "image/jpeg")
                        addProperty("data", screenshotBase64Jpeg)
                    })
                })
            }
        }

        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", parts)
                })
            })
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", PromptBuilder.SYSTEM_PROMPT) })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.2)
                addProperty("responseMimeType", "application/json")
            })
        }

        val normalizedModel = model.removePrefix("models/").trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$normalizedModel:generateContent?key=$apiKey"

        var lastError: Throwable? = null
        for (attempt in 1..3) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(gson.toJson(body).toRequestBody(jsonMedia))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "API error ${resp.code} (attempt $attempt): $raw")
                        if ((resp.code == 503 || resp.code == 429) && attempt < 3) {
                            delay(1000L * attempt)
                            return@use
                        }
                        throw IllegalStateException("Gemini API error ${resp.code}: $raw")
                    }

                    val root = JsonParser.parseString(raw).asJsonObject
                    val candidates = root.getAsJsonArray("candidates")
                    if (candidates == null || candidates.size() == 0) {
                        Log.e(TAG, "No candidates in response: $raw")
                        throw IllegalStateException("No candidates in Gemini response")
                    }

                    val firstCandidate = candidates[0].asJsonObject
                    val contentObj = firstCandidate.getAsJsonObject("content")
                    val partsArray = contentObj?.getAsJsonArray("parts")
                    if (partsArray == null || partsArray.size() == 0) {
                        Log.w(TAG, "Candidate content parts empty: $raw")
                        throw IllegalStateException("Empty content parts in Gemini response")
                    }

                    val textContent = partsArray[0].asJsonObject.get("text")?.asString.orEmpty()
                    return@withContext LlmJsonParser.parse(textContent)
                }
            } catch (t: Throwable) {
                lastError = t
                if (attempt == 3) throw t
                Log.w(TAG, "Attempt $attempt failed, retrying in ${attempt}s...", t)
                delay(1000L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("Gemini request failed after 3 attempts")
    }
}
