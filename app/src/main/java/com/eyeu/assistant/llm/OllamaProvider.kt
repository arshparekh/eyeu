package com.eyeu.assistant.llm

import android.util.Log
import com.eyeu.assistant.data.LlmStepResponse
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to a local/self-hosted Ollama server's /api/chat endpoint.
 */
class OllamaProvider(
    private val host: String,
    private val model: String
) : LlmProvider {

    companion object {
        private const val TAG = "EyeU/Ollama"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    override suspend fun nextStep(
        userGoal: String,
        history: List<String>,
        screenText: String,
        screenshotBase64Jpeg: String?
    ): LlmStepResponse = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", false)
            add("format", JsonObject().apply { addProperty("type", "json_object") })
        }

        val userMessage = JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", PromptBuilder.userTurn(userGoal, history, screenText))
            if (!screenshotBase64Jpeg.isNullOrBlank()) {
                add("images", JsonArray().apply { add(screenshotBase64Jpeg) })
            }
        }

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", PromptBuilder.SYSTEM_PROMPT)
            })
            add(userMessage)
        }
        body.add("messages", messages)

        val url = host.trimEnd('/') + "/api/chat"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(body).toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Ollama error ${resp.code}: $raw")
                throw IllegalStateException("Ollama error ${resp.code}: $raw")
            }
            val root = JsonParser.parseString(raw).asJsonObject
            val content = root.getAsJsonObject("message").get("content").asString
            LlmJsonParser.parse(content)
        }
    }
}
