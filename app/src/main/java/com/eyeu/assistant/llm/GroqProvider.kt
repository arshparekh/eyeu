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
 * Talks to Groq's OpenAI-compatible /openai/v1/chat/completions endpoint.
 * Supports vision models (e.g. llama-3.2-11b-vision-preview) and text-only layout fallback.
 */
class GroqProvider(
    private val apiKey: String,
    private val model: String
) : LlmProvider {

    companion object {
        private const val TAG = "EyeU/Groq"
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
        require(apiKey.isNotBlank()) { "Groq API key is not set. Please add it in Settings." }

        val body = JsonObject().apply {
            addProperty("model", model.ifBlank { "llama-3.2-11b-vision-preview" })
            addProperty("temperature", 0.2)
            add("response_format", JsonObject().apply { addProperty("type", "json_object") })
        }

        val userContent = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", PromptBuilder.userTurn(userGoal, history, screenText))
            })
            if (!screenshotBase64Jpeg.isNullOrBlank()) {
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:image/jpeg;base64,$screenshotBase64Jpeg")
                    })
                })
            }
        }

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", PromptBuilder.SYSTEM_PROMPT)
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                add("content", userContent)
            })
        }
        body.add("messages", messages)

        var lastError: Throwable? = null
        for (attempt in 1..3) {
            try {
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
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
                        throw IllegalStateException("Groq API error ${resp.code}: $raw")
                    }

                    val root = JsonParser.parseString(raw).asJsonObject
                    val choices = root.getAsJsonArray("choices")
                    if (choices == null || choices.size() == 0) {
                        throw IllegalStateException("No choices in Groq response")
                    }
                    val content = choices[0].asJsonObject
                        .getAsJsonObject("message")
                        .get("content").asString
                    return@withContext LlmJsonParser.parse(content)
                }
            } catch (t: Throwable) {
                lastError = t
                if (attempt == 3) throw t
                Log.w(TAG, "Attempt $attempt failed, retrying in ${attempt}s...", t)
                delay(1000L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("Groq request failed after 3 attempts")
    }
}
