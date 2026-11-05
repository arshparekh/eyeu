package com.eyeu.assistant.data

/** One node from the accessibility tree, flattened and indexed for the LLM to reference. */
data class ScreenElement(
    val id: Int,
    val text: String?,
    val desc: String?,
    val resId: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val bounds: IntArray // left, top, right, bottom
) {
    /** Compact one-line representation sent to the LLM, e.g. [12] Button "Send" #btn_send clickable */
    fun toPromptLine(): String {
        val label = (text ?: desc ?: "").trim()
        val flags = buildList {
            if (clickable) add("clickable")
            if (editable) add("editable")
            if (scrollable) add("scrollable")
            checked?.let { add(if (it) "checked" else "unchecked") }
        }.joinToString(",")
        val short = className?.substringAfterLast('.') ?: "View"
        val idPart = resId?.let { " #${it.substringAfterLast('/')}" } ?: ""
        return "[$id] $short${idPart} \"$label\" ($flags)"
    }
}

/** A single action instruction the LLM asks EyeU to perform. */
data class LlmAction(
    val type: String,               // click, long_click, type_text, press_enter, scroll, swipe, back, home, recents,
                                     // open_app, tap_xy, set_alarm, wait, notify, done
    val targetId: Int? = null,
    val text: String? = null,
    val direction: String? = null,  // up, down, left, right
    val packageName: String? = null,
    val millis: Long? = null,
    val hour: Int? = null,          // for set_alarm (24h)
    val minute: Int? = null,        // for set_alarm
    val label: String? = null,      // for set_alarm, e.g. "leaving work"
    val pressEnter: Boolean = false,
    val x: Float? = null,
    val y: Float? = null
)

/** Full structured response the LLM is asked to return for one reasoning step. */
data class LlmStepResponse(
    val thought: String? = null,
    val speak: String? = null,
    val actions: List<LlmAction> = emptyList(),
    val done: Boolean = false
)

enum class LlmBackend { GROQ, GEMINI, OLLAMA }

/**
 * All settings live here and are edited only from the Settings screen.
 */
object Prefs {
    private const val FILE = "eyeu_prefs"
    private const val KEY_BACKEND = "backend"

    private const val KEY_GROQ_KEY = "groq_api_key"
    private const val KEY_GROQ_MODEL = "groq_model"

    private const val KEY_GEMINI_KEY = "gemini_api_key"
    private const val KEY_GEMINI_MODEL = "gemini_model"

    private const val KEY_OLLAMA_HOST = "ollama_host"
    private const val KEY_OLLAMA_MODEL = "ollama_model"

    private const val KEY_VOICE_FEEDBACK = "voice_feedback"

    fun prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

    fun backend(ctx: android.content.Context): LlmBackend =
        when (prefs(ctx).getString(KEY_BACKEND, "GEMINI")) {
            "GROQ" -> LlmBackend.GROQ
            "OLLAMA" -> LlmBackend.OLLAMA
            else -> LlmBackend.GEMINI
        }
    fun setBackend(ctx: android.content.Context, b: LlmBackend) =
        prefs(ctx).edit().putString(KEY_BACKEND, b.name).apply()

    // Groq — official vision-capable model
    fun groqKey(ctx: android.content.Context) = prefs(ctx).getString(KEY_GROQ_KEY, "") ?: ""
    fun setGroqKey(ctx: android.content.Context, v: String) = prefs(ctx).edit().putString(KEY_GROQ_KEY, v).apply()
    fun groqModel(ctx: android.content.Context) =
        prefs(ctx).getString(KEY_GROQ_MODEL, "llama-3.2-11b-vision-preview")
            ?: "llama-3.2-11b-vision-preview"
    fun setGroqModel(ctx: android.content.Context, v: String) = prefs(ctx).edit().putString(KEY_GROQ_MODEL, v).apply()

    // Gemini — vision-capable and fast
    fun geminiKey(ctx: android.content.Context) = prefs(ctx).getString(KEY_GEMINI_KEY, "") ?: ""
    fun setGeminiKey(ctx: android.content.Context, v: String) = prefs(ctx).edit().putString(KEY_GEMINI_KEY, v).apply()
    fun geminiModel(ctx: android.content.Context) =
        prefs(ctx).getString(KEY_GEMINI_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash"
    fun setGeminiModel(ctx: android.content.Context, v: String) = prefs(ctx).edit().putString(KEY_GEMINI_MODEL, v).apply()

    // Ollama — multimodal local server
    fun ollamaHost(ctx: android.content.Context) =
        prefs(ctx).getString(KEY_OLLAMA_HOST, "http://10.0.2.2:11434") ?: "http://10.0.2.2:11434"
    fun setOllamaHost(ctx: android.content.Context, v: String) = prefs(ctx).edit().putString(KEY_OLLAMA_HOST, v).apply()
    fun ollamaModel(ctx: android.content.Context) =
        prefs(ctx).getString(KEY_OLLAMA_MODEL, "llama3.2-vision:11b") ?: "llama3.2-vision:11b"
    fun setOllamaModel(ctx: android.content.Context, v: String) = prefs(ctx).edit().putString(KEY_OLLAMA_MODEL, v).apply()

    fun voiceFeedback(ctx: android.content.Context) = prefs(ctx).getBoolean(KEY_VOICE_FEEDBACK, true)
    fun setVoiceFeedback(ctx: android.content.Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_VOICE_FEEDBACK, v).apply()

    /** True once the currently-selected backend has the minimum fields needed to actually call it. */
    fun isBackendConfigured(ctx: android.content.Context): Boolean = when (backend(ctx)) {
        LlmBackend.GROQ -> groqKey(ctx).isNotBlank()
        LlmBackend.GEMINI -> geminiKey(ctx).isNotBlank()
        LlmBackend.OLLAMA -> ollamaHost(ctx).isNotBlank() && ollamaModel(ctx).isNotBlank()
    }
}
