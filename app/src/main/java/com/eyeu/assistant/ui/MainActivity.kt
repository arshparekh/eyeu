package com.eyeu.assistant.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.eyeu.assistant.R
import com.eyeu.assistant.data.LlmBackend
import com.eyeu.assistant.data.Prefs
import com.eyeu.assistant.service.EyeUAccessibilityService
import com.eyeu.assistant.service.OverlayService

/**
 * Home screen: onboarding steps (overlay/accessibility/mic permissions), a start/stop toggle,
 * and a read-only summary of the AI backend. Actual API keys and model choices are edited only
 * in SettingsActivity, reached via the bottom bar — never shown here.
 */
class MainActivity : AppCompatActivity() {

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(this, if (granted) "Microphone access granted" else "Microphone access denied", Toast.LENGTH_SHORT).show()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnGrantMic).setOnClickListener {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        findViewById<LinearLayout>(R.id.btnBottomSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnBottomHome).setOnClickListener { /* already home */ }

        val btnToggle = findViewById<Button>(R.id.btnToggleOverlay)
        btnToggle.setOnClickListener {
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Grant the overlay permission first (step 1).", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!isAccessibilityServiceEnabled()) {
                    Toast.makeText(this, "Turn on the EyeU accessibility service first (step 2).", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                startForegroundOverlay()
            }
            refreshToggleButtonLabel()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshToggleButtonLabel()
        refreshBackendSummary()
    }

    private fun refreshToggleButtonLabel() {
        findViewById<Button>(R.id.btnToggleOverlay).text =
            getString(if (OverlayService.isRunning) R.string.btn_stop else R.string.btn_start)
    }

    private fun refreshBackendSummary() {
        val configured = Prefs.isBackendConfigured(this)
        val backend = Prefs.backend(this)
        val label = when (backend) {
            LlmBackend.GROQ -> "Groq — ${Prefs.groqModel(this)}"
            LlmBackend.GEMINI -> "Gemini — ${Prefs.geminiModel(this)}"
            LlmBackend.OLLAMA -> "Ollama — ${Prefs.ollamaModel(this)} @ ${Prefs.ollamaHost(this)}"
        }
        findViewById<TextView>(R.id.tvBackendSummary).text =
            if (configured) "Using $label. Change this in Settings below."
            else "Not configured yet — EyeU will use offline commands only until you add a key in Settings below."
    }

    private fun startForegroundOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, EyeUAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
