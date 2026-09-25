package com.atlas.agent.core.agent

import com.atlas.agent.core.Atlas
import com.atlas.agent.core.notify.Notifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class LiveRun(
    val sessionId: Long,
    val status: String = "Starting",
    val assistantId: Long? = null,
    val text: String = "",
    val reasoning: String = "",
    val activeTool: String? = null,
    val activeToolArgs: String? = null,
    val toolLog: List<String> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val error: String? = null,
    val goalRun: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
)

/**
 * Owns the single active agent run for the process. Runs execute on an application-scoped
 * coroutine so they survive the UI going away; AgentService keeps the process alive while
 * something is running.
 */
object RunController {

    private val supervisor = SupervisorJob()
    val scope = CoroutineScope(supervisor + Dispatchers.IO)

    val live = MutableStateFlow<LiveRun?>(null)
    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun start(
        sessionId: Long,
        text: String?,
        attachments: List<Attachment> = emptyList(),
        goalId: Long? = null,
        maxSteps: Int? = null,
        wallClockMillis: Long = 10 * 60_000L,
        notifyWhenDone: Boolean = false,
    ): Boolean {
        if (isRunning()) return false
        val sink = UiRunSink(sessionId, goalId != null)
        job = scope.launch {
            live.value = LiveRun(sessionId = sessionId, status = "Starting", goalRun = goalId != null)
            var ok = true
            var errorMessage: String? = null
            try {
                Atlas.engine.runTurn(
                    sessionId = sessionId,
                    userText = text,
                    attachments = attachments,
                    goalId = goalId,
                    sink = sink,
                    maxStepsOverride = maxSteps,
                    wallClockMillis = wallClockMillis,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                ok = false
                sink.status("")
            } catch (e: Exception) {
                ok = false
                errorMessage = e.message
            } finally {
                live.value = null
                job = null
                if (notifyWhenDone || (goalId != null)) {
                    Notifications.runFinished(
                        sessionId = sessionId,
                        title = if (goalId != null) "Scheduled run finished" else "Atlas finished",
                        body = errorMessage ?: "Run complete.",
                        ok = ok,
                    )
                } else if (Atlas.settings.notifyOnComplete && !Atlas.foreground) {
                    Notifications.runFinished(sessionId, "Atlas finished", errorMessage ?: "Turn complete.", ok)
                }
                com.atlas.agent.core.service.AgentService.stopIfIdle()
                if (Atlas.settings.titleModel) {
                    runCatching {
                        val session = Atlas.db.sessionTitle(sessionId)
                        if (session == "New chat" || session.startsWith("Chat ")) {
                            val t = Atlas.engine.titleFor(sessionId)
                            Atlas.db.renameSession(sessionId, t)
                        }
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
    }

    private class UiRunSink(private val sessionId: Long, private val goalRun: Boolean) : RunSink {
        private fun mutate(block: (LiveRun) -> LiveRun) {
            val cur = live.value ?: return
            if (cur.sessionId != sessionId) return
            live.value = block(cur)
        }

        override fun status(text: String) = mutate { it.copy(status = text) }

        override suspend fun approval(req: ApprovalRequest): Boolean = ApprovalHub.request(req)

        override fun assistantStart(messageId: Long) = mutate { it.copy(assistantId = messageId, text = "", reasoning = "", activeTool = null, activeToolArgs = null) }

        override fun textDelta(delta: String) = mutate { it.copy(text = it.text + delta, status = "") }

        override fun reasoningDelta(delta: String) = mutate { it.copy(reasoning = it.reasoning + delta) }

        override fun assistantDone(text: String, reasoning: String, tokens: Int) = mutate { it.copy(text = text, reasoning = reasoning, status = "Thinking") }

        override fun toolStarted(name: String, args: String) = mutate {
            it.copy(activeTool = name, activeToolArgs = args, status = "Running $name", toolLog = it.toolLog + "$name ← ${args.take(200)}")
        }

        override fun toolFinished(name: String, ok: Boolean, result: String) = mutate {
            it.copy(
                activeTool = null,
                activeToolArgs = null,
                toolLog = it.toolLog + "${if (ok) "✓" else "✗"} $name → ${result.replace('\n', ' ').take(240)}",
            )
        }

        override fun todos(list: List<Todo>) = mutate { it.copy(todos = list) }

        override fun error(message: String) = mutate { it.copy(error = message, status = "Error") }
    }
}

/** Bridges approval requests between the engine (any thread) and the UI / notification actions. */
object ApprovalHub {
    private val pendings = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val counter = AtomicLong(1000)
    val pending = MutableStateFlow<ApprovalRequest?>(null)

    suspend fun request(req: ApprovalRequest): Boolean {
        val id = counter.incrementAndGet()
        val request = req.copy(id = id)
        val deferred = CompletableDeferred<Boolean>()
        pendings[id] = deferred
        pending.value = request
        Notifications.approval(request)
        val answer = withTimeoutOrNull(5 * 60_000L) { deferred.await() }
        pendings.remove(id)
        if (pending.value?.id == id) pending.value = null
        Notifications.cancelApproval(id)
        return answer ?: false
    }

    fun resolve(id: Long, allow: Boolean) {
        pendings[id]?.complete(allow)
    }
}
