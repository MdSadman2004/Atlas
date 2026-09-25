package com.atlas.agent.core.agent

import android.util.Log
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.llm.ApiMessage
import com.atlas.agent.core.llm.LlmEvent
import com.atlas.agent.core.llm.ToolSpec
import com.atlas.agent.core.store.AtlasDb
import com.atlas.agent.core.store.Settings
import com.atlas.agent.core.memory.MemoryStore
import com.atlas.agent.core.skills.SkillStore
import com.atlas.agent.core.tools.ToolRegistry
import com.atlas.agent.core.util.Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AgentEngine(
    private val settings: Settings,
    private val db: AtlasDb,
    private val llm: com.atlas.agent.core.llm.LlmClient,
    private val tools: ToolRegistry,
    private val memory: MemoryStore,
    private val skills: SkillStore,
) {

    suspend fun runTurn(
        sessionId: Long,
        userText: String?,
        attachments: List<Attachment> = emptyList(),
        goalId: Long? = null,
        sink: RunSink,
        maxStepsOverride: Int? = null,
        wallClockMillis: Long = 10 * 60_000L,
        excludeTools: Set<String> = emptySet(),
    ): Long {
        val runId = db.startRun(sessionId, goalId)
        val started = System.currentTimeMillis()
        Log.i("Atlas", "run#$runId start session=$sessionId model=${settings.model} goal=$goalId stream=${settings.streaming}")
        var steps = 0
        var tokens = 0
        var assistantMsgId: Long? = null
        try {
            if (!userText.isNullOrBlank() || attachments.isNotEmpty()) {
                val text = buildString {
                    if (!userText.isNullOrBlank()) append(userText.trim())
                    for (a in attachments) {
                        append("\n\n[Attached ${a.mime}: ${a.path}]")
                    }
                }.trim()
                db.addMessage(sessionId, "user", text)
            }

            val enabled = tools.enabled(settings).filter { it.name !in excludeTools }
            val specs = enabled.map { ToolSpec(it.name, it.description, it.parameters) }
            val maxSteps = maxStepsOverride ?: settings.maxSteps

            var finished = false
            while (steps < maxSteps && !finished) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() - started > wallClockMillis) {
                    sink.status("Time budget reached")
                    break
                }
                steps++

                sink.status("Thinking")
                val history = db.messagesForApi(sessionId, settings.historyLimit)
                val system = Prompt.system(sessionId, goalId != null)
                assistantMsgId = db.addMessage(sessionId, "assistant", "")
                sink.assistantStart(assistantMsgId)

                val result = llm.chat(
                    messages = history,
                    tools = specs,
                    system = system,
                    model = settings.model,
                    maxTokens = settings.maxTokens,
                    temperature = settings.temperature.toDouble(),
                    stream = settings.streaming,
                ) { ev ->
                    when (ev) {
                        is LlmEvent.Text -> sink.textDelta(ev.delta)
                        is LlmEvent.Reasoning -> sink.reasoningDelta(ev.delta)
                        else -> {}
                    }
                }
                tokens += result.promptTokens + result.completionTokens
                Log.i(
                    "Atlas",
                    "llm: chars=${result.text.length} tools=${result.toolCalls.size} " +
                        "tokens=${result.promptTokens}+${result.completionTokens} finish=${result.finishReason}"
                )
                sink.assistantDone(result.text, result.reasoning, result.promptTokens + result.completionTokens)

                val payload: String? = if (result.toolCalls.isNotEmpty()) {
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().also { arr ->
                            for (tc in result.toolCalls) {
                                arr.put(
                                    JSONObject()
                                        .put("id", tc.id)
                                        .put("name", tc.name)
                                        .put("arguments", tc.arguments)
                                )
                            }
                        }
                    ).toString()
                } else null
                db.updateMessage(assistantMsgId, result.text, result.reasoning, payload)
                assistantMsgId = null

                if (result.toolCalls.isEmpty()) {
                    finished = true
                    continue
                }

                for (call in result.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    val tool = tools.get(call.name)
                    val callId = call.id.ifBlank { UUID.randomUUID().toString() }
                    if (tool == null) {
                        db.addMessage(sessionId, "tool", "ERROR: unknown tool '${call.name}'", null, call.name, null, callId)
                        sink.toolFinished(call.name, false, "unknown tool")
                        continue
                    }
                    sink.toolStarted(call.name, call.arguments)
                    val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
                    var resultText: String
                    var ok = true
                    try {
                        val needsApproval = when {
                            settings.approvals == Settings.APPROVALS_OFF -> false
                            settings.approvals == Settings.APPROVALS_MANUAL -> true
                            else -> tool.dangerous
                        }
                        if (needsApproval) {
                            if (goalId != null) {
                                ok = false
                                resultText = "DENIED (autonomous run): '${tool.name}' needs in-app approval and this run is unattended. " +
                                    "Tell the user what you wanted to do; do not retry."
                            } else {
                                val allowed = sink.approval(
                                    ApprovalRequest(
                                        id = System.nanoTime(),
                                        tool = tool.name,
                                        args = Util.truncate(call.arguments, 600),
                                        reason = if (tool.dangerous) "high-risk tool" else "manual approval mode",
                                    )
                                )
                                if (allowed) {
                                    resultText = withTimeout(300_000) { tool.run(ctx(sessionId, runId, goalId, sink), args) }
                                } else {
                                    ok = false
                                    resultText = "DENIED by the user. Do not retry; continue without it or ask what they want."
                                }
                            }
                        } else {
                            resultText = withTimeout(300_000) { tool.run(ctx(sessionId, runId, goalId, sink), args) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ok = false
                        resultText = "ERROR: ${e.message ?: e.javaClass.simpleName}"
                    }
                    db.addMessage(sessionId, "tool", Util.truncate(resultText, 20000), null, call.name, null, callId)
                    db.addEvent(runId, call.name, Util.truncate(call.arguments, 1000), Util.truncate(resultText, 1500), ok)
                    Log.i("Atlas", "tool ${call.name} ok=$ok → ${resultText.take(160).replace('\n', ' ')}")
                    sink.toolFinished(call.name, ok, Util.truncate(resultText, 1200))
                }
            }

            val status = if (finished) "done" else if (steps >= maxSteps) "max-steps" else "timeout"
            db.finishRun(runId, status, steps, tokens, null)
            Log.i("Atlas", "run#$runId end status=$status steps=$steps tokens=$tokens")
            sink.status("")
            if (settings.autoMemory && goalId == null && !userText.isNullOrBlank()) {
                runCatching { extractMemory(sessionId) }
            }
            return runId
        } catch (e: CancellationException) {
            assistantMsgId?.let { db.updateMessage(it, "[cancelled]", null, null) }
            db.finishRun(runId, "cancelled", steps, tokens, null)
            sink.status("")
            throw e
        } catch (e: Exception) {
            assistantMsgId?.let { db.updateMessage(it, "[error] ${e.message}", null, null) }
            db.finishRun(runId, "error", steps, tokens, e.message)
            sink.error(e.message ?: "unknown error")
            sink.status("")
            return runId
        }
    }

    private fun ctx(sessionId: Long, runId: Long, goalId: Long?, sink: RunSink) =
        ToolContext(Atlas.app, sessionId, runId, goalId != null, emitTodos = { sink.todos(it) })

    private suspend fun extractMemory(sessionId: Long) {
        val rows = db.lastMessages(sessionId, 12)
        if (rows.isEmpty()) return
        val transcript = rows.joinToString("\n") { "${it.role}: ${it.content.take(700)}" }
        val sys = "You extract durable, long-lived facts about the user from a conversation. " +
            "Return ONLY a JSON array of short factual strings (max 3 items, each <= 140 chars). " +
            "Facts must be stable preferences, ongoing projects, or personal details worth remembering across sessions. " +
            "Never include transient details, secrets, or anything about the assistant. If nothing qualifies, return []."
        val out = runCatching {
            llm.complete(
                messages = listOf(ApiMessage(role = "user", content = transcript)),
                system = sys,
                model = settings.fastModel,
                maxTokens = 400,
                temperature = 0.0,
            )
        }.getOrNull() ?: return
        val jsonStart = out.indexOf('[')
        val jsonEnd = out.lastIndexOf(']')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return
        val arr = runCatching { JSONArray(out.substring(jsonStart, jsonEnd + 1)) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val fact = arr.optString(i, "").trim()
            if (fact.isNotBlank()) memory.addIfNew(fact, 2)
        }
    }

    /** Generate a short session title; falls back to the first user line. */
    suspend fun titleFor(sessionId: Long): String {
        val first = db.lastMessages(sessionId, 4).firstOrNull { it.role == "user" }?.content ?: return "New chat"
        if (!settings.titleModel) return Util.truncate(first.replace('\n', ' '), 40)
        return runCatching {
            llm.complete(
                messages = listOf(ApiMessage(role = "user", content = first.take(400))),
                system = "Write a 3-6 word title for this chat. Output only the title, no quotes.",
                model = settings.fastModel,
                maxTokens = 20,
                temperature = 0.3,
            ).trim().trim('"').replace('\n', ' ').take(60)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: Util.truncate(first.replace('\n', ' '), 40)
    }
}
