package com.eyeu.assistant.ui

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eyeu.assistant.R
import com.eyeu.assistant.data.LlmBackend
import com.eyeu.assistant.data.Prefs

/**
 * The only place API keys / model choices live. Deliberately not reachable from the home screen
 * directly — only via the bottom "Settings" tab — so the home screen stays a clean status/start
 * surface.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        loadIntoUi()
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveSettings)
            .setOnClickListener { saveFromUi() }
    }

    private fun loadIntoUi() {
        when (Prefs.backend(this)) {
            LlmBackend.GROQ -> findViewById<RadioButton>(R.id.radioGroq).isChecked = true
            LlmBackend.GEMINI -> findViewById<RadioButton>(R.id.radioGemini).isChecked = true
            LlmBackend.OLLAMA -> findViewById<RadioButton>(R.id.radioOllama).isChecked = true
        }
        findViewById<EditText>(R.id.etGroqKey).setText(Prefs.groqKey(this))
        findViewById<EditText>(R.id.etGroqModel).setText(Prefs.groqModel(this))
        findViewById<EditText>(R.id.etGeminiKey).setText(Prefs.geminiKey(this))
        findViewById<EditText>(R.id.etGeminiModel).setText(Prefs.geminiModel(this))
        findViewById<EditText>(R.id.etOllamaHost).setText(Prefs.ollamaHost(this))
        findViewById<EditText>(R.id.etOllamaModel).setText(Prefs.ollamaModel(this))
        findViewById<CheckBox>(R.id.cbVoiceFeedback).isChecked = Prefs.voiceFeedback(this)
    }

    private fun saveFromUi() {
        val backend = when {
            findViewById<RadioButton>(R.id.radioGemini).isChecked -> LlmBackend.GEMINI
            findViewById<RadioButton>(R.id.radioOllama).isChecked -> LlmBackend.OLLAMA
            else -> LlmBackend.GROQ
        }
        Prefs.setBackend(this, backend)
        Prefs.setGroqKey(this, findViewById<EditText>(R.id.etGroqKey).text.toString().trim())
        Prefs.setGroqModel(this, findViewById<EditText>(R.id.etGroqModel).text.toString().trim())
        Prefs.setGeminiKey(this, findViewById<EditText>(R.id.etGeminiKey).text.toString().trim())
        Prefs.setGeminiModel(this, findViewById<EditText>(R.id.etGeminiModel).text.toString().trim())
        Prefs.setOllamaHost(this, findViewById<EditText>(R.id.etOllamaHost).text.toString().trim())
        Prefs.setOllamaModel(this, findViewById<EditText>(R.id.etOllamaModel).text.toString().trim())
        Prefs.setVoiceFeedback(this, findViewById<CheckBox>(R.id.cbVoiceFeedback).isChecked)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
