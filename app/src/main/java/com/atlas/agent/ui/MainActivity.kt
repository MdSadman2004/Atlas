package com.atlas.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.ApprovalHub
import com.atlas.agent.ui.theme.AtlasTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_AUTOSEND = "autosend"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        val sessionFromIntent = intent.getLongExtra(EXTRA_SESSION_ID, 0L).takeIf { it > 0 }
        setContent {
            AtlasTheme {
                AtlasRoot(initialSessionId = sessionFromIntent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.getBooleanExtra(EXTRA_AUTOSEND, false)) ShareBus.autoSend.value = true
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { ShareBus.text.value = it }
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    val att = ShareBus.copyToCache(this, uri)
                    if (att != null) ShareBus.images.value = ShareBus.images.value + att
                }
            }
        }
    }
}

private enum class Tab { Chat, Sessions, Goals, Activity, Settings }

@Composable
private fun AtlasRoot(initialSessionId: Long?) {
    val ctx = LocalContext.current
    val db = Atlas.db
    var tab by rememberSaveable { mutableStateOf(Tab.Chat) }
    var sessionId by rememberSaveable { mutableStateOf(initialSessionId ?: 0L) }

    val notifPermission = remember {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(notifPermission)
        }
        if (sessionId == 0L) {
            sessionId = db.sessions().firstOrNull()?.id ?: db.createSession("New chat")
            // enable autonomous ticks on first launch
            runCatching { com.atlas.agent.core.autonomy.GoalScheduler(ctx).scheduleAll() }
        }
    }

    val approval by ApprovalHub.pending.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column {
                        Text(if (sessionId > 0) db.sessionTitle(sessionId) else "Atlas", maxLines = 1)
                        Text(
                            Atlas.settings.model.substringAfterLast('/'),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Chat,
                    onClick = { tab = Tab.Chat },
                    icon = { Icon(Icons.Default.Chat, null) },
                    label = { Text("Chat") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Sessions,
                    onClick = { tab = Tab.Sessions },
                    icon = { Icon(Icons.Default.Forum, null) },
                    label = { Text("Chats") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Goals,
                    onClick = { tab = Tab.Goals },
                    icon = { Icon(Icons.Default.DateRange, null) },
                    label = { Text("Goals") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Activity,
                    onClick = { tab = Tab.Activity },
                    icon = { Icon(Icons.Default.CheckCircle, null) },
                    label = { Text("Activity") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Setup") },
                )
            }
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Chat -> ChatScreen(
                    sessionId = sessionId,
                    onOpenSessions = { tab = Tab.Sessions },
                )

                Tab.Sessions -> SessionsScreen(
                    onOpen = { id ->
                        sessionId = id
                        tab = Tab.Chat
                    },
                    onNew = { id ->
                        sessionId = id
                        tab = Tab.Chat
                    },
                )

                Tab.Goals -> GoalsScreen()
                Tab.Activity -> ActivityScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
    }

    approval?.let { req ->
        AlertDialog(
            onDismissRequest = { ApprovalHub.resolve(req.id, false) },
            title = { Text("Allow ${req.tool}?") },
            text = {
                Column {
                    Text(req.reason, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Box(Modifier.padding(top = 6.dp)) { BoxedText(req.args) }
                }
            },
            confirmButton = {
                TextButton(onClick = { ApprovalHub.resolve(req.id, true) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { ApprovalHub.resolve(req.id, false) }) { Text("Deny") }
            },
        )
    }
}
