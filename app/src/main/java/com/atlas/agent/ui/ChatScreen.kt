package com.atlas.agent.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.Attachment
import com.atlas.agent.core.agent.LiveRun
import com.atlas.agent.core.agent.RunController
import com.atlas.agent.core.service.AgentService
import com.atlas.agent.core.voice.VoiceInput
import com.atlas.agent.ui.theme.Motion

@Composable
fun ChatScreen(sessionId: Long, onOpenSessions: () -> Unit, onOpenSession: (Long) -> Unit) {
    val ctx = LocalContext.current
    val db = Atlas.db
    val dbVersion by db.version.collectAsStateWithLifecycle()
    val live by RunController.live.collectAsStateWithLifecycle()
    val messages = remember(dbVersion, sessionId) { db.messages(sessionId) }
    val listState = rememberLazyListState()

    var input by rememberSaveable(sessionId) { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Attachment>() }
    val voice = remember { VoiceInput(ctx) }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var wasRunning by remember { mutableStateOf(false) }

    fun send() {
        val text = input.trim()
        if ((text.isEmpty() && attachments.isEmpty()) || RunController.isRunning()) return
        AgentService.start(ctx)
        RunController.start(sessionId, text, attachments.toList())
        input = ""
        attachments.clear()
    }

    val sharedText by ShareBus.text.collectAsStateWithLifecycle()
    val sharedImages by ShareBus.images.collectAsStateWithLifecycle()
    LaunchedEffect(sharedText) {
        sharedText?.let {
            input = if (input.isBlank()) it else "$input\n$it"
            ShareBus.text.value = null
            if (ShareBus.autoSend.value) {
                ShareBus.autoSend.value = false
                kotlinx.coroutines.delay(150)
                send()
            }
        }
    }
    LaunchedEffect(sharedImages) {
        if (sharedImages.isNotEmpty()) {
            attachments.addAll(sharedImages)
            ShareBus.images.value = emptyList()
        }
    }

    LaunchedEffect(voiceState) {
        when (val s = voiceState) {
            is VoiceInput.State.Partial -> input = s.text
            is VoiceInput.State.Final -> {
                input = s.text
                voice.reset()
                if (Atlas.settings.voiceAutoSend) send()
            }
            is VoiceInput.State.Failure -> {
                statusMessage = s.message
                voice.reset()
            }
            else -> {}
        }
    }

    LaunchedEffect(live, messages.size) {
        if (live != null) {
            wasRunning = true
        } else if (wasRunning) {
            wasRunning = false
            if (Atlas.settings.speakReplies && Atlas.settings.ttsEnabled) {
                db.messages(sessionId).lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
                    ?.let { Atlas.tts.speak(it.content.take(600)) }
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else statusMessage = "Microphone permission is needed for voice input."
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { ShareBus.copyToCache(ctx, it)?.let { a -> attachments.add(a) } }
    }

    LaunchedEffect(messages.size, live?.text) {
        val target = messages.size + if (live != null) 1 else 0
        if (target > 0) runCatching { listState.animateScrollToItem((target - 1).coerceAtLeast(0)) }
    }

    // Follow a run that was started from outside this screen (automation intent, scheduled goal)
    // so the conversation being worked on is the one you are looking at.
    LaunchedEffect(live?.sessionId) {
        val id = live?.sessionId
        if (id != null && id != sessionId) onOpenSession(id)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(10.dp)) }
            if (messages.isEmpty() && live == null) {
                item { WelcomeState(onPick = { input = it }) }
            }
            items(messages, key = { it.id }) { row ->
                Box(Modifier.animateItem()) { MessageView(row, onToolDetail = {}) }
            }
            live?.let { run ->
                item { Box(Modifier.animateItem()) { LiveRunBlock(run) } }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }

        statusMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f)) {
                    attachments.forEach { a ->
                        Text(
                            "📎 " + a.path.substringAfterLast('/').take(18),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                }
                Text(
                    "clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { attachments.clear() },
                )
            }
        }

        Composer(
            input = input,
            onInput = { input = it },
            running = RunController.isRunning(),
            listening = voiceState is VoiceInput.State.Listening,
            canSend = input.isNotBlank() || attachments.isNotEmpty(),
            onSend = { send() },
            onStop = { RunController.stop() },
            onAttach = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onMic = {
                if (voiceState is VoiceInput.State.Listening) {
                    voice.stop()
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    voice.start()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
    }
}

@Composable
private fun WelcomeState(onPick: (String) -> Unit) {
    Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) {
        Text(
            "Good to see you.",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Atlas runs on this phone — ${Atlas.tools.all().size} tools, screen control, long-term memory and schedules. Ask for something and it will go do it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        listOf(
            "What's my battery doing right now?",
            "Every morning at 8, summarise tech news",
            "Open WhatsApp and tell me what's on screen",
            "Remember that I prefer concise answers",
        ).forEach { idea ->
            Box(Modifier.padding(bottom = 8.dp)) { SuggestionChip(idea, onPick) }
        }
    }
}

@Composable
private fun LiveRunBlock(run: LiveRun) {
    Column(Modifier.fillMaxWidth()) {
        if (run.todos.isNotEmpty()) {
            WarmCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                    run.todos.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            StatusDot(
                                active = false,
                                tint = when (t.status) {
                                    "done" -> MaterialTheme.colorScheme.secondary
                                    "doing" -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                size = 6,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                t.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (t.status == "done") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        if (run.reasoning.isNotBlank()) {
            AssistantBubble(text = "", reasoning = run.reasoning, streaming = false)
        }
        if (run.text.isNotBlank()) {
            AssistantBubble(text = run.text, reasoning = null, streaming = false)
        } else {
            TypingDots()
        }
        run.toolLog.takeLast(3).forEach { line ->
            Text(
                line.take(160),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (run.status.isNotBlank()) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(active = true)
                Spacer(Modifier.width(7.dp))
                Text(run.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        run.error?.let {
            Text("error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    running: Boolean,
    listening: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), shape)
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onAttach, modifier = Modifier.padding(bottom = 4.dp)) {
                Icon(Icons.Default.AddCircle, "attach image", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
            }
            TextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Ask Atlas to do something…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onMic, modifier = Modifier.padding(bottom = 4.dp)) {
                if (listening) {
                    StatusDot(active = true, tint = MaterialTheme.colorScheme.primary, size = 9)
                } else {
                    Icon(Icons.Default.Mic, "voice", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
                }
            }
            SendButton(enabled = canSend, running = running, onClick = { if (running) onStop() else onSend() })
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, running: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (enabled || running) 1f else 0.86f, Motion.snappy, label = "sendScale")
    val bg by animateColorAsState(
        when {
            running -> MaterialTheme.colorScheme.error
            enabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "sendBg",
    )
    val tint = when {
        running -> MaterialTheme.colorScheme.onError
        enabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .padding(start = 2.dp, end = 2.dp, bottom = 6.dp, top = 6.dp)
            .size(38.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (running) Icons.Default.Stop else Icons.Default.Send,
            contentDescription = if (running) "stop" else "send",
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
