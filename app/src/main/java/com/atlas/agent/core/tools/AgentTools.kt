package com.atlas.agent.core.tools

import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.Tool
import com.atlas.agent.core.agent.ToolContext
import com.atlas.agent.core.agent.intOr
import com.atlas.agent.core.agent.reqStr
import com.atlas.agent.core.agent.str
import com.atlas.agent.core.util.Util
import org.json.JSONObject

class MemorySaveTool : Tool {
    override val name = "memory_save"
    override val description =
        "Store a durable fact about the user (preference, ongoing project, personal detail) in long-term memory. Facts are injected into every future conversation."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"text":{"type":"string"},"importance":{"type":"integer","description":"1 normal, 2 important, 3 critical"}},"required":["text"]}"""
    )
    override val group = "memory"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val text = args.reqStr("text").trim()
        val imp = args.intOr("importance", 2).coerceIn(1, 3)
        val added = Atlas.memory.addIfNew(text, imp)
        return if (added) "saved to memory: $text" else "already known (not duplicated)"
    }
}

class MemorySearchTool : Tool {
    override val name = "memory_search"
    override val description = "Search long-term memory facts about the user. Empty query returns the most important facts."
    override val parameters = JSONObject("""{"type":"object","properties":{"query":{"type":"string"}}}""")
    override val group = "memory"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val q = args.str("query", "") ?: ""
        val rows = if (q.isBlank()) Atlas.memory.all(40) else Atlas.memory.search(q, 30)
        if (rows.isEmpty()) return "No matching memories."
        return rows.joinToString("\n") { "[${it.id}] ${it.text}" }
    }
}

class MemoryForgetTool : Tool {
    override val name = "memory_forget"
    override val description = "Delete a memory fact by its id (get ids from memory_search)."
    override val parameters = JSONObject("""{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}""")
    override val group = "memory"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val id = args.intOr("id", -1)
        if (id <= 0) return "Provide a valid id."
        Atlas.memory.forget(id.toLong())
        return "forgotten: id=$id"
    }
}

class SkillsListTool : Tool {
    override val name = "skills_list"
    override val description = "List installed skills (name + description). Load one with skill_read before following it."
    override val parameters = JSONObject("""{"type":"object","properties":{}}""")
    override val group = "skills"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val items = Atlas.skills.list()
        if (items.isEmpty()) return "No skills installed yet."
        return items.joinToString("\n") { "- ${it.name} — ${it.description}" }
    }
}

class SkillReadTool : Tool {
    override val name = "skill_read"
    override val description = "Read the full markdown body of a skill by name."
    override val parameters = JSONObject("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
    override val group = "skills"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val name = args.reqStr("name")
        return Atlas.skills.read(name)?.let { Util.truncate(it, 20000) }
            ?: "No skill named '$name'. Use skills_list."
    }
}

class SkillWriteTool : Tool {
    override val name = "skill_write"
    override val description =
        "Create or overwrite a skill: a reusable procedure the agent will follow in future. Give a short name, one-line description, and the markdown body with concrete steps."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"body":{"type":"string"}},"required":["name","description","body"]}"""
    )
    override val group = "skills"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val f = Atlas.skills.write(args.reqStr("name"), args.reqStr("description"), args.reqStr("body"))
        return "skill written: ${f.absolutePath}"
    }
}

class SkillDeleteTool : Tool {
    override val name = "skill_delete"
    override val description = "Delete a skill by name."
    override val parameters = JSONObject("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
    override val group = "skills"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val ok = Atlas.skills.delete(args.reqStr("name"))
        return if (ok) "deleted" else "no such skill"
    }
}

class GoalCreateTool : Tool {
    override val name = "goal_create"
    override val description =
        "Create a standing autonomous goal: Atlas will run 'instruction' on a repeating schedule (every N minutes) in the background, even when the app is closed, and notify the user of results. Use for monitoring, recurring checks, reminders and self-directed upkeep."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"title":{"type":"string"},"instruction":{"type":"string","description":"what to do on each run; be specific, e.g. 'Check the weather in Dhaka and notify only if rain is likely'"},"interval_minutes":{"type":"integer","description":"15-720, default 60"}},"required":["title","instruction"]}"""
    )
    override val group = "goals"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val title = args.reqStr("title")
        val instruction = args.reqStr("instruction")
        val interval = args.intOr("interval_minutes", 60).coerceIn(15, 720)
        val id = Atlas.db.insertGoal(title, instruction, interval)
        com.atlas.agent.core.autonomy.GoalScheduler(ctx.app).scheduleAll()
        return "goal #$id created: '$title' every ${interval}min"
    }
}

class GoalListTool : Tool {
    override val name = "goal_list"
    override val description = "List autonomous goals with their schedule state and last result."
    override val parameters = JSONObject("""{"type":"object","properties":{}}""")
    override val group = "goals"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val goals = Atlas.db.goals()
        if (goals.isEmpty()) return "No goals yet."
        return goals.joinToString("\n") { g ->
            "#${g.id} ${if (g.enabled) "ON " else "OFF"} '${g.title}' every ${g.intervalMinutes}min, ran ${g.runCount}x, last: ${Util.ago(g.lastRun)}" +
                (g.lastResult?.let { " → ${it.take(120)}" } ?: "")
        }
    }
}

class GoalUpdateTool : Tool {
    override val name = "goal_update"
    override val description = "Enable/disable a goal, change its interval, or rewrite its instruction."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"id":{"type":"integer"},"enabled":{"type":"boolean"},"interval_minutes":{"type":"integer"},"instruction":{"type":"string"}},"required":["id"]}"""
    )
    override val group = "goals"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val id = args.intOr("id", -1).toLong()
        if (id <= 0) return "Provide a valid id."
        val enabled = if (args.has("enabled") && !args.isNull("enabled")) args.optBoolean("enabled") else null
        val interval = if (args.has("interval_minutes") && !args.isNull("interval_minutes")) args.optInt("interval_minutes").coerceIn(15, 720) else null
        val instruction = args.str("instruction", null)
        Atlas.db.updateGoal(id, enabled, interval, instruction)
        com.atlas.agent.core.autonomy.GoalScheduler(ctx.app).scheduleAll()
        return "goal #$id updated"
    }
}

