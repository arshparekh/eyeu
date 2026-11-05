package com.eyeu.assistant.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.eyeu.assistant.data.ScreenElement

/**
 * Flattens the current on-screen accessibility node hierarchy (across all visible windows,
 * e.g. app + keyboard + system dialogs) into a compact, LLM-friendly list.
 * Each element gets a small integer id so the model can reference it ("click [7]").
 */
class ScreenTreeExtractor {

    private val nodeMap = HashMap<Int, AccessibilityNodeInfo>()

    fun clear() {
        nodeMap.values.forEach { runCatching { it.recycle() } }
        nodeMap.clear()
    }

    /** Returns (elements, promptText). Caller must call clear() when done using the node references. */
    fun extract(windows: List<AccessibilityWindowInfo>): Pair<List<ScreenElement>, String> {
        clear()
        val elements = ArrayList<ScreenElement>()
        var counter = 0

        for (window in windows) {
            val root = window.root ?: continue
            counter = walk(root, elements, counter)
        }

        val prompt = if (elements.isEmpty()) {
            "(no interactive elements detected on screen)"
        } else {
            elements.joinToString("\n") { it.toPromptLine() }
        }
        return elements to prompt
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<ScreenElement>, startId: Int): Int {
        var id = startId
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val isMeaningful = node.isClickable || node.isEditable || node.isScrollable ||
            node.isCheckable || !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

        if (isMeaningful && bounds.width() > 0 && bounds.height() > 0) {
            val myId = id
            nodeMap[myId] = AccessibilityNodeInfo.obtain(node)
            out.add(
                ScreenElement(
                    id = myId,
                    text = node.text?.toString(),
                    desc = node.contentDescription?.toString(),
                    resId = node.viewIdResourceName,
                    className = node.className?.toString(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checked = if (node.isCheckable) node.isChecked else null,
                    bounds = intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
                )
            )
            id++
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            id = walk(child, out, id)
        }
        return id
    }

    fun nodeFor(id: Int): AccessibilityNodeInfo? = nodeMap[id]
}
