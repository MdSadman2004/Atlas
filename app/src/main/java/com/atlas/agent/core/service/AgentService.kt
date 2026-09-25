package com.atlas.agent.core.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.RunController
import com.atlas.agent.core.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process (and the running agent turn) alive while the user is away.
 * Purely a liveness wrapper: the actual run lives in RunController.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, AgentService::class.java)) }
        }

        fun stopIfIdle() {
            val ctx = Atlas.app
            if (!RunController.isRunning()) {
                runCatching { ctx.stopService(Intent(ctx, AgentService::class.java)) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifications.agentProgress(this, "Starting…")
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    Notifications.ID_AGENT,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Notifications.ID_AGENT, notification)
            }
        }
        if (watcher == null) {
            watcher = scope.launch {
                RunController.live.collectLatest { run ->
                    if (run != null) {
                        Notifications.updateAgentProgress(this@AgentService, run.status.ifBlank { "Working…" })
                    } else {
                        delay(2000)
                        if (RunController.live.value == null) {
                            Notifications.cancelAgentProgress(this@AgentService)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }
}