class GoalDeleteTool : Tool {
    override val name = "goal_delete"
    override val description = "Delete an autonomous goal."
    override val parameters = JSONObject("""{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}""")
    override val group = "goals"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val id = args.intOr("id", -1).toLong()
        if (id <= 0) return "Provide a valid id."
        Atlas.db.deleteGoal(id)
        com.atlas.agent.core.autonomy.GoalScheduler(ctx.app).scheduleAll()
        return "goal #$id deleted"
    }
}

class ScheduleOnceTool : Tool {
    override val name = "schedule_once"
    override val description =
        "Schedule a one-off background task: run 'prompt' once after delay_minutes. Use for reminders and deferred checks ('in 30 minutes …')."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"prompt":{"type":"string"},"delay_minutes":{"type":"integer","description":"default 15"}},"required":["prompt"]}"""
    )
    override val group = "goals"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val prompt = args.reqStr("prompt")
        val delay = args.intOr("delay_minutes", 15).coerceIn(1, 1440)
        val workId = com.atlas.agent.core.autonomy.GoalScheduler(ctx.app).scheduleOnce(prompt, delay * 60_000L)
        return "scheduled task (id ${workId?.toString()?.take(8)}) to run in ${delay}min"
    }
}

class SpawnAgentTool : Tool {
    override val name = "spawn_agent"
    override val description =
        "Delegate a self-contained sub-task to a fresh sub-agent with its own context; it returns its final answer. " +
            "Use for work that would flood this conversation (long research, multi-step file work). Give it everything it needs — it cannot see this chat."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"task":{"type":"string","description":"complete instructions for the sub-agent"},"context":{"type":"string"},"max_steps":{"type":"integer","description":"default 8"}},"required":["task"]}"""
    )
    override val group = "agent"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val task = args.reqStr("task")
        val extra = args.str("context", null)
        val maxSteps = args.intOr("max_steps", 8).coerceIn(1, 20)
        val full = buildString {
            append(task.trim())
            if (!extra.isNullOrBlank()) append("\n\nAdditional context:\n").append(extra.trim())
            append("\n\nWork autonomously with your tools and finish with a concise report.")
        }
        val session = Atlas.db.createSession("subagent: " + task.replace('\n', ' ').take(30))
        try {
            Atlas.engine.runTurn(
                sessionId = session,
                userText = full,
                goalId = if (ctx.goalRun) 0L else null,
                sink = CollectSink(),
                maxStepsOverride = maxSteps,
                wallClockMillis = 5 * 60_000L,
                excludeTools = setOf("spawn_agent"),
            )
            val rows = Atlas.db.messages(session)
            val answer = rows.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content
            return answer?.let { Util.truncate(it, 8000) } ?: "(sub-agent produced no answer)"
        } catch (e: Exception) {
            return "sub-agent failed: ${e.message}"
        } finally {
            Atlas.db.deleteSession(session)
        }
    }

    private class CollectSink : com.atlas.agent.core.agent.RunSink {
        override fun status(text: String) {}
        override suspend fun approval(req: com.atlas.agent.core.agent.ApprovalRequest): Boolean =
            com.atlas.agent.core.agent.ApprovalHub.request(req)

        override fun assistantStart(messageId: Long) {}
        override fun textDelta(delta: String) {}
        override fun reasoningDelta(delta: String) {}
        override fun assistantDone(text: String, reasoning: String, tokens: Int) {}
        override fun toolStarted(name: String, args: String) {}
        override fun toolFinished(name: String, ok: Boolean, result: String) {}
        override fun todos(list: List<com.atlas.agent.core.agent.Todo>) {}
        override fun error(message: String) {}
    }
}

class SetTodosTool : Tool {
    override val name = "set_todos"
    override val description =
        "Publish/update your working plan for the current task as a checklist the user can watch. Call it when a task needs 3+ steps; keep statuses todo|doing|done."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"todos":{"type":"array","items":{"type":"object","properties":{"text":{"type":"string"},"status":{"type":"string","enum":["todo","doing","done"]}},"required":["text","status"]}}},"required":["todos"]}"""
    )
    override val group = "agent"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val arr = args.optJSONArray("todos") ?: return "Provide a todos array."
        val list = ArrayList<com.atlas.agent.core.agent.Todo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                com.atlas.agent.core.agent.Todo(
                    text = o.optString("text"),
                    status = o.optString("status", "todo"),
                )
            )
        }
        ctx.emitTodos(list)
        return "plan updated (${list.count { it.status == "done" }}/${list.size} done)"
    }
}
