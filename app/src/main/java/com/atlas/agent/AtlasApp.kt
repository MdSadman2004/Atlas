package com.atlas.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.notify.Notifications

class AtlasApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Atlas.init(this)
        runCatching { Atlas.skills.seedFromAssets() }
        createChannels()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                Atlas.foreground = true
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                Atlas.foreground = false
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(Notifications.CHANNEL_AGENT, "Atlas agent", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(Notifications.CHANNEL_ALERTS, "Atlas alerts", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(Notifications.CHANNEL_APPROVALS, "Atlas approvals", NotificationManager.IMPORTANCE_HIGH),
        )
        channels.forEach { nm.createNotificationChannel(it) }
    }
}
