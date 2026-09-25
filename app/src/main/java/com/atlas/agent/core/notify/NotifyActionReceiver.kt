package com.atlas.agent.core.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.atlas.agent.core.agent.ApprovalHub
import com.atlas.agent.core.agent.RunController

class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Notifications.ACTION_STOP_RUN -> {
                RunController.stop()
                Notifications.cancelAgentProgress(context)
            }
            Notifications.ACTION_APPROVE, Notifications.ACTION_DENY -> {
                val id = intent.getLongExtra("approval_id", -1L)
                val allow = intent.action == Notifications.ACTION_APPROVE
                if (id > 0) ApprovalHub.resolve(id, allow)
                runCatching { NotificationManagerCompat.from(context).cancel((2000 + (id % 1000)).toInt()) }
            }
        }
    }
}
