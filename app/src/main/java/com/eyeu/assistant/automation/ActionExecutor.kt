package com.eyeu.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.eyeu.assistant.data.LlmAction

/**
 * Executes a single [LlmAction] against the live screen using Android Accessibility APIs.
 * Supports intelligent app package resolution, node actions (click, text typing, enter/search submission),
 * gesture fallbacks (taps, swipes), and system controls.
 */
class ActionExecutor(
    private val service: AccessibilityService,
    private val tree: ScreenTreeExtractor
) {
    companion object {
        private const val TAG = "EyeU/Executor"

        private val APP_ALIASES = mapOf(
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "camera" to "com.android.camera",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "deskclock" to "com.google.android.deskclock",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "play store" to "com.android.vending",
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer"
        )
    }

    suspend fun execute(action: LlmAction): Boolean {
        return try {
            when (action.type) {
                "click" -> click(action.targetId)
                "long_click" -> longClick(action.targetId)
                "type_text", "type" -> typeText(action.targetId, action.text.orEmpty(), action.pressEnter)
                "press_enter", "enter", "submit", "search" -> pressEnter(action.targetId)
                "scroll" -> scroll(action.targetId, action.direction ?: "down")
                "swipe" -> swipeScreen(action.direction ?: "up")
                "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "open_app", "launch_app" -> openApp(action.packageName)
                "tap_xy" -> tapXyAction(action)
                "set_alarm" -> setAlarm(action)
                "wait" -> {
                    val waitMs = action.millis ?: 1000L
                    kotlinx.coroutines.delay(waitMs)
                    true
                }
                "notify", "done" -> true
                else -> {
                    Log.w(TAG, "Unknown action type: ${action.type}")
                    false
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Action execution failed: $action", t)
            false
        }
    }

    private fun click(id: Int?): Boolean {
        val node = id?.let { tree.nodeFor(it) } ?: return false
        val clickable = findClickableSelfOrAncestor(node)
        if (clickable != null) {
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Fallback: tap the center of its bounds via a gesture.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun longClick(id: Int?): Boolean {
        val node = id?.let { tree.nodeFor(it) } ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun typeText(id: Int?, rawText: String, pressEnter: Boolean): Boolean {
        val shouldEnter = pressEnter || rawText.contains("\n")
        val cleanText = rawText.removeSuffix("\n")

        val node = id?.let { tree.nodeFor(it) } ?: findFocusedOrFirstEditableNode()
        if (node == null) {
            Log.w(TAG, "typeText: No target or editable node found")
            return false
        }

        // Focus the node
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        var setSuccess = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText)
            }
            setSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        if (shouldEnter) {
            pressEnterOnNode(node)
        }
        return setSuccess
    }

    private fun pressEnter(id: Int?): Boolean {
        val node = id?.let { tree.nodeFor(it) } ?: findFocusedOrFirstEditableNode()
        return if (node != null) {
            pressEnterOnNode(node)
        } else {
            // Tap bottom-right keyboard area (where standard search/enter button resides)
            val metrics = service.resources.displayMetrics
            tapAt(metrics.widthPixels * 0.9f, metrics.heightPixels * 0.92f)
        }
    }

    private fun pressEnterOnNode(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val actionSuccess = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (actionSuccess) return true
        }

        // Search for clickable submit/search siblings or tap bottom-right of keyboard
        val metrics = service.resources.displayMetrics
        return tapAt(metrics.widthPixels * 0.9f, metrics.heightPixels * 0.92f)
    }

    private fun findFocusedOrFirstEditableNode(): AccessibilityNodeInfo? {
        val windows = service.windows ?: emptyList()
        for (w in windows) {
            val root = w.root ?: continue
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) return focused
        }
        return null
    }

    private fun scroll(id: Int?, direction: String): Boolean {
        val node = id?.let { tree.nodeFor(it) }
        if (node != null && node.isScrollable) {
            val action = if (direction == "up" || direction == "left") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (node.performAction(action)) return true
        }
        return swipeScreen(direction)
    }

    private fun swipeScreen(direction: String): Boolean {
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val (x1, y1, x2, y2) = when (direction) {
            "up" -> listOf(w / 2f, h * 0.75f, w / 2f, h * 0.25f)
            "down" -> listOf(w / 2f, h * 0.25f, w / 2f, h * 0.75f)
            "left" -> listOf(w * 0.8f, h / 2f, w * 0.2f, h / 2f)
            "right" -> listOf(w * 0.2f, h / 2f, w * 0.8f, h / 2f)
            else -> listOf(w / 2f, h * 0.75f, w / 2f, h * 0.25f)
        }
        return dispatchSwipe(x1, y1, x2, y2)
    }

    private fun tapXyAction(action: LlmAction): Boolean {
        if (action.x != null && action.y != null) {
            return tapAt(action.x, action.y)
        }
        val parts = action.text?.split(",")?.map { it.trim().toFloatOrNull() } ?: return false
        if (parts.size != 2 || parts[0] == null || parts[1] == null) return false
        return tapAt(parts[0]!!, parts[1]!!)
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun openApp(rawPkgName: String?): Boolean {
        if (rawPkgName.isNullOrBlank()) return false
        val cleanPkg = rawPkgName.trim().lowercase()

        // 1. Check known aliases
        val targetPkg = APP_ALIASES[cleanPkg] ?: APP_ALIASES.entries.firstOrNull { cleanPkg.contains(it.key) }?.value ?: cleanPkg

        val pm = service.packageManager

        // 2. Try direct launch intent
        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(launchIntent)
            return true
        }

        // 3. Fallback: query installed launcher activities matching the name
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            val matched = apps.firstOrNull { app ->
                val label = app.loadLabel(pm).toString().lowercase()
                val pkg = app.activityInfo.packageName.lowercase()
                label.contains(cleanPkg) || cleanPkg.contains(label) || pkg.contains(cleanPkg)
            }
            if (matched != null) {
                val matchedIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
                if (matchedIntent != null) {
                    matchedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    service.startActivity(matchedIntent)
                    return true
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error resolving app package for $rawPkgName", e)
        }

        Log.w(TAG, "Could not find launcher intent for app: $rawPkgName")
        return false
    }

    private fun setAlarm(action: LlmAction): Boolean {
        val hour = action.hour ?: return false
        val minute = action.minute ?: 0
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            action.label?.let { putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            service.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private operator fun <T> List<T>.component1() = this[0]
    private operator fun <T> List<T>.component2() = this[1]
    private operator fun <T> List<T>.component3() = this[2]
    private operator fun <T> List<T>.component4() = this[3]
}
