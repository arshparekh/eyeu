package com.eyeu.assistant.llm

import android.util.Log
import com.eyeu.assistant.data.LlmAction
import com.eyeu.assistant.data.LlmStepResponse
import com.google.gson.JsonParser

object LlmJsonParser {
    private const val TAG = "EyeU/JsonParser"

    fun parse(rawContent: String): LlmStepResponse {
        val jsonStr = extractJsonObjectString(rawContent)
        if (jsonStr == null) {
            Log.w(TAG, "No valid JSON object found in response: $rawContent")
            return LlmStepResponse(
                thought = "Could not parse JSON from model response",
                speak = "",
                actions = emptyList(),
                done = false
            )
        }

        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject

            val actions = obj.getAsJsonArray("actions")?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val a = el.asJsonObject

                val type = a.get("type")?.takeIf { !it.isJsonNull }?.asString
                    ?: a.get("action")?.takeIf { !it.isJsonNull }?.asString
                    ?: "wait"

                val targetId = a.get("targetId")?.takeIf { !it.isJsonNull }?.asInt
                    ?: a.get("id")?.takeIf { !it.isJsonNull }?.asInt
                    ?: a.get("target_id")?.takeIf { !it.isJsonNull }?.asInt

                val text = a.get("text")?.takeIf { !it.isJsonNull }?.asString
                    ?: a.get("query")?.takeIf { !it.isJsonNull }?.asString
                    ?: a.get("input")?.takeIf { !it.isJsonNull }?.asString
                    ?: a.get("value")?.takeIf { !it.isJsonNull }?.asString

                val direction = a.get("direction")?.takeIf { !it.isJsonNull }?.asString
                val packageName = a.get("packageName")?.takeIf { !it.isJsonNull }?.asString
                    ?: a.get("package")?.takeIf { !it.isJsonNull }?.asString
                    ?: a.get("app")?.takeIf { !it.isJsonNull }?.asString

                val millis = a.get("millis")?.takeIf { !it.isJsonNull }?.asLong
                    ?: a.get("duration")?.takeIf { !it.isJsonNull }?.asLong

                val hour = a.get("hour")?.takeIf { !it.isJsonNull }?.asInt
                val minute = a.get("minute")?.takeIf { !it.isJsonNull }?.asInt
                val label = a.get("label")?.takeIf { !it.isJsonNull }?.asString

                val pressEnter = a.get("pressEnter")?.takeIf { !it.isJsonNull }?.asBoolean
                    ?: a.get("press_enter")?.takeIf { !it.isJsonNull }?.asBoolean
                    ?: (text?.endsWith("\n") == true)

                val x = a.get("x")?.takeIf { !it.isJsonNull }?.asFloat
                val y = a.get("y")?.takeIf { !it.isJsonNull }?.asFloat

                LlmAction(
                    type = type.lowercase().trim(),
                    targetId = targetId,
                    text = text,
                    direction = direction?.lowercase()?.trim(),
                    packageName = packageName?.trim(),
                    millis = millis,
                    hour = hour,
                    minute = minute,
                    label = label,
                    pressEnter = pressEnter,
                    x = x,
                    y = y
                )
            } ?: emptyList()

            LlmStepResponse(
                thought = obj.get("thought")?.takeIf { !it.isJsonNull }?.asString,
                speak = obj.get("speak")?.takeIf { !it.isJsonNull }?.asString,
                actions = actions,
                done = obj.get("done")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error parsing JSON payload: $jsonStr", e)
            LlmStepResponse(
                thought = "JSON parsing exception: ${e.message}",
                speak = "",
                actions = emptyList(),
                done = false
            )
        }
    }

    /**
     * Extracts the outermost balanced `{ ... }` substring from a raw model string,
     * ignoring markdown fences (```json ... ```) or conversational commentary.
     */
    fun extractJsonObjectString(raw: String): String? {
        var str = raw.trim()

        // Fast path: clean code block fences
        if (str.startsWith("```json")) {
            str = str.removePrefix("```json")
        } else if (str.startsWith("```")) {
            str = str.removePrefix("```")
        }
        if (str.endsWith("```")) {
            str = str.removeSuffix("```")
        }
        str = str.trim()

        val firstBrace = str.indexOf('{')
        val lastBrace = str.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return str.substring(firstBrace, lastBrace + 1).trim()
        }
        return null
    }
}
