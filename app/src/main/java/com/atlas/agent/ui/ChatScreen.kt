package com.atlas.agent.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.Attachment
import com.atlas.agent.core.agent.RunController
import com.atlas.agent.core.service.AgentService
import com.atlas.agent.core.voice.VoiceInput
import com.atlas.agent.ui.theme.AtlasAmber
import com.atlas.agent.ui.theme.AtlasMuted

@Composable
fun ChatScreen(sessionId: Long, onOpenSessions: () -> Unit) {
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

    // shared content from other apps
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

    // voice results
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

    // speak replies + track completion
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
        if (granted) voice.start() else statusMessage = "Microphone permission needed for voice input."
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { ShareBus.copyToCache(ctx, it)?.let { a -> attachments.add(a) } }
    }

    LaunchedEffect(messages.size, live?.text) {
        val target = messages.size + if (live != null) 1 else 0
        if (target > 0) runCatching { listState.animateScrollToItem((target - 1).coerceAtLeast(0)) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            if (messages.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 40.dp)) {
                        Text("Atlas", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "An autonomous agent on your phone.\nAsk it to do something — it has ${Atlas.tools.all().size} tools: apps, files, shell, web, screen control, voice, schedules, memory.",
                            color = AtlasMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            "Try: “what's my battery doing?”, “open WhatsApp and send Mom hi”, “every morning at 8 summarise tech news”.",
                            color = AtlasMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
            items(messages, key = { it.id }) { row -> MessageView(row, onToolDetail = {}) }
            live?.let { run ->
                item {
                    Column {
                        if (run.todos.isNotEmpty()) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                                Column(Modifier.padding(8.dp)) {
                                    run.todos.forEach { t ->
                                        Text(
                                            "${if (t.status == "done") "☑" else if (t.status == "doing") "▸" else "☐"} ${t.text}",
                                            fontSize = 12.sp,
                                            color = if (t.status == "done") AtlasMuted else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        AssistantBubble(
                            text = run.text,
                            reasoning = run.reasoning.ifBlank { null },
                            streaming = true,
                        )
                        run.toolLog.takeLast(3).forEach { line ->
                            Text(line, color = AtlasMuted, fontSize = 11.sp)
                        }
                        if (run.status.isNotBlank()) {
                            Text(run.status, color = AtlasAmber, fontSize = 11.sp)
                        }
                        run.error?.let { Text("error: $it", color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        statusMessage?.let { msg ->
            Text(
                msg, color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (attachments.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                attachments.forEach { a ->
                    Text(
                        "📎 ${a.path.substringAfterLast('/')}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    "clear",
                    fontSize = 11.sp,
                    color = AtlasMuted,
                    modifier = Modifier.padding(start = 4.dp).clickable { attachments.clear() }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Default.AddCircle, "attach image", tint = AtlasMuted)
            }
            IconButton(onClick = {
                if (voiceState is VoiceInput.State.Listening) voice.stop()
                else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    voice.start()
                } else {
                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }) {
                Icon(
                    Icons.Default.Mic, "voice",
                    tint = if (voiceState is VoiceInput.State.Listening) MaterialTheme.colorScheme.primary else AtlasMuted
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Atlas to do something…", fontSize = 13.sp) },
                maxLines = 6,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            if (RunController.isRunning()) {
                IconButton(onClick = { RunController.stop() }) {
                    Icon(Icons.Default.Stop, "stop", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(onClick = { send() }) {
                    Icon(Icons.Default.Send, "send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
