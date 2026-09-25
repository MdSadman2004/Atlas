package com.atlas.agent.core.autonomy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.ApprovalHub
import com.atlas.agent.core.agent.ApprovalRequest
import com.atlas.agent.core.agent.RunController
import com.atlas.agent.core.agent.RunSink
import com.atlas.agent.core.notify.Notifications
import com.atlas.agent.core.store.GoalRow
import com.atlas.agent.core.util.Util
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Schedules the autonomous ticks. WorkManager drives everything so it survives reboots
 * and does not depend on the app being open.
 */
class GoalScheduler(private val ctx: Context) {

    companion object {
        const val UNIQUE_PERIODIC = "atlas_autonomy"
        const val KEY_GOAL_ID = "goal_id"
        const val KEY_FORCE = "force"
        const val KEY_PROMPT = "prompt"
    }

    fun scheduleAll() {
        val wm = WorkManager.getInstance(ctx)
        if (!Atlas.settings.autonomyEnabled) {
            wm.cancelUniqueWork(UNIQUE_PERIODIC)
            return
        }
        val interval = Atlas.settings.autonomyIntervalMinutes.toLong().coerceAtLeast(15L)
        val request = PeriodicWorkRequestBuilder<AutonomyWorker>(interval, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runNow(goalId: Long) {
        val request = OneTimeWorkRequestBuilder<AutonomyWorker>()
            .setInputData(workDataOf(KEY_GOAL_ID to goalId, KEY_FORCE to true))
            .build()
        WorkManager.getInstance(ctx).enqueue(request)
    }

    fun scheduleOnce(prompt: String, delayMs: Long): UUID? {
        val request = OneTimeWorkRequestBuilder<OnceTaskWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_PROMPT to prompt))
            .build()
        WorkManager.getInstance(ctx).enqueue(request)
        return request.id
    }
}

private class NotifySink(private val goalTitle: String) : RunSink {
    @Volatile
    var summary: String = ""
        private set

    override fun status(text: String) {}

    override suspend fun approval(req: ApprovalRequest): Boolean = false

    override fun assistantStart(messageId: Long) {}

    override fun textDelta(delta: String) {}

    override fun reasoningDelta(delta: String) {}

    override fun assistantDone(text: String, reasoning: String, tokens: Int) {
        if (text.isNotBlank()) summary = text.take(600)
    }

    override fun toolStarted(name: String, args: String) {}

    override fun toolFinished(name: String, ok: Boolean, result: String) {
        if (result.isNotBlank() && ok) summary = result.take(600)
    }

    override fun todos(list: List<com.atlas.agent.core.agent.Todo>) {}

    override fun error(message: String) {
        summary = "error: $message"
    }
}

/** Periodic tick: runs due goals in the background (no foreground service needed). */
class AutonomyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!Atlas.settings.autonomyEnabled) return Result.success()
        if (RunController.isRunning()) return Result.success() // user is mid-run; wait for next tick

        val forcedId = inputData.getLong(GoalScheduler.KEY_GOAL_ID, -1L)
        val force = inputData.getBoolean(GoalScheduler.KEY_FORCE, false)
        val targets: List<GoalRow> = if (force && forcedId > 0) {
            listOfNotNull(Atlas.db.goal(forcedId))
        } else {
            Atlas.db.dueGoals(System.currentTimeMillis()).take(2)
        }
        for (goal in targets) {
            val sessionId = sessionFor(goal)
            val sink = NotifySink(goal.title)
            try {
                withTimeout(4 * 60_000L) {
                    Atlas.engine.runTurn(
                        sessionId = sessionId,
                        userText = "[Autonomous run — scheduled goal '${goal.title}']\n${goal.instruction}",
                        attachments = emptyList(),
                        goalId = goal.id,
                        sink = sink,
                        maxStepsOverride = 12,
                        wallClockMillis = 3 * 60_000L,
                    )
                }
            } catch (e: Exception) {
                // keep the tick alive; report below
                sink.error(e.message ?: "unknown error")
            }
            val next = System.currentTimeMillis() + goal.intervalMinutes * 60_000L
            Atlas.db.markGoalRun(goal.id, next, sink.summary)
            if (Atlas.settings.notifyOnComplete) {
                Notifications.simple(
                    applicationContext,
                    "Goal: ${goal.title}",
                    sink.summary.ifBlank { "Ran with no report." },
                )
            }
        }
        return Result.success()
    }

    private fun sessionFor(goal: GoalRow): Long {
        val key = "goal_session_${goal.id}"
        val existing = Atlas.db.kvGet(key)?.toLongOrNull()
        if (existing != null && Atlas.db.messageCount(existing) >= 0 && Atlas.db.sessionTitle(existing).isNotBlank()) {
            return existing
        }
        val id = Atlas.db.createSession("Goal: ${goal.title}")
        Atlas.db.kvSet(key, id.toString())
        return id
    }
}

/** One-off deferred task ("remind me in 30 minutes..."). */
class OnceTaskWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prompt = inputData.getString(GoalScheduler.KEY_PROMPT) ?: return Result.success()
        val sessionId = Atlas.db.createSession("Scheduled: ${Util.truncate(prompt, 30)}")
        val sink = NotifySink("scheduled task")
        try {
            withTimeout(4 * 60_000L) {
                Atlas.engine.runTurn(
                    sessionId = sessionId,
                    userText = "[Scheduled task fired — no user is watching]\n$prompt",
                    goalId = 0L,
                    sink = sink,
                    maxStepsOverride = 12,
                    wallClockMillis = 3 * 60_000L,
                )
            }
        } catch (e: Exception) {
            sink.error(e.message ?: "unknown error")
        }
        Notifications.simple(applicationContext, "Scheduled task", sink.summary.ifBlank { "Done (no report)." })
        return Result.success()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { GoalScheduler(context).scheduleAll() }
    }
}
