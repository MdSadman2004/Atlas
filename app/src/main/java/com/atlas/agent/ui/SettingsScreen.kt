package com.atlas.agent.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.autonomy.GoalScheduler
import com.atlas.agent.core.llm.ModelInfo
import com.atlas.agent.core.util.Util
import com.atlas.agent.ui.theme.AtlasMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    var sub by remember { mutableStateOf<String?>(null) }
    when (sub) {
        "tools" -> SubScreen("Tools", onBack = { sub = null }) { ToolsScreen() }
        "memory" -> SubScreen("Memory", onBack = { sub = null }) { MemoryScreen() }
        "skills" -> SubScreen("Skills", onBack = { sub = null }) { SkillsScreen() }
        else -> MainSettings(onOpen = { sub = it })
    }
}

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← back") }
            Text(title, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        content()
    }
}

@Composable
private fun MainSettings(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val s = Atlas.settings

    var baseUrl by remember { mutableStateOf(s.baseUrl) }
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var model by remember { mutableStateOf(s.model) }
    var fastModel by remember { mutableStateOf(s.fastModel) }
    var visionModel by remember { mutableStateOf(s.visionModel) }
    var userName by remember { mutableStateOf(s.userName) }
    var aboutUser by remember { mutableStateOf(s.aboutUser) }
    var systemExtra by remember { mutableStateOf(s.systemExtra) }
    var status by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var showModels by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val dbVersion by Atlas.db.version.collectAsStateWithLifecycle()

    LaunchedEffect(reload) {
        models = withContext(Dispatchers.IO) {
            runCatching { Atlas.llm.models() }.getOrDefault(emptyList())
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        status = result.entries.joinToString(", ") { "${it.key.substringAfterLast('.')}=${it.value}" }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SectionTitle("Connection (CommandCode)") }
        item {
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("Base URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API key") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    s.baseUrl = baseUrl
                    s.apiKey = apiKey
                    status = "saved; testing…"
                    reload++
                }) { Text("Save & test") }
                Text(
                    "${models.size} models on this key · model is locked",
                    color = AtlasMuted, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        item {
            OutlinedButton(onClick = {
                s.baseUrl = baseUrl
                s.apiKey = apiKey
                val sid = Atlas.db.createSession("self-test")
                com.atlas.agent.core.service.AgentService.start(ctx)
                com.atlas.agent.core.agent.RunController.start(
                    sid,
                    "Self-test: call the now tool, then reply with one short line confirming you are online.",
                    emptyList(),
                )
                status = "self-test running — open the Chat tab to watch"
            }) { Text("Run self-test") }
        }
        item {
            Text("model: $model  🔒 locked · reasoning_effort=high", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            Text("vision: $visionModel", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AtlasMuted)
            Text("Every turn, title, memory extraction and sub-agent runs on the locked model.", color = AtlasMuted, fontSize = 10.sp)
        }
        status?.let { st ->
            item { Text(st, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary) }
        }

        item { SectionTitle("Agent") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Approvals", fontSize = 13.sp, modifier = Modifier.weight(1f))
                listOf("manual", "smart", "off").forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.approvals == mode, onClick = { s.approvals = mode })
                        Text(mode, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Text("manual = every tool asks · smart = risky tools ask · off = no prompts (default)", color = AtlasMuted, fontSize = 11.sp)
        }
        item {
            Text("Auto-approve is on by default: Atlas finishes tasks without stopping. Switch to smart/manual above if you want a gate on risky tools.", color = AtlasMuted, fontSize = 10.sp)
        }
        item {
            IntSlider("Max steps per turn", s.maxSteps, 1f..60f) { s.maxSteps = it.toInt() }
        }
        item { IntSlider("Max tokens per reply", s.maxTokens, 512f..16000f) { s.maxTokens = it.toInt() } }
        item {
            Text("Temperature ${"%.2f".format(s.temperature)}", fontSize = 12.sp)
            Slider(value = s.temperature, onValueChange = { s.temperature = it }, valueRange = 0f..1.5f)
        }
        item { IntSlider("History window (messages)", s.historyLimit, 6f..120f) { s.historyLimit = it.toInt() } }
        item { SwitchRow("Stream replies", s.streaming) { s.streaming = it } }
        item { SwitchRow("Auto-extract memories", s.autoMemory) { s.autoMemory = it } }
        item { SwitchRow("AI-generated chat titles", s.titleModel) { s.titleModel = it } }

        item { SectionTitle("User") }
        item {
            OutlinedTextField(value = userName, onValueChange = { userName = it; s.userName = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(
                value = aboutUser, onValueChange = { aboutUser = it; s.aboutUser = it },
                label = { Text("About you (injected into every chat)") }, minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = systemExtra, onValueChange = { systemExtra = it; s.systemExtra = it },
                label = { Text("Standing instructions") }, minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item { SectionTitle("Voice") }
        item { SwitchRow("Text-to-speech available", s.ttsEnabled) { s.ttsEnabled = it } }
        item { SwitchRow("Speak replies out loud", s.speakReplies) { s.speakReplies = it } }
        item { SwitchRow("Send after voice input", s.voiceAutoSend) { s.voiceAutoSend = it } }

        item { SectionTitle("Autonomy") }
        item { SwitchRow("Autonomous goals enabled", s.autonomyEnabled) {
            s.autonomyEnabled = it
            GoalScheduler(ctx).scheduleAll()
        } }
        item { IntSlider("Check interval (min)", s.autonomyIntervalMinutes, 15f..360f) {
            s.autonomyIntervalMinutes = it.toInt()
            GoalScheduler(ctx).scheduleAll()
        } }
        item { SwitchRow("Notify when a run finishes", s.notifyOnComplete) { s.notifyOnComplete = it } }
        item {
            Row {
                OutlinedButton(onClick = { GoalScheduler(ctx).scheduleAll(); status = "autonomy scheduled" }) { Text("Reschedule now") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    runCatching {
                        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    }
                }) { Text("Battery exemption") }
            }
        }

        item { SectionTitle("Permissions & access") }
        item {
            Column {
                Row {
                    OutlinedButton(onClick = {
                        permLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.CAMERA,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        )
                    }) { Text("Grant phone permissions") }
                }
                Row(Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("Enable screen control") }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (com.atlas.agent.core.a11y.AtlasA11yService.isEnabled(ctx)) "accessibility: ON" else "accessibility: off",
                        fontSize = 11.sp,
                        color = if (com.atlas.agent.core.a11y.AtlasA11yService.isEnabled(ctx)) MaterialTheme.colorScheme.secondary else AtlasMuted,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                Text(
                    "Screen control (accessibility) lets Atlas read the screen and tap/type for you. Enable 'Atlas' in the list.",
                    color = AtlasMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item { SectionTitle("Library") }
        item {
            Row {
                OutlinedButton(onClick = { onOpen("tools") }) { Text("Tools (${Atlas.tools.all().size})") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onOpen("memory") }) { Text("Memory") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onOpen("skills") }) { Text("Skills") }
            }
        }

        item { SectionTitle("About") }
        item {
            Column {
                KeyValueLine("Version", "1.0.0 (${com.atlas.agent.BuildConfig.VERSION_CODE})")
                KeyValueLine("Tools", "${Atlas.tools.all().size} registered")
                KeyValueLine("Models", "${models.size} available on this key")
                val st = Atlas.db.stats()
                KeyValueLine("Data", "${st.first} messages · ${st.second} runs · ${st.third} tokens")
                KeyValueLine("Approvals", s.approvals)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    if (showModels) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("Models") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("filter") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val filtered = models.filter { query.isBlank() || it.id.contains(query, true) }
                    if (filtered.isEmpty()) {
                        Text("no models loaded — check the API key and tap 'Save & test'", color = AtlasMuted, fontSize = 12.sp)
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 6.dp)) {
                        items(filtered) { m ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        status = "model is hard-locked to deepseek/deepseek-v4.1-flash"
                                        showModels = false
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(m.id, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(
                                    "${m.name} · ${if (m.contextLength > 0) Util.fmtBytes(m.contextLength) + " ctx" else ""} · ${m.endpoints.joinToString(",")}",
                                    fontSize = 10.sp, color = AtlasMuted,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModels = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: $value", fontSize = 12.sp)
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

// ------------------------------------------------------------------ tools

@Composable
fun ToolsScreen() {
    var bump by remember { mutableStateOf(0) }
    val s = Atlas.settings
    val tools = remember(bump) { Atlas.tools.all() }
    val grouped = tools.groupBy { it.group }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        grouped.forEach { (group, list) ->
            item {
                Text(group.uppercase(), color = AtlasMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            }
            items(list) { t ->
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (t.dangerous) {
                                Text("risky", color = MaterialTheme.colorScheme.error, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
                            }
                            Switch(
                                checked = s.toolEnabled(t.name, t.defaultEnabled),
                                onCheckedChange = {
                                    s.setToolEnabled(t.name, it)
                                    bump++
                                },
                            )
                        }
                        Text(t.description, fontSize = 11.sp, color = AtlasMuted)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

// ------------------------------------------------------------------ memory

@Composable
fun MemoryScreen() {
    val dbVersion by Atlas.db.version.collectAsStateWithLifecycle()
    val rows = remember(dbVersion) { Atlas.memory.all(300) }
    var adding by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row {
                OutlinedButton(onClick = { adding = true }) { Text("Add fact") }
                Spacer(Modifier.width(8.dp))
                Text("${rows.size} facts · injected into every chat", color = AtlasMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        items(rows, key = { it.id }) { m ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.text, fontSize = 12.sp)
                        Text("imp ${m.importance} · used ${m.uses}×", color = AtlasMuted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { Atlas.memory.forget(m.id) }) { Text("delete") }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New memory") },
            text = {
                OutlinedTextField(
                    value = newText, onValueChange = { newText = it },
                    label = { Text("Fact about the user") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newText.isNotBlank()) {
                        Atlas.memory.add(newText.trim(), 2)
                        newText = ""
                        adding = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ skills

@Composable
fun SkillsScreen() {
    val dbVersion by Atlas.db.version.collectAsStateWithLifecycle()
    val skills = remember(dbVersion) { Atlas.skills.list() }
    var viewing by remember { mutableStateOf<String?>(null) }
    var viewingBody by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var newBody by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row {
                OutlinedButton(onClick = { creating = true }) { Text("New skill") }
                Spacer(Modifier.width(8.dp))
                Text("${skills.size} skills · loaded on demand", color = AtlasMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        items(skills, key = { it.name }) { sk ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(sk.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(sk.description, fontSize = 11.sp, color = AtlasMuted)
                    Row(Modifier.padding(top = 4.dp)) {
                        TextButton(onClick = {
                            viewing = sk.name
                            viewingBody = sk.file.readText()
                        }) { Text("view") }
                        TextButton(onClick = { Atlas.skills.delete(sk.name) }) { Text("delete") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }

    viewingBody?.let { body ->
        AlertDialog(
            onDismissRequest = { viewingBody = null; viewing = null },
            title = { Text(viewing ?: "skill") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp)) {
                    Text(body, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { viewingBody = null; viewing = null }) { Text("Close") } },
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New skill") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newDesc, onValueChange = { newDesc = it }, label = { Text("one-line description") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    OutlinedTextField(value = newBody, onValueChange = { newBody = it }, label = { Text("body (markdown steps)") }, minLines = 5, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank() && newBody.isNotBlank()) {
                        Atlas.skills.write(newName, newDesc.ifBlank { "custom skill" }, newBody)
                        newName = ""; newDesc = ""; newBody = ""
                        creating = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}
