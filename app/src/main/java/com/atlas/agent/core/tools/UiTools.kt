package com.atlas.agent.core.tools

import com.atlas.agent.core.a11y.AtlasA11yService
import com.atlas.agent.core.agent.Tool
import com.atlas.agent.core.agent.ToolContext
import com.atlas.agent.core.agent.boolOr
import com.atlas.agent.core.agent.dblOr
import com.atlas.agent.core.agent.intOr
import com.atlas.agent.core.agent.reqStr
import com.atlas.agent.core.agent.str
import org.json.JSONObject

private fun svc(): AtlasA11yService? = AtlasA11yService.instance

private const val NEED_A11Y =
    "Accessibility service is OFF. Ask the user to enable 'Atlas' in Settings > Accessibility, then retry. " +
        "(open_settings screen=accessibility can take them there.)"

class UiDumpTool : Tool {
    override val name = "ui_dump"
    override val description =
        "Dump the current screen's UI tree as text: every node's class, text, content description, whether it is clickable and its bounds. " +
            "Use it to find what is on screen before tapping. Then ui_tap with the text you saw, or with coordinates."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"max_nodes":{"type":"integer","description":"default 120"}}}"""
    )
    override val group = "ui"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val s = svc() ?: return NEED_A11Y
        return s.dumpTree(args.intOr("max_nodes", 120).coerceIn(10, 400))
    }
}

class UiTapTool : Tool {
    override val name = "ui_tap"
    override val description =
        "Tap on screen. Prefer tapping by text or content_desc (matched case-insensitively, exact match first then contains). " +
            "Use index to pick among several matches. Raw x/y coordinates (screen pixels, top-left origin) also work."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"text":{"type":"string"},"content_desc":{"type":"string"},"index":{"type":"integer","description":"which match to tap, default 0"},"x":{"type":"number"},"y":{"type":"number"}},"required":[]}"""
    )
    override val group = "ui"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val s = svc() ?: return NEED_A11Y
        val x = if (args.has("x") && !args.isNull("x")) args.dblOr("x", -1.0) else -1.0
        val y = if (args.has("y") && !args.isNull("y")) args.dblOr("y", -1.0) else -1.0
        if (x >= 0 && y >= 0) {
            return if (s.tapAt(x.toFloat(), y.toFloat())) "tapped ($x, $y)" else "tap gesture failed"
        }
        val needle = (args.str("text", null) ?: args.str("content_desc", null) ?: "").trim()
        if (needle.isBlank()) return "Provide text, content_desc or x/y."
        val matches = s.findNodes(needle)
        if (matches.isEmpty()) return "Nothing on screen matches '$needle'. Run ui_dump to see the screen."
        val idx = args.intOr("index", 0).coerceIn(0, matches.size - 1)
        val node = matches[idx]
        val label = (node.text ?: node.contentDescription ?: "").toString().take(60)
        val ok = s.clickNode(node)
        return if (ok) "tapped '$label'${if (matches.size > 1) " (match ${idx + 1} of ${matches.size})" else ""}"
        else "found '$label' but the click failed (not clickable — try tapping its coordinates from ui_dump)"
    }
}

class UiTypeTool : Tool {
    override val name = "ui_type"
    override val description =
        "Type text into a text field. If target_text is given, the field whose text/description matches it is used; otherwise the currently focused editable field. " +
            "Tip: ui_tap the field first to focus it."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"text":{"type":"string"},"target_text":{"type":"string"}},"required":["text"]}"""
    )
    override val group = "ui"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val s = svc() ?: return NEED_A11Y
        val text = args.reqStr("text")
        val target = args.str("target_text", null)
        val node = s.findEditable(target)
            ?: return "No editable field found${if (target != null) " matching '$target'" else ""}. ui_dump, then ui_tap the field, then retry."
        return if (s.setText(node, text)) "typed into field${target?.let { " '$it'" } ?: ""} (${text.length} chars)" else "could not set text on that field"
    }
}

class UiSwipeTool : Tool {
    override val name = "ui_swipe"
    override val description = "Swipe/drag from one screen coordinate to another (pixels)."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"from_x":{"type":"number"},"from_y":{"type":"number"},"to_x":{"type":"number"},"to_y":{"type":"number"},"duration_ms":{"type":"integer","description":"default 300"}},"required":["from_x","from_y","to_x","to_y"]}"""
    )
    override val group = "ui"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val s = svc() ?: return NEED_A11Y
        val ok = s.swipe(
            args.dblOr("from_x", 0.0).toFloat(), args.dblOr("from_y", 0.0).toFloat(),
            args.dblOr("to_x", 0.0).toFloat(), args.dblOr("to_y", 0.0).toFloat(),
            args.intOr("duration_ms", 300).toLong()
        )
        return if (ok) "swiped" else "swipe failed"
    }
}

class UiScrollTool : Tool {
    override val name = "ui_scroll"
    override val description = "Scroll the screen (up/down/left/right) — a single page swipe."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"direction":{"type":"string","enum":["up","down","left","right"]},"distance":{"type":"number","description":"0..1 fraction of screen, default 0.6"}},"required":["direction"]}"""
    )
    override val group = "ui"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val s = svc() ?: return NEED_A11Y
        val dir = args.reqStr("direction")
        val frac = args.dblOr("distance", 0.6).coerceIn(0.1, 1.0)
        val ok = s.scroll(dir, frac)
        return if (ok) "scrolled $dir" else "scroll failed"
    }
}

class UiPressTool : Tool {
    override val name = "ui_press"
    override val description = "Press a system key: back, home, recents, notifications, quick_settings (power/mute-volumes need root)."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"key":{"type":"string","enum":["back","home","recents","notifications","quick_settings"]}},"required":["key"]}"""
    )
    override val group = "ui"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val s = svc() ?: return NEED_A11Y
        val k = args.reqStr("key")
        val ok = s.globalAction(k)
        return if (ok) "pressed $k" else "unknown key: $k"
    }
}
