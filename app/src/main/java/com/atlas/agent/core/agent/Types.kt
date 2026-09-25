package com.atlas.agent.core.agent

import android.app.Application
import org.json.JSONObject
import java.io.File

interface Tool {
    val name: String
    val description: String
    val parameters: JSONObject
    val group: String get() = "general"
    /** High-risk tools require approval (unless approvals are off). */
    val dangerous: Boolean get() = false
    val defaultEnabled: Boolean get() = true
    suspend fun run(ctx: ToolContext, args: JSONObject): String
}

class ToolContext(
    val app: Application,
    val sessionId: Long,
    val runId: Long,
    val goalRun: Boolean,
    val emitTodos: (List<Todo>) -> Unit = {},
) {
    val filesDir: File get() = app.filesDir
    val cacheDir: File get() = app.cacheDir
}

data class Attachment(val path: String, val mime: String)
data class Todo(val text: String, val status: String)

data class ApprovalRequest(
    val id: Long,
    val tool: String,
    val args: String,
    val reason: String,
)

interface RunSink {
    fun status(text: String)
    suspend fun approval(req: ApprovalRequest): Boolean
    fun assistantStart(messageId: Long)
    fun textDelta(delta: String)
    fun reasoningDelta(delta: String)
    fun assistantDone(text: String, reasoning: String, tokens: Int)
    fun toolStarted(name: String, args: String)
    fun toolFinished(name: String, ok: Boolean, result: String)
    fun todos(list: List<Todo>)
    fun error(message: String)
}

/** Convenience: JSON helper for tool implementations. */
fun JSONObject.str(key: String, default: String? = null): String? {
    if (!has(key) || isNull(key)) return default
    val v = optString(key, default ?: "")
    return if (v.isBlank()) default else v
}

fun JSONObject.reqStr(key: String): String =
    str(key) ?: throw IllegalArgumentException("missing required argument '$key'")

fun JSONObject.intOr(key: String, default: Int): Int = if (!has(key) || isNull(key)) default else optInt(key, default)
fun JSONObject.boolOr(key: String, default: Boolean): Boolean = if (!has(key) || isNull(key)) default else optBoolean(key, default)
fun JSONObject.dblOr(key: String, default: Double): Double = if (!has(key) || isNull(key)) default else optDouble(key, default)
