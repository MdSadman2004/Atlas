package com.atlas.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.ApprovalHub
import com.atlas.agent.core.agent.RunController
import com.atlas.agent.ui.theme.AtlasTheme
import com.atlas.agent.ui.theme.Motion

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_AUTOSEND = "autosend"
        /** Session that hands-free / automation prompts run in, so they never pollute real chats. */
        const val AUTOMATION_SESSION = "Automation"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        val sessionFromIntent = intent.getLongExtra(EXTRA_SESSION_ID, 0L).takeIf { it > 0 }
        setContent {
            AtlasTheme(light = Atlas.settings.theme == "light") {
                AtlasRoot(initialSessionId = sessionFromIntent)
            }
        }
    }

    /**
     * Hands-free path used by automation and device tests: submit the prompt immediately from the
     * Activity, with no dependency on the Compose composer state.
     */
    private fun runAutomation(text: String, sessionIdHint: Long = -1L) {
        val db = com.atlas.agent.core.Atlas.db
        val sessionId = if (sessionIdHint > 0) {
            sessionIdHint
        } else {
            db.sessions().firstOrNull { it.title == AUTOMATION_SESSION }?.id
                ?: db.createSession(AUTOMATION_SESSION)
        }
        com.atlas.agent.core.service.AgentService.start(this)
        com.atlas.agent.core.agent.RunController.start(sessionId, text, emptyList())
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
                val shareText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val auto = intent.getBooleanExtra(EXTRA_AUTOSEND, false)
                if (auto && !shareText.isNullOrBlank()) {
                    runAutomation(shareText, intent.getLongExtra(EXTRA_SESSION_ID, -1L))
                } else {
                    shareText?.let { ShareBus.text.value = it }
                }
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

private enum class Tab(val label: String) {
    Chat("Chat"), Sessions("Chats"), Goals("Goals"), Activity("Activity"), Setup("Setup");

    fun icon() = when (this) {
        Chat -> Icons.Default.Chat
        Sessions -> Icons.Default.Forum
        Goals -> Icons.Default.DateRange
        Activity -> Icons.Default.CheckCircle
        Setup -> Icons.Default.Settings
    }
}

@Composable
private fun AtlasRoot(initialSessionId: Long?) {
    val ctx = LocalContext.current
    val db = Atlas.db
    var tab by rememberSaveable { mutableStateOf(Tab.Chat) }
    var sessionId by rememberSaveable { mutableStateOf(initialSessionId ?: 0L) }
    val live by RunController.live.collectAsStateWithLifecycle()

    val notifPermission = remember { arrayOf(Manifest.permission.POST_NOTIFICATIONS) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(notifPermission)
        }
        if (sessionId == 0L) {
            sessionId = db.sessions().firstOrNull()?.id ?: db.createSession("New chat")
            runCatching { com.atlas.agent.core.autonomy.GoalScheduler(ctx).scheduleAll() }
        }
    }

    val approval by ApprovalHub.pending.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Atlas",
                        style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 1.2.sp),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.width(12.dp))
                    StatusPill(if (live != null) "working" else "ready", active = live != null)
                    Spacer(Modifier.weight(1f))
                    Text(
                        Atlas.settings.model.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon(), null, modifier = Modifier.size(19.dp)) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(Motion.MED, easing = Motion.easeOut)) + slideInVertically { it / 28 })
                    .togetherWith(fadeOut(tween(Motion.FAST)))
            },
            label = "tabs",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { current ->
            when (current) {
                Tab.Chat -> ChatScreen(
                    sessionId = sessionId,
                    onOpenSessions = { tab = Tab.Sessions },
                    onOpenSession = { id -> sessionId = id },
                )
                Tab.Sessions -> SessionsScreen(
                    onOpen = { id -> sessionId = id; tab = Tab.Chat },
                    onNew = { id -> sessionId = id; tab = Tab.Chat },
                )
                Tab.Goals -> GoalsScreen()
                Tab.Activity -> ActivityScreen()
                Tab.Setup -> SettingsScreen()
            }
        }
    }

    approval?.let { req ->
        AlertDialog(
            onDismissRequest = { ApprovalHub.resolve(req.id, false) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text("Allow ${req.tool}?", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Column {
                    Text(
                        req.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(Modifier.padding(top = 8.dp)) { BoxedText(req.args) }
                }
            },
            confirmButton = {
                TextButton(onClick = { ApprovalHub.resolve(req.id, true) }) {
                    Text("Allow", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { ApprovalHub.resolve(req.id, false) }) {
                    Text("Deny", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}
