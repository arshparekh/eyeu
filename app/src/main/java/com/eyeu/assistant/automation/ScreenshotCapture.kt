package com.eyeu.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Automates screen capture via AccessibilityService.takeScreenshot() (Android 11+).
 * Safely converts HardwareBuffer to a software ARGB_8888 bitmap BEFORE closing the buffer,
 * ensuring compatibility across all Android emulators and physical devices without memory corruption.
 * Includes automatic retry on transient rate-limit (code 4) and frame (code 2) errors.
 */
class ScreenshotCapture(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "EyeU/Screenshot"
        private const val MAX_RETRIES = 3
    }

    suspend fun capture(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "takeScreenshot requires Android 11 (API 30)+")
            return null
        }

        for (attempt in 1..MAX_RETRIES) {
            val bmp = captureSingleAttempt()
            if (bmp != null) return bmp
            if (attempt < MAX_RETRIES) {
                delay(300L * attempt)
            }
        }
        Log.w(TAG, "All $MAX_RETRIES screenshot attempts failed")
        return null
    }

    private suspend fun captureSingleAttempt(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            try {
                                val hwBuffer = result.hardwareBuffer
                                val colorSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    result.colorSpace
                                } else null

                                val hwBitmap = if (colorSpace != null) {
                                    Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                } else {
                                    Bitmap.wrapHardwareBuffer(
                                        hwBuffer,
                                        ColorSpace.get(ColorSpace.Named.SRGB)
                                    )
                                }

                                // Critical for emulator and device stability: convert hardware bitmap
                                // to a software bitmap BEFORE closing the hardwareBuffer.
                                val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap?.recycle()
                                hwBuffer.close()

                                cont.resume(swBitmap)
                            } catch (e: Throwable) {
                                Log.e(TAG, "Error converting screenshot hardware buffer", e)
                                cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Accessibility takeScreenshot onFailure: errorCode=$errorCode")
                            cont.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Accessibility takeScreenshot error", t)
                cont.resume(null)
            }
        }
    }

    /** JPEG-encodes and base64s a bitmap for sending to a multimodal LLM API. Downscales first to keep payloads small/fast. */
    fun toBase64Jpeg(bitmap: Bitmap, maxDim: Int = 1024, quality: Int = 80): String {
        val scaled = downscale(bitmap, maxDim)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        if (scaled != bitmap) {
            scaled.recycle()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val largest = maxOf(w, h)
        if (largest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / largest
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
}
