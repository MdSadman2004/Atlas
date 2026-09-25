package com.atlas.agent.core.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.atlas.agent.R
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.ApprovalRequest
import com.atlas.agent.ui.MainActivity

object Notifications {
    const val CHANNEL_AGENT = "atlas_agent"
    const val CHANNEL_ALERTS = "atlas_alerts"
    const val CHANNEL_APPROVALS = "atlas_approvals"

    const val ID_AGENT = 1001
    const val ID_RESULT = 1002
    private const val ID_APPROVAL_BASE = 2000

    const val ACTION_STOP_RUN = "com.atlas.agent.STOP_RUN"
    const val ACTION_APPROVE = "com.atlas.agent.APPROVE"
    const val ACTION_DENY = "com.atlas.agent.DENY"

    fun openApp(ctx: Context, sessionId: Long? = null, requestCode: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (sessionId != null) i.putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getActivity(
            ctx, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun simpleReceiver(ctx: Context, action: String, id: Long = 0, requestCode: Int): PendingIntent {
        val i = Intent(ctx, NotifyActionReceiver::class.java).setAction(action)
        if (id != 0L) i.putExtra("approval_id", id)
        return PendingIntent.getBroadcast(
            ctx, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun agentProgress(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_atlas)
            .setContentTitle("Atlas is working")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Stop", simpleReceiver(ctx, ACTION_STOP_RUN, requestCode = 91))
            .build()

    fun updateAgentProgress(ctx: Context, text: String) {
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_AGENT, agentProgress(ctx, text)) }
    }

    fun cancelAgentProgress(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(ID_AGENT) }
    }

    fun approval(req: ApprovalRequest) {
        val ctx = Atlas.app
        val n = NotificationCompat.Builder(ctx, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_atlas)
            .setContentTitle("Atlas needs approval: ${req.tool}")
            .setContentText(req.args.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText("${req.tool}\n${req.args.take(600)}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, requestCode = 92))
            .addAction(0, "Allow", simpleReceiver(ctx, ACTION_APPROVE, req.id, requestCode = (200 + (req.id % 100)).toInt()))
            .addAction(0, "Deny", simpleReceiver(ctx, ACTION_DENY, req.id, requestCode = (400 + (req.id % 100)).toInt()))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(approvalId(req.id), n) }
    }

    fun cancelApproval(id: Long) {
        runCatching { NotificationManagerCompat.from(Atlas.app).cancel(approvalId(id)) }
    }

    private fun approvalId(id: Long): Int = (ID_APPROVAL_BASE + (id % 1000)).toInt()

    fun runFinished(sessionId: Long, title: String, body: String, ok: Boolean) {
        val ctx = Atlas.app
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_atlas)
            .setContentTitle(if (ok) title else "$title (with problems)")
            .setContentText(body.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(800)))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, sessionId, requestCode = 93))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_RESULT, n) }
    }

    fun simple(ctx: Context, title: String, body: String) {
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_atlas)
            .setContentTitle(title)
            .setContentText(body.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(800)))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, requestCode = 94))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_RESULT, n) }
    }
}
