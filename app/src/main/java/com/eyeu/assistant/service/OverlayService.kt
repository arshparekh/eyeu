package com.eyeu.assistant.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.eyeu.assistant.EyeUApp
import com.eyeu.assistant.R
import com.eyeu.assistant.ui.MainActivity
import kotlin.math.abs

/**
 * Hosts the always-on-top floating bar: a small pill docked near the bottom of the screen
 * (draggable) that expands upward into a status/transcript panel when tapped, and a mic button
 * that starts listening immediately. This is the visible "presence" of EyeU across all apps.
 */
class OverlayService : Service() {

    companion object {
        private const val NOTIF_ID = 4201
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var voiceIO: VoiceIO

    private var expanded = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIF_ID, buildNotification())

        voiceIO = VoiceIO(this)
        voiceIO.init()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        rootView = LayoutInflater.from(this).inflate(R.layout.overlay_root, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        windowManager.addView(rootView, params)
        wireInteractions()
    }

    private fun wireInteractions() {
        val collapsedBar = rootView.findViewById<LinearLayout>(R.id.collapsedBar)
        val expandedPanel = rootView.findViewById<LinearLayout>(R.id.expandedPanel)
        val btnMic = rootView.findViewById<ImageView>(R.id.btnMic)
        val btnToggle = rootView.findViewById<ImageView>(R.id.btnCollapseToggle)
        val tvStatus = rootView.findViewById<TextView>(R.id.tvStatus)
        val tvTranscript = rootView.findViewById<TextView>(R.id.tvTranscript)

        btnToggle.setOnClickListener {
            expanded = !expanded
            expandedPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        }

        btnMic.setOnClickListener {
            if (!expanded) {
                expanded = true
                expandedPanel.visibility = View.VISIBLE
            }
            startListeningFlow(btnMic, tvStatus, tvTranscript)
        }

        makeDraggable(collapsedBar)
    }

    private fun startListeningFlow(btnMic: ImageView, tvStatus: TextView, tvTranscript: TextView) {
        val a11y = EyeUAccessibilityService.instance
        if (a11y == null) {
            tvStatus.text = "Turn on EyeU's accessibility service first (tap to open EyeU app)."
            rootView.findViewById<View>(R.id.expandedPanel).setOnClickListener { openApp() }
            return
        }

        btnMic.setBackgroundResource(R.drawable.bg_mic_listening)
        tvStatus.text = "Listening…"
        tvTranscript.visibility = View.GONE

        voiceIO.startListening(object : VoiceIO.Listener {
            override fun onPartial(text: String) {
                mainHandler.post {
                    tvTranscript.visibility = View.VISIBLE
                    tvTranscript.text = text
                }
            }

            override fun onListeningStateChanged(listening: Boolean) {
                mainHandler.post {
                    btnMic.setBackgroundResource(if (listening) R.drawable.bg_mic_listening else R.drawable.bg_mic_idle)
                }
            }

            override fun onFinal(text: String) {
                mainHandler.post {
                    tvTranscript.visibility = View.VISIBLE
                    tvTranscript.text = "\u201c$text\u201d"
                    tvStatus.text = "Working on it…"
                    btnMic.setBackgroundResource(R.drawable.bg_mic_idle)
                }
                a11y.runVoiceCommand(
                    command = text,
                    onStatus = { status -> mainHandler.post { tvStatus.text = status } },
                    onSpeak = { line ->
                        mainHandler.post { tvStatus.text = line }
                        if (com.eyeu.assistant.data.Prefs.voiceFeedback(this@OverlayService)) {
                            voiceIO.speak(line)
                        }
                    }
                )
            }

            override fun onError(message: String) {
                mainHandler.post {
                    tvStatus.text = message
                    btnMic.setBackgroundResource(R.drawable.bg_mic_idle)
                }
            }
        })
    }

    /** Lets the user drag the collapsed pill up/down along the screen edge to reposition it. */
    private fun makeDraggable(handleView: View) {
        var initialY = 0
        var touchStartY = 0f
        var moved = false

        handleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    touchStartY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (touchStartY - event.rawY).toInt()
                    if (abs(dy) > 6) moved = true
                    params.y = (initialY + dy).coerceIn(0, 2000)
                    windowManager.updateViewLayout(rootView, params)
                    true
                }
                MotionEvent.ACTION_UP -> moved // consume if it was a drag, else let click fire
                else -> false
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, EyeUApp.CHANNEL_OVERLAY)
            .setContentTitle("EyeU is active")
            .setContentText("Tap the floating bar to give a voice command.")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        voiceIO.release()
        runCatching { windowManager.removeView(rootView) }
    }
}
