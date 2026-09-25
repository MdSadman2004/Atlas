package com.atlas.agent.core.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.atlas.agent.core.Atlas
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screen perception + control. The agent's ui_* tools and the screenshot tool are thin
 * wrappers over this service; everything no-ops with a clear message when it is disabled.
 */
class AtlasA11yService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AtlasA11yService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val expected = "${ctx.packageName}/${AtlasA11yService::class.java.name}"
            val enabled = runCatching {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull() ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ------------------------------------------------------------------ perception

    fun screenBounds(): Rect {
        val r = Rect()
        runCatching { rootInActiveWindow?.getBoundsInScreen(r) }
        if (r.isEmpty) {
            val dm = resources.displayMetrics
            r.set(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return r
    }

    fun dumpTree(maxNodes: Int): String {
        val root = rootInActiveWindow ?: return "No active window (screen off or nothing focused)."
        val sb = StringBuilder()
        val b = screenBounds()
        sb.append("Screen ${b.width()}x${b.height()} — window: ${root.packageName ?: "?"}\n")
        val counter = intArrayOf(0)
        walk(root, sb, 0, maxNodes, counter)
        if (counter[0] >= maxNodes) sb.append("…(truncated at $maxNodes nodes — pass a bigger max_nodes for more)\n")
        return sb.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, maxNodes: Int, counter: IntArray) {
        if (node == null || counter[0] >= maxNodes || depth > 26) return
        counter[0]++
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || viewId.isNotEmpty()
        if (interesting) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            sb.append("[${counter[0]}] ").append(node.className?.toString()?.substringAfterLast('.') ?: "?")
            if (viewId.isNotEmpty()) sb.append(" #").append(viewId)
            if (text.isNotEmpty()) sb.append(" text=\"").append(text.take(180)).append('"')
            if (desc.isNotEmpty()) sb.append(" desc=\"").append(desc.take(120)).append('"')
            if (node.isClickable) sb.append(" clickable")
            if (node.isEditable) sb.append(" editable")
            if (node.isScrollable) sb.append(" scrollable")
            sb.append(" [").append(rect.left).append(',').append(rect.top).append(',')
                .append(rect.right).append(',').append(rect.bottom).append("]\n")
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), sb, depth + 1, maxNodes, counter)
        }
    }

    fun findNodes(needle: String, limit: Int = 20): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val n = needle.lowercase()
        val exact = ArrayList<AccessibilityNodeInfo>()
        val partial = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || partial.size > limit * 4) return
            val t = node.text?.toString()?.lowercase() ?: ""
            val d = node.contentDescription?.toString()?.lowercase() ?: ""
            when {
                t == n || d == n -> exact.add(node)
                t.contains(n) || d.contains(n) -> partial.add(node)
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return (exact + partial).take(limit)
    }

    fun findEditable(target: String?): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var focused: AccessibilityNodeInfo? = null
        val matches = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isEditable) {
                if (node.isFocused && focused == null) focused = node
                if (target != null) {
                    val t = node.text?.toString()?.lowercase() ?: ""
                    val d = node.contentDescription?.toString()?.lowercase() ?: ""
                    val n = target.lowercase()
                    if (t.contains(n) || d.contains(n)) matches.add(node)
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return matches.firstOrNull() ?: focused
    }

    // ------------------------------------------------------------------ action

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            hops++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.isEmpty) return tapAt(r.exactCenterX(), r.exactCenterY())
        return false
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun globalAction(key: String): Boolean {
        val action = when (key.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun tapAt(x: Float, y: Float): Boolean = gesture(x, y, x, y, 60)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(x1, y1, x2, y2, durationMs.coerceIn(50, 3000))

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return runCatching {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, ms)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
    }

    fun scroll(direction: String, frac: Double): Boolean {
        val b = screenBounds()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val dx = (b.width() * frac / 2).toFloat()
        val dy = (b.height() * frac / 2).toFloat()
        return when (direction.lowercase()) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy, 350)
            "up" -> swipe(cx, cy - dy, cx, cy + dy, 350)
            "right" -> swipe(cx + dx, cy, cx - dx, cy, 350)
            "left" -> swipe(cx - dx, cy, cx + dx, cy, 350)
            else -> false
        }
    }

    // ------------------------------------------------------------------ screenshot

    fun captureScreenshot(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        val started = runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        }.getOrNull()
                        runCatching { screenshot.hardwareBuffer.close() }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
            true
        }.getOrDefault(false)
        if (!started) return null
        if (!latch.await(6, TimeUnit.SECONDS)) return null
        val bmp = bitmap ?: return null
        val dir = File(Atlas.app.filesDir, "shots").also { it.mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.png")
        return runCatching {
            FileOutputStream(file).use { out ->
                bmp.copy(Bitmap.Config.ARGB_8888, false).compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            file.absolutePath
        }.getOrNull()
    }
}
