package com.eyeu.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class EyeUApp : Application() {
    companion object {
        const val CHANNEL_OVERLAY = "eyeu_overlay"
        const val CHANNEL_STATUS = "eyeu_status"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_OVERLAY, "EyeU Overlay", NotificationManager.IMPORTANCE_MIN)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "EyeU Status", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
