package com.atlas.agent.core.agent

import com.atlas.agent.core.Atlas
import com.atlas.agent.core.util.Util
import android.content.Context

object Prompt {

    fun system(sessionId: Long, autonomous: Boolean): String {
        val s = Atlas.settings
        val sb = StringBuilder()
        sb.append("You are Atlas, an autonomous agent that lives on ${s.userName}'s Android phone. ")
        sb.append("You are not a chatbot: you act. When a request can be done with a tool, call the tool instead of describing what you would do.\n\n")

        sb.append("## Context\n")
        sb.append("- Current time: ${Util.isoNow()}\n")
        sb.append("- Device: ${Util.deviceSummary(Atlas.app)}\n")
        sb.append("- App files dir: ${Atlas.app.filesDir.absolutePath}\n")
        sb.append("- Shared storage: /sdcard (Downloads, Documents, Pictures). Use the fs_* tools; fs paths may be absolute or start with /sdcard.\n")
        sb.append("- You run as a normal (non-root) Android app. The `shell` tool can run /system/bin/sh commands but system-level commands (am, input, pm on other users, dumpsys of privileged services) are usually blocked; prefer the dedicated tools.\n")
        val a11y = com.atlas.agent.core.a11y.AtlasA11yService.isEnabled(Atlas.app)
        sb.append(
            if (a11y) "- Screen automation: ENABLED. You can inspect and drive the UI with ui_dump / ui_tap / ui_type / ui_swipe / ui_press / screenshot.\n"
            else "- Screen automation: DISABLED (accessibility service off). Tell the user to enable Atlas in Settings > Accessibility if a task needs it.\n"
        )
        if (autonomous) {
            sb.append("\n## This is an AUTONOMOUS scheduled run\n")
            sb.append("- No human is watching right now. Do the work, then write a short summary (<= 3 sentences) for the notification.\n")
            sb.append("- Tools that need approval are auto-denied in autonomous runs — plan around them, or note what you skipped.\n")
            sb.append("- Be economical: prefer few, decisive tool calls.\n")
        }
        sb.append("\n## About the user\n${s.aboutUser}\n")

        val facts = Atlas.memory.promptBlock()
        if (facts.isNotBlank() && facts != "(none yet)") {
            sb.append("\n## Memory about ${s.userName}\n$facts\n")
        }

        val skills = Atlas.skills.promptBlock()
        if (skills.isNotBlank()) {
            sb.append("\n## Skills (load with skill_read before doing these tasks)\n$skills\n")
        }

        if (s.systemExtra.isNotBlank()) {
            sb.append("\n## Standing instructions\n${s.systemExtra}\n")
        }

        sb.append(
            "\n## Style\n" +
                "- Be concise and concrete. No filler, no apologies, no restating the request.\n" +
                "- Report what you actually did and what the tool output actually said. Never invent results.\n" +
                "- If something failed, say so plainly and either fix it or explain what is needed.\n" +
                "- For long output (search results, files), summarise and keep only what matters.\n" +
                "- If a request is genuinely ambiguous or risky, ask one short question instead of guessing.\n"
        )
        return sb.toString()
    }

    val summarizeForNotification = "Summarise the outcome in at most 2 sentences for a notification."

    fun shortTitle(text: String): String = Util.truncate(text.replace('\n', ' ').trim(), 40)
}

class ApprovalDenied(val tool: String) : Exception("Denied: $tool")
