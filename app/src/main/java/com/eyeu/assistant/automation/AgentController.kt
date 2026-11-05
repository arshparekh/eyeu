package com.eyeu.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.eyeu.assistant.data.LlmBackend
import com.eyeu.assistant.data.LlmStepResponse
import com.eyeu.assistant.data.Prefs
import com.eyeu.assistant.llm.GeminiProvider
import com.eyeu.assistant.llm.GroqProvider
import com.eyeu.assistant.llm.LlmProvider
import com.eyeu.assistant.llm.OllamaProvider
import kotlinx.coroutines.delay

/**
 * OpenDroid-style Autonomous Agent Loop:
 * 1. Takes a fresh screenshot + accessibility element tree at EVERY step.
 * 2. Feeds the visual + textual state to the vision LLM (Gemini, Groq, or Ollama).
 * 3. Executes the returned action(s) (opening apps, clicking, typing, submitting searches).
 * 4. Waits for UI animations to settle.
 * 5. Repeats the loop with a fresh screenshot to verify the result until the goal is achieved (`done = true`).
 */
class AgentController(
    private val service: AccessibilityService,
    private val onStatus: (String) -> Unit,
    private val onSpeak: (String) -> Unit
) {
    companion object {
        private const val TAG = "EyeU/Agent"
        private const val MAX_STEPS = 25
    }

    private val tree = ScreenTreeExtractor()
    private val executor = ActionExecutor(service, tree)
    private val screenshots = ScreenshotCapture(service)

    suspend fun runCommand(spokenCommand: String) {
        val history = ArrayList<String>()
        val backendConfigured = Prefs.isBackendConfigured(service)

        if (!backendConfigured) {
            onStatus("Offline Mode (No API key)")
        } else {
            onStatus("Thinking…")
        }

        for (step in 1..MAX_STEPS) {
            Log.d(TAG, "===== Starting Agent Step $step for goal: \"$spokenCommand\" =====")

            val windows = service.windows ?: emptyList()
            val (elements, screenText) = tree.extract(windows)

            val response: LlmStepResponse = if (!backendConfigured) {
                RuleBasedFallback.interpret(spokenCommand, elements)
            } else {
                try {
                    // 1. Capture fresh screenshot (automated for physical device + emulator)
                    val screenshotB64 = captureScreenshotB64()
                    if (screenshotB64 != null) {
                        Log.d(TAG, "Step $step: Screenshot captured (${screenshotB64.length} chars), sending to vision LLM")
                    } else {
                        Log.w(TAG, "Step $step: Screenshot unavailable, using layout tree fallback")
                    }

                    // 2. Query LLM provider with current multimodal context
                    onStatus("Step $step: Planning…")
                    provider().nextStep(spokenCommand, history, screenText, screenshotB64)
                } catch (t: Throwable) {
                    Log.e(TAG, "LLM call failed at step $step", t)
                    val errorMsg = t.message ?: "Network error"
                    onStatus("AI Error: $errorMsg")
                    onSpeak("I encountered an error connecting to the AI model: $errorMsg")
                    return
                }
            }

            // 3. User feedback (TTS & UI Status)
            response.speak?.takeIf { it.isNotBlank() }?.let { speakText ->
                onSpeak(speakText)
                onStatus(speakText)
            }

            response.thought?.let { thought ->
                Log.d(TAG, "Step $step thought: $thought")
                history.add("Step $step thought: $thought")
            }

            if (response.actions.isNotEmpty()) {
                val actionSummary = response.actions.joinToString(", ") { action ->
                    when (action.type) {
                        "open_app" -> "open ${action.packageName}"
                        "type_text" -> "type \"${action.text}\""
                        "click" -> "click [${action.targetId}]"
                        else -> action.type
                    }
                }
                history.add("Step $step actions: $actionSummary")
                onStatus("Step $step: Executing $actionSummary")
            }

            // 4. Execute all planned actions in sequence
            var maxSettleDelay = 600L
            for (action in response.actions) {
                val ok = executor.execute(action)
                Log.d(TAG, "Step $step: Executed ${action.type} (success=$ok)")

                // Determine appropriate UI settle delay based on action type
                val actionDelay = when (action.type) {
                    "open_app", "launch_app" -> 1500L
                    "type_text", "type", "press_enter", "search" -> 1000L
                    "click", "long_click", "tap_xy" -> 600L
                    "scroll", "swipe" -> 700L
                    else -> 500L
                }
                if (actionDelay > maxSettleDelay) {
                    maxSettleDelay = actionDelay
                }
                delay(300) // Small pause between batched actions
            }

            tree.clear()

            // 5. Check if goal is accomplished
            if (response.done) {
                Log.i(TAG, "Goal accomplished in $step steps: \"$spokenCommand\"")
                onStatus("Done")
                return
            }

            if (!backendConfigured) {
                // Offline single-action interpreter completes after 1 step
                onStatus("Done")
                return
            }

            // 6. Settle UI before taking the next step's screenshot
            delay(maxSettleDelay)
        }

        onStatus("Completed ($MAX_STEPS steps)")
        onSpeak("I reached the maximum step limit for this command.")
    }

    private suspend fun captureScreenshotB64(): String? {
        return try {
            val bmp = screenshots.capture() ?: return null
            val b64 = screenshots.toBase64Jpeg(bmp)
            bmp.recycle()
            b64
        } catch (t: Throwable) {
            Log.w(TAG, "captureScreenshotB64 failed", t)
            null
        }
    }

    private fun provider(): LlmProvider = when (Prefs.backend(service)) {
        LlmBackend.GEMINI -> GeminiProvider(Prefs.geminiKey(service), Prefs.geminiModel(service))
        LlmBackend.GROQ -> GroqProvider(Prefs.groqKey(service), Prefs.groqModel(service))
        LlmBackend.OLLAMA -> OllamaProvider(Prefs.ollamaHost(service), Prefs.ollamaModel(service))
    }
}
