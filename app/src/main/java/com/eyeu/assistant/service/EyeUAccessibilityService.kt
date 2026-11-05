package com.eyeu.assistant.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.eyeu.assistant.automation.AgentController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The "eyes & hands" of EyeU. Stays largely passive (accessibility services must not do heavy
 * work in onAccessibilityEvent), and instead exposes [runVoiceCommand] which the overlay's mic
 * button calls once speech-to-text produces a final transcript.
 */
class EyeUAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "EyeU/A11yService"
        var instance: EyeUAccessibilityService? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var agent: AgentController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "EyeU accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally no-op: EyeU reads the tree on-demand per voice command rather than
        // reacting to every event, which keeps this callback fast and battery-friendly.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun isReady(): Boolean = instance != null

    fun runVoiceCommand(command: String, onStatus: (String) -> Unit, onSpeak: (String) -> Unit) {
        if (agent == null) {
            agent = AgentController(this, onStatus, onSpeak)
        }
        scope.launch {
            try {
                agent?.runCommand(command)
            } catch (t: Throwable) {
                Log.e(TAG, "command run failed", t)
                onStatus("Error")
                onSpeak("Sorry, something went wrong running that command.")
            }
        }
    }
}
