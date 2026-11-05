package com.eyeu.assistant.automation

import com.eyeu.assistant.data.LlmAction
import com.eyeu.assistant.data.LlmStepResponse
import com.eyeu.assistant.data.ScreenElement

/**
 * Offline, no-network command interpreter.
 * Handles simple single-intent commands when no LLM backend is configured.
 */
object RuleBasedFallback {

    private val appNameToPackageHints = mapOf(
        "camera" to "com.android.camera",
        "settings" to "com.android.settings",
        "gmail" to "com.google.android.gm",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "whatsapp" to "com.whatsapp",
        "phone" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "maps" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "play store" to "com.android.vending",
        "calculator" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock"
    )

    fun interpret(command: String, elements: List<ScreenElement>, errorHint: String? = null): LlmStepResponse {
        val text = command.trim().lowercase()

        // Check if user requested a multi-step task without an AI backend configured
        val isMultiStep = text.contains(" and ") || text.contains(" then ") || text.contains(" search ")

        matchNavigation(text)?.let { return it }

        matchOpenApp(text, isMultiStep)?.let { return it }

        matchScroll(text)?.let { return it }

        matchTapByLabel(text, elements)?.let { return it }

        matchTypeText(text, elements)?.let { return it }

        val speakMsg = if (errorHint != null) {
            "I encountered an issue connecting to the AI ($errorHint). Please verify your API key in Settings."
        } else if (isMultiStep) {
            "Multi-step tasks require an AI model. Please configure an API key (e.g. Gemini or Groq) in Settings."
        } else {
            "I didn't understand that command. Try saying 'open Chrome', 'go home', or configure an AI model in Settings."
        }

        return LlmStepResponse(
            thought = "rule-based fallback: no single-action pattern matched${if (errorHint != null) " (Error: $errorHint)" else ""}",
            speak = speakMsg,
            actions = emptyList(),
            done = true
        )
    }

    private fun matchNavigation(text: String): LlmStepResponse? {
        val action = when {
            "go back" in text || text == "back" -> "back"
            "go home" in text || "home screen" in text || text == "home" -> "home"
            "recent apps" in text || "recents" in text || "app switcher" in text -> "recents"
            "notification" in text -> "notifications"
            else -> null
        } ?: return null
        return LlmStepResponse(speak = "Okay.", actions = listOf(LlmAction(type = action)), done = true)
    }

    private fun matchOpenApp(text: String, isMultiStep: Boolean): LlmStepResponse? {
        if (!text.startsWith("open ") && !text.startsWith("launch ") && !text.startsWith("start ")) return null
        val targetPart = text.substringAfter(' ').substringBefore(" and ").substringBefore(" then ").substringBefore(" search ").trim()
        val pkg = appNameToPackageHints.entries.firstOrNull { targetPart.contains(it.key) || it.key.contains(targetPart) }?.value ?: return null

        val speak = if (isMultiStep) {
            "Opening $targetPart. Note: To automatically complete the rest of this task, please configure an AI API key in Settings."
        } else {
            "Opening $targetPart."
        }

        return LlmStepResponse(
            speak = speak,
            actions = listOf(LlmAction(type = "open_app", packageName = pkg)),
            done = true
        )
    }

    private fun matchScroll(text: String): LlmStepResponse? {
        val direction = when {
            "scroll up" in text -> "up"
            "scroll down" in text -> "down"
            "swipe left" in text -> "left"
            "swipe right" in text -> "right"
            else -> null
        } ?: return null
        val type = if (text.startsWith("swipe")) "swipe" else "scroll"
        return LlmStepResponse(
            speak = "Okay.",
            actions = listOf(LlmAction(type = type, direction = direction)),
            done = true
        )
    }

    private fun matchTapByLabel(text: String, elements: List<ScreenElement>): LlmStepResponse? {
        val trigger = when {
            text.startsWith("tap ") -> text.removePrefix("tap ")
            text.startsWith("click ") -> text.removePrefix("click ")
            text.startsWith("press ") -> text.removePrefix("press ")
            else -> return null
        }.trim()
        val match = elements.firstOrNull { el ->
            val label = (el.text ?: el.desc ?: "").lowercase()
            label.isNotBlank() && (label.contains(trigger) || trigger.contains(label))
        } ?: return LlmStepResponse(
            speak = "I couldn't find anything on screen called $trigger.",
            actions = emptyList(), done = true
        )
        return LlmStepResponse(
            speak = "Tapping $trigger.",
            actions = listOf(LlmAction(type = "click", targetId = match.id)),
            done = true
        )
    }

    private fun matchTypeText(text: String, elements: List<ScreenElement>): LlmStepResponse? {
        if (!text.startsWith("type ")) return null
        val toType = text.removePrefix("type ").trim()
        val editable = elements.firstOrNull { it.editable } ?: return LlmStepResponse(
            speak = "I don't see a text field to type into.",
            actions = emptyList(), done = true
        )
        return LlmStepResponse(
            speak = "Typing that in.",
            actions = listOf(LlmAction(type = "type_text", targetId = editable.id, text = toType, pressEnter = true)),
            done = true
        )
    }
}
